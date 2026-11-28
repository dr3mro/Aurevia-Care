package com.aurevia.tenant.importing;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.aurevia.api.contract.Sha256Hex;
import com.aurevia.tenant.importing.DryRunReport.Counts;
import com.aurevia.tenant.importing.DryRunReport.MissingAttachment;

/**
 * Evaluates a versioned onboarding manifest and prepares provenance-bearing import
 * records without writing to the chart.
 *
 * <p>{@link com.aurevia.tenant.importing.OnboardingImportService#dryRun(ImportManifest)}
 * is a pure computation: it reports counts, unsupported fields, duplicate risk,
 * missing attachments and errors, and touches no store.
 * {@link com.aurevia.tenant.importing.OnboardingImportService#toImportedRecords(ImportManifest)}
 * only projects records with their provenance and original signature evidence;
 * controlled commits are a separate, separately authorized operation on the
 * resulting manifest. Canonical hashing makes the report deterministic, and no
 * operation mints a new attestation.</p>
 */
@Service
public class OnboardingImportService {

    private static final String SCHEMA_VERSION = "1.0";
    private static final String REQUIREMENT = "OPS-08";
    private static final String STRATEGY = "canonical_sha256";
    private static final List<String> REQUIRED_TARGETS =
            List.of("mrn", "mrnIssuer", "givenName", "familyName", "birthDate");
    private static final Set<String> ATTACHMENT_TYPES = Set.of("pdf", "jpeg", "png");

    private final ImportDigests digests;

    /**
     * Create the service over the platform SHA-256 implementation.
     */
    public OnboardingImportService() {
        this(new ImportDigests());
    }

