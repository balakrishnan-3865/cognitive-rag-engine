package com.skyshift.cognitiveragengine.common.ai;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FallbackChatModelTest {

    private static ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    @Test
    void primaryTierSucceeds_returnsItsResponse_noOtherTierInvoked() {
        ChatModel primary = mock(ChatModel.class);
        ChatModel fallback1 = mock(ChatModel.class);
        ChatModel fallback2 = mock(ChatModel.class);
        ChatResponse primaryResponse = response("primary");
        when(primary.call(any(Prompt.class))).thenReturn(primaryResponse);

        FallbackChatModel fallbackChatModel = new FallbackChatModel(List.of(
                ChatModelTier.of("primary", primary),
                ChatModelTier.of("fallback1", fallback1),
                ChatModelTier.of("fallback2", fallback2)));

        ChatResponse result = fallbackChatModel.call(new Prompt("hi"));

        assertSame(primaryResponse, result);
        verifyNoInteractions(fallback1);
        verifyNoInteractions(fallback2);
    }

    @Test
    void primaryThrows_tier2Invoked_returnsItsResponse() {
        ChatModel primary = mock(ChatModel.class);
        ChatModel fallback1 = mock(ChatModel.class);
        ChatModel fallback2 = mock(ChatModel.class);
        when(primary.call(any(Prompt.class))).thenThrow(new RuntimeException("primary down"));
        ChatResponse fallback1Response = response("fallback1");
        when(fallback1.call(any(Prompt.class))).thenReturn(fallback1Response);

        FallbackChatModel fallbackChatModel = new FallbackChatModel(List.of(
                ChatModelTier.of("primary", primary),
                ChatModelTier.of("fallback1", fallback1),
                ChatModelTier.of("fallback2", fallback2)));

        ChatResponse result = fallbackChatModel.call(new Prompt("hi"));

        assertSame(fallback1Response, result);
        verify(fallback2, never()).call(any(Prompt.class));
    }

    @Test
    void primaryAndTier2Throw_tier3Invoked_returnsItsResponse() {
        ChatModel primary = mock(ChatModel.class);
        ChatModel fallback1 = mock(ChatModel.class);
        ChatModel fallback2 = mock(ChatModel.class);
        when(primary.call(any(Prompt.class))).thenThrow(new RuntimeException("primary down"));
        when(fallback1.call(any(Prompt.class))).thenThrow(new RuntimeException("fallback1 down"));
        ChatResponse fallback2Response = response("fallback2");
        when(fallback2.call(any(Prompt.class))).thenReturn(fallback2Response);

        FallbackChatModel fallbackChatModel = new FallbackChatModel(List.of(
                ChatModelTier.of("primary", primary),
                ChatModelTier.of("fallback1", fallback1),
                ChatModelTier.of("fallback2", fallback2)));

        ChatResponse result = fallbackChatModel.call(new Prompt("hi"));

        assertSame(fallback2Response, result);
    }

    @Test
    void allTiersThrow_rethrowsLastTiersException() {
        ChatModel primary = mock(ChatModel.class);
        ChatModel fallback1 = mock(ChatModel.class);
        ChatModel fallback2 = mock(ChatModel.class);
        when(primary.call(any(Prompt.class))).thenThrow(new RuntimeException("primary down"));
        when(fallback1.call(any(Prompt.class))).thenThrow(new RuntimeException("fallback1 down"));
        RuntimeException tier3Exception = new RuntimeException("fallback2 down");
        when(fallback2.call(any(Prompt.class))).thenThrow(tier3Exception);

        FallbackChatModel fallbackChatModel = new FallbackChatModel(List.of(
                ChatModelTier.of("primary", primary),
                ChatModelTier.of("fallback1", fallback1),
                ChatModelTier.of("fallback2", fallback2)));

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> fallbackChatModel.call(new Prompt("hi")));

        assertSame(tier3Exception, thrown);
    }
}
