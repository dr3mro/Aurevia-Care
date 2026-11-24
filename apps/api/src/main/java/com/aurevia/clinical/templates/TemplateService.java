package com.aurevia.clinical.templates;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.aurevia.api.ApiException;

/**
 * Domain service for versioned clinical note templates (CLN-04).
 *
 * <p>A template version is a draft until clinical governance approves it. A
 * CODE field may only be approved once its site dictionary is itself approved.
 * Each version keeps its complete field definitions, so a note validated
 * against an old version keeps the historical field meanings.</p>
 *
 * <p>Authorization is evaluated against the supplied {@code TemplateActor} snapshot; the integrating caller
 * must supply an actor freshly derived from active membership on every request
 * (IAM-01). Template definitions are not clinical payload, so reads are open to
 * any active role, while define, approve and audit are role gated. Each command
 * appends an append-only audit fact.</p>
 */
@Service
public final class TemplateService {

    /** Role that administers tenant configuration and may define templates. */
    public static final String TENANT_ADMIN = "tenant_admin";
    /** Role that approves dictionaries and template versions. */
    public static final String CLINICAL_GOVERNANCE = "clinical_governance";
    /** Role that authors clinical notes. */
    public static final String CLINICIAN = "clinician";
    /** Role that reviews the append-only audit trail. */
    public static final String PRIVACY_AUDITOR = "privacy_auditor";

    private static final String ACTION_DEFINE = "TEMPLATE_DEFINE";
    private static final String ACTION_APPROVE = "TEMPLATE_APPROVE";
    private static final String ACTION_READ = "TEMPLATE_READ";
    private static final String ACTION_SAVE_NOTE = "TEMPLATE_NOTE_SAVE";
    private static final String ACTION_AUDIT = "AUDIT_READ";

    private final TemplateStore store;

    /**
     * Create the service over a tenant-scoped store.
     *
     * @param store persistence boundary for templates, dictionaries and audit
     */
    public TemplateService(TemplateStore store) {
        this.store = store;
    }

    /**
     * Define a new pending field dictionary in the caller's tenant.
     *
     * @param actor   caller identity
     * @param key     tenant-local dictionary key
     * @param entries permitted codes, which must be non-empty, non-blank and unique
     * @return the pending dictionary
     * @throws ApiException on invalid input, denied access or a duplicate key
     */
    public FieldDictionary defineDictionary(TemplateActor actor, String key, List<String> entries) {
        authorize(actor, ACTION_DEFINE, key);
        if (blank(key) || key.length() > 64 || !key.matches("[a-z][a-z0-9_]{1,62}")) {
            throw invalid("INVALID_DICTIONARY_KEY", "dictionary key must be 2-63 lowercase letters, digits or _");
        }
        if (entries == null || entries.isEmpty()) {
            throw invalid("INVALID_ENTRIES", "dictionary entries must not be empty");
        }
        for (String entry : entries) {
            if (blank(entry)) {
                throw invalid("INVALID_ENTRIES", "dictionary entries must be non-blank");
            }
        }
        if (new HashSet<>(entries).size() != entries.size()) {
            throw invalid("INVALID_ENTRIES", "dictionary entries must be unique");
        }
        if (store.dictionary(actor.organizationId(), key).isPresent()) {
            audit(actor, ACTION_DEFINE, "dictionary", key, "CONFLICT");
            throw conflict("dictionary already exists");
        }
        FieldDictionary dictionary = new FieldDictionary(key, entries, false, null);
        store.putDictionary(actor.organizationId(), dictionary);
        audit(actor, ACTION_DEFINE, "dictionary", key, "SUCCESS");
        return dictionary;
    }

    /**
     * Approve one pending dictionary as clinical governance.
     *
     * @param actor caller identity
     * @param key   tenant-local dictionary key
     * @return the approved dictionary
     * @throws ApiException on denied access, an unknown key or a duplicate approval
     */
    public FieldDictionary approveDictionary(TemplateActor actor, String key) {
        authorize(actor, ACTION_APPROVE, key);
        FieldDictionary found = store.dictionary(actor.organizationId(), key).orElse(null);
        if (found == null) {
            audit(actor, ACTION_APPROVE, "dictionary", key, "NOT_FOUND");
            throw notFound("dictionary not found");
        }
        if (found.approved()) {
            audit(actor, ACTION_APPROVE, "dictionary", key, "CONFLICT");
            throw conflict("dictionary already approved");
        }
        FieldDictionary approved = new FieldDictionary(found.key(), found.entries(), true, actor.subject());
        store.putDictionary(actor.organizationId(), approved);
        audit(actor, ACTION_APPROVE, "dictionary", key, "SUCCESS");
        return approved;
    }

