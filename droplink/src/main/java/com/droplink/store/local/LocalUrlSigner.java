package com.droplink.store.local;

import com.droplink.config.SecretKeyHolder;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/** Signs the little tokens that act as "signed addresses" in local mode: only this app can make them, and they expire. */
public class LocalUrlSigner {

    private final SecretKeyHolder keys;

    public LocalUrlSigner(SecretKeyHolder keys) {
        this.keys = keys;
    }

    /** The fields are joined, encoded and signed. */
    public String sign(List<String> fields) {
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(String.join("\n", fields).getBytes(StandardCharsets.UTF_8));
        return payload + "." + mac(payload);
    }

    /** The fields when the signature is right, else empty. The expiry is checked by the caller. */
    public Optional<List<String>> verify(String token) {
        if (token == null) {
            return Optional.empty();
        }
        int dot = token.indexOf('.');
        if (dot < 1) {
            return Optional.empty();
        }
        String payload = token.substring(0, dot);
        if (!MessageDigest.isEqual(mac(payload).getBytes(StandardCharsets.UTF_8), token.substring(dot + 1).getBytes(StandardCharsets.UTF_8))) {
            return Optional.empty();
        }
        try {
            String text = new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8);
            return Optional.of(List.of(text.split("\n", -1)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private String mac(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(keys.key(), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
