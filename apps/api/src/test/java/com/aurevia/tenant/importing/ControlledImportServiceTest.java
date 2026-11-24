package com.aurevia.tenant.importing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.aurevia.tenant.importing.BatchOutcome;
import com.aurevia.tenant.importing.ControlledImportService;
import com.aurevia.tenant.importing.FieldMapping;
import com.aurevia.tenant.importing.HistoricalSignature;
import com.aurevia.tenant.importing.ImportAttachment;
import com.aurevia.tenant.importing.ImportBatch;
import com.aurevia.tenant.importing.ImportManifest;
import com.aurevia.tenant.importing.ImportProgress;
import com.aurevia.tenant.importing.ImportRecord;
import com.aurevia.tenant.importing.ImportSignOff;
import com.aurevia.tenant.importing.SignOffResult;

/** OPS-08: controlled, resumable batches, reconciliation and authorized sign-off. */
class ControlledImportServiceTest {

    private static final String WRONG_HASH = "f".repeat(64);
    private static final HistoricalSignature SIGNATURE =
            new HistoricalSignature("legacy-issuer", "2019-03-04T05:06:07Z", "a".repeat(64));

    private final ControlledImportService service = new ControlledImportService();

    @Test
    void cleanManifestCommitsEveryBatchInOrder() {
        List<ImportRecord> records = List.of(record("r1", "M1"), record("r2", "M2"),
                record("r3", "M3"));
        ImportManifest manifest = manifestWithBatches(records,
                spec(1, List.of("r1", "r2")), spec(2, List.of("r3")));

        CommitReport report = service.commitBatches(manifest, ImportProgress.empty(7));

        assertFalse(report.stopped());
        assertEquals(List.of(BatchOutcome.COMMITTED, BatchOutcome.COMMITTED),
                report.results().stream().map(BatchCommit::outcome).toList());
        assertEquals(List.of(1, 2), List.copyOf(report.progress().committedSequences()).stream().sorted().toList());
        assertEquals(3, report.progress().committedSourceIds().size());
        assertFalse(report.progress().finalized());
    }

    @Test
    void hashMismatchStopsTheImportAndSkipsLaterBatches() {
        List<ImportRecord> records = List.of(record("r1", "M1"), record("r2", "M2"),
                record("r3", "M3"));
        ImportManifest good = manifestWithBatches(records,
                spec(1, List.of("r1")), spec(2, List.of("r2")), spec(3, List.of("r3")));
        ImportBatch tampered = new ImportBatch(2, List.of("r2"), 1, WRONG_HASH);
        ImportManifest manifest = new ImportManifest(good.schemaVersion(), good.requirement(),
                good.manifestVersion(), good.source(), good.syntheticOnly(), good.records(),
                good.mapping(), good.provenance(),
                List.of(good.batches().get(0), tampered, good.batches().get(2)));

        CommitReport report = service.commitBatches(manifest, ImportProgress.empty(7));

        assertTrue(report.stopped());
        assertEquals(2, report.results().size());
        assertEquals(BatchOutcome.COMMITTED, report.results().get(0).outcome());
        assertEquals(BatchOutcome.HASH_MISMATCH, report.results().get(1).outcome());
        assertEquals(List.of(1), List.copyOf(report.progress().committedSequences()));
        assertEquals(1, report.progress().committedSourceIds().size());
    }

    @Test
    void failedBatchResumesWithoutDuplicates() {
        List<ImportRecord> records = List.of(record("r1", "M1"), record("r2", "M2"),
                record("r3", "M3"));
        ImportManifest good = manifestWithBatches(records,
                spec(1, List.of("r1")), spec(2, List.of("r2")), spec(3, List.of("r3")));
        ImportBatch tampered = new ImportBatch(2, List.of("r2"), 1, WRONG_HASH);
        ImportManifest failing = replaceBatch(good, 1, tampered);

        CommitReport first = service.commitBatches(failing, ImportProgress.empty(7));
        assertEquals(BatchOutcome.COMMITTED, first.results().get(0).outcome());
        assertEquals(BatchOutcome.HASH_MISMATCH, first.results().get(1).outcome());

        CommitReport resumed = service.commitBatches(good, first.progress());

        assertFalse(resumed.stopped());
        assertEquals(BatchOutcome.ALREADY_COMMITTED, resumed.results().get(0).outcome());
        assertEquals(BatchOutcome.COMMITTED, resumed.results().get(1).outcome());
        assertEquals(BatchOutcome.COMMITTED, resumed.results().get(2).outcome());
        assertEquals(List.of(1, 2, 3), resumed.progress().committedSequences().stream().sorted().toList());
        assertEquals(3, resumed.progress().committedSourceIds().size());
    }

