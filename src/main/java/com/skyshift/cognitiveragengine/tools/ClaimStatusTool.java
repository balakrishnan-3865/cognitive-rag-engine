package com.skyshift.cognitiveragengine.tools;

import com.alibaba.cloud.ai.graph.agent.tools.ToolContextHelper;
import com.skyshift.cognitiveragengine.claims.mapper.ClaimMapper;
import com.skyshift.cognitiveragengine.claims.model.dto.ClaimSummary;
import com.skyshift.cognitiveragengine.claims.model.entity.ClaimEntity;
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

    private static final int DEFAULT_MONTHS_BACK = 6;

    private final ClaimMapper claimMapper;

    public ClaimStatusTool(ClaimMapper claimMapper) {
        this.claimMapper = claimMapper;
    }

    @Tool(description = "Get the current user's recent insurance claims, including status, amount, " +
            "service date, provider and description. Use this whenever the user asks about the status " +
            "of their claims.")
    public List<ClaimSummary> getRecentClaims(
            @ToolParam(description = "Number of months to look back for claims. Default is 6 months. Use 1 for most recent claims.", required = false) Integer monthsBack,
            ToolContext toolContext
    ) {
        Long userId = ToolContextHelper.getMetadata(toolContext, ContextKeys.USER_ID_CONTEXT_KEY, Long.class)
                .orElseThrow(() -> new IllegalStateException("userId missing from tool context"));
        Long groupId = ToolContextHelper.getMetadata(toolContext, ContextKeys.GROUP_ID_CONTEXT_KEY, Long.class)
                .orElseThrow(() -> new IllegalStateException("groupId missing from tool context"));

        int months = monthsBack != null ? monthsBack : DEFAULT_MONTHS_BACK;
        LocalDate fromDate = LocalDate.now().minusMonths(months);

        log.debug("Fetching recent claims: userId={}, groupId={}, fromDate={}", userId, groupId, fromDate);

        List<ClaimEntity> claims = claimMapper.findByUserIdAndGroupIdSince(userId, groupId, fromDate);

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