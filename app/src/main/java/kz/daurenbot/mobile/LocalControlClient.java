package kz.daurenbot.mobile;

import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Only connects to the Baileys engine listening on this same phone. */
final class LocalControlClient {
    interface Result { void receive(JSONObject data, String error); }
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    void call(String token, String path, JSONObject payload, Result result) {
        if (!path.startsWith("/v1/") || path.contains("//") || path.contains("#") || path.contains(" ")) {
            result.receive(null, "Неверный путь");return;
        }
        if (!token.matches("^[0-9a-f]{64}$")) { result.receive(null, "Нужен ключ панели из Termux (64 символа)"); return; }
        io.execute(() -> {
            HttpURLConnection connection=null;
            JSONObject data=null;
            String error=null;
            try {
                connection=(HttpURLConnection)new URL("http://127.0.0.1:16619"+path).openConnection();
                connection.setRequestMethod(payload==null?"GET":"POST");
                connection.setRequestProperty("Authorization", "Bearer "+token);
                connection.setRequestProperty("Accept", "application/json");
                connection.setConnectTimeout(3000);
                connection.setReadTimeout(7000);
                connection.setUseCaches(false);
                if(payload!=null){
                    connection.setDoOutput(true);
                    connection.setRequestProperty("Content-Type","application/json");
                    try(OutputStream out=connection.getOutputStream()) {
                        out.write(payload.toString().getBytes(StandardCharsets.UTF_8));
                    }
                }
                int code=connection.getResponseCode();
                InputStream stream=code>=200&&code<300?connection.getInputStream():connection.getErrorStream();
                try(InputStream s=stream; ByteArrayOutputStream bytes=new ByteArrayOutputStream()) {
                    byte[] buffer=new byte[4096];int n;
                    while((n=s.read(buffer))!=-1){bytes.write(buffer,0,n);if(bytes.size()>120000)throw new IllegalStateException("Ответ слишком длинный");}
                    data=new JSONObject(bytes.toString("UTF-8"));
                }
                if(code<200||code>=300)error=data.optString("error","HTTP "+code);
            }catch(Exception e){error="Нет связи с локальным ботом: "+e.getClass().getSimpleName();}
            finally{if(connection!=null)connection.disconnect();}
            JSONObject output=data;String message=error;
            main.post(()->result.receive(output,message));
        });
    }
    void shutdown(){io.shutdownNow();}
}