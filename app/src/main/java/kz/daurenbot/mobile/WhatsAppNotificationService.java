package kz.daurenbot.mobile;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Intent;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class WhatsAppNotificationService extends NotificationListenerService {
    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(2);
    private final ConcurrentHashMap<String, Long> lastFingerprint = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> versions = new ConcurrentHashMap<>();
    private BotPreferences prefs;

    @Override public void onCreate() {
        super.onCreate();
        prefs = new BotPreferences(this);
    }

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        String pkg = sbn.getPackageName();
        if (!"com.whatsapp".equals(pkg) && !"com.whatsapp.w4b".equals(pkg)) return;
        Notification n = sbn.getNotification();
        if (n == null || n.extras == null) return;
        Bundle extras = n.extras;
        CharSequence title = extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence text = extras.getCharSequence(Notification.EXTRA_TEXT);
        if (title == null || text == null) return;
        String chat = title.toString().trim();
        String message = text.toString().trim();
        if (message.isEmpty() || message.length() > 3200) return;
        // Fail closed for known group indicators, although Android notification metadata can be incomplete.
        boolean group = extras.getBoolean("android.isGroupConversation", false)
                || extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE) != null;
        if (group || chat.isEmpty() || chat.toLowerCase(Locale.ROOT).contains("сообщений от")) return;
        Notification.Action action = findReplyAction(n);
        if (action == null) return;
        if (!prefs.enabled() || prefs.pauseUntil() > System.currentTimeMillis() || prefs.isBlocked(chat)) return;
        if (prefs.apiKey().isEmpty()) return;
        String fingerprint = sbn.getKey() + "/" + message.hashCode();
        long now = System.currentTimeMillis();
        Long previous = lastFingerprint.put(fingerprint, now);
        if (previous != null && now - previous < 120000) return;
        if (lastFingerprint.size() > 1000) lastFingerprint.clear();
        AtomicLong version = versions.computeIfAbsent(chat, k -> new AtomicLong());
        long requested = version.incrementAndGet();
        executor.schedule(() -> {
            if (version.get() != requested || !prefs.enabled() || prefs.pauseUntil() > System.currentTimeMillis() || prefs.isBlocked(chat)) return;
            try {
                String answer = DeepSeekClient.reply(prefs.apiKey(), chat, message);
                if (version.get() != requested || !prefs.enabled() || prefs.pauseUntil() > System.currentTimeMillis() || prefs.isBlocked(chat)) return;
                // Send only through WhatsApp's inline reply action exposed in this notification.
                Notification.Action currentAction = findReplyAction(n);
                if (currentAction == null) return;
                sendReply(currentAction, answer);
                prefs.status("Отправлен запрос ответа: " + chat);
            } catch (Exception e) {
                prefs.status("Ошибка автоответа: " + e.getClass().getSimpleName());
            }
        }, 2300, TimeUnit.MILLISECONDS);
    }

    private Notification.Action findReplyAction(Notification notification) {
        if (notification.actions == null) return null;
        for (Notification.Action action : notification.actions) {
            if (action == null || action.actionIntent == null) continue;
            RemoteInput[] remoteInputs = action.getRemoteInputs();
            if (remoteInputs != null && remoteInputs.length > 0) return action;
        }
        return null;
    }

    private void sendReply(Notification.Action action, String content) throws PendingIntent.CanceledException {
        RemoteInput[] remoteInputs = action.getRemoteInputs();
        if (remoteInputs == null || remoteInputs.length == 0) return;
        Intent fillIn = new Intent();
        Bundle results = new Bundle();
        for (RemoteInput input : remoteInputs) results.putCharSequence(input.getResultKey(), content);
        RemoteInput.addResultsToIntent(remoteInputs, fillIn, results);
        action.actionIntent.send(this, 0, fillIn);
    }

    @Override public void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}