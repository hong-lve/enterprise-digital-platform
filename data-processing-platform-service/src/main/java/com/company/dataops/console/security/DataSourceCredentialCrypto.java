package com.company.dataops.console.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * AES-256-GCM for data_source.password / sys_user_totp.secret_encrypted at
 * rest (see DataSourceCredentialTypeHandler and AuthController's TOTP
 * secret handling) - now with key-version support (see
 * [[project_reliability_hardening_roadmap]]'s rotation item; the old
 * single-key design's own config comment used to admit "no key-versioning/
 * rotation support"). Multiple named key versions can be configured at once;
 * encrypt() always uses whichever version platform.datasource-encryption-
 * current-version names, but tryDecrypt() picks the right key by the
 * version tag embedded in the stored ciphertext - so a retired-but-still-
 * configured key version keeps decrypting rows that haven't been rotated to
 * the new version yet. Ciphertext format: "{version}:" +
 * base64(IV[12 bytes] || AES-GCM ciphertext+16-byte tag). Pre-existing
 * ciphertext from before this versioning scheme existed has no prefix at
 * all - by convention, adopting this scheme means naming that original key
 * "v1", and tryDecrypt() falls back to "v1" for any unprefixed value.
 *
 * MyBatis instantiates DataSourceCredentialTypeHandler itself via a no-arg
 * constructor (TypeHandlerRegistry reflection, not Spring's context), so the
 * keys can't be injected into the handler directly. Spring constructs its own
 * instance of this class (an ordinary @Component) purely to run the
 * constructor below and populate the static fields; every instance
 * thereafter - Spring's or MyBatis's own - reads those same static values.
 */
@Component
public class DataSourceCredentialCrypto {
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_LENGTH_BYTES = 12;
    private static final String LEGACY_FALLBACK_VERSION = "v1";
    // Base64's alphabet never contains ':', so any stored value containing
    // one is unambiguously a versioned ciphertext, never raw legacy base64.
    private static final Pattern VERSION_PREFIX = Pattern.compile("^([A-Za-z0-9_-]+):(.+)$", Pattern.DOTALL);

    private static volatile Map<String, SecretKeySpec> keysByVersion;
    private static volatile String currentVersion;

    /**
     * @param keysConfig comma-separated {@code version:base64key} pairs, e.g.
     *     {@code "v1:BASE64KEY1,v2:BASE64KEY2"} - a plain Spring property
     *     (env var backed) rather than a proper key map because this class
     *     is bootstrapped outside normal Spring DI (see class javadoc).
     */
    public DataSourceCredentialCrypto(
        @Value("${platform.datasource-encryption-keys}") String keysConfig,
        @Value("${platform.datasource-encryption-current-version}") String currentVersionConfig
    ) {
        Map<String, SecretKeySpec> parsed = new LinkedHashMap<>();
        for (String entry : keysConfig.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int separatorIndex = trimmed.indexOf(':');
            if (separatorIndex <= 0) {
                throw new IllegalStateException("platform.datasource-encryption-keys 格式错误，应为 version:base64key[,version:base64key...]：" + trimmed);
            }
            String version = trimmed.substring(0, separatorIndex);
            String base64Key = trimmed.substring(separatorIndex + 1);
            parsed.put(version, new SecretKeySpec(Base64.getDecoder().decode(base64Key), "AES"));
        }
        if (!parsed.containsKey(currentVersionConfig)) {
            throw new IllegalStateException(
                "platform.datasource-encryption-current-version=" + currentVersionConfig + " 在 datasource-encryption-keys 里没有对应的密钥");
        }
        keysByVersion = parsed;
        currentVersion = currentVersionConfig;
    }

    public static String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        String version = requireCurrentVersion();
        SecretKeySpec key = requireKeys().get(version);
        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            return version + ":" + Base64.getEncoder().encodeToString(combined);
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
        if (stored == null) {
            return null;
        }
        String version;
        String base64Blob;
        Matcher matcher = VERSION_PREFIX.matcher(stored);
        if (matcher.matches() && requireKeys().containsKey(matcher.group(1))) {
            version = matcher.group(1);
            base64Blob = matcher.group(2);
        } else if (requireKeys().containsKey(LEGACY_FALLBACK_VERSION)) {
            // No recognized version prefix - either genuinely-unmigrated
            // plaintext, or ciphertext from before this versioning scheme
            // existed (which had no prefix at all). Try the conventional
            // "v1" key as a migration-era fallback rather than giving up
            // immediately; a real decrypt failure (bad tag/wrong key) still
            // falls through to the null return below like before.
            version = LEGACY_FALLBACK_VERSION;
            base64Blob = stored;
        } else {
            return null;
        }
        try {
            byte[] combined = Base64.getDecoder().decode(base64Blob);
            if (combined.length <= IV_LENGTH_BYTES) {
                return null;
            }
            byte[] iv = new byte[IV_LENGTH_BYTES];
            System.arraycopy(combined, 0, iv, 0, iv.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, requireKeys().get(version), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] plaintext = cipher.doFinal(combined, iv.length, combined.length - iv.length);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            return null;
        }
    }

    /** For status reporting only - which key version (if any) a raw stored value is tagged with. Never attempts to decrypt. */
    public static String detectVersion(String stored) {
        if (stored == null) {
            return null;
        }
        Matcher matcher = VERSION_PREFIX.matcher(stored);
        if (matcher.matches() && requireKeys().containsKey(matcher.group(1))) {
            return matcher.group(1);
        }
        return "LEGACY";
    }

    public static String currentVersion() {
        return requireCurrentVersion();
    }

    public static Set<String> configuredVersions() {
        return requireKeys().keySet();
    }

    private static Map<String, SecretKeySpec> requireKeys() {
        Map<String, SecretKeySpec> current = keysByVersion;
        if (current == null) {
            throw new IllegalStateException("platform.datasource-encryption-keys 未配置");
        }
        return current;
    }

    private static String requireCurrentVersion() {
        String current = currentVersion;
        if (current == null) {
            throw new IllegalStateException("platform.datasource-encryption-current-version 未配置");
        }
        return current;
    }
}
