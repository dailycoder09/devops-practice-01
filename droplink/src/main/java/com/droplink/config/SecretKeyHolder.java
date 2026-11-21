package com.droplink.config;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * The key that signs the unlock cookie (and, in local mode, the upload and download addresses). It comes from
 * DROPLINK_SECRET. When it is empty (allowed in local mode only; aws mode refuses to start without it) a random key
 * is made at start, so cookies stop working after a restart, which is fine on a laptop.
 */
@Component
public class SecretKeyHolder {

    private final byte[] key;

    public SecretKeyHolder(DropLinkProperties properties) {
        String secret = properties.getSecret();
        if (secret == null || secret.isBlank()) {
            key = new byte[32];
            new SecureRandom().nextBytes(key);
        } else {
            key = secret.getBytes(StandardCharsets.UTF_8);
        }
    }

    public byte[] key() {
        return key.clone();
    }
}
