package com.droplink.service;

import com.droplink.model.DownloadEvent;
import com.droplink.model.Link;

import java.util.List;

/** What the creator sees on the manage page. */
public record Activity(Link link, List<DownloadEvent> events) {
}
