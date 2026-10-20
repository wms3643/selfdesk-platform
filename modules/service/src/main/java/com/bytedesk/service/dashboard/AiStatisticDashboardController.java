package com.bytedesk.service.dashboard;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.bytedesk.ai.statistic.AiTokenUsageService;
import com.bytedesk.core.config.metrics.BytedeskMetrics;
import com.bytedesk.core.dashboard.OrganizationAccessResolver;
import com.bytedesk.core.rbac.role.RolePermissions;
import com.bytedesk.core.utils.JsonResult;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@Tag(name = "AI Statistics", description = "AI usage statistics for administrators")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/ai/statistic/token")
@PreAuthorize(RolePermissions.ROLE_ADMIN)
public class AiStatisticDashboardController {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final BytedeskMetrics metrics;
    private final AiTokenUsageService aiTokenUsageService;
    private final OrganizationAccessResolver organizationAccessResolver;

    @Operation(summary = "Get organization token statistics")
    @GetMapping("/query/org")
    public ResponseEntity<?> queryOrganization(@RequestParam(required = false) String orgUid) {
        String resolvedOrgUid = organizationAccessResolver.resolve(orgUid);
        ZonedDateTime start = LocalDate.now(BUSINESS_ZONE).atStartOfDay(BUSINESS_ZONE);
        ZonedDateTime end = start.plusDays(1);
        Map<String, Object> result = new LinkedHashMap<>(
                aiTokenUsageService.summary(resolvedOrgUid, start, end));
        result.put("organizationUid", resolvedOrgUid);
        result.put("requestsSinceStartup", metrics.getAiRequestsCount());
        result.put("historicalUsageAvailable", true);
        return ResponseEntity.ok(JsonResult.success(result));
    }

    @Operation(summary = "Get hourly token statistics")
    @GetMapping("/hourly")
    public ResponseEntity<?> queryHourly(
            @RequestParam(required = false) String orgUid,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        String resolvedOrgUid = organizationAccessResolver.resolve(orgUid);
        Map<String, Object> result = new LinkedHashMap<>(
                aiTokenUsageService.hourly(resolvedOrgUid, date));
        result.put("organizationUid", resolvedOrgUid);
        result.put("historicalUsageAvailable", true);
        return ResponseEntity.ok(JsonResult.success(result));
    }

    @Operation(summary = "Get token usage grouped by provider and model")
    @GetMapping("/models")
    public ResponseEntity<?> queryModels(@RequestParam(required = false) String orgUid) {
        String resolvedOrgUid = organizationAccessResolver.resolve(orgUid);
        return ResponseEntity.ok(JsonResult.success(
                aiTokenUsageService.modelBreakdown(resolvedOrgUid)));
    }
}
