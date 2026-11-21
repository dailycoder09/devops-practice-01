package com.droplink.model;

/** A link is PENDING from the moment it is made until the browser has finished uploading the file, then ACTIVE. */
public enum LinkStatus {
    PENDING,
    ACTIVE
}
