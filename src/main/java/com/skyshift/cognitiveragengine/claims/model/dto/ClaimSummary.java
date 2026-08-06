package com.skyshift.cognitiveragengine.claims.model.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ClaimSummary(
        String claimReference,
        String claimStatus,
        BigDecimal claimAmount,
        LocalDate serviceDate,
        String providerName,
        String policyId,
        String description
) {
}