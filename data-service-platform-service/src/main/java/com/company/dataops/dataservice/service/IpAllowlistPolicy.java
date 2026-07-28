package com.company.dataops.dataservice.service;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class IpAllowlistPolicy {
    private IpAllowlistPolicy() {
    }

    public static boolean allows(List<String> rules, String clientIp) {
        if (rules == null || rules.isEmpty()) {
            return true;
        }
        return rules.stream().anyMatch(rule -> matches(rule, clientIp));
    }

    public static List<String> normalize(List<String> rules) {
        if (rules == null) {
            return List.of();
        }
        return rules.stream()
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .distinct()
            .peek(IpAllowlistPolicy::validate)
            .toList();
    }

    private static boolean matches(String rule, String clientIp) {
        try {
            String[] parts = rule.split("/", 2);
            byte[] network = parseLiteral(parts[0]);
            byte[] address = parseLiteral(clientIp);
            if (network.length != address.length) {
                return false;
            }
            int prefix = parts.length == 1 ? network.length * 8 : Integer.parseInt(parts[1]);
            if (prefix < 0 || prefix > network.length * 8) {
                return false;
            }
            int fullBytes = prefix / 8;
            int remainingBits = prefix % 8;
            for (int index = 0; index < fullBytes; index++) {
                if (network[index] != address[index]) {
                    return false;
                }
            }
            if (remainingBits == 0) {
                return true;
            }
            int mask = 0xff << (8 - remainingBits);
            return (network[fullBytes] & mask) == (address[fullBytes] & mask);
        } catch (UnknownHostException | NumberFormatException exception) {
            return false;
        }
    }

    private static void validate(String rule) {
        String[] parts = rule.split("/", 2);
        try {
            byte[] address = parseLiteral(parts[0]);
            if (parts.length == 2) {
                int prefix = Integer.parseInt(parts[1]);
                if (prefix < 0 || prefix > address.length * 8) {
                    throw new IllegalArgumentException();
                }
            }
        } catch (UnknownHostException | IllegalArgumentException exception) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Invalid IP allowlist entry: " + rule
            );
        }
    }

    private static byte[] parseLiteral(String value) throws UnknownHostException {
        if (value == null || value.isBlank()) {
            throw new UnknownHostException("Empty address");
        }
        if (value.contains(":")) {
            if (!value.matches("[0-9A-Fa-f:.]+")) {
                throw new UnknownHostException("Not an IPv6 literal");
            }
        } else {
            if (!value.matches("\\d{1,3}(?:\\.\\d{1,3}){3}")) {
                throw new UnknownHostException("Not an IPv4 literal");
            }
            for (String part : value.split("\\.")) {
                if (Integer.parseInt(part) > 255) {
                    throw new UnknownHostException("Invalid IPv4 octet");
                }
            }
        }
        return InetAddress.getByName(value).getAddress();
    }
}
