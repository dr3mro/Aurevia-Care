package com.aurevia.therapy;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Domain contract for the PT-01 physical-therapy workflow.
 *
 * <p>Every clinically significant fact is an immutable value: an accepted
 * referral becomes an episode, evaluation and outcome readings are bound to the
 * episode, notes are drafted and signed as separate events, and a summary and
 * its return acknowledgment are distinct revisions. The types carry no
 * persistence or transport concerns.</p>
 */
public final class TherapyContract {

    private TherapyContract() {
    }

    /**
     * Caller identity: active organization, subject and roles in that organization.
     *
 * @return this record
 */
public record Actor(String organizationId, String subject, Set<String> roles) {

        /**
         * Freeze the role set so authorization cannot change through the caller.
         *
         * @throws NullPointerException when roles is null
         */
        public Actor {
            roles = Set.copyOf(roles);
        }

        /**
         * Return the immutable active roles.
         *
         * @return active role assignments
         */
        @Override
        public Set<String> roles() {
            return Set.copyOf(roles);
        }

        /**
         * Build an actor from role names. Parameter semantics are documented once
         * on the record declaration; repeating them here yields duplicate
         * Doxygen sections for the canonical constructor.
         *
         * @return the actor
         */
        public static Actor of(String organizationId, String subject, String... roles) {
            return new Actor(organizationId, subject, Set.of(roles));
        }
    }

    /**
     * Lifecycle of a therapy episode. Evaluation opens treatment; a signed
     * discharge closes it. No other transition is legal.
     */
    public enum EpisodeState {
        /** Referral accepted; awaiting the initial evaluation. */
        ACTIVE,
        /** Evaluation recorded and treatment in progress. */
        IN_TREATMENT,
        /** Signed discharge recorded; the episode is read-only. */
        DISCHARGED;

        /**
         * Decide whether a transition is permitted.
         *
         * @return {@code true} only for the two forward transitions
         */
        public boolean canTransitionTo(EpisodeState next) {
            return (this == ACTIVE && next == IN_TREATMENT)
                    || (this == IN_TREATMENT && next == DISCHARGED);
        }
    }

    /**
     * Kind of clinical note in a therapy episode.
     */
    public enum NoteKind {
        /** One treatment session. */
        SESSION,
        /** A progress review against the plan. */
        PROGRESS,
        /** The discharge note. */
        DISCHARGE
    }

    /**
     * A clinical measurement with an explicit unit and qualifier-safe value.
     *
     */
    public record Measurement(String code, String value, String unit, String observedAt) {
    }

    /**
     * A therapy goal with a target and a target date.
     *
 * @return this record
 */
public record Goal(String description, String target, String targetDate) {
    }

    /**
     * The immutable header of an accepted therapy episode.
     *
 * @return this record
 */
public record Episode(String id, String organizationId, String patientId, String referralId,
                          String referrerOrg, String referrerSubject, EpisodeState state, int version,
                          Instant createdAt, String receivedAt, String clinicalQuestion) {
    }

    /**
     * The initial evaluation: problems, baseline measurements, goals and plan.
     *
 * @return this record
 */
public record Evaluation(String episodeId, String patientId, List<String> problems,
                             List<Measurement> baselines, List<Goal> goals, int frequencyPerWeek,
                             int durationWeeks, String author, Instant recordedAt, int version) {

        /**
         * Freeze all list members against caller mutation.
         *
         * @throws NullPointerException when a list is null
         */
        public Evaluation {
            problems = List.copyOf(problems);
            baselines = List.copyOf(baselines);
            goals = List.copyOf(goals);
        }

        /**
         * Return the immutable problem list.
         *
         * @return problems
         */
        @Override
        public List<String> problems() {
            return List.copyOf(problems);
        }

        /**
         * Return the immutable baseline list.
         *
         * @return baseline measurements
         */
        @Override
        public List<Measurement> baselines() {
            return List.copyOf(baselines);
        }

        /**
         * Return the immutable goal list.
         *
         * @return therapy goals
         */
        @Override
        public List<Goal> goals() {
            return List.copyOf(goals);
        }
    }

    /**
     * A versioned, approved outcome scale definition.
     *
     */
    public record OutcomeScale(String organizationId, String key, int version, String description,
                               String unit, List<String> permittedValues, boolean approved,
                               String approvedBy) {

        /**
         * Freeze the permitted values against caller mutation.
         *
         * @throws NullPointerException when permittedValues is null
         */
        public OutcomeScale {
            permittedValues = List.copyOf(permittedValues);
        }

        /**
         * Return the immutable permitted values.
         *
         * @return permitted coded values
         */
        @Override
        public List<String> permittedValues() {
            return List.copyOf(permittedValues);
        }
    }

