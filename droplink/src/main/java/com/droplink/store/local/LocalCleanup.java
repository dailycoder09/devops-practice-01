package com.droplink.store.local;

import com.droplink.model.Link;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;

/** Local mode: every 5 minutes, delete the files and the links that have expired. (In aws mode the S3 rule and the DynamoDB time-to-live do this.) */
@Component
@ConditionalOnProperty(name = "droplink.mode", havingValue = "local", matchIfMissing = true)
public class LocalCleanup {

    private static final Logger log = LoggerFactory.getLogger(LocalCleanup.class);

    private final InMemoryLinkRepository repository;
    private final LocalFileStore files;
    private final Clock clock;

    public LocalCleanup(InMemoryLinkRepository repository, LocalFileStore files, Clock clock) {
        this.repository = repository;
        this.files = files;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000)
    public void sweep() {
        List<Link> expired = repository.removeExpired(clock.instant());
        for (Link link : expired) {
            files.delete(link.key());
        }
        if (!expired.isEmpty()) {
            log.info("Cleanup: {} expired link(s) removed", expired.size());
        }
    }
}