    /**
     * Read one dictionary in the caller's tenant.
     *
     * @param actor caller identity
     * @param key   tenant-local dictionary key
     * @return the dictionary
     * @throws ApiException on denied access or an unknown key
     */
    public FieldDictionary dictionary(TemplateActor actor, String key) {
        authorize(actor, ACTION_READ, key);
        FieldDictionary found = store.dictionary(actor.organizationId(), key).orElse(null);
        if (found == null) {
            audit(actor, ACTION_READ, "dictionary", key, "NOT_FOUND");
            throw notFound("dictionary not found");
        }
        audit(actor, ACTION_READ, "dictionary", key, "SUCCESS");
        return found;
    }

    /**
     * Define a new pending version of a template family.
     *
     * @param actor  caller identity
     * @param kind   template family
     * @param fields complete field definition set for the version
     * @return the pending version
     * @throws ApiException on invalid input, denied access or an unknown dictionary
     */
    public TemplateVersion defineVersion(TemplateActor actor, TemplateKind kind, List<FieldDefinition> fields) {
        authorize(actor, ACTION_DEFINE, kind == null ? "null" : kind.name());
        if (kind == null) {
            throw invalid("INVALID_KIND", "template kind is required");
        }
        if (fields == null || fields.isEmpty()) {
            throw invalid("INVALID_FIELDS", "template fields must not be empty");
        }
        Set<String> keys = new HashSet<>();
        for (FieldDefinition field : fields) {
            if (blank(field.key())) {
                throw invalid("INVALID_FIELD_KEY", "field key is required");
            }
            if (blank(field.label())) {
                throw invalid("INVALID_FIELD_LABEL", "field label is required");
            }
            if (field.type() == null) {
                throw invalid("INVALID_FIELD_TYPE", "field type is required");
            }
            if (!keys.add(field.key())) {
                throw invalid("DUPLICATE_FIELD", "field keys must be unique");
            }
            if (field.type() == FieldType.CODE) {
                if (blank(field.dictionaryKey())) {
                    throw invalid("INVALID_DICTIONARY_REF", "CODE fields require a dictionary key");
                }
                if (store.dictionary(actor.organizationId(), field.dictionaryKey()).isEmpty()) {
                    throw invalid("UNKNOWN_DICTIONARY", "referenced dictionary does not exist");
                }
            }
        }
        TemplateVersion created = store.addVersion(actor.organizationId(), kind, fields);
        audit(actor, ACTION_DEFINE, "template", kind.name(), "SUCCESS");
        return created;
    }

    /**
     * Approve one pending template version as clinical governance.
     *
     * @param actor   caller identity
     * @param kind    template family
     * @param version version to approve
     * @return the approved version
     * @throws ApiException on denied access, an unknown/approved version or an unapproved dictionary
     */
    public TemplateVersion approveVersion(TemplateActor actor, TemplateKind kind, int version) {
        authorize(actor, ACTION_APPROVE, kind.name());
        TemplateVersion found = store.version(actor.organizationId(), kind, version).orElse(null);
        if (found == null) {
            audit(actor, ACTION_APPROVE, "template", kind.name(), "NOT_FOUND");
            throw notFound("template version not found");
        }
        if (found.approved()) {
            audit(actor, ACTION_APPROVE, "template", kind.name(), "CONFLICT");
            throw conflict("template version already approved");
        }
        for (FieldDefinition field : found.fields()) {
            if (field.type() == FieldType.CODE) {
                FieldDictionary dictionary = store.dictionary(actor.organizationId(), field.dictionaryKey())
                        .orElseThrow();
                if (!dictionary.approved()) {
                    audit(actor, ACTION_APPROVE, "template", kind.name(), "INVALID");
                    throw invalid("DICTIONARY_NOT_APPROVED", "approve the referenced dictionary first");
                }
            }
        }
        TemplateVersion approved = new TemplateVersion(found.kind(), found.version(), true,
                found.fields(), actor.subject());
        store.putVersion(actor.organizationId(), approved);
        audit(actor, ACTION_APPROVE, "template", kind.name(), "SUCCESS");
        return approved;
    }

