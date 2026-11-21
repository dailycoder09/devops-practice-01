package com.droplink.store;

import com.droplink.model.Link;

/**
 * Tells somebody that a file was downloaded (an SNS topic in aws mode, the log in local mode).
 * Best effort: an implementation never throws and never makes the download wait.
 */
public interface Notifier {

    Notifier NONE = (link, downloadsAfter) -> { };

    void downloaded(Link link, long downloadsAfter);
}
