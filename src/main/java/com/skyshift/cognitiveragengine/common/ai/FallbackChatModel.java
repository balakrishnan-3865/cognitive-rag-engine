package com.skyshift.cognitiveragengine.common.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

/**
 * A {@link ChatModel} decorator that tries an ordered list of {@link ChatModelTier}s,
 * falling through to the next tier on any exception. The primary tier (index 0) always
 * receives the prompt unchanged; subsequent tiers have their {@code promptAdapter}
 * applied first. If every tier throws, the last tier's exception is rethrown.
 */
@Slf4j
public class FallbackChatModel implements ChatModel {

    private final List<ChatModelTier> tiers;

    public FallbackChatModel(List<ChatModelTier> tiers) {
        this.tiers = List.copyOf(tiers);
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        RuntimeException lastException = null;

        for (int i = 0; i < tiers.size(); i++) {
            ChatModelTier tier = tiers.get(i);
            Prompt tierPrompt = i == 0 ? prompt : tier.promptAdapter().apply(prompt);

            try {
                ChatResponse response = tier.model().call(tierPrompt);
                log.info("Chat model tier '{}' succeeded", tier.name());
                return response;
            }
            catch (Exception e) {
                log.warn("Chat model tier '{}' failed: {}", tier.name(), e.getMessage());
                lastException = e instanceof RuntimeException runtimeException
                        ? runtimeException
                        : new RuntimeException(e);
            }
        }

        throw lastException;
    }
}
