package com.skyshift.cognitiveragengine.workflows.claims.controller;

import com.skyshift.cognitiveragengine.user.model.AuthenticatedUser;
import com.skyshift.cognitiveragengine.workflows.claims.model.dto.AssistantQueryRequest;
import com.skyshift.cognitiveragengine.workflows.claims.model.dto.AssistantQueryResponse;
import com.skyshift.cognitiveragengine.workflows.claims.service.ClaimsAgentOrchestratorService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/v1/claims/query")
public class ClaimsQueryController {

    private final ClaimsAgentOrchestratorService claimsAgentOrchestratorService;

    public ClaimsQueryController(ClaimsAgentOrchestratorService claimsAgentOrchestratorService) {
        this.claimsAgentOrchestratorService = claimsAgentOrchestratorService;
    }

    @PostMapping
    public AssistantQueryResponse query(
        @Valid @RequestBody AssistantQueryRequest request,
        @AuthenticationPrincipal AuthenticatedUser principal
    ) {
        log.info("Received claims query request: groupId={}", principal.getGroupId());
        return claimsAgentOrchestratorService.query(request.query(), principal.getGroupId(), principal.getId());
    }
}