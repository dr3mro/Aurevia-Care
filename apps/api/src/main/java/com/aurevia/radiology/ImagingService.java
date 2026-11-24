package com.aurevia.radiology;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.aurevia.api.ApiException;
import com.aurevia.clinical.notes.NoteChecksum;
import com.aurevia.radiology.RadCommands.AddendumCommand;
import com.aurevia.radiology.RadCommands.AccessionCommand;
import com.aurevia.radiology.RadCommands.AssociateStudyCommand;
import com.aurevia.radiology.RadCommands.CriticalCommunicationCommand;
import com.aurevia.radiology.RadCommands.IntakeCommand;
import com.aurevia.radiology.RadCommands.ScheduleCommand;
import com.aurevia.radiology.RadCommands.SignCommand;
import com.aurevia.radiology.RadCommands.StudyReviewCommand;
import com.aurevia.radiology.RadTypes.InterpretationKind;
import com.aurevia.radiology.RadTypes.OrderState;
import com.aurevia.radiology.RadTypes.RadEventType;
import com.aurevia.radiology.RadTypes.StudyLinkReviewStatus;

/**
 * RAD-01 imaging domain kernel: intake, scheduling, accession, study association,
 * signed interpretation and linked addenda.
 *
 * <p>The service is a tenant-scoped domain kernel. It owns append-only order and
 * interpretation revisions and immutable domain facts, and evaluates every
 * command only against approved, effective configuration. It deliberately has
 * no HTTP, persistence or outbox dependency: a later story with a migration and
 * controller persists the returned revisions and events inside one guarded
 * transaction. The caller supplies verified membership roles, the active tenant
 * and a version precondition and must re-check them on every command.</p>
 *
 * <p>External images are never fetched or copied. Only the Study Instance UID and
 * a partner diagnostic workstation reference are recorded, so images remain
 * under partner access control. Signing an interpretation, notifying the
 * referrer and recording a critical communication are separate facts.</p>
 */
public final class ImagingService {

    private final RadPolicyCatalog policies;
    private final Clock clock;
    private final Map<String, Map<String, List<ImagingOrder>>> orders = new HashMap<>();
    private final Map<String, Map<String, List<Interpretation>>> interpretations = new HashMap<>();
    private final Map<String, List<RadEvent>> events = new HashMap<>();
    private final Map<String, Set<String>> accessions = new HashMap<>();
    private final Map<String, Map<String, StudyLinkReview>> studyLinkReviews = new HashMap<>();

    /**
     * Create the imaging service.
     *
     * @param policies approved effective-dated radiology configuration
     * @param clock    application clock used for signing time and event occurrence
     */
    public ImagingService(RadPolicyCatalog policies, Clock clock) {
        this.policies = policies;
        this.clock = clock;
    }

    /**
     * Receive an imaging order with its urgency, clinical question and destination.
     *
     * <p>An approved modality may be captured at intake; when it is absent the
     * scheduling step records it. Urgency and the clinical question are always
     * captured here and copied to every later order revision.</p>
     *
     * @param command intake command with authenticated actor and matched patient
     * @return the initial order revision
     * @throws ApiException on invalid input, missing permission or a duplicate order identifier
     */
    public ImagingOrder intake(IntakeCommand command) {
        requireText(command.organizationId(), "INVALID_ORGANIZATION", "active organization is required");
        requireText(command.subject(), "INVALID_ACTOR", "actor is required");
        requireText(command.orderId(), "INVALID_ORDER", "order identifier is required");
        requireText(command.patientId(), "INVALID_PATIENT", "patient identifier is required");
        requireText(command.orderCode(), "INVALID_ORDER_CODE", "order code is required");
        requireText(command.clinicalQuestion(), "NO_CLINICAL_QUESTION", "a clinical question is required");
        requireText(command.sourceActor(), "INVALID_SOURCE", "source actor is required");
        requireText(command.destination(), "INVALID_DESTINATION", "destination is required");
        requireText(command.workQueue(), "INVALID_QUEUE", "responsible work queue is required");
        requireText(command.referrer(), "INVALID_REFERRER", "referrer is required");
        if (command.urgency() == null) {
            throw new ApiException(422, "INVALID_URGENCY", "urgency is required");
        }
        if (command.urgency().requiresNamedOwner()) {
            requireText(command.responsibleClinician(), "OWNER_REQUIRED",
                    "a stat order requires a named responsible clinician");
        }
        if (command.modality() != null && !policies.modalityApproved(command.modality())) {
            throw new ApiException(422, "UNSUPPORTED_MODALITY",
                    "the modality is not on the approved configuration");
        }
        if (!RadRules.mayIntake(command.roles())) {
            throw forbidden("intake");
        }
        Map<String, List<ImagingOrder>> tenant = orders.computeIfAbsent(
                command.organizationId(), key -> new HashMap<>());
        if (tenant.containsKey(command.orderId())) {
            throw new ApiException(409, "DUPLICATE_ORDER", "the order identifier already exists");
        }
        ImagingOrder revision = new ImagingOrder(command.organizationId(), command.orderId(),
                command.patientId(), command.encounterId(), command.orderCode(), command.urgency(),
                command.clinicalQuestion(), command.sourceActor(), command.destination(),
                command.responsibleClinician(), command.workQueue(), command.referrer(),
                OrderState.INTAKE, 1, null, command.modality(), null, null, null, null, null, null);
        appendOrder(tenant, revision);
        emit(revision, RadEventType.ORDER_INTAKED, null, null, command.subject());
        return revision;
    }

