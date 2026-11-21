package com.droplink.model;

/** What the store knows about an uploaded file. */
public record StoredFile(long size, String contentType) {
}
