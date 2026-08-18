package com.skyshift.cognitiveragengine.common.ai;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.function.UnaryOperator;

/**
 * A single tier in a {@link FallbackChatModel}'s ordered chain.
 *
 * @param name          human-readable identifier for logging (e.g. "groq", "openrouter")
 * @param model         the underlying {@link ChatModel} to invoke for this tier
 * @param promptAdapter transforms the incoming {@link Prompt} before it is passed to
 *                      {@code model.call(...)}; applied only to non-primary tiers by
 *                      {@link FallbackChatModel}
 */
public record ChatModelTier(String name, ChatModel model, UnaryOperator<Prompt> promptAdapter) {

    public static ChatModelTier of(String name, ChatModel model) {
        return new ChatModelTier(name, model, UnaryOperator.identity());
    }
}