    /**
     * Record a scheduled study time and an approved modality.
     *
     * @param command schedule command with the expected order version
     * @return the new scheduled order revision
     * @throws ApiException when denied, concealed, stale, unsupported or illegally transitioned
     */
    public ImagingOrder schedule(ScheduleCommand command) {
        requireText(command.organizationId(), "INVALID_ORGANIZATION", "active organization is required");
        requireText(command.subject(), "INVALID_ACTOR", "actor is required");
        requireText(command.orderId(), "INVALID_ORDER", "order identifier is required");
        if (command.scheduledAt() == null) {
            throw new ApiException(422, "INVALID_SCHEDULE", "a scheduled time is required");
        }
        if (!RadRules.maySchedule(command.roles())) {
            throw forbidden("schedule");
        }
        ImagingOrder current = load(command.organizationId(), command.orderId());
        requireVersion(current, command.expectedVersion());
        requireTransition(current, OrderState.SCHEDULED);
        if (!policies.modalityApproved(command.modality())) {
            throw new ApiException(422, "UNSUPPORTED_MODALITY",
                    "the modality is not on the approved configuration");
        }
        ImagingOrder scheduled = new ImagingOrder(current.organizationId(), current.orderId(),
                current.patientId(), current.encounterId(), current.orderCode(), current.urgency(),
                current.clinicalQuestion(), current.sourceActor(), current.destination(),
                current.responsibleClinician(), current.workQueue(), current.referrer(),
                OrderState.SCHEDULED, current.version() + 1, command.scheduledAt(), command.modality(),
                null, null, null, null, null, null);
        appendOrder(orders.get(command.organizationId()), scheduled);
        emit(scheduled, RadEventType.ORDER_SCHEDULED, command.modality().wire(), null, command.subject());
        return scheduled;
    }

    /**
     * Assign an issuer-scoped accession that is unique within the active tenant.
     *
     * @param command accession command with the expected order version
     * @return the new accessioned order revision
     * @throws ApiException when denied, concealed, stale, duplicated or illegally transitioned
     */
    public ImagingOrder accession(AccessionCommand command) {
        requireText(command.organizationId(), "INVALID_ORGANIZATION", "active organization is required");
        requireText(command.subject(), "INVALID_ACTOR", "actor is required");
        requireText(command.orderId(), "INVALID_ORDER", "order identifier is required");
        requireText(command.accession(), "INVALID_ACCESSION", "accession is required");
        requireText(command.accessionIssuer(), "INVALID_ISSUER", "accession issuer is required");
        if (!RadRules.mayAccession(command.roles())) {
            throw forbidden("accession");
        }
        ImagingOrder current = load(command.organizationId(), command.orderId());
        requireVersion(current, command.expectedVersion());
        requireTransition(current, OrderState.ACCESSIONED);
        Set<String> tenantAccessions = accessions.computeIfAbsent(
                command.organizationId(), key -> new HashSet<>());
        String accessionKey = command.accessionIssuer() + "|" + command.accession();
        if (tenantAccessions.contains(accessionKey)) {
            throw new ApiException(409, "DUPLICATE_ACCESSION",
                    "the accession is already used in this tenant");
        }
        tenantAccessions.add(accessionKey);
        ImagingOrder accessioned = new ImagingOrder(current.organizationId(), current.orderId(),
                current.patientId(), current.encounterId(), current.orderCode(), current.urgency(),
                current.clinicalQuestion(), current.sourceActor(), current.destination(),
                current.responsibleClinician(), current.workQueue(), current.referrer(),
                OrderState.ACCESSIONED, current.version() + 1, current.scheduledAt(), current.modality(),
                command.accession(), command.accessionIssuer(), null, null, null, null);
        appendOrder(orders.get(command.organizationId()), accessioned);
        emit(accessioned, RadEventType.ACCESSION_RECORDED, accessionKey, null, command.subject());
        return accessioned;
    }

