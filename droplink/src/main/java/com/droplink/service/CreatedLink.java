package com.droplink.service;

import com.droplink.model.Link;
import com.droplink.model.UploadTarget;

/** A new link, the secret that manages it (shown once, only its hash is kept) and where to upload the file. */
public record CreatedLink(Link link, String manageToken, UploadTarget upload) {
}
