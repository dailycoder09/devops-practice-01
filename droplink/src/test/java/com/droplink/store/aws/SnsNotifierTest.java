package com.droplink.store.aws;

import com.droplink.model.Link;
import com.droplink.model.LinkStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.PublishResponse;
import software.amazon.awssdk.services.sns.model.SnsException;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SnsNotifierTest {

    private static final String ID = "aB3dE5gH7jK9";
    private static final String KEY = "files/aB3dE5gH7jK9/secret-key.bin";
    private static final String PASSWORD_HASH = "$2a$10$abcdefghijklmnopqrstuvPASSWORDHASH";
    private static final String TOKEN_HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String TOPIC = "arn:aws:sns:us-west-2:123456789012:droplink";

    private static Link link(Integer max) {
        return new Link(ID, "holiday photos.zip", "application/zip", 100L, KEY, Instant.now(), Instant.now().plusSeconds(3600),
                max, 0L, PASSWORD_HASH, TOKEN_HASH, LinkStatus.ACTIVE);
    }

    @Test
    void publishesAMessageWithoutAnySecret() {
        SnsClient sns = mock(SnsClient.class);
        when(sns.publish(any(PublishRequest.class))).thenReturn(PublishResponse.builder().build());
        try (SnsNotifier notifier = new SnsNotifier(sns, TOPIC)) {
            notifier.downloaded(link(5), 2);

            ArgumentCaptor<PublishRequest> captor = ArgumentCaptor.forClass(PublishRequest.class);
            verify(sns, timeout(3000)).publish(captor.capture());
            PublishRequest request = captor.getValue();
            assertThat(request.topicArn()).isEqualTo(TOPIC);
            assertThat(request.subject()).isEqualTo("DropLink: file downloaded").hasSizeLessThanOrEqualTo(100);
            assertThat(request.message())
                    .contains("holiday photos.zip")
                    .contains("Downloads so far: 2")
                    .contains("Downloads left: 3")
                    .contains("Time: ")
                    .doesNotContain(ID).doesNotContain(KEY).doesNotContain("secret-key")
                    .doesNotContain(PASSWORD_HASH).doesNotContain("PASSWORDHASH").doesNotContain(TOKEN_HASH)
                    .doesNotContain("aB3d");
            assertThat(request.subject()).doesNotContain(ID);
        }
    }

    @Test
    void noLimitAndNothingLeft() {
        Instant at = Instant.parse("2025-03-01T12:00:00Z");
        assertThat(SnsNotifier.message("a.txt", 7, null, at)).contains("Downloads left: no limit").contains("Time: 2025-03-01T12:00:00Z");
        assertThat(SnsNotifier.message("a.txt", 1, 1, at)).contains("Downloads left: 0");
        assertThat(SnsNotifier.message("a.txt", 9, 1, at)).contains("Downloads left: 0");
    }

    @Test
    void fileNameCannotBreakTheMessageLayout() {
        String message = SnsNotifier.message("a\r\nDownloads left: 99.txt", 1, 5, Instant.now());
        assertThat(message.lines().filter(l -> l.startsWith("Downloads left:")).count()).isEqualTo(1);
        assertThat(SnsNotifier.message("n".repeat(1000), 1, 5, Instant.now()).length()).isLessThan(500);
    }

    @Test
    void neverThrowsWhenSnsFails() {
        SnsClient sns = mock(SnsClient.class);
        when(sns.publish(any(PublishRequest.class))).thenThrow(SnsException.builder().message("AccessDenied for " + TOPIC).build());
        try (SnsNotifier notifier = new SnsNotifier(sns, TOPIC)) {
            assertThatCode(() -> notifier.downloaded(link(null), 1)).doesNotThrowAnyException();
            verify(sns, timeout(3000)).publish(any(PublishRequest.class));
            // the worker survived the failure and still works
            assertThatCode(() -> notifier.downloaded(link(null), 2)).doesNotThrowAnyException();
            verify(sns, timeout(3000).times(2)).publish(any(PublishRequest.class));
        }
    }

    @Test
    void neverThrowsOnABadLink() {
        SnsClient sns = mock(SnsClient.class);
        try (SnsNotifier notifier = new SnsNotifier(sns, TOPIC)) {
            assertThatCode(() -> notifier.downloaded(null, 1)).doesNotThrowAnyException();
        }
    }

    @Test
    void doesNotBlockTheCallerWhenSnsIsSlowAndDropsWhenTheQueueIsFull() throws Exception {
        SnsClient sns = mock(SnsClient.class);
        CountDownLatch release = new CountDownLatch(1);
        when(sns.publish(any(PublishRequest.class))).thenAnswer(inv -> {
            release.await(30, TimeUnit.SECONDS);
            return PublishResponse.builder().build();
        });
        SnsNotifier notifier = new SnsNotifier(sns, TOPIC);
        try {
            long start = System.nanoTime();
            for (int i = 0; i < SnsNotifier.QUEUE_CAPACITY + 50; i++) {   // more than the queue holds: the rest is dropped
                notifier.downloaded(link(3), i);
            }
            Duration took = Duration.ofNanos(System.nanoTime() - start);
            assertThat(took).isLessThan(Duration.ofSeconds(2));
            verify(sns, timeout(3000).atLeastOnce()).publish(any(PublishRequest.class));
        } finally {
            release.countDown();
            notifier.close();
        }
    }

    @Test
    void workerThreadIsADaemon() throws Exception {
        SnsClient sns = mock(SnsClient.class);
        java.util.concurrent.atomic.AtomicReference<Thread> thread = new java.util.concurrent.atomic.AtomicReference<>();
        when(sns.publish(any(PublishRequest.class))).thenAnswer(inv -> {
            thread.set(Thread.currentThread());
            return PublishResponse.builder().build();
        });
        try (SnsNotifier notifier = new SnsNotifier(sns, TOPIC)) {
            notifier.downloaded(link(1), 1);
            verify(sns, timeout(3000)).publish(any(PublishRequest.class));
            assertThat(thread.get().isDaemon()).isTrue();
        }
    }
}
