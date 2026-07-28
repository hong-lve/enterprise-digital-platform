package com.company.dataops.dataservice.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class SecretCryptoServiceTest {
    @Test
    void encryptsAndDecryptsSecretWithRandomIv() {
        SecretCryptoService service = new SecretCryptoService(
            "unit-test-master-key-at-least-thirty-two-characters"
        );
        String secret = "application-secret";
        String firstCiphertext = service.encrypt(secret);
        String secondCiphertext = service.encrypt(secret);

        assertNotEquals(secret, firstCiphertext);
        assertNotEquals(firstCiphertext, secondCiphertext);
        assertEquals(secret, service.decrypt(firstCiphertext));
        assertEquals(secret, service.decrypt(secondCiphertext));
    }
}
