package com.aurevia.smartclinic.messaging.s_g3_12;

import java.time.Clock;
import java.util.List;

import org.springframework.stereotype.Service;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.messaging.s_g3_12.GateEvidenceContract.EvidenceInput;
import com.aurevia.smartclinic.messaging.s_g3_12.GateEvidenceContract.EvidenceRecord;
import com.aurevia.smartclinic.messaging.s_g3_12.GateEvidenceContract.GateEvidenceReport;
import com.aurevia.smartclinic.messaging.s_g3_12.GateEvidenceContract.Verdict;

/**
 * Produces the composite, append-only G3 gate-evidence report from synthetic fixtures.
 *
 * <p>This service is the public entry point for the G3 evidence slice: it
 * evaluates all five categories, appends each immutable record to a
 * tamper-evident ledger in the same operation, and returns a report whose
 * verdict is PASS only when every category passes and the chain verifies. The
 * organization is validated but never inferred from the fixture bodies, so an
 * evidence report cannot be attributed to a different tenant by a client.</p>
 */
@Service
public class G3EvidenceService {

    private final GateEvidenceEvaluator evaluator = new GateEvidenceEvaluator();
    private final EvidenceLedger ledger;
    private final Clock clock;

    /**
     * Create the service with a deterministic hasher and clock.
     *
     * @param hash  deterministic evidence hasher
     * @param clock injected clock for the report's generation instant
     */
    public G3EvidenceService(EvidenceHash hash, Clock clock) {
        this.ledger = new EvidenceLedger(hash);
        this.clock = clock;
    }

    /**
     * Produce one composite evidence report for the active synthetic organization.
     *
     * @param organizationId owning organization, validated at the boundary
     * @param input          synthetic fixture families for all five categories
     * @return the immutable composite report
     * @throws ApiException when the organization identifier is missing or blank
     */
    public GateEvidenceReport produce(String organizationId, EvidenceInput input) {
        if (organizationId == null || organizationId.isBlank()) {
            throw new ApiException(422, "INVALID_ORGANIZATION", "organizationId must be a nonblank identifier");
        }
        EvidenceRecord restore = evaluator.evaluateRestore(input.snapshot(), input.journal(),
                input.restore(), clock.instant());
        EvidenceRecord downtime = evaluator.evaluateDowntime(input.runbook(), input.commands(), input.effects());
        EvidenceRecord load = evaluator.evaluateLoad(input.profile(), input.samples(), input.availability());
        EvidenceRecord accessibility = evaluator.evaluateAccessibility(input.accessibility());
        EvidenceRecord audit = evaluator.evaluateAudit(input.audit(), ledger.verifyChain());
        for (EvidenceRecord record : List.of(restore, downtime, load, accessibility, audit)) {
            ledger.append(record);
        }
        Verdict verdict = ledger.entries().stream()
                .allMatch(entry -> entry.record().verdict() == Verdict.PASS)
                ? Verdict.PASS : Verdict.FAIL;
        return new GateEvidenceReport(organizationId, ledger.entries().stream()
                .map(entry -> entry.record()).toList(),
                ledger.entries().get(ledger.entries().size() - 1).hash(), verdict, clock.instant());
    }

    /**
     * Expose the append-only ledger for independent chain verification.
     *
     * @return the evidence ledger
     */
    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
            value = "EI_EXPOSE_REP", justification = "Evidence ledger is the owned domain aggregate")
    public EvidenceLedger ledger() {
        return ledger;
    }
}
