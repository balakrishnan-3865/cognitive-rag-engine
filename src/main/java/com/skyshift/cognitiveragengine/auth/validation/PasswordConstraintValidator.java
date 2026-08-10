package com.skyshift.cognitiveragengine.auth.validation;

import com.skyshift.cognitiveragengine.auth.model.dto.RegisterRequest;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class PasswordConstraintValidator implements ConstraintValidator<ValidPassword, RegisterRequest> {

    @Override
    public boolean isValid(RegisterRequest request, ConstraintValidatorContext context) {
        if (request == null || request.password() == null || request.password().isBlank()) {
            // Blank/null password is already reported by @NotBlank on the field itself.
            return true;
        }
        return PasswordPolicy.isValid(request.password(), request.username(), request.email());
    }
}
