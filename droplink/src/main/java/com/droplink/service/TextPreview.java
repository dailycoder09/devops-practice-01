package com.droplink.service;

/** The beginning of a text file, and whether there was more. */
public record TextPreview(String text, boolean truncated) {
}
