package com.kashi.grc.common.controller;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

@RestController
public class RootController {

    @GetMapping("/")
    public Map<String, Object> welcome(Authentication authentication) {
        // 1. Handle Unauthenticated (Guest) users
        if (authentication == null || !authentication.isAuthenticated()) {
            return Map.of(
                    "status", "Online",
                    "message", "Welcome to Kashi GRC! Please login to continue.",
                    "role", "GUEST"
            );
        }

        // 2. Extract the primary role from authorities (looking for the ROLE_ prefix)
        String userRole = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(auth -> auth.startsWith("ROLE_"))
                .map(role -> role.replace("ROLE_", "").replace("_", " "))
                .findFirst()
                .orElse("USER");

        // 3. Return a personalized response
        return Map.of(
                "status", "Online",
                "message", "Kashi GRC API is running",
                "welcome_message", String.format("Welcome back, %s!", capitalize(userRole)),
                "role", userRole,
                "version", "v1"
        );
    }

    private String capitalize(String str) {
        if (str == null || str.isEmpty()) return str;
        return str.substring(0, 1).toUpperCase() + str.substring(1).toLowerCase();
    }
}