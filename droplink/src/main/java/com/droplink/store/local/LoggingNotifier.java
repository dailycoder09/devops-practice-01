package com.droplink.store.local;

import com.droplink.model.Link;
import com.droplink.store.Notifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Local mode: the notification is a line in the log (never the link id, which is the secret). */
public class LoggingNotifier implements Notifier {

    private static final Logger log = LoggerFactory.getLogger(LoggingNotifier.class);

    @Override
    public void downloaded(Link link, long downloadsAfter) {
        log.info("NOTIFY: '{}' was downloaded ({} so far)", link.fileName(), downloadsAfter);
    }
}
