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

/**
 * Configuration for the embedded REST API server.
 *
 * <p>All settings are read from environment variables (with lowercased,
 * dot-separated system-property fallbacks) so the service is easy to configure in
 * Kubernetes without touching Haveno's own command-line option parser.
 */
public class OrderbookConfig {

    public static final String VERSION = "1.0.0";

    /** Interface the REST server binds to. Default 0.0.0.0 (all interfaces) for containers. */
    public final String restHost;
    /** TCP port the REST server listens on. */
    public final int restPort;
    /** When true, offers expose the maker's onion address; redacted by default for privacy. */
    public final boolean exposeMakerAddress;
    /** Hard cap on the number of trade-statistics rows returned by a single /trades response. */
    public final int maxTradesPerResponse;
    /** Number of worker threads for the HTTP server. */
    public final int httpThreads;

    private OrderbookConfig(String restHost, int restPort, boolean exposeMakerAddress,
                            int maxTradesPerResponse, int httpThreads) {
        this.restHost = restHost;
        this.restPort = restPort;
        this.exposeMakerAddress = exposeMakerAddress;
        this.maxTradesPerResponse = maxTradesPerResponse;
        this.httpThreads = httpThreads;
    }

    public static OrderbookConfig fromEnv() {
        return new OrderbookConfig(
                env("HAVENO_REST_HOST", "0.0.0.0"),
                Integer.parseInt(env("HAVENO_REST_PORT", "8080")),
                Boolean.parseBoolean(env("HAVENO_EXPOSE_MAKER_ADDRESS", "false")),
                Integer.parseInt(env("HAVENO_MAX_TRADES", "5000")),
                Integer.parseInt(env("HAVENO_HTTP_THREADS", "8")));
    }

    private static String env(String key, String defaultValue) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            value = System.getProperty(key.toLowerCase().replace('_', '.'));
        }
        return (value == null || value.isBlank()) ? defaultValue : value;
    }
}
