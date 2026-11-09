package kz.daurenbot.v11p0;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView status;
    private EditText phoneField, destinationField, messageField;
    private String pairingCode = "";
    private boolean livePolling = false;
    private boolean statusRequestInProgress = false;
    private final Runnable statusPoller = new Runnable() {
        @Override public void run() {
            if (!livePolling) return;
            if (!statusRequestInProgress) send("status",null,null,null);
            ui.postDelayed(this,3500);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("DaurenBOT v11 — P0");
        ScrollView scroll=new ScrollView(this);
        LinearLayout content=new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int px=(int)(18*getResources().getDisplayMetrics().density);
        content.setPadding(px,px,px,px);
        scroll.addView(content);
        header(content,"DaurenBOT v11 • P0");
        label(content,"Один APK: Node.js ARM64 + Baileys внутри приложения. Termux не требуется.");
        label(content,"ЭТО ПРОТОТИП: автоответы и доклады выключены. Привязка с одного телефона ещё не проверена.");
        button(content,"1. Запустить встроенный движок",()->{
            if(Build.VERSION.SDK_INT>=33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=
                    android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},71);
            }
            try {
                Intent service=new Intent(this,EngineService.class);
                startForegroundService(service);
                toast("Запуск Node в фоне. Затем нажми «Статус».");
            }catch(Exception e){toast("Ошибка запуска: "+e.getMessage());}
        });
        phoneField=input(content,"Номер WhatsApp для привязки (международный, без +)");
        button(content,"2. Запросить код привязки",()->{
            String number=phoneField.getText().toString().trim();
            if(!number.matches("[1-9][0-9]{7,14}")) {toast("Введи 8–15 цифр без +");return;}
            send("connect",number,null,null);
        });
        button(content,"Показать статус Node / WhatsApp",()->send("status",null,null,null));
        button(content,"Диагностика сети WhatsApp",()->send("diagnose",null,null,null));
        button(content,"Повторить запрос кода (если ошибка)",()->send("retry-pairing",null,null,null));
        button(content,"Скопировать код привязки",()->{
            if(pairingCode.isEmpty()){toast("Код пока не получен. Выполни диагностику сети.");return;}
            ClipboardManager clip=getSystemService(ClipboardManager.class);
            clip.setPrimaryClip(ClipData.newPlainText("Код WhatsApp",pairingCode));
            toast("Код скопирован");
        });
        status=new TextView(this);
        status.setTextColor(Color.rgb(13,74,55));
        status.setTextSize(14);
        status.setText("Статус пока неизвестен. Запусти движок.");
        label(content,"Проверка подключения обновляется автоматически каждые 3,5 секунды. Код покажется здесь, когда WhatsApp его выдаст.");
        status.setPadding(0,px,0,px);
        content.addView(status,new LinearLayout.LayoutParams(-1,-2));
        header(content,"Тест отправки");
        label(content,"ТОЛЬКО на отдельный тестовый номер после ручного подтверждения. Подтверждение API ≠ доставка.");
        destinationField=input(content,"Тестовый номер получателя");
        messageField=input(content,"Сообщение");
        button(content,"Отправить тест (с подтверждением)",()->{
            String to=destinationField.getText().toString().trim();
            String text=messageField.getText().toString().trim();
            if(!to.matches("[1-9][0-9]{7,14}") || text.isEmpty()){toast("Проверь номер и сообщение");return;}
            new AlertDialog.Builder(this)
              .setTitle("Отправить тест?")
              .setMessage("Отправить сообщение на "+to+"? Убедись, что это твой тестовый номер.")
              .setNegativeButton("Отмена",(d,w)->{})
              .setPositiveButton("Отправить",(d,w)->send("send",null,to,text))
              .show();
        });
        header(content,"Ограничения этапа P0");
        label(content,"Нет автоматических ИИ-ответов, команд, расписаний, шифрования Baileys-сессии и подтверждённой работы в фоне 24/7. Не используй основной служебный аккаунт.");
        setContentView(scroll);
    }

    private void send(String cmd,String phone,String recipient,String text) {
        if ("status".equals(cmd)) statusRequestInProgress = true;
        JSONObject query=new JSONObject();
        try {
            query.put("cmd",cmd);
            if(phone!=null)query.put("phone",phone);
            if(recipient!=null){query.put("to",recipient);query.put("text",text);query.put("confirmed",true);}
        }catch(Exception ignored){}
        io.execute(()->{
            String info;
            try {
                File sockFile = new File(new File(getNoBackupFilesDir(),"engine-state"),"control.sock");
                try(LocalSocket sock=new LocalSocket()) {
                    sock.connect(new LocalSocketAddress(sockFile.getAbsolutePath(),
                            LocalSocketAddress.Namespace.FILESYSTEM));
                    sock.setSoTimeout(18000);
                    sock.getOutputStream().write((query.toString()+"\n").getBytes(StandardCharsets.UTF_8));
                    sock.getOutputStream().flush();
                    BufferedReader reader=new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));
                    String line=reader.readLine();
                    if(line==null)throw new IllegalStateException("No IPC response");
                    JSONObject response=new JSONObject(line);
                    if(!response.optBoolean("ok",false))throw new IllegalStateException(response.optString("error","IPC error"));
                    JSONObject data=response.optJSONObject("status");
                    if (data!=null) {
                        String code=data.optString("pairingCode","");
                        if(!code.isEmpty()) pairingCode=code;
                        String stage=data.optString("stage","неизвестно");
                        String message;
                        switch(stage){
                            case "connecting": message="Соединяемся с WhatsApp. Ожидание QR/WebSocket...";break;
                            case "requesting-pairing-code": message="WhatsApp обрабатывает запрос кода...";break;
                            case "pairing-code-issued": message="КОД ПРИВЯЗКИ: "+data.optString("pairingCode")+"\\nОткрой WhatsApp → Связанные устройства → Привязать по номеру.";break;
                            case "connected": message="WhatsApp подключён!";break;
                            case "network-timeout": message="WhatsApp не отвечает. Нажми «Диагностика сети WhatsApp».";break;
                            case "pairing-error": message="Ошибка запроса кода. Нажми «Диагностика» и «Повторить запрос кода».";break;
                            default: message="Этап подключения: "+stage;
                        }
                        info=message+"\\n\\n"+data.toString(2);
                        if(response.has("network"))info+="\\n\\nДиагностика сети:\\n"+response.getJSONObject("network").toString(2);
                    } else info=response.toString(2);
                }
            }catch(Exception e){
                info="Нет связи с движком: "+e.getClass().getSimpleName()+": "+e.getMessage();
                File fatal=new File(new File(getNoBackupFilesDir(),"engine-state"),"last-exit.txt");
                if(fatal.exists()){
                    try(FileInputStream in=new FileInputStream(fatal)){
                        byte[] bytes=new byte[512];
                        int n=in.read(bytes);
                        if(n>0)info+="\n"+new String(bytes,0,n,StandardCharsets.UTF_8);
                    }catch(Exception ignored){}
                }
            }
            String result=info;
            ui.post(()->{
                if ("status".equals(cmd)) statusRequestInProgress=false;
                status.setText(result);
            });
        });
    }

    private void header(LinearLayout parent,String text) {
        TextView t=new TextView(this);t.setText(text);t.setTextSize(22);
        t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);t.setTextColor(Color.rgb(32,45,58));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=18;parent.addView(t,p);
    }
    private void label(LinearLayout parent,String text) {
        TextView t=new TextView(this);t.setText(text);t.setTextSize(14);
        t.setTextColor(Color.rgb(76,92,108));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=8;parent.addView(t,p);
    }
    private EditText input(LinearLayout parent,String hint) {
        EditText e=new EditText(this);e.setTextSize(16);e.setHint(hint);
        e.setSingleLine(true);
        parent.addView(e,new LinearLayout.LayoutParams(-1,-2));return e;
    }
    private void button(LinearLayout parent,String text,Runnable callback) {
        Button b=new Button(this);b.setAllCaps(false);b.setText(text);
        parent.addView(b,new LinearLayout.LayoutParams(-1,-2));
        b.setOnClickListener(v->callback.run());
    }
    private void toast(String text){Toast.makeText(this,text,Toast.LENGTH_LONG).show();}
    @Override protected void onResume(){
        super.onResume();
        livePolling=true;
        ui.removeCallbacks(statusPoller);
        ui.postDelayed(statusPoller,1000);
    }
    @Override protected void onPause(){
        livePolling=false;
        ui.removeCallbacks(statusPoller);
        super.onPause();
    }
    @Override public void onDestroy(){ui.removeCallbacksAndMessages(null);io.shutdownNow();super.onDestroy();}
}
