package com.aurevia.tenant.importing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.aurevia.tenant.importing.DryRunReport.Counts;
import com.aurevia.tenant.importing.DryRunReport.MissingAttachment;

/** OPS-08: deterministic dry run, provenance and historical-signature preservation. */
class OnboardingImportServiceTest {

    private static final String SHA_A = "a".repeat(64);
    private static final String SHA_B = "b".repeat(64);
    private static final String SHA_C = "c".repeat(64);
    private static final HistoricalSignature SIGNATURE =
            new HistoricalSignature("legacy-issuer", "2019-03-04T05:06:07Z", SHA_A);

    private final OnboardingImportService service = new OnboardingImportService();

    @Test
    void cleanManifestProducesZeroFindings() {
        ImportManifest manifest = manifest(List.of(record("r1", fields("MRN-1"))));
        DryRunReport report = service.dryRun(manifest);

        Counts counts = report.counts();
        assertEquals(1, counts.totalRecords());
        assertEquals(1, counts.batchCount());
        assertEquals(0, counts.unsupportedFields());
        assertEquals(0, counts.duplicateRisk());
        assertEquals(0, counts.missingAttachments());
        assertEquals(0, counts.errors());
        assertTrue(report.unsupportedFields().isEmpty());
        assertTrue(report.duplicateRisk().isEmpty());
        assertTrue(report.missingAttachments().isEmpty());
        assertTrue(report.errors().isEmpty());
        assertEquals(List.of("r1"), manifest.batches().get(0).recordIds());
    }

    @Test
    void dryRunChangesNoRow() {
        ImportManifest manifest = manifest(List.of(record("r1", fields("MRN-1"))));
        List<ImportRecord> before = List.copyOf(manifest.records());

        DryRunReport report = service.dryRun(manifest);

        // The dry run is a pure evaluation: it returns only a report, never an
        // ImportedRecord, and leaves the source records (the prospective rows)
        // exactly as they were. Nothing can be committed from this report alone.
        assertEquals(before, manifest.records());
        assertEquals(0, report.counts().errors());
        assertEquals(0, report.counts().missingAttachments());
    }

    @Test
    void sameInputGivesSameReportRegardlessOfJsonKeyOrder() {
        Map<String, String> ordered = new LinkedHashMap<>();
        ordered.put("mrn_no", "MRN-1");
        ordered.put("issuer", "clinic-a");
        ordered.put("first", "Ada");
        ordered.put("last", "Lovelace");
        ordered.put("dob", "1815-12-10");

        Map<String, String> reordered = new LinkedHashMap<>();
        reordered.put("dob", "1815-12-10");
        reordered.put("last", "Lovelace");
        reordered.put("first", "Ada");
        reordered.put("issuer", "clinic-a");
        reordered.put("mrn_no", "MRN-1");

        DryRunReport first = service.dryRun(manifest(List.of(record("r1", ordered))));
        DryRunReport second = service.dryRun(manifest(List.of(record("r1", reordered))));

        assertEquals(first, second);
    }

    @Test
    void unsupportedFieldIsListedAndIgnoredFieldIsNot() {
        Map<String, String> withExtra = new LinkedHashMap<>(fields("MRN-1"));
        withExtra.put("legacy_code", "X");
        withExtra.put("note", "Y");
        FieldMapping mapping = new FieldMapping(true, "canonical_sha256", targetMap(),
                List.of("mrn", "mrnIssuer"), List.of("note"));

        DryRunReport report = service.dryRun(
                manifest(List.of(record("r1", withExtra)), mapping, provenance()));

        assertEquals(List.of("legacy_code"), report.unsupportedFields());
        assertEquals(1, report.counts().unsupportedFields());
    }

    @Test
    void unsupportedFieldsAreDeduplicatedAndSorted() {
        Map<String, String> a = new LinkedHashMap<>(fields("MRN-1"));
        a.put("zz", "1");
        a.put("aa", "2");
        Map<String, String> b = new LinkedHashMap<>(fields("MRN-2"));
        b.put("aa", "2");
        b.put("zz", "1");

        DryRunReport report = service.dryRun(
                manifest(List.of(record("r1", a), record("r2", b))));

        assertEquals(List.of("aa", "zz"), report.unsupportedFields());
        assertEquals(2, report.counts().unsupportedFields());
    }

