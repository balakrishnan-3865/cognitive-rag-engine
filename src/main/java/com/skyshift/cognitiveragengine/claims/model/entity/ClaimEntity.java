package com.skyshift.cognitiveragengine.claims.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ClaimEntity {
    private Long id;
    private Long userId;
    private Long groupId;
    private String policyId;
    private String claimReference;
    private String claimStatus;
    private BigDecimal claimAmount;
    private LocalDate serviceDate;
    private LocalDateTime filingDate;
    private String providerName;
    private String diagnosisCode;
    private String description;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}