    /**
     * Read the highest approved version of a template family.
     *
     * @param actor caller identity
     * @param kind  template family
     * @return the effective version
     * @throws ApiException on denied access or when no version is effective
     */
    public TemplateVersion effective(TemplateActor actor, TemplateKind kind) {
        authorize(actor, ACTION_READ, kind.name());
        TemplateVersion version = store.versions(actor.organizationId(), kind).stream()
                .filter(TemplateVersion::approved)
                .max(Comparator.comparingInt(TemplateVersion::version))
                .orElse(null);
        if (version == null) {
            audit(actor, ACTION_READ, "template", kind.name(), "NOT_FOUND");
            throw notFound("no effective template version");
        }
        audit(actor, ACTION_READ, "template", kind.name(), "SUCCESS");
        return version;
    }

    /**
     * Read the append-only version history of a template family.
     *
     * @param actor caller identity
     * @param kind  template family
     * @return versions oldest first
     * @throws ApiException on denied access or when the family has no versions
     */
    public List<TemplateVersion> history(TemplateActor actor, TemplateKind kind) {
        authorize(actor, ACTION_READ, kind.name());
        List<TemplateVersion> versions = store.versions(actor.organizationId(), kind);
        if (versions.isEmpty()) {
            audit(actor, ACTION_READ, "template", kind.name(), "NOT_FOUND");
            throw notFound("template family not found");
        }
        audit(actor, ACTION_READ, "template", kind.name(), "SUCCESS");
        return versions;
    }

    /**
     * Validate a note against the exact template version it references.
     *
     * @param actor caller identity
     * @param note  synthetic note revision
     * @return a report with machine-readable violations
     * @throws ApiException on denied access, invalid input or a concealed unknown version
     */
    public ValidationReport validate(TemplateActor actor, TemplateNoteRevision note) {
        authorize(actor, ACTION_READ, "note");
        if (note == null) {
            throw invalid("INVALID_NOTE", "note is required");
        }
        TemplateVersion version = store.version(actor.organizationId(), note.kind(), note.templateVersion())
                .orElse(null);
        if (version == null) {
            audit(actor, ACTION_READ, "note", note.kind().name(), "NOT_FOUND");
            throw notFound("template version not found");
        }
        Set<String> known = version.fields().stream().map(FieldDefinition::key).collect(Collectors.toSet());
        List<String> violations = new ArrayList<>();
        for (FieldValue value : note.values()) {
            if (!known.contains(value.fieldKey())) {
                violations.add("unknown:" + value.fieldKey());
            }
        }
        for (FieldDefinition field : version.fields()) {
            List<FieldValue> matches = note.values().stream()
                    .filter(value -> field.key().equals(value.fieldKey()))
                    .toList();
            if (matches.isEmpty()) {
                if (field.required()) {
                    violations.add("missing:" + field.key());
                }
            } else {
                for (FieldValue value : matches) {
                    // A present but blank value is not a documented clinical fact (CLN-04).
                    // The guard also keeps a null value out of dictionary membership checks.
                    if (blank(value.value())) {
                        violations.add("blank:" + field.key());
                    } else if (field.type() == FieldType.FREE_TEXT && blank(value.language())) {
                        violations.add("language:" + field.key());
                    } else if (field.type() == FieldType.CODE && !dictionaryHas(actor, field, value.value())) {
                        violations.add("dictionary:" + field.key());
                    } else if (field.type() == FieldType.DATE && !isIsoDate(value.value())) {
                        violations.add("format:" + field.key());
                    }
                }
            }
        }
        boolean valid = violations.isEmpty();
        audit(actor, ACTION_READ, "note", note.kind().name(), valid ? "SUCCESS" : "INVALID");
        return new ValidationReport(valid, violations);
    }