    @Test
    void missingRequiredFieldIsAnError() {
        Map<String, String> missing = new LinkedHashMap<>(fields("MRN-1"));
        missing.remove("mrn_no");

        DryRunReport report = service.dryRun(manifest(List.of(record("r1", missing))));

        assertTrue(report.errors().contains("r1: missing required field mrn"));
    }

    @Test
    void blankRequiredFieldIsAnError() {
        Map<String, String> blank = new LinkedHashMap<>(fields("MRN-1"));
        blank.put("mrn_no", "   ");

        DryRunReport report = service.dryRun(manifest(List.of(record("r1", blank))));

        assertTrue(report.errors().contains("r1: missing required field mrn"));
    }

    @Test
    void invalidSourceIdIsAnError() {
        DryRunReport report = service.dryRun(manifest(List.of(record("  ", fields("MRN-1")))));

        assertTrue(report.errors().contains("<invalid>: invalid source_id"));
    }

    @Test
    void duplicateRiskListsEveryMemberOfTheIdentityGroup() {
        DryRunReport report = service.dryRun(manifest(List.of(
                record("r2", fields("SAME")), record("r1", fields("SAME")))));

        assertEquals(List.of("r1", "r2"), report.duplicateRisk());
        assertEquals(2, report.counts().duplicateRisk());
    }

    @Test
    void invalidAttachmentsAreErrorsAndMissingAttachmentsAreReported() {
        ImportAttachment badKey = new ImportAttachment("  ", SHA_B, "pdf", true);
        ImportAttachment badHash = new ImportAttachment("k2", "short", "pdf", true);
        ImportAttachment badType = new ImportAttachment("k3", SHA_B, "gif", true);
        ImportAttachment badPresent = new ImportAttachment("k4", SHA_B, "pdf", null);
        ImportAttachment missing = new ImportAttachment("k5", SHA_B, "png", false);
        ImportAttachment nullHash = new ImportAttachment("k6", null, "pdf", true);
        ImportRecord record = new ImportRecord("r1", fields("MRN-1"),
                List.of(presentAttachment(), badKey, badHash, badType, badPresent, missing, nullHash),
                SIGNATURE);

        DryRunReport report = service.dryRun(manifest(List.of(record)));

        assertTrue(report.errors().contains("r1: invalid attachment   "));
        assertTrue(report.errors().contains("r1: invalid attachment k2"));
        assertTrue(report.errors().contains("r1: invalid attachment k3"));
        assertTrue(report.errors().contains("r1: invalid attachment k4"));
        assertTrue(report.errors().contains("r1: invalid attachment k6"));
        assertEquals(List.of(new MissingAttachment("r1", "k5")), report.missingAttachments());
        assertEquals(1, report.counts().missingAttachments());
        assertEquals(5, report.counts().errors());
    }

    @Test
    void uppercaseHexChecksumsAreAcceptedByTheSharedDigestRule() {
        String upperSha = SHA_A.toUpperCase(Locale.ROOT);
        ImportAttachment attachment = new ImportAttachment("doc-upper", upperSha, "pdf", true);
        HistoricalSignature signature =
                new HistoricalSignature("legacy-issuer", "2019-03-04T05:06:07Z", upperSha);
        ImportRecord record = new ImportRecord("r1", fields("MRN-UPPER"), List.of(attachment), signature);

        DryRunReport report = service.dryRun(manifest(List.of(record)));

        assertTrue(report.errors().isEmpty(), () -> "expected no errors but got " + report.errors());
        // OPS-08 keeps the caller's original signed evidence verbatim; validation
        // normalizes only for the verdict, never for the preserved checksum text.
        ImportedRecord projected = service.toImportedRecords(manifest(List.of(record))).get(0);
        assertEquals(upperSha, projected.historicalSignature().checksum());
    }

    @Test
    void historicalSignatureValidationCoversEveryGuard() {
        HistoricalSignature blankIssuer = new HistoricalSignature("  ", "2020-01-01T00:00:00Z", SHA_A);
        HistoricalSignature blankSignedAt = new HistoricalSignature("issuer", "  ", SHA_A);
        HistoricalSignature badChecksum = new HistoricalSignature("issuer", "2020-01-01T00:00:00Z", "short");
        List<ImportRecord> records = List.of(
                new ImportRecord("r1", fields("M1"), List.of(presentAttachment()), null),
                new ImportRecord("r2", fields("M2"), List.of(presentAttachment()), blankIssuer),
                new ImportRecord("r3", fields("M3"), List.of(presentAttachment()), blankSignedAt),
                new ImportRecord("r4", fields("M4"), List.of(presentAttachment()), badChecksum));

        DryRunReport report = service.dryRun(manifest(records));

        assertEquals(List.of(
                "r1: invalid historical signature",
                "r2: invalid historical signature",
                "r3: invalid historical signature",
                "r4: invalid historical signature"), report.errors());
    }

