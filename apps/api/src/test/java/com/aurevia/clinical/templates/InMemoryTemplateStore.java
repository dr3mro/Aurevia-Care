package com.aurevia.clinical.templates;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Synthetic, thread-safe in-memory {@link com.aurevia.clinical.templates.TemplateStore}.
 *
 * <p>Keys embed the organization identifier so two tenants with identical
 * local keys and version numbers remain isolated. This fake lives only under
 * {@code src/test}; production uses
 * {@link com.aurevia.clinical.templates.JdbcTemplateStore} over V80.</p>
 */
public final class InMemoryTemplateStore implements TemplateStore {

    private final Map<String, FieldDictionary> dictionaries = new ConcurrentHashMap<>();
    private final Map<String, Map<Integer, TemplateVersion>> versions = new ConcurrentHashMap<>();
    private final Map<String, Map<String, SavedNoteRevision>> notes = new ConcurrentHashMap<>();
    private final Map<String, CopyOnWriteArrayList<TemplateAuditEvent>> audit = new ConcurrentHashMap<>();

    private static String familyKey(String organizationId, TemplateKind kind) {
        return organizationId + "|" + kind.name();
    }

    @Override
    public Optional<FieldDictionary> dictionary(String organizationId, String key) {
        return Optional.ofNullable(dictionaries.get(organizationId + "|" + key));
    }

    @Override
    public void putDictionary(String organizationId, FieldDictionary dictionary) {
        dictionaries.put(organizationId + "|" + dictionary.key(), dictionary);
    }

    @Override
    public Optional<TemplateVersion> version(String organizationId, TemplateKind kind, int version) {
        return Optional.ofNullable(versions.getOrDefault(familyKey(organizationId, kind), Map.of()).get(version));
    }

    @Override
    public List<TemplateVersion> versions(String organizationId, TemplateKind kind) {
        return versions.getOrDefault(familyKey(organizationId, kind), Map.of()).values().stream()
                .sorted(Comparator.comparingInt(TemplateVersion::version))
                .toList();
    }

    @Override
    public void putVersion(String organizationId, TemplateVersion version) {
        // addVersion always creates the family map before a version can be approved.
        versions.get(familyKey(organizationId, version.kind())).put(version.version(), version);
    }

    @Override
    public TemplateVersion addVersion(String organizationId, TemplateKind kind, List<FieldDefinition> fields) {
        AtomicReference<TemplateVersion> created = new AtomicReference<>();
        versions.compute(familyKey(organizationId, kind), (key, existing) -> {
            Map<Integer, TemplateVersion> family = existing == null ? new ConcurrentHashMap<>() : existing;
            int next = family.keySet().stream().mapToInt(Integer::intValue).max().orElse(0) + 1;
            TemplateVersion version = new TemplateVersion(kind, next, false, fields, null);
            family.put(next, version);
            created.set(version);
            return family;
        });
        return created.get();
    }

    @Override
    public void saveNoteRevision(String organizationId, SavedNoteRevision revision) {
        notes.computeIfAbsent(organizationId, key -> new ConcurrentHashMap<>())
                .put(revision.noteId(), revision);
    }

    @Override
    public Optional<SavedNoteRevision> noteRevision(String organizationId, String noteId) {
        return Optional.ofNullable(notes.getOrDefault(organizationId, Map.of()).get(noteId));
    }

    @Override
    public List<TemplateAuditEvent> audit(String organizationId) {
        return List.copyOf(audit.getOrDefault(organizationId, new CopyOnWriteArrayList<>()));
    }

    @Override
    public void appendAudit(TemplateAuditEvent event) {
        audit.computeIfAbsent(event.organizationId(), key -> new CopyOnWriteArrayList<>()).add(event);
    }
}
