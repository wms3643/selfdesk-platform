package com.bytedesk.ai.demo_order;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

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
 * Local order-system contract used to verify the customer-service HTTP tool.
 * It is disabled by default and must never be enabled in production.
 */
@Hidden
@RestController
@RequestMapping("/internal/demo/orders")
@ConditionalOnProperty(name = "bytedesk.demo-order-api.enabled", havingValue = "true")
public class DemoOrderApiController {

    private static final String TOOL_TOKEN_HEADER = "X-Bytedesk-Tool-Token";
    private static final DateTimeFormatter ORDER_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final Pattern PHONE_PATTERN = Pattern.compile("^1[3-9]\\d{9}$");

    private final String expectedToken;
    private final ConcurrentMap<String, SampleOrder> orders = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Map<String, Object>> idempotencyRecords = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    public DemoOrderApiController(@Value("${BYTEDESK_DEMO_ORDER_TOKEN:}") String expectedToken) {
        this.expectedToken = expectedToken;
        orders.put("A123456", new SampleOrder("A123456", "13800138000", "SHIPPED", "已发货",
                new BigDecimal("299.00"), "示例蓝牙耳机", 1, "顺丰速运",
                "SF123456789", "快件已到达杭州转运中心"));
        orders.put("B2026001", new SampleOrder("B2026001", "13900139000", "DELIVERED", "已签收",
                new BigDecimal("1599.00"), "示例智能手表", 1, "京东物流",
                "JD987654321", "快件已由本人签收"));
    }

    @PostMapping("/query")
    public ResponseEntity<Map<String, Object>> query(
            @RequestHeader(name = TOOL_TOKEN_HEADER, required = false) String token,
            @RequestBody(required = false) OrderQueryRequest request) {
        if (!tokenMatches(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("found", false, "error", "UNAUTHORIZED"));
        }
        if (request == null || request.slots() == null) {
            return ResponseEntity.badRequest().body(Map.of("found", false, "error", "SLOTS_REQUIRED"));
        }

        String orderNo = trim(request.slots().get("orderNo"));
        String phone = trim(request.slots().get("phone"));
        if (!StringUtils.hasText(orderNo) && !StringUtils.hasText(phone)) {
            return ResponseEntity.badRequest().body(Map.of("found", false, "error", "ORDER_NO_OR_PHONE_REQUIRED"));
        }

        SampleOrder order = orders.values().stream()
                .filter(candidate -> StringUtils.hasText(orderNo)
                        ? candidate.orderNo().equalsIgnoreCase(orderNo)
                        : candidate.phone().equals(phone))
                .findFirst()
                .orElse(null);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("requestId", request.requestId());
        if (order == null) {
            response.put("found", false);
            response.put("message", "未找到匹配订单");
            return ResponseEntity.ok(response);
        }
        response.put("found", true);
        response.put("orderNo", order.orderNo());
        response.put("status", order.status());
        response.put("statusText", order.statusText());
        response.put("amount", order.amount());
        response.put("currency", "CNY");
        response.put("items", List.of(Map.of(
                "name", order.productName(),
                "quantity", order.quantity())));
        response.put("logistics", Map.of(
                "company", order.carrier(),
                "trackingNo", order.trackingNo(),
                "latestEvent", order.latestEvent(),
                "updatedAt", ZonedDateTime.now().toString()));
        return ResponseEntity.ok(response);
    }

    @PostMapping("/create")
    public ResponseEntity<Map<String, Object>> create(
            @RequestHeader(name = TOOL_TOKEN_HEADER, required = false) String token,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) OrderCreateRequest request) {
        if (!tokenMatches(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("created", false, "error", "UNAUTHORIZED"));
        }
        if (request == null || request.slots() == null) {
            return ResponseEntity.badRequest().body(Map.of("created", false, "error", "SLOTS_REQUIRED"));
        }

        String productName = trim(request.slots().get("productName"));
        String phone = trim(request.slots().get("phone"));
        String address = trim(request.slots().get("address"));
        Integer quantity = parseQuantity(request.slots().get("quantity"));
        if (!StringUtils.hasText(productName) || productName.length() > 200) {
            return ResponseEntity.badRequest().body(Map.of("created", false, "error", "PRODUCT_REQUIRED"));
        }
        if (quantity == null || quantity < 1 || quantity > 99) {
            return ResponseEntity.badRequest().body(Map.of("created", false, "error", "INVALID_QUANTITY"));
        }
        if (!StringUtils.hasText(phone) || !PHONE_PATTERN.matcher(phone).matches()) {
            return ResponseEntity.badRequest().body(Map.of("created", false, "error", "INVALID_PHONE"));
        }
        if (!StringUtils.hasText(address) || address.length() < 2 || address.length() > 300) {
            return ResponseEntity.badRequest().body(Map.of("created", false, "error", "ADDRESS_REQUIRED"));
        }

        String dedupeKey = StringUtils.hasText(idempotencyKey) ? idempotencyKey
                : (StringUtils.hasText(request.requestId()) ? request.requestId() : "generated-" + System.nanoTime());
        Map<String, Object> candidate = buildCreatedOrder(request, productName, quantity, phone, address);
        Map<String, Object> existing = idempotencyRecords.putIfAbsent(dedupeKey, candidate);
        Map<String, Object> response = new LinkedHashMap<>(existing != null ? existing : candidate);
        response.put("duplicate", existing != null);
        return ResponseEntity.ok(response);
    }

    private Map<String, Object> buildCreatedOrder(OrderCreateRequest request, String productName, int quantity,
            String phone, String address) {
        ZonedDateTime createdAt = ZonedDateTime.now();
        String orderNo = "C" + createdAt.format(ORDER_DATE_FORMAT)
                + String.format("%06d", sequence.incrementAndGet());
        SampleOrder created = new SampleOrder(orderNo, phone, "CREATED", "Order created",
                new BigDecimal("0.00"), productName, quantity, "", "",
                "Waiting for fulfillment");
        orders.put(orderNo, created);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("requestId", request.requestId());
        response.put("created", true);
        response.put("orderNo", orderNo);
        response.put("status", created.status());
        response.put("statusText", created.statusText());
        response.put("productName", productName);
        response.put("quantity", quantity);
        response.put("phone", phone);
        response.put("address", address);
        response.put("createdAt", createdAt.toString());
        return response;
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

    private static Integer parseQuantity(String value) {
        try {
            return value != null ? Integer.valueOf(value.trim()) : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public record OrderQueryRequest(String requestId, String intent, Map<String, String> slots) {
    }

    public record OrderCreateRequest(String requestId, String intent, Map<String, String> slots) {
    }

    private record SampleOrder(String orderNo, String phone, String status, String statusText,
            BigDecimal amount, String productName, int quantity, String carrier,
            String trackingNo, String latestEvent) {
    }
}
