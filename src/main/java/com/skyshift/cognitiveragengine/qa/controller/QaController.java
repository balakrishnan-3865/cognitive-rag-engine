package com.skyshift.cognitiveragengine.qa.controller;

import com.skyshift.cognitiveragengine.qa.model.dto.QARequest;
import com.skyshift.cognitiveragengine.qa.model.dto.QAResponse;
import com.skyshift.cognitiveragengine.qa.service.QaService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/v1/qa")
public class QaController {

    private final QaService qaService;

    public QaController(QaService qaService) {
        this.qaService = qaService;
    }

    @PostMapping("/ask")
    public QAResponse askQuestion(@Valid @RequestBody QARequest request) {
        log.info("Received QA request: query='{}', groupId={}", request.query(), request.groupId());
        return qaService.askQuestion(request.query(), request.groupId());
    }
}