package com.aurevia.tenant.importing;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Deterministic source-to-target field mapping for an onboarding manifest.
 *
 * <p>The only accepted strategy is {@code canonical_sha256}: identity keys and
 * batch digests are SHA-256 over canonical JSON bytes (sorted keys, compact
 * separators, no ASCII escaping), so the same source always maps the same way and
 * a validation replay is stable.</p>
 *
 * @param deterministic  must be {@code true}
 * @param strategy       must be {@code canonical_sha256}
 * @param sourceToTarget source-field name to target-field name
 * @param identityFields target names used for duplicate risk; includes the issuer-scoped MRN
 * @param ignoredFields  source fields the operator explicitly chose to drop
 */
public record FieldMapping(
        Boolean deterministic,
        String strategy,
        @JsonProperty("source_to_target") Map<String, String> sourceToTarget,
        @JsonProperty("identity_fields") List<String> identityFields,
        @JsonProperty("ignored_fields") List<String> ignoredFields) {

    /**
     * Freeze the mapping collections.
     *
     * @throws NullPointerException when a collection is null
     */
    public FieldMapping {
        sourceToTarget = Map.copyOf(sourceToTarget);
        identityFields = List.copyOf(identityFields);
        ignoredFields = List.copyOf(ignoredFields);
    }

    /**
     * Build the target-to-source reverse map.
     *
     * <p>Target names are unique in a valid mapping, so the reverse map is
     * independent of JSON key order.</p>
     *
     * @return target-field name to source-field name
     */
    public Map<String, String> reverse() {
        Map<String, String> reverse = new LinkedHashMap<>();
        sourceToTarget.forEach((source, target) -> reverse.put(target, source));
        return Map.copyOf(reverse);
    }

    /**
     * Return the immutable source-to-target map.
     *
     * @return source-field name to target-field name
     */
    @Override
    public Map<String, String> sourceToTarget() {
        return Map.copyOf(sourceToTarget);
    }

    /**
     * Return the immutable identity-field list.
     *
     * @return target names used for duplicate risk
     */
    @Override
    public List<String> identityFields() {
        return List.copyOf(identityFields);
    }

    /**
     * Return the immutable ignored-field list.
     *
     * @return source fields explicitly dropped by the operator
     */
    @Override
    public List<String> ignoredFields() {
        return List.copyOf(ignoredFields);
    }
}
