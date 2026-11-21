package com.droplink.service;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/** Random secrets: the id that is the secret part of a link address, and the token that manages a link. */
@Component
public class IdGenerator {

    private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private final SecureRandom random = new SecureRandom();

    /** 12 letters and digits (about 71 bits): too many to guess. */
    public String newId() {
        return random(12);
    }

    /** 32 letters and digits: the key that lets the creator see the activity and delete the link. */
    public String newToken() {
        return random(32);
    }

    private String random(int length) {
        StringBuilder text = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            text.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return text.toString();
    }
}