    /**
     * Save a note together with the exact field meanings it was validated against.
     *
     * <p>The version's field definitions and the entries of every referenced dictionary
     * are captured now, so a later dictionary or template change cannot rewrite what
     * this note meant (CLN-04). A note must first satisfy its exact template version.</p>
     *
     * @param actor  caller identity
     * @param noteId tenant-local opaque note identifier
     * @param note   authored note revision bound to one exact template version
     * @return the saved revision with its frozen field meanings
     * @throws ApiException on invalid input, denied access or an unknown version
     */
    public SavedNoteRevision saveNote(TemplateActor actor, String noteId, TemplateNoteRevision note) {
        authorize(actor, ACTION_SAVE_NOTE, "note");
        if (blank(noteId)) {
            throw invalid("INVALID_NOTE_ID", "note id is required");
        }
        if (note == null) {
            throw invalid("INVALID_NOTE", "note is required");
        }
        TemplateVersion version = store.version(actor.organizationId(), note.kind(), note.templateVersion())
                .orElse(null);
        if (version == null) {
            audit(actor, ACTION_SAVE_NOTE, "note", note.kind().name(), "NOT_FOUND");
            throw notFound("template version not found");
        }
        if (!validate(actor, note).valid()) {
            audit(actor, ACTION_SAVE_NOTE, "note", note.kind().name(), "INVALID");
            throw invalid("INVALID_NOTE", "note does not satisfy its template version");
        }
        Map<String, List<String>> dictionaries = new HashMap<>();
        for (FieldDefinition field : version.fields()) {
            if (field.type() == FieldType.CODE) {
                Optional<FieldDictionary> dictionary = store.dictionary(
                        actor.organizationId(), field.dictionaryKey());
                dictionaries.put(field.dictionaryKey(),
                        dictionary.isPresent() ? dictionary.get().entries() : List.of());
            }
        }
        SavedNoteRevision saved = new SavedNoteRevision(actor.organizationId(), noteId, note,
                version.fields(), dictionaries);
        store.saveNoteRevision(actor.organizationId(), saved);
        audit(actor, ACTION_SAVE_NOTE, "note", note.kind().name(), "SUCCESS");
        return saved;
    }

    /**
     * Read a saved note with the field meanings captured when it was saved.
     *
     * @param actor  caller identity
     * @param noteId tenant-local opaque note identifier
     * @return the saved revision with its frozen field meanings
     * @throws ApiException on denied access or when the note is not visible in this tenant
     */
    public SavedNoteRevision savedNote(TemplateActor actor, String noteId) {
        authorize(actor, ACTION_READ, "note");
        SavedNoteRevision saved = store.noteRevision(actor.organizationId(), noteId).orElse(null);
        if (saved == null) {
            audit(actor, ACTION_READ, "note", "saved", "NOT_FOUND");
            throw notFound("saved note not found");
        }
        audit(actor, ACTION_READ, "note", "saved", "SUCCESS");
        return saved;
    }

    /**
     * Read the tenant's append-only template audit trail.
     *
     * @param actor caller identity
     * @return audit facts in insertion order
     * @throws ApiException when the caller may not review audit facts
     */
    public List<TemplateAuditEvent> auditTrail(TemplateActor actor) {
        authorize(actor, ACTION_AUDIT, "audit");
        List<TemplateAuditEvent> events = store.audit(actor.organizationId());
        audit(actor, ACTION_AUDIT, "audit", "trail", "SUCCESS");
        return events;
    }

    private boolean dictionaryHas(TemplateActor actor, FieldDefinition field, String value) {
        return store.dictionary(actor.organizationId(), field.dictionaryKey()).orElseThrow()
                .entries().contains(value);
    }

    private void authorize(TemplateActor actor, String action, String resourceId) {
        if (actor.roles().isEmpty()) {
            audit(actor, action, "template", resourceId, "DENIED");
            throw new ApiException(403, "FORBIDDEN", "no active membership");
        }
        Set<String> allowed = switch (action) {
            case ACTION_DEFINE -> Set.of(TENANT_ADMIN, CLINICAL_GOVERNANCE);
            case ACTION_APPROVE -> Set.of(CLINICAL_GOVERNANCE);
            case ACTION_SAVE_NOTE -> Set.of(CLINICIAN);
            case ACTION_AUDIT -> Set.of(PRIVACY_AUDITOR, TENANT_ADMIN);
            default -> null;
        };
        if (allowed != null && actor.roles().stream().noneMatch(allowed::contains)) {
            audit(actor, action, "template", resourceId, "DENIED");
            throw new ApiException(403, "FORBIDDEN", "missing permission for " + action);
        }
    }

    private void audit(TemplateActor actor, String action, String resourceType, String resourceId, String outcome) {
        store.appendAudit(new TemplateAuditEvent(actor.organizationId(), actor.subject(), action,
                resourceType, resourceId, outcome));
    }

    private static boolean isIsoDate(String value) {
        try {
            LocalDate.parse(value);
            return true;
        } catch (DateTimeParseException exception) {
            return false;
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static ApiException invalid(String code, String message) {
        return new ApiException(422, code, message);
    }

    private static ApiException conflict(String message) {
        return new ApiException(409, "CONFLICT", message);
    }

    private static ApiException notFound(String message) {
        return new ApiException(404, "NOT_FOUND", message);
    }
}
