package com.aurevia.documents;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.aurevia.documents.DocumentContract.Artifact;
import com.aurevia.documents.DocumentContract.DocumentAuthorization;
import com.aurevia.documents.DocumentContract.DocumentEvent;
import com.aurevia.documents.DocumentContract.PdfBinding;
import com.aurevia.documents.DocumentContract.PdfJob;
import com.aurevia.documents.DocumentContract.ReleaseDecision;

/**
 * Thread-safe synthetic implementation of {@code DocumentStore}.
 *
 * <p>Every key embeds the organization so two tenants may use identical local
 * identifiers without sharing a row. Reads return immutable snapshots and
 * writes are compare-and-set so a stale caller cannot overwrite a committed
 * artifact, job or release decision.</p>
 */
public final class InMemoryDocumentStore implements DocumentStore {

    private final ConcurrentHashMap<String, DocumentAuthorization> authorizations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Artifact> artifacts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> objects = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PdfJob> jobs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PdfBinding> bindings = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ReleaseDecision> releases = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<DocumentEvent>> events =
            new ConcurrentHashMap<>();
    private volatile boolean failNextArtifactReplace;
    private volatile boolean failNextJobReplace;
    private volatile boolean failNextReleaseInsert;

    /**
     * Create an empty store.
     */
    public InMemoryDocumentStore() {
    }

    @Override
    public Optional<DocumentAuthorization> authorization(String organizationId, String contextId) {
        return Optional.ofNullable(authorizations.get(composite(organizationId, contextId)));
    }

    @Override
    public void putAuthorization(DocumentAuthorization grant) {
        authorizations.put(composite(grant.organizationId(), grant.contextId()), grant);
    }

    @Override
    public void putAuthorizationFor(String organizationId, String contextId, DocumentAuthorization grant) {
        authorizations.put(composite(organizationId, contextId), grant);
    }

    @Override
    public Optional<Artifact> artifact(String organizationId, String id) {
        return Optional.ofNullable(artifacts.get(composite(organizationId, id)));
    }

    @Override
    public void putArtifact(Artifact artifact) {
        artifacts.put(composite(artifact.organizationId(), artifact.id()), artifact);
    }

    @Override
    public boolean replaceArtifactIfVersion(String organizationId, String id,
                                            int expectedVersion, Artifact next) {
        if (failNextArtifactReplace) {
            failNextArtifactReplace = false;
            return false;
        }
        return artifacts.compute(composite(organizationId, id), (key, current) ->
                current != null && current.version() == expectedVersion ? next : current) == next;
    }

    /**
     * Make the next artifact compare-and-set fail once, to simulate a racing writer.
     */
    public void failNextArtifactReplace() {
        failNextArtifactReplace = true;
    }

    @Override
    public List<Artifact> artifacts(String organizationId) {
        return artifacts.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(organizationId + "|"))
                .map(java.util.Map.Entry::getValue)
                .toList();
    }

    @Override
    public void putObject(String organizationId, String objectKey, int objectVersion,
                          String checksum, long sizeBytes) {
        objects.put(objectKey(organizationId, objectKey, objectVersion), checksum);
    }

    @Override
    public boolean verifyObject(String organizationId, String objectKey,
                                int objectVersion, String checksum) {
        String stored = objects.get(objectKey(organizationId, objectKey, objectVersion));
        return stored != null && stored.equals(checksum);
    }

    @Override
    public void removeObject(String organizationId, String objectKey, int objectVersion) {
        objects.remove(objectKey(organizationId, objectKey, objectVersion));
    }

    @Override
    public Optional<PdfJob> pdfJob(String organizationId, String jobId) {
        return Optional.ofNullable(jobs.get(composite(organizationId, jobId)));
    }

    @Override
    public List<PdfJob> pdfJobs(String organizationId) {
        return jobs.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(organizationId + "|"))
                .map(java.util.Map.Entry::getValue)
                .toList();
    }

    @Override
    public void putPdfJob(PdfJob job) {
        jobs.put(composite(job.organizationId(), job.jobId()), job);
    }

    @Override
    public boolean replacePdfJobIfVersion(String organizationId, String jobId,
                                          int expectedVersion, PdfJob next) {
        if (failNextJobReplace) {
            failNextJobReplace = false;
            return false;
        }
        return jobs.compute(composite(organizationId, jobId), (key, current) ->
                current != null && current.version() == expectedVersion ? next : current) == next;
    }

    /**
     * Make the next render-job compare-and-set fail once, to simulate a racing writer.
     */
    public void failNextJobReplace() {
        failNextJobReplace = true;
    }

    @Override
    public Optional<PdfBinding> pdfBinding(String organizationId, String revisionId) {
        return Optional.ofNullable(bindings.get(composite(organizationId, revisionId)));
    }

    @Override
    public void putPdfBinding(PdfBinding binding) {
        bindings.put(composite(binding.organizationId(), binding.revisionId()), binding);
    }

    @Override
    public Optional<ReleaseDecision> release(String organizationId, String revisionId,
                                             int revisionVersion, String policyVersion) {
        return Optional.ofNullable(releases.get(releaseKey(
                organizationId, revisionId, revisionVersion, policyVersion)));
    }

    @Override
    public List<ReleaseDecision> releases(String organizationId) {
        return releases.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(organizationId + "|"))
                .map(java.util.Map.Entry::getValue)
                .toList();
    }

    @Override
    public boolean insertReleaseIfAbsent(ReleaseDecision decision) {
        if (failNextReleaseInsert) {
            failNextReleaseInsert = false;
            return false;
        }
        String key = releaseKey(decision.organizationId(), decision.revisionId(),
                decision.revisionVersion(), decision.policyVersion());
        return releases.putIfAbsent(key, decision) == null;
    }

    /**
     * Make the next release insert fail once, to simulate a racing decision writer.
     */
    public void failNextReleaseInsert() {
        failNextReleaseInsert = true;
    }

    @Override
    public void appendEvent(DocumentEvent event) {
        events.computeIfAbsent(event.organizationId(), ignored -> new CopyOnWriteArrayList<>()).add(event);
    }

    @Override
    public List<DocumentEvent> events(String organizationId) {
        return List.copyOf(events.getOrDefault(organizationId, new CopyOnWriteArrayList<>()));
    }

    private static String composite(String organizationId, String id) {
        return organizationId + "|" + id;
    }

    private static String objectKey(String organizationId, String objectKey, int objectVersion) {
        return organizationId + "|" + objectKey + "|" + objectVersion;
    }

    private static String releaseKey(String organizationId, String revisionId,
                                     int revisionVersion, String policyVersion) {
        return organizationId + "|" + revisionId + "|" + revisionVersion + "|" + policyVersion;
    }
}
