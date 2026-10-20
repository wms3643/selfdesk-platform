package com.bytedesk.service.dashboard;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.bytedesk.core.thread.ThreadRepository;
import com.bytedesk.core.thread.enums.ThreadProcessStatusEnum;
import com.bytedesk.service.agent.AgentRepository;
import com.bytedesk.service.agent.AgentStatusEnum;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ServiceStatisticService {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final AgentRepository agentRepository;
    private final ThreadRepository threadRepository;

    public Map<String, Object> current(String orgUid) {
        List<Object[]> agentStatuses = agentRepository.countStatusByOrgUid(orgUid);
        List<Object[]> threadStatuses = threadRepository.countStatusByOrgUid(orgUid);
        Map<String, Object> result = build(orgUid, null, null, agentStatuses, threadStatuses,
                List.of());
        return result;
    }

    public Map<String, Object> forPeriod(String orgUid, LocalDate startDate, LocalDate endDate) {
        LocalDate effectiveEnd = endDate != null ? endDate : LocalDate.now(BUSINESS_ZONE);
        LocalDate effectiveStart = startDate != null ? startDate : effectiveEnd;
        if (effectiveEnd.isBefore(effectiveStart)) {
            throw new IllegalArgumentException("endDate must not be before startDate");
        }
        if (effectiveStart.plusDays(366).isBefore(effectiveEnd)) {
            throw new IllegalArgumentException("The statistic period cannot exceed 366 days");
        }

        ZonedDateTime start = effectiveStart.atStartOfDay(BUSINESS_ZONE);
        ZonedDateTime end = effectiveEnd.plusDays(1).atStartOfDay(BUSINESS_ZONE);
        List<Object[]> agentStatuses = agentRepository.countStatusByOrgUid(orgUid);
        List<Object[]> threadStatuses = threadRepository
                .countStatusByOrgUidAndCreatedAtBetween(orgUid, start, end);
        List<Object[]> transferStatuses = threadRepository
                .countTransferStatusByOrgUidAndCreatedAtBetween(orgUid, start, end);
        return build(orgUid, start, end, agentStatuses, threadStatuses, transferStatuses);
    }

    private Map<String, Object> build(String orgUid, ZonedDateTime start, ZonedDateTime end,
            List<Object[]> agentStatuses, List<Object[]> threadStatuses,
            List<Object[]> transferStatuses) {
        Map<String, Long> agents = countsByStatus(agentStatuses);
        Map<String, Long> threads = countsByStatus(threadStatuses);

        long totalAgents = sum(agents);
        long offlineAgents = agents.getOrDefault(AgentStatusEnum.OFFLINE.name(), 0L);
        long onlineAgents = Math.max(0L, totalAgents - offlineAgents);

        long totalThreads = sum(threads);
        long queuing = threads.getOrDefault(ThreadProcessStatusEnum.QUEUING.name(), 0L);
        long chatting = threads.getOrDefault(ThreadProcessStatusEnum.CHATTING.name(), 0L);
        long roboting = threads.getOrDefault(ThreadProcessStatusEnum.ROBOTING.name(), 0L);
        long transferred = transferStatuses.stream()
                .filter(row -> row[0] != null)
                .filter(row -> !"NONE".equalsIgnoreCase(String.valueOf(row[0])))
                .mapToLong(this::countOf)
                .sum();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("organizationUid", orgUid);
        result.put("generatedAt", ZonedDateTime.now(BUSINESS_ZONE).format(TIMESTAMP_FORMAT));
        result.put("timezone", BUSINESS_ZONE.getId());
        if (start != null) {
            result.put("periodStart", start.format(TIMESTAMP_FORMAT));
            result.put("periodEnd", end.format(TIMESTAMP_FORMAT));
        }

        result.put("onlineAgentCount", onlineAgents);
        result.put("offlineAgentCount", offlineAgents);
        result.put("totalAgentCount", totalAgents);
        result.put("queuingThreadCount", queuing);
        result.put("currentThreadCount", chatting);
        result.put("acceptedThreadCount", chatting);
        result.put("robotingThreadCount", roboting);
        result.put("transferredThreadCount", transferred);
        result.put("totalThreadCount", totalThreads);
        result.put("acceptRate", percentage(chatting, totalThreads));

        Map<String, Object> availability = new LinkedHashMap<>();
        availability.put("currentStatusCounts", true);
        availability.put("agentStatusCounts", true);
        availability.put("firstResponseTime", false);
        availability.put("conversationDuration", false);
        availability.put("satisfactionRate", false);
        result.put("metricsAvailability", availability);
        result.put("note",
                "\u5f53\u524d\u72b6\u6001\u4e0e\u5df2\u6709\u4f1a\u8bdd\u8bb0\u5f55\u5b9e\u65f6\u7edf\u8ba1\uff1b"
                        + "\u9996\u54cd\u65f6\u957f\u3001\u4f1a\u8bdd\u65f6\u957f\u548c\u6ee1\u610f\u5ea6"
                        + "\u5c1a\u9700\u5ba1\u8ba1\u4e8b\u4ef6\u6216\u8bc4\u4ef7\u8bb0\u5f55\u3002");
        return result;
    }

    private Map<String, Long> countsByStatus(List<Object[]> rows) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Object[] row : rows) {
            if (row[0] == null) {
                continue;
            }
            counts.merge(String.valueOf(row[0]).toUpperCase(), countOf(row), Long::sum);
        }
        return counts;
    }

    private long countOf(Object[] row) {
        if (row.length < 2 || !(row[1] instanceof Number number)) {
            return 0L;
        }
        return number.longValue();
    }

    private long sum(Map<String, Long> values) {
        return values.values().stream().mapToLong(Long::longValue).sum();
    }

    private double percentage(long part, long total) {
        if (total <= 0) {
            return 0.0;
        }
        return Math.round(part * 1000.0 / total) / 10.0;
    }
}
