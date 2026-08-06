package com.skyshift.cognitiveragengine.tools;

/**
 * Centralized keys for values bound into the ReactAgent's {@link org.springframework.ai.chat.model.ToolContext}.
 * These are set server-side by the agent factory and are never tool arguments the model can set.
 */
public final class ContextKeys {

    public static final String GROUP_ID_CONTEXT_KEY = "groupId";
    public static final String USER_ID_CONTEXT_KEY = "userId";
    public static final String RESULT_HOLDER_CONTEXT_KEY = "rag.retrievedDocuments";

    private ContextKeys() {
    }
}
