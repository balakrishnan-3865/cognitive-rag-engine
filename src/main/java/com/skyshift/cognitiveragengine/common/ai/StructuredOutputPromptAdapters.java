package com.skyshift.cognitiveragengine.common.ai;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Prompt adapters for {@link ChatModelTier}s whose underlying model has no provider-native
 * structured-output support (e.g. OpenRouter's Nemotron free tier, which lacks
 * {@code response_format}/{@code structured_outputs} entirely - confirmed live via
 * OpenRouter's {@code GET /api/v1/models}). These adapters revive the pre-native-structured-
 * output approach of appending a text format instruction to the prompt.
 */
public final class StructuredOutputPromptAdapters {

    private StructuredOutputPromptAdapters() {
    }

    /**
     * Builds a {@link UnaryOperator} that appends {@link BeanOutputConverter}-generated
     * JSON format instructions to the last user message of the incoming {@link Prompt}
     * (or adds a new user message with just the instructions, if the prompt has none),
     * and returns a new {@link Prompt} built from the resulting messages with no
     * {@code ChatOptions} - deliberately dropping whatever options the incoming prompt
     * carried, since this adapter exists specifically for a tier that can't use them
     * (e.g. native-structured-output options meant for a different provider), letting
     * that tier's own bean-level {@code defaultOptions} apply instead.
     */
    public static UnaryOperator<Prompt> appendFormatInstructions(Class<?> responseType) {
        String formatInstructions = new BeanOutputConverter<>(responseType).getFormat();

        return prompt -> {
            List<Message> messages = new ArrayList<>(prompt.getInstructions());
            int lastIndex = messages.size() - 1;

            if (lastIndex >= 0 && messages.get(lastIndex) instanceof UserMessage userMessage) {
                messages.set(lastIndex, userMessage.mutate()
                        .text(userMessage.getText() + "\n\n" + formatInstructions)
                        .build());
            }
            else {
                messages.add(new UserMessage(formatInstructions));
            }

            return new Prompt(messages);
        };
    }
}
