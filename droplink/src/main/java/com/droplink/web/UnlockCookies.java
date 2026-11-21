package com.droplink.web;

import com.droplink.config.SecretKeyHolder;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;

/**
 * The cookie that says "this browser typed the right password for this link" for one hour. It holds no password: only
 * the expiry and a signature over the link id and the expiry, made with DROPLINK_SECRET (the same on every instance, so
 * it works behind a load balancer). It is only sent to the pages of that one link.
 */
@Component
public class UnlockCookies {

    private static final Duration VALID = Duration.ofHours(1);

    private final SecretKeyHolder keys;
    private final Clock clock;

    public UnlockCookies(SecretKeyHolder keys, Clock clock) {
        this.keys = keys;
        this.clock = clock;
    }

    static String cookieName(String id) {
        return "dl_u_" + id;
    }

    public void add(HttpServletRequest request, HttpServletResponse response, String id) {
        long expires = clock.instant().plus(VALID).getEpochSecond();
        Cookie cookie = new Cookie(cookieName(id), expires + "." + mac(id, expires));
        cookie.setPath("/d/" + id);
        cookie.setHttpOnly(true);
        cookie.setSecure(request.isSecure());
        cookie.setMaxAge((int) VALID.toSeconds());
        cookie.setAttribute("SameSite", "Lax");
        response.addCookie(cookie);
    }

    public boolean isUnlocked(HttpServletRequest request, String id) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return false;
        }
        for (Cookie cookie : cookies) {
            if (cookieName(id).equals(cookie.getName())) {
                return valid(id, cookie.getValue());
            }
        }
        return false;
    }

    private boolean valid(String id, String value) {
        int dot = value == null ? -1 : value.indexOf('.');
        if (dot < 1) {
            return false;
        }
        try {
            long expires = Long.parseLong(value.substring(0, dot));
            return expires > clock.instant().getEpochSecond()
                    && MessageDigest.isEqual(mac(id, expires).getBytes(StandardCharsets.UTF_8), value.substring(dot + 1).getBytes(StandardCharsets.UTF_8));
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private String mac(String id, long expires) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(keys.key(), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(("unlock|" + id + "|" + expires).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
