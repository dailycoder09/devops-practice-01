package com.droplink.store;

import com.droplink.model.DownloadEvent;
import com.droplink.model.Link;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Where the links and their download events are kept (memory in local mode, DynamoDB in aws mode).
 *
 * Every method must be safe when several app instances call it at the same moment. The two that matter most are
 * {@link #activate} and {@link #tryCountDownload}: each is ONE conditional change in the store, never a read followed
 * by a write.
 */
public interface LinkRepository {

    /** Saves a new link (status PENDING). The id is new and unique. */
    void create(Link link);

    Optional<Link> find(String id);

    /**
     * PENDING to ACTIVE, and records the real size and type of the stored file.
     *
     * @return true when this call made the change; false when the link does not exist or is not PENDING
     */
    boolean activate(String id, long size, String contentType);

    /**
     * Counts one download, but only if the link exists, is ACTIVE, is not expired at {@code now} and still has
     * downloads left. The test and the count are one atomic step, so a one-time link can never be downloaded twice.
     *
     * @return the new number of downloads, or empty when the download was refused
     */
    Optional<Long> tryCountDownload(String id, Instant now);

    /** Adds one event to the activity of a link. A link that no longer exists is ignored. */
    void addEvent(String id, DownloadEvent event);

    /** The newest events first, at most {@code limit}. */
    List<DownloadEvent> events(String id, int limit);

    /**
     * Removes the link and its events.
     *
     * @return true when the link existed
     */
    boolean delete(String id);
}
