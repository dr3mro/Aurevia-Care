package com.aurevia.tenant.importing;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Service;

/**
 * Applies a versioned onboarding manifest in controlled, resumable batches and
 * gates the change of source of truth on an authorized sign-off.
 *
 * <p>Each batch is reconciled before it is applied: its declared record count
 * must match its contents and its declared SHA-256 must match a digest recomputed
 * from the source records. A rejected batch stops the sequence and is never
 * applied, so a corrected manifest can resume from the returned checkpoint
 * without duplicating earlier work. Batches before sign-off are staging only
 * and reversible; only an authorized sign-off bound to the manifest version and
 * content finalizes the import. No operation mints a new attestation.</p>
 */
@Service
public class ControlledImportService {

    private static final Set<String> AUTHORIZED_ROLES =
            Set.of("tenant_admin", "privacy_auditor", "clinical_safety_lead");

    private final ImportDigests digests;

    /** Create the service over the platform SHA-256 implementation. */
    public ControlledImportService() {
        this(new ImportDigests());
    }

    /**
     * Create the service over the shared import digest primitive.
     *
     * <p>The primitive is an extension point used by a failure test so an
     * unavailable algorithm fails closed instead of yielding an unverifiable
     * batch digest.</p>
     *
     * @param digests shared digest primitive used for every reconciliation hash
     */
    ControlledImportService(ImportDigests digests) {
        this.digests = digests;
    }

    /**
     * Compute the shared lowercase-hex digest of canonical bytes.
     *
     * @param bytes canonical bytes to digest
     * @return lowercase 64-hex-digit digest
     */
    String digest(byte[] bytes) {
        return digests.sha256Hex(bytes);
    }

    /**
     * Apply the manifest's batches in order from a resumable checkpoint.
     *
     * <p>Batches already present in the checkpoint are skipped. The first batch
     * that fails reconciliation stops the run; later batches are not attempted.
     * Applying batches never finalizes the import. A checkpoint bound to a
     * different manifest version is refused before any batch is applied, so a
     * stale checkpoint cannot silently skip reconciliation of a newer manifest.</p>
     *
     * @param manifest versioned onboarding manifest
     * @param progress checkpoint from a previous attempt, or {@code empty}
     * @return the updated checkpoint, one result per attempted batch, a stop flag
     *         and a report-level refusal reason
     */
    public CommitReport commitBatches(ImportManifest manifest, ImportProgress progress) {
        if (progress.manifestVersion() != manifest.manifestVersion()) {
            return new CommitReport(progress, List.of(), true, "manifest version mismatch");
        }
        List<BatchCommit> results = new ArrayList<>();
        ImportProgress current = progress;
        boolean stopped = false;
        for (ImportBatch batch : manifest.batches()) {
            int sequence = sequenceOf(batch);
            if (current.hasCommitted(sequence)) {
                results.add(new BatchCommit(sequence, BatchOutcome.ALREADY_COMMITTED, ""));
                continue;
            }
            BatchCommit decision = reconcile(manifest, batch);
            results.add(decision);
            if (decision.outcome() != BatchOutcome.COMMITTED) {
                stopped = true;
                break;
            }
            current = current.recordBatch(sequence, batch.recordIds());
        }
        return new CommitReport(current, results, stopped, "");
    }

    /**
     * Reconcile one batch against its declared count and digest without applying it.
     *
     * @param manifest versioned onboarding manifest
     * @param batch    batch declaration to reconcile
     * @return the batch outcome and a short reason
     */
    public BatchCommit reconcile(ImportManifest manifest, ImportBatch batch) {
        List<String> ids = batch.recordIds();
        Map<String, ImportRecord> byId = byId(manifest);
        if (ids.isEmpty() || !byId.keySet().containsAll(ids)
                || ids.size() != Set.copyOf(ids).size()) {
            return new BatchCommit(sequenceOf(batch), BatchOutcome.INVALID_BATCH,
                    "invalid batch plan");
        }
        if (!Integer.valueOf(ids.size()).equals(batch.expectedCount())) {
            return new BatchCommit(sequenceOf(batch), BatchOutcome.COUNT_MISMATCH,
                    "count mismatch");
        }
        String computed = batchDigest(manifest, batch);
        if (!computed.equals(batch.sha256())) {
            return new BatchCommit(sequenceOf(batch), BatchOutcome.HASH_MISMATCH,
                    "hash mismatch");
        }
        return new BatchCommit(sequenceOf(batch), BatchOutcome.COMMITTED, "");
    }

