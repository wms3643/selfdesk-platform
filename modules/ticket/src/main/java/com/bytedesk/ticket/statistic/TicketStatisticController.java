package com.bytedesk.ticket.statistic;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
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

@Tag(name = "Ticket Statistics", description = "Ticket dashboard statistics")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/ticket/statistic")
@PreAuthorize(RolePermissions.ROLE_ADMIN)
public class TicketStatisticController {

    private final TicketStatisticService statisticService;
    private final OrganizationAccessResolver organizationAccessResolver;

    @Operation(summary = "Get ticket statistics for a date range")
    @GetMapping("/query/date")
    public ResponseEntity<?> queryByDate(
            @RequestParam(required = false) String orgUid,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ResponseEntity.ok(JsonResult.success(
                statisticService.forPeriod(organizationAccessResolver.resolve(orgUid),
                        startDate, endDate)));
    }
}
