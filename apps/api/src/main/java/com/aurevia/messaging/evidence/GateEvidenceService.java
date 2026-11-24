package com.aurevia.messaging.evidence;

import java.time.Clock;
import java.util.List;

import org.springframework.stereotype.Service;

import com.aurevia.api.ApiException;
import com.aurevia.messaging.evidence.GateEvidenceContract.EvidenceInput;
import com.aurevia.messaging.evidence.GateEvidenceContract.EvidenceRecord;
import com.aurevia.messaging.evidence.GateEvidenceContract.GateEvidenceReport;
import com.aurevia.messaging.evidence.GateEvidenceContract.Verdict;

/**
 * Produces the composite, append-only G3 gate-evidence report from synthetic fixtures.
 *
 * <p>This service is the public entry point for the G3 evidence slice: it
 * evaluates all five categories, appends each immutable record to a
 * tamper-evident ledger, and returns a report whose verdict is PASS only when
 * every category in that call passes. The ledger is rebuilt for every call, so
 * a report never carries records or a verdict from an earlier evaluation. The
 * organization is validated as nonblank and recorded verbatim; it is never
 * inferred from the fixture bodies. A caller-supplied organization must still
 * be bound to the authenticated tenant by the transport boundary, which is
 * outside this slice.</p>
 */
@Service
public class GateEvidenceService {

    private final GateEvidenceEvaluator evaluator = new GateEvidenceEvaluator();
    private final EvidenceHash hash;
    private final Clock clock;
    private EvidenceLedger ledger;

    /**
     * Create the service with a deterministic hasher and clock.
     *
     * @param hash  deterministic evidence hasher
     * @param clock injected clock for the report's generation instant
     */
    public GateEvidenceService(EvidenceHash hash, Clock clock) {
        this.hash = hash;
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
    public synchronized GateEvidenceReport produce(String organizationId, EvidenceInput input) {
        if (organizationId == null || organizationId.isBlank()) {
            throw new ApiException(422, "INVALID_ORGANIZATION", "organizationId must be a nonblank identifier");
        }
        // The bean is a singleton: build a fresh chain per call so one report never
        // carries another call's records or a stale/cumulative verdict.
        EvidenceLedger current = new EvidenceLedger(hash);
        EvidenceRecord restore = evaluator.evaluateRestore(input.snapshot(), input.journal(),
                input.restore(), clock.instant());
        EvidenceRecord downtime = evaluator.evaluateDowntime(input.runbook(), input.commands(), input.effects());
        EvidenceRecord load = evaluator.evaluateLoad(input.profile(), input.samples(), input.availability());
        EvidenceRecord accessibility = evaluator.evaluateAccessibility(input.accessibility());
        for (EvidenceRecord record : List.of(restore, downtime, load, accessibility)) {
            current.append(record);
        }
        // Verify the chain built for this report before the audit record attests to it.
        EvidenceRecord audit = evaluator.evaluateAudit(input.audit(), current.verifyChain());
        current.append(audit);
        Verdict verdict = current.entries().stream()
                .allMatch(entry -> entry.record().verdict() == Verdict.PASS)
                ? Verdict.PASS : Verdict.FAIL;
        this.ledger = current;
        return new GateEvidenceReport(organizationId, current.entries().stream()
                .map(entry -> entry.record()).toList(),
                current.entries().get(current.entries().size() - 1).hash(), verdict, clock.instant());
    }

    /**
     * Expose the ledger built by the most recent {@code produce(String, EvidenceInput)}
     * call for independent chain verification. Each call builds a fresh chain,
     * so this exposes only the latest report's records, never accumulated history.
     *
     * @return the most recently produced evidence ledger
     */
    // EI_EXPOSE_REP is excluded centrally for this aggregate accessor: the ledger is
    // the service's owned domain aggregate, exposed only for chain verification.
    public EvidenceLedger ledger() {
        return ledger;
    }
}
