package dev.sebi.sebstats;

import com.google.gson.GsonBuilder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

final class WebServer {
    private final HttpServer server;

    WebServer(String bind, int port, Supplier<String> stats) throws IOException {
        byte[] page;
        try (InputStream in = WebServer.class.getResourceAsStream("/web/index.html")) {
            page = in.readAllBytes();
        }

        server = HttpServer.create(new InetSocketAddress(bind, port), 0);
        server.createContext("/api", ex -> {
            Api.Result r = Api.handle(ex.getRequestURI().getPath(), query(ex.getRequestURI().getRawQuery()), stats.get());
            var gson = query(ex.getRequestURI().getRawQuery()).containsKey("pretty")
                    ? new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create()
                    : new GsonBuilder().disableHtmlEscaping().create();
            ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            send(ex, r.status(), "application/json; charset=utf-8",
                    gson.toJson(r.body()).getBytes(StandardCharsets.UTF_8));
        });
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            if (path.equals("/") || path.equals("/index.html")) {
                send(ex, "text/html; charset=utf-8", page);
            } else {
                ex.sendResponseHeaders(404, -1);
                ex.close();
            }
        });
        server.setExecutor(Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "SebStats-web");
            t.setDaemon(true);
            return t;
        }));
        server.start();
    }

    int port() {
        return server.getAddress().getPort();
    }

    void stop() {
        server.stop(0);
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> out = new HashMap<>();
        if (raw == null) return out;
        for (String pair : raw.split("&")) {
            String[] kv = pair.split("=", 2);
            out.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                    kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
        }
        return out;
    }

    private static void send(HttpExchange ex, String type, byte[] body) throws IOException {
        send(ex, 200, type, body);
    }

    private static void send(HttpExchange ex, int status, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, body.length);
        try (var out = ex.getResponseBody()) {
            out.write(body);
        }
    }
}
