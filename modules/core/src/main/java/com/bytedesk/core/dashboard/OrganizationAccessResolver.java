package com.bytedesk.core.dashboard;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.bytedesk.core.rbac.user.UserDetailsImpl;

/**
 * Resolves the organization used by management statistics.
 *
 * Super administrators may inspect another organization explicitly. Every
 * other administrator is pinned to the organization on the authenticated
 * principal.
 */
@Component
public class OrganizationAccessResolver {

    public String resolve(String requestedOrgUid) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof UserDetailsImpl user)) {
            throw new AccessDeniedException("Authenticated administrator is required");
        }

        String currentOrgUid = user.getOrgUid();
        String normalizedRequestedOrgUid = StringUtils.hasText(requestedOrgUid)
                ? requestedOrgUid.trim()
                : null;

        if (Boolean.TRUE.equals(user.getSuperUser()) && normalizedRequestedOrgUid != null) {
            return normalizedRequestedOrgUid;
        }
        if (normalizedRequestedOrgUid != null
                && !normalizedRequestedOrgUid.equalsIgnoreCase(currentOrgUid)) {
            throw new AccessDeniedException("Cannot access another organization's statistics");
        }
        if (!StringUtils.hasText(currentOrgUid)) {
            throw new AccessDeniedException("Current organization is unavailable");
        }
        return currentOrgUid;
    }
}
