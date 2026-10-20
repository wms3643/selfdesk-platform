package com.bytedesk.ai.service;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.util.StringUtils;

import com.alibaba.fastjson2.JSON;
import com.bytedesk.ai.robot.RobotEntity;
import com.bytedesk.ai.robot.RobotProtobuf;
import com.bytedesk.ai.robot.RobotRestService;
import com.bytedesk.ai.robot_tool.RobotToolConfig;
import com.bytedesk.ai.robot_tool.RobotToolsSettingsEntity;

import lombok.extern.slf4j.Slf4j;

/**
 * Executes configured order/logistics/complaint tools through HTTP_ENDPOINT
 * bindings. Only Java-validated slots are sent to the business API; the raw
 * customer message is not forwarded.
 */
@Slf4j
public final class BusinessToolExecutor {

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private static final Semaphore IN_FLIGHT = new Semaphore(32, true);
    private static final int MAX_AGGREGATE_RESPONSE_BYTES = 131_072;
    private static final Pattern HEADER_NAME_PATTERN = Pattern.compile(
            "[!#$%&'*+.^_`|~0-9A-Za-z-]+");
    private static final Pattern ENV_PLACEHOLDER_PATTERN = Pattern.compile(
            "\\$\\{[A-Za-z_][A-Za-z0-9_]*}");
    private static final Set<String> SENSITIVE_HEADERS = Set.of(
            "authorization", "proxy-authorization", "cookie", "set-cookie",
            "x-api-key", "api-key", "apikey", "x-auth-token", "x-bytedesk-tool-token");
    private static final Set<String> RESERVED_HEADERS = Set.of(
            "host", "content-length", "content-type", "accept", "connection", "transfer-encoding", "expect",
            "x-bytedesk-intent", "x-bytedesk-robot-uid", "x-bytedesk-org-uid",
            "x-bytedesk-request-id", "x-bytedesk-thread-uid", "x-bytedesk-message-uid",
            "x-bytedesk-timestamp", "x-bytedesk-nonce", "x-bytedesk-content-sha256",
            "x-bytedesk-signature");

    private BusinessToolExecutor() {
    }

    public record InvocationContext(String requestId, String threadUid, String messageUid) {

        public static InvocationContext empty() {
            return new InvocationContext(null, null, null);
        }
    }

    private record ToolHttpResponse(int statusCode, String body, boolean tooLarge) {
    }

    public static BusinessToolResult execute(RobotRestService robotRestService, RobotProtobuf robot,
            String query, BusinessIntentEvaluation evaluation) {
        return execute(robotRestService, robot, query, evaluation, InvocationContext.empty());
    }

