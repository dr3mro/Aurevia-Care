package com.aurevia.pharmacyflow.substitution;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.pharmacyflow.substitution.SubstitutionContract.Proposal;
import com.aurevia.pharmacyflow.substitution.SubstitutionContract.SignedRevision;
import com.aurevia.prescription.PrescriptionContract.Alternative;
import com.aurevia.prescription.PrescriptionContract.AllergyAssessment;
import com.aurevia.prescription.PrescriptionContract.Draft;
import com.aurevia.prescription.PrescriptionContract.DrugEntry;
import com.aurevia.prescription.PrescriptionRepository;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Tenant-guarded JDBC implementation of the substitution read and decision ports (RX-01, RX-02, ARC-02).
 *
 * <p>Every read runs in the shared tenant-guarded transaction, so forced row
 * level security confines the result to the active organization. The adapter is
 * deliberately read-only for the prescription catalog, which is why it can serve
 * a pharmacy-staff propose flow without widening the clinician-gated catalog
 * search action. Decisions are append-only and bound to the organization both
 * by forced RLS and by an explicit predicate; the idempotency claim is a single
 * compare-and-set so two retries cannot both win.</p>
 */
@Component
public final class JdbcSubstitutionStore implements SubstitutionCatalog, SubstitutionStore {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<Alternative>> ALTERNATIVES = new TypeReference<>() { };

    private final PrescriptionRepository repository;
    private final TransactionTemplate transactions;
    private final NamedParameterJdbcTemplate jdbc;

    /**
     * Create the adapter over the existing prescription SQL boundary and V94 tables.
     *
     * @param repository    RLS-bounded prescription repository
     * @param transactions  tenant-guarded transaction template
     * @param jdbc          shared named-parameter JDBC template
     */
    public JdbcSubstitutionStore(PrescriptionRepository repository, TransactionTemplate transactions,
                                 NamedParameterJdbcTemplate jdbc) {
        this.repository = repository;
        this.transactions = transactions;
        this.jdbc = jdbc;
    }

    @Override
    public Draft draft(String prescriptionId) {
        return transactions.execute(status -> repository.find(prescriptionId).orElse(null));
    }

    @Override
    public List<DrugEntry> entries() {
        return transactions.execute(status -> repository.loadCatalog());
    }

    @Override
    public AllergyAssessment allergies(String patientId) {
        return transactions.execute(status -> repository.allergyAssessment(patientId));
    }

    @Override
    public String route(String entryId, int version) {
        return transactions.execute(status -> repository.catalogRoute(entryId, version));
    }

    @Override
    public Proposal findProposal(String organizationId, String proposalId) {
        String snapshot = jdbc.query("""
                 SELECT snapshot FROM public.pharmacy_substitution_proposal
                  WHERE organization_id = CAST(:organizationId AS uuid) AND id = CAST(:proposalId AS uuid)
                 """, params(organizationId).addValue("proposalId", proposalId),
                (resultSet, rowNumber) -> resultSet.getString("snapshot")).stream().findFirst().orElse(null);
        return snapshot == null ? null : JSON.readValue(snapshot, Proposal.class);
    }

    @Override
    public List<Alternative> findAlternatives(String organizationId, String proposalId) {
        String alternatives = jdbc.query("""
                 SELECT alternatives FROM public.pharmacy_substitution_proposal
                  WHERE organization_id = CAST(:organizationId AS uuid) AND id = CAST(:proposalId AS uuid)
                 """, params(organizationId).addValue("proposalId", proposalId),
                (resultSet, rowNumber) -> resultSet.getString("alternatives")).stream().findFirst().orElse(null);
        return alternatives == null ? List.of() : JSON.readValue(alternatives, ALTERNATIVES);
    }

    @Override
    public SignedRevision findRevision(String organizationId, String proposalId) {
        String snapshot = jdbc.query("""
                 SELECT snapshot FROM public.pharmacy_substitution_revision
                  WHERE organization_id = CAST(:organizationId AS uuid) AND proposal_id = CAST(:proposalId AS uuid)
                 """, params(organizationId).addValue("proposalId", proposalId),
                (resultSet, rowNumber) -> resultSet.getString("snapshot")).stream().findFirst().orElse(null);
        return snapshot == null ? null : JSON.readValue(snapshot, SignedRevision.class);
    }

    @Override
    public String findReplayProposalId(String organizationId, String command, String idempotencyKey) {
        return jdbc.query("""
                 SELECT proposal_id::text FROM public.pharmacy_substitution_decision
                  WHERE organization_id = CAST(:organizationId AS uuid) AND command = :command
                    AND idempotency_key = :idempotencyKey
                 """, params(organizationId).addValue("command", command).addValue("idempotencyKey", idempotencyKey),
                (resultSet, rowNumber) -> resultSet.getString(1)).stream().findFirst().orElse(null);
    }

