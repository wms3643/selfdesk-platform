package com.bytedesk.ai.demo_order;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.bytedesk.ai.robot.RobotEntity;
import com.bytedesk.ai.robot.RobotRestService;
import com.bytedesk.ai.robot_settings.RobotSettingsEntity;
import com.bytedesk.ai.robot_settings.RobotSettingsRestService;
import com.bytedesk.ai.robot_tool.RobotToolConfig;
import com.bytedesk.ai.robot_tool.RobotToolsSettingsEntity;
import com.bytedesk.core.constant.BytedeskConsts;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "bytedesk.demo-order-api.enabled", havingValue = "true")
public class DemoBusinessToolInitializer implements ApplicationRunner {

    private final RobotRestService robotRestService;
    private final RobotSettingsRestService robotSettingsRestService;

    @Value("${server.port:9003}")
    private int serverPort;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        RobotEntity robot = robotRestService.findByUid(BytedeskConsts.DEFAULT_ROBOT_UID).orElse(null);
        if (robot == null || robot.getSettings() == null) {
            log.warn("Demo business tools skipped: default robot settings not found");
            return;
        }
        RobotSettingsEntity settings = robot.getSettings();
        RobotToolsSettingsEntity tools = settings.getToolsSettings();
        if (tools == null) {
            tools = new RobotToolsSettingsEntity();
        }
        List<RobotToolConfig> configs = tools.getToolConfigs() != null
                ? new ArrayList<>(tools.getToolConfigs())
                : new ArrayList<>();
        RobotToolConfig orderTool = configureTool(configs, "business.order.lookup", "ORDER_QUERY",
                "/internal/demo/orders/query");
        RobotToolConfig orderCreateTool = configureTool(configs, "business.order.create", "ORDER_CREATE",
                "/internal/demo/orders/create");
        RobotToolConfig complaintTool = configureTool(configs, "business.complaint.create", "COMPLAINT",
                "/internal/demo/complaints/create");
        tools.setEnabled(true);
        tools.setToolConfigs(configs);
        settings.setToolsSettings(tools);
        robotSettingsRestService.save(settings);
        log.info("Demo business tools configured for robot={}, orderEndpoint={}, orderCreateEndpoint={}, complaintEndpoint={}",
                robot.getUid(), orderTool.getEndpoint(), orderCreateTool.getEndpoint(), complaintTool.getEndpoint());
    }

    private RobotToolConfig configureTool(List<RobotToolConfig> configs, String key, String intent,
            String path) {
        RobotToolConfig tool = configs.stream()
                .filter(config -> config != null && key.equals(config.getKey()))
                .findFirst()
                .orElseGet(() -> RobotToolConfig.defaultSpringAiTools().stream()
                        .filter(config -> key.equals(config.getKey()))
                        .findFirst()
                        .orElseThrow());
        tool.setEnabled(true)
                .setBindingType("HTTP_ENDPOINT")
                .setEndpoint("http://127.0.0.1:" + serverPort + path)
                .setHeaders(Map.of("X-Bytedesk-Tool-Token", "${BYTEDESK_DEMO_ORDER_TOKEN}"))
                .setResponseFormat("json")
                .setRequiresApproval(false)
                .setIdempotent(true)
                .setIdempotencyHeader("Idempotency-Key")
                .setMetadata(Map.of("intent", intent));
        if (!configs.contains(tool)) {
            configs.add(tool);
        }
        return tool;
    }
}