    /**
     * Associate an external study only after patient and accession labels match the order.
     *
     * <p>The caller supplies labels already verified upstream; a mismatch between
     * those labels and the order is refused and opens an item in the study-link
     * review queue while leaving the order revision untouched. Binding the Study Instance UID to a
     * patient is the responsibility of the partner PACS/DICOMweb verification
     * step, not of this in-memory kernel. Image pixels are never retrieved; only
     * the UID and the partner workstation reference are recorded for the reader.</p>
     *
     * @param command association command with the verified patient and accession
     * @return the new study-associated order revision
     * @throws ApiException when denied, concealed, stale, mismatched or illegally transitioned
     */
    public ImagingOrder associateStudy(AssociateStudyCommand command) {
        requireText(command.organizationId(), "INVALID_ORGANIZATION", "active organization is required");
        requireText(command.subject(), "INVALID_ACTOR", "actor is required");
        requireText(command.orderId(), "INVALID_ORDER", "order identifier is required");
        requireText(command.studyInstanceUid(), "INVALID_STUDY", "study instance UID is required");
        requireText(command.workstationRef(), "INVALID_WORKSTATION", "partner workstation is required");
        requireText(command.patientId(), "INVALID_PATIENT", "patient identifier is required");
        requireText(command.accession(), "INVALID_ACCESSION", "accession is required");
        if (!RadRules.mayAssociate(command.roles())) {
            throw forbidden("study association");
        }
        ImagingOrder current = load(command.organizationId(), command.orderId());
        requireVersion(current, command.expectedVersion());
        requireTransition(current, OrderState.STUDY_ASSOCIATED);
        if (!matches(current, command.patientId(), command.accession())) {
            queueStudyLinkReview(current, command);
            throw new ApiException(422, "LABEL_MISMATCH", "patient and accession must match the order");
        }
        ImagingOrder associated = new ImagingOrder(current.organizationId(), current.orderId(),
                current.patientId(), current.encounterId(), current.orderCode(), current.urgency(),
                current.clinicalQuestion(), current.sourceActor(), current.destination(),
                current.responsibleClinician(), current.workQueue(), current.referrer(),
                OrderState.STUDY_ASSOCIATED, current.version() + 1, current.scheduledAt(),
                current.modality(), current.accession(), current.accessionIssuer(),
                command.studyInstanceUid(), command.workstationRef(), command.patientId(),
                command.accession());
        appendOrder(orders.get(command.organizationId()), associated);
        emit(associated, RadEventType.STUDY_ASSOCIATED, command.studyInstanceUid(), null, command.subject());
        return associated;
    }

    /**
     * Read the study-link review queue of one tenant, newest first (RAD-01).
     *
     * <p>The queue is append-only across resolutions: a resolved review is
     * replaced by its resolved revision rather than deleted, so the decision
     * trail survives a later re-link. The caller must pass the active tenant;
     * a tenant with no reviews gets an empty queue.</p>
     *
     * @param organizationId active tenant
     * @return immutable review revisions in the order they were raised
     */
    public List<StudyLinkReview> studyLinkReviews(String organizationId) {
        Map<String, StudyLinkReview> tenant = studyLinkReviews.get(organizationId);
        if (tenant == null) {
            return List.of();
        }
        return List.copyOf(tenant.values());
    }

