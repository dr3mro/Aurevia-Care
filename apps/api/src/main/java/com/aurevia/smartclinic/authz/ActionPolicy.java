package com.aurevia.smartclinic.authz;

import java.util.Set;

import org.springframework.stereotype.Component;

/** Bounded action policy for the G0 facility slice. */
@Component
public class ActionPolicy {

    private static final Set<String> ADMIN_ACTIONS = Set.of("FACILITY_READ", "FACILITY_WRITE");

    public boolean allows(String action, String role) {
        return "tenant_admin".equals(role) && ADMIN_ACTIONS.contains(action);
    }
}
