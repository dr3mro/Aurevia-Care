package com.aurevia.therapy;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.aurevia.api.ApiException;
import com.aurevia.clinical.notes.NoteChecksum;
import com.aurevia.therapy.TherapyContract.AckRequest;
import com.aurevia.therapy.TherapyContract.Actor;
import com.aurevia.therapy.TherapyContract.ClinicalNote;
import com.aurevia.therapy.TherapyContract.Episode;
import com.aurevia.therapy.TherapyContract.EpisodeState;
import com.aurevia.therapy.TherapyContract.Evaluation;
import com.aurevia.therapy.TherapyContract.EvaluationRequest;
import com.aurevia.therapy.TherapyContract.Goal;
import com.aurevia.therapy.TherapyContract.Intervention;
import com.aurevia.therapy.TherapyContract.InterventionRequest;
import com.aurevia.therapy.TherapyContract.Measurement;
import com.aurevia.therapy.TherapyContract.NoteKind;
import com.aurevia.therapy.TherapyContract.NoteRequest;
import com.aurevia.therapy.TherapyContract.OutcomeReading;
import com.aurevia.therapy.TherapyContract.OutcomeRequest;
import com.aurevia.therapy.TherapyContract.OutcomeScale;
import com.aurevia.therapy.TherapyContract.ReferralRequest;
import com.aurevia.therapy.TherapyContract.ReturnAcknowledgment;
import com.aurevia.therapy.TherapyContract.ScaleRequest;
import com.aurevia.therapy.TherapyContract.Summary;
import com.aurevia.therapy.TherapyContract.SummaryRequest;
import com.aurevia.therapy.TherapyContract.TherapyAggregate;
import com.aurevia.therapy.TherapyContract.TherapyAuditEvent;

/**
 * Domain service for PT-01 physical-therapy episodes.
 *
 * <p>Every command is authorized against the supplied {@code Actor} snapshot and
 * appends an append-only audit fact in the same operation. The integrating
 * caller must supply an actor freshly derived from active membership on every
 * request (IAM-01). All state changes require the caller's {@code expectedVersion}
 * as an {@code If-Match} precondition (412 on mismatch). A patient id supplied
 * with a clinical command is checked against the episode's owning patient, and
 * signed notes are immutable.</p>
 */
@Service
public class TherapyService {

    /** Physical-therapy clinician who may author, sign and discharge. */
    public static final String PT_CLINICIAN = "pt_clinician";
    /** PT coordinator who may send approved summaries and record acknowledgments. */
    public static final String PT_COORDINATOR = "pt_coordinator";
    /** Tenant administrator who defines clinical scales. */
    public static final String TENANT_ADMIN = "tenant_admin";
    /** Clinical governance role that approves scale versions. */
    public static final String CLINICAL_GOVERNANCE = "clinical_governance";
    /** Privacy auditor role that reads the audit trail. */
    public static final String PRIVACY_AUDITOR = "privacy_auditor";

    private static final String ACTION_ACCEPT = "EPISODE_ACCEPT";
    private static final String ACTION_EVALUATION = "EVALUATION_RECORD";
    private static final String ACTION_OUTCOME = "OUTCOME_RECORD";
    private static final String ACTION_INTERVENTION = "INTERVENTION_RECORD";
    private static final String ACTION_NOTE = "NOTE_RECORD";
    private static final String ACTION_SIGN = "NOTE_SIGN";
    private static final String ACTION_SUMMARY = "SUMMARY_SEND";
    private static final String ACTION_ACK = "RETURN_ACK";
    private static final String ACTION_SCALE_DEFINE = "SCALE_DEFINE";
    private static final String ACTION_SCALE_APPROVE = "SCALE_APPROVE";
    private static final String ACTION_READ_EPISODE = "THERAPY_READ";
    private static final String ACTION_READ_SCALE = "SCALE_READ";
    private static final String ACTION_AUDIT = "AUDIT_READ";
    private static final String OUTCOME_SUCCESS = "SUCCESS";
    private static final String OUTCOME_DENIED = "DENIED";
    private static final String OUTCOME_NOT_FOUND = "NOT_FOUND";
    private static final String OUTCOME_CONFLICT = "CONFLICT";
    private static final String OUTCOME_INVALID = "INVALID";

