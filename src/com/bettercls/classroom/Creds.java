package com.bettercls.classroom;

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

/** The sign-in details used for auto-login, encrypted with a key that never leaves the Android Keystore. */
final class Creds {
    private static final String ALIAS = "bc_login";
    private final SharedPreferences prefs;

    Creds(Context ctx) {
        prefs = ctx.getSharedPreferences("creds", Context.MODE_PRIVATE);
    }

    boolean has() {
        return prefs.contains("email") && prefs.contains("pw");
    }

    String email() {
        return prefs.getString("email", "");
    }

    String password() {
        try {
            byte[] iv = Base64.decode(prefs.getString("iv", ""), Base64.NO_WRAP);
            byte[] data = Base64.decode(prefs.getString("pw", ""), Base64.NO_WRAP);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            return new String(c.doFinal(data), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    boolean save(String email, String password) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key());
            byte[] enc = c.doFinal(password.getBytes(StandardCharsets.UTF_8));
            prefs.edit()
                    .putString("email", email)
                    .putString("iv", Base64.encodeToString(c.getIV(), Base64.NO_WRAP))
                    .putString("pw", Base64.encodeToString(enc, Base64.NO_WRAP))
                    .apply();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    void clear() {
        prefs.edit().clear().apply();
    }

    private SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(ALIAS)) return (SecretKey) ks.getKey(ALIAS, null);
        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        g.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return g.generateKey();
    }
}