    /**
     * Resolve a study-link review after an authorized reviewer checked the labels (RAD-01).
     *
     * <p>Resolving does not link the study. A fresh {@link com.aurevia.radiology.ImagingService#associateStudy(com.aurevia.radiology.RadCommands.AssociateStudyCommand)}
     * with matching labels is still required, so a review never bypasses the
     * patient and accession verification.</p>
     *
     * @param command review command carrying the active tenant and reviewer
     * @return the new resolved review revision
     * @throws ApiException when denied, concealed, stale or already resolved
     */
    public StudyLinkReview resolveStudyLinkReview(StudyReviewCommand command) {
        requireText(command.organizationId(), "INVALID_ORGANIZATION", "active organization is required");
        requireText(command.subject(), "INVALID_ACTOR", "actor is required");
        requireText(command.orderId(), "INVALID_ORDER", "order identifier is required");
        requireText(command.studyInstanceUid(), "INVALID_STUDY", "study instance UID is required");
        requireText(command.resolution(), "RESOLUTION_REQUIRED", "a review resolution is required");
        if (!RadRules.mayAssociate(command.roles())) {
            throw forbidden("study link review");
        }
        Map<String, StudyLinkReview> tenant = studyLinkReviews.get(command.organizationId());
        StudyLinkReview current = tenant == null ? null
                : tenant.get(reviewKey(command.orderId(), command.studyInstanceUid()));
        if (current == null) {
            throw new ApiException(404, "REVIEW_NOT_FOUND", "study link review not found");
        }
        if (current.status() == StudyLinkReviewStatus.RESOLVED) {
            throw new ApiException(409, "REVIEW_ALREADY_RESOLVED", "the review is already resolved");
        }
        StudyLinkReview resolved = current.resolve(command.subject(), command.resolution(),
                clock.instant());
        tenant.put(reviewKey(command.orderId(), command.studyInstanceUid()), resolved);
        emitReview(resolved, RadEventType.STUDY_LINK_REVIEW_RESOLVED, command.subject(),
                load(command.organizationId(), command.orderId()).version());
        return resolved;
    }

    /**
     * Sign an interpretation of an associated study (CLN-05).
     *
     * @param command sign command carrying the clinical narrative and attestation
     * @return the initial immutable interpretation revision
     * @throws ApiException when denied, concealed, stale, mismatched or illegally transitioned
     */
    public Interpretation sign(SignCommand command) {
        requireText(command.organizationId(), "INVALID_ORGANIZATION", "active organization is required");
        requireText(command.subject(), "INVALID_ACTOR", "actor is required");
        requireText(command.orderId(), "INVALID_ORDER", "order identifier is required");
        requireText(command.patientId(), "INVALID_PATIENT", "patient identifier is required");
        requireText(command.accession(), "INVALID_ACCESSION", "accession is required");
        requireText(command.studyInstanceUid(), "INVALID_STUDY", "study instance UID is required");
        requireText(command.technique(), "TECHNIQUE_REQUIRED", "technique is required");
        requireText(command.comparison(), "COMPARISON_REQUIRED", "comparison is required");
        requireText(command.findings(), "FINDINGS_REQUIRED", "findings are required");
        requireText(command.impression(), "IMPRESSION_REQUIRED", "impression is required");
        requireText(command.followUp(), "FOLLOW_UP_REQUIRED", "follow-up is required");
        requireText(command.templateVersion(), "TEMPLATE_REQUIRED", "template version is required");
        requireText(command.attestation(), "ATTESTATION_REQUIRED", "signer attestation is required");
        if (!RadRules.mayInterpret(command.roles())) {
            throw forbidden("interpretation");
        }
        ImagingOrder current = load(command.organizationId(), command.orderId());
        requireVersion(current, command.expectedVersion());
        requireTransition(current, OrderState.SIGNED);
        requireStudyMatch(current, command.patientId(), command.studyInstanceUid());
        requireAccessionMatch(current, command.accession());
        if (!policies.mayInterpret(command.subject(), current.modality(), clock.instant())) {
            throw forbidden("imaging privilege");
        }
        Instant at = clock.instant();
        Interpretation signed = new Interpretation(command.organizationId(), command.orderId(), 1,
                null, InterpretationKind.INITIAL, command.patientId(), command.studyInstanceUid(),
                command.technique(), command.comparison(), command.findings(), command.impression(),
                command.followUp(), command.subject(), command.attestation(), at,
                command.templateVersion(), null);
        appendInterpretation(command.organizationId(), command.orderId(), signed);
        ImagingOrder signedOrder = copyOrder(current, OrderState.SIGNED);
        appendOrder(orders.get(command.organizationId()), signedOrder);
        emit(signedOrder, RadEventType.INTERPRETATION_SIGNED, current.referrer(), signed.checksum(),
                command.subject());
        notifyReferrer(signedOrder, command.subject());
        return signed;
    }

