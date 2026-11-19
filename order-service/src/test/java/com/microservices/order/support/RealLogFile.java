package com.microservices.order.support;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * The JSON log file that the running test application really writes (LOG_PATH/order-service.json.log; the pom
 * points LOG_PATH at target/test-logs). The file is written by an async appender, so tests wait for a line.
 * Only the part written after a remembered offset is read, so older lines of the same run do not matter.
 */
public final class RealLogFile {

    private RealLogFile() {
    }

    public static Path path() {
        return Path.of(System.getProperty("LOG_PATH", "logs"), "order-service.json.log");
    }

    /** The current size of the file (0 if it does not exist yet): the offset to read from later. */
    public static long size() {
        try {
            return Files.exists(path()) ? Files.size(path()) : 0;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The complete lines written after the offset (a line that is still being written is left out). */
    public static List<String> linesSince(long offset) {
        if (!Files.exists(path())) {
            return List.of();
        }
        try (RandomAccessFile file = new RandomAccessFile(path().toFile(), "r")) {
            long length = file.length();
            if (length <= offset) {
                return List.of();
            }
            byte[] bytes = new byte[(int) (length - offset)];
            file.seek(offset);
            file.readFully(bytes);
            String text = new String(bytes, StandardCharsets.UTF_8);
            List<String> lines = new ArrayList<>();
            int start = 0;
            int newline;
            while ((newline = text.indexOf('\n', start)) >= 0) {
                String line = text.substring(start, newline);
                if (!line.isBlank()) {
                    lines.add(line);
                }
                start = newline + 1;
            }
            return lines;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Waits until the lines after the offset satisfy the condition; returns them (fails after the time-out). */
    public static List<String> await(long offset, Predicate<List<String>> condition, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        List<String> lines = linesSince(offset);
        while (!condition.test(lines)) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("The log file did not get the expected lines in " + timeout
                        + ". It has " + lines.size() + " new line(s).");
            }
            Thread.sleep(50);
            lines = linesSince(offset);
        }
        return lines;
    }
}
