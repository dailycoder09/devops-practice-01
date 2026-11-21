package com.droplink.web;

import com.droplink.config.DropLinkProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** The address people use to reach the app: DROPLINK_PUBLIC_URL when set, otherwise the address of the current request. */
@Component
public class PublicUrls {

    private final DropLinkProperties properties;

    public PublicUrls(DropLinkProperties properties) {
        this.properties = properties;
    }

    public String base(HttpServletRequest request) {
        String configured = properties.getPublicUrl();
        if (configured != null && !configured.isBlank()) {
            return configured.replaceAll("/+$", "");
        }
        return ServletUriComponentsBuilder.fromContextPath(request).build().toUriString();
    }

    public String shareUrl(HttpServletRequest request, String id) {
        return base(request) + "/d/" + id;
    }
}