    @Test
    void countMismatchRejectsTheBatchAndStops() {
        ImportRecord record = record("r1", "M1");
        ImportManifest base = manifestWithBatches(List.of(record), spec(1, List.of("r1")));
        ImportBatch badCount = new ImportBatch(1, List.of("r1"), 2, base.batches().get(0).sha256());
        CommitReport report = service.commitBatches(replaceBatch(base, 0, badCount),
                ImportProgress.empty(7));

        assertTrue(report.stopped());
        assertEquals(BatchOutcome.COUNT_MISMATCH, report.results().get(0).outcome());
        assertTrue(report.progress().committedSequences().isEmpty());
    }

    @Test
    void invalidBatchPlanIsRejectedForEmptyUnknownAndDuplicateIds() {
        ImportManifest base = manifestWithBatches(List.of(record("r1", "M1")),
                spec(1, List.of("r1")));
        String digest = base.batches().get(0).sha256();

        assertEquals(BatchOutcome.INVALID_BATCH, service.reconcile(base,
                new ImportBatch(1, List.of(), 0, digest)).outcome());
        assertEquals(BatchOutcome.INVALID_BATCH, service.reconcile(base,
                new ImportBatch(1, List.of("missing"), 1, digest)).outcome());
        assertEquals(BatchOutcome.INVALID_BATCH, service.reconcile(base,
                new ImportBatch(1, List.of("r1", "r1"), 2, digest)).outcome());
    }

    @Test
    void missingDeclaredCountIsACountMismatch() {
        ImportManifest base = manifestWithBatches(List.of(record("r1", "M1")),
                spec(1, List.of("r1")));
        ImportBatch noCount = new ImportBatch(1, List.of("r1"), null,
                base.batches().get(0).sha256());
        assertEquals(BatchOutcome.COUNT_MISMATCH, service.reconcile(base, noCount).outcome());
    }

    @Test
    void batchDigestIsDeterministicAndSensitiveToContent() {
        ImportRecord r1 = record("r1", "M1");
        ImportManifest base = manifestWithBatches(List.of(r1), spec(1, List.of("r1")));
        ImportBatch batch = base.batches().get(0);

        String first = service.batchDigest(base, batch);
        assertEquals(first, service.batchDigest(base, batch));

        Map<String, String> changed = new LinkedHashMap<>(r1.fields());
        changed.put("first", "Grace");
        ImportManifest altered = manifestWithBatches(
                List.of(new ImportRecord("r1", changed, r1.attachments(), r1.historicalSignature())),
                spec(1, List.of("r1")));
        assertNotEquals(first, service.batchDigest(altered, altered.batches().get(0)));
    }

    @Test
    void contentDigestBindsVersionAndOrderedBatchDigests() {
        List<ImportRecord> records = List.of(record("r1", "M1"), record("r2", "M2"));
        ImportManifest manifest = manifestWithBatches(records,
                spec(1, List.of("r2")), spec(2, List.of("r1")));

        String first = service.contentDigest(manifest);
        assertEquals(first, service.contentDigest(manifest));

        ImportManifest otherVersion = new ImportManifest(manifest.schemaVersion(),
                manifest.requirement(), 8, manifest.source(), manifest.syntheticOnly(),
                manifest.records(), manifest.mapping(), manifest.provenance(), manifest.batches());
        assertNotEquals(first, service.contentDigest(otherVersion));
    }

    @Test
    void digestFailureFailsClosed() {
        ControlledImportService failing = new ControlledImportService(new ImportDigests(() -> {
            throw new NoSuchAlgorithmException("missing");
        }));
        ImportManifest manifest = manifestWithBatches(List.of(record("r1", "M1")),
                spec(1, List.of("r1")));

        assertThrows(IllegalStateException.class,
                () -> failing.reconcile(manifest, manifest.batches().get(0)));
    }

    @Test
    void staleCheckpointVersionIsRefusedByCommit() {
        ImportManifest manifest = twoBatchManifest();
        ImportProgress stale = ImportProgress.empty(8);

        CommitReport report = service.commitBatches(manifest, stale);

        assertTrue(report.stopped());
        assertTrue(report.results().isEmpty());
        assertEquals("manifest version mismatch", report.reason());
        assertEquals(8, report.progress().manifestVersion());
        assertTrue(report.progress().committedSequences().isEmpty());
    }