    /**
     * Create the service over the shared import digest primitive.
     *
     * <p>The primitive is an extension point used by a failure test so an unavailable
     * algorithm fails closed rather than producing an unverifiable identity key.</p>
     *
     * @param digests shared digest primitive used for every identity hash
     */
    OnboardingImportService(ImportDigests digests) {
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
     * Compute the deterministic dry-run report for one manifest.
     *
     * @param manifest versioned onboarding manifest
     * @return the five findings and their counts; no chart row is changed
     */
    public DryRunReport dryRun(ImportManifest manifest) {
        List<String> unsupportedFields = new ArrayList<>();
        List<String> duplicateRisk = new ArrayList<>();
        List<MissingAttachment> missingAttachments = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        validateEnvelope(manifest, errors);
        if (manifest.records().isEmpty()) {
            errors.add("dataset.records must be non-empty");
        }

        FieldMapping mapping = manifest.mapping();
        Map<String, String> reverse = mapping.reverse();
        Set<String> mappedSources = mapping.sourceToTarget().keySet();
        List<String> ignored = mapping.ignoredFields();
        List<String> identityTargets = mapping.identityFields();

        Map<String, List<String>> identities = new LinkedHashMap<>();
        for (ImportRecord record : manifest.records()) {
            String sourceId = record.sourceId();
            String label = ImportDigests.isNonBlank(sourceId) ? sourceId : "<invalid>";
            if (!ImportDigests.isNonBlank(sourceId)) {
                errors.add(label + ": invalid source_id");
            }
            Map<String, String> fields = record.fields();

            for (String field : fields.keySet()) {
                if (!mappedSources.contains(field) && !ignored.contains(field)) {
                    unsupportedFields.add(field);
                }
            }
            for (String target : REQUIRED_TARGETS) {
                String source = reverse.get(target);
                String value = source == null ? null : fields.get(source);
                if (!ImportDigests.isNonBlank(value)) {
                    errors.add(label + ": missing required field " + target);
                }
            }
            for (ImportAttachment attachment : record.attachments()) {
                if (!validAttachment(attachment)) {
                    errors.add(label + ": invalid attachment " + attachment.key());
                }
                if (Boolean.FALSE.equals(attachment.present())) {
                    missingAttachments.add(new MissingAttachment(label, attachment.key()));
                }
            }
            identities.computeIfAbsent(identityKey(fields, identityTargets, reverse),
                    ignoredKey -> new ArrayList<>()).add(label);
        }
        for (List<String> labels : identities.values()) {
            if (labels.size() > 1) {
                duplicateRisk.addAll(labels);
            }
        }

        List<String> sortedUnsupported = unsupportedFields.stream().distinct().sorted().toList();
        List<String> sortedDuplicates = duplicateRisk.stream().distinct().sorted().toList();
        validateMapping(mapping, errors);
        validateProvenance(manifest, errors);

        Counts counts = new Counts(manifest.records().size(), manifest.batches().size(),
                sortedUnsupported.size(), sortedDuplicates.size(), missingAttachments.size(),
                errors.size());
        return new DryRunReport(counts, sortedUnsupported, sortedDuplicates,
                missingAttachments, errors);
    }

    /**
     * Project each source record into an imported record carrying source provenance
     * and its original signature evidence, marked as imported with no new attestation.
     *
     * @param manifest versioned onboarding manifest
     * @return immutable list of provenance-bearing import projections
     */
    public List<ImportedRecord> toImportedRecords(ImportManifest manifest) {
        ImportProvenance provenance = manifest.provenance();
        String system = provenance == null ? null : provenance.sourceSystem();
        String version = provenance == null ? null : provenance.sourceVersion();
        List<ImportedRecord> imported = new ArrayList<>();
        for (ImportRecord record : manifest.records()) {
            imported.add(new ImportedRecord(record.sourceId(), system, version,
                    record.historicalSignature(), true, false));
        }
        return List.copyOf(imported);
    }

    private void validateEnvelope(ImportManifest manifest, List<String> errors) {
        if (!SCHEMA_VERSION.equals(manifest.schemaVersion())) {
            errors.add("manifest.schema_version");
        }
        if (!REQUIREMENT.equals(manifest.requirement())) {
            errors.add("manifest.requirement");
        }
        Integer version = manifest.manifestVersion();
        if (version == null || version <= 0) {
            errors.add("manifest.manifest_version");
        }
        if (!ImportDigests.isNonBlank(manifest.source())) {
            errors.add("manifest.source");
        }
        if (!Boolean.TRUE.equals(manifest.syntheticOnly())) {
            errors.add("manifest.synthetic_only");
        }
    }

    private void validateMapping(FieldMapping mapping, List<String> errors) {
        if (!Boolean.TRUE.equals(mapping.deterministic())) {
            errors.add("mapping.deterministic");
        }
        if (!STRATEGY.equals(mapping.strategy())) {
            errors.add("mapping.strategy");
        }
        List<String> targets = new ArrayList<>(mapping.sourceToTarget().values());
        boolean uniqueTargets = targets.size() == Set.copyOf(targets).size();
        boolean targetsOk = uniqueTargets && targets.containsAll(REQUIRED_TARGETS)
                && targets.stream().noneMatch(String::isBlank);
        if (!targetsOk) {
            errors.add("mapping.required_fields");
        }
        List<String> identity = mapping.identityFields();
        boolean identityOk = !identity.isEmpty() && identity.contains("mrn")
                && identity.contains("mrnIssuer") && targetsOk && identity.stream().allMatch(targets::contains);
        if (!identityOk) {
            errors.add("mapping.identity_fields");
        }
    }

    private void validateProvenance(ImportManifest manifest, List<String> errors) {
        ImportProvenance provenance = manifest.provenance();
        if (provenance == null) {
            errors.add("provenance");
            errors.add("provenance.source_system");
            errors.add("provenance.source_version");
            errors.add("provenance.preserve_historical_signatures");
            errors.add("provenance.creates_new_attestation");
            return;
        }
        if (!ImportDigests.isNonBlank(provenance.sourceSystem())) {
            errors.add("provenance.source_system");
        }
        if (!ImportDigests.isNonBlank(provenance.sourceVersion())) {
            errors.add("provenance.source_version");
        }
        if (!Boolean.TRUE.equals(provenance.preserveHistoricalSignatures())) {
            errors.add("provenance.preserve_historical_signatures");
        }
        if (!Boolean.FALSE.equals(provenance.createsNewAttestation())) {
            errors.add("provenance.creates_new_attestation");
        }
        for (ImportRecord record : manifest.records()) {
            if (!signatureValid(record.historicalSignature())) {
                errors.add(record.sourceId() + ": invalid historical signature");
            }
        }
    }

    private static boolean validAttachment(ImportAttachment attachment) {
        return ImportDigests.isNonBlank(attachment.key()) && isSha256(attachment.sha256())
                && ATTACHMENT_TYPES.contains(attachment.type()) && attachment.present() != null;
    }

    private static boolean signatureValid(HistoricalSignature signature) {
        return signature != null && ImportDigests.isNonBlank(signature.issuer())
                && ImportDigests.isNonBlank(signature.signedAt()) && isSha256(signature.checksum());
    }

    private static boolean isSha256(String value) {
        // The shared rule accepts either case; only the verdict is used here because
        // preserves the caller's original checksum text as provenance evidence.
        try {
            Sha256Hex.sha256Hex(value);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private String identityKey(Map<String, String> fields, List<String> identityTargets,
            Map<String, String> reverse) {
        List<String> values = new ArrayList<>();
        for (String target : identityTargets) {
            String source = reverse.get(target);
            values.add(source == null ? null : fields.get(source));
        }
        return digest(canonical(values));
    }

    /** Canonical JSON bytes: sorted keys, compact separators, UTF-8, no escaping of non-ASCII. */
    private static byte[] canonical(List<String> values) {
        StringBuilder json = new StringBuilder("[");
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            String value = values.get(index);
            if (value == null) {
                json.append("null");
            } else {
                json.append('"').append(quote(value)).append('"');
            }
        }
        return json.append(']').toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String quote(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
