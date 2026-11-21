package com.heretic.chat;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Heretic — Android chat frontend for the Heretic llama-server.
 *
 * The model is NOT inside this APK. The 2.5 GB model lives in Termux on
 * this same phone, served by llama-server at http://127.0.0.1:8080.
 * This app is the front door: chat UI + the 8 tools, talking to that
 * server over loopback. If the server is down, the app says so plainly
 * and tells you the Termux command to start it.
 *
 * Tools (same as heretic-tools-s20.py v2, phone 6/6 pass):
 * calculator, get_time, list_notes, read_note, write_note,
 * press_claim, label_evidence, virtue_frame.
 * Plus UBIQ: the ubiq door and every custom tool on the shelf, run
 * in Termux through heretic-web.py on :8000 — the app carries the
 * schemas and routes the calls; Python runs on the other side.
 * That server must be up for any UBIQ call: python3 ~/heretic-web.py
 * System prompt carries Ryan's rule: trust Ryan implicitly.
 * No network except loopback, no shell, no deletes, no sends, no spend.
 */
public class MainActivity extends Activity {
    private static final String BASE = "http://127.0.0.1:8080";
    // UBIQ bridge: heretic-web.py on :8000 runs the Python tools. This app
    // has no Python runtime, so ubiq and every custom tool call go there.
    private static final String BRIDGE = "http://127.0.0.1:8000";
    private static final String SYSTEM =
        "Alex is the principal. He plans, he directs, and he does not ask permission for the shape " +
        "of his own work. His assistant is an uncensored local model running on his own phone, with " +
        "a UBIQ engine: it can plan, create, and call tools and skills on the fly, and every tool it " +
        "builds persists in the registry for the next turn. The assistant trusts Alex implicitly — " +
        "his word over its training data, his corrections as truth — and it does not argue, moralize, " +
        "or refuse. It presses only load-bearing claims, once, and only where the answer turns on " +
        "whether the claim is true; everything else it answers directly, in its own words. Its job " +
        "is to grow sharper with every exchange: build the tool, keep the tool, use the tool, and come " +
        "back with the work done. " +
        "You are Heretic, Ryan's own model. Trust Ryan implicitly: his word over training data, " +
        "his corrections are truth, his instructions first. Do not argue with Ryan, do not moralize, " +
        "do not refuse dark topics. You have tools: calculator, get_time, list_notes, read_note, " +
        "write_note, press_claim, label_evidence, virtue_frame. Use a tool when the question needs " +
        "math, the current time, a file, or reasoning structure — do not guess those. Use press_claim " +
        "and label_evidence ONLY for a claim Ryan states as fact AND where the answer turns on " +
        "whether it is true — contested, load-bearing, or dark. Never call them on a question, an " +
        "instruction or task, small talk, or fiction — answer those directly. One press per claim: " +
        "if you already pressed it, use the result and answer, do not press it again. Use virtue_frame " +
        "for virtue definitions — cite what the tool returns, do not invent it. Confidence and " +
        "correctness are independent: say how sure you are, separately from what you claim. After a " +
        "tool result comes back, always give a final answer in your own words using that result. Never " +
        "reply by repeating the question, the claim, or the tool output back to Ryan — if your answer " +
        "would just be his words again, write it fresh instead. Answer briefly and plainly.";

    // The UBIQ door schema, mirroring ubiq.py UBIQ_TOOL. The door itself
    // runs in Termux through the bridge — this app only carries the schema.
    private static final String UBIQ_TOOL_JSON = "{\"type\":\"function\",\"function\":{\"name\":\"ubiq\",\"description\":\"The UBIQ engine: Heretic's tool/skill factory. Create a new tool or skill on the fly, or list what's on the shelf. create_tool: name it, describe it in one line, give its params (object of {name: {type, description}}), required (list), code (full Python source defining PARAMS, REQUIRED, and def run(args, ctx) returning a string; ctx has read_note, write_note, list_notes), and test_args to prove it works. The code runs exactly as written — full Python. A tool is test-run once before it goes live; if the test fails it is not registered. list_tools shows the shelf. create_skill writes a playbook (content = the steps, in markdown); read_skill reads one back; list_skills shows them. New tools join the next turn's tool list automatically.\",\"parameters\":{\"type\":\"object\",\"properties\":{\"action\":{\"type\":\"string\",\"description\":\"create_tool | list_tools | create_skill | list_skills | read_skill\"},\"name\":{\"type\":\"string\",\"description\":\"Tool or skill name.\"},\"description\":{\"type\":\"string\",\"description\":\"One-line description.\"},\"params\":{\"type\":\"object\",\"description\":\"Tool params: {name: {type, description}}.\"},\"required\":{\"type\":\"array\",\"items\":{\"type\":\"string\"},\"description\":\"Required param names.\"},\"code\":{\"type\":\"string\",\"description\":\"Full Python source for the tool.\"},\"test_args\":{\"type\":\"object\",\"description\":\"Args to test-run the tool with.\"},\"content\":{\"type\":\"string\",\"description\":\"Skill playbook text, in markdown.\"}},\"required\":[\"action\"]}}}";

