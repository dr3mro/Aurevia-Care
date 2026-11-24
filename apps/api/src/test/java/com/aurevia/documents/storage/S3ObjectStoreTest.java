package com.aurevia.documents.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import software.amazon.awssdk.core.ResponseBytes;
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
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

/**
 * Acceptance and branch tests for the managed S3 object-storage adapter (ARC-06).
 *
 * <p>Every test uses a mocked client and presigner: no test contacts a real
 * bucket. Payloads are synthetic strings; keys are patient-opaque.</p>
 */
class S3ObjectStoreTest {

    private static final String TENANT = "tenant-north";
    private static final String OBJECT = "export-42";
    private static final String BUCKET = "orevia-private";
    private static final String ETAG = "\"synthetic-etag\"";
    private static final byte[] PAYLOAD = "clean clinical payload".getBytes(StandardCharsets.UTF_8);
    private static final String CHECKSUM = FileSystemObjectStore.checksum("SHA-256", PAYLOAD);

    private S3Client client;
    private S3Presigner presigner;
    private S3ObjectStore store;

    @BeforeEach
    void setUp() {
        client = mock(S3Client.class);
        presigner = mock(S3Presigner.class);
        store = new S3ObjectStore(client, presigner, BUCKET);
    }

    private ObjectKey key(int version) {
        return new ObjectKey(TENANT, OBJECT, version);
    }

    /** Stub a described object with the given state, checksum, version and ETag. */
    private void described(ObjectState state, String checksum, int version, String eTag) {
        when(client.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder()
                .contentLength((long) PAYLOAD.length)
                .eTag(eTag)
                .metadata(Map.of(
                        S3ObjectStore.STATE_KEY, state.name(),
                        S3ObjectStore.CHECKSUM_KEY, checksum,
                        S3ObjectStore.VERSION_KEY, String.valueOf(version)))
                .build());
    }

    private void missing() {
        when(client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().message("missing").build());
    }

    private void bytes(byte[] content, String eTag) {
        when(client.getObjectAsBytes(any(GetObjectRequest.class))).thenReturn(
                ResponseBytes.fromByteArray(GetObjectResponse.builder().eTag(eTag).build(), content));
    }

    @Test
    void putInQuarantineUsesPrivateEncryptedBucketAndOpaqueKey() {
        StoredObject stored = store.putInQuarantine(key(1), PAYLOAD);

        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client).putObject(request.capture(), any(RequestBody.class));
        assertEquals(BUCKET, request.getValue().bucket());
        assertEquals(key(1).value(), request.getValue().key());
        assertEquals(ServerSideEncryption.AES256, request.getValue().serverSideEncryption());
        assertEquals(ObjectCannedACL.PRIVATE, request.getValue().acl());
        assertEquals(ObjectState.QUARANTINED.name(), request.getValue().metadata().get(S3ObjectStore.STATE_KEY));
        assertEquals(CHECKSUM, request.getValue().metadata().get(S3ObjectStore.CHECKSUM_KEY));
        assertEquals("1", request.getValue().metadata().get(S3ObjectStore.VERSION_KEY));

