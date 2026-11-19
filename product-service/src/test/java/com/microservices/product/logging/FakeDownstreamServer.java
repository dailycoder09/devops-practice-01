package com.microservices.product.logging;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test helper: a tiny real HTTP server on a random local port that plays "the next service".
 * It remembers the headers of every request it receives, so a test can check which trace header
 * the caller sent, and answers every request with a fixed status and JSON body.
 */
public final class FakeDownstreamServer implements AutoCloseable {

    /** What the fake received: method, path (no query), and the request headers with lower-case names. */
    public record Received(String method, String path, Map<String, String> headers) {
    }

    private final HttpServer server;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String body = "{}";

    public FakeDownstreamServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException ex) {
            throw new IllegalStateException("Could not start the fake server", ex);
        }
        server.createContext("/", exchange -> {
            Map<String, String> headers = new TreeMap<>();
            exchange.getRequestHeaders().forEach((name, values) ->
                    headers.put(name.toLowerCase(Locale.ROOT), String.join(",", values)));
            received.add(new Received(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), headers));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void respondWith(int status, String body) {
        this.status = status;
        this.body = body;
    }

    public List<Received> received() {
        return List.copyOf(received);
    }

    public Received last() {
        if (received.isEmpty()) {
            throw new IllegalStateException("The fake server received no request");
        }
        return received.get(received.size() - 1);
    }

    public void clear() {
        received.clear();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
