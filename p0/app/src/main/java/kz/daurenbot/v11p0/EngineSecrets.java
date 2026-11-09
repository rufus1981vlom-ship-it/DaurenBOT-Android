package kz.daurenbot.v11p0;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.KeyStore;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Android Keystore-wrapped secret for Node's encrypted Baileys store.
 *
 * Keystore AES key cannot be exported. A separate 32-byte data key is generated
 * once, wrapped by that Keystore key, and given to the embedded Node process
 * only in RAM through JNI. The wrapped key lives in no_backup and cannot be
 * restored onto another device.
 */
final class EngineSecrets {
    private static final String ALIAS = "kz.daurenbot.v11p0.auth_key_v1";
    private static final String WRAPPED_FILENAME = "engine-key-v1.wrap";
    private static final int MAGIC = 0x444B4231; // DKB1
    private EngineSecrets() {}

    static String getOrCreateHex(Context context) throws Exception {
        File directory = new File(context.getNoBackupFilesDir(), "engine-state");
        if (!directory.exists() && !directory.mkdirs())
            throw new IllegalStateException("Could not create private state directory");
        File wrappedFile = new File(directory, WRAPPED_FILENAME);
        SecretKey wrappingKey = loadOrCreateWrappingKey();
        byte[] key;
        if (wrappedFile.exists()) {
            try (DataInputStream input = new DataInputStream(new FileInputStream(wrappedFile))) {
                if (input.readInt() != MAGIC) throw new IllegalStateException("Invalid wrapped key header");
                int ivSize = input.readUnsignedByte();
                if (ivSize != 12) throw new IllegalStateException("Invalid IV size");
                byte[] iv = new byte[ivSize];
                input.readFully(iv);
                int length = input.readUnsignedShort();
                if (length < 32 || length > 128) throw new IllegalStateException("Invalid ciphertext size");
                byte[] encrypted = new byte[length];
                input.readFully(encrypted);
                if (input.read() != -1) throw new IllegalStateException("Invalid wrapped key length");
                Cipher decryptor = Cipher.getInstance("AES/GCM/NoPadding");
                decryptor.init(Cipher.DECRYPT_MODE, wrappingKey, new GCMParameterSpec(128, iv));
                key = decryptor.doFinal(encrypted);
            }
        } else {
            key = new byte[32];
            new SecureRandom().nextBytes(key);
            Cipher encryptor = Cipher.getInstance("AES/GCM/NoPadding");
            encryptor.init(Cipher.ENCRYPT_MODE, wrappingKey);
            byte[] iv = encryptor.getIV();
            byte[] encrypted = encryptor.doFinal(key);
            File temp = new File(directory, WRAPPED_FILENAME + ".tmp");
            try (FileOutputStream output = new FileOutputStream(temp);
                 DataOutputStream stream = new DataOutputStream(output)) {
                stream.writeInt(MAGIC);
                stream.writeByte(iv.length);
                stream.write(iv);
                stream.writeShort(encrypted.length);
                stream.write(encrypted);
                stream.flush();
                output.getFD().sync();
            }
            if (!temp.renameTo(wrappedFile))
                throw new IllegalStateException("Could not persist wrapped key");
        }
        if (key.length != 32) throw new IllegalStateException("Incorrect decrypted data key length");
        final char[] digits = "0123456789abcdef".toCharArray();
        char[] hex = new char[64];
        for (int i=0; i<32; i++) {
            hex[2*i] = digits[(key[i] >>> 4) & 15];
            hex[2*i + 1] = digits[key[i] & 15];
            key[i] = 0;
        }
        return new String(hex);
    }

    private static SecretKey loadOrCreateWrappingKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) {
            java.security.Key key = store.getKey(ALIAS, null);
            if (!(key instanceof SecretKey)) throw new IllegalStateException("Invalid Android Keystore key");
            return (SecretKey) key;
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(
                ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }
}