    public static BusinessToolResult execute(RobotRestService robotRestService, RobotProtobuf robot,
            String query, BusinessIntentEvaluation evaluation, InvocationContext invocationContext) {
        if (robotRestService == null || robot == null || evaluation == null
                || evaluation.intents() == null || evaluation.intents().isEmpty()) {
            return BusinessToolResult.failure("business-tool-context-unavailable");
        }

        Optional<RobotEntity> robotOptional = robotRestService.findByUid(robot.getUid());
        if (robotOptional.isEmpty()) {
            return BusinessToolResult.failure("robot-not-found");
        }
        RobotToolsSettingsEntity toolsSettings = resolveToolsSettings(robotOptional.get());
        if (toolsSettings == null || toolsSettings.getToolConfigs() == null
                || toolsSettings.getToolConfigs().isEmpty()) {
            return BusinessToolResult.failure("business-tool-not-configured");
        }

        String requestId = resolveRequestId(invocationContext);
        List<Map<String, Object>> results = new ArrayList<>();
        int aggregateBytes = 0;
        for (BusinessIntentMatch intent : evaluation.intents()) {
            if (!isHttpToolIntent(intent.getType())) {
                continue;
            }
            String toolKey = toolKeyFor(intent.getType());
            RobotToolConfig config = findToolConfig(toolsSettings, toolKey, intent.getType());
            if (config == null || !StringUtils.hasText(config.getEndpoint())) {
                return BusinessToolResult.failure("business-tool-not-configured:" + intent.getType());
            }
            if (Boolean.TRUE.equals(config.getRequiresApproval())) {
                return BusinessToolResult.failure("business-tool-requires-approval:" + intent.getType());
            }

            URI endpoint;
            try {
                endpoint = URI.create(config.getEndpoint().trim());
                String endpointError = validateEndpoint(endpoint);
                if (endpointError != null) {
                    return BusinessToolResult.failure(endpointError);
                }
            } catch (Exception e) {
                return BusinessToolResult.failure("business-tool-invalid-endpoint");
            }

            Map<String, String> slots = slotMap(intent.getSlots());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("requestId", requestId);
            payload.put("intent", intent.getType());
            payload.put("slots", slots);
            String requestBody = JSON.toJSONString(payload);

            try {
                HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                        .uri(endpoint)
                        .timeout(Duration.ofMillis(clampTimeout(config.getTimeoutMs())))
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json, text/plain")
                        .header("X-Bytedesk-Intent", intent.getType())
                        .header("X-Bytedesk-Request-Id", requestId)
                        .header("X-Bytedesk-Robot-Uid", robot.getUid() != null ? robot.getUid() : "")
                        .header("X-Bytedesk-Org-Uid", robot.getOrgUid() != null ? robot.getOrgUid() : "");
                if (invocationContext != null && isSafeHeaderValue(invocationContext.threadUid())) {
                    requestBuilder.header("X-Bytedesk-Thread-Uid", invocationContext.threadUid().trim());
                }
                if (invocationContext != null && isSafeHeaderValue(invocationContext.messageUid())) {
                    requestBuilder.header("X-Bytedesk-Message-Uid", invocationContext.messageUid().trim());
                }
                String headerError = addConfiguredHeaders(requestBuilder, config);
                if (headerError != null) {
                    return BusinessToolResult.failure(headerError);
                }
                if (Boolean.TRUE.equals(config.getIdempotent())) {
                    String header = StringUtils.hasText(config.getIdempotencyHeader())
                            ? config.getIdempotencyHeader().trim()
                            : "Idempotency-Key";
                    if (!HEADER_NAME_PATTERN.matcher(header).matches()
                            || RESERVED_HEADERS.contains(header.toLowerCase(Locale.ROOT))) {
                        return BusinessToolResult.failure("business-tool-invalid-idempotency-header");
                    }
                    requestBuilder.header(header, idempotencyKey(robot, requestId, intent.getType(), slots));
                }
                String signingError = addSignatureHeaders(requestBuilder, endpoint, requestBody);
                if (signingError != null) {
                    return BusinessToolResult.failure(signingError);
                }
                requestBuilder.POST(HttpRequest.BodyPublishers.ofString(requestBody));

                ToolHttpResponse response = sendWithRetry(requestBuilder.build(), config);
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    return BusinessToolResult.failure("business-tool-http-" + response.statusCode());
                }
                if (response.tooLarge()) {
                    return BusinessToolResult.failure("business-tool-response-too-large");
                }
                String body = response.body() != null ? response.body() : "{}";
                String validationError = validateResponse(body, config);
                if (validationError != null) {
                    return BusinessToolResult.failure(validationError);
                }
                int bodyBytes = body.getBytes(StandardCharsets.UTF_8).length;
                aggregateBytes += bodyBytes;
                if (aggregateBytes > MAX_AGGREGATE_RESPONSE_BYTES) {
                    return BusinessToolResult.failure("business-tool-aggregate-response-too-large");
                }
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("intent", intent.getType());
                result.put("data", body);
                results.add(result);
            } catch (CapacityExceededException e) {
                return BusinessToolResult.failure("business-tool-capacity-exceeded");
            } catch (Exception e) {
                log.warn("Business tool execution failed, intent={}, tool={}, error={}",
                        intent.getType(), toolKey, e.getClass().getSimpleName());
                return BusinessToolResult.failure("business-tool-execution-failed:" + intent.getType());
            }
        }
        if (results.isEmpty()) {
            return BusinessToolResult.failure("no-http-business-tool-intent");
        }
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("source", "authorized_business_api");
        envelope.put("requestId", requestId);
        envelope.put("results", results);
        return BusinessToolResult.success(JSON.toJSONString(envelope));
    }

    private static String resolveRequestId(InvocationContext context) {
        if (context != null && StringUtils.hasText(context.requestId())) {
            String requestId = context.requestId().trim();
            if (isSafeHeaderValue(requestId)) {
                return requestId.length() <= 128 ? requestId : requestId.substring(0, 128);
            }
        }
        return UUID.randomUUID().toString();
    }

    private static ToolHttpResponse sendWithRetry(HttpRequest request, RobotToolConfig config)
            throws Exception {
        int maxBodyBytes = config.getMaxResponseBytes() != null
                ? Math.max(1024, Math.min(config.getMaxResponseBytes(), 1_048_576))
                : 65536;
        boolean canRetry = Boolean.TRUE.equals(config.getRetryable())
                && Boolean.TRUE.equals(config.getIdempotent());
        int attempts = canRetry
                ? Math.max(1, (config.getMaxRetries() != null ? config.getMaxRetries() : 0) + 1)
                : 1;
        attempts = Math.min(attempts, 4);
        for (int attempt = 1; attempt <= attempts; attempt++) {
            if (!IN_FLIGHT.tryAcquire(100, TimeUnit.MILLISECONDS)) {
                throw new CapacityExceededException();
            }
            ToolHttpResponse response;
            try {
                HttpResponse<InputStream> raw = HTTP_CLIENT.send(request,
                        HttpResponse.BodyHandlers.ofInputStream());
                byte[] bytes;
                try (InputStream input = raw.body()) {
                    bytes = input.readNBytes(maxBodyBytes + 1);
                }
                boolean tooLarge = bytes.length > maxBodyBytes;
                String body = tooLarge ? "" : new String(bytes, StandardCharsets.UTF_8);
                response = new ToolHttpResponse(raw.statusCode(), body, tooLarge);
            } finally {
                IN_FLIGHT.release();
            }
            if (response.tooLarge()) {
                return response;
            }
            if (response.statusCode() < 500 || attempt == attempts) {
                return response;
            }
            try {
                Thread.sleep(100L * attempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            }
        }
        throw new IllegalStateException("business-tool-request-failed");
    }

    private static String addConfiguredHeaders(HttpRequest.Builder builder, RobotToolConfig config) {
        if (config.getHeaders() == null) {
            return null;
        }
        for (Map.Entry<String, String> entry : config.getHeaders().entrySet()) {
            String name = entry.getKey() != null ? entry.getKey().trim() : "";
            String configuredValue = entry.getValue() != null ? entry.getValue().trim() : "";
            if (!StringUtils.hasText(name) || !StringUtils.hasText(configuredValue)) {
                continue;
            }
            String normalizedName = name.toLowerCase(Locale.ROOT);
            String idempotencyHeader = StringUtils.hasText(config.getIdempotencyHeader())
                    ? config.getIdempotencyHeader().trim().toLowerCase(Locale.ROOT)
                    : "idempotency-key";
            if (!HEADER_NAME_PATTERN.matcher(name).matches() || RESERVED_HEADERS.contains(normalizedName)
                    || normalizedName.equals(idempotencyHeader)) {
                return "business-tool-invalid-header:" + name;
            }
            if (SENSITIVE_HEADERS.contains(normalizedName)
                    && !ENV_PLACEHOLDER_PATTERN.matcher(configuredValue).matches()) {
                return "business-tool-sensitive-header-must-use-env:" + name;
            }
            String resolved = resolveEnvironmentValue(configuredValue);
            if (!StringUtils.hasText(resolved)) {
                return "business-tool-secret-unavailable:" + name;
            }
            if (containsLineBreak(resolved)) {
                return "business-tool-invalid-header-value:" + name;
            }
            builder.header(name, resolved);
        }
        return null;
    }

    private static String addSignatureHeaders(HttpRequest.Builder builder, URI endpoint, String body) {
        String secret = resolveSetting("BYTEDESK_BUSINESS_TOOL_SIGNING_SECRET");
        if (!StringUtils.hasText(secret)) {
            return null;
        }
        String timestamp = Long.toString(System.currentTimeMillis() / 1000L);
        String nonce = UUID.randomUUID().toString();
        String bodyHash = sha256Hex(body);
        String requestTarget = requestTarget(endpoint);
        String canonical = "POST\n" + endpoint.getHost().toLowerCase(Locale.ROOT) + "\n"
                + requestTarget + "\n" + timestamp + "\n" + nonce + "\n" + bodyHash;
        String signature;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            signature = HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return "business-tool-signing-unavailable";
        }
        builder.header("X-Bytedesk-Timestamp", timestamp)
                .header("X-Bytedesk-Nonce", nonce)
                .header("X-Bytedesk-Content-SHA256", bodyHash)
                .header("X-Bytedesk-Signature", signature);
        return null;
    }

    private static String requestTarget(URI endpoint) {
        String path = StringUtils.hasText(endpoint.getRawPath()) ? endpoint.getRawPath() : "/";
        return StringUtils.hasText(endpoint.getRawQuery()) ? path + "?" + endpoint.getRawQuery() : path;
    }

    private static String resolveEnvironmentValue(String value) {
        if (value.startsWith("${") && value.endsWith("}") && value.length() > 3) {
            String key = value.substring(2, value.length() - 1);
            String env = System.getenv(key);
            return StringUtils.hasText(env) ? env : System.getProperty("bytedesk.business-tool.env." + key);
        }
        return value;
    }

    private static String resolveSetting(String key) {
        String value = System.getenv(key);
        return StringUtils.hasText(value) ? value.trim() : System.getProperty(key);
    }

    private static boolean settingEnabled(String key) {
        return Boolean.parseBoolean(resolveSetting(key));
    }

    private static boolean containsLineBreak(String value) {
        return value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0;
    }

    private static boolean isSafeHeaderValue(String value) {
        if (!StringUtils.hasText(value) || containsLineBreak(value)) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String idempotencyKey(RobotProtobuf robot, String requestId, String intent,
            Map<String, String> slots) {
        String source = (robot.getOrgUid() != null ? robot.getOrgUid() : "") + "|"
                + (robot.getUid() != null ? robot.getUid() : "") + "|"
                + requestId + "|" + intent + "|" + slots;
        return sha256Hex(source);
    }

    private static String validateResponse(String body, RobotToolConfig config) {
        int maxBytes = config.getMaxResponseBytes() != null
                ? Math.max(1024, Math.min(config.getMaxResponseBytes(), 1_048_576))
                : 65536;
        if (body.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            return "business-tool-response-too-large";
        }
        if ("json".equalsIgnoreCase(config.getResponseFormat())) {
            try {
                JSON.parse(body);
            } catch (Exception e) {
                return "business-tool-response-invalid-json";
            }
        }
        return null;
    }

    private static String validateEndpoint(URI endpoint) {
        if (endpoint == null || !StringUtils.hasText(endpoint.getHost())
                || endpoint.getUserInfo() != null || endpoint.getFragment() != null) {
            return "business-tool-invalid-endpoint";
        }
        String scheme = endpoint.getScheme();
        boolean https = "https".equalsIgnoreCase(scheme);
        boolean http = "http".equalsIgnoreCase(scheme);
        if (!https && !http) {
            return "business-tool-invalid-endpoint-scheme";
        }
        if (http && !settingEnabled("BYTEDESK_BUSINESS_TOOL_ALLOW_HTTP")) {
            return "business-tool-http-not-allowed";
        }

        String host = endpoint.getHost().toLowerCase(Locale.ROOT);
        String allowlist = resolveSetting("BYTEDESK_BUSINESS_TOOL_ALLOWED_HOSTS");
        if (StringUtils.hasText(allowlist)) {
            Set<String> allowed = Arrays.stream(allowlist.split(","))
                    .map(String::trim)
                    .filter(StringUtils::hasText)
                    .map(value -> value.toLowerCase(Locale.ROOT))
                    .collect(Collectors.toSet());
            if (allowed.stream().noneMatch(candidate -> hostMatches(host, candidate))) {
                return "business-tool-host-not-allowed";
            }
        }

        boolean allowPrivate = settingEnabled("BYTEDESK_BUSINESS_TOOL_ALLOW_PRIVATE_HOSTS");
        if (!allowPrivate) {
            try {
                InetAddress[] addresses = InetAddress.getAllByName(host);
                for (InetAddress address : addresses) {
                    if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                            || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                            || address.isMulticastAddress()) {
                        return "business-tool-private-host-not-allowed";
                    }
                }
            } catch (Exception e) {
                return "business-tool-host-resolution-failed";
            }
        }
        return null;
    }

    private static boolean hostMatches(String host, String allowed) {
        if (allowed.startsWith("*.")) {
            String suffix = allowed.substring(1);
            return host.endsWith(suffix) && host.length() > suffix.length();
        }
        return host.equals(allowed);
    }

    private static long clampTimeout(Integer timeoutMs) {
        int value = timeoutMs != null ? timeoutMs : 4000;
        return Math.max(500, Math.min(value, 10_000));
    }

    private static RobotToolsSettingsEntity resolveToolsSettings(RobotEntity entity) {
        if (entity == null || entity.getSettings() == null) {
            return null;
        }
        return entity.getSettings().getToolsSettings();
    }

    private static RobotToolConfig findToolConfig(RobotToolsSettingsEntity settings, String toolKey,
            String intent) {
        for (RobotToolConfig config : settings.getToolConfigs()) {
            if (config == null || Boolean.FALSE.equals(config.getEnabled())
                    || !"HTTP_ENDPOINT".equalsIgnoreCase(config.getBindingType())) {
                continue;
            }
            String key = config.getKey() != null ? config.getKey().trim().toLowerCase(Locale.ROOT) : "";
            boolean keyMatches = key.equals(toolKey.toLowerCase(Locale.ROOT))
                    || key.endsWith("." + toolKey.toLowerCase(Locale.ROOT));
            boolean metadataMatches = config.getMetadata() != null
                    && intent.equalsIgnoreCase(String.valueOf(config.getMetadata().get("intent")));
            if (keyMatches || metadataMatches) {
                return config;
            }
        }
        return null;
    }

    private static boolean isHttpToolIntent(String intent) {
        return "ORDER_QUERY".equals(intent) || "ORDER_CREATE".equals(intent)
                || "LOGISTICS_QUERY".equals(intent)
                || "COMPLAINT".equals(intent);
    }

    private static String toolKeyFor(String intent) {
        return switch (intent) {
            case "ORDER_CREATE" -> "order.create";
            case "LOGISTICS_QUERY" -> "logistics.track";
            case "COMPLAINT" -> "complaint.create";
            default -> "order.lookup";
        };
    }

    private static Map<String, String> slotMap(List<BusinessSlot> slots) {
        Map<String, String> result = new LinkedHashMap<>();
        if (slots != null) {
            for (BusinessSlot slot : slots) {
                if (slot != null && StringUtils.hasText(slot.getName())) {
                    result.put(slot.getName(), slot.getValue());
                }
            }
        }
        return result;
    }

    private static final class CapacityExceededException extends Exception {
        private static final long serialVersionUID = 1L;
    }
}
