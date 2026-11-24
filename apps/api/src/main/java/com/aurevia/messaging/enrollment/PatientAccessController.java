package com.aurevia.messaging.enrollment;

import java.net.URI;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.aurevia.api.ApiException;
import com.aurevia.messaging.enrollment.EnrollmentContract.AccessDecision;
import com.aurevia.messaging.enrollment.EnrollmentContract.AccessEvent;
import com.aurevia.messaging.enrollment.EnrollmentContract.AccessToken;
import com.aurevia.messaging.enrollment.EnrollmentContract.AccountView;
import com.aurevia.messaging.enrollment.EnrollmentContract.ChangeContactRequest;
import com.aurevia.messaging.enrollment.EnrollmentContract.EnrollRequest;
import com.aurevia.messaging.enrollment.EnrollmentContract.GenericNotification;
import com.aurevia.messaging.enrollment.EnrollmentContract.Invitation;
import com.aurevia.messaging.enrollment.EnrollmentContract.IssueInvitationRequest;
import com.aurevia.messaging.enrollment.EnrollmentContract.OpenCaseRequest;
import com.aurevia.messaging.enrollment.EnrollmentContract.PatientAccount;
import com.aurevia.messaging.enrollment.EnrollmentContract.RecoverRequest;
import com.aurevia.messaging.enrollment.EnrollmentContract.SensitiveChangeRequest;
import com.aurevia.messaging.enrollment.EnrollmentContract.SupportCase;
import com.aurevia.staff.StaffRepository;
import com.aurevia.tenant.TenantContext;

/**
 * Thin, tenant-scoped HTTP adapter for the PAT-03 patient-access workflow (PAT-03,
 * IAM-01, IAM-02, IAM-05, ARC-02, ARC-04, OPS-02).
 *
 * <p>Every authorization, single-use, lockout and support decision lives in
 * {@link com.aurevia.messaging.enrollment.PatientAccessService}; this
 * adapter only binds command fields, derives the caller snapshot from verified
 * request state and returns domain values. The organization comes solely from
 * {@link com.aurevia.tenant.TenantContext} and roles are re-resolved
 * from active membership on every request inside the tenant-guarded transaction,
 * so neither can be supplied or widened through the request body.</p>
 */
@RestController
@RequestMapping("/api/v1/patient/access")
public final class PatientAccessController {

    /** Base path for the patient-access commands. */
    public static final String BASE_PATH = "/api/v1/patient/access";

    private final PatientAccessService service;
    private final StaffRepository staff;
    private final TransactionTemplate transactions;

    /**
     * Create the route over the injected domain service and fresh role source.
     *
     * @param service      PAT-03 domain service
     * @param staff        fresh membership and role source
     * @param transactions tenant-guarded transaction template
     */
    @Autowired
    public PatientAccessController(PatientAccessService service, StaffRepository staff,
                                   TransactionTemplate transactions) {
        this.service = service;
        this.staff = staff;
        this.transactions = transactions;
    }

    /**
     * Issue a site-verified, single-use enrollment invitation.
     *
     * @param command issue command
     * @return the created invitation with HTTP 201
     */
    @PostMapping("/invitations")
    public ResponseEntity<Invitation> issueInvitation(@RequestBody IssueInvitationRequest command) {
        Invitation invitation = service.issueInvitation(actor(), command);
        return ResponseEntity.created(URI.create(BASE_PATH + "/invitations/" + invitation.code())).body(invitation);
    }

    /**
     * Redeem a single-use invitation into a verified patient account.
     *
     * @param command enrollment command
     * @return the verified account
     */
    @PostMapping("/invitations/redeem")
    public PatientAccount enroll(@RequestBody EnrollRequest command) {
        return service.enroll(actor(), command);
    }