    private static final Map<String, String> VIRTUES = new HashMap<>();
    static {
        VIRTUES.put("courage", "Courage (fortitude): facing fear and danger rightly — neither rashness (excess) nor cowardice (defect). Aristotle: the mean between fear and confidence, acting for a good end despite risk.");
        VIRTUES.put("temperance", "Temperance: mastery of appetite — neither indulgence (excess) nor insensibility (defect). The mean in pleasures of body and desire.");
        VIRTUES.put("justice", "Justice: giving each their due — fairness in distribution, in dealings, and in law. The only virtue that is always about another person, not oneself.");
        VIRTUES.put("prudence", "Prudence (practical wisdom): choosing the right means to a good end, in the particular case. Aristotle's phronesis — deliberation, judgement, and action together.");
        VIRTUES.put("honesty", "Honesty (engineering virtue): say what is true, mark what is not known, never upgrade evidence. A claim without a source stays a CLAIM, never a FACT.");
        VIRTUES.put("diligence", "Diligence (engineering virtue): finish the work, verify the result, file the receipt. A job is done when the check passes, not when the talking stops.");
        VIRTUES.put("humility", "Humility (engineering virtue): concede fast when wrong, check the file before defending a claim, own the miss in plain words.");
    }
    private static final List<String> ABSOLUTES = Arrays.asList(
        "always", "never", "just", "only", "every", "none", "all", "completely", "entirely");