    /**
     * Link a signed addendum to the current interpretation and notify the referrer.
     *
     * @param command addendum command with a required reason and attestation
     * @return the new addendum revision
     * @throws ApiException when denied, concealed, stale, mismatched or illegally transitioned
     */
    public Interpretation addAddendum(AddendumCommand command) {
        requireText(command.organizationId(), "INVALID_ORGANIZATION", "active organization is required");
        requireText(command.subject(), "INVALID_ACTOR", "actor is required");
        requireText(command.orderId(), "INVALID_ORDER", "order identifier is required");
        requireText(command.patientId(), "INVALID_PATIENT", "patient identifier is required");
        requireText(command.studyInstanceUid(), "INVALID_STUDY", "study instance UID is required");
        requireText(command.findings(), "FINDINGS_REQUIRED", "addendum findings are required");
        requireText(command.impression(), "IMPRESSION_REQUIRED", "addendum impression is required");
        requireText(command.reason(), "REASON_REQUIRED", "an addendum reason is required");
        requireText(command.attestation(), "ATTESTATION_REQUIRED", "signer attestation is required");
        if (!RadRules.mayInterpret(command.roles())) {
            throw forbidden("addendum");
        }
        ImagingOrder current = load(command.organizationId(), command.orderId());
        requireVersion(current, command.expectedVersion());
        requireTransition(current, OrderState.ADDENDED);
        requireStudyMatch(current, command.patientId(), command.studyInstanceUid());
        if (!policies.mayInterpret(command.subject(), current.modality(), clock.instant())) {
            throw forbidden("imaging privilege");
        }
        Interpretation previous = currentInterpretation(command.organizationId(), command.orderId());
        Interpretation addendum = new Interpretation(command.organizationId(), command.orderId(),
                previous.version() + 1, previous.version(), InterpretationKind.ADDENDUM,
                command.patientId(), command.studyInstanceUid(), previous.technique(),
                previous.comparison(), command.findings(), command.impression(), previous.followUp(),
                command.subject(), command.attestation(), clock.instant(), previous.templateVersion(),
                command.reason());
        appendInterpretation(command.organizationId(), command.orderId(), addendum);
        ImagingOrder addendedOrder = copyOrder(current, OrderState.ADDENDED);
        appendOrder(orders.get(command.organizationId()), addendedOrder);
        emit(addendedOrder, RadEventType.ADDENDUM_LINKED, current.referrer(), addendum.checksum(),
                command.subject());
        notifyReferrer(addendedOrder, command.subject());
        return addendum;
    }

    /**
     * Record a critical communication and its read-back as separate facts.
     *
     * <p>The finding and read-back narratives are never placed in an event: only
     * their SHA-256 digests are kept, so an operational fact cannot disclose a
     * protected clinical payload (ARC-06, OPS-02). Read-back is a distinct fact
     * (XCH-05) so a communication without confirmed read-back is detectable.</p>
     *
     * @param command critical communication command with finding, recipient and read-back
     * @return the immutable critical-communication event
     * @throws ApiException when denied, concealed, stale, mismatched or before study association
     */
    public RadEvent recordCriticalCommunication(CriticalCommunicationCommand command) {
        requireText(command.organizationId(), "INVALID_ORGANIZATION", "active organization is required");
        requireText(command.subject(), "INVALID_ACTOR", "actor is required");
        requireText(command.orderId(), "INVALID_ORDER", "order identifier is required");
        requireText(command.patientId(), "INVALID_PATIENT", "patient identifier is required");
        requireText(command.studyInstanceUid(), "INVALID_STUDY", "study instance UID is required");
        requireText(command.finding(), "FINDING_REQUIRED", "critical finding is required");
        requireText(command.recipient(), "RECIPIENT_REQUIRED", "recipient is required");
        requireText(command.readBack(), "READ_BACK_REQUIRED", "read-back evidence is required");
        if (!RadRules.mayRecordCritical(command.roles())) {
            throw forbidden("critical communication");
        }
        ImagingOrder current = load(command.organizationId(), command.orderId());
        requireVersion(current, command.expectedVersion());
        if (current.studyInstanceUid() == null) {
            throw new ApiException(409, "STUDY_NOT_ASSOCIATED", "no study is associated with this order");
        }
        requireStudyMatch(current, command.patientId(), command.studyInstanceUid());
        RadEvent event = new RadEvent(command.organizationId(), RadEventType.CRITICAL_COMMUNICATION,
                command.orderId(), current.version(), current.patientId(), current.accession(),
                command.subject(), command.recipient(), NoteChecksum.sha256(command.finding()),
                clock.instant());
        appendEvent(command.organizationId(), event);
        appendEvent(command.organizationId(), new RadEvent(command.organizationId(),
                RadEventType.READ_BACK, command.orderId(), current.version(), current.patientId(),
                current.accession(), command.subject(), command.recipient(),
                NoteChecksum.sha256(command.readBack()), clock.instant()));
        return event;
    }

