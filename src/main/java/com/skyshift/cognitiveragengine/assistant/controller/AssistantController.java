package com.skyshift.cognitiveragengine.assistant.controller;

import com.skyshift.cognitiveragengine.assistant.model.dto.AssistantRequest;
import com.skyshift.cognitiveragengine.assistant.model.dto.AssistantResponse;
import com.skyshift.cognitiveragengine.assistant.service.AssistantService;
import com.skyshift.cognitiveragengine.user.model.AuthenticatedUser;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/v1/assistant")
public class AssistantController {

    private final AssistantService assistantService;

    public AssistantController(AssistantService assistantService) {
        this.assistantService = assistantService;
    }

    @PostMapping("/ask")
    public AssistantResponse ask(
        @Valid @RequestBody AssistantRequest request,
        @AuthenticationPrincipal AuthenticatedUser principal
    ) {
        log.info("Received assistant request: groupId={}", principal.getGroupId());
        return assistantService.ask(request.message(), principal.getGroupId(), principal.getId(), request.conversationId());
    }
}