    @Test
    void staleCheckpointVersionIsRefusedBySignOff() {
        ImportManifest manifest = twoBatchManifest();
        ImportProgress stale = new ImportProgress(8, Set.of(1, 2),
                Set.of("r1", "r2", "r3"), false);

        SignOffResult result = service.signOff(manifest, stale, validSignOff(manifest));

        assertFalse(result.accepted());
        assertEquals("version", result.reason());
        assertFalse(result.progress().finalized());
    }

    @Test
    void nullCheckpointIsRefusedBySignOff() {
        ImportManifest manifest = twoBatchManifest();

        SignOffResult result = service.signOff(manifest, null, validSignOff(manifest));

        assertFalse(result.accepted());
        assertEquals("version", result.reason());
    }

    @Test
    void authorizedSignOffFinalizesTheImport() {
        ImportManifest manifest = twoBatchManifest();
        ImportProgress staged = service.commitBatches(manifest, ImportProgress.empty(7)).progress();

        SignOffResult result = service.signOff(manifest, staged, validSignOff(manifest));

        assertTrue(result.accepted());
        assertTrue(result.progress().finalized());
        assertEquals(staged.committedSourceIds(), result.progress().committedSourceIds());
    }

    @Test
    void unauthorizedRoleIsRefused() {
        ImportManifest manifest = twoBatchManifest();
        ImportProgress staged = staged(manifest);
        ImportSignOff signOff = new ImportSignOff("ops-user", "reception", "approved",
                service.contentDigest(manifest), 7, List.of(1, 2), "2026-11-21T09:00:00Z");

        SignOffResult result = service.signOff(manifest, staged, signOff);

        assertFalse(result.accepted());
        assertTrue(result.reason().contains("unauthorized"));
        assertFalse(result.progress().finalized());
    }

    @Test
    void blankAndNullApproversAreRefused() {
        ImportManifest manifest = twoBatchManifest();
        ImportProgress staged = staged(manifest);
        String content = service.contentDigest(manifest);

        SignOffResult blank = service.signOff(manifest, staged,
                new ImportSignOff("  ", "tenant_admin", "approved", content, 7,
                        List.of(1, 2), "2026-11-21T09:00:00Z"));
        SignOffResult absent = service.signOff(manifest, staged,
                new ImportSignOff(null, "tenant_admin", "approved", content, 7,
                        List.of(1, 2), "2026-11-21T09:00:00Z"));

        assertFalse(blank.accepted());
        assertTrue(blank.reason().contains("approver"));
        assertFalse(absent.accepted());
        assertTrue(absent.reason().contains("approver"));
    }

    @Test
    void nonApprovedDecisionIsRefused() {
        ImportManifest manifest = twoBatchManifest();
        ImportSignOff signOff = new ImportSignOff("admin", "tenant_admin", "pending",
                service.contentDigest(manifest), 7, List.of(1, 2), "2026-11-21T09:00:00Z");

        assertFalse(service.signOff(manifest, staged(manifest), signOff).accepted());
    }

    @Test
    void wrongVersionIsRefused() {
        ImportManifest manifest = twoBatchManifest();
        ImportSignOff signOff = new ImportSignOff("admin", "tenant_admin", "approved",
                service.contentDigest(manifest), 8, List.of(1, 2), "2026-11-21T09:00:00Z");

        assertFalse(service.signOff(manifest, staged(manifest), signOff).accepted());
    }

    @Test
    void partialBatchScopeIsRefused() {
        ImportManifest manifest = twoBatchManifest();
        ImportSignOff signOff = new ImportSignOff("admin", "privacy_auditor", "approved",
                service.contentDigest(manifest), 7, List.of(1), "2026-11-21T09:00:00Z");

        assertFalse(service.signOff(manifest, staged(manifest), signOff).accepted());
    }

    @Test
    void wrongContentDigestIsRefused() {
        ImportManifest manifest = twoBatchManifest();
        ImportSignOff signOff = new ImportSignOff("admin", "clinical_safety_lead", "approved",
                WRONG_HASH, 7, List.of(1, 2), "2026-11-21T09:00:00Z");

        assertFalse(service.signOff(manifest, staged(manifest), signOff).accepted());
    }

    @Test
    void blankApprovedAtIsRefused() {
        ImportManifest manifest = twoBatchManifest();
        ImportSignOff signOff = new ImportSignOff("admin", "tenant_admin", "approved",
                service.contentDigest(manifest), 7, List.of(1, 2), "  ");

        assertFalse(service.signOff(manifest, staged(manifest), signOff).accepted());
    }