    /**
     * Declarative server-side action policy (IAM-01). Unknown actions resolve to
     * an empty role set, so authorization always fails closed. Clinical reads are
     * restricted to PT staff; scale reads additionally allow governance and admin.
     */
    private static final Map<String, Set<String>> ACTION_ROLES = Map.ofEntries(
            Map.entry(ACTION_ACCEPT, Set.of(PT_CLINICIAN, PT_COORDINATOR)),
            Map.entry(ACTION_SUMMARY, Set.of(PT_CLINICIAN, PT_COORDINATOR)),
            Map.entry(ACTION_EVALUATION, Set.of(PT_CLINICIAN)),
            Map.entry(ACTION_OUTCOME, Set.of(PT_CLINICIAN)),
            Map.entry(ACTION_INTERVENTION, Set.of(PT_CLINICIAN)),
            Map.entry(ACTION_NOTE, Set.of(PT_CLINICIAN)),
            Map.entry(ACTION_SIGN, Set.of(PT_CLINICIAN)),
            Map.entry(ACTION_ACK, Set.of(PT_CLINICIAN, PT_COORDINATOR)),
            Map.entry(ACTION_SCALE_DEFINE, Set.of(TENANT_ADMIN)),
            Map.entry(ACTION_SCALE_APPROVE, Set.of(CLINICAL_GOVERNANCE)),
            Map.entry(ACTION_READ_EPISODE, Set.of(PT_CLINICIAN, PT_COORDINATOR)),
            Map.entry(ACTION_READ_SCALE, Set.of(TENANT_ADMIN, CLINICAL_GOVERNANCE, PT_CLINICIAN)),
            Map.entry(ACTION_AUDIT, Set.of(PRIVACY_AUDITOR, TENANT_ADMIN)));

    private final TherapyStore store;
    private final Clock clock;

