package com.aurevia.documents.storage;

import java.net.URL;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.MetadataDirective;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectCannedACL;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

/**
 * Managed S3 implementation of the object-storage port (ARC-06, OPS-02).
 *
 * <p>Payload bytes live in a private bucket under a tenant-scoped, patient-opaque
 * key. Every write is server-side encrypted and marked private; the lifecycle
 * label and the checksum travel as S3 user metadata, which is returned by
 * {@code headObject} in the same round trip. Bytes are released only after the
 * state, immutable version, checksum label and ETag all agree and the checksum
 * is re-derived from the bytes actually read, so a concurrent overwrite or a
 * tampered payload fails closed.</p>
 *
 * <p>Promotion is a compare-and-set copy on the scanned ETag and carries every
 * metadata field forward, so it cannot silently lose the checksum. The adapter
 * has no logging and no patient or file-name data: error messages name only the
 * opaque object key. Authorization, scan verdicts and audit facts stay in the
 * domain services above this boundary; signed URLs are exposed through the
 * separate {@link com.aurevia.documents.storage.SignedAccessPort} so
 * they can only be issued after mediation completed.</p>
 */
public final class S3ObjectStore implements ObjectStorePort, SignedAccessPort {

    /** User-metadata key holding the lifecycle label. */
    static final String STATE_KEY = "state";
    /** User-metadata key holding the verified checksum. */
    static final String CHECKSUM_KEY = "checksum";
    /** User-metadata key holding the immutable object version. */
    static final String VERSION_KEY = "version";
    /** Fixed short lifetime of a mediated download URL. */
    static final java.time.Duration SIGNED_URL_TTL = java.time.Duration.ofSeconds(60);

    private final S3Client client;
    private final S3Presigner presigner;
    private final String bucket;

    /**
     * Bind the adapter to an already-configured client, presigner and private bucket.
     *
     * <p>The collaborators are injected so tests can use a mocked client and never
     * contact a real bucket. Credentials come from the SDK default provider chain,
     * never from the repository or an object key.</p>
     *
     * @param client    S3 client for byte-level operations
     * @param presigner presigner for short-lived mediated download URLs
     * @param bucket    name of the private bucket
     */
    public S3ObjectStore(S3Client client, S3Presigner presigner, String bucket) {
        this.client = client;
        this.presigner = presigner;
        this.bucket = bucket;
    }

    @Override
    public StoredObject putInQuarantine(ObjectKey key, byte[] content) {
        String checksum = FileSystemObjectStore.checksum(FileSystemObjectStore.DIGEST, content);
        client.putObject(PutObjectRequest.builder()
                .bucket(bucket)
                .key(key.value())
                .acl(ObjectCannedACL.PRIVATE)
                .serverSideEncryption(ServerSideEncryption.AES256)
                .metadata(metadata(ObjectState.QUARANTINED, checksum, key.version()))
                .build(), RequestBody.fromBytes(content));
        return new StoredObject(key, checksum, content.length, ObjectState.QUARANTINED);
    }

    @Override
    public StoredObject promote(ObjectKey key, String scannedChecksum) {
        Described described = describe(key).orElseThrow(
                () -> new IllegalStateException("object is unknown: " + key.value()));
        StoredObject current = described.stored();
        if (current.state() != ObjectState.QUARANTINED) {
            throw new IllegalStateException("object is not in quarantine: " + key.value());
        }
        if (!current.checksum().equals(scannedChecksum)) {
            throw new IllegalStateException("object checksum does not match the scan: " + key.value());
        }
        client.copyObject(CopyObjectRequest.builder()
                .bucket(bucket)
                .key(key.value())
                .copySource(bucket + "/" + key.value())
                .copySourceIfMatch(described.eTag())
                .metadataDirective(MetadataDirective.REPLACE)
                .acl(ObjectCannedACL.PRIVATE)
                .serverSideEncryption(ServerSideEncryption.AES256)
                .metadata(metadata(ObjectState.PROMOTED, current.checksum(), key.version()))
                .build());
        return new StoredObject(key, current.checksum(), current.sizeBytes(), ObjectState.PROMOTED);
    }

    @Override
    public Optional<byte[]> getVerified(ObjectKey key, String expectedChecksum) {
        Described described = describe(key).orElse(null);
        if (described == null || described.stored().state() != ObjectState.PROMOTED
                || !described.stored().checksum().equals(expectedChecksum)) {
            return Optional.empty();
        }
        ResponseBytes<GetObjectResponse> response = client.getObjectAsBytes(
                GetObjectRequest.builder().bucket(bucket).key(key.value()).build());
        byte[] content = response.asByteArray();
        if (!described.eTag().equals(response.response().eTag())
                || !described.stored().checksum().equals(
                        FileSystemObjectStore.checksum(FileSystemObjectStore.DIGEST, content))) {
            return Optional.empty();
        }
        return Optional.of(content);
    }

    @Override
    public boolean delete(ObjectKey key) {
        if (describe(key).isEmpty()) {
            return false;
        }
        client.deleteObject(deleteRequest(key));
        return true;
    }

    @Override
    public boolean expire(ObjectKey key) {
        Described described = describe(key).orElse(null);
        if (described == null || described.stored().state() != ObjectState.PROMOTED) {
            return false;
        }
        client.deleteObject(deleteRequest(key));
        return true;
    }

    @Override
    public Optional<URL> signedDownloadUrl(ObjectKey key, String expectedChecksum) {
        Described described = describe(key).orElse(null);
        if (described == null || described.stored().state() != ObjectState.PROMOTED
                || !described.stored().checksum().equals(expectedChecksum)) {
            return Optional.empty();
        }
        return Optional.of(presigner.presignGetObject(GetObjectPresignRequest.builder()
                .signatureDuration(SIGNED_URL_TTL)
                .getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(key.value()).build())
                .build()).url());
    }

    private DeleteObjectRequest deleteRequest(ObjectKey key) {
        return DeleteObjectRequest.builder().bucket(bucket).key(key.value()).build();
    }

    private Optional<Described> describe(ObjectKey key) {
        HeadObjectResponse head;
        try {
            head = client.headObject(
                    HeadObjectRequest.builder().bucket(bucket).key(key.value()).build());
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        }
        Map<String, String> metadata = head.metadata();
        String stateText = metadata.get(STATE_KEY);
        String checksum = metadata.get(CHECKSUM_KEY);
        String versionText = metadata.get(VERSION_KEY);
        if (stateText == null || checksum == null || versionText == null) {
            return Optional.empty();
        }
        long size = Objects.requireNonNull(head.contentLength(), "object size");
        if (Integer.parseInt(versionText) != key.version()) {
            return Optional.empty();
        }
        StoredObject stored = new StoredObject(key, checksum, size, ObjectState.valueOf(stateText));
        return Optional.of(new Described(stored, Objects.requireNonNull(head.eTag(), "object ETag")));
    }

    private static Map<String, String> metadata(ObjectState state, String checksum, int version) {
        return Map.of(
                STATE_KEY, state.name(),
                CHECKSUM_KEY, checksum,
                VERSION_KEY, String.valueOf(version));
    }

    private record Described(StoredObject stored, String eTag) {
    }
}