    /**
     * Compute the deterministic SHA-256 digest of a batch's full source records.
     *
     * <p>Records are ordered by the batch's declared source ids and canonicalized
     * (sorted field keys, sorted attachments) before hashing, so key order cannot
     * change the digest while any changed value can.</p>
     *
     * @param manifest versioned onboarding manifest
     * @param batch    batch whose records are hashed
     * @return lowercase 64-hex-digit digest
     */
    String batchDigest(ImportManifest manifest, ImportBatch batch) {
        Map<String, ImportRecord> byId = byId(manifest);
        StringBuilder canonical = new StringBuilder();
        for (String id : batch.recordIds()) {
            ImportRecord record = Objects.requireNonNull(byId.get(id),
                    "batch names an unknown source id: " + id);
            canonical.append(canonicalRecord(record)).append('\n');
        }
        return digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Compute the content digest a sign-off must bind to: the manifest version and
     * the ordered batch digests the importer recomputes from the dataset.
     *
     * @param manifest versioned onboarding manifest
     * @return lowercase 64-hex-digit content digest
     */
    public String contentDigest(ImportManifest manifest) {
        StringBuilder bound = new StringBuilder().append(manifest.manifestVersion());
        for (ImportBatch batch : manifest.batches()) {
            bound.append('|').append(batchDigest(manifest, batch));
        }
        return digest(bound.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Accept an authorized sign-off and finalize the import, or refuse it.
     *
     * <p>A sign-off is accepted only when it names an approver in an authorized
     * role, decides {@code approved}, matches this manifest version, covers every
     * batch and binds to the recomputed content digest, the approval time is
     * present and every batch has been applied. The submitted checkpoint must also
     * be bound to this manifest version, so a checkpoint from another version
     * cannot carry staged work into a new manifest. A refusal leaves the checkpoint
     * unchanged and reversible.</p>
     *
     * @param manifest versioned onboarding manifest
     * @param progress checkpoint to finalize
     * @param signOff  named authorization, or null
     * @return acceptance with a finalized checkpoint, or a refusal with reason
     */
    public SignOffResult signOff(ImportManifest manifest, ImportProgress progress,
            ImportSignOff signOff) {
        if (signOff == null) {
            return refused(progress, "missing sign-off");
        }
        if (progress == null || progress.manifestVersion() != manifest.manifestVersion()) {
            return refused(progress, "version");
        }
        if (!ImportDigests.isNonBlank(signOff.approver())) {
            return refused(progress, "approver");
        }
        // This layer checks the claimed role against an allow-list; the controller has
        // already bound the named approver to the authenticated subject.
        if (!AUTHORIZED_ROLES.contains(signOff.role())) {
            return refused(progress, "unauthorized role");
        }
        if (!"approved".equals(signOff.decision())) {
            return refused(progress, "decision");
        }
        if (!Objects.equals(signOff.manifestVersion(), manifest.manifestVersion())) {
            return refused(progress, "version");
        }
        List<Integer> allSequences = manifest.batches().stream()
                .map(ControlledImportService::sequenceOf).sorted().toList();
        List<Integer> declared = signOff.batches().stream().sorted().toList();
        if (!declared.equals(allSequences)) {
            return refused(progress, "scope");
        }
        if (!contentDigest(manifest).equals(signOff.contentSha256())) {
            return refused(progress, "content");
        }
        if (!ImportDigests.isNonBlank(signOff.approvedAt())) {
            return refused(progress, "approved_at");
        }
        if (!progress.committedSequences().containsAll(allSequences)) {
            return refused(progress, "batches not committed");
        }
        return new SignOffResult(true, "", progress.finalizeImport());
    }

    /**
     * Reverse a staging-only import back to an empty checkpoint.
     *
     * @param progress staging checkpoint to reverse
     * @return an empty, unfinalized checkpoint for the same manifest version
     * @throws IllegalStateException when the import has already been signed off
     */
    public ImportProgress reverse(ImportProgress progress) {
        if (progress.finalized()) {
            throw new IllegalStateException("a signed-off import cannot be reversed");
        }
        return ImportProgress.empty(progress.manifestVersion());
    }

    private static SignOffResult refused(ImportProgress progress, String reason) {
        return new SignOffResult(false, reason, progress);
    }

    private static int sequenceOf(ImportBatch batch) {
        return Objects.requireNonNull(batch.sequence(), "batch sequence");
    }

    private static Map<String, ImportRecord> byId(ImportManifest manifest) {
        Map<String, ImportRecord> byId = new HashMap<>();
        for (ImportRecord record : manifest.records()) {
            byId.put(record.sourceId(), record);
        }
        return byId;
    }

    private static String canonicalRecord(ImportRecord record) {
        StringBuilder canonical = new StringBuilder().append(record.sourceId()).append('|');
        record.fields().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> canonical.append(entry.getKey()).append('=')
                        .append(String.valueOf(entry.getValue())).append(';'));
        canonical.append('|');
        record.attachments().stream()
                .sorted(Comparator.comparing(ImportAttachment::key))
                .forEach(attachment -> canonical.append(attachment.key()).append('=')
                        .append(String.valueOf(attachment.sha256())).append('=')
                        .append(String.valueOf(attachment.type())).append('=')
                        .append(String.valueOf(attachment.present())).append(';'));
        return canonical.append('|')
                .append(String.valueOf(record.historicalSignature())).toString();
    }
}