    /**
     * An outcome reading bound to the exact scale version and unit it used.
     *
     */
    public record OutcomeReading(String scaleKey, int scaleVersion, String value, String unit,
                                 String observedAt) {
    }

    /**
     * One recorded therapy intervention.
     *
 * @return this record
 */
public record Intervention(String description, String performedAt, String author) {
    }

    /**
     * A session, progress or discharge note, draft until signed.
     *
 * @return this record
 */
public record ClinicalNote(NoteKind kind, int version, String patientId, String content,
                               String author, String effectiveAt, Integer templateVersion,
                               boolean signed, String signedBy, Instant signedAt, String attestation,
                               String contentChecksum) {
    }

    /**
     * An approved summary disclosed to the referring organization.
     *
 * @return this record
 */
public record Summary(int version, String content, String referrerOrg, String referrerSubject,
                          boolean approved, String approvedBy, Instant sentAt) {
    }

    /**
     * The referrer's acknowledgment of one exact summary revision.
     *
 * @return this record
 */
public record ReturnAcknowledgment(int summaryVersion, String receivedAt, String receivedBy,
                                       String note) {
    }

    /**
     * The complete clinical state of one episode. All collections are defensive
     * copies so callers cannot mutate the domain through a view.
     *
 * @return this record
 */
public record TherapyAggregate(Episode episode, Evaluation evaluation,
                                   List<Intervention> interventions, List<OutcomeReading> readings,
                                   List<ClinicalNote> notes, Summary summary,
                                   ReturnAcknowledgment acknowledgment) {

        /**
         * Freeze all list members against caller mutation.
         *
         * @throws NullPointerException when a list is null
         */
        public TherapyAggregate {
            interventions = List.copyOf(interventions);
            readings = List.copyOf(readings);
            notes = List.copyOf(notes);
        }

        /**
         * Return the immutable interventions.
         *
         * @return interventions
         */
        @Override
        public List<Intervention> interventions() {
            return List.copyOf(interventions);
        }

        /**
         * Return the immutable outcome readings.
         *
         * @return outcome readings
         */
        @Override
        public List<OutcomeReading> readings() {
            return List.copyOf(readings);
        }

        /**
         * Return the immutable note revisions.
         *
         * @return note revisions
         */
        @Override
        public List<ClinicalNote> notes() {
            return List.copyOf(notes);
        }

        /**
         * Copy this aggregate with a new episode header.
         *
         * @return a new aggregate
         */
        public TherapyAggregate withEpisode(Episode episode) {
            return new TherapyAggregate(episode, evaluation, interventions, readings, notes,
                    summary, acknowledgment);
        }

        /**
         * Copy this aggregate with an initial evaluation.
         *
         * @return a new aggregate
         */
        public TherapyAggregate withEvaluation(Evaluation evaluation) {
            return new TherapyAggregate(episode, evaluation, interventions, readings, notes,
                    summary, acknowledgment);
        }

        /**
         * Copy this aggregate with one more intervention.
         *
         * @return a new aggregate
         */
        public TherapyAggregate withIntervention(Intervention intervention) {
            List<Intervention> next = new java.util.ArrayList<>(interventions);
            next.add(intervention);
            return new TherapyAggregate(episode, evaluation, next, readings, notes,
                    summary, acknowledgment);
        }

        /**
         * Copy this aggregate with one more outcome reading.
         *
         * @return a new aggregate
         */
        public TherapyAggregate withReading(OutcomeReading reading) {
            List<OutcomeReading> next = new java.util.ArrayList<>(readings);
            next.add(reading);
            return new TherapyAggregate(episode, evaluation, interventions, next, notes,
                    summary, acknowledgment);
        }

        /**
         * Copy this aggregate with one more note revision.
         *
         * @return a new aggregate
         */
        public TherapyAggregate withNote(ClinicalNote note) {
            List<ClinicalNote> next = new java.util.ArrayList<>(notes);
            next.add(note);
            return new TherapyAggregate(episode, evaluation, interventions, readings, next,
                    summary, acknowledgment);
        }

        /**
         * Replace the note with the given kind and revision in this aggregate.
         *
         * @return a new aggregate
         */
        public TherapyAggregate replaceNote(ClinicalNote note) {
            List<ClinicalNote> next = notes.stream()
                    .map(existing -> existing.kind() == note.kind() && existing.version() == note.version()
                            ? note : existing)
                    .toList();
            return new TherapyAggregate(episode, evaluation, interventions, readings, next,
                    summary, acknowledgment);
        }

        /**
         * Copy this aggregate with an approved summary.
         *
         * @return a new aggregate
         */
        public TherapyAggregate withSummary(Summary summary) {
            return new TherapyAggregate(episode, evaluation, interventions, readings, notes,
                    summary, acknowledgment);
        }

        /**
         * Copy this aggregate with a return acknowledgment.
         *
         * @return a new aggregate
         */
        public TherapyAggregate withAcknowledgment(ReturnAcknowledgment acknowledgment) {
            return new TherapyAggregate(episode, evaluation, interventions, readings, notes,
                    summary, acknowledgment);
        }
    }

