package com.bytedesk.service.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.bytedesk.ai.statistic.AiTokenUsageService;
import com.bytedesk.core.config.metrics.BytedeskMetrics;
import com.bytedesk.core.connection.ConnectionRepository;
import com.bytedesk.core.member.MemberRepository;
import com.bytedesk.core.message.MessageRepository;
import com.bytedesk.core.thread.ThreadRepository;
import com.bytedesk.service.visitor.VisitorRepository;

class DashboardServiceTest {

    @Test
    void aggregatesOrganizationOverview() {
        MemberRepository members = mock(MemberRepository.class);
        VisitorRepository visitors = mock(VisitorRepository.class);
        ThreadRepository threads = mock(ThreadRepository.class);
        MessageRepository messages = mock(MessageRepository.class);
        ConnectionRepository connections = mock(ConnectionRepository.class);
        BytedeskMetrics metrics = mock(BytedeskMetrics.class);
        AiTokenUsageService tokenUsageService = mock(AiTokenUsageService.class);

        when(members.countByOrgUidAndDeletedFalse("org-1")).thenReturn(12L);
        when(members.countByOrgUidAndCreatedAtBetweenAndDeletedFalse(eq("org-1"), any(), any()))
                .thenReturn(2L);
        when(visitors.countByOrgUidAndDeletedFalse("org-1")).thenReturn(40L);
        when(visitors.countByOrgUidAndCreatedAtBetweenAndDeletedFalse(eq("org-1"), any(), any()))
                .thenReturn(5L);
        when(visitors.countByOrgUidAndStatusAndDeletedFalse("org-1", "ONLINE")).thenReturn(3L);
        when(threads.countByOrgUidAndDeletedFalse("org-1")).thenReturn(100L);
        when(threads.countByOrgUidAndCreatedAtBetweenAndDeletedFalse(eq("org-1"), any(), any()))
                .thenReturn(8L);
        when(threads.countByOrgUidAndStatusNotInAndDeletedFalse(eq("org-1"), any())).thenReturn(7L);
        when(threads.countByOrgUidAndStatusAndDeletedFalse("org-1", "QUEUING")).thenReturn(2L);
        when(messages.countByOrgUidAndDeletedFalse("org-1")).thenReturn(500L);
        when(messages.countByOrgUidAndCreatedAtBetweenAndDeletedFalse(eq("org-1"), any(), any()))
                .thenReturn(32L);
        when(connections.countByOrgUidAndStatusAndDeletedFalse("org-1", "CONNECTED")).thenReturn(4L);
        when(metrics.getAiRequestsCount()).thenReturn(9.0);
        when(tokenUsageService.summary(eq("org-1"), any(), any())).thenReturn(Map.of(
                "todayTokens", 120L,
                "totalTokens", 300L,
                "todayCostCny", 0.42));

        DashboardService service = new DashboardService(members, visitors, threads, messages,
                connections, metrics, tokenUsageService);
        Map<String, Object> overview = service.overview("org-1");

        assertEquals("org-1", overview.get("organizationUid"));
        assertEquals(12L, ((Map<?, ?>) overview.get("users")).get("members"));
        assertEquals(3L, ((Map<?, ?>) overview.get("traffic")).get("onlineVisitors"));
        assertEquals(2L, ((Map<?, ?>) overview.get("conversations")).get("queuing"));
        assertEquals(32L, ((Map<?, ?>) overview.get("messages")).get("today"));
        assertEquals(120L, ((Map<?, ?>) overview.get("ai")).get("todayTokens"));
    }
}
