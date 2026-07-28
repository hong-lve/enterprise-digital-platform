package com.company.dataops.dataservice.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.company.dataops.dataservice.domain.AdminSession;
import com.company.dataops.dataservice.domain.AdminUserRecord;
import com.company.dataops.dataservice.repository.AdminSecurityRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

class AdminAuthenticationServiceTest {
    private AdminSecurityRepository repository;
    private PasswordEncoder passwordEncoder;
    private SecretCryptoService cryptoService;
    private AdminAuthenticationService service;

    @BeforeEach
    void setUp() {
        repository = mock(AdminSecurityRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        cryptoService = mock(SecretCryptoService.class);
        service = new AdminAuthenticationService(
            repository,
            passwordEncoder,
            cryptoService,
            Duration.ofHours(8),
            Duration.ofMinutes(15),
            5
        );
    }

    @Test
    void createsRevocableSessionForValidCredentials() {
        AdminUserRecord user = activeUser(null);
        when(repository.findUserByUsername("admin")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("correct-password", "hash")).thenReturn(true);
        when(cryptoService.hash(any())).thenReturn("token-hash");

        AdminSession session = service.login(
            "admin", "correct-password", "127.0.0.1", "JUnit"
        );

        assertEquals("admin", session.user().username());
        assertTrue(session.expiresAt().isAfter(Instant.now().plus(Duration.ofHours(7))));
        verify(repository).recordSuccessfulLogin(1L);
        verify(repository).createSession(
            eq(1L), eq("token-hash"), any(), eq("127.0.0.1"), eq("JUnit")
        );
        verify(repository).auditLogin(1L, "admin", true, null, "127.0.0.1");
    }

    @Test
    void countsInvalidPasswordAttempts() {
        when(repository.findUserByUsername("admin")).thenReturn(Optional.of(activeUser(null)));
        when(passwordEncoder.matches("wrong", "hash")).thenReturn(false);

        ResponseStatusException exception = assertThrows(
            ResponseStatusException.class,
            () -> service.login("admin", "wrong", "127.0.0.1", "JUnit")
        );

        assertEquals(401, exception.getStatusCode().value());
        verify(repository).recordFailedAttempt(1L, 5, 900);
    }

    @Test
    void rejectsLockedAccountWithoutCheckingPassword() {
        when(repository.findUserByUsername("admin"))
            .thenReturn(Optional.of(activeUser(Instant.now().plusSeconds(60))));

        ResponseStatusException exception = assertThrows(
            ResponseStatusException.class,
            () -> service.login("admin", "correct-password", "127.0.0.1", "JUnit")
        );

        assertEquals(423, exception.getStatusCode().value());
    }

    @Test
    void authenticatesAndTouchesStoredSession() {
        AdminUserRecord user = activeUser(null);
        when(cryptoService.hash("token")).thenReturn("token-hash");
        when(repository.findActiveUserBySessionHash("token-hash")).thenReturn(Optional.of(user));

        assertEquals(Optional.of(user), service.authenticate("token"));
        verify(repository).touchSession("token-hash");
    }

    private AdminUserRecord activeUser(Instant lockedUntil) {
        return new AdminUserRecord(
            1L,
            "admin",
            "hash",
            "系统管理员",
            "ACTIVE",
            0,
            lockedUntil,
            null,
            Set.of("SUPER_ADMIN"),
            Set.of("API_MANAGE")
        );
    }
}
