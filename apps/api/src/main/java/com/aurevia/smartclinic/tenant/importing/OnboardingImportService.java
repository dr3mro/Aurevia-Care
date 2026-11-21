package com.aurevia.smartclinic.tenant.importing;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.aurevia.smartclinic.tenant.importing.DryRunReport.Counts;
import com.aurevia.smartclinic.tenant.importing.DryRunReport.MissingAttachment;

/**
 * Evaluates a versioned onboarding manifest and prepares provenance-bearing import
 * records without writing to the chart (OPS-08).
 *
 * <p>{@link com.aurevia.smartclinic.tenant.importing.OnboardingImportService#dryRun(ImportManifest)}
 * is a pure computation: it reports counts, unsupported fields, duplicate risk,
 * missing attachments and errors, and touches no store.
 * {@link com.aurevia.smartclinic.tenant.importing.OnboardingImportService#toImportedRecords(ImportManifest)}
 * only projects records with their provenance and original signature evidence; a
 * later, separately authorized story performs controlled commits. Canonical
 * hashing makes the report deterministic, and no operation mints a new attestation.</p>
 */
@Service
public class OnboardingImportService {

    private static final String SCHEMA_VERSION = "1.0";
    private static final String REQUIREMENT = "OPS-08";
    private static final String STRATEGY = "canonical_sha256";
    private static final List<String> REQUIRED_TARGETS =
            List.of("mrn", "mrnIssuer", "givenName", "familyName", "birthDate");
    private static final Set<String> ATTACHMENT_TYPES = Set.of("pdf", "jpeg", "png");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /** Creates a message digest on demand. */
    @FunctionalInterface
    interface DigestFactory {
        /**
         * Create a fresh digest instance.
         *
         * @return a message digest
         * @throws NoSuchAlgorithmException when SHA-256 is unavailable
         */
        MessageDigest create() throws NoSuchAlgorithmException;
    }

    private final DigestFactory digestFactory;

    /**
     * Create the service over the platform SHA-256 implementation.
     */
    public OnboardingImportService() {
        this(() -> MessageDigest.getInstance("SHA-256"));
    }

    /**
     * Create the service over an explicit digest factory.
     *
     * <p>The factory is an extension point used by a failure test so an unavailable
     * algorithm fails closed rather than producing an unverifiable identity key.</p>
     *
     * @param digestFactory digest factory used for every identity hash
     */
    OnboardingImportService(DigestFactory digestFactory) {
        this.digestFactory = digestFactory;
    }

    /**
     * Compute the deterministic OPS-08 dry-run report for one manifest.
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
            String label = isNonBlank(sourceId) ? sourceId : "<invalid>";
            if (!isNonBlank(sourceId)) {
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
                if (!isNonBlank(value)) {
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
        if (!isNonBlank(manifest.source())) {
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
        if (!isNonBlank(provenance.sourceSystem())) {
            errors.add("provenance.source_system");
        }
        if (!isNonBlank(provenance.sourceVersion())) {
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
        return isNonBlank(attachment.key()) && isSha256(attachment.sha256())
                && ATTACHMENT_TYPES.contains(attachment.type()) && attachment.present() != null;
    }

    private static boolean signatureValid(HistoricalSignature signature) {
        return signature != null && isNonBlank(signature.issuer())
                && isNonBlank(signature.signedAt()) && isSha256(signature.checksum());
    }

    private static boolean isNonBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean isSha256(String value) {
        return value != null && SHA256.matcher(value).matches();
    }

    private String identityKey(Map<String, String> fields, List<String> identityTargets,
            Map<String, String> reverse) {
        List<String> values = new ArrayList<>();
        for (String target : identityTargets) {
            String source = reverse.get(target);
            values.add(source == null ? null : fields.get(source));
        }
        return sha256(canonical(values));
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

    private String sha256(byte[] bytes) {
        try {
            byte[] digest = digestFactory.create().digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(HEX[(value >> 4) & 0xf]).append(HEX[value & 0xf]);
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
