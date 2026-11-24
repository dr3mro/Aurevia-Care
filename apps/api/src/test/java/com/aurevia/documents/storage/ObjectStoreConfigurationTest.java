package com.aurevia.documents.storage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Startup validation and profile binding tests for the managed S3 adapter (ARC-06, OPS-02).
 *
 * <p>Only the Spring context is exercised; building an SDK client here makes no
 * network call. No credential value is stored in the repository.</p>
 */
class ObjectStoreConfigurationTest {

    private static final String ENDPOINT = "AUREVIA_S3_ENDPOINT";
    private static final String BUCKET = "AUREVIA_S3_BUCKET";
    private static final String REGION = "AUREVIA_S3_REGION";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ObjectStoreConfiguration.class)
            .withInitializer(context -> context.getEnvironment().setActiveProfiles("production"));

    @BeforeEach
    void provideStaticCredentials() {
        // Keep SDK client construction away from the EC2 metadata lookup.
        System.setProperty("aws.accessKeyId", "synthetic-key");
        System.setProperty("aws.secretAccessKey", "synthetic-secret");
    }

    @AfterEach
    void clearStaticCredentials() {
        System.clearProperty("aws.accessKeyId");
        System.clearProperty("aws.secretAccessKey");
    }

    @Test
    void allMissingSettingsFailStartupAndAreNamedWithoutValues() {
        runner.withPropertyValues(ENDPOINT + "=", BUCKET + "=", REGION + "=").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageContaining(ENDPOINT)
                    .hasMessageContaining(BUCKET)
                    .hasMessageContaining(REGION);
            assertThat(context.getStartupFailure().getMessage()).doesNotContain("synthetic-secret");
        });
    }

    @Test
    void missingEndpointNamesOnlyThatSetting() {
        runner.withPropertyValues(ENDPOINT + "=", BUCKET + "=aurevia-private", REGION + "=us-east-1")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasMessageContaining(ENDPOINT);
                });
    }

    @Test
    void missingBucketNamesOnlyThatSetting() {
        runner.withPropertyValues(ENDPOINT + "=https://s3.example.test", BUCKET + "=", REGION + "=us-east-1")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasMessageContaining(BUCKET);
                });
    }

    @Test
    void missingRegionNamesOnlyThatSetting() {
        runner.withPropertyValues(ENDPOINT + "=https://s3.example.test", BUCKET + "=aurevia-private",
                REGION + "=").run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasMessageContaining(REGION);
                });
    }

    @Test
    void plainHttpEndpointFailsStartup() {
        runner.withPropertyValues(ENDPOINT + "=http://s3.example.test", BUCKET + "=aurevia-private",
                REGION + "=us-east-1").run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasMessageContaining("https");
                });
    }

    @Test
    void validSettingsCreateTheS3Adapter() {
        runner.withPropertyValues(ENDPOINT + "=https://s3.example.test", BUCKET + "=aurevia-private",
                REGION + "=us-east-1").run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ObjectStorePort.class);
                    assertThat(context).hasSingleBean(SignedAccessPort.class);
                    assertThat(context.getBean(ObjectStorePort.class)).isInstanceOf(S3ObjectStore.class);
                });
    }

    @Test
    void syntheticProfileDoesNotCreateTheS3Adapter() {
        runner.withPropertyValues(ENDPOINT + "=", BUCKET + "=", REGION + "=")
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("synthetic-auth"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(ObjectStorePort.class);
                });
    }
}