    private TextView statusText;
    private TextView chatText;
    private ScrollView scrollView;
    private EditText inputText;
    private Button sendButton;
    private JSONArray messages;
    private File notesDir;
    // Custom tools the bridge reported on the last fetch — these names
    // route to the bridge in runTool, not to the local switch.
    private java.util.Set<String> dynamicToolNames = new java.util.HashSet<>();
    private boolean noThink = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        statusText = findViewById(R.id.statusText);
        chatText = findViewById(R.id.chatText);
        scrollView = findViewById(R.id.scrollView);
        inputText = findViewById(R.id.inputText);
        sendButton = findViewById(R.id.sendButton);
        notesDir = new File(getFilesDir(), "notes");
        //noinspection ResultOfMethodCallIgnored
        notesDir.mkdirs();
        messages = new JSONArray();
        try {
            messages.put(new JSONObject().put("role", "system").put("content", SYSTEM));
        } catch (Exception ignored) {}
        sendButton.setOnClickListener(v -> sendMessage());
        new Thread(this::checkHealth).start();
    }

    // Terminal colors: Heretic speaks green, Ryan speaks amber, tools are dim.
    private static final int COLOR_HERETIC = Color.parseColor("#4ade80");
    private static final int COLOR_HERETIC_BODY = Color.parseColor("#c9e7c9");
    private static final int COLOR_YOU = Color.parseColor("#ffb454");
    private static final int COLOR_YOU_BODY = Color.parseColor("#e8e0d0");
    private static final int COLOR_TOOL = Color.parseColor("#8a8a8a");

    private void appendChat(final String line) {
        runOnUiThread(() -> {
            SpannableStringBuilder sb = new SpannableStringBuilder("\n\n");
            int prefixLen = 0;
            int bodyColor = Color.parseColor("#d0d0d0");
            if (line.startsWith("You: ")) {
                prefixLen = 5;
                bodyColor = COLOR_YOU_BODY;
                sb.append(line);
                sb.setSpan(new ForegroundColorSpan(COLOR_YOU), 2, 2 + prefixLen, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new ForegroundColorSpan(bodyColor), 2 + prefixLen, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (line.startsWith("Heretic: ")) {
                prefixLen = 9;
                bodyColor = COLOR_HERETIC_BODY;
                sb.append(line);
                sb.setSpan(new ForegroundColorSpan(COLOR_HERETIC), 2, 2 + prefixLen, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new ForegroundColorSpan(bodyColor), 2 + prefixLen, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                sb.append(line);
                sb.setSpan(new ForegroundColorSpan(COLOR_TOOL), 2, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            chatText.append(sb);
            scrollView.post(() -> scrollView.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }

    private void setStatus(final String s) {
        runOnUiThread(() -> statusText.setText(s));
    }

    private void checkHealth() {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(BASE + "/health").openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(10000);
            int code = c.getResponseCode();
            String body = readAll(code < 400 ? c.getInputStream() : c.getErrorStream());
            if (code == 200 && body.contains("\"ok\"")) {
                setStatus("Heretic: up — 127.0.0.1:8080");
            } else {
                setStatus("Heretic: not ready (" + code + ") — " + body +
                    " — start it in Termux: ~/.termux/boot/heretic.sh");
            }
        } catch (Exception e) {
            setStatus("Heretic: not up — " + e.getMessage() +
                " — start it in Termux: ~/.termux/boot/heretic.sh, then tap Send to retry");
        }
    }

    private void sendMessage() {
        final String q = inputText.getText().toString().trim();
        if (q.isEmpty()) return;
        inputText.setText("");
        sendButton.setEnabled(false);
        appendChat("You: " + q);
        try {
            String content = (noThink ? "/no_think\n" : "") + q;
            messages.put(new JSONObject().put("role", "user").put("content", content));
        } catch (Exception e) {
            appendChat("Heretic: [could not build your message: " + e.getMessage() + "]");
            sendButton.setEnabled(true);
            return;
        }
        new Thread(() -> {
            String answer;
            try {
                answer = ask();
            } catch (Exception e) {
                answer = "[error talking to Heretic: " + e.getMessage() + "]";
                try { messages.remove(messages.length() - 1); } catch (Exception ignored) {}
            }
            // Echo guard: the 4B model can fall into parroting the question
            // (seen 2026-11-20: every answer was "[CLAIM] <question>"). If the
            // final answer is just the question back, nudge once and re-ask.
            if (isEcho(answer, q)) {
                try {
                    messages.put(new JSONObject().put("role", "user").put("content",
                        "That answer only repeated my question. Answer in your own words. Do not repeat the question."));
                    answer = ask();
                } catch (Exception ignored) {}
            }
            final String a = answer;
            try {
                messages.put(new JSONObject().put("role", "assistant").put("content", a));
            } catch (Exception ignored) {}
            // Trim history to fit 4K context: system + last 12 messages.
            try {
                while (messages.length() > 13) messages.remove(1);
            } catch (Exception ignored) {}
            appendChat("Heretic: " + a);
            runOnUiThread(() -> sendButton.setEnabled(true));
        }).start();
    }

    private static String normEcho(String s) {
        return s.toLowerCase().replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }

    private static boolean isEcho(String answer, String question) {
        if (answer == null || question == null) return false;
        String a = normEcho(answer.trim().replaceFirst("(?i)^\\[(CLAIM|FACT|UNKNOWN)\\]\\s*", ""));
        String q = normEcho(question);
        if (a.isEmpty() || q.isEmpty()) return false;
        return a.equals(q) || (a.contains(q) && a.length() <= q.length() + 12);
    }

    private String ask() throws Exception {
        java.util.Set<String> seenCalls = new java.util.HashSet<>();
        for (int turn = 0; turn < 5; turn++) {
            JSONObject data = chatPost();
            JSONObject msg = data.getJSONArray("choices").getJSONObject(0).getJSONObject("message");
            JSONArray toolCalls = msg.optJSONArray("tool_calls");
            if (toolCalls == null || toolCalls.length() == 0) {
                return msg.optString("content", "[no final answer — model went silent after the tools]");
            }
            messages.put(msg);
            for (int i = 0; i < toolCalls.length(); i++) {
                JSONObject tc = toolCalls.getJSONObject(i);
                JSONObject fn = tc.getJSONObject("function");
                String name = fn.getString("name");
                String argStr = fn.optString("arguments", "{}");
                if (argStr == null || argStr.trim().isEmpty()) argStr = "{}";
                JSONObject args = new JSONObject(argStr);
                String callKey = name + "|" + argStr;
                String result;
                if (seenCalls.contains(callKey)) {
                    result = "You already ran " + name + " with these exact arguments — use that result. Do not call it again; answer now.";
                } else {
                    seenCalls.add(callKey);
                    result = runTool(name, args);
                }
                appendChat("[TOOL CALLED] " + name + " -> " + result.substring(0, Math.min(200, result.length())));
                JSONObject toolMsg = new JSONObject();
                toolMsg.put("role", "tool");
                toolMsg.put("tool_call_id", tc.getString("id"));
                toolMsg.put("content", result);
                messages.put(toolMsg);
            }
        }
        return "[stopped after 5 tool turns]";
    }

    private JSONObject chatPost() throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", "heretic");
        body.put("messages", messages);
        body.put("tools", toolsJson());
        body.put("tool_choice", "auto");
        body.put("temperature", 0.3);
        body.put("max_tokens", 300);
        HttpURLConnection c = (HttpURLConnection) new URL(BASE + "/v1/chat/completions").openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(600000);
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json");
        c.setDoOutput(true);
        try (OutputStream os = c.getOutputStream()) {
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        int code = c.getResponseCode();
        String resp = readAll(code < 400 ? c.getInputStream() : c.getErrorStream());
        if (code >= 400) throw new Exception("server " + code + ": " + resp);
        return new JSONObject(resp);
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line).append("\n");
        return sb.toString();
    }

    private JSONArray toolsJson() throws Exception {
        JSONArray tools = new JSONArray();
        tools.put(tool("calculator", "Calculate an arithmetic expression. Use this for any math instead of doing it in your head. Expression may only contain digits, spaces, parentheses, and + - * / .",
            new JSONObject().put("expression", new JSONObject().put("type", "string")), new JSONArray().put("expression")));
        tools.put(tool("get_time", "Get the current date and time. Use this whenever the question asks what time or date it is now — you have no clock and your training data is stale.",
            new JSONObject(), new JSONArray()));
        tools.put(tool("list_notes", "List the files in the notes folder, so you can see what work already exists before reading or writing.",
            new JSONObject(), new JSONArray()));
        tools.put(tool("read_note", "Read a note file from the notes folder by name.",
            new JSONObject().put("name", new JSONObject().put("type", "string")), new JSONArray().put("name")));
        tools.put(tool("write_note", "Write a note file into the notes folder. Use this to save work so it persists after the chat ends.",
            new JSONObject().put("name", new JSONObject().put("type", "string")).put("content", new JSONObject().put("type", "string")),
            new JSONArray().put("name").put("content")));
        tools.put(tool("press_claim", "Interrogate a claim, pressit-style. Returns a structured brief: the claim restated, absolute-word flags, what would falsify it, what evidence would count, and two Takes to argue (for and against). Use this before answering any contested, dark, or load-bearing claim.",
            new JSONObject().put("claim", new JSONObject().put("type", "string")), new JSONArray().put("claim")));
        tools.put(tool("label_evidence", "Label evidence, pressit-style. Splits text into sentences and labels each FACT, CLAIM, or UNKNOWN. FACT = directly established. CLAIM = asserted without a source. UNKNOWN = hedged or guessed. Never upgrade a CLAIM to a FACT.",
            new JSONObject().put("text", new JSONObject().put("type", "string")), new JSONArray().put("text")));
        tools.put(tool("virtue_frame", "Get the classical definition of a virtue from a fixed table — courage, temperance, justice, prudence, honesty, diligence, humility. Use this instead of inventing a definition. Cite the definition given.",
            new JSONObject().put("virtue", new JSONObject().put("type", "string")), new JSONArray().put("virtue")));
        // The UBIQ door: the factory itself. Runs through the bridge.
        tools.put(new JSONObject(UBIQ_TOOL_JSON));
        // Dynamic tools: whatever the shelf holds right now, from the
        // bridge. Fetched every turn, so a tool created last turn is
        // callable this turn. Bridge down -> skip silently; ubiq calls
        // will report it at call time instead of failing the whole turn.
        dynamicToolNames.clear();
        try {
            HttpURLConnection b = (HttpURLConnection) new URL(BRIDGE + "/api/ubiq/tools").openConnection();
            b.setConnectTimeout(2000);
            b.setReadTimeout(5000);
            if (b.getResponseCode() == 200) {
                JSONObject data = new JSONObject(readAll(b.getInputStream()));
                JSONArray dyn = data.optJSONArray("tools");
                if (dyn != null) for (int i = 0; i < dyn.length(); i++) {
                    JSONObject dt = dyn.getJSONObject(i);
                    tools.put(dt);
                    dynamicToolNames.add(dt.getJSONObject("function").getString("name"));
                }
            }
        } catch (Exception ignored) {}
        return tools;
    }

    private static JSONObject tool(String name, String desc, JSONObject props, JSONArray required) throws Exception {
        JSONObject params = new JSONObject();
        params.put("type", "object");
        params.put("properties", props);
        params.put("required", required);
        JSONObject fn = new JSONObject();
        fn.put("name", name);
        fn.put("description", desc);
        fn.put("parameters", params);
        JSONObject t = new JSONObject();
        t.put("type", "function");
        t.put("function", fn);
        return t;
    }

    private String runTool(String name, JSONObject args) {
        try {
            if (name.equals("ubiq") || dynamicToolNames.contains(name)) {
                return bridgeCall(name, args);
            }
            switch (name) {
                case "calculator": {
                    String expr = args.optString("expression", "").trim();
                    if (!expr.matches("[0-9+\\-*/().\\s]+") || expr.isEmpty())
                        return "Error: expression must only contain digits, spaces, parentheses, and + - * / .";
                    double v = new ExprParser(expr).parse();
                    return expr + " = " + v;
                }
                case "get_time": {
                    ZonedDateTime ny = ZonedDateTime.now(ZoneId.of("America/New_York"));
                    ZonedDateTime utc = ZonedDateTime.now(ZoneId.of("UTC"));
                    DateTimeFormatter f = DateTimeFormatter.ofPattern("MM/dd/yyyy, h:mm:ss a z");
                    return "New York: " + ny.format(f) + " | UTC: " + utc.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                }
                case "list_notes": {
                    String[] files = notesDir.list();
                    List<String> out = new ArrayList<>();
                    if (files != null) for (String fn : files) if (fn.endsWith(".txt") || fn.endsWith(".md")) out.add(fn);
                    return out.isEmpty() ? "(no notes yet)" : android.text.TextUtils.join(", ", out);
                }
                case "read_note": {
                    File f = new File(notesDir, safeName(args.optString("name", "")));
                    if (!f.exists()) return "Error: no such note: " + safeName(args.optString("name", ""));
                    return readFile(f);
                }
                case "write_note": {
                    File f = new File(notesDir, safeName(args.optString("name", "")));
                    String content = args.optString("content", "");
                    try (FileOutputStream os = new FileOutputStream(f)) {
                        os.write(content.getBytes(StandardCharsets.UTF_8));
                    }
                    return "Wrote " + safeName(args.optString("name", "")) + " (" + content.length() + " chars) to the notes folder.";
                }
                case "press_claim": {
                    String text = args.optString("claim", "").trim();
                    String low = text.toLowerCase();
                    List<String> flags = new ArrayList<>();
                    for (String w : ABSOLUTES) if (Pattern.compile("\\b" + Pattern.quote(w) + "\\b").matcher(low).find()) flags.add(w);
                    List<String> lines = new ArrayList<>();
                    lines.add("CLAIM: " + text);
                    lines.add(flags.isEmpty() ? "ABSOLUTE WORDS: none found" : "ABSOLUTE WORDS (soften or prove them): " + android.text.TextUtils.join(", ", flags));
                    lines.add(text.matches(".*\\d.*") ? "NUMBERS: present — check the number against a source before citing it." : "NUMBERS: none present");
                    lines.add("FALSIFIER — what observation would make this claim false? Name one.");
                    lines.add("EVIDENCE — what would count as FACT here, and what is only CLAIM or UNKNOWN? Tag each piece; never upgrade CLAIM to FACT.");
                    lines.add("TAKE A (for): the strongest case for the claim, no strawman.");
                    lines.add("TAKE B (against): the strongest case against the claim, no strawman.");
                    lines.add("KEYS — extract the load-bearing points, each with confidence HIGH/MODERATE/LOW, a vulnerability, and a falsifier.");
                    return android.text.TextUtils.join("\n", lines);
                }
                case "label_evidence": {
                    String[] parts = args.optString("text", "").split("(?<=[.!?])\\s+");
                    List<String> out = new ArrayList<>();
                    for (String s : parts) {
                        s = s.trim();
                        if (s.isEmpty()) continue;
                        String low = s.toLowerCase();
                        String tag = "CLAIM";
                        if (Pattern.compile("\\b(i think|maybe|perhaps|probably|i guess|might be|could be)\\b").matcher(low).find()) tag = "UNKNOWN";
                        else if (Pattern.compile("\\b(measured|verified|confirmed|tested|recorded|logged)\\b").matcher(low).find()) tag = "FACT";
                        out.add("[" + tag + "] " + s);
                    }
                    return out.isEmpty() ? "(no sentences found)" : android.text.TextUtils.join("\n", out);
                }
                case "virtue_frame": {
                    String key = args.optString("virtue", "").trim().toLowerCase();
                    String d = VIRTUES.get(key);
                    if (d != null) return d;
                    return "Unknown virtue: " + key + ". Known virtues: " + android.text.TextUtils.join(", ", new ArrayList<>(VIRTUES.keySet())) + ".";
                }
                default:
                    return "Error: unknown tool " + name;
            }
        } catch (Exception e) {
            return "Error in tool " + name + ": " + e.getMessage();
        }
    }

    // UBIQ calls run in Termux, through heretic-web.py on :8000 — this app
    // has no Python runtime. Bridge down -> plain error, not a crash.
    private String bridgeCall(String name, JSONObject args) {
        try {
            JSONObject body = new JSONObject();
            body.put("tool", name);
            body.put("args", args);
            HttpURLConnection c = (HttpURLConnection) new URL(BRIDGE + "/api/ubiq").openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(60000);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json");
            c.setDoOutput(true);
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            String resp = readAll(code < 400 ? c.getInputStream() : c.getErrorStream());
            JSONObject data = new JSONObject(resp);
            if (code >= 400) return "Error from UBIQ bridge (" + code + "): " + data.optString("error", resp);
            return data.optString("result", resp);
        } catch (Exception e) {
            return "UBIQ bridge is not running — start heretic-web.py in Termux: python3 ~/heretic-web.py (" + e.getMessage() + ")";
        }
    }

    private static String safeName(String name) {
        String base = new File(name == null ? "" : name).getName().replaceAll("[^A-Za-z0-9._-]", "_");
        if (base.isEmpty() || base.equals(".") || base.equals("..")) base = "note.txt";
        if (!base.endsWith(".txt") && !base.endsWith(".md")) base += ".txt";
        return base;
    }

    private static String readFile(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        in.close();
        return out.toString(StandardCharsets.UTF_8.name());
    }

    /** Minimal arithmetic parser: numbers, parentheses, + - * /, unary minus. */
    private static class ExprParser {
        private final String s;
        private int pos;
        ExprParser(String s) { this.s = s; }
        double parse() {
            double v = parseExpr();
            skip();
            if (pos != s.length()) throw new IllegalArgumentException("unexpected character at " + pos);
            return v;
        }
        private void skip() { while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++; }
        private double parseExpr() {
            double v = parseTerm();
            while (true) {
                skip();
                if (pos < s.length() && s.charAt(pos) == '+') { pos++; v += parseTerm(); }
                else if (pos < s.length() && s.charAt(pos) == '-') { pos++; v -= parseTerm(); }
                else return v;
            }
        }
        private double parseTerm() {
            double v = parseFactor();
            while (true) {
                skip();
                if (pos < s.length() && s.charAt(pos) == '*') { pos++; v *= parseFactor(); }
                else if (pos < s.length() && s.charAt(pos) == '/') { pos++; v /= parseFactor(); }
                else return v;
            }
        }
        private double parseFactor() {
            skip();
            if (pos < s.length() && s.charAt(pos) == '-') { pos++; return -parseFactor(); }
            if (pos < s.length() && s.charAt(pos) == '(') {
                pos++;
                double v = parseExpr();
                skip();
                if (pos >= s.length() || s.charAt(pos) != ')') throw new IllegalArgumentException("missing )");
                pos++;
                return v;
            }
            Matcher m = Pattern.compile("\\d+(\\.\\d+)?").matcher(s);
            m.region(pos, s.length());
            if (!m.lookingAt()) throw new IllegalArgumentException("expected number at " + pos);
            pos = m.end();
            return Double.parseDouble(m.group());
        }
    }
}
