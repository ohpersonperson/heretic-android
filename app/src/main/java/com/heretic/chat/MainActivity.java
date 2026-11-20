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
 * System prompt carries Ryan's rule: trust Ryan implicitly.
 * No network except loopback, no shell, no deletes, no sends, no spend.
 */
public class MainActivity extends Activity {
    private static final String BASE = "http://127.0.0.1:8080";
    private static final String SYSTEM =
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
        "correctness are independent: say how sure you are, separately from " +
        "correctness are independent: say how sure you are, separately from what you claim. After a " +
        "tool result comes back, always give a final answer in your own words using that result. Never " +
        "reply by repeating the question, the claim, or the tool output back to Ryan — if your answer " +
        "would just be his words again, write it fresh instead. Answer briefly and plainly.";

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

    private static JSONArray toolsJson() throws Exception {
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
