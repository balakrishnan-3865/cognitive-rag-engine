package com.skyshift.cognitiveragengine.auth.jwt;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class RefreshTokenGeneratorTest {

    @Test
    void generate_producesUniqueValuesAcrossRepeatedCalls() {
        Set<String> generated = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            generated.add(RefreshTokenGenerator.generate());
        }

        assertEquals(100, generated.size());
    }

    @Test
    void hash_isNotEqualToRawToken() {
        String raw = RefreshTokenGenerator.generate();

        String hash = RefreshTokenGenerator.hash(raw);

        assertNotEquals(raw, hash);
    }

    @Test
    void hash_isDeterministicForSameInput() {
        String raw = RefreshTokenGenerator.generate();

        assertEquals(RefreshTokenGenerator.hash(raw), RefreshTokenGenerator.hash(raw));
    }
}
