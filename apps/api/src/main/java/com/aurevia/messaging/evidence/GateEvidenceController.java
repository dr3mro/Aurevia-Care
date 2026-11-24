package com.aurevia.messaging.evidence;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aurevia.messaging.evidence.GateEvidenceContract.EvidenceInput;
import com.aurevia.messaging.evidence.GateEvidenceContract.GateEvidenceReport;

/**
 * Thin HTTP adapter for the composite G3 gate-evidence report (OPS-02..OPS-05, UX-01).
 *
 * <p>The organization is taken from the request tenant context, never from the
 * fixture body; evaluation, hashing and the append-only ledger live in
 * {@link com.aurevia.messaging.evidence.GateEvidenceService}.</p>
 */
@RestController
@RequestMapping("/api/v1/gate-evidence")
public final class GateEvidenceController {

    private final GateEvidenceService service;

    /**
     * Create the route over the gate-evidence service bean.
     *
     * @param service composite evidence service
     */
    public GateEvidenceController(GateEvidenceService service) {
        this.service = service;
    }

    /**
     * Evaluate the synthetic fixture families and return the immutable report.
     *
     * @param input synthetic fixture families
     * @return the composite evidence report
     */
    @PostMapping("/produce")
    public GateEvidenceReport produce(@RequestBody EvidenceInput input) {
        return service.produce(org(), input);
    }

    /**
     * Read the active organization from the bound tenant context, failing closed.
     *
     * @return active organization identifier
     */
    private static String org() {
        com.aurevia.tenant.TenantContext context =
                com.aurevia.tenant.TenantContext.current();
        if (context == null) {
            throw new com.aurevia.api.ApiException(
                    401, "UNAUTHENTICATED", "authentication required");
        }
        return context.organizationId();
    }
}
