/*
 * This file is part of Haveno.
 *
 * Haveno is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Haveno is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Haveno. If not, see <http://www.gnu.org/licenses/>.
 */

package haveno.orderbook;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Read-only REST/JSON API over {@link OrderbookAggregator}, built on the JDK's bundled
 * {@code com.sun.net.httpserver.HttpServer} so it adds no third-party dependency.
 *
 * <p>All routes are GET and prefixed with {@code /api/v1}. Responses are JSON except
 * {@code /api/v1/openapi.yaml}. The OpenAPI document is the authoritative machine-readable
 * contract for consumers.
 */
@Slf4j
public class RestApiServer {

    private static final String PREFIX = "/api/v1";
    private final OrderbookAggregator aggregator;
    private final OrderbookConfig config;
    private final Gson gson = new GsonBuilder().serializeNulls().create();
    private final Gson prettyGson = new GsonBuilder().serializeNulls().setPrettyPrinting().create();
    private HttpServer server;

    public RestApiServer(OrderbookAggregator aggregator, OrderbookConfig config) {
        this.aggregator = aggregator;
        this.config = config;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(config.restHost, config.restPort), 0);
        server.setExecutor(Executors.newFixedThreadPool(config.httpThreads));
        server.createContext("/", new RootHandler());
        server.start();
        log.info("Orderbook REST API listening on http://{}:{}{}", config.restHost, config.restPort, PREFIX);
    }

    public void stop() {
        if (server != null) server.stop(0);
    }

    private class RootHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            try {
                if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
                    writeError(ex, 405, "Only GET is supported");
                    return;
                }
                URI uri = ex.getRequestURI();
                String path = uri.getPath();
                Map<String, String> q = parseQuery(uri.getRawQuery());
                boolean pretty = "true".equalsIgnoreCase(q.getOrDefault("pretty", "false"));

                if (path.equals("/") || path.equals(PREFIX) || path.equals(PREFIX + "/")) {
                    writeJson(ex, 200, index(), pretty);
                } else if (path.equals(PREFIX + "/health")) {
                    writeJson(ex, 200, aggregator.health(), pretty);
                } else if (path.equals(PREFIX + "/markets") || path.equals(PREFIX + "/ticker")) {
                    writeJson(ex, 200, aggregator.markets(), pretty);
                } else if (path.equals(PREFIX + "/prices")) {
                    writeJson(ex, 200, aggregator.prices(), pretty);
                } else if (path.equals(PREFIX + "/offers")) {
                    writeJson(ex, 200, aggregator.offers(q.get("market"), q.get("direction")), pretty);
                } else if (path.startsWith(PREFIX + "/orderbook/")) {
                    String market = decode(path.substring((PREFIX + "/orderbook/").length()));
                    if (market.isEmpty()) { writeError(ex, 400, "Missing market"); return; }
                    int depth = parseInt(q.get("depth"), 0);
                    writeJson(ex, 200, aggregator.orderbook(market, depth), pretty);
                } else if (path.startsWith(PREFIX + "/trades/")) {
                    String market = decode(path.substring((PREFIX + "/trades/").length()));
                    if (market.isEmpty()) { writeError(ex, 400, "Missing market"); return; }
                    int limit = parseInt(q.get("limit"), 0);
                    long since = parseLong(q.get("since"), 0);
                    long until = parseLong(q.get("until"), 0);
                    writeJson(ex, 200, aggregator.trades(market, limit, since, until), pretty);
                } else if (path.equals(PREFIX + "/openapi.yaml")) {
                    writeResource(ex, "openapi.yaml", "application/yaml");
                } else {
                    writeError(ex, 404, "Not found: " + path);
                }
            } catch (IllegalArgumentException iae) {
                writeError(ex, 400, iae.getMessage());
            } catch (Throwable t) {
                log.warn("Error handling {}", ex.getRequestURI(), t);
                writeError(ex, 500, "Internal error: " + t.getMessage());
            } finally {
                ex.close();
            }
        }
    }

    private Map<String, Object> index() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("service", "haveno-orderbook");
        m.put("version", OrderbookConfig.VERSION);
        Map<String, String> endpoints = new LinkedHashMap<>();
        endpoints.put("health", PREFIX + "/health");
        endpoints.put("markets", PREFIX + "/markets");
        endpoints.put("ticker", PREFIX + "/ticker");
        endpoints.put("prices", PREFIX + "/prices");
        endpoints.put("offers", PREFIX + "/offers?market={code}&direction={BUY|SELL}");
        endpoints.put("orderbook", PREFIX + "/orderbook/{code}?depth={n}");
        endpoints.put("trades", PREFIX + "/trades/{code}?limit={n}&since={epochMs}&until={epochMs}");
        endpoints.put("openapi", PREFIX + "/openapi.yaml");
        m.put("endpoints", endpoints);
        return m;
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // I/O helpers
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void writeJson(HttpExchange ex, int status, Object body, boolean pretty) throws IOException {
        byte[] bytes = (pretty ? prettyGson : gson).toJson(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void writeError(HttpExchange ex, int status, String message) throws IOException {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("error", message);
        err.put("status", status);
        writeJson(ex, status, err, false);
    }

    private void writeResource(HttpExchange ex, String name, String contentType) throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(name)) {
            if (in == null) { writeError(ex, 404, "Resource not found: " + name); return; }
            byte[] bytes = in.readAllBytes();
            ex.getResponseHeaders().set("Content-Type", contentType + "; charset=utf-8");
            ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> map = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) return map;
        for (String pair : rawQuery.split("&")) {
            int i = pair.indexOf('=');
            if (i < 0) map.put(decode(pair), "");
            else map.put(decode(pair.substring(0, i)), decode(pair.substring(i + 1)));
        }
        return map;
    }

    private static String decode(String s) {
        return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    private static int parseInt(String s, int def) {
        try { return s == null ? def : Integer.parseInt(s); } catch (NumberFormatException e) { return def; }
    }

    private static long parseLong(String s, long def) {
        try { return s == null ? def : Long.parseLong(s); } catch (NumberFormatException e) { return def; }
    }
}
