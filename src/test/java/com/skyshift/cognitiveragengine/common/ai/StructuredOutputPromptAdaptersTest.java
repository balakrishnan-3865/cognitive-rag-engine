package com.skyshift.cognitiveragengine.common.ai;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;

import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructuredOutputPromptAdaptersTest {

    private record TestResponse(String field) {
    }

    @Test
    void appendFormatInstructions_appendsFormatToUserMessage_andDropsOptions() {
        UnaryOperator<Prompt> adapter = StructuredOutputPromptAdapters.appendFormatInstructions(TestResponse.class);
        String originalText = "classify this query";
        Prompt original = new Prompt(originalText);

        Prompt adapted = adapter.apply(original);

        String adaptedText = adapted.getInstructions().get(0).getText();
        String expectedFormat = new BeanOutputConverter<>(TestResponse.class).getFormat();

        assertTrue(adaptedText.contains(originalText));
        assertTrue(adaptedText.contains(expectedFormat));
        assertNull(adapted.getOptions());
    }
}
