package com.droplink.store.aws;

import com.droplink.model.Link;
import com.droplink.store.Notifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;

import java.time.Instant;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Publishes "file downloaded" messages to an SNS topic. Best effort: it never throws and never makes the caller wait.
 * One daemon worker thread sends the messages from a queue of 100; when the queue is full the message is dropped.
 *
 * The message never contains the link id (it is the secret of the link), the storage key or any hash.
 */
public class SnsNotifier implements Notifier, AutoCloseable {

    static final String SUBJECT = "DropLink: file downloaded";
    static final int QUEUE_CAPACITY = 100;
    private static final int MAX_FILE_NAME_IN_MESSAGE = 200;

    private static final Logger log = LoggerFactory.getLogger(SnsNotifier.class);

    private final SnsClient sns;
    private final String topicArn;
    private final ThreadPoolExecutor worker;

    public SnsNotifier(SnsClient sns, String topicArn) {
        this.sns = sns;
        this.topicArn = topicArn;
        this.worker = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                runnable -> {
                    Thread thread = new Thread(runnable, "droplink-sns");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Override
    public void downloaded(Link link, long downloadsAfter) {
        try {
            // Everything that is needed is copied now; the worker never touches the Link again.
            String message = message(link.fileName(), downloadsAfter, link.maxDownloads(), Instant.now());
            worker.execute(() -> publish(message));
        } catch (RejectedExecutionException e) {
            log.warn("SNS notification dropped: queue full");
        } catch (RuntimeException e) {
            log.warn("SNS notification failed: {}", e.getClass().getSimpleName());
        }
    }

    private void publish(String message) {
        try {
            sns.publish(PublishRequest.builder()
                    .topicArn(topicArn)
                    .subject(SUBJECT)
                    .message(message)
                    .build());
        } catch (Throwable e) {   // never let the worker thread die or the error reach the download
            log.warn("SNS notification failed: {}", e.getClass().getSimpleName());
        }
    }

    static String message(String fileName, long downloadsAfter, Integer maxDownloads, Instant at) {
        String left = maxDownloads == null ? "no limit" : String.valueOf(Math.max(0L, maxDownloads - downloadsAfter));
        return "A file was downloaded.\n"
                + "File: " + printable(fileName) + "\n"
                + "Downloads so far: " + downloadsAfter + "\n"
                + "Downloads left: " + left + "\n"
                + "Time: " + at;
    }

    private static String printable(String fileName) {
        if (fileName == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fileName.length() && sb.length() < MAX_FILE_NAME_IN_MESSAGE; i++) {
            char c = fileName.charAt(i);
            sb.append(c < 0x20 || c == 0x7f ? ' ' : c);
        }
        return sb.toString();
    }

    /** Stops the worker (messages still in the queue are dropped). Called by Spring when the app stops. */
    @Override
    public void close() {
        worker.shutdownNow();
    }
}
