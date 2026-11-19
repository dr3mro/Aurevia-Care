package com.aurevia.smartclinic.g4.s_g4_01;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.g4.s_g4_01.RadCommands.AddendumCommand;
import com.aurevia.smartclinic.g4.s_g4_01.RadCommands.AccessionCommand;
import com.aurevia.smartclinic.g4.s_g4_01.RadCommands.AssociateStudyCommand;
import com.aurevia.smartclinic.g4.s_g4_01.RadCommands.CriticalCommunicationCommand;
import com.aurevia.smartclinic.g4.s_g4_01.RadCommands.IntakeCommand;
import com.aurevia.smartclinic.g4.s_g4_01.RadCommands.ScheduleCommand;
import com.aurevia.smartclinic.g4.s_g4_01.RadCommands.SignCommand;
import com.aurevia.smartclinic.g4.s_g4_01.RadTypes.InterpretationKind;
import com.aurevia.smartclinic.g4.s_g4_01.RadTypes.OrderState;
import com.aurevia.smartclinic.g4.s_g4_01.RadTypes.RadEventType;

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
                OrderState.INTAKE, 1, null, null, null, null, null, null, null, null);
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
     * Associate an external study only after patient and accession match the order.
     *
     * <p>A mismatch is refused, so a study of a different patient can never be
     * attached to this order. Image pixels are not retrieved; the partner
     * workstation reference is recorded for the reader.</p>
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
        requireMatch(current, command.patientId(), command.accession());
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
     * Record a critical communication as a separate fact from interpretation and release.
     *
     * @param command critical communication command with finding, recipient and read-back
     * @return the immutable domain event
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
                command.subject(), command.recipient(), command.finding(), clock.instant());
        appendEvent(command.organizationId(), event);
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

    private static void requireMatch(ImagingOrder current, String patientId, String accession) {
        if (!current.patientId().equals(patientId) || !current.accession().equals(accession)) {
            throw new ApiException(422, "LABEL_MISMATCH", "patient and accession must match the order");
        }
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
