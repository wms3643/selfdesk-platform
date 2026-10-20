package com.bytedesk.ai.demo_business;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Hidden;

/**
 * Local complaint-system contract used to verify the customer-service HTTP
 * tool. It is disabled by default and stores records in memory only.
 */
@Hidden
@RestController
@RequestMapping("/internal/demo/complaints")
@ConditionalOnProperty(name = "bytedesk.demo-order-api.enabled", havingValue = "true")
public class DemoComplaintApiController {

    private static final String TOOL_TOKEN_HEADER = "X-Bytedesk-Tool-Token";
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final String expectedToken;
    private final AtomicLong sequence = new AtomicLong();
    private final ConcurrentMap<String, ComplaintRecord> idempotencyRecords = new ConcurrentHashMap<>();

    public DemoComplaintApiController(@Value("${BYTEDESK_DEMO_ORDER_TOKEN:}") String expectedToken) {
        this.expectedToken = expectedToken;
    }

    @PostMapping("/create")
    public ResponseEntity<Map<String, Object>> create(
            @RequestHeader(name = TOOL_TOKEN_HEADER, required = false) String token,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) ComplaintCreateRequest request) {
        if (!tokenMatches(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("created", false, "error", "UNAUTHORIZED"));
        }
        if (request == null || request.slots() == null) {
            return ResponseEntity.badRequest().body(Map.of("created", false, "error", "SLOTS_REQUIRED"));
        }
        String reason = trim(request.slots().get("reason"));
        if (!StringUtils.hasText(reason) || reason.length() < 6) {
            return ResponseEntity.badRequest()
                    .body(Map.of("created", false, "error", "COMPLAINT_REASON_REQUIRED"));
        }

        String dedupeKey = StringUtils.hasText(idempotencyKey)
                ? idempotencyKey
                : request.requestId();
        ZonedDateTime createdAt = ZonedDateTime.now();
        ComplaintRecord candidate = new ComplaintRecord(
                "CMP" + createdAt.format(DATE_FORMAT)
                        + String.format("%06d", sequence.incrementAndGet()),
                "CREATED", createdAt);
        ComplaintRecord existing = idempotencyRecords.putIfAbsent(dedupeKey, candidate);
        ComplaintRecord record = existing != null ? existing : candidate;

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("requestId", request.requestId());
        response.put("created", true);
        response.put("complaintNo", record.complaintNo());
        response.put("status", record.status());
        response.put("createdAt", record.createdAt().toString());
        response.put("duplicate", existing != null);
        response.put("message", "投诉已登记，将转接人工客服跟进");
        return ResponseEntity.ok(response);
    }

    private boolean tokenMatches(String candidate) {
        if (!StringUtils.hasText(expectedToken) || candidate == null) {
            return false;
        }
        return MessageDigest.isEqual(expectedToken.getBytes(StandardCharsets.UTF_8),
                candidate.getBytes(StandardCharsets.UTF_8));
    }

    private static String trim(String value) {
        return value != null ? value.trim() : null;
    }

    public record ComplaintCreateRequest(String requestId, String intent, Map<String, String> slots) {
    }

    private record ComplaintRecord(String complaintNo, String status, ZonedDateTime createdAt) {
    }
}
