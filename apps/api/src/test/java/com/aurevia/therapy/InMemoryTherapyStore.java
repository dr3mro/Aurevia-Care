package com.aurevia.therapy;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.aurevia.therapy.TherapyContract.OutcomeScale;
import com.aurevia.therapy.TherapyContract.TherapyAggregate;
import com.aurevia.therapy.TherapyContract.TherapyAuditEvent;

/**
 * Synthetic thread-safe in-memory {@link com.aurevia.therapy.TherapyStore}.
 *
 * <p>All keys embed the organization identifier, so two tenants with identical
 * local identifiers cannot observe one another's episodes, scales or audit.
 * Scale version allocation uses an atomic per-tenant map computation.</p>
 */
public final class InMemoryTherapyStore implements TherapyStore {

    private final Map<String, TherapyAggregate> episodes = new ConcurrentHashMap<>();
    private final Map<String, Map<Integer, OutcomeScale>> scales = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> scaleSequences = new ConcurrentHashMap<>();
    private final Map<String, CopyOnWriteArrayList<TherapyAuditEvent>> audit = new ConcurrentHashMap<>();

    /** Create an empty synthetic store. */
    public InMemoryTherapyStore() {
    }

    @Override
    public Optional<TherapyAggregate> find(String organizationId, String episodeId) {
        return Optional.ofNullable(episodes.get(organizationId + "|" + episodeId));
    }

    @Override
    public void save(TherapyAggregate aggregate) {
        episodes.put(aggregate.episode().organizationId() + "|" + aggregate.episode().id(), aggregate);
    }

    @Override
    public boolean hasReferral(String organizationId, String referralId) {
        return episodes.values().stream().anyMatch(
                aggregate -> organizationId.equals(aggregate.episode().organizationId())
                        && referralId.equals(aggregate.episode().referralId()));
    }

    @Override
    public Optional<OutcomeScale> scale(String organizationId, String key, int version) {
        return Optional.ofNullable(scales.getOrDefault(scaleKey(organizationId, key), Map.of()).get(version));
    }

    @Override
    public List<OutcomeScale> scales(String organizationId, String key) {
        return scales.getOrDefault(scaleKey(organizationId, key), Map.of()).values().stream()
                .sorted(Comparator.comparingInt(OutcomeScale::version))
                .toList();
    }

    @Override
    public int allocateScaleVersion(String organizationId, String key) {
        AtomicInteger sequence = scaleSequences.computeIfAbsent(
                scaleKey(organizationId, key), ignored -> new AtomicInteger());
        return sequence.incrementAndGet();
    }

    @Override
    public void saveScale(OutcomeScale scale) {
        scales.computeIfAbsent(scaleKey(scale.organizationId(), scale.key()),
                ignored -> new ConcurrentHashMap<>()).put(scale.version(), scale);
    }

    @Override
    public List<TherapyAuditEvent> audit(String organizationId) {
        return List.copyOf(audit.getOrDefault(organizationId, new CopyOnWriteArrayList<>()));
    }

    @Override
    public void appendAudit(TherapyAuditEvent event) {
        audit.computeIfAbsent(event.organizationId(), ignored -> new CopyOnWriteArrayList<>()).add(event);
    }

    private static String scaleKey(String organizationId, String key) {
        return organizationId + "|" + key;
    }
}
