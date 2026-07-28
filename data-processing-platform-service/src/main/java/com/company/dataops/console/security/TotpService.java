package com.company.dataops.console.security;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.apache.commons.codec.binary.Base32;
import org.springframework.stereotype.Component;

/**
 * RFC 6238 TOTP (HMAC-SHA1, 30s step, 6 digits) - the same algorithm Google
 * Authenticator/Microsoft Authenticator/Authy all implement, so a secret
 * enrolled here works in any of them without this app needing to talk to an
 * external service (unlike SMS/email OTP). Cross-checked live against
 * Python's pyotp library for the same secret/timestamp to confirm this
 * produces codes a real authenticator app would accept, not just codes this
 * class's own verify() happens to agree with.
 */
@Component
public class TotpService {
    private static final int SECRET_BYTES = 20; // 160 bits - standard for HMAC-SHA1 TOTP
    private static final int TIME_STEP_SECONDS = 30;
    private static final int CODE_DIGITS = 6;
    private static final int ALLOWED_STEP_DRIFT = 1; // +/- 30s clock skew tolerance
    private static final String ISSUER = "DataPlatform";

    public String generateSecret() {
        byte[] randomBytes = new byte[SECRET_BYTES];
        new SecureRandom().nextBytes(randomBytes);
        return new Base32().encodeToString(randomBytes).replace("=", "");
    }

    public String buildOtpAuthUri(String secretBase32, String accountName) {
        String label = URLEncoder.encode(ISSUER + ":" + accountName, StandardCharsets.UTF_8).replace("+", "%20");
        String issuerParam = URLEncoder.encode(ISSUER, StandardCharsets.UTF_8);
        return "otpauth://totp/" + label + "?secret=" + secretBase32 + "&issuer=" + issuerParam + "&algorithm=SHA1&digits=" + CODE_DIGITS + "&period=" + TIME_STEP_SECONDS;
    }

    public boolean verifyCode(String secretBase32, String code) {
        if (code == null || !code.matches("[0-9]{" + CODE_DIGITS + "}")) {
            return false;
        }
        byte[] secretBytes = new Base32().decode(secretBase32);
        long currentStep = System.currentTimeMillis() / 1000 / TIME_STEP_SECONDS;
        for (long step = currentStep - ALLOWED_STEP_DRIFT; step <= currentStep + ALLOWED_STEP_DRIFT; step++) {
            if (generateCode(secretBytes, step).equals(code)) {
                return true;
            }
        }
        return false;
    }

    private String generateCode(byte[] secretBytes, long timeStep) {
        byte[] counter = new byte[8];
        for (int i = 7; i >= 0; i--) {
            counter[i] = (byte) (timeStep & 0xFF);
            timeStep >>= 8;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secretBytes, "HmacSHA1"));
            byte[] hash = mac.doFinal(counter);
            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24)
                | ((hash[offset + 1] & 0xFF) << 16)
                | ((hash[offset + 2] & 0xFF) << 8)
                | (hash[offset + 3] & 0xFF);
            int code = binary % (int) Math.pow(10, CODE_DIGITS);
            return String.format("%0" + CODE_DIGITS + "d", code);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("生成 TOTP 验证码失败", exception);
        }
    }
}
