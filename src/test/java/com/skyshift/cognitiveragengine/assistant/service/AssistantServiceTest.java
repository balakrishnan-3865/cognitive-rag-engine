package com.skyshift.cognitiveragengine.assistant.service;

import com.skyshift.cognitiveragengine.assistant.agent.AssistantReactAgentFactory;
import com.skyshift.cognitiveragengine.assistant.config.AssistantProperties;
import com.skyshift.cognitiveragengine.common.exception.BusinessException;
import com.skyshift.cognitiveragengine.document.service.DocumentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Covers the documentId validation added to ask(): an invalid/inaccessible documentId must be a
 * clear rejection (BusinessException escaping to GlobalExceptionHandler -> 400), raised before any
 * conversation is created or the ReactAgent is invoked - mirrors QaServiceTest's equivalent case.
 */
@ExtendWith(MockitoExtension.class)
class AssistantServiceTest {

    private static final Long GROUP_ID = 100L;
    private static final Long USER_ID = 7L;

    @Mock
    private AssistantReactAgentFactory assistantReactAgentFactory;

    @Mock
    private ConversationService conversationService;

    @Mock
    private ConversationSummaryService conversationSummaryService;

    @Mock
    private DocumentService documentService;

    private AssistantService assistantService;

    @BeforeEach
    void setUp() {
        assistantService = new AssistantService(
                assistantReactAgentFactory,
                conversationService,
                conversationSummaryService,
                documentService,
                new AssistantProperties()
        );
    }

    @Test
    void ask_invalidDocumentId_propagatesBusinessExceptionAsClearRejection() {
        when(documentService.resolveSearchableDocumentIds(GROUP_ID, 999L))
                .thenThrow(new BusinessException("Document not found or not ready: documentId=999"));

        assertThrows(BusinessException.class,
                () -> assistantService.ask("what does this document say?", GROUP_ID, USER_ID, null, 999L));

        verifyNoInteractions(conversationService, assistantReactAgentFactory);
    }
}
