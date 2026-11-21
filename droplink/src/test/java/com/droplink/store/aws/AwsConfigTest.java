package com.droplink.store.aws;

import com.droplink.config.DropLinkProperties;
import com.droplink.store.FileStore;
import com.droplink.store.LinkRepository;
import com.droplink.store.Notifier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.NestedExceptionUtils;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.sns.SnsClient;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class AwsConfigTest {

    @Configuration
    @EnableConfigurationProperties(DropLinkProperties.class)
    static class PropsConfig {
    }

    // The presigner needs credentials to sign. Fake ones through system properties (first in the default chain); the
    // tests never send anything to a network.
    @BeforeAll
    static void fakeCredentials() {
        System.setProperty("aws.accessKeyId", "AKIAFAKEFAKEFAKE");
        System.setProperty("aws.secretAccessKey", "fakesecretfakesecretfakesecret");
    }

    @AfterAll
    static void clearFakeCredentials() {
        System.clearProperty("aws.accessKeyId");
        System.clearProperty("aws.secretAccessKey");
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropsConfig.class, AwsConfig.class);

    private static final String[] VALID = {
            "droplink.mode=aws",
            "droplink.bucket=the-bucket",
            "droplink.table=the-table",
            "droplink.secret=the-shared-secret",
            "droplink.region=us-west-2"
    };

    @Test
    void doesNothingInLocalMode() {
        runner.withPropertyValues("droplink.mode=local").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(FileStore.class).doesNotHaveBean(LinkRepository.class)
                    .doesNotHaveBean(S3Client.class).doesNotHaveBean(Notifier.class);
        });
    }

    @Test
    void emptyBucketFailsFastAndNamesTheVariable() {
        runner.withPropertyValues("droplink.mode=aws", "droplink.bucket=", "droplink.table=value-of-table",
                "droplink.secret=value-of-secret", "droplink.region=us-west-2").run(ctx -> {
            String message = rootMessage(ctx.getStartupFailure());
            assertThat(message).contains("DROPLINK_BUCKET")
                    .doesNotContain("DROPLINK_TABLE").doesNotContain("DROPLINK_SECRET")
                    .doesNotContain("value-of-table").doesNotContain("value-of-secret");
        });
    }

    @Test
    void emptyTableFailsFastAndNamesTheVariable() {
        runner.withPropertyValues("droplink.mode=aws", "droplink.bucket=value-of-bucket", "droplink.table=   ",
                "droplink.secret=value-of-secret", "droplink.region=us-west-2").run(ctx -> {
            String message = rootMessage(ctx.getStartupFailure());
            assertThat(message).contains("DROPLINK_TABLE")
                    .doesNotContain("DROPLINK_BUCKET").doesNotContain("DROPLINK_SECRET")
                    .doesNotContain("value-of-bucket").doesNotContain("value-of-secret");
        });
    }

    @Test
    void emptySecretFailsFastAndNamesTheVariable() {
        runner.withPropertyValues("droplink.mode=aws", "droplink.bucket=value-of-bucket", "droplink.table=value-of-table",
                "droplink.secret=", "droplink.region=us-west-2").run(ctx -> {
            String message = rootMessage(ctx.getStartupFailure());
            assertThat(message).contains("DROPLINK_SECRET").contains("every instance must share the same secret")
                    .doesNotContain("DROPLINK_BUCKET").doesNotContain("DROPLINK_TABLE")
                    .doesNotContain("value-of-bucket").doesNotContain("value-of-table");
        });
    }

    @Test
    void failureIsAnIllegalStateException() {
        runner.withPropertyValues("droplink.mode=aws").run(ctx -> {
            Throwable root = NestedExceptionUtils.getMostSpecificCause(ctx.getStartupFailure());
            assertThat(root).isInstanceOf(IllegalStateException.class);
            assertThat(root.getMessage()).contains("DROPLINK_BUCKET", "DROPLINK_TABLE", "DROPLINK_SECRET");
        });
    }

    @Test
    void validSettingsWithoutTopicGiveNoNotifier() {
        runner.withPropertyValues(VALID).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(S3Client.class).hasSingleBean(S3Presigner.class)
                    .hasSingleBean(DynamoDbClient.class).doesNotHaveBean(SnsClient.class);
            assertThat(ctx.getBean(FileStore.class)).isInstanceOf(S3FileStore.class);
            assertThat(ctx.getBean(LinkRepository.class)).isInstanceOf(DynamoLinkRepository.class);
            assertThat(ctx.getBean(Notifier.class)).isSameAs(Notifier.NONE);
            assertThat(ctx.getBean(S3Client.class).serviceClientConfiguration().region().id()).isEqualTo("us-west-2");
            assertThat(ctx.getBean(S3Client.class).serviceClientConfiguration().endpointOverride()).isEmpty();
        });
    }

    @Test
    void blankTopicIsTheSameAsNoTopic() {
        runner.withPropertyValues(VALID).withPropertyValues("droplink.topic-arn=  ").run(ctx -> {
            assertThat(ctx).hasNotFailed().doesNotHaveBean(SnsClient.class);
            assertThat(ctx.getBean(Notifier.class)).isSameAs(Notifier.NONE);
        });
    }

    @Test
    void topicGivesSnsClientAndNotifier() {
        runner.withPropertyValues(VALID).withPropertyValues("droplink.topic-arn=arn:aws:sns:us-west-2:123456789012:droplink").run(ctx -> {
            assertThat(ctx).hasNotFailed().hasSingleBean(SnsClient.class);
            assertThat(ctx.getBean(Notifier.class)).isInstanceOf(SnsNotifier.class);
        });
    }

    @Test
    void endpointAndPathStyleAreUsedByAllClientsAndThePresigner() {
        runner.withPropertyValues(VALID).withPropertyValues(
                "droplink.endpoint=http://localhost:9999",
                "droplink.path-style=true",
                "droplink.topic-arn=arn:aws:sns:us-west-2:123456789012:droplink").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            URI endpoint = URI.create("http://localhost:9999");
            assertThat(ctx.getBean(S3Client.class).serviceClientConfiguration().endpointOverride()).contains(endpoint);
            assertThat(ctx.getBean(DynamoDbClient.class).serviceClientConfiguration().endpointOverride()).contains(endpoint);
            assertThat(ctx.getBean(SnsClient.class).serviceClientConfiguration().endpointOverride()).contains(endpoint);

            // the presigner: path style (http://localhost:9999/the-bucket/key), not the virtual host style
            URI url = ctx.getBean(FileStore.class).createDownloadUrl("files/k", "a.txt", false, null, Duration.ofMinutes(1));
            assertThat(url.getScheme()).isEqualTo("http");
            assertThat(url.getHost()).isEqualTo("localhost");
            assertThat(url.getPort()).isEqualTo(9999);
            assertThat(url.getPath()).isEqualTo("/the-bucket/files/k");
        });
    }

    @Test
    void withoutPathStyleTheBucketIsInTheHostName() {
        runner.withPropertyValues(VALID).run(ctx -> {
            URI url = ctx.getBean(FileStore.class).createDownloadUrl("files/k", "a.txt", false, null, Duration.ofMinutes(1));
            assertThat(url.getHost()).isEqualTo("the-bucket.s3.us-west-2.amazonaws.com");
        });
    }

    @Test
    void emptyRegionUsesTheDefaultProviderChain() {
        String old = System.getProperty("aws.region");
        System.setProperty("aws.region", "eu-central-1");
        try {
            runner.withPropertyValues("droplink.mode=aws", "droplink.bucket=b", "droplink.table=t",
                    "droplink.secret=s", "droplink.region=").run(ctx -> {
                assertThat(ctx).hasNotFailed();
                assertThat(ctx.getBean(DynamoDbClient.class).serviceClientConfiguration().region().id()).isEqualTo("eu-central-1");
            });
        } finally {
            if (old == null) {
                System.clearProperty("aws.region");
            } else {
                System.setProperty("aws.region", old);
            }
        }
    }

    private static String rootMessage(Throwable failure) {
        assertThat(failure).isNotNull();
        Throwable root = NestedExceptionUtils.getMostSpecificCause(failure);
        assertThat(root).isInstanceOf(IllegalStateException.class);
        return root.getMessage();
    }
}
