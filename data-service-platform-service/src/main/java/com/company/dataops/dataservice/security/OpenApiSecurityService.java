package com.company.dataops.dataservice.security;

import com.company.dataops.dataservice.repository.ApplicationRepository;
import com.company.dataops.dataservice.repository.RequestSecurityRepository;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class OpenApiSecurityService {
    private static final Logger LOGGER = LoggerFactory.getLogger(OpenApiSecurityService.class);
    private final ApplicationRepository applicationRepository;
    private final RequestSecurityRepository requestSecurityRepository;
    private final SecretCryptoService cryptoService;
    private final Duration timestampSkew;
    private final Map<Long, Instant> secretUsageTouches = new ConcurrentHashMap<>();

    public OpenApiSecurityService(
        ApplicationRepository applicationRepository,
        RequestSecurityRepository requestSecurityRepository,
        SecretCryptoService cryptoService,
        @Value("${platform.data-service.security.timestamp-skew:5m}") Duration timestampSkew
    ) {
        this.applicationRepository = applicationRepository;
        this.requestSecurityRepository = requestSecurityRepository;
        this.cryptoService = cryptoService;
        this.timestampSkew = timestampSkew;
    }

    public AuthenticationResult authenticate(SignedRequest request) {
        requirePresent(request.appKey(), "缺少 X-App-Key");
        requirePresent(request.timestamp(), "缺少 X-Timestamp");
        requirePresent(request.nonce(), "缺少 X-Nonce");
        requirePresent(request.signature(), "缺少 X-Signature");
        if (!request.nonce().matches("[A-Za-z0-9_-]{16,120}")) {
            throw unauthorized("X-Nonce 格式不正确");
        }

        long timestamp = parseTimestamp(request.timestamp());
        long delta = Math.abs(Instant.now().toEpochMilli() - timestamp);
        if (delta > timestampSkew.toMillis()) {
            throw unauthorized("请求时间戳已过期");
        }

        Integer requestedVersion = parseSecretVersion(request.secretVersion());
        java.util.List<ApplicationRepository.UsableApplicationSecret> credentials =
            applicationRepository.findUsableSecrets(request.appKey(), requestedVersion);
        if (credentials.isEmpty()) {
            throw unauthorized("应用凭证无效或已过期");
        }
        ApplicationRepository.UsableApplicationSecret application = credentials.get(0);
        if (!"ENABLED".equals(application.appStatus())) {
            throw new GatewaySecurityException(HttpStatus.FORBIDDEN, "应用已停用");
        }

        String canonical = canonicalRequest(request);
        ApplicationRepository.UsableApplicationSecret matched = null;
        for (ApplicationRepository.UsableApplicationSecret candidate : credentials) {
            String secret = cryptoService.decrypt(candidate.encryptedSecret());
            String expected = hmacSha256Hex(secret, canonical);
            if (MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                request.signature().toLowerCase().getBytes(StandardCharsets.US_ASCII)
            )) {
                matched = candidate;
                break;
            }
        }
        if (matched == null) {
            throw unauthorized("请求签名不正确");
        }
        Instant nonceExpiry = Instant.ofEpochMilli(timestamp).plus(timestampSkew);
        if (!requestSecurityRepository.registerNonce(application.appKey(), request.nonce(), nonceExpiry)) {
            throw unauthorized("请求随机数已使用，疑似重放请求");
        }
        touchSecretUsage(matched.secretId());

        int qpsLimit = Math.max(1, application.qpsLimit());
        RequestSecurityRepository.RateLimitDecision rateLimit = requestSecurityRepository.acquire(
            application.appKey(),
            qpsLimit,
            Instant.now().getEpochSecond()
        );
        if (!rateLimit.allowed()) {
            throw new GatewaySecurityException(HttpStatus.TOO_MANY_REQUESTS, "应用请求频率超过限制");
        }
        return new AuthenticationResult(
            application.appId(),
            application.appKey(),
            matched.secretVersion(),
            rateLimit.limit(),
            rateLimit.remaining()
        );
    }

    public static String canonicalRequest(SignedRequest request) {
        return String.join("\n",
            request.method().toUpperCase(),
            request.path(),
            request.rawQuery() == null ? "" : request.rawQuery(),
            request.timestamp(),
            request.nonce(),
            request.bodySha256()
        );
    }

    public static String hmacSha256Hex(String secret, String canonicalRequest) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(canonicalRequest.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 不可用", exception);
        }
    }

    public static String sha256Hex(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private long parseTimestamp(String timestamp) {
        try {
            return Long.parseLong(timestamp);
        } catch (NumberFormatException exception) {
            throw unauthorized("X-Timestamp 格式不正确");
        }
    }

    private Integer parseSecretVersion(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            int version = Integer.parseInt(value);
            if (version < 1) {
                throw new NumberFormatException();
            }
            return version;
        } catch (NumberFormatException exception) {
            throw unauthorized("X-Secret-Version 格式不正确");
        }
    }

    private void touchSecretUsage(long secretId) {
        Instant now = Instant.now();
        try {
            secretUsageTouches.compute(secretId, (ignored, lastTouch) -> {
                if (lastTouch == null || lastTouch.isBefore(now.minusSeconds(60))) {
                    applicationRepository.markSecretUsed(secretId);
                    return now;
                }
                return lastTouch;
            });
        } catch (RuntimeException exception) {
            LOGGER.warn("Unable to update last-used time for application secret {}", secretId);
        }
    }

    private void requirePresent(String value, String message) {
        if (value == null || value.isBlank()) {
            throw unauthorized(message);
        }
    }

    private GatewaySecurityException unauthorized(String message) {
        return new GatewaySecurityException(HttpStatus.UNAUTHORIZED, message);
    }

    public record SignedRequest(
        String method,
        String path,
        String rawQuery,
        String timestamp,
        String nonce,
        String bodySha256,
        String appKey,
        String signature,
        String secretVersion
    ) {
    }

    public record AuthenticationResult(
        long appId,
        String appKey,
        int secretVersion,
        int qpsLimit,
        int remaining
    ) {
    }
}