    /**
     * Issue a single-use recovery code to a verified account subject.
     *
     * @param accountId account identifier
     * @return the recovery credential with HTTP 201
     */
    @PostMapping("/accounts/{accountId}/recovery")
    public ResponseEntity<AccessToken> issueRecovery(@PathVariable String accountId) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.issueRecovery(actor(), accountId));
    }

    /**
     * Restore access with a single-use recovery code and re-verified facts.
     *
     * @param command recovery command
     * @return the restored account
     */
    @PostMapping("/accounts/recovery/redeem")
    public PatientAccount recover(@RequestBody RecoverRequest command) {
        return service.recover(actor(), command);
    }

    /**
     * Clear a lockout after an authorized review.
     *
     * @param accountId account identifier
     * @return the unlocked account
     */
    @PostMapping("/accounts/{accountId}/unlock")
    public PatientAccount unlock(@PathVariable String accountId) {
        return service.unlock(actor(), accountId);
    }

    /**
     * Issue a short-lived, purpose-bound renewed-authentication token.
     *
     * @param purpose sensitive action the token is bound to
     * @return the re-authentication credential with HTTP 201
     */
    @PostMapping("/reauth")
    public ResponseEntity<AccessToken> issueReauth(@RequestParam String purpose) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.issueReauth(actor(), purpose));
    }

    /**
     * Apply a sensitive change after valid renewed authentication.
     *
     * @param command sensitive-change command
     * @return the updated account
     */
    @PostMapping("/accounts/sensitive-change")
    public PatientAccount sensitiveChange(@RequestBody SensitiveChangeRequest command) {
        return service.sensitiveChange(actor(), command);
    }

    /**
     * Replace a recovery contact as staff without changing account ownership.
     *
     * @param command contact-change command
     * @return the updated account
     */
    @PostMapping("/accounts/contact")
    public PatientAccount changeStaffContact(@RequestBody ChangeContactRequest command) {
        return service.changeStaffContact(actor(), command);
    }

    /**
     * Check whether a verified chart link currently permits access.
     *
     * @param accountId account identifier
     * @param chartId   chart identifier
     * @return an explicit allow or deny decision
     */
    @GetMapping("/accounts/{accountId}/chart-access")
    public AccessDecision checkChartAccess(@PathVariable String accountId, @RequestParam String chartId) {
        return service.checkChartAccess(actor(), accountId, chartId);
    }

    /**
     * Open a support case for an identity dispute, accessibility need or record request.
     *
     * @param command open-case command
     * @return the created case with HTTP 201
     */
    @PostMapping("/cases")
    public ResponseEntity<SupportCase> openCase(@RequestBody OpenCaseRequest command) {
        SupportCase supportCase = service.openCase(actor(), command);
        return ResponseEntity.created(URI.create(BASE_PATH + "/cases/" + supportCase.id())).body(supportCase);
    }

    /**
     * Assign an open support case to a named queue owner.
     *
     * @param caseId   case identifier
     * @param assignee named queue owner
     * @return the assigned case
     */
    @PostMapping("/cases/{caseId}/assign")
    public SupportCase assignCase(@PathVariable String caseId, @RequestParam String assignee) {
        return service.assignCase(actor(), caseId, assignee);
    }

    /**
     * Resolve an in-review support case.
     *
     * @param caseId case identifier
     * @param note   non-sensitive resolution note
     * @return the resolved case
     */
    @PostMapping("/cases/{caseId}/resolve")
    public SupportCase resolveCase(@PathVariable String caseId, @RequestParam String note) {
        return service.resolveCase(actor(), caseId, note);
    }

    /**
     * Read an account with its support cases and access facts.
     *
     * @param accountId account identifier
     * @return the account view
     */
    @GetMapping("/accounts/{accountId}/view")
    public AccountView accountView(@PathVariable String accountId) {
        return service.accountView(actor(), accountId);
    }

    /**
     * Read the tenant's append-only access audit.
     *
     * @return access facts in insertion order
     */
    @GetMapping("/audit")
    public List<AccessEvent> audit() {
        return service.audit(actor());
    }

    /**
     * Read the tenant's generic notifications.
     *
     * @return generic notifications in insertion order
     */
    @GetMapping("/notifications")
    public List<GenericNotification> notifications() {
        return service.notifications(actor());
    }

    /**
     * Build a fresh actor from the bound tenant context and current membership
     * roles, failing closed when no context is bound.
     *
     * <p>Roles are read inside the tenant-guarded transaction so forced row-level
     * security confines them to the active organization and a deactivation takes
     * effect on the very next request (IAM-01, IAM-02).</p>
     *
     * @return the actor for this request
     */
    private PatientAccessActor actor() {
        TenantContext context = TenantContext.current();
        if (context == null) {
            throw new ApiException(401, "UNAUTHENTICATED", "authentication required");
        }
        Set<String> roles = Set.copyOf(transactions.execute(status -> staff.activeRoles(context.subject())));
        return new PatientAccessActor(context.organizationId(), context.subject(), roles);
    }
}