    @Test
    void envelopeViolationsBecomeErrors() {
        ImportManifest bad = new ImportManifest("2.0", "OPS-07", 0, "  ", false,
                List.of(record("r1", fields("MRN-1"))), mapping(), provenance(),
                List.of(new ImportBatch(1, List.of("r1"), 1, SHA_C)));

        List<String> errors = service.dryRun(bad).errors();

        assertTrue(errors.contains("manifest.schema_version"));
        assertTrue(errors.contains("manifest.requirement"));
        assertTrue(errors.contains("manifest.manifest_version"));
        assertTrue(errors.contains("manifest.source"));
        assertTrue(errors.contains("manifest.synthetic_only"));
    }

    @Test
    void mappingViolationsBecomeErrors() {
        FieldMapping bad = new FieldMapping(false, "md5", duplicateTargetMap(),
                List.of("mrn", "unknown"), List.of());
        List<String> errors = service.dryRun(manifest(List.of(record("r1", fields("MRN-1"))), bad,
                provenance())).errors();

        assertTrue(errors.contains("mapping.deterministic"));
        assertTrue(errors.contains("mapping.strategy"));
        assertTrue(errors.contains("mapping.required_fields"));
        assertTrue(errors.contains("mapping.identity_fields"));
    }

    @Test
    void emptyAndIncompleteIdentityFieldsAreErrors() {
        FieldMapping empty = new FieldMapping(true, "canonical_sha256", targetMap(),
                List.of(), List.of());
        assertTrue(service.dryRun(manifest(List.of(record("r1", fields("MRN-1"))), empty,
                provenance())).errors().contains("mapping.identity_fields"));

        FieldMapping noMrn = new FieldMapping(true, "canonical_sha256", targetMap(),
                List.of("mrnIssuer", "givenName"), List.of());
        assertTrue(service.dryRun(manifest(List.of(record("r1", fields("MRN-1"))), noMrn,
                provenance())).errors().contains("mapping.identity_fields"));
    }

    @Test
    void missingIdentityTargetStillComputesAKey() {
        FieldMapping bad = new FieldMapping(true, "canonical_sha256", targetMap(),
                List.of("mrn", "mrnIssuer", "notMapped"), List.of());

        DryRunReport report = service.dryRun(manifest(List.of(record("r1", fields("MRN-1"))), bad,
                provenance()));

        assertTrue(report.errors().contains("mapping.identity_fields"));
        assertEquals(0, report.counts().duplicateRisk());
    }

    @Test
    void provenanceViolationsBecomeErrors() {
        ImportProvenance bad = new ImportProvenance("  ", "  ", false, true);
        HistoricalSignature badSignature = new HistoricalSignature("  ", "  ", "short");
        ImportRecord record = new ImportRecord("r1", fields("MRN-1"),
                List.of(presentAttachment()), badSignature);

        List<String> errors = service.dryRun(manifest(List.of(record), mapping(), bad)).errors();

        assertTrue(errors.contains("provenance.source_system"));
        assertTrue(errors.contains("provenance.source_version"));
        assertTrue(errors.contains("provenance.preserve_historical_signatures"));
        assertTrue(errors.contains("provenance.creates_new_attestation"));
        assertTrue(errors.contains("r1: invalid historical signature"));
    }

    @Test
    void nullManifestVersionIsAnError() {
        ImportManifest bad = new ImportManifest("1.0", "OPS-08", null, "legacy-clinic", true,
                List.of(record("r1", fields("MRN-1"))), mapping(), provenance(),
                List.of(new ImportBatch(1, List.of("r1"), 1, SHA_C)));

        assertTrue(service.dryRun(bad).errors().contains("manifest.manifest_version"));
    }

