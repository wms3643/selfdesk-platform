package com.bytedesk.ticket.statistic;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.bytedesk.ticket.ticket.TicketRepository;
import com.bytedesk.ticket.ticket.enums.TicketStatusEnum;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class TicketStatisticService {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final TicketRepository ticketRepository;

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
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Object[] row : ticketRepository.countStatusByOrgUidAndCreatedAtBetween(orgUid, start, end)) {
            if (row[0] != null && row[1] instanceof Number number) {
                counts.merge(String.valueOf(row[0]).toUpperCase(), number.longValue(), Long::sum);
            }
        }

        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("organizationUid", orgUid);
        result.put("periodStart", start.format(TIMESTAMP_FORMAT));
        result.put("periodEnd", end.format(TIMESTAMP_FORMAT));
        result.put("timezone", BUSINESS_ZONE.getId());
        result.put("generatedAt", ZonedDateTime.now(BUSINESS_ZONE).format(TIMESTAMP_FORMAT));
        result.put("totalTickets", total);
        result.put("newTickets", counts.getOrDefault(TicketStatusEnum.NEW.name(), 0L));
        result.put("processingTickets", counts.getOrDefault(TicketStatusEnum.PROCESSING.name(), 0L));
        result.put("resolvedTickets", counts.getOrDefault(TicketStatusEnum.RESOLVED.name(), 0L));
        result.put("closedTickets", counts.getOrDefault(TicketStatusEnum.CLOSED.name(), 0L));
        result.put("holdingTickets", counts.getOrDefault(TicketStatusEnum.HOLDING.name(), 0L));
        result.put("pendingTickets", counts.getOrDefault(TicketStatusEnum.PENDING.name(), 0L));
        result.put("satisfactionAvailable", false);
        return result;
    }
}
