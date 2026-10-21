package com.law.backend.auth;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtServiceTest {

    @Test
    void configuredKeyKeepsTokensValidAcrossInstances() {
        AuthProperties properties = new AuthProperties();
        properties.setJwtSecret("test_" + "configured_signing_material_".repeat(3));
        MockEnvironment environment = new MockEnvironment();
        JwtService first = new JwtService(properties, environment);
        JwtService restarted = new JwtService(properties, environment);

        String token = first.generate(42L, "test-user", "USER");

        assertEquals("42", restarted.parse(token).orElseThrow().getSubject());
        assertEquals("USER", restarted.parse(token).orElseThrow().get("role"));
    }

    @Test
    void missingKeyInDevelopmentGeneratesDistinctProcessKeys() {
        JwtService first = new JwtService(new AuthProperties(), new MockEnvironment());
        JwtService second = new JwtService(new AuthProperties(), new MockEnvironment());
        String token = first.generate(42L, "test-user", "USER");

        assertTrue(first.parse(token).isPresent());
        assertFalse(second.parse(token).isPresent());
    }

    @Test
    void productionRefusesMissingKey() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");

        assertThrows(IllegalStateException.class, () -> new JwtService(new AuthProperties(), environment));
    }

    @Test
    void productionRefusesRetiredDefaultKey() {
        AuthProperties properties = new AuthProperties();
        properties.setJwtSecret("change-me-in-production-legal-assistant-dev-secret");
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");

        assertThrows(IllegalStateException.class, () -> new JwtService(properties, environment));
    }
}