    @Test
    void blankAndMissingMappedTargetsAreErrors() {
        Map<String, String> blankTarget = new LinkedHashMap<>(targetMap());
        blankTarget.put("other", "  ");
        FieldMapping blank = new FieldMapping(true, "canonical_sha256", blankTarget,
                List.of("mrn", "mrnIssuer"), List.of());
        assertTrue(service.dryRun(manifest(List.of(record("r1", fields("MRN-1"))), blank,
                provenance())).errors().contains("mapping.required_fields"));

        Map<String, String> incomplete = new LinkedHashMap<>(targetMap());
        incomplete.remove("last");
        FieldMapping missing = new FieldMapping(true, "canonical_sha256", incomplete,
                List.of("mrn", "mrnIssuer"), List.of());
        List<String> errors = service.dryRun(manifest(List.of(record("r1", fields("MRN-1"))), missing,
                provenance())).errors();
        assertTrue(errors.contains("mapping.required_fields"));
        assertTrue(errors.contains("r1: missing required field familyName"));
    }

    @Test
    void emptyRecordsIsAnError() {
        DryRunReport report = service.dryRun(manifest(List.of()));

        assertEquals(0, report.counts().totalRecords());
        assertTrue(report.errors().contains("dataset.records must be non-empty"));
    }

    @Test
    void dryRunWithNullProvenanceReportsProvenanceErrors() {
        DryRunReport report = service.dryRun(
                manifest(List.of(record("r1", fields("MRN-1"))), mapping(), null));

        assertTrue(report.errors().contains("provenance"));
        assertTrue(report.errors().contains("provenance.source_system"));
        assertTrue(report.errors().contains("provenance.creates_new_attestation"));
    }

    @Test
    void missingDigestAlgorithmFailsClosed() {
        OnboardingImportService failing = new OnboardingImportService(new ImportDigests(
                () -> { throw new java.security.NoSuchAlgorithmException("missing"); }));
        ImportManifest input = manifest(List.of(record("r1", fields("MRN-1"))));

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> failing.dryRun(input));
    }

    @Test
    void historicalSignatureStaysMarkedAsImportedWithoutNewAttestation() {
        ImportProvenance provenance = provenance();

        List<ImportedRecord> imported = service.toImportedRecords(
                manifest(List.of(record("r1", fields("MRN-1"))), mapping(), provenance));

        assertEquals(1, imported.size());
        ImportedRecord record = imported.get(0);
        assertEquals("r1", record.sourceId());
        assertEquals("legacy-clinic", record.sourceSystem());
        assertEquals("1", record.sourceVersion());
        assertEquals(SIGNATURE, record.historicalSignature());
        assertTrue(record.imported());
        assertFalse(record.newAttestation());
    }

    @Test
    void nullProvenanceUsesNullSourceValues() {
        List<ImportedRecord> imported = service.toImportedRecords(
                manifest(List.of(record("r1", fields("MRN-1"))), mapping(), null));

        assertEquals(1, imported.size());
        assertEquals(null, imported.get(0).sourceSystem());
        assertEquals(null, imported.get(0).sourceVersion());
    }

    private static ImportAttachment presentAttachment() {
        return new ImportAttachment("doc-1", SHA_B, "pdf", true);
    }

    private static ImportRecord record(String sourceId, Map<String, String> fields) {
        return new ImportRecord(sourceId, fields, List.of(presentAttachment()), SIGNATURE);
    }

    private static Map<String, String> fields(String mrn) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("mrn_no", mrn);
        values.put("issuer", "clinic-a");
        values.put("first", "Ada");
        values.put("last", "Lovelace");
        values.put("dob", "1815-12-10");
        return values;
    }

    private static Map<String, String> targetMap() {
        Map<String, String> targets = new LinkedHashMap<>();
        targets.put("mrn_no", "mrn");
        targets.put("issuer", "mrnIssuer");
        targets.put("first", "givenName");
        targets.put("last", "familyName");
        targets.put("dob", "birthDate");
        return targets;
    }

    private static Map<String, String> duplicateTargetMap() {
        Map<String, String> targets = new LinkedHashMap<>(targetMap());
        targets.put("alias", "mrn");
        return targets;
    }

    private static FieldMapping mapping() {
        return new FieldMapping(true, "canonical_sha256", targetMap(),
                List.of("mrn", "mrnIssuer"), List.of());
    }

    private static ImportProvenance provenance() {
        return new ImportProvenance("legacy-clinic", "1", true, false);
    }

    private static ImportManifest manifest(List<ImportRecord> records) {
        return manifest(records, mapping(), provenance());
    }

    private static ImportManifest manifest(List<ImportRecord> records, FieldMapping mapping,
            ImportProvenance provenance) {
        return new ImportManifest("1.0", "OPS-08", 1, "legacy-clinic", true,
                records, mapping, provenance,
                List.of(new ImportBatch(1, List.of("r1"), 1, SHA_C)));
    }
}
