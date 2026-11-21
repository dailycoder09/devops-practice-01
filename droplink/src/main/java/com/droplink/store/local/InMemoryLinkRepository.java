package com.droplink.store.local;

import com.droplink.model.DownloadEvent;
import com.droplink.model.Link;
import com.droplink.model.LinkStatus;
import com.droplink.store.LinkRepository;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Links in memory (local mode). Gone when the app stops. Every change is one atomic map operation. */
public class InMemoryLinkRepository implements LinkRepository {

    private final ConcurrentHashMap<String, Link> links = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Deque<DownloadEvent>> events = new ConcurrentHashMap<>();

    @Override
    public void create(Link link) {
        if (links.putIfAbsent(link.id(), link) != null) {
            throw new IllegalStateException("A link with this id already exists");
        }
    }

    @Override
    public Optional<Link> find(String id) {
        return Optional.ofNullable(links.get(id));
    }

    @Override
    public boolean activate(String id, long size, String contentType) {
        boolean[] changed = {false};
        links.computeIfPresent(id, (key, link) -> {
            if (link.status() != LinkStatus.PENDING) {
                return link;
            }
            changed[0] = true;
            return new Link(link.id(), link.fileName(), contentType, size, link.key(), link.createdAt(), link.expiresAt(),
                    link.maxDownloads(), link.downloads(), link.passwordHash(), link.manageTokenHash(), LinkStatus.ACTIVE);
        });
        return changed[0];
    }

    @Override
    public Optional<Long> tryCountDownload(String id, Instant now) {
        long[] count = {-1};
        links.computeIfPresent(id, (key, link) -> {
            if (link.status() != LinkStatus.ACTIVE || link.isExpired(now) || !link.hasDownloadsLeft()) {
                return link;
            }
            count[0] = link.downloads() + 1;
            return new Link(link.id(), link.fileName(), link.contentType(), link.size(), link.key(), link.createdAt(),
                    link.expiresAt(), link.maxDownloads(), count[0], link.passwordHash(), link.manageTokenHash(), link.status());
        });
        return count[0] < 0 ? Optional.empty() : Optional.of(count[0]);
    }

    @Override
    public void addEvent(String id, DownloadEvent event) {
        if (!links.containsKey(id)) {
            return;
        }
        Deque<DownloadEvent> list = events.computeIfAbsent(id, key -> new ArrayDeque<>());
        synchronized (list) {
            list.addFirst(event);
        }
    }

    @Override
    public List<DownloadEvent> events(String id, int limit) {
        Deque<DownloadEvent> list = events.get(id);
        if (list == null) {
            return List.of();
        }
        synchronized (list) {
            List<DownloadEvent> copy = new ArrayList<>();
            for (DownloadEvent event : list) {
                if (copy.size() >= limit) {
                    break;
                }
                copy.add(event);
            }
            return copy;
        }
    }

    @Override
    public boolean delete(String id) {
        events.remove(id);
        return links.remove(id) != null;
    }

    /** Local mode only: removes every link that has expired and returns them, so their files can be deleted too. */
    public List<Link> removeExpired(Instant now) {
        List<Link> removed = new ArrayList<>();
        for (Link link : List.copyOf(links.values())) {
            if (link.isExpired(now) && links.remove(link.id(), link)) {
                events.remove(link.id());
                removed.add(link);
            }
        }
        return removed;
    }
}
