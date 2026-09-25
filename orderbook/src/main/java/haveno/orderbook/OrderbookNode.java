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

import com.google.inject.Injector;
import haveno.common.config.Config;
import haveno.core.app.misc.AppSetup;
import haveno.core.app.misc.AppSetupWithP2P;
import haveno.core.offer.OfferBookService;
import haveno.core.provider.price.PriceFeedService;
import haveno.core.trade.statistics.TradeStatisticsManager;
import haveno.network.p2p.BootstrapListener;
import haveno.network.p2p.P2PService;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

/**
 * A wallet-less, fund-less P2P observer node (modeled on {@code haveno.statistics.Statistics})
 * that ingests the entire distributed offer book and trade-statistics store, and serves a derived
 * orderbook over an embedded REST API. It never registers to trade and never starts a wallet.
 */
@Slf4j
public class OrderbookNode {
    @Setter
    private Injector injector;

    // pinned to avoid GC
    private OfferBookService offerBookService;
    private PriceFeedService priceFeedService;
    private TradeStatisticsManager tradeStatisticsManager;
    private P2PService p2pService;
    private AppSetup appSetup;
    private RestApiServer restApiServer;

    public void startApplication() {
        p2pService = injector.getInstance(P2PService.class);
        offerBookService = injector.getInstance(OfferBookService.class);
        priceFeedService = injector.getInstance(PriceFeedService.class);
        tradeStatisticsManager = injector.getInstance(TradeStatisticsManager.class);
        Config havenoConfig = injector.getInstance(Config.class);

        // We need the price feed to resolve market-based (margin) offers into executable prices.
        priceFeedService.setCurrencyCode("USD");
        p2pService.addP2PServiceListener(new BootstrapListener() {
            @Override
            public void onDataReceived() {
                log.info("onDataReceived: start requesting prices");
                priceFeedService.startRequestingPrices(
                        price -> log.debug("requestPriceFeed. price={}", price),
                        (errorMessage, throwable) -> log.warn("Exception at requestPriceFeed: {}",
                                throwable == null ? errorMessage : throwable.getMessage()));
                tradeStatisticsManager.onAllServicesInitialized();
            }
        });

        // Start the REST API immediately so /health is reachable while the node is still bootstrapping.
        OrderbookConfig obConfig = OrderbookConfig.fromEnv();
        OrderbookAggregator aggregator = new OrderbookAggregator(
                offerBookService, priceFeedService, tradeStatisticsManager, p2pService,
                obConfig, havenoConfig.baseCurrencyNetwork.name());
        restApiServer = new RestApiServer(aggregator, obConfig);
        try {
            restApiServer.start();
            Runtime.getRuntime().addShutdownHook(new Thread(restApiServer::stop, "rest-api-shutdown"));
        } catch (Exception e) {
            throw new RuntimeException("Failed to start REST API server on port " + obConfig.restPort, e);
        }

        appSetup = injector.getInstance(AppSetupWithP2P.class);
        appSetup.start();
    }
}
