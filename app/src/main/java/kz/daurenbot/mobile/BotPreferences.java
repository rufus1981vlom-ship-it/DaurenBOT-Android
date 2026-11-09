package kz.daurenbot.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class BotPreferences {
    private static final String STORE = "daurenbot_settings";
    private static final String KEY_ALIAS = "daurenbot-deepseek-key";
    private final SharedPreferences preferences;

    BotPreferences(Context context) {
        preferences = context.getSharedPreferences(STORE, Context.MODE_PRIVATE);
    }

    boolean enabled() { return preferences.getBoolean("enabled", false); }
    void setEnabled(boolean v) { preferences.edit().putBoolean("enabled", v).apply(); }
    String blockedNames() { return preferences.getString("blocked_names", ""); }
    void setBlockedNames(String v) { preferences.edit().putString("blocked_names", v).apply(); }
    long pauseUntil() { return preferences.getLong("pause_until", 0); }
    void pauseMinute() { preferences.edit().putLong("pause_until", System.currentTimeMillis() + 60000L).apply(); }
    String lastStatus() { return preferences.getString("last_status", "Не запускался"); }
    void status(String value) { preferences.edit().putString("last_status", value).apply(); }

    boolean isBlocked(String title) {
        String normalized = normalize(title);
        if (normalized.isEmpty()) return true;
        String[] lines = blockedNames().split("[,;\\n]");
        for (String line : lines) {
            String candidate = normalize(line);
            if (!candidate.isEmpty() && normalized.equals(candidate)) return true;
        }
        return false;
    }

    private String normalize(String x) { return x == null ? "" : x.trim().toLowerCase(java.util.Locale.ROOT); }

    void saveApiKey(String value) throws Exception {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException("Введите новый API-ключ");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] encrypted = cipher.doFinal(value.trim().getBytes(StandardCharsets.UTF_8));
        preferences.edit()
                .putString("key_data", Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .putString("key_iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                .apply();
    }

    String apiKey() {
        try {
            String ciphertext = preferences.getString("key_data", "");
            String iv = preferences.getString("key_iv", "");
            if (ciphertext.isEmpty() || iv.isEmpty()) return "";
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (Exception e) { return ""; }
    }

    private SecretKey getOrCreateKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(KEY_ALIAS)) return (SecretKey) ks.getKey(KEY_ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build());
        return generator.generateKey();
    }
}