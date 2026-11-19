package com.microservices.order.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A tiny HTTP server (the JDK one) that stands in for another service in a test.
 * It listens on a random local port, never on 8081 to 8085.
 *
 * A test registers a handler per "METHOD /path". Every request is recorded, so a test can check what was called,
 * with which headers, and in which order. A handler can answer normally, answer late (past the client's time-out),
 * or cut the connection without any answer. The server can also be stopped and started again on the same port,
 * which looks to the caller like a service that is down.
 */
public final class FakeHttpServer implements AutoCloseable {

    /** One request that reached the server. */
    public record Request(String method, String path, String query, Map<String, String> headers, String body) {

        /** Header value by name, without regard to upper or lower case. */
        public String header(String name) {
            return headers.get(name);
        }
    }

    /** What a handler answers. */
    public record Reply(int status, String body, long delayMillis, boolean dropConnection) {

        public static Reply json(int status, String body) {
            return new Reply(status, body, 0, false);
        }

        public static Reply ok(String body) {
            return json(200, body);
        }

        public static Reply noContent() {
            return new Reply(204, null, 0, false);
        }

        /** The connection is closed without any answer (like a network failure). */
        public static Reply drop() {
            return new Reply(0, null, 0, true);
        }

        /** The same answer, but sent only after a pause (for a time-out test). */
        public Reply after(long millis) {
            return new Reply(status, body, millis, dropConnection);
        }
    }

    @FunctionalInterface
    public interface Handler {
        Reply handle(Request request) throws Exception;
    }

    private final Map<String, Handler> handlers = new ConcurrentHashMap<>();
    private final Map<String, Handler> prefixHandlers = new ConcurrentHashMap<>();
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "fake-http-server");
        thread.setDaemon(true);
        return thread;
    });

    private HttpServer server;
    private int port;
    private boolean stopped;

    public static FakeHttpServer start() {
        FakeHttpServer fake = new FakeHttpServer();
        fake.bind(0);
        return fake;
    }

    private void bind(int wantedPort) {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), wantedPort), 50);
        } catch (IOException e) {
            throw new IllegalStateException("The fake server could not start", e);
        }
        server.setExecutor(executor);
        server.createContext("/", this::dispatch);
        server.start();
        port = server.getAddress().getPort();
        stopped = false;
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    /** Registers the answer for one method and one exact path (without the query). */
    public void on(String method, String path, Handler handler) {
        handlers.put(method + " " + path, handler);
    }

    /** Registers the answer for one method and every path that starts with the prefix (an exact match wins). */
    public void onPrefix(String method, String pathPrefix, Handler handler) {
        prefixHandlers.put(method + " " + pathPrefix, handler);
    }

    public List<Request> requests() {
        return List.copyOf(requests);
    }

    /** The requests with this method and path, in the order they arrived. */
    public List<Request> requests(String method, String path) {
        return requests.stream().filter(r -> r.method().equals(method) && r.path().equals(path)).toList();
    }

    /** Forgets the recorded requests (the handlers stay). */
    public void clearRequests() {
        requests.clear();
    }

    /** Forgets handlers and requests. */
    public void reset() {
        handlers.clear();
        prefixHandlers.clear();
        requests.clear();
    }

    /** Stops listening: callers now get "connection refused". */
    public synchronized void stop() {
        server.stop(0);
        stopped = true;
    }

    /** Listens again on the same port after {@link #stop()}. */
    public void restart() {
        bind(port);
    }

    /** Listens again on the same port if the server was stopped; does nothing if it is running. */
    public synchronized void restartIfStopped() {
        if (stopped) {
            bind(port);
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private Handler findHandler(Request request) {
        Handler exact = handlers.get(request.method() + " " + request.path());
        if (exact != null) {
            return exact;
        }
        String wanted = request.method() + " " + request.path();
        return prefixHandlers.entrySet().stream()
                .filter(entry -> wanted.startsWith(entry.getKey()))
                .findFirst()
                .map(Map.Entry::getValue)
                .orElse(null);
    }

    private void dispatch(HttpExchange exchange) throws IOException {
        try {
            Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            exchange.getRequestHeaders().forEach((name, values) -> headers.put(name, values.get(0)));
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Request request = new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestURI().getRawQuery(), headers, body);
            requests.add(request);

            Handler handler = findHandler(request);
            Reply reply;
            try {
                reply = handler == null
                        ? Reply.json(404, "{\"title\":\"Not Found\",\"status\":404,\"detail\":\"No fake handler\"}")
                        : handler.handle(request);
            } catch (Exception e) {
                reply = Reply.json(500, "{\"title\":\"Internal Server Error\",\"status\":500}");
            }

            if (reply.delayMillis() > 0) {
                Thread.sleep(reply.delayMillis());
            }
            if (reply.dropConnection()) {
                exchange.close();
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            if (reply.body() == null) {
                exchange.sendResponseHeaders(reply.status(), -1);
            } else {
                byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(reply.status(), bytes.length);
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            // The caller gave up (time-out). Nothing to do.
        }
    }
}
