package com.bytedesk.core.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.bytedesk.core.rbac.user.UserDetailsImpl;

class OrganizationAccessResolverTest {

    private final OrganizationAccessResolver resolver = new OrganizationAccessResolver();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void regularAdministratorIsPinnedToOwnOrganization() {
        authenticate("org-1", false);

        assertEquals("org-1", resolver.resolve(null));
        assertEquals("org-1", resolver.resolve("org-1"));
        assertThrows(AccessDeniedException.class, () -> resolver.resolve("org-2"));
    }

    @Test
    void superAdministratorCanSelectAnotherOrganization() {
        authenticate("org-1", true);

        assertEquals("org-2", resolver.resolve("org-2"));
    }

    private void authenticate(String orgUid, boolean superUser) {
        UserDetailsImpl principal = mock(UserDetailsImpl.class);
        when(principal.getOrgUid()).thenReturn(orgUid);
        when(principal.getSuperUser()).thenReturn(superUser);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null));
    }
}
