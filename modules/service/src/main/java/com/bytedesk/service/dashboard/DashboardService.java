package com.bytedesk.service.dashboard;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.bytedesk.ai.statistic.AiTokenUsageService;
import com.bytedesk.core.config.metrics.BytedeskMetrics;
import com.bytedesk.core.connection.ConnectionRepository;
import com.bytedesk.core.connection.ConnectionStatusEnum;
import com.bytedesk.core.member.MemberRepository;
import com.bytedesk.core.message.MessageRepository;
import com.bytedesk.core.thread.ThreadRepository;
import com.bytedesk.core.thread.enums.ThreadProcessStatusEnum;
import com.bytedesk.service.visitor.VisitorRepository;
import com.bytedesk.service.visitor.VisitorStatusEnum;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class DashboardService {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final MemberRepository memberRepository;
    private final VisitorRepository visitorRepository;
    private final ThreadRepository threadRepository;
    private final MessageRepository messageRepository;
    private final ConnectionRepository connectionRepository;
    private final BytedeskMetrics bytedeskMetrics;
    private final AiTokenUsageService aiTokenUsageService;

    public Map<String, Object> overview(String orgUid) {
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        ZonedDateTime start = today.atStartOfDay(BUSINESS_ZONE);
        ZonedDateTime end = start.plusDays(1);

        long totalMembers = memberRepository.countByOrgUidAndDeletedFalse(orgUid);
        long newMembersToday = memberRepository
                .countByOrgUidAndCreatedAtBetweenAndDeletedFalse(orgUid, start, end);
        long activeConnections = connectionRepository.countByOrgUidAndStatusAndDeletedFalse(orgUid,
                ConnectionStatusEnum.CONNECTED.name());

        long totalVisitors = visitorRepository.countByOrgUidAndDeletedFalse(orgUid);
        long newVisitorsToday = visitorRepository
                .countByOrgUidAndCreatedAtBetweenAndDeletedFalse(orgUid, start, end);
        long onlineVisitors = visitorRepository.countByOrgUidAndStatusAndDeletedFalse(orgUid,
                VisitorStatusEnum.ONLINE.name());

        long totalThreads = threadRepository.countByOrgUidAndDeletedFalse(orgUid);
        long threadsToday = threadRepository
                .countByOrgUidAndCreatedAtBetweenAndDeletedFalse(orgUid, start, end);
        long activeThreads = threadRepository.countByOrgUidAndStatusNotInAndDeletedFalse(orgUid,
                List.of(ThreadProcessStatusEnum.CLOSED.name(), ThreadProcessStatusEnum.TIMEOUT.name()));
        long queuingThreads = threadRepository.countByOrgUidAndStatusAndDeletedFalse(orgUid,
                ThreadProcessStatusEnum.QUEUING.name());

        long totalMessages = messageRepository.countByOrgUidAndDeletedFalse(orgUid);
        long messagesToday = messageRepository
                .countByOrgUidAndCreatedAtBetweenAndDeletedFalse(orgUid, start, end);

        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("organizationUid", orgUid);
        overview.put("generatedAt", ZonedDateTime.now(BUSINESS_ZONE).format(TIMESTAMP_FORMAT));
        overview.put("periodStart", start.format(TIMESTAMP_FORMAT));
        overview.put("periodEnd", end.format(TIMESTAMP_FORMAT));
        overview.put("timezone", BUSINESS_ZONE.getId());

        Map<String, Object> users = new LinkedHashMap<>();
        users.put("members", totalMembers);
        users.put("newMembersToday", newMembersToday);
        users.put("onlineConnections", activeConnections);
        overview.put("users", users);

        Map<String, Object> traffic = new LinkedHashMap<>();
        traffic.put("visitorRecords", totalVisitors);
        traffic.put("newVisitorsToday", newVisitorsToday);
        traffic.put("onlineVisitors", onlineVisitors);
        traffic.put("uniqueVisitorsAvailable", true);
        traffic.put("pageViewsAvailable", false);
        traffic.put("note", "当前统计访客记录与在线访客；页面浏览量 PV 需要前端埋点上报。");
        overview.put("traffic", traffic);

        Map<String, Object> conversations = new LinkedHashMap<>();
        conversations.put("total", totalThreads);
        conversations.put("today", threadsToday);
        conversations.put("active", activeThreads);
        conversations.put("queuing", queuingThreads);
        overview.put("conversations", conversations);

        Map<String, Object> messages = new LinkedHashMap<>();
        messages.put("total", totalMessages);
        messages.put("today", messagesToday);
        overview.put("messages", messages);

        Map<String, Object> ai = new LinkedHashMap<>(
                aiTokenUsageService.summary(orgUid, start, end));
        ai.put("requestsSinceStartup", bytedeskMetrics != null
                ? bytedeskMetrics.getAiRequestsCount()
                : 0.0);
        overview.put("ai", ai);
        return overview;
    }
}