    @Override
    public boolean claim(String organizationId, String command, String idempotencyKey, String proposalId) {
        List<String> claimed = jdbc.query("""
                 INSERT INTO public.pharmacy_substitution_decision
                     (organization_id, command, idempotency_key, proposal_id)
                 VALUES (CAST(:organizationId AS uuid), :command, :idempotencyKey, CAST(:proposalId AS uuid))
                 ON CONFLICT (organization_id, command, idempotency_key) DO NOTHING
                 RETURNING proposal_id::text
                 """, params(organizationId).addValue("command", command)
                .addValue("idempotencyKey", idempotencyKey).addValue("proposalId", proposalId),
                (resultSet, rowNumber) -> resultSet.getString(1));
        return !claimed.isEmpty();
    }

    @Override
    public void insertProposal(Proposal proposal, List<Alternative> alternatives) {
        jdbc.update("""
                 INSERT INTO public.pharmacy_substitution_proposal
                     (organization_id, id, prescription_id, patient_id, prescriber_subject, proposed_by,
                      reason, state, version, proposed_at, snapshot, alternatives)
                 VALUES (CAST(:organizationId AS uuid), CAST(:id AS uuid), CAST(:prescriptionId AS uuid),
                         :patientId, :prescriberSubject, :proposedBy, :reason, :state, :version,
                         CAST(:proposedAt AS timestamptz), CAST(:snapshot AS jsonb), CAST(:alternatives AS jsonb))
                 """, params(proposal.organizationId())
                .addValue("id", proposal.id())
                .addValue("prescriptionId", proposal.prescriptionId())
                .addValue("patientId", proposal.patientId())
                .addValue("prescriberSubject", proposal.prescriberSubject())
                .addValue("proposedBy", proposal.proposedBy())
                .addValue("reason", proposal.reason().wire())
                .addValue("state", proposal.state().wire())
                .addValue("version", proposal.version())
                .addValue("proposedAt", instant(proposal.proposedAt()))
                .addValue("snapshot", JSON.writeValueAsString(proposal))
                .addValue("alternatives", JSON.writeValueAsString(alternatives)));
    }

    @Override
    public void insertRevision(String organizationId, SignedRevision revision) {
        jdbc.update("""
                 INSERT INTO public.pharmacy_substitution_revision
                     (organization_id, id, prescription_id, proposal_id, revision_number, approved_by,
                      signed_at, snapshot)
                 VALUES (CAST(:organizationId AS uuid), CAST(:id AS uuid), CAST(:prescriptionId AS uuid),
                         CAST(:proposalId AS uuid), :revisionNumber, :approvedBy,
                         CAST(:signedAt AS timestamptz), CAST(:snapshot AS jsonb))
                 """, params(organizationId)
                .addValue("id", revision.id())
                .addValue("prescriptionId", revision.prescriptionId())
                .addValue("proposalId", revision.proposalId())
                .addValue("revisionNumber", revision.revisionNumber())
                .addValue("approvedBy", revision.approvedBy())
                .addValue("signedAt", instant(revision.signedAt()))
                .addValue("snapshot", JSON.writeValueAsString(revision)));
    }

    @Override
    public void closeProposal(Proposal closed, Instant decidedAt) {
        int updated = jdbc.update("""
                 UPDATE public.pharmacy_substitution_proposal
                    SET state = :state, version = :version, decided_at = CAST(:decidedAt AS timestamptz),
                        snapshot = CAST(:snapshot AS jsonb)
                  WHERE organization_id = CAST(:organizationId AS uuid) AND id = CAST(:id AS uuid)
                    AND state = 'pending' AND version = :previousVersion
                 """, params(closed.organizationId())
                .addValue("id", closed.id())
                .addValue("state", closed.state().wire())
                .addValue("version", closed.version())
                .addValue("previousVersion", closed.version() - 1)
                .addValue("decidedAt", instant(decidedAt))
                .addValue("snapshot", JSON.writeValueAsString(closed)));
        if (updated != 1) {
            // A competing decision closed the row first; fail closed instead of
            // returning a view that does not match the authoritative record.
            throw new ApiException(409, "CLOSED_PROPOSAL", "a decided substitution proposal is read-only");
        }
    }

    @Override
    public int nextRevisionNumber(String organizationId, String prescriptionId) {
        Integer next = jdbc.queryForObject("""
                 SELECT count(*) + 1 FROM public.pharmacy_substitution_revision
                  WHERE organization_id = CAST(:organizationId AS uuid)
                    AND prescription_id = CAST(:prescriptionId AS uuid)
                 """, params(organizationId).addValue("prescriptionId", prescriptionId), Integer.class);
        return Objects.requireNonNull(next, "revision sequence aggregate returned no row");
    }

    private static MapSqlParameterSource params(String organizationId) {
        return new MapSqlParameterSource().addValue("organizationId", organizationId);
    }

    private static OffsetDateTime instant(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }
}
