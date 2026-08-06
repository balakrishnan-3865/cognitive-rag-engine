package com.skyshift.cognitiveragengine.tools;

import com.alibaba.cloud.ai.graph.agent.tools.ToolContextHelper;
import com.skyshift.cognitiveragengine.workflows.claims.mapper.ClaimMapper;
import com.skyshift.cognitiveragengine.workflows.claims.model.dto.ClaimSummary;
import com.skyshift.cognitiveragengine.workflows.claims.model.entity.ClaimEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * Claim lookup tool for the assistant's ReAct agent. userId and groupId are never tool arguments
 * the model can set - they are bound server-side via {@link ToolContext} (see {@link ContextKeys})
 * so a prompt cannot talk the agent into querying another user's or group's claims.
 */
@Slf4j
@Component
public class ClaimStatusTool {

    private static final int DEFAULT_RANGE_MONTHS = 1;

    private final ClaimMapper claimMapper;

    public ClaimStatusTool(ClaimMapper claimMapper) {
        this.claimMapper = claimMapper;
    }

    @Tool(description = "Get the current user's insurance claims within a date range, including status, amount, " +
            "service date, provider and description. Use this whenever the user asks about the status " +
            "of their claims.")
    public List<ClaimSummary> getClaims(
            @ToolParam(description = "Start of the date range (inclusive). Defaults to 1 month before the end date if not specified.", required = false) LocalDate startDate,
            @ToolParam(description = "End of the date range (inclusive). Defaults to today if not specified.", required = false) LocalDate endDate,
            ToolContext toolContext
    ) {
        Long userId = ToolContextHelper.getMetadata(toolContext, ContextKeys.USER_ID_CONTEXT_KEY, Long.class)
                .orElseThrow(() -> new IllegalStateException("userId missing from tool context"));
        Long groupId = ToolContextHelper.getMetadata(toolContext, ContextKeys.GROUP_ID_CONTEXT_KEY, Long.class)
                .orElseThrow(() -> new IllegalStateException("groupId missing from tool context"));

        LocalDate resolvedEndDate = endDate != null ? endDate : LocalDate.now();
        LocalDate resolvedStartDate = startDate != null ? startDate : resolvedEndDate.minusMonths(DEFAULT_RANGE_MONTHS);

        log.debug("Fetching claims: userId={}, groupId={}, startDate={}, endDate={}", userId, groupId, resolvedStartDate, resolvedEndDate);

        List<ClaimEntity> claims = claimMapper.findByUserIdAndGroupIdBetween(userId, groupId, resolvedStartDate, resolvedEndDate);

        return claims.stream()
                .map(claim -> new ClaimSummary(
                        claim.getClaimReference(),
                        claim.getClaimStatus(),
                        claim.getClaimAmount(),
                        claim.getServiceDate(),
                        claim.getProviderName(),
                        claim.getPolicyId(),
                        claim.getDescription()))
                .toList();
    }
}