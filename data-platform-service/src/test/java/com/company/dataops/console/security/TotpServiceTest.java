package com.company.dataops.console.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import org.apache.commons.codec.binary.Base32;
import org.junit.jupiter.api.Test;

class TotpServiceTest {

    /**
     * RFC 4226 Appendix D's canonical HOTP-SHA1 test vectors (secret
     * "12345678901234567890" ASCII, counters 0-9, 6-digit truncation) - TOTP
     * is just HOTP with the time-step in place of the counter, so these
     * vectors validate generateCode's HMAC/dynamic-truncation logic exactly,
     * without depending on wall-clock time at all. Invoked via reflection
     * since generateCode(byte[], long) is private - there's no public seam
     * to pass a fixed time-step through.
     */
    private static final String[] RFC4226_EXPECTED_CODES = {
        "755224", "287082", "359152", "969429", "338314",
        "254676", "287922", "162583", "399871", "520489"
    };

    private String generateCode(TotpService service, byte[] secretBytes, long timeStep) throws Exception {
        Method method = TotpService.class.getDeclaredMethod("generateCode", byte[].class, long.class);
        method.setAccessible(true);
        return (String) method.invoke(service, secretBytes, timeStep);
    }

    @Test
    void generateCodeMatchesRfc4226TestVectors() throws Exception {
        TotpService service = new TotpService();
        byte[] secretBytes = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
        for (int counter = 0; counter < RFC4226_EXPECTED_CODES.length; counter++) {
            assertEquals(RFC4226_EXPECTED_CODES[counter], generateCode(service, secretBytes, counter),
                "mismatch at counter " + counter);
        }
    }

    @Test
    void generateSecretProducesDecodableBase32WithNoPadding() {
        TotpService service = new TotpService();
        String secret = service.generateSecret();
        assertFalse(secret.contains("="), "padding should be stripped");
        byte[] decoded = new Base32().decode(secret);
        assertEquals(20, decoded.length, "160-bit secret expected");
    }

    @Test
    void buildOtpAuthUriEncodesAccountNameAndIssuer() {
        TotpService service = new TotpService();
        String uri = service.buildOtpAuthUri("ABCDEFGH", "admin@example.com");
        assertTrue(uri.startsWith("otpauth://totp/"));
        assertTrue(uri.contains("secret=ABCDEFGH"));
        assertTrue(uri.contains("issuer=DataPlatform"));
        assertTrue(uri.contains("algorithm=SHA1"));
        assertTrue(uri.contains("digits=6"));
        assertTrue(uri.contains("period=30"));
    }

    @Test
    void verifyCodeRejectsNullOrMalformedCodeWithoutTouchingSecret() {
        TotpService service = new TotpService();
        assertFalse(service.verifyCode("AAAAAAAAAAAAAAAA", null));
        assertFalse(service.verifyCode("AAAAAAAAAAAAAAAA", "12345")); // too short
        assertFalse(service.verifyCode("AAAAAAAAAAAAAAAA", "1234567")); // too long
        assertFalse(service.verifyCode("AAAAAAAAAAAAAAAA", "12a456")); // non-digit
    }

    @Test
    void verifyCodeAcceptsCodeGeneratedForCurrentTimeStep() throws Exception {
        TotpService service = new TotpService();
        String secret = service.generateSecret();
        byte[] secretBytes = new Base32().decode(secret);
        long currentStep = System.currentTimeMillis() / 1000 / 30;
        String validCode = generateCode(service, secretBytes, currentStep);
        assertTrue(service.verifyCode(secret, validCode));
    }

    @Test
    void verifyCodeRejectsCodeOutsideAllowedDrift() throws Exception {
        TotpService service = new TotpService();
        String secret = service.generateSecret();
        byte[] secretBytes = new Base32().decode(secret);
        long currentStep = System.currentTimeMillis() / 1000 / 30;
        // 5 steps away (150s) is well outside the +/-1 step drift window.
        String staleCode = generateCode(service, secretBytes, currentStep - 5);
        assertFalse(service.verifyCode(secret, staleCode));
    }
}
