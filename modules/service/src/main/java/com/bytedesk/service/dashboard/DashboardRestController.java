package com.bytedesk.service.dashboard;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.bytedesk.core.dashboard.OrganizationAccessResolver;
import com.bytedesk.core.rbac.role.RolePermissions;
import com.bytedesk.core.utils.JsonResult;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@Tag(name = "Dashboard", description = "Organization business overview")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/dashboard")
@PreAuthorize(RolePermissions.ROLE_ADMIN)
public class DashboardRestController {

    private final DashboardService dashboardService;
    private final OrganizationAccessResolver organizationAccessResolver;

    @Operation(summary = "Get Dashboard Overview",
            description = "Members, visitors, conversations, messages and AI request totals for an organization")
    @GetMapping("/overview")
    public ResponseEntity<?> overview(@RequestParam(required = false) String orgUid) {
        Map<String, Object> overview = dashboardService
                .overview(organizationAccessResolver.resolve(orgUid));
        return ResponseEntity.ok(JsonResult.success(overview));
    }
}
