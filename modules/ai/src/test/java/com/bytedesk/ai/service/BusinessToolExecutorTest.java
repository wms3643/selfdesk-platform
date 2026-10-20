package com.bytedesk.ai.service;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.bytedesk.ai.robot.RobotEntity;
import com.bytedesk.ai.robot.RobotProtobuf;
import com.bytedesk.ai.robot.RobotRestService;
import com.bytedesk.ai.robot_settings.RobotSettingsEntity;
import com.bytedesk.ai.robot_tool.RobotToolConfig;
import com.bytedesk.ai.robot_tool.RobotToolsSettingsEntity;
import com.sun.net.httpserver.HttpServer;

class BusinessToolExecutorTest {

    @Test
    void executesConfiguredHttpOrderToolWithValidatedSlots() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> idempotencyKey = new AtomicReference<>();
        AtomicReference<String> customHeader = new AtomicReference<>();
        AtomicReference<String> signature = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        System.setProperty("BYTEDESK_BUSINESS_TOOL_ALLOW_HTTP", "true");
        System.setProperty("BYTEDESK_BUSINESS_TOOL_ALLOW_PRIVATE_HOSTS", "true");
        System.setProperty("bytedesk.business-tool.env.TEST_API_KEY", "test-key");
        System.setProperty("BYTEDESK_BUSINESS_TOOL_SIGNING_SECRET", "test-signing-secret");
        server.createContext("/order", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            idempotencyKey.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            customHeader.set(exchange.getRequestHeaders().getFirst("X-Api-Key"));
            signature.set(exchange.getRequestHeaders().getFirst("X-Bytedesk-Signature"));
            byte[] response = "{\"status\":\"SHIPPED\",\"trackingNo\":\"SF123\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            RobotToolConfig toolConfig = RobotToolConfig.builder()
                    .key("business.order.lookup")
                    .enabled(true)
                    .bindingType("HTTP_ENDPOINT")
                    .endpoint("http://127.0.0.1:" + server.getAddress().getPort() + "/order")
                    .headers(Map.of("X-Api-Key", "${TEST_API_KEY}"))
                    .responseFormat("json")
                    .metadata(Map.of("intent", "ORDER_QUERY"))
                    .build();
            RobotToolsSettingsEntity toolsSettings = RobotToolsSettingsEntity.builder()
                    .toolConfigs(List.of(toolConfig))
                    .build();
            RobotSettingsEntity settings = RobotSettingsEntity.builder()
                    .toolsSettings(toolsSettings)
                    .build();
            RobotEntity entity = RobotEntity.builder()
                    .uid("robot-1")
                    .settings(settings)
                    .build();
            RobotRestService robotRestService = mock(RobotRestService.class);
            when(robotRestService.findByUid("robot-1")).thenReturn(Optional.of(entity));

            BusinessIntentEvaluation evaluation = new BusinessIntentEvaluation(
                    List.of(new BusinessIntentMatch("ORDER_QUERY", 0.95,
                            List.of(new BusinessSlot("orderNo", "A123456")))),
                    BusinessIntentAction.TOOL, List.of(), null, "test");

            BusinessToolResult result = BusinessToolExecutor.execute(
                    robotRestService,
                    RobotProtobuf.builder().uid("robot-1").orgUid("org-1").build(),
                    "订单A123456到哪了",
                    evaluation,
                    new BusinessToolExecutor.InvocationContext("request-1", "thread-1", "message-1"));

            assertTrue(result.success());
            assertTrue(result.content().contains("SHIPPED"));
            assertTrue(requestBody.get().contains("A123456"));
            assertTrue(requestBody.get().contains("request-1"));
            assertTrue(!requestBody.get().contains("到哪了"));
            assertTrue(idempotencyKey.get() != null && !idempotencyKey.get().isBlank());
            assertTrue("test-key".equals(customHeader.get()));
            assertTrue(signature.get() != null && !signature.get().isBlank());
        } finally {
            server.stop(0);
            System.clearProperty("BYTEDESK_BUSINESS_TOOL_ALLOW_HTTP");
            System.clearProperty("BYTEDESK_BUSINESS_TOOL_ALLOW_PRIVATE_HOSTS");
            System.clearProperty("bytedesk.business-tool.env.TEST_API_KEY");
            System.clearProperty("BYTEDESK_BUSINESS_TOOL_SIGNING_SECRET");
        }
    }

    @Test
    void rejectsLiteralSecretsAndNonHttpToolBindings() {
        System.setProperty("BYTEDESK_BUSINESS_TOOL_ALLOW_HTTP", "true");
        System.setProperty("BYTEDESK_BUSINESS_TOOL_ALLOW_PRIVATE_HOSTS", "true");
        try {
            RobotToolConfig literalSecret = RobotToolConfig.builder()
                    .key("business.order.lookup")
                    .enabled(true)
                    .bindingType("HTTP_ENDPOINT")
                    .endpoint("http://127.0.0.1:1/order")
                    .headers(Map.of("Authorization", "Bearer plain-text"))
                    .metadata(Map.of("intent", "ORDER_QUERY"))
                    .build();
            RobotRestService robotRestService = mockRobot(literalSecret);

            BusinessToolResult result = BusinessToolExecutor.execute(
                    robotRestService,
                    RobotProtobuf.builder().uid("robot-1").orgUid("org-1").build(),
                    "订单A123456",
                    orderEvaluation());

            assertTrue(!result.success());
            assertTrue(result.error().contains("sensitive-header-must-use-env"));

            RobotToolConfig nonHttpBinding = RobotToolConfig.builder()
                    .key("business.order.lookup")
                    .enabled(true)
                    .bindingType("CLASS")
                    .endpoint("http://127.0.0.1:1/order")
                    .metadata(Map.of("intent", "ORDER_QUERY"))
                    .build();
            BusinessToolResult wrongBinding = BusinessToolExecutor.execute(
                    mockRobot(nonHttpBinding),
                    RobotProtobuf.builder().uid("robot-1").orgUid("org-1").build(),
                    "订单A123456",
                    orderEvaluation());
            assertTrue(!wrongBinding.success());
            assertTrue(wrongBinding.error().contains("not-configured"));
        } finally {
            System.clearProperty("BYTEDESK_BUSINESS_TOOL_ALLOW_HTTP");
            System.clearProperty("BYTEDESK_BUSINESS_TOOL_ALLOW_PRIVATE_HOSTS");
        }
    }

    @Test
    void blocksPrivateNetworkTargetsUnlessExplicitlyAllowed() {
        System.setProperty("BYTEDESK_BUSINESS_TOOL_ALLOW_HTTP", "true");
        try {
            RobotToolConfig toolConfig = RobotToolConfig.builder()
                    .key("business.order.lookup")
                    .enabled(true)
                    .bindingType("HTTP_ENDPOINT")
                    .endpoint("http://127.0.0.1:1/order")
                    .metadata(Map.of("intent", "ORDER_QUERY"))
                    .build();

            BusinessToolResult result = BusinessToolExecutor.execute(
                    mockRobot(toolConfig),
                    RobotProtobuf.builder().uid("robot-1").orgUid("org-1").build(),
                    "订单A123456",
                    orderEvaluation());

            assertTrue(!result.success());
            assertTrue(result.error().contains("private-host-not-allowed"));
        } finally {
            System.clearProperty("BYTEDESK_BUSINESS_TOOL_ALLOW_HTTP");
        }
    }

    private static RobotRestService mockRobot(RobotToolConfig toolConfig) {
        RobotToolsSettingsEntity toolsSettings = RobotToolsSettingsEntity.builder()
                .toolConfigs(List.of(toolConfig))
                .build();
        RobotSettingsEntity settings = RobotSettingsEntity.builder()
                .toolsSettings(toolsSettings)
                .build();
        RobotEntity entity = RobotEntity.builder()
                .uid("robot-1")
                .settings(settings)
                .build();
        RobotRestService robotRestService = mock(RobotRestService.class);
        when(robotRestService.findByUid("robot-1")).thenReturn(Optional.of(entity));
        return robotRestService;
    }

    private static BusinessIntentEvaluation orderEvaluation() {
        return new BusinessIntentEvaluation(
                List.of(new BusinessIntentMatch("ORDER_QUERY", 0.95,
                        List.of(new BusinessSlot("orderNo", "A123456")))),
                BusinessIntentAction.TOOL, List.of(), null, "test");
    }
}
