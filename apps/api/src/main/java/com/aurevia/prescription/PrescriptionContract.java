package com.aurevia.prescription;

import java.time.Instant;
import java.util.List;

import com.aurevia.api.ApiException;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Domain contract for the prescription-draft slice.
 *
 * <p>These types carry no transport or persistence concern. The design keeps the
 * clinician's selected medication separate from suggestion-only alternatives,
 * distinguishes verified allergy facts from unknown/unverified data, and records
 * an immutable snapshot whenever a verified conflict is overridden.</p>
 */
public final class PrescriptionContract {

    private PrescriptionContract() {
    }

    /** Why an alternative is shown; a suggestion is never an accepted substitution. */
    public enum AlternativeKind {
        /** A suggestion for clinician review; selection remains with the clinician. */
        SUGGESTION("suggestion");

        private final String wire;

        /**
         * Create an alternative kind.
         *
         */
        AlternativeKind(String wire) {
            this.wire = wire;
        }

        /**
         * Return the wire key.
         *
         * @return stable lowercase key
         */
        @JsonValue
        public String wire() {
            return wire;
        }

        /**
         * Parse a stored key.
         *
         * @return the matching kind
         * @throws IllegalArgumentException when the key is unknown
         */
        public static AlternativeKind from(String value) {
            for (AlternativeKind candidate : values()) {
                if (candidate.wire.equals(value)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("unknown alternative kind: " + value);
        }
    }

    /** Overall state of the patient's allergy assessment, including explicit unknown states. */
    public enum AllergyStatus {
        /** At least one verified allergy is on the chart. */
        VERIFIED_LIST("verified_list"),
        /** A verified statement that no known allergies are recorded. */
        VERIFIED_NONE("verified_none"),
        /** No reliable allergy assessment exists; never treated as safe. */
        UNKNOWN("unknown"),
        /** Allergy entries exist but none has been verified. */
        UNVERIFIED("unverified");

        private final String wire;

        /**
         * Create an allergy status.
         *
         */
        AllergyStatus(String wire) {
            this.wire = wire;
        }

        /**
         * Return the wire key.
         *
         * @return stable lowercase key
         */
        @JsonValue
        public String wire() {
            return wire;
        }

        /**
         * Parse a stored status key.
         *
         * @return the matching status
         * @throws IllegalArgumentException when the key is unknown
         */
        public static AllergyStatus from(String value) {
            for (AllergyStatus candidate : values()) {
                if (candidate.wire.equals(value)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("unknown allergy status: " + value);
        }
    }

    /** A single fact produced by an allergy check, retained rather than collapsed. */
    public enum FindingKind {
        /** A verified allergy names the exact ingredient of the selected drug. */
        EXACT_MATCH("exact_match"),
        /** A verified allergy shares an approved cross-reactivity group. */
        CROSS_REACTIVITY("cross_reactivity"),
        /** The drug ingredient has no mappable substance identity. */
        UNRESOLVED_INGREDIENT("unresolved_ingredient"),
        /** The underlying allergy assessment is absent, unknown or unverified. */
        INCOMPLETE_ASSESSMENT("incomplete_assessment");

        private final String wire;

        /**
         * Create a finding kind.
         *
         */
        FindingKind(String wire) {
            this.wire = wire;
        }

        /**
         * Return the wire key.
         *
         * @return stable lowercase key
         */
        @JsonValue
        public String wire() {
            return wire;
        }

        /**
         * Parse a stored finding key.
         *
         * @return the matching kind
         * @throws IllegalArgumentException when the key is unknown
         */
        public static FindingKind from(String value) {
            for (FindingKind candidate : values()) {
                if (candidate.wire.equals(value)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("unknown finding kind: " + value);
        }
    }

    /** Overall disposition of an allergy check; only a verified clean assessment is NO_CONFLICT. */
    public enum CheckOutcome {
        /** No relevant verified allergy and no unresolved data. */
        NO_CONFLICT("no_conflict"),
        /** A verified exact ingredient match was found. */
        CONFLICT("conflict"),
        /** Only a cross-reactivity group warning was found. */
        WARNING("warning"),
        /** The check could not be completed reliably; surfaced, never silently safe. */
        UNRESOLVED("unresolved");

        private final String wire;

        /**
         * Create a check outcome.
         *
         */
        CheckOutcome(String wire) {
            this.wire = wire;
        }

        /**
         * Return the wire key.
         *
         * @return stable lowercase key
         */
        @JsonValue
        public String wire() {
            return wire;
        }

        /**
         * Parse a stored outcome key.
         *
         * @return the matching outcome
         * @throws IllegalArgumentException when the key is unknown
         */
        public static CheckOutcome from(String value) {
            for (CheckOutcome candidate : values()) {
                if (candidate.wire.equals(value)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("unknown check outcome: " + value);
        }
    }

    /**
     * Structured substance identity used for matching; free-text names are never matched.
     *
     * @return the validated structured ingredient identity
     */
    public record Ingredient(String substanceId, String group, int groupVersion) {
        /**
         * Reject malformed identity values.
         *
         * @throws ApiException when a present identifier is blank or the group version is negative
         */
        public Ingredient {
            if (groupVersion < 0
                    || substanceId != null && substanceId.isBlank()
                    || group != null && group.isBlank()
                    || group != null && groupVersion < 1) {
                throw new ApiException(422, "INVALID_INGREDIENT", "invalid ingredient identity");
            }
        }
    }

    /**
     * One versioned, approved-effective drug catalog entry.
     *
     * @return the validated, effective-datable catalog entry
     */
    public record DrugEntry(String entryId, int version, String genericName, String tradeName,
                            String formulation, String strengthValue, String strengthUnit,
                            List<Ingredient> ingredients, boolean approved, Instant effectiveFrom,
                            Instant effectiveTo, String approvalProvenance) {
        /**
         * Validate the entry and make the ingredient list immutable.
         *
         * @throws ApiException when a required field is missing or the version is not positive
         */
        public DrugEntry {
            if (version < 1 || isBlank(entryId) || isBlank(genericName) || isBlank(tradeName)
                    || isBlank(formulation) || isBlank(strengthValue) || isBlank(strengthUnit)
                    || ingredients == null || effectiveFrom == null || isBlank(approvalProvenance)) {
                throw new ApiException(422, "INVALID_DRUG_ENTRY", "invalid drug catalog entry");
            }
            ingredients = List.copyOf(ingredients);
        }

        /**
         * Decide whether this approved version applies at an instant.
         *
         * <p>The evaluation instant is the caller-supplied {@code at} argument.
         * Parameter semantics are documented on the record declaration; repeating
         * them here or on the canonical constructor yields duplicate Doxygen
         * sections for a record.</p>
         *
         * @return {@code true} when approved and inside the effective window
         */
        public boolean effectiveAt(Instant at) {
            return approved && !at.isBefore(effectiveFrom)
                    && (effectiveTo == null || at.isBefore(effectiveTo));
        }
    }

    /**
     * A suggestion shown alongside a search result; it never changes the clinician's selection.
     *
     * @return the validated review-only suggestion
     */
    public record Alternative(AlternativeKind kind, DrugEntry entry, String reason,
                              String sourceCatalogVersion, boolean requiresClinicianSelection) {
        /**
         * Validate the suggestion and its provenance.
         *
         * @throws ApiException when the kind, entry, reason or provenance is missing
         */
        public Alternative {
            if (kind == null || entry == null || isBlank(reason) || isBlank(sourceCatalogVersion)) {
                throw new ApiException(422, "INVALID_ALTERNATIVE", "invalid tagged alternative");
            }
        }
    }

    /**
     * One effective catalog entry with its suggestions.
     *
     * @return the validated catalog hit with immutable suggestions
     */
    public record CatalogHit(DrugEntry entry, List<Alternative> alternatives) {
        /**
         * Validate the hit and make its alternatives immutable.
         *
         * @throws ApiException when the entry is missing
         */
        public CatalogHit {
            if (entry == null || alternatives == null) {
                throw new ApiException(422, "INVALID_CATALOG_HIT", "a catalog hit needs an entry and alternatives");
            }
            alternatives = List.copyOf(alternatives);
        }
    }

    /**
     * One allergy statement from the chart, including its verification state.
     *
     * @return the validated chart allergy statement
     */
    public record AllergyEntry(String substanceId, String group, int groupVersion,
                               String verificationState, String source, Instant effectiveFrom) {
        /**
         * Validate the statement's identity, verification and provenance.
         *
         * @throws ApiException when identity, verification or source is missing
         */
        public AllergyEntry {
            if (isBlank(substanceId) || isBlank(verificationState) || isBlank(source)
                    || effectiveFrom == null || groupVersion < 0
                    || isBlank(group) && groupVersion > 0) {
                throw new ApiException(422, "INVALID_ALLERGY_ENTRY", "invalid allergy statement");
            }
            if (!"verified".equals(verificationState) && !"unverified".equals(verificationState)
                    && !"unknown".equals(verificationState)) {
                throw new ApiException(422, "INVALID_ALLERGY_ENTRY", "unknown allergy verification state");
            }
        }
    }

    /**
     * A patient's overall allergy assessment together with its statements.
     *
     * @return the validated assessment with immutable entries
     */
    public record AllergyAssessment(AllergyStatus status, List<AllergyEntry> entries) {
        /**
         * Validate the assessment and make its entries immutable.
         *
         * @throws ApiException when the status or entry list is missing
         */
        public AllergyAssessment {
            if (status == null || entries == null) {
                throw new ApiException(422, "INVALID_ALLERGY_ASSESSMENT", "an assessment needs a status and entries");
            }
            entries = List.copyOf(entries);
        }
    }

    /**
     * One retained finding of an allergy check.
     *
     * @return the validated retained finding
     */
    public record Finding(FindingKind kind, String substanceId, String group, int groupVersion,
                          String detail) {
        /**
         * Validate the finding.
         *
         * @throws ApiException when the kind, detail or identity is missing
         */
        public Finding {
            if (kind == null || isBlank(detail)
                    || (isBlank(substanceId) && isBlank(group))) {
                throw new ApiException(422, "INVALID_FINDING", "an allergy finding needs a kind, identity and detail");
            }
        }
    }

    /**
     * Immutable, versioned result of an allergy check.
     *
     * @return the validated immutable check with retained findings
     */
    public record AllergyCheck(CheckOutcome outcome, AllergyStatus allergyStatus,
                               List<Finding> findings, String catalogVersion) {
        /**
         * Validate the check and make its findings immutable.
         *
         * @throws ApiException when the outcome, status, findings or catalog version is missing
         */
        public AllergyCheck {
            if (outcome == null || allergyStatus == null || findings == null || isBlank(catalogVersion)) {
                throw new ApiException(422, "INVALID_ALLERGY_CHECK", "invalid allergy check");
            }
            findings = List.copyOf(findings);
        }
    }

    /**
     * Current projection of a prescription draft.
     *
     * @return the validated draft projection
     */
    public record Draft(String id, int version, String patientId, String state, String selectedEntryId,
                        Integer selectedEntryVersion, String drugGenericName, String doseValue,
                        String doseUnit, String route, String frequency, String quantity,
                        String quantityUnit, Integer refills, String instructions, String destination,
                        String encounterReference, String prescriberSubject, Instant createdAt) {
        /**
         * Validate the draft identity and version.
         *
         * @throws ApiException when the identity, patient or state is missing
         */
        public Draft {
            if (version < 1 || isBlank(id) || isBlank(patientId) || isBlank(state)) {
                throw new ApiException(422, "INVALID_DRAFT", "invalid prescription draft");
            }
        }
    }

    /**
     * Immutable override record binding reason, author and the matched data snapshot.
     *
     * @return the validated immutable override fact
     */
    public record OverrideRecord(String draftId, int draftVersion, String reason, String authorSubject,
                                 String matchedSnapshot, Instant occurredAt) {
        /**
         * Validate the override reason, author and snapshot.
         *
         * @throws ApiException when the reason, snapshot or version is missing
         */
        public OverrideRecord {
            if (draftVersion < 1 || isBlank(draftId) || isBlank(reason) || isBlank(matchedSnapshot)) {
                throw new ApiException(422, "INVALID_OVERRIDE", "invalid override record");
            }
        }
    }

    /**
     * A draft with its optional immutable override.
     *
     * @return the validated draft and its optional override
     */
    public record DraftView(Draft draft, OverrideRecord override) {
        /**
         * Validate the view.
         *
         * @throws ApiException when the draft is missing
         */
        public DraftView {
            if (draft == null) {
                throw new ApiException(422, "INVALID_DRAFT_VIEW", "a draft view needs a draft");
            }
        }
    }

    /**
     * Create a draft for a verified patient of the active tenant.
     *
     * @param patientId            patient visible in the active tenant
     * @param encounterReference   optional bounded encounter link
     * @param destination          optional bounded intended destination
     * @return the validated creation command
     */
    public record CreateDraftRequest(String patientId, String encounterReference, String destination) {
    }

    /**
     * Request an allergy check for a specific effective catalog entry.
     *
     * @param catalogEntryId       effective catalog entry to check against the patient
     * @return the validated allergy-check command
     */
    public record AllergyCheckRequest(String catalogEntryId) {
    }

    /**
     * Select a medication, optionally recording a documented override of a verified finding.
     *
     * @param catalogEntryId       effective catalog entry selected by the clinician
     * @param doseValue            required dose value
     * @param doseUnit             required dose unit
     * @param route                required route
     * @param frequency            required frequency
     * @param quantity             required quantity
     * @param quantityUnit         required quantity unit
     * @param refills              optional bounded refill count
     * @param instructions         required clinician instructions
     * @param overrideReason       required when the candidate conflicts with a verified allergy
     * @return the validated medication-selection command
     */
    public record SelectMedicationRequest(String catalogEntryId, String doseValue, String doseUnit,
                                          String route, String frequency, String quantity,
                                          String quantityUnit, Integer refills, String instructions,
                                          String overrideReason) {
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
