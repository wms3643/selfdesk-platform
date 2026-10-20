package com.bytedesk.service.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.bytedesk.core.thread.ThreadRepository;
import com.bytedesk.service.agent.AgentRepository;

class ServiceStatisticServiceTest {

    @Test
    void aggregatesCurrentAgentAndThreadStatusesForPeriod() {
        AgentRepository agents = mock(AgentRepository.class);
        ThreadRepository threads = mock(ThreadRepository.class);
        when(agents.countStatusByOrgUid("org-1"))
                .thenReturn(List.of(new Object[] { "AVAILABLE", 2L },
                        new Object[] { "BUSY", 1L },
                        new Object[] { "OFFLINE", 1L }));
        when(threads.countStatusByOrgUidAndCreatedAtBetween(eq("org-1"), any(), any()))
                .thenReturn(List.of(new Object[] { "QUEUING", 1L },
                        new Object[] { "CHATTING", 3L },
                        new Object[] { "CLOSED", 6L }));
        when(threads.countTransferStatusByOrgUidAndCreatedAtBetween(eq("org-1"), any(), any()))
                .thenReturn(List.of(new Object[] { "NONE", 8L },
                        new Object[] { "TRANSFERRED", 2L }));

        ServiceStatisticService service = new ServiceStatisticService(agents, threads);
        Map<String, Object> result = service.forPeriod("org-1",
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 18));

        assertEquals(3L, result.get("onlineAgentCount"));
        assertEquals(1L, result.get("offlineAgentCount"));
        assertEquals(1L, result.get("queuingThreadCount"));
        assertEquals(3L, result.get("currentThreadCount"));
        assertEquals(10L, result.get("totalThreadCount"));
        assertEquals(2L, result.get("transferredThreadCount"));
        assertEquals(30.0, result.get("acceptRate"));
    }
}
