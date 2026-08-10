package com.skyshift.cognitiveragengine.user.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

// @Getter/@Setter only (not @Data) - never auto-generate a toString()/equals() that would
// serialize passwordHash into a log line or assertion failure message.
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserEntity {
    private Long id;
    private Long groupId;
    private String username;
    private String email;
    private String passwordHash;
    private String firstName;
    private String lastName;
    private String role;
    private Boolean enabled;
    private LocalDateTime lastLoginAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
