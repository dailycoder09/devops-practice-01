package com.droplink.store.aws;

import com.droplink.config.DropLinkProperties;
import com.droplink.store.FileStore;
import com.droplink.store.LinkRepository;
import com.droplink.store.Notifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.sns.SnsClient;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * The AWS side of the app, used only when DROPLINK_MODE=aws. Credentials come from the default chain (the instance
 * profile on EC2). The region is DROPLINK_REGION, or else the default of the machine. DROPLINK_ENDPOINT points every
 * client at an emulator (DynamoDB Local, MinIO); DROPLINK_PATH_STYLE=true is needed for MinIO.
 *
 * The app refuses to start when a required setting is empty. The messages name the variable, never a value.
 */
@Configuration
@ConditionalOnProperty(name = "droplink.mode", havingValue = "aws")
public class AwsConfig {

    private final DropLinkProperties props;

    public AwsConfig(DropLinkProperties props) {
        this.props = props;
        validate(props);
    }

    /** Fail fast: IllegalStateException that names the empty variables (and shows no values). */
    static void validate(DropLinkProperties props) {
        List<String> missing = new ArrayList<>();
        if (blank(props.getBucket())) {
            missing.add("DROPLINK_BUCKET is empty: set it to the name of the S3 bucket");
        }
        if (blank(props.getTable())) {
            missing.add("DROPLINK_TABLE is empty: set it to the name of the DynamoDB table");
        }
        if (blank(props.getSecret())) {
            missing.add("DROPLINK_SECRET is empty: in aws mode every instance must share the same secret");
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("DropLink cannot start in aws mode. " + String.join("; ", missing) + ".");
        }
    }

    @Bean
    public S3Client s3Client() {
        var builder = S3Client.builder()
                .credentialsProvider(DefaultCredentialsProvider.create())
                .region(region())
                .serviceConfiguration(s3Configuration());
        endpoint(builder);
        return builder.build();
    }

    @Bean
    public S3Presigner s3Presigner() {
        var builder = S3Presigner.builder()
                .credentialsProvider(DefaultCredentialsProvider.create())
                .region(region())
                .serviceConfiguration(s3Configuration());
        if (!blank(props.getEndpoint())) {
            builder.endpointOverride(URI.create(props.getEndpoint().trim()));
        }
        return builder.build();
    }

    @Bean
    public DynamoDbClient dynamoDbClient() {
        var builder = DynamoDbClient.builder()
                .credentialsProvider(DefaultCredentialsProvider.create())
                .region(region());
        endpoint(builder);
        return builder.build();
    }

    @Bean
    @ConditionalOnExpression("!'${droplink.topic-arn:}'.trim().isEmpty()")
    public SnsClient snsClient() {
        var builder = SnsClient.builder()
                .credentialsProvider(DefaultCredentialsProvider.create())
                .region(region());
        endpoint(builder);
        return builder.build();
    }

    @Bean
    public FileStore fileStore(S3Client s3Client, S3Presigner s3Presigner) {
        return new S3FileStore(s3Client, s3Presigner, props.getBucket().trim());
    }

    @Bean
    public LinkRepository linkRepository(DynamoDbClient dynamoDbClient) {
        return new DynamoLinkRepository(dynamoDbClient, props.getTable().trim());
    }

    @Bean
    public Notifier notifier(ObjectProvider<SnsClient> snsClient) {
        SnsClient sns = snsClient.getIfAvailable();
        if (sns == null || blank(props.getTopicArn())) {
            return Notifier.NONE;
        }
        return new SnsNotifier(sns, props.getTopicArn().trim());
    }

    // ---- helpers -----------------------------------------------------------------------------------------------

    private Region region() {
        if (!blank(props.getRegion())) {
            return Region.of(props.getRegion().trim());
        }
        return new DefaultAwsRegionProviderChain().getRegion();
    }

    private S3Configuration s3Configuration() {
        return S3Configuration.builder()
                .pathStyleAccessEnabled(props.isPathStyle())
                .build();
    }

    private void endpoint(AwsClientBuilder<?, ?> builder) {
        if (!blank(props.getEndpoint())) {
            builder.endpointOverride(URI.create(props.getEndpoint().trim()));
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
