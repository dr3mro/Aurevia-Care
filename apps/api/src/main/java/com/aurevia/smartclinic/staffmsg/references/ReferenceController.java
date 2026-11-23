package com.aurevia.smartclinic.staffmsg.references;

import java.net.URI;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.staff.StaffRepository;
import com.aurevia.smartclinic.staffmsg.references.ReferenceContract.AttachRequest;
import com.aurevia.smartclinic.staffmsg.references.ReferenceContract.AttachResult;
import com.aurevia.smartclinic.staffmsg.references.ReferenceContract.OpenResult;
import com.aurevia.smartclinic.staffmsg.references.ReferenceContract.ReferenceActor;
import com.aurevia.smartclinic.staffmsg.references.ReferenceContract.ReferenceHandle;
import com.aurevia.smartclinic.staffmsg.references.ReferenceContract.ReferenceView;
import com.aurevia.smartclinic.staffmsg.references.ReferenceContract.ScreenSubjectRequest;
import com.aurevia.smartclinic.staffmsg.references.ReferenceContract.SubjectWarning;
import com.aurevia.smartclinic.tenant.TenantContext;

/**
 * Thin HTTP entry point for typed staff-message references (MSG-07, ARC-07).
 *
 * <p>The controller only derives identity from the tenant context and fresh
 * active membership, then delegates to the domain service. No role, organization
 * or target tenant is accepted from the request body. Responses expose labels
 * before an access-checked open and never echo a subject line into a warning.</p>
 */
@RestController
@RequestMapping("/api/v1/staff/references")
public final class ReferenceController {

    private final ReferenceService service;
    private final StaffRepository staff;
    private final TransactionTemplate transactions;
    private final ReferenceActor fixedActor;

    /**
     * Create the route over injected collaborators.
     *
     * @param service      reference domain service
     * @param staff        fresh active-membership and role source
     * @param transactions tenant-guarded transaction template
     */
    @Autowired
    public ReferenceController(ReferenceService service, StaffRepository staff,
                               TransactionTemplate transactions) {
        this.service = service;
        this.staff = staff;
        this.transactions = transactions;
        this.fixedActor = null;
    }

    /**
     * Fixture constructor for standalone HTTP tests that drive the real service.
     *
     * @param service    reference domain service built over synthetic ports
     * @param fixedActor actor to use instead of a security context
     */
    ReferenceController(ReferenceService service, ReferenceActor fixedActor) {
        this.service = service;
        this.staff = null;
        this.transactions = null;
        this.fixedActor = fixedActor;
    }

    /**
     * Attach a validated reference and screen the subject line.
     *
     * @param request target handles, label and raw subject
     * @return HTTP 201 with the opaque reference and identifier-safe warning
     */
    @PostMapping(path = "/attach", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AttachResult> attach(@RequestBody AttachRequest request) {
        AttachResult result = service.attach(actor(), request);
        return ResponseEntity.created(URI.create("/api/v1/staff/references/view?id="
                + result.reference().id())).body(result);
    }

    /**
     * Read the label-only projection of a reference.
     *
     * @param id           opaque reference handle
     * @param connectionId opaque connection handle
     * @param kind         reference kind wire key
     * @param targetId     opaque target handle
     * @param label        non-identifying label
     * @return the label-only view
     */
    @GetMapping("/view")
    public ReferenceView view(@RequestParam("id") String id,
                              @RequestParam("connectionId") String connectionId,
                              @RequestParam("kind") String kind,
                              @RequestParam("targetId") String targetId,
                              @RequestParam("label") String label) {
        return service.view(actor(), new ReferenceHandle(id, connectionId, kind, targetId, label));
    }

    /**
     * Open a reference after re-running the recipient's access check.
     *
     * @param handle client-held reference descriptor
     * @return the target locator when access is granted
     */
    @PostMapping(path = "/open", consumes = MediaType.APPLICATION_JSON_VALUE)
    public OpenResult open(@RequestBody ReferenceHandle handle) {
        return service.open(actor(), handle);
    }

    /**
     * Screen a subject line before a sender attaches a reference.
     *
     * @param request command carrying the raw subject line in a POST body
     * @return a warning with a fixed reason code, never the subject text
     */
    @PostMapping(path = "/screen-subject", consumes = MediaType.APPLICATION_JSON_VALUE)
    public SubjectWarning screenSubject(@RequestBody ScreenSubjectRequest request) {
        return service.screenSubject(request.subject());
    }

    /**
     * Derive the actor from the tenant context and fresh active roles.
     *
     * @return the authenticated actor
     * @throws ApiException 401 when no authentication context is present
     */
    private ReferenceActor actor() {
        if (fixedActor != null) {
            return fixedActor;
        }
        TenantContext context = TenantContext.current();
        if (context == null) {
            throw new ApiException(401, "UNAUTHENTICATED", "authentication required");
        }
        Set<String> roles = Set.copyOf(transactions.execute(
                status -> staff.activeRoles(context.subject())));
        return ReferenceActor.staff(context.organizationId(), context.subject(),
                roles.toArray(String[]::new));
    }
}