    /**
     * Append-only audit fact for one PT-01 command.
     *
     */
    public record TherapyAuditEvent(String organizationId, String actorSubject, String action,
                                    String resourceId, String outcome) {
    }

    /**
     * Command to accept a referral into a new episode.
     *
 * @return this record
 */
public record ReferralRequest(String referralId, String patientId, String referrerOrg,
                                  String referrerSubject, String receivedAt, String clinicalQuestion) {
    }

    /**
     * Command to record an initial evaluation.
     *
 * @return this record
 */
public record EvaluationRequest(List<String> problems, List<Measurement> baselines,
                                    List<Goal> goals, int frequencyPerWeek, int durationWeeks) {

        /**
         * Freeze caller-owned lists; null is preserved so the service can reject
         * a missing list with a structured 422. Component semantics are documented
         * on the record declaration.
         */
        public EvaluationRequest {
            problems = problems == null ? null : List.copyOf(problems);
            baselines = baselines == null ? null : List.copyOf(baselines);
            goals = goals == null ? null : List.copyOf(goals);
        }

        /**
         * Return the immutable problem list, or {@code null} when it was omitted.
         *
         * @return problems
         */
        @Override
        public List<String> problems() {
            return problems == null ? null : List.copyOf(problems);
        }

        /**
         * Return the immutable baseline list, or {@code null} when it was omitted.
         *
         * @return baseline measurements
         */
        @Override
        public List<Measurement> baselines() {
            return baselines == null ? null : List.copyOf(baselines);
        }

        /**
         * Return the immutable goal list, or {@code null} when it was omitted.
         *
         * @return therapy goals
         */
        @Override
        public List<Goal> goals() {
            return goals == null ? null : List.copyOf(goals);
        }
    }

    /**
     * Command to define a new pending scale version.
     *
     */
    public record ScaleRequest(String key, String description, String unit,
                               List<String> permittedValues) {

        /**
         * Freeze caller-owned permitted values; null is preserved so the service
         * can reject a missing list with a structured 422. Component semantics are
         * documented on the record declaration.
         */
        public ScaleRequest {
            permittedValues = permittedValues == null ? null : List.copyOf(permittedValues);
        }

        /**
         * Return the immutable permitted values, or {@code null} when omitted.
         *
         * @return permitted coded values
         */
        @Override
        public List<String> permittedValues() {
            return permittedValues == null ? null : List.copyOf(permittedValues);
        }
    }

    /**
     * Command to record one outcome reading.
     *
     * @param scaleKey     scale key
     * @param scaleVersion approved scale version
     * @param value        recorded value
     * @param unit         recorded unit
     * @param observedAt   clinical observation time
     */
    public record OutcomeRequest(String scaleKey, int scaleVersion, String value, String unit,
                                 String observedAt) {
    }

    /**
     * Command to record one intervention.
     *
     * @param description intervention description
     * @param performedAt clinical time
 * @return this record
 */
public record InterventionRequest(String description, String performedAt) {
    }

    /**
     * Command to draft a note.
     *
     * @param kind           note kind
     * @param content        note content
     * @param effectiveAt    clinical effective time
     * @param templateVersion template version used
 * @return this record
 */
public record NoteRequest(NoteKind kind, String content, String effectiveAt,
                              Integer templateVersion) {
    }

    /**
     * Command to send an approved summary to the referrer.
     *
     * @param content        summary content
     * @param referrerOrg    referring organization
     * @param referrerSubject referring clinician
 * @return this record
 */
public record SummaryRequest(String content, String referrerOrg, String referrerSubject) {
    }

    /**
     * Command to record the referrer's return acknowledgment.
     *
     * @param summaryVersion summary revision acknowledged
     * @param receivedAt     acknowledgment time
     * @param receivedBy     acknowledging subject
     * @param note           acknowledgment note
 * @return this record
 */
public record AckRequest(int summaryVersion, String receivedAt, String receivedBy, String note) {
    }
}