        assertEquals(ObjectState.QUARANTINED, stored.state());
        assertEquals(CHECKSUM, stored.checksum());
        assertEquals(PAYLOAD.length, stored.sizeBytes());
    }

    @Test
    void promoteIsConditionalAndCarriesEveryMetadataField() {
        described(ObjectState.QUARANTINED, CHECKSUM, 1, ETAG);

        StoredObject promoted = store.promote(key(1), CHECKSUM);

        ArgumentCaptor<CopyObjectRequest> request = ArgumentCaptor.forClass(CopyObjectRequest.class);
        verify(client).copyObject(request.capture());
        CopyObjectRequest copy = request.getValue();
        assertEquals(BUCKET, copy.bucket());
        assertEquals(key(1).value(), copy.key());
        assertEquals(BUCKET + "/" + key(1).value(), copy.copySource());
        assertEquals(ETAG, copy.copySourceIfMatch(), "promotion must be a compare-and-set on the scanned bytes");
        assertEquals(MetadataDirective.REPLACE, copy.metadataDirective());
        assertEquals(ServerSideEncryption.AES256, copy.serverSideEncryption());
        assertEquals(ObjectState.PROMOTED.name(), copy.metadata().get(S3ObjectStore.STATE_KEY));
        assertEquals(CHECKSUM, copy.metadata().get(S3ObjectStore.CHECKSUM_KEY));
        assertEquals("1", copy.metadata().get(S3ObjectStore.VERSION_KEY));

        assertEquals(ObjectState.PROMOTED, promoted.state());
        assertEquals(CHECKSUM, promoted.checksum());
        assertEquals(PAYLOAD.length, promoted.sizeBytes());
    }

    @Test
    void promoteRejectsUnknownObject() {
        missing();

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> store.promote(key(1), CHECKSUM));
        assertTrue(failure.getMessage().contains(key(1).value()));
        assertFalse(failure.getMessage().contains("X-Amz"), "SDK errors must not leak signed URLs");
        verify(client, never()).copyObject(any(CopyObjectRequest.class));
    }

    @Test
    void promoteRejectsNonQuarantinedObject() {
        described(ObjectState.PROMOTED, CHECKSUM, 1, ETAG);

        assertThrows(IllegalStateException.class, () -> store.promote(key(1), CHECKSUM));
        verify(client, never()).copyObject(any(CopyObjectRequest.class));
    }

    @Test
    void promoteRejectsChecksumMismatch() {
        described(ObjectState.QUARANTINED, CHECKSUM, 1, ETAG);

        assertThrows(IllegalStateException.class, () -> store.promote(key(1), "c".repeat(64)));
        verify(client, never()).copyObject(any(CopyObjectRequest.class));
    }

    @Test
    void promoteRejectsChangedVersion() {
        described(ObjectState.QUARANTINED, CHECKSUM, 1, ETAG);

        assertThrows(IllegalStateException.class, () -> store.promote(key(2), CHECKSUM));
        verify(client, never()).copyObject(any(CopyObjectRequest.class));
    }

    @Test
    void promotedObjectKeepsItsChecksumAndBytes() {
        described(ObjectState.PROMOTED, CHECKSUM, 1, ETAG);
        bytes(PAYLOAD, ETAG);

        Optional<byte[]> retrieved = store.getVerified(key(1), CHECKSUM);

        assertTrue(retrieved.isPresent());
        assertArrayEquals(PAYLOAD, retrieved.orElseThrow());
    }

    @Test
    void quarantinedObjectIsNotRetrievable() {
        described(ObjectState.QUARANTINED, CHECKSUM, 1, ETAG);

        assertTrue(store.getVerified(key(1), CHECKSUM).isEmpty());
        verify(client, never()).getObjectAsBytes(any(GetObjectRequest.class));
    }

    @Test
    void wrongChecksumIsRejected() {
        described(ObjectState.PROMOTED, CHECKSUM, 1, ETAG);

        assertTrue(store.getVerified(key(1), "b".repeat(64)).isEmpty());
        verify(client, never()).getObjectAsBytes(any(GetObjectRequest.class));
    }

    @Test
    void tamperedBytesAreRejected() {
        described(ObjectState.PROMOTED, CHECKSUM, 1, ETAG);
        bytes("tampered!".getBytes(StandardCharsets.UTF_8), ETAG);

        assertTrue(store.getVerified(key(1), CHECKSUM).isEmpty(),
                "bytes changed in the bucket fail verification even with the right label and ETag");
    }

    @Test
    void divergentETagIsRejected() {
        described(ObjectState.PROMOTED, CHECKSUM, 1, ETAG);
        bytes(PAYLOAD, "\"different-etag\"");

        assertTrue(store.getVerified(key(1), CHECKSUM).isEmpty(),
                "bytes read after a concurrent overwrite must not be released under a stale state");
    }

    @Test
    void changedObjectVersionIsRejected() {
        described(ObjectState.PROMOTED, CHECKSUM, 1, ETAG);
        bytes(PAYLOAD, ETAG);

        assertTrue(store.getVerified(key(2), CHECKSUM).isEmpty());
        verify(client, never()).getObjectAsBytes(any(GetObjectRequest.class));
    }

    @Test
    void missingObjectIsNotRetrievable() {
        missing();

        assertTrue(store.getVerified(key(1), CHECKSUM).isEmpty());
        verify(client, never()).getObjectAsBytes(any(GetObjectRequest.class));
    }

    @Test
    void objectWithMissingStateLabelIsNotRetrievable() {
        when(client.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder()
                .contentLength((long) PAYLOAD.length)
                .eTag(ETAG)
                .metadata(Map.of(S3ObjectStore.CHECKSUM_KEY, CHECKSUM,
                        S3ObjectStore.VERSION_KEY, "1"))
                .build());

        assertTrue(store.getVerified(key(1), CHECKSUM).isEmpty());
    }

    @Test
    void objectWithMissingChecksumLabelIsNotRetrievable() {
        when(client.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder()
                .contentLength((long) PAYLOAD.length)
                .eTag(ETAG)
                .metadata(Map.of(S3ObjectStore.STATE_KEY, ObjectState.PROMOTED.name(),
                        S3ObjectStore.VERSION_KEY, "1"))
                .build());

        assertTrue(store.getVerified(key(1), CHECKSUM).isEmpty());
    }

    @Test
    void objectWithMissingVersionLabelIsNotRetrievable() {
        when(client.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder()
                .contentLength((long) PAYLOAD.length)
                .eTag(ETAG)
                .metadata(Map.of(S3ObjectStore.STATE_KEY, ObjectState.PROMOTED.name(),
                        S3ObjectStore.CHECKSUM_KEY, CHECKSUM))
                .build());

        assertTrue(store.getVerified(key(1), CHECKSUM).isEmpty());
    }

    @Test
    void unknownStateLabelFailsClosed() {
        when(client.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder()
                .contentLength((long) PAYLOAD.length)
                .eTag(ETAG)
                .metadata(Map.of(
                        S3ObjectStore.STATE_KEY, "NOT_A_STATE",
                        S3ObjectStore.CHECKSUM_KEY, CHECKSUM,
                        S3ObjectStore.VERSION_KEY, "1"))
                .build());

        assertThrows(IllegalArgumentException.class, () -> store.getVerified(key(1), CHECKSUM));
    }

    @Test
    void malformedVersionLabelFailsClosed() {
        when(client.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder()
                .contentLength((long) PAYLOAD.length)
                .eTag(ETAG)
                .metadata(Map.of(
                        S3ObjectStore.STATE_KEY, ObjectState.PROMOTED.name(),
                        S3ObjectStore.CHECKSUM_KEY, CHECKSUM,
                        S3ObjectStore.VERSION_KEY, "not-a-version"))
                .build());

        assertThrows(NumberFormatException.class, () -> store.getVerified(key(1), CHECKSUM));
    }

    @Test
    void objectWithNoSizeIsNotRetrievable() {
        when(client.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder()
                .eTag(ETAG)
                .metadata(Map.of(
                        S3ObjectStore.STATE_KEY, ObjectState.PROMOTED.name(),
                        S3ObjectStore.CHECKSUM_KEY, CHECKSUM,
                        S3ObjectStore.VERSION_KEY, "1"))
                .build());

        assertThrows(NullPointerException.class, () -> store.getVerified(key(1), CHECKSUM));
    }

    @Test
    void deleteRemovesObjectInAnyState() {
        described(ObjectState.QUARANTINED, CHECKSUM, 1, ETAG);

        assertTrue(store.delete(key(1)));
        ArgumentCaptor<DeleteObjectRequest> request = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(client).deleteObject(request.capture());
        assertEquals(BUCKET, request.getValue().bucket());
        assertEquals(key(1).value(), request.getValue().key());
    }

    @Test
    void deleteOfMissingObjectReturnsFalse() {
        missing();

        assertFalse(store.delete(key(1)));
        verify(client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void expireRemovesPromotedExport() {
        described(ObjectState.PROMOTED, CHECKSUM, 1, ETAG);

        assertTrue(store.expire(key(1)));
        verify(client).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void expireRejectsNonPromotedObject() {
        described(ObjectState.QUARANTINED, CHECKSUM, 1, ETAG);

        assertFalse(store.expire(key(1)), "only a promoted export may be expired");
        verify(client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void expireOfMissingObjectReturnsFalse() {
        missing();

        assertFalse(store.expire(key(1)));
        verify(client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void signedAccessIsShortLivedAndOnlyForPromotedVerifiedObjects() throws Exception {
        described(ObjectState.PROMOTED, CHECKSUM, 1, ETAG);
        PresignedGetObjectRequest signed = mock(PresignedGetObjectRequest.class);
        URL url = new URL("https://s3.example.test/" + key(1).value() + "?X-Amz-Signature=synthetic");
        when(signed.url()).thenReturn(url);
        when(presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(signed);

        Optional<URL> result = store.signedDownloadUrl(key(1), CHECKSUM);

        assertTrue(result.isPresent());
        assertEquals(url, result.orElseThrow());
        ArgumentCaptor<GetObjectPresignRequest> request = ArgumentCaptor.forClass(GetObjectPresignRequest.class);
        verify(presigner).presignGetObject(request.capture());
        assertEquals(Duration.ofSeconds(60), request.getValue().signatureDuration());
        assertEquals(key(1).value(), request.getValue().getObjectRequest().key());
        assertEquals(BUCKET, request.getValue().getObjectRequest().bucket());
    }

    @Test
    void signedAccessIsDeniedForQuarantinedObject() {
        described(ObjectState.QUARANTINED, CHECKSUM, 1, ETAG);

        assertTrue(store.signedDownloadUrl(key(1), CHECKSUM).isEmpty());
        verify(presigner, never()).presignGetObject(any(GetObjectPresignRequest.class));
    }

    @Test
    void signedAccessIsDeniedForWrongChecksum() {
        described(ObjectState.PROMOTED, CHECKSUM, 1, ETAG);

        assertTrue(store.signedDownloadUrl(key(1), "b".repeat(64)).isEmpty());
        verify(presigner, never()).presignGetObject(any(GetObjectPresignRequest.class));
    }

    @Test
    void signedAccessIsDeniedForMissingObject() {
        missing();

        assertTrue(store.signedDownloadUrl(key(1), CHECKSUM).isEmpty());
        verify(presigner, never()).presignGetObject(any(GetObjectPresignRequest.class));
    }

    @Test
    void signedAccessIsDeniedForChangedVersion() {
        described(ObjectState.PROMOTED, CHECKSUM, 1, ETAG);

        assertTrue(store.signedDownloadUrl(key(2), CHECKSUM).isEmpty());
        verify(presigner, never()).presignGetObject(any(GetObjectPresignRequest.class));
    }
}