    @Test
    void signOffBeforeEveryBatchIsCommittedIsRefused() {
        ImportManifest manifest = twoBatchManifest();
        ImportProgress partial = ImportProgress.empty(7).recordBatch(1, List.of("r1"));

        assertFalse(service.signOff(manifest, partial, validSignOff(manifest)).accepted());
    }

    @Test
    void nullSignOffIsRefused() {
        ImportManifest manifest = twoBatchManifest();
        SignOffResult result = service.signOff(manifest, staged(manifest), null);
        assertFalse(result.accepted());
        assertTrue(result.reason().contains("missing"));
    }

    @Test
    void reverseBeforeSignOffClearsStagedRecords() {
        ImportManifest manifest = twoBatchManifest();
        ImportProgress staged = staged(manifest);

        ImportProgress reversed = service.reverse(staged);

        assertTrue(reversed.committedSequences().isEmpty());
        assertTrue(reversed.committedSourceIds().isEmpty());
        assertFalse(reversed.finalized());
        assertEquals(7, reversed.manifestVersion());
    }

    @Test
    void reverseAfterSignOffIsRefused() {
        ImportManifest manifest = twoBatchManifest();
        ImportProgress finalized = service.signOff(manifest, staged(manifest),
                validSignOff(manifest)).progress();

        assertThrows(IllegalStateException.class, () -> service.reverse(finalized));
    }

    // ---- helpers ----

    private ImportManifest twoBatchManifest() {
        return manifestWithBatches(
                List.of(record("r1", "M1"), record("r2", "M2"), record("r3", "M3")),
                spec(1, List.of("r1", "r2")), spec(2, List.of("r3")));
    }

    private ImportProgress staged(ImportManifest manifest) {
        return service.commitBatches(manifest, ImportProgress.empty(7)).progress();
    }

    private ImportSignOff validSignOff(ImportManifest manifest) {
        return new ImportSignOff("admin", "tenant_admin", "approved",
                service.contentDigest(manifest), 7, List.of(1, 2), "2026-11-21T09:00:00Z");
    }

    private record BatchSpec(int sequence, List<String> ids) {
    }

    private static BatchSpec spec(int sequence, List<String> ids) {
        return new BatchSpec(sequence, ids);
    }

    private ImportManifest manifestWithBatches(List<ImportRecord> records, BatchSpec... specs) {
        ImportManifest base = manifest(records, List.of());
        List<ImportBatch> batches = new ArrayList<>();
        for (BatchSpec spec : specs) {
            String digest = service.batchDigest(base,
                    new ImportBatch(spec.sequence(), spec.ids(), spec.ids().size(), WRONG_HASH));
            batches.add(new ImportBatch(spec.sequence(), spec.ids(), spec.ids().size(), digest));
        }
        return manifest(records, batches);
    }

    private static ImportManifest replaceBatch(ImportManifest manifest, int index, ImportBatch batch) {
        List<ImportBatch> batches = new ArrayList<>(manifest.batches());
        batches.set(index, batch);
        return new ImportManifest(manifest.schemaVersion(), manifest.requirement(),
                manifest.manifestVersion(), manifest.source(), manifest.syntheticOnly(),
                manifest.records(), manifest.mapping(), manifest.provenance(), batches);
    }

    private static ImportRecord record(String sourceId, String mrn) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("mrn_no", mrn);
        fields.put("issuer", "clinic-a");
        fields.put("first", "Ada");
        fields.put("last", "Lovelace");
        fields.put("dob", "1815-12-10");
        ImportAttachment attachment = new ImportAttachment("doc-" + sourceId, "b".repeat(64),
                "pdf", true);
        return new ImportRecord(sourceId, fields, List.of(attachment), SIGNATURE);
    }

    private static FieldMapping mapping() {
        Map<String, String> targets = new LinkedHashMap<>();
        targets.put("mrn_no", "mrn");
        targets.put("issuer", "mrnIssuer");
        targets.put("first", "givenName");
        targets.put("last", "familyName");
        targets.put("dob", "birthDate");
        return new FieldMapping(true, "canonical_sha256", targets,
                List.of("mrn", "mrnIssuer"), List.of());
    }

    private static ImportManifest manifest(List<ImportRecord> records, List<ImportBatch> batches) {
        return new ImportManifest("1.0", "OPS-08", 7, "legacy-clinic", true, records,
                mapping(),
                new com.aurevia.tenant.importing.ImportProvenance(
                        "legacy-clinic", "1", true, false),
                batches);
    }
}
