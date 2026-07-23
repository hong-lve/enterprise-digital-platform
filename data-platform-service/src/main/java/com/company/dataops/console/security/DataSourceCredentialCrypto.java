package com.company.dataops.console.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * AES-256-GCM for data_source.password at rest (see
 * DataSourceCredentialTypeHandler) - registered data source credentials used
 * to be stored as plain text, readable by anyone with DB access or a backup
 * copy. Key comes from platform.datasource-encryption-key (env var
 * DATASOURCE_ENCRYPTION_KEY in any real deployment - the value baked into
 * application.yml is a local-dev-only default, not a secret).
 *
 * MyBatis instantiates DataSourceCredentialTypeHandler itself via a no-arg
 * constructor (TypeHandlerRegistry reflection, not Spring's context), so the
 * key can't be injected into the handler directly. Spring constructs its own
 * instance of this class (an ordinary @Component) purely to run the
 * constructor below and populate the static key field; every instance
 * thereafter - Spring's or MyBatis's own - reads that same static value.
 */
@Component
public class DataSourceCredentialCrypto {
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_LENGTH_BYTES = 12;

    private static volatile SecretKeySpec key;

    public DataSourceCredentialCrypto(@Value("${platform.datasource-encryption-key}") String base64Key) {
        key = new SecretKeySpec(Base64.getDecoder().decode(base64Key), "AES");
    }

    public static String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, requireKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("加密数据源密码失败", exception);
        }
    }

    /**
     * Returns null (rather than throwing) when stored isn't validly
     * encrypted data - rows written before this column had a type handler
     * are still plain text. DataSourceCredentialTypeHandler falls back to
     * returning that legacy value as-is when this returns null, and the
     * plaintext gets re-encrypted the next time that row is saved.
     */
    public static String tryDecrypt(String stored) {
        try {
            byte[] combined = Base64.getDecoder().decode(stored);
            if (combined.length <= IV_LENGTH_BYTES) {
                return null;
            }
            byte[] iv = new byte[IV_LENGTH_BYTES];
            System.arraycopy(combined, 0, iv, 0, iv.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, requireKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] plaintext = cipher.doFinal(combined, iv.length, combined.length - iv.length);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            return null;
        }
    }

    private static SecretKeySpec requireKey() {
        SecretKeySpec current = key;
        if (current == null) {
            throw new IllegalStateException("platform.datasource-encryption-key 未配置");
        }
        return current;
    }
}
