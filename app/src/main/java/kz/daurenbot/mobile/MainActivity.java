package kz.daurenbot.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONObject;

/** Control panel for the DaurenBOT Baileys engine on the same Android phone. */
public class MainActivity extends Activity {
    private LocalControlClient control;
    private EditText token, number, nickname, prompt, time, recurrence, message, keyword, ruleReply, bossNumber, blockedNumber;
    private TextView status, detail;
    private LinearLayout root;
    private static final int INK=Color.rgb(25,38,49), MUTED=Color.rgb(90,103,113);
    private int dp(int v) {return (int)(v*getResources().getDisplayMetrics().density+0.5f);}
    private TextView label(String title,int size,boolean bold) {
        TextView v=new TextView(this);v.setText(title);v.setTextSize(size);v.setTextColor(bold?INK:MUTED);
        if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(bold?12:5);root.addView(v,lp);return v;
    }
    private EditText field(String hint,String initial, boolean multiline) {
        EditText v=new EditText(this);v.setTextSize(15);v.setHint(hint);v.setSingleLine(!multiline);
        if(multiline)v.setMinLines(2);
        v.setText(initial);root.addView(v,new LinearLayout.LayoutParams(-1,-2));return v;
    }
    private Button button(String title,Runnable onClick){
        Button b=new Button(this);b.setText(title);b.setAllCaps(false);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(50));lp.topMargin=dp(5);root.addView(b,lp);
        b.setOnClickListener(v->onClick.run());return b;
    }
    private void show(String title,JSONObject data,String error){
        if(error!=null){status.setText("Ошибка: "+error);Toast.makeText(this,error,Toast.LENGTH_LONG).show();return;}
        status.setText("Готово: "+title);
        if(data!=null){String json=data.toString();detail.setText(json.length()>3800?json.substring(0,3800)+"…":json);}
    }
    private String key(){return token.getText().toString().trim();}
    private String phone(){return number.getText().toString().trim();}
    private void saveToken(){getPreferences(MODE_PRIVATE).edit().putString("local_panel_token",key()).apply();}
    private void get(String path){saveToken();control.call(key(),path,null,(data,err)->show(path,data,err));}
    private void post(String path,JSONObject json){saveToken();control.call(key(),path,json,(data,err)->show(path,data,err));}
    private JSONObject obj(Object... args){
        JSONObject data=new JSONObject();try{for(int i=0;i<args.length;i+=2)data.put(String.valueOf(args[i]),args[i+1]);}catch(Exception ignored){}return data;
    }
    private void confirm(String title,String text,Runnable onYes){new AlertDialog.Builder(this).setTitle(title).setMessage(text).setNegativeButton("Отмена",(d,w)->{}).setPositiveButton("Подтвердить",(d,w)->onYes.run()).show();}
    private void line(){View v=new View(this);v.setBackgroundColor(Color.rgb(218,226,227));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(1));lp.topMargin=dp(16);lp.bottomMargin=dp(6);root.addView(v,lp);}
    @Override public void onCreate(Bundle state){super.onCreate(state);control=new LocalControlClient();render();}
    private void render(){
        ScrollView scrolling=new ScrollView(this);scrolling.setFillViewport(true);scrolling.setBackgroundColor(Color.rgb(250,251,251));
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(20),dp(22),dp(20),dp(40));scrolling.addView(root);
        label("DaurenBOT • Mobile Control",25,true);
        label("Полный движок WhatsApp работает в Termux на этом же телефоне, без ПК и внешнего сервера. APK — панель управления.",14,false);
        label("Подключение к движку",19,true);
        token=field("Ключ локальной панели (64 символа)",getPreferences(MODE_PRIVATE).getString("local_panel_token",""),false);
        token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        button("Проверить связь / обновить статус",()->get("/v1/status"));
        button("Установить Termux",()->startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org/packages/com.termux/"))));
        label("Ключ после запуска бота: cat ~/DaurenBOT_Android/data/control-token",12,false);
        status=label("Статус: ещё не подключено",14,true);
        detail=label("В WhatsApp «Сообщения себе»: !, !help, !s, !ч, !boss, !report",12,false);

        line();label("Экстренное управление",19,true);
        button("⏸ Остановить все автоотправки",()->post("/v1/pause",obj("paused",true)));
        button("▶ Возобновить автоответы",()->confirm("Возобновить?","Проверь список командования перед запуском.",()->post("/v1/pause",obj("paused",false))));

        line();label("Контакты и ИИ",19,true);
        number=field("Номер контакта: 77012345678","",false);
        nickname=field("Имя контакта (необязательно)","",false);
        button("Добавить личный контакт",()->post("/v1/chat",obj("number",phone(),"label",nickname.getText().toString())));
        button("Включить ИИ",()->post("/v1/ai",obj("number",phone(),"enabled",true)));
        button("Выключить ИИ",()->post("/v1/ai",obj("number",phone(),"enabled",false)));
        prompt=field("Индивидуальный стиль ответа","",true);
        button("Сохранить промпт",()->post("/v1/ai",obj("number",phone(),"prompt",prompt.getText().toString())));
        button("Включить распознавание голосовых",()->post("/v1/ai",obj("number",phone(),"voice",true)));
        button("Показать чаты",()->get("/v1/chats"));
        button("Память контакта",()->get("/v1/memory?number="+phone()));
        button("Очистить память",()->confirm("Очистить память?","Удалить историю ИИ этого контакта?",()->post("/v1/memory/clear",obj("number",phone()))));

        line();label("Защита контактов",19,true);
        bossNumber=field("Номер командования","",false);
        button("Запретить ИИ отвечать командованию",()->post("/v1/boss/add",obj("number",bossNumber.getText().toString().trim())));
        blockedNumber=field("Номер для блокировки","",false);
        button("Заблокировать номер",()->post("/v1/block/add",obj("number",blockedNumber.getText().toString().trim())));
        button("Список командования",()->get("/v1/boss"));
        button("Блокировки",()->get("/v1/blocks"));

        line();label("Расписания",19,true);
        label("Получатель — номер из раздела «Контакты». Для служебных групп нужна отдельная команда с подтверждением.",13,false);
        time=field("Время: 09:00","09:00",false);
        recurrence=field("Период: daily, weekdays, mon,fri","daily",false);
        message=field("Текст сообщения","",true);
        button("Создать расписание",()->confirm("Создать расписание?","Проверь номер и текст сообщения.",()->post("/v1/schedule",obj("number",phone(),"time",time.getText().toString(),"recurrence",recurrence.getText().toString(),"message",message.getText().toString()))));
        button("Список расписаний",()->get("/v1/schedules"));

        line();label("Правила автоответов",19,true);
        keyword=field("Ключевое слово","",false);
        ruleReply=field("Ответ","",true);
        button("Добавить правило",()->post("/v1/rule",obj("number",phone(),"keyword",keyword.getText().toString(),"reply",ruleReply.getText().toString())));
        button("Список правил",()->get("/v1/rules"));

        line();label("Доклады, черновики и статистика",19,true);
        button("История докладов",()->get("/v1/reports"));
        button("Черновики",()->get("/v1/drafts"));
        button("Статистика ИИ",()->get("/v1/usage"));
        label("Подтверждение служебного статуса: !bp или !report confirm в «Сообщениях себе». Без подтверждения доклады не отправляются.",13,false);
        label("Baileys — неофициальное подключение WhatsApp. Неизвестные @lid не должны обходить блокировку командования. Установку и работу следует проверять на отдельном аккаунте.",13,false);
        setContentView(scrolling);
    }
    @Override protected void onDestroy(){control.shutdown();super.onDestroy();}
}