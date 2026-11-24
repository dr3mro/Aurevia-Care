package com.aurevia.documents.storage;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Binds the managed object-store adapter outside the synthetic profile (ARC-06, OPS-02).
 *
 * <p>Endpoint, bucket and region come only from the environment. Missing values
 * fail startup with a message that names the settings and never prints their
 * values or any credential. Production credentials are resolved by the SDK
 * default provider chain. The bean is bound to the {@code production} profile,
 * the deployment profile that already validates database and TLS settings;
 * every non-production context, including {@code synthetic-auth} and the
 * no-profile entry-point test, creates no adapter and makes no network call.</p>
 */
@Configuration(proxyBeanMethods = false)
public class ObjectStoreConfiguration {

    /**
     * Create the private S3 adapter, failing closed on an incomplete or insecure configuration.
     *
     * @param endpoint S3-compatible endpoint, read from {@code OREVIA_S3_ENDPOINT}
     * @param bucket   private bucket name, read from {@code OREVIA_S3_BUCKET}
     * @param region   AWS region, read from {@code OREVIA_S3_REGION}
     * @return the managed object-store adapter, also usable as a signed-access port
     * @throws IllegalStateException when a setting is missing or the endpoint is not HTTPS
     */
    @Bean
    @Profile("production")
    S3ObjectStore s3ObjectStore(
            @Value("${OREVIA_S3_ENDPOINT:}") String endpoint,
            @Value("${OREVIA_S3_BUCKET:}") String bucket,
            @Value("${OREVIA_S3_REGION:}") String region) {
        List<String> missing = new ArrayList<>();
        if (endpoint.isBlank()) {
            missing.add("OREVIA_S3_ENDPOINT");
        }
        if (bucket.isBlank()) {
            missing.add("OREVIA_S3_BUCKET");
        }
        if (region.isBlank()) {
            missing.add("OREVIA_S3_REGION");
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Missing S3 object-store settings: " + String.join(", ", missing));
        }
        if (!endpoint.startsWith("https://")) {
            throw new IllegalStateException("OREVIA_S3_ENDPOINT must use https");
        }
        Region awsRegion = Region.of(region);
        URI endpointUri = URI.create(endpoint);
        S3Client client = S3Client.builder()
                .region(awsRegion)
                .endpointOverride(endpointUri)
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
        S3Presigner presigner = S3Presigner.builder()
                .region(awsRegion)
                .endpointOverride(endpointUri)
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
        return new S3ObjectStore(client, presigner, bucket);
    }
}
