package com.skyshift.cognitiveragengine.auth.validation;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordPolicyTest {

    private static final String USERNAME = "someuser";
    private static final String EMAIL = "someuser@example.com";

    @Test
    void length11_rejected() {
        assertFalse(PasswordPolicy.isValid(repeat("Aa1!", 11), USERNAME, EMAIL));
    }

    @Test
    void length12_accepted() {
        assertTrue(PasswordPolicy.isValid(repeat("Aa1!", 12), USERNAME, EMAIL));
    }

    @Test
    void length64_accepted() {
        assertTrue(PasswordPolicy.isValid(repeat("Aa1!", 64), USERNAME, EMAIL));
    }

    @Test
    void length65_rejected() {
        assertFalse(PasswordPolicy.isValid(repeat("Aa1!", 65), USERNAME, EMAIL));
    }

    @Test
    void exactlyTwoCharacterClasses_rejected() {
        // lowercase + digits only, length 16 (satisfies the length policy in isolation)
        assertFalse(PasswordPolicy.isValid("abcdefghijkl1234", USERNAME, EMAIL));
    }

    @Test
    void exactlyThreeCharacterClasses_accepted() {
        // upper + lower + digits, length 16
        assertTrue(PasswordPolicy.isValid("Abcdefghijkl1234", USERNAME, EMAIL));
    }

    @Test
    void passwordEqualToUsername_rejected() {
        String policyCompliant = "Abcdefgh1234"; // 12 chars, 3 classes, otherwise valid
        assertTrue(PasswordPolicy.isValid(policyCompliant, "otheruser", "other@example.com"));
        assertFalse(PasswordPolicy.isValid(policyCompliant, policyCompliant, "other@example.com"));
    }

    @Test
    void passwordEqualToEmailLocalPart_rejected() {
        String policyCompliant = "Abcdefgh1234";
        assertFalse(PasswordPolicy.isValid(policyCompliant, "otheruser", policyCompliant + "@example.com"));
    }

    @Test
    void utf8ByteLengthExceeds72WhileCharCountWithinBounds_rejectedDeterministically() {
        // 43 chars (within the 12-64 char-count bound), but 73 UTF-8 bytes (over the 72 limit)
        // thanks to two-byte accented characters - proves byte length is checked, not truncated.
        String password = "Abcdefgh1234!" + "é".repeat(30);
        assertEquals(43, password.length());
        assertTrue(password.getBytes(StandardCharsets.UTF_8).length > 72);

        assertFalse(PasswordPolicy.isValid(password, USERNAME, EMAIL));
    }

    @Test
    void nullPassword_rejectedWithoutException() {
        assertFalse(PasswordPolicy.isValid(null, USERNAME, EMAIL));
    }

    @Test
    void blankPassword_rejectedWithoutException() {
        assertFalse(PasswordPolicy.isValid("   ", USERNAME, EMAIL));
    }

    private static String repeat(String pattern, int length) {
        StringBuilder sb = new StringBuilder();
        while (sb.length() < length) {
            sb.append(pattern);
        }
        return sb.substring(0, length);
    }
}
