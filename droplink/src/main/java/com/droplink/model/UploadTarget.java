package com.droplink.model;

import java.net.URI;
import java.util.Map;

/**
 * Where and how the browser must upload the file: an HTTP PUT of the file bytes to {@code url}, with exactly these
 * headers. In aws mode the url is a signed S3 address; in local mode it is an address of this app.
 */
public record UploadTarget(String method, URI url, Map<String, String> headers) {
}
