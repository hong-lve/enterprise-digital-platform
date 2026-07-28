package com.company.dataops.dataservice.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class IpAllowlistPolicyTest {
    @Test
    void allowsExactAddressAndIpv4Cidr() {
        assertTrue(IpAllowlistPolicy.allows(List.of("10.20.30.40"), "10.20.30.40"));
        assertTrue(IpAllowlistPolicy.allows(List.of("10.20.0.0/16"), "10.20.99.8"));
        assertFalse(IpAllowlistPolicy.allows(List.of("10.20.0.0/16"), "10.21.0.1"));
    }

    @Test
    void supportsIpv6AndRejectsInvalidRules() {
        assertTrue(IpAllowlistPolicy.allows(List.of("2001:db8::/32"), "2001:db8::1234"));
        assertThrows(
            ResponseStatusException.class,
            () -> IpAllowlistPolicy.normalize(List.of("10.0.0.0/99"))
        );
    }

    @Test
    void emptyAllowlistMeansUnrestricted() {
        assertTrue(IpAllowlistPolicy.allows(List.of(), "203.0.113.10"));
    }
}
