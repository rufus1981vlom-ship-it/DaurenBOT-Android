package kz.daurenbot.mobile;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int INK = Color.rgb(29, 41, 53);
    private static final int MUTED = Color.rgb(92, 108, 122);
    private static final int GREEN = Color.rgb(16, 119, 82);
    private BotPreferences settings;
    private TextView status;
    private EditText apiKeyField;
    private EditText blockedField;
    private CheckBox autoReply;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        settings = new BotPreferences(this);
        render();
    }

    private void render() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(246, 248, 247));
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        content.setPadding(pad, dp(28), pad, dp(32));
        scroll.addView(content);
        TextView heading = addText(content, "DaurenBOT", 29, INK, true);
        heading.setPadding(0, 0, 0, dp(4));
        addText(content, "Android • AI-автоответчик", 14, MUTED, false);
        gap(content, 18);
        status = addText(content, "", 14, INK, false);
        updateStatus();
        gap(content, 20);

        addText(content, "1. Доступ к уведомлениям", 18, INK, true);
        addText(content, "Разреши доступ к уведомлениям для DaurenBOT. Автоответ сработает только при наличии у уведомления WhatsApp кнопки «Ответить».", 14, MUTED, false);
        button(content, "Открыть доступ к уведомлениям", () -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        gap(content, 20);

        addText(content, "2. DeepSeek", 18, INK, true);
        addText(content, "Ключ шифруется в Android Keystore и не показывается после сохранения.", 13, MUTED, false);
        apiKeyField = new EditText(this);
        apiKeyField.setSingleLine(true);
        apiKeyField.setHint(settings.apiKey().isEmpty() ? "Вставь новый API-ключ DeepSeek" : "Ключ сохранён — оставь пустым");
        apiKeyField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        content.addView(apiKeyField, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
        button(content, "Проверить DeepSeek", () -> {
            String entered = apiKeyField.getText().toString().trim();
            String key = entered.isEmpty() ? settings.apiKey() : entered;
            if (key.isEmpty()) { toast("Сначала укажи ключ"); return; }
            status.setText("Проверка DeepSeek...");
            worker.execute(() -> {
                try {
                    String result = DeepSeekClient.reply(key, "проверка", "Ответь одним словом: да");
                    handler.post(() -> status.setText("DeepSeek отвечает: " + result));
                } catch (Exception e) {
                    handler.post(() -> status.setText("DeepSeek: " + e.getClass().getSimpleName() + ". Проверь сеть и ключ."));
                }
            });
        });
        gap(content, 20);

        addText(content, "3. Автоматические ответы", 18, INK, true);
        autoReply = new CheckBox(this);
        autoReply.setText("Включить ИИ-автоответ на личные уведомления");
        autoReply.setTextColor(INK);
        autoReply.setChecked(settings.enabled());
        content.addView(autoReply);
        addText(content, "Уведомления не гарантируют точного определения номера, группы или @lid. Не включай для служебного аккаунта без проверки.", 13, MUTED, false);
        gap(content, 15);

        addText(content, "4. Исключения", 18, INK, true);
        addText(content, "Отображаемые имена контактов, которым запрещено отвечать. Одно имя в строке.", 13, MUTED, false);
        blockedField = new EditText(this);
        blockedField.setHint("Имя контакта 1\nИмя контакта 2");
        blockedField.setGravity(Gravity.TOP);
        blockedField.setMinLines(4);
        blockedField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        blockedField.setText(settings.blockedNames());
        content.addView(blockedField, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(120)));
        gap(content, 10);
        button(content, "Сохранить настройки", () -> {
            try {
                String entered = apiKeyField.getText().toString().trim();
                if (!entered.isEmpty()) settings.saveApiKey(entered);
                settings.setBlockedNames(blockedField.getText().toString());
                boolean turnOn = autoReply.isChecked();
                if (turnOn && settings.apiKey().isEmpty()) {
                    toast("Укажи ключ DeepSeek перед включением");
                    settings.setEnabled(false);
                    autoReply.setChecked(false);
                } else {
                    settings.setEnabled(turnOn);
                    toast("Сохранено");
                }
                apiKeyField.setText("");
                apiKeyField.setHint("Ключ сохранён — оставь пустым");
                updateStatus();
            } catch (Exception e) { toast("Ошибка сохранения: " + e.getClass().getSimpleName()); }
        });
        button(content, "Пауза на 1 минуту", () -> {
            settings.pauseMinute();
            updateStatus();
            toast("Автоответы приостановлены на 60 секунд");
        });
        gap(content, 20);
        addText(content, "Ограничения версии", 18, INK, true);
        addText(content,
                "Это экспериментальный автоответчик по уведомлениям, а не полный Baileys-клиент. " +
                "Нет списка чатов, расписаний, голосовых, команд WhatsApp и определения ручного ответа. " +
                "По уведомлению нельзя достоверно проверить @lid или номер руководителя. " +
                "Текст уведомления передаётся в DeepSeek. Проверяй только на тестовом аккаунте.",
                13, MUTED, false);
        setContentView(scroll);
    }

    private void updateStatus() {
        if (status == null) return;
        String state = settings.enabled() ? "Автоответ: ВКЛ" : "Автоответ: ВЫКЛ";
        if (settings.pauseUntil() > System.currentTimeMillis()) state += " • пауза";
        status.setText(state + "\n" + settings.lastStatus());
    }

    private TextView addText(LinearLayout parent, String s, int sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(s);
        view.setTextColor(color);
        view.setTextSize(sp);
        view.setLineSpacing(dp(2), 1f);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        parent.addView(view, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return view;
    }

    private void button(LinearLayout parent, String title, Runnable onClick) {
        Button button = new Button(this);
        button.setText(title);
        button.setTextColor(GREEN);
        button.setAllCaps(false);
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        layout.topMargin = dp(8);
        parent.addView(button, layout);
        button.setOnClickListener(v -> onClick.run());
    }

    private void gap(LinearLayout parent, int size) {
        View v = new View(this);
        parent.addView(v, new LinearLayout.LayoutParams(1, dp(size)));
    }

    private int dp(int px) { return (int)(px * getResources().getDisplayMetrics().density + 0.5f); }
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
    @Override protected void onResume() { super.onResume(); updateStatus(); }
    @Override protected void onDestroy() { worker.shutdownNow(); super.onDestroy(); }
}
