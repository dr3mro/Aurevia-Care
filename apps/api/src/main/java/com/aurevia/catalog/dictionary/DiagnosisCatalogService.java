package com.aurevia.catalog.dictionary;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.aurevia.configuration.ConfigurationService;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Versioned diagnosis and procedure catalogues (CLN-03, CLN-04, ADM-01).
 *
 * <p>Reuses the shared version persistence port for define, approve, effective
 * fold and history. Search reads only the effective approved version, so a draft
 * mapping never becomes visible before governance approval. The no-argument
 * constructor is the deterministic unit-test kernel; the injected constructor is
 * the durable production bean.</p>
 */
@Service
public final class DiagnosisCatalogService {

    private final VersionPersistence<DiagnosisCode> versions;

    /**
     * Build the deterministic in-memory kernel used by unit tests.
     */
    public DiagnosisCatalogService() {
        this(new VersionStore<>(DiagnosisCode::code));
    }

    /**
     * Build the durable production service.
     *
     * @param configurations configuration domain service for durable drafts and approvals
     * @param json           shared JSON mapper
     */
    @Autowired
    public DiagnosisCatalogService(ConfigurationService configurations, ObjectMapper json) {
        this(new DurableVersionStore<>(configurations, json, "cln_diagnosis",
                DiagnosisCode::code, new TypeReference<StoredVersion<DiagnosisCode>>() { }));
    }

    private DiagnosisCatalogService(VersionPersistence<DiagnosisCode> versions) {
        this.versions = versions;
    }

    /**
     * Define a new draft catalogue version.
     *
     * @param key   catalogue key
     * @param codes catalogue entries
     * @param author defining actor
     * @return the allocated version number
     */
    public int define(String key, List<DiagnosisCode> codes, String author) {
        return versions.define(key, codes, author);
    }

    /**
     * Approve one draft version.
     *
     * @param key      catalogue key
     * @param version  version to approve
     * @param approval named approval
     * @return the approved version
     */
    public CatalogVersion<DiagnosisCode> approve(String key, int version, Approval approval) {
        return versions.approve(key, version, approval);
    }

    /**
     * Return the effective approved catalogue version.
     *
     * @param key catalogue key
     * @return the effective version, or {@code null}
     */
    public CatalogVersion<DiagnosisCode> effective(String key) {
        return versions.effective(key);
    }

    /**
     * Return the immutable version history.
     *
     * @param key catalogue key
     * @return all versions, oldest first
     */
    public List<CatalogVersion<DiagnosisCode>> history(String key) {
        return versions.history(key);
    }

    /**
     * Search the effective approved catalogue by code or display text.
     *
     * @param key   catalogue key
     * @param query case-insensitive search fragment
     * @return matching entries, or an empty list when no version is effective
     */
    public List<DiagnosisCode> search(String key, String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        CatalogVersion<DiagnosisCode> version = versions.effective(key);
        if (version == null) {
            return List.of();
        }
        List<DiagnosisCode> matches = new ArrayList<>();
        for (DiagnosisCode code : version.entries()) {
            if (code.matches(query)) {
                matches.add(code);
            }
        }
        return List.copyOf(matches);
    }
}