    /**
     * Read the current imaging order revision, concealing orders outside the active tenant.
     *
     * @param organizationId active tenant
     * @param orderId        tenant-local order identifier
     * @return the current order revision
     * @throws ApiException when the order is not found in this tenant
     */
    public ImagingOrder read(String organizationId, String orderId) {
        return load(organizationId, orderId);
    }

    /**
     * Read the append-only order revision history in the active tenant.
     *
     * @param organizationId active tenant
     * @param orderId        tenant-local order identifier
     * @return revisions oldest first
     * @throws ApiException when the order is not found in this tenant
     */
    public List<ImagingOrder> history(String organizationId, String orderId) {
        return List.copyOf(loadRevisions(organizationId, orderId));
    }

    /**
     * Read the current signed interpretation of an order.
     *
     * @param organizationId active tenant
     * @param orderId        tenant-local order identifier
     * @return the latest interpretation or addendum revision
     * @throws ApiException when no interpretation exists in this tenant
     */
    public Interpretation interpretation(String organizationId, String orderId) {
        return currentInterpretation(organizationId, orderId);
    }

    /**
     * Read the append-only interpretation revision history of an order.
     *
     * @param organizationId active tenant
     * @param orderId        tenant-local order identifier
     * @return interpretation revisions oldest first
     * @throws ApiException when no interpretation exists in this tenant
     */
    public List<Interpretation> interpretations(String organizationId, String orderId) {
        Map<String, List<Interpretation>> tenant = interpretations.get(organizationId);
        List<Interpretation> revisions = tenant == null ? null : tenant.get(orderId);
        if (revisions == null) {
            throw notFound();
        }
        return List.copyOf(revisions);
    }

    /**
     * Return the immutable domain facts emitted for one tenant (ARC-02).
     *
     * @param organizationId active tenant
     * @return that tenant's events in emission order, or an empty list
     */
    public List<RadEvent> events(String organizationId) {
        return List.copyOf(events.getOrDefault(organizationId, List.of()));
    }

    private void notifyReferrer(ImagingOrder order, String actor) {
        emit(order, RadEventType.REFERRER_NOTIFIED, order.referrer(), null, actor);
    }

    private void emit(ImagingOrder order, RadEventType type, String destination, String digest,
                      String actor) {
        appendEvent(order.organizationId(), new RadEvent(order.organizationId(), type, order.orderId(),
                order.version(), order.patientId(), order.accession(), actor, destination, digest,
                clock.instant()));
    }

    private void appendEvent(String organizationId, RadEvent event) {
        events.computeIfAbsent(organizationId, key -> new ArrayList<>()).add(event);
    }

    private void appendOrder(Map<String, List<ImagingOrder>> tenant, ImagingOrder revision) {
        tenant.computeIfAbsent(revision.orderId(), key -> new ArrayList<>()).add(revision);
    }

    private void appendInterpretation(String organizationId, String orderId, Interpretation revision) {
        interpretations.computeIfAbsent(organizationId, key -> new HashMap<>())
                .computeIfAbsent(orderId, key -> new ArrayList<>()).add(revision);
    }

