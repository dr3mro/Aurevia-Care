package com.aurevia.prescription;

import java.util.ArrayList;
import java.util.List;

import com.aurevia.api.ApiException;
import com.aurevia.prescription.PrescriptionContract.AllergyAssessment;
import com.aurevia.prescription.PrescriptionContract.AllergyCheck;
import com.aurevia.prescription.PrescriptionContract.AllergyEntry;
import com.aurevia.prescription.PrescriptionContract.AllergyStatus;
import com.aurevia.prescription.PrescriptionContract.CheckOutcome;
import com.aurevia.prescription.PrescriptionContract.DrugEntry;
import com.aurevia.prescription.PrescriptionContract.Finding;
import com.aurevia.prescription.PrescriptionContract.FindingKind;
import com.aurevia.prescription.PrescriptionContract.Ingredient;

/**
 * Pure RX-01 allergy decision support over structured substance identities.
 *
 * <p>Only verified assertions participate in matching. A verified exact
 * ingredient match is a conflict, a shared approved cross-reactivity group is a
 * warning, and any absent, unknown, unverified or unmappable data is surfaced as
 * unresolved — never reported as "no allergy". Free-text name similarity is
 * deliberately never used as evidence.</p>
 */
public final class AllergyChecker {

    private AllergyChecker() {
    }

    /**
     * Check a drug's ingredients against a patient's allergy assessment.
     *
     * @param drug       the candidate drug entry
     * @param assessment the patient's assessment, including unknown states
     * @return an immutable check with every retained finding
     * @throws ApiException when either argument is missing
     */
    public static AllergyCheck check(DrugEntry drug, AllergyAssessment assessment) {
        if (drug == null || assessment == null) {
            throw new ApiException(422, "INVALID_ALLERGY_INPUT", "a drug and an assessment are required");
        }
        List<Finding> findings = new ArrayList<>();
        AllergyStatus status = assessment.status();
        if (status == AllergyStatus.UNKNOWN || status == AllergyStatus.UNVERIFIED) {
            findings.add(new Finding(FindingKind.INCOMPLETE_ASSESSMENT, drug.entryId(), null, 0,
                    "the allergy assessment is not verified: " + status.wire()));
        }
        for (Ingredient ingredient : drug.ingredients()) {
            boolean hasSubstance = ingredient.substanceId() != null;
            boolean hasGroup = ingredient.group() != null;
            if (!hasSubstance && !hasGroup) {
                findings.add(new Finding(FindingKind.UNRESOLVED_INGREDIENT, drug.entryId(), null, 0,
                        "an ingredient has no structured substance identity"));
            }
            for (AllergyEntry allergy : assessment.entries()) {
                if (!"verified".equals(allergy.verificationState())) {
                    continue;
                }
                if (hasSubstance && allergy.substanceId().equals(ingredient.substanceId())) {
                    findings.add(new Finding(FindingKind.EXACT_MATCH, ingredient.substanceId(),
                            ingredient.group(), ingredient.groupVersion(),
                            "a verified allergy names this exact ingredient"));
                } else if (hasGroup && ingredient.group().equals(allergy.group())) {
                    findings.add(new Finding(FindingKind.CROSS_REACTIVITY, ingredient.substanceId(),
                            ingredient.group(), ingredient.groupVersion(),
                            "a verified allergy shares this approved cross-reactivity group"));
                }
            }
        }
        return new AllergyCheck(disposition(findings), status, findings, drug.approvalProvenance());
    }

    private static CheckOutcome disposition(List<Finding> findings) {
        boolean exact = findings.stream().anyMatch(finding -> finding.kind() == FindingKind.EXACT_MATCH);
        boolean group = findings.stream().anyMatch(finding -> finding.kind() == FindingKind.CROSS_REACTIVITY);
        boolean incomplete = findings.stream().anyMatch(finding ->
                finding.kind() == FindingKind.INCOMPLETE_ASSESSMENT
                        || finding.kind() == FindingKind.UNRESOLVED_INGREDIENT);
        if (exact) {
            return CheckOutcome.CONFLICT;
        }
        if (group) {
            return CheckOutcome.WARNING;
        }
        if (incomplete) {
            return CheckOutcome.UNRESOLVED;
        }
        return CheckOutcome.NO_CONFLICT;
    }
}
