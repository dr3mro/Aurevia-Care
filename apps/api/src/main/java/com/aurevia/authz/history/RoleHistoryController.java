package com.aurevia.authz.history;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin HTTP adapter for the IAM-02 read-only membership-history API.
 *
 * <p>Only a GET mapping is exposed: history rows are append-only and there is no
 * update or delete route. All authorization and audit invariants live in the
 * injected domain service; no organization or actor is taken from the request.</p>
 */
@RestController
@RequestMapping("/api/v1/authz")
public class RoleHistoryController {

    private final RoleHistoryService service;

    /**
     * Create the controller.
     *
     * @param service injected role-history domain service
     */
    public RoleHistoryController(RoleHistoryService service) {
        this.service = service;
    }

    /**
     * Return the immutable role-change history of one staff subject.
     *
     * @param subject subject from the path
     * @return the append-only history visible in the active organization
     */
    @GetMapping("/role-history/{subject}")
    public RoleHistory history(@PathVariable String subject) {
        return service.history(subject);
    }
}