    /**
     * Create the service over a tenant-scoped store.
     *
     * @param store persistence boundary for episodes, scales and audit
     * @param clock application clock for system timestamps
     */
    public TherapyService(TherapyStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /**
     * Accept a referral into a new therapy episode owned by the active tenant.
     *
     * @param actor   caller identity
     * @param request referral command
     * @return the created active episode
     * @throws ApiException on invalid input, denial or a duplicate referral
     */
    public Episode acceptReferral(Actor actor, ReferralRequest request) {
        authorize(actor, ACTION_ACCEPT, "referral");
        String referralId = text(request.referralId(), "referralId", 128);
        String patientId = uuid(request.patientId(), "patientId");
        String referrerOrg = text(request.referrerOrg(), "referrerOrg", 128);
        String referrerSubject = text(request.referrerSubject(), "referrerSubject", 128);
        String receivedAt = text(request.receivedAt(), "receivedAt", 64);
        String question = text(request.clinicalQuestion(), "clinicalQuestion", 500);
        if (store.hasReferral(actor.organizationId(), referralId)) {
            audit(actor, ACTION_ACCEPT, referralId, OUTCOME_CONFLICT);
            throw conflict("referral already accepted");
        }
        Episode episode = new Episode(newId(), actor.organizationId(), patientId, referralId,
                referrerOrg, referrerSubject, EpisodeState.ACTIVE, 1, clock.instant(),
                receivedAt, question);
        store.save(new TherapyAggregate(episode, null, List.of(), List.of(), List.of(), null, null));
        audit(actor, ACTION_ACCEPT, episode.id(), OUTCOME_SUCCESS);
        return episode;
    }

    /**
     * Record the initial evaluation and open treatment.
     *
     * @param actor          caller identity
     * @param episodeId      owning episode
     * @param request        evaluation command
     * @param expectedVersion version read by the caller
     * @return the recorded evaluation
     * @throws ApiException on invalid input, denial, a wrong patient or a stale version
     */
    public Evaluation recordEvaluation(Actor actor, String episodeId, EvaluationRequest request,
                                       int expectedVersion) {
        return recordEvaluation(actor, episodeId, request, null, expectedVersion);
    }

    /**
     * Record the initial evaluation with an explicit patient binding.
     *
     * @param actor          caller identity
     * @param episodeId      owning episode
     * @param request        evaluation command
     * @param patientId      expected patient, or {@code null} to use the episode patient
     * @param expectedVersion version read by the caller
     * @return the recorded evaluation
     * @throws ApiException on invalid input, denial, a wrong patient or a stale version
     */
    public Evaluation recordEvaluation(Actor actor, String episodeId, EvaluationRequest request,
                                       String patientId, int expectedVersion) {
        authorize(actor, ACTION_EVALUATION, episodeId);
        if (request == null || request.problems() == null || request.baselines() == null
                || request.goals() == null || request.problems().isEmpty()) {
            audit(actor, ACTION_EVALUATION, episodeId, OUTCOME_INVALID);
            throw invalid("INVALID_EVALUATION", "an evaluation needs problems, baselines and goals");
        }
        if (request.frequencyPerWeek() <= 0 || request.durationWeeks() <= 0) {
            audit(actor, ACTION_EVALUATION, episodeId, OUTCOME_INVALID);
            throw invalid("INVALID_PLAN", "frequency and duration must be positive");
        }
        for (Measurement measurement : request.baselines()) {
            if (blank(measurement.unit())) {
                audit(actor, ACTION_EVALUATION, episodeId, OUTCOME_INVALID);
                throw invalid("MISSING_UNIT", "a baseline measurement needs a unit");
            }
        }
        for (Goal goal : request.goals()) {
            if (blank(goal.description())) {
                audit(actor, ACTION_EVALUATION, episodeId, OUTCOME_INVALID);
                throw invalid("INVALID_GOAL", "a goal needs a description");
            }
        }
        TherapyAggregate aggregate = load(actor, episodeId, ACTION_EVALUATION);
        requireVersion(actor, ACTION_EVALUATION, episodeId, aggregate, expectedVersion);
        requirePatient(actor, ACTION_EVALUATION, episodeId, aggregate, patientId);
        requireState(actor, ACTION_EVALUATION, episodeId, aggregate, EpisodeState.ACTIVE);
        Evaluation evaluation = new Evaluation(episodeId, aggregate.episode().patientId(),
                request.problems(), request.baselines(), request.goals(), request.frequencyPerWeek(),
                request.durationWeeks(), actor.subject(), clock.instant(), 1);
        Episode nextEpisode = withState(aggregate.episode(), EpisodeState.IN_TREATMENT);
        store.save(aggregate.withEvaluation(evaluation).withEpisode(nextEpisode));
        audit(actor, ACTION_EVALUATION, episodeId, OUTCOME_SUCCESS);
        return evaluation;
    }

    /**
     * Define a new pending version of an outcome scale.
     *
     * @param actor   caller identity
     * @param request scale definition
     * @return the pending scale version
     * @throws ApiException on invalid input, denial or a duplicate scale key
     */
    public OutcomeScale defineOutcomeScale(Actor actor, ScaleRequest request) {
        authorize(actor, ACTION_SCALE_DEFINE, "scale");
        String key = text(request.key(), "key", 64);
        String description = text(request.description(), "description", 200);
        String unit = text(request.unit(), "unit", 32);
        if (request.permittedValues() == null || request.permittedValues().isEmpty()) {
            audit(actor, ACTION_SCALE_DEFINE, key, OUTCOME_INVALID);
            throw invalid("INVALID_VALUES", "an outcome scale needs permitted values");
        }
        for (String value : request.permittedValues()) {
            if (blank(value)) {
                audit(actor, ACTION_SCALE_DEFINE, key, OUTCOME_INVALID);
                throw invalid("INVALID_VALUE", "permitted values must be non-blank");
            }
        }
        int version = store.allocateScaleVersion(actor.organizationId(), key);
        OutcomeScale scale = new OutcomeScale(actor.organizationId(), key, version, description,
                unit, request.permittedValues(), false, null);
        store.saveScale(scale);
        audit(actor, ACTION_SCALE_DEFINE, key, OUTCOME_SUCCESS);
        return scale;
    }

    /**
     * Approve a pending outcome-scale version as clinical governance.
     *
     * @param actor   caller identity
     * @param key     scale key
     * @param version scale version
     * @return the approved scale
     * @throws ApiException on denial, an unknown version or a duplicate approval
     */
    public OutcomeScale approveOutcomeScale(Actor actor, String key, int version) {
        authorize(actor, ACTION_SCALE_APPROVE, key);
        text(key, "key", 64);
        OutcomeScale found = store.scale(actor.organizationId(), key, version).orElse(null);
        if (found == null) {
            audit(actor, ACTION_SCALE_APPROVE, key, OUTCOME_NOT_FOUND);
            throw notFound("outcome scale version not found");
        }
        if (found.approved()) {
            audit(actor, ACTION_SCALE_APPROVE, key, OUTCOME_CONFLICT);
            throw conflict("outcome scale already approved");
        }
        OutcomeScale approved = new OutcomeScale(found.organizationId(), found.key(),
                found.version(), found.description(), found.unit(), found.permittedValues(),
                true, actor.subject());
        store.saveScale(approved);
        audit(actor, ACTION_SCALE_APPROVE, key, OUTCOME_SUCCESS);
        return approved;
    }

    /**
     * List all versions of an outcome scale in the active organization.
     *
     * @param actor caller identity
     * @param key   scale key
     * @return immutable version history, oldest first
     * @throws ApiException on denial
     */
    public List<OutcomeScale> versions(Actor actor, String key) {
        authorize(actor, ACTION_READ_SCALE, key);
        List<OutcomeScale> all = store.scales(actor.organizationId(), key);
        audit(actor, ACTION_READ_SCALE, key, OUTCOME_SUCCESS);
        return all;
    }

    /**
     * Record an outcome reading bound to an approved scale version and unit.
     *
     * @param actor          caller identity
     * @param episodeId      owning episode
     * @param request        reading command
     * @param expectedVersion version read by the caller
     * @return the recorded reading
     * @throws ApiException on invalid input, denial, a wrong patient or a stale version
     */
    public OutcomeReading recordOutcome(Actor actor, String episodeId, OutcomeRequest request,
                                        int expectedVersion) {
        return recordOutcome(actor, episodeId, request, null, expectedVersion);
    }

    /**
     * Record an outcome reading with an explicit patient binding.
     *
     * @param actor          caller identity
     * @param episodeId      owning episode
     * @param request        reading command
     * @param patientId      expected patient, or {@code null} to use the episode patient
     * @param expectedVersion version read by the caller
     * @return the recorded reading
     * @throws ApiException on invalid input, denial, a wrong patient or a stale version
     */
    public OutcomeReading recordOutcome(Actor actor, String episodeId, OutcomeRequest request,
                                        String patientId, int expectedVersion) {
        authorize(actor, ACTION_OUTCOME, episodeId);
        String scaleKey = text(request.scaleKey(), "scaleKey", 64);
        text(request.value(), "value", 64);
        text(request.unit(), "unit", 32);
        text(request.observedAt(), "observedAt", 64);
        TherapyAggregate aggregate = load(actor, episodeId, ACTION_OUTCOME);
        requireVersion(actor, ACTION_OUTCOME, episodeId, aggregate, expectedVersion);
        requirePatient(actor, ACTION_OUTCOME, episodeId, aggregate, patientId);
        requireState(actor, ACTION_OUTCOME, episodeId, aggregate, EpisodeState.IN_TREATMENT);
        OutcomeScale scale = store.scale(actor.organizationId(), scaleKey, request.scaleVersion())
                .orElse(null);
        if (scale == null || !scale.approved()) {
            audit(actor, ACTION_OUTCOME, episodeId, OUTCOME_INVALID);
            throw invalid("SCALE_NOT_APPROVED", "the scale version is not approved");
        }
        if (!scale.permittedValues().contains(request.value())) {
            audit(actor, ACTION_OUTCOME, episodeId, OUTCOME_INVALID);
            throw invalid("VALUE_NOT_PERMITTED", "the value is not in the approved scale");
        }
        if (!scale.unit().equals(request.unit())) {
            audit(actor, ACTION_OUTCOME, episodeId, OUTCOME_INVALID);
            throw invalid("UNIT_MISMATCH", "the reading unit must match the approved scale version");
        }
        OutcomeReading reading = new OutcomeReading(scaleKey, request.scaleVersion(), request.value(),
                request.unit(), request.observedAt());
        store.save(aggregate.withReading(reading).withEpisode(bump(aggregate.episode())));
        audit(actor, ACTION_OUTCOME, episodeId, OUTCOME_SUCCESS);
        return reading;
    }

    /**
     * Record one therapy intervention.
     *
     * @param actor          caller identity
     * @param episodeId      owning episode
     * @param request        intervention command
     * @param expectedVersion version read by the caller
     * @return the recorded intervention
     * @throws ApiException on invalid input, denial or a stale version
     */
    public Intervention recordIntervention(Actor actor, String episodeId, InterventionRequest request,
                                           int expectedVersion) {
        authorize(actor, ACTION_INTERVENTION, episodeId);
        String description = text(request.description(), "description", 500);
        String performedAt = text(request.performedAt(), "performedAt", 64);
        TherapyAggregate aggregate = load(actor, episodeId, ACTION_INTERVENTION);
        requireVersion(actor, ACTION_INTERVENTION, episodeId, aggregate, expectedVersion);
        requireState(actor, ACTION_INTERVENTION, episodeId, aggregate, EpisodeState.IN_TREATMENT);
        Intervention intervention = new Intervention(description, performedAt, actor.subject());
        store.save(aggregate.withIntervention(intervention).withEpisode(bump(aggregate.episode())));
        audit(actor, ACTION_INTERVENTION, episodeId, OUTCOME_SUCCESS);
        return intervention;
    }

    /**
     * Draft a session, progress or discharge note.
     *
     * @param actor          caller identity
     * @param episodeId      owning episode
     * @param request        note command
     * @param expectedVersion version read by the caller
     * @return the unsigned draft note
     * @throws ApiException on invalid input, denial or a stale version
     */
    public ClinicalNote recordNote(Actor actor, String episodeId, NoteRequest request,
                                   int expectedVersion) {
        return recordNote(actor, episodeId, request, null, expectedVersion);
    }

    /**
     * Draft a note with an explicit patient binding.
     *
     * @param actor          caller identity
     * @param episodeId      owning episode
     * @param request        note command
     * @param patientId      expected patient, or {@code null} to use the episode patient
     * @param expectedVersion version read by the caller
     * @return the unsigned draft note
     * @throws ApiException on invalid input, denial or a stale version
     */
    public ClinicalNote recordNote(Actor actor, String episodeId, NoteRequest request,
                                   String patientId, int expectedVersion) {
        authorize(actor, ACTION_NOTE, episodeId);
        if (request == null || request.kind() == null) {
            audit(actor, ACTION_NOTE, episodeId, OUTCOME_INVALID);
            throw invalid("INVALID_NOTE", "a note kind is required");
        }
        String content = text(request.content(), "content", 5000);
        String effectiveAt = text(request.effectiveAt(), "effectiveAt", 64);
        TherapyAggregate aggregate = load(actor, episodeId, ACTION_NOTE);
        requireVersion(actor, ACTION_NOTE, episodeId, aggregate, expectedVersion);
        requirePatient(actor, ACTION_NOTE, episodeId, aggregate, patientId);
        requireState(actor, ACTION_NOTE, episodeId, aggregate,
                EpisodeState.ACTIVE, EpisodeState.IN_TREATMENT);
        int noteVersion = 1 + (int) aggregate.notes().stream()
                .filter(note -> note.kind() == request.kind()).count();
        ClinicalNote note = new ClinicalNote(request.kind(), noteVersion,
                aggregate.episode().patientId(), content, actor.subject(), effectiveAt,
                request.templateVersion(), false, null, null, null, null);
        store.save(aggregate.withNote(note).withEpisode(bump(aggregate.episode())));
        audit(actor, ACTION_NOTE, episodeId, OUTCOME_SUCCESS);
        return note;
    }

    /**
     * Sign a draft note with an attestation. Signing a discharge note closes the episode.
     *
     * @param actor       caller identity
     * @param episodeId   owning episode
     * @param kind        note kind
     * @param noteVersion note revision
     * @param attestation signer attestation
     * @param expectedVersion version read by the caller
     * @return the signed note
     * @throws ApiException on denial, an unknown or signed note, unmet discharge preconditions,
     *                      invalid input or a stale version
     */
    public ClinicalNote signNote(Actor actor, String episodeId, NoteKind kind, int noteVersion,
                                 String attestation, int expectedVersion) {
        authorize(actor, ACTION_SIGN, episodeId);
        String signedAttestation = text(attestation, "attestation", 1000);
        TherapyAggregate aggregate = load(actor, episodeId, ACTION_SIGN);
        requireVersion(actor, ACTION_SIGN, episodeId, aggregate, expectedVersion);
        // IN_TREATMENT proves the evaluation was recorded; a DISCHARGED episode is read-only,
        // so no draft (of any kind) may be signed after discharge.
        requireState(actor, ACTION_SIGN, episodeId, aggregate, EpisodeState.IN_TREATMENT);
        ClinicalNote draft = aggregate.notes().stream()
                .filter(note -> note.kind() == kind && note.version() == noteVersion)
                .findFirst().orElse(null);
        if (draft == null) {
            audit(actor, ACTION_SIGN, episodeId, OUTCOME_NOT_FOUND);
            throw notFound("note revision not found");
        }
        if (draft.signed()) {
            audit(actor, ACTION_SIGN, episodeId, OUTCOME_CONFLICT);
            throw conflict("a signed note is immutable");
        }
        if (kind == NoteKind.DISCHARGE) {
            boolean hasSignedSession = aggregate.notes().stream().anyMatch(
                    note -> note.kind() != NoteKind.DISCHARGE && note.signed());
            if (!hasSignedSession) {
                audit(actor, ACTION_SIGN, episodeId, OUTCOME_CONFLICT);
                throw conflict("discharge needs a signed session note");
            }
        }
        Instant signedAt = clock.instant();
        // Bind every signed field, including the attestation and signing time, to the fingerprint.
        String checksum = checksum(kind.name(), String.valueOf(noteVersion), draft.patientId(),
                draft.content(), String.valueOf(draft.effectiveAt()), draft.author(),
                String.valueOf(draft.templateVersion()), actor.subject(), signedAttestation,
                signedAt.toString());
        ClinicalNote signed = new ClinicalNote(draft.kind(), draft.version(), draft.patientId(),
                draft.content(), draft.author(), draft.effectiveAt(), draft.templateVersion(),
                true, actor.subject(), signedAt, signedAttestation, checksum);
        Episode nextEpisode = kind == NoteKind.DISCHARGE
                ? withState(aggregate.episode(), EpisodeState.DISCHARGED)
                : bump(aggregate.episode());
        store.save(aggregate.replaceNote(signed).withEpisode(nextEpisode));
        audit(actor, ACTION_SIGN, episodeId, OUTCOME_SUCCESS);
        return signed;
    }

    /**
     * Send an approved summary of a discharged episode to the referrer.
     *
     * @param actor          caller identity
     * @param episodeId      owning episode
     * @param request        summary command
     * @param expectedVersion version read by the caller
     * @return the approved, sent summary
     * @throws ApiException on denial, an undisclosed episode, invalid input or a stale version
     */
    public Summary sendSummary(Actor actor, String episodeId, SummaryRequest request,
                               int expectedVersion) {
        authorize(actor, ACTION_SUMMARY, episodeId);
        String content = text(request.content(), "content", 10000);
        String referrerOrg = text(request.referrerOrg(), "referrerOrg", 128);
        String referrerSubject = text(request.referrerSubject(), "referrerSubject", 128);
        TherapyAggregate aggregate = load(actor, episodeId, ACTION_SUMMARY);
        requireVersion(actor, ACTION_SUMMARY, episodeId, aggregate, expectedVersion);
        requireState(actor, ACTION_SUMMARY, episodeId, aggregate, EpisodeState.DISCHARGED);
        if (!referrerOrg.equals(aggregate.episode().referrerOrg())
                || !referrerSubject.equals(aggregate.episode().referrerSubject())) {
            audit(actor, ACTION_SUMMARY, episodeId, OUTCOME_CONFLICT);
            throw conflict("summary recipient is not the verified referrer");
        }
        Summary previous = aggregate.summary();
        int nextVersion = previous == null ? 1 : previous.version() + 1;
        Summary summary = new Summary(nextVersion, content, referrerOrg, referrerSubject, true,
                actor.subject(), clock.instant());
        // A replacement revision reopens acknowledgment; the old ack cannot vouch for new content.
        store.save(aggregate.withSummary(summary).withAcknowledgment(null)
                .withEpisode(bump(aggregate.episode())));
        audit(actor, ACTION_SUMMARY, episodeId, OUTCOME_SUCCESS);
        return summary;
    }

    /**
     * Record the referrer's acknowledgment of the exact sent summary revision.
     *
     * @param actor          caller identity
     * @param episodeId      owning episode
     * @param request        acknowledgment command
     * @param expectedVersion version read by the caller
     * @return the recorded acknowledgment
     * @throws ApiException on denial, a missing summary, a revision mismatch, a duplicate
     *                      acknowledgment, invalid input or a stale version
     */
    public ReturnAcknowledgment acknowledgeReturn(Actor actor, String episodeId, AckRequest request,
                                                  int expectedVersion) {
        authorize(actor, ACTION_ACK, episodeId);
        String receivedAt = text(request.receivedAt(), "receivedAt", 64);
        String receivedBy = text(request.receivedBy(), "receivedBy", 128);
        String note = text(request.note(), "note", 1000);
        TherapyAggregate aggregate = load(actor, episodeId, ACTION_ACK);
        requireVersion(actor, ACTION_ACK, episodeId, aggregate, expectedVersion);
        if (!receivedBy.equals(aggregate.episode().referrerSubject())) {
            audit(actor, ACTION_ACK, episodeId, OUTCOME_INVALID);
            throw invalid("UNVERIFIED_REFERER", "the acknowledgment must come from the verified referrer");
        }
        Summary summary = aggregate.summary();
        if (summary == null) {
            audit(actor, ACTION_ACK, episodeId, OUTCOME_CONFLICT);
            throw conflict("no summary has been sent");
        }
        if (request.summaryVersion() != summary.version()) {
            audit(actor, ACTION_ACK, episodeId, OUTCOME_CONFLICT);
            throw conflict("acknowledgment must bind the exact summary revision");
        }
        if (aggregate.acknowledgment() != null) {
            audit(actor, ACTION_ACK, episodeId, OUTCOME_CONFLICT);
            throw conflict("this summary was already acknowledged");
        }
        ReturnAcknowledgment acknowledgment = new ReturnAcknowledgment(
                request.summaryVersion(), receivedAt, receivedBy, note);
        store.save(aggregate.withAcknowledgment(acknowledgment).withEpisode(bump(aggregate.episode())));
        audit(actor, ACTION_ACK, episodeId, OUTCOME_SUCCESS);
        return acknowledgment;
    }

    /**
     * Read the full clinical state of one episode.
     *
     * @param actor     caller identity
     * @param episodeId episode identifier
     * @return the episode aggregate
     * @throws ApiException on denial or an invisible episode
     */
    public TherapyAggregate get(Actor actor, String episodeId) {
        authorize(actor, ACTION_READ_EPISODE, episodeId);
        TherapyAggregate aggregate = load(actor, episodeId, ACTION_READ_EPISODE);
        audit(actor, ACTION_READ_EPISODE, episodeId, OUTCOME_SUCCESS);
        return aggregate;
    }

    /**
     * Read the tenant's append-only PT-01 audit trail.
     *
     * @param actor caller identity
     * @return immutable audit facts
     * @throws ApiException on denial
     */
    public List<TherapyAuditEvent> auditTrail(Actor actor) {
        authorize(actor, ACTION_AUDIT, "audit");
        List<TherapyAuditEvent> events = store.audit(actor.organizationId());
        audit(actor, ACTION_AUDIT, "audit", OUTCOME_SUCCESS);
        return events;
    }

    private TherapyAggregate load(Actor actor, String episodeId, String action) {
        TherapyAggregate aggregate = store.find(actor.organizationId(), episodeId).orElse(null);
        if (aggregate == null) {
            audit(actor, action, episodeId, OUTCOME_NOT_FOUND);
            throw notFound("therapy episode not found");
        }
        return aggregate;
    }

    private void authorize(Actor actor, String action, String resourceId) {
        if (actor.roles().isEmpty()) {
            audit(actor, action, resourceId, OUTCOME_DENIED);
            throw new ApiException(403, "FORBIDDEN", "no active membership");
        }
        Set<String> allowed = ACTION_ROLES.getOrDefault(action, Set.of());
        if (actor.roles().stream().noneMatch(allowed::contains)) {
            audit(actor, action, resourceId, OUTCOME_DENIED);
            throw new ApiException(403, "FORBIDDEN", "missing " + action + " permission");
        }
    }

    private void audit(Actor actor, String action, String resourceId, String outcome) {
        store.appendAudit(new TherapyAuditEvent(actor.organizationId(), actor.subject(),
                action, resourceId, outcome));
    }

    private void requireVersion(Actor actor, String action, String episodeId,
                                TherapyAggregate aggregate, int expectedVersion) {
        if (aggregate.episode().version() != expectedVersion) {
            audit(actor, action, episodeId, OUTCOME_CONFLICT);
            throw new ApiException(412, "STALE_VERSION", "the therapy episode changed since it was read");
        }
    }

    private void requirePatient(Actor actor, String action, String episodeId,
                                TherapyAggregate aggregate, String patientId) {
        if (patientId != null && !patientId.equals(aggregate.episode().patientId())) {
            audit(actor, action, episodeId, OUTCOME_INVALID);
            throw new ApiException(422, "PATIENT_MISMATCH", "the patient does not own this episode");
        }
    }

    private void requireState(Actor actor, String action, String episodeId,
                              TherapyAggregate aggregate, EpisodeState... expected) {
        for (EpisodeState state : expected) {
            if (aggregate.episode().state() == state) {
                return;
            }
        }
        audit(actor, action, episodeId, OUTCOME_CONFLICT);
        throw new ApiException(409, "INVALID_TRANSITION", "the episode is not in a writable state");
    }

    private static Episode withState(Episode episode, EpisodeState next) {
        return new Episode(episode.id(), episode.organizationId(), episode.patientId(),
                episode.referralId(), episode.referrerOrg(), episode.referrerSubject(), next,
                episode.version() + 1, episode.createdAt(), episode.receivedAt(),
                episode.clinicalQuestion());
    }

    private static Episode bump(Episode episode) {
        return new Episode(episode.id(), episode.organizationId(), episode.patientId(),
                episode.referralId(), episode.referrerOrg(), episode.referrerSubject(),
                episode.state(), episode.version() + 1, episode.createdAt(), episode.receivedAt(),
                episode.clinicalQuestion());
    }

    private static String checksum(String... parts) {
        return NoteChecksum.sha256(String.join("\u0000", parts));
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }

    private static String text(String value, String field, int maxLength) {
        if (blank(value) || value.length() > maxLength) {
            throw invalid("INVALID_" + field.toUpperCase(java.util.Locale.ROOT),
                    field + " must be non-blank and at most " + maxLength + " characters");
        }
        return value;
    }

    private static String uuid(String value, String field) {
        text(value, field, 64);
        try {
            UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw invalid("INVALID_" + field.toUpperCase(java.util.Locale.ROOT), field + " must be a UUID");
        }
        return value;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static ApiException invalid(String code, String message) {
        return new ApiException(422, code, message);
    }

    private static ApiException conflict(String message) {
        return new ApiException(409, "CONFLICT", message);
    }

    private static ApiException notFound(String message) {
        return new ApiException(404, "NOT_FOUND", message);
    }
}
