package com.company.dataops.dataservice.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.company.dataops.dataservice.repository.ApplicationRepository;
import com.company.dataops.dataservice.repository.RequestSecurityRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class OpenApiSecurityServiceTest {
    private static final String APP_KEY = "test_application";
    private static final String SECRET = "test-secret-with-enough-entropy";

    private ApplicationRepository applicationRepository;
    private RequestSecurityRepository requestSecurityRepository;
    private SecretCryptoService cryptoService;
    private OpenApiSecurityService service;

    @BeforeEach
    void setUp() {
        applicationRepository = mock(ApplicationRepository.class);
        requestSecurityRepository = mock(RequestSecurityRepository.class);
        cryptoService = mock(SecretCryptoService.class);
        service = new OpenApiSecurityService(
            applicationRepository,
            requestSecurityRepository,
            cryptoService,
            Duration.ofMinutes(5)
        );
        when(applicationRepository.findUsableSecrets(APP_KEY, null)).thenReturn(List.of(
            new ApplicationRepository.UsableApplicationSecret(
                7L, APP_KEY, "ENABLED", 50, 10L, 1, "ciphertext"
            )
        ));
        when(cryptoService.decrypt("ciphertext")).thenReturn(SECRET);
        when(requestSecurityRepository.registerNonce(eq(APP_KEY), any(), any())).thenReturn(true);
        when(requestSecurityRepository.acquire(eq(APP_KEY), eq(50), anyLong())).thenReturn(
            new RequestSecurityRepository.RateLimitDecision(true, 50, 49)
        );
    }

    @Test
    void acceptsValidSignature() {
        OpenApiSecurityService.SignedRequest request = signedRequest(
            String.valueOf(Instant.now().toEpochMilli()),
            "nonce_1234567890123456"
        );
        OpenApiSecurityService.AuthenticationResult result = service.authenticate(request);
        assertEquals(7L, result.appId());
        assertEquals(49, result.remaining());
        assertEquals(1, result.secretVersion());
        verify(applicationRepository).markSecretUsed(10L);
    }

    @Test
    void acceptsGracePeriodSecretWithoutVersionHeader() {
        String oldSecret = "old-secret-with-enough-entropy";
        when(applicationRepository.findUsableSecrets(APP_KEY, null)).thenReturn(List.of(
            new ApplicationRepository.UsableApplicationSecret(
                7L, APP_KEY, "ENABLED", 50, 20L, 2, "new-ciphertext"
            ),
            new ApplicationRepository.UsableApplicationSecret(
                7L, APP_KEY, "ENABLED", 50, 10L, 1, "old-ciphertext"
            )
        ));
        when(cryptoService.decrypt("new-ciphertext")).thenReturn("new-secret-with-enough-entropy");
        when(cryptoService.decrypt("old-ciphertext")).thenReturn(oldSecret);
        String timestamp = String.valueOf(Instant.now().toEpochMilli());
        OpenApiSecurityService.SignedRequest unsigned = unsignedRequest(
            timestamp,
            "nonce_5234567890123456",
            null
        );
        OpenApiSecurityService.SignedRequest signed = withSignature(unsigned, oldSecret);

        OpenApiSecurityService.AuthenticationResult result = service.authenticate(signed);

        assertEquals(1, result.secretVersion());
        verify(applicationRepository).markSecretUsed(10L);
    }

    @Test
    void requestedVersionOnlyUsesThatCredential() {
        when(applicationRepository.findUsableSecrets(APP_KEY, 2)).thenReturn(List.of(
            new ApplicationRepository.UsableApplicationSecret(
                7L, APP_KEY, "ENABLED", 50, 20L, 2, "new-ciphertext"
            )
        ));
        when(cryptoService.decrypt("new-ciphertext")).thenReturn("new-secret-with-enough-entropy");
        String timestamp = String.valueOf(Instant.now().toEpochMilli());
        OpenApiSecurityService.SignedRequest unsigned = unsignedRequest(
            timestamp,
            "nonce_6234567890123456",
            "2"
        );

        assertThrows(
            GatewaySecurityException.class,
            () -> service.authenticate(withSignature(unsigned, SECRET))
        );
    }

    @Test
    void rejectsInvalidSignature() {
        OpenApiSecurityService.SignedRequest valid = signedRequest(
            String.valueOf(Instant.now().toEpochMilli()),
            "nonce_2234567890123456"
        );
        OpenApiSecurityService.SignedRequest invalid = new OpenApiSecurityService.SignedRequest(
            valid.method(), valid.path(), valid.rawQuery(), valid.timestamp(), valid.nonce(),
            valid.bodySha256(), valid.appKey(), "00".repeat(32), null
        );
        GatewaySecurityException exception = assertThrows(
            GatewaySecurityException.class,
            () -> service.authenticate(invalid)
        );
        assertEquals(HttpStatus.UNAUTHORIZED, exception.status());
    }

    @Test
    void rejectsExpiredTimestamp() {
        OpenApiSecurityService.SignedRequest request = signedRequest(
            String.valueOf(Instant.now().minus(Duration.ofMinutes(10)).toEpochMilli()),
            "nonce_3234567890123456"
        );
        assertThrows(GatewaySecurityException.class, () -> service.authenticate(request));
    }

    @Test
    void rejectsReplayedNonce() {
        when(requestSecurityRepository.registerNonce(eq(APP_KEY), any(), any())).thenReturn(false);
        OpenApiSecurityService.SignedRequest request = signedRequest(
            String.valueOf(Instant.now().toEpochMilli()),
            "nonce_4234567890123456"
        );
        GatewaySecurityException exception = assertThrows(
            GatewaySecurityException.class,
            () -> service.authenticate(request)
        );
        assertEquals(HttpStatus.UNAUTHORIZED, exception.status());
    }

    private OpenApiSecurityService.SignedRequest signedRequest(String timestamp, String nonce) {
        return withSignature(unsignedRequest(timestamp, nonce, null), SECRET);
    }

    private OpenApiSecurityService.SignedRequest unsignedRequest(
        String timestamp,
        String nonce,
        String secretVersion
    ) {
        return new OpenApiSecurityService.SignedRequest(
            "GET",
            "/openapi/governance/call-logs",
            "page=1&pageSize=20",
            timestamp,
            nonce,
            OpenApiSecurityService.sha256Hex(new byte[0]),
            APP_KEY,
            "",
            secretVersion
        );
    }

    private OpenApiSecurityService.SignedRequest withSignature(
        OpenApiSecurityService.SignedRequest unsigned,
        String secret
    ) {
        return new OpenApiSecurityService.SignedRequest(
            unsigned.method(),
            unsigned.path(),
            unsigned.rawQuery(),
            unsigned.timestamp(),
            unsigned.nonce(),
            unsigned.bodySha256(),
            unsigned.appKey(),
            OpenApiSecurityService.hmacSha256Hex(secret, OpenApiSecurityService.canonicalRequest(unsigned)),
            unsigned.secretVersion()
        );
    }
}