    private ImagingOrder load(String organizationId, String orderId) {
        List<ImagingOrder> revisions = loadRevisions(organizationId, orderId);
        return revisions.get(revisions.size() - 1);
    }

    private List<ImagingOrder> loadRevisions(String organizationId, String orderId) {
        Map<String, List<ImagingOrder>> tenant = orders.get(organizationId);
        List<ImagingOrder> revisions = tenant == null ? null : tenant.get(orderId);
        if (revisions == null) {
            throw notFound();
        }
        return revisions;
    }

    private Interpretation currentInterpretation(String organizationId, String orderId) {
        Map<String, List<Interpretation>> tenant = interpretations.get(organizationId);
        List<Interpretation> revisions = tenant == null ? null : tenant.get(orderId);
        if (revisions == null) {
            throw notFound();
        }
        return revisions.get(revisions.size() - 1);
    }

    private static ImagingOrder copyOrder(ImagingOrder source, OrderState state) {
        return new ImagingOrder(source.organizationId(), source.orderId(), source.patientId(),
                source.encounterId(), source.orderCode(), source.urgency(), source.clinicalQuestion(),
                source.sourceActor(), source.destination(), source.responsibleClinician(),
                source.workQueue(), source.referrer(), state, source.version() + 1,
                source.scheduledAt(), source.modality(), source.accession(), source.accessionIssuer(),
                source.studyInstanceUid(), source.workstationRef(), source.verifiedPatientId(),
                source.verifiedAccession());
    }

    private static void requireText(String value, String code, String message) {
        if (value == null || value.isBlank()) {
            throw new ApiException(422, code, message);
        }
    }

    private static void requireVersion(ImagingOrder current, int expectedVersion) {
        if (current.version() != expectedVersion) {
            throw new ApiException(412, "STALE_VERSION", "the order changed since it was read");
        }
    }

    private static void requireTransition(ImagingOrder current, OrderState target) {
        if (!current.state().canTransitionTo(target)) {
            throw new ApiException(409, "INVALID_TRANSITION", "the order cannot enter that state");
        }
    }

    private static boolean matches(ImagingOrder current, String patientId, String accession) {
        return current.patientId().equals(patientId) && current.accession().equals(accession);
    }

    /**
     * Raise one review per order and study so a repeated mismatch cannot duplicate the queue item.
     */
    private void queueStudyLinkReview(ImagingOrder current, AssociateStudyCommand command) {
        String key = reviewKey(current.orderId(), command.studyInstanceUid());
        Map<String, StudyLinkReview> tenant = studyLinkReviews.computeIfAbsent(
                current.organizationId(), ignored -> new LinkedHashMap<>());
        StudyLinkReview review = new StudyLinkReview(current.organizationId(), current.orderId(),
                command.studyInstanceUid(), command.patientId(), command.accession(),
                current.patientId(), current.accession(), StudyLinkReviewStatus.OPEN, null, null,
                clock.instant(), null);
        if (tenant.putIfAbsent(key, review) == null) {
            emitReview(review, RadEventType.STUDY_LINK_REVIEW_OPENED, command.subject(),
                    current.version());
        }
    }

    private void emitReview(StudyLinkReview review, RadEventType type, String actor, int orderVersion) {
        appendEvent(review.organizationId(), new RadEvent(review.organizationId(), type,
                review.orderId(), orderVersion, review.expectedPatientId(),
                review.expectedAccession(), actor, review.studyInstanceUid(), null, clock.instant()));
    }

    private static String reviewKey(String orderId, String studyInstanceUid) {
        return orderId + "|" + studyInstanceUid;
    }

    private static void requireStudyMatch(ImagingOrder current, String patientId,
                                          String studyInstanceUid) {
        if (!current.patientId().equals(patientId)
                || !current.studyInstanceUid().equals(studyInstanceUid)) {
            throw new ApiException(422, "LABEL_MISMATCH", "patient and study must match the order");
        }
    }

    private static void requireAccessionMatch(ImagingOrder current, String accession) {
        if (!current.accession().equals(accession)) {
            throw new ApiException(422, "LABEL_MISMATCH", "accession must match the order");
        }
    }

    private static ApiException forbidden(String action) {
        return new ApiException(403, "FORBIDDEN", "missing permission for " + action);
    }

    private static ApiException notFound() {
        return new ApiException(404, "NOT_FOUND", "imaging order not found");
    }
}
