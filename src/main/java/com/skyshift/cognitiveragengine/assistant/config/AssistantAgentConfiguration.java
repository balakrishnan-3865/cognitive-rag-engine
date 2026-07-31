package com.skyshift.cognitiveragengine.assistant.config;

import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

@Configuration
public class AssistantAgentConfiguration {

    @Bean("assistantReactInstructionTemplate")
    public PromptTemplate assistantReactInstructionTemplate() {
        return PromptTemplate.builder()
                .resource(new ClassPathResource("prompts/assistant/react-instruction.st"))
                .build();
    }
}