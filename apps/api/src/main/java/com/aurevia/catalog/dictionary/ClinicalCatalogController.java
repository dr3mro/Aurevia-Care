package com.aurevia.catalog.dictionary;

import java.time.Instant;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.aurevia.api.ApiException;
import com.aurevia.tenant.TenantContext;

/**
 * Thin, tenant-owned HTTP adapter for versioned clinical dictionaries and diagnosis catalogues.
 *
 * <p>Every command is authorized and stored by the configuration domain through the
 * wired services; this adapter only derives the actor from the request context,
 * maps missing effective versions to 404 and translates requests into domain
 * values. The actor is never taken from the request body, so a client cannot
 * impersonate an author or approver.</p>
 */
@RestController
@RequestMapping("/api/v1/clinical-catalog")
public final class ClinicalCatalogController {

    private final ClinicalDictionaryService dictionaries;
    private final DiagnosisCatalogService diagnoses;

    /**
     * Create the controller with injected domain services.
     *
     * @param dictionaries versioned clinical dictionary service
     * @param diagnoses    versioned diagnosis catalogue service
     */
    public ClinicalCatalogController(ClinicalDictionaryService dictionaries,
                                     DiagnosisCatalogService diagnoses) {
        this.dictionaries = dictionaries;
        this.diagnoses = diagnoses;
    }

    /**
     * Define a pending clinical dictionary version.
     *
     * @param key     dictionary key from the path
     * @param request field definitions
     * @return the allocated version number
     */
    @PostMapping("/dictionaries/{key}/versions")
    public int defineDictionary(@PathVariable String key, @RequestBody DefineDictionaryRequest request) {
        requireEntries(request.fields());
        return dictionaries.define(key, request.fields(), actor());
    }

    /**
     * Approve one pending dictionary version with a named review.
     *
     * @param key     dictionary key from the path
     * @param version version to approve
     * @param request optional approval note
     * @return the approved version
     */
    @PostMapping("/dictionaries/{key}/versions/{version}/approve")
    public CatalogVersion<DictionaryField> approveDictionary(@PathVariable String key,
            @PathVariable int version, @RequestBody ApprovalRequest request) {
        return dictionaries.approve(key, version, approval(request));
    }

    /**
     * Read the effective dictionary version.
     *
     * @param key dictionary key from the path
     * @return the effective version
     */
    @GetMapping("/dictionaries/{key}/effective")
    public CatalogVersion<DictionaryField> effectiveDictionary(@PathVariable String key) {
        CatalogVersion<DictionaryField> version = dictionaries.effective(key);
        if (version == null) {
            throw new ApiException(404, "NOT_FOUND", "no effective dictionary version");
        }
        return version;
    }

    /**
     * Read the immutable dictionary version history.
     *
     * @param key dictionary key from the path
     * @return all versions, oldest first
     */
    @GetMapping("/dictionaries/{key}/history")
    public List<CatalogVersion<DictionaryField>> historyDictionary(@PathVariable String key) {
        return dictionaries.history(key);
    }

    /**
     * Define a pending diagnosis catalogue version.
     *
     * @param key     catalogue key from the path
     * @param request diagnosis code entries
     * @return the allocated version number
     */
    @PostMapping("/diagnosis/{key}/versions")
    public int defineDiagnosis(@PathVariable String key, @RequestBody DefineDiagnosisRequest request) {
        requireEntries(request.codes());
        return diagnoses.define(key, request.codes(), actor());
    }

    /**
     * Approve one pending diagnosis catalogue version with a named review.
     *
     * @param key     catalogue key from the path
     * @param version version to approve
     * @param request optional approval note
     * @return the approved version
     */
    @PostMapping("/diagnosis/{key}/versions/{version}/approve")
    public CatalogVersion<DiagnosisCode> approveDiagnosis(@PathVariable String key,
            @PathVariable int version, @RequestBody ApprovalRequest request) {
        return diagnoses.approve(key, version, approval(request));
    }

    /**
     * Read the effective diagnosis catalogue version.
     *
     * @param key catalogue key from the path
     * @return the effective version
     */
    @GetMapping("/diagnosis/{key}/effective")
    public CatalogVersion<DiagnosisCode> effectiveDiagnosis(@PathVariable String key) {
        CatalogVersion<DiagnosisCode> version = diagnoses.effective(key);
        if (version == null) {
            throw new ApiException(404, "NOT_FOUND", "no effective diagnosis catalogue version");
        }
        return version;
    }

    /**
     * Read the immutable diagnosis catalogue version history.
     *
     * @param key catalogue key from the path
     * @return all versions, oldest first
     */
    @GetMapping("/diagnosis/{key}/history")
    public List<CatalogVersion<DiagnosisCode>> historyDiagnosis(@PathVariable String key) {
        return diagnoses.history(key);
    }

    /**
     * Search the effective diagnosis catalogue without exposing draft mappings.
     *
     * @param key   catalogue key from the path
     * @param query case-insensitive search fragment
     * @return matching entries
     */
    @GetMapping("/diagnosis/{key}/search")
    public List<DiagnosisCode> searchDiagnosis(@PathVariable String key, @RequestParam String query) {
        return diagnoses.search(key, query);
    }

    private String actor() {
        return TenantContext.current().subject();
    }

    private Approval approval(ApprovalRequest request) {
        return new Approval(actor(), Instant.now().toString(), request.note());
    }

    private static void requireEntries(List<?> entries) {
        if (entries.isEmpty()) {
            throw new ApiException(422, "INVALID_ENTRIES", "at least one entry is required");
        }
    }

    /**
     * Dictionary definition request; the key and actor come from the path and context.
     *
     * @param fields field definitions
     * @return the immutable request
     */
    public record DefineDictionaryRequest(List<DictionaryField> fields) {
        /**
         * Freeze the field list.
         */
        public DefineDictionaryRequest {
            fields = List.copyOf(fields);
        }

        /**
         * Return an immutable copy of the fields.
         *
         * @return the fields
         */
        @Override
        public List<DictionaryField> fields() {
            return List.copyOf(fields);
        }
    }

    /**
     * Diagnosis catalogue definition request.
     *
     * @param codes diagnosis code entries
     * @return the immutable request
     */
    public record DefineDiagnosisRequest(List<DiagnosisCode> codes) {
        /**
         * Freeze the code list.
         */
        public DefineDiagnosisRequest {
            codes = List.copyOf(codes);
        }

        /**
         * Return an immutable copy of the codes.
         *
         * @return the codes
         */
        @Override
        public List<DiagnosisCode> codes() {
            return List.copyOf(codes);
        }
    }

    /**
     * Approval request; the approver identity comes from the actor, never the body.
     *
     * @param note optional governance note
     * @return the immutable request
     */
    public record ApprovalRequest(String note) {
    }
}
