package kz.daurenbot.mobile;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class DeepSeekClient {
    static String reply(String key, String conversation, String incoming) throws Exception {
        if (key == null || key.isEmpty()) throw new IllegalStateException("API-ключ не настроен");
        JSONObject request = new JSONObject();
        request.put("model", "deepseek-flash");
        request.put("stream", false);
        request.put("max_tokens", 180);
        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "system").put("content",
                "Ты персональный ИИ-автоответчик в мессенджере. Отвечай на языке собеседника, коротко и естественно. " +
                "Не обещай денег, встреч, услуг или действий за владельца. Не выдавай личные или служебные сведения. " +
                "Если спрашивают, кто отвечает, сообщи, что это автоматический помощник. " +
                "Сложные, срочные и финансовые вопросы оставляй на владельца."));
        messages.put(new JSONObject().put("role", "user").put("content", incoming));
        request.put("messages", messages);
        HttpURLConnection connection = (HttpURLConnection) new URL("https://api.deepseek.com/chat/completions").openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Authorization", "Bearer " + key);
            try (OutputStream os = connection.getOutputStream()) {
                os.write(request.toString().getBytes(StandardCharsets.UTF_8));
            }
            int status = connection.getResponseCode();
            InputStream body = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
            String response = readStream(body);
            if (status < 200 || status >= 300) throw new IllegalStateException("DeepSeek HTTP " + status);
            JSONObject parsed = new JSONObject(response);
            String content = parsed.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content", "").trim();
            if (content.isEmpty()) throw new IllegalStateException("Пустой ответ модели");
            return content.length() > 500 ? content.substring(0, 500) : content;
        } finally {
            connection.disconnect();
        }
    }

    private static String readStream(InputStream in) throws Exception {
        if (in == null) return "";
        try (InputStream stream = in; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int n;
            while ((n = stream.read(buffer)) != -1) {
                output.write(buffer, 0, n);
                if (output.size() > 150000) break;
            }
            return output.toString("UTF-8");
        }
    }
}