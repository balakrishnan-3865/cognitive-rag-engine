package com.skyshift.cognitiveragengine.tools;

import com.skyshift.cognitiveragengine.workflows.claims.mapper.ClaimMapper;
import com.skyshift.cognitiveragengine.workflows.claims.model.entity.ClaimEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClaimStatusToolTest {

    private static final Long USER_ID = 1L;
    private static final Long GROUP_ID = 2L;

    @Mock
    private ClaimMapper claimMapper;

    private ClaimStatusTool claimStatusTool;
    private ToolContext toolContext;

    @BeforeEach
    void setUp() {
        claimStatusTool = new ClaimStatusTool(claimMapper);
        toolContext = new ToolContext(Map.of(
                ContextKeys.USER_ID_CONTEXT_KEY, USER_ID,
                ContextKeys.GROUP_ID_CONTEXT_KEY, GROUP_ID
        ));
    }

    @Test
    void bothDatesAbsent_defaultsToOneMonthEndingToday() {
        when(claimMapper.findByUserIdAndGroupIdBetween(eq(USER_ID), eq(GROUP_ID), any(), any()))
                .thenReturn(List.of());

        claimStatusTool.getClaims(null, null, toolContext);

        LocalDate expectedEnd = LocalDate.now();
        LocalDate expectedStart = expectedEnd.minusMonths(1);
        verify(claimMapper).findByUserIdAndGroupIdBetween(USER_ID, GROUP_ID, expectedStart, expectedEnd);
    }

    @Test
    void onlyStartDateGiven_endDateDefaultsToToday() {
        when(claimMapper.findByUserIdAndGroupIdBetween(eq(USER_ID), eq(GROUP_ID), any(), any()))
                .thenReturn(List.of());
        LocalDate startDate = LocalDate.of(2026, 1, 1);

        claimStatusTool.getClaims(startDate, null, toolContext);

        verify(claimMapper).findByUserIdAndGroupIdBetween(USER_ID, GROUP_ID, startDate, LocalDate.now());
    }

    @Test
    void onlyEndDateGiven_startDateDefaultsToOneMonthBefore() {
        when(claimMapper.findByUserIdAndGroupIdBetween(eq(USER_ID), eq(GROUP_ID), any(), any()))
                .thenReturn(List.of());
        LocalDate endDate = LocalDate.of(2026, 6, 15);

        claimStatusTool.getClaims(null, endDate, toolContext);

        verify(claimMapper).findByUserIdAndGroupIdBetween(USER_ID, GROUP_ID, endDate.minusMonths(1), endDate);
    }

    @Test
    void bothDatesGiven_passedThroughUnchanged() {
        when(claimMapper.findByUserIdAndGroupIdBetween(eq(USER_ID), eq(GROUP_ID), any(), any()))
                .thenReturn(List.of());
        LocalDate startDate = LocalDate.of(2026, 1, 1);
        LocalDate endDate = LocalDate.of(2026, 3, 1);

        claimStatusTool.getClaims(startDate, endDate, toolContext);

        verify(claimMapper).findByUserIdAndGroupIdBetween(USER_ID, GROUP_ID, startDate, endDate);
    }

    @Test
    void mapsClaimEntityToClaimSummary() {
        ClaimEntity entity = ClaimEntity.builder()
                .claimReference("CLM-1")
                .claimStatus("APPROVED")
                .serviceDate(LocalDate.of(2026, 1, 5))
                .build();
        when(claimMapper.findByUserIdAndGroupIdBetween(eq(USER_ID), eq(GROUP_ID), any(), any()))
                .thenReturn(List.of(entity));

        var summaries = claimStatusTool.getClaims(null, null, toolContext);

        assertEquals(1, summaries.size());
        assertEquals("CLM-1", summaries.get(0).claimReference());
        assertEquals("APPROVED", summaries.get(0).claimStatus());
    }

    @Test
    void missingUserIdInContext_throwsIllegalStateException() {
        ToolContext incompleteContext = new ToolContext(Map.of(ContextKeys.GROUP_ID_CONTEXT_KEY, GROUP_ID));

        assertThrows(IllegalStateException.class, () -> claimStatusTool.getClaims(null, null, incompleteContext));
    }
}
