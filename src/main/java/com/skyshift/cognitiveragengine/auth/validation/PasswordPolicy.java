package com.skyshift.cognitiveragengine.auth.validation;

import java.nio.charset.StandardCharsets;

public final class PasswordPolicy {

    private static final int MIN_LENGTH = 12;
    private static final int MAX_LENGTH = 64;
    private static final int MAX_UTF8_BYTES = 72;
    private static final int MIN_CHARACTER_CLASSES = 3;

    private PasswordPolicy() {
    }

    public static boolean isValid(String password, String username, String email) {
        if (password == null || password.isBlank()) {
            return false;
        }
        if (password.length() < MIN_LENGTH || password.length() > MAX_LENGTH) {
            return false;
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_UTF8_BYTES) {
            return false;
        }
        if (countCharacterClasses(password) < MIN_CHARACTER_CLASSES) {
            return false;
        }
        if (password.equals(username)) {
            return false;
        }
        if (email != null && password.equals(localPart(email))) {
            return false;
        }
        return true;
    }

    private static String localPart(String email) {
        int at = email.indexOf('@');
        return at >= 0 ? email.substring(0, at) : email;
    }

    private static int countCharacterClasses(String password) {
        boolean hasUpper = false;
        boolean hasLower = false;
        boolean hasDigit = false;
        boolean hasSpecial = false;
        for (int i = 0; i < password.length(); i++) {
            char c = password.charAt(i);
            if (Character.isUpperCase(c)) {
                hasUpper = true;
            } else if (Character.isLowerCase(c)) {
                hasLower = true;
            } else if (Character.isDigit(c)) {
                hasDigit = true;
            } else {
                hasSpecial = true;
            }
        }
        int classes = 0;
        if (hasUpper) classes++;
        if (hasLower) classes++;
        if (hasDigit) classes++;
        if (hasSpecial) classes++;
        return classes;
    }
}
