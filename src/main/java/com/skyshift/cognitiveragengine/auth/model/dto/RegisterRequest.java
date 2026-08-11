package com.skyshift.cognitiveragengine.auth.model.dto;

import com.skyshift.cognitiveragengine.auth.validation.ValidPassword;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

@ValidPassword
public record RegisterRequest(
        @NotBlank(message = "Username cannot be blank")
        String username,

        @NotBlank(message = "Email cannot be blank")
        @Email(message = "Email must be a valid email address")
        String email,

        @NotBlank(message = "Password cannot be blank")
        String password,

        @NotBlank(message = "First name cannot be blank")
        String firstName,

        @NotBlank(message = "Last name cannot be blank")
        String lastName
) {}
