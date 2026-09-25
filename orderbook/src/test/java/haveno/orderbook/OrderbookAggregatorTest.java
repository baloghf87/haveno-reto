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

import haveno.core.monetary.Price;
import haveno.core.offer.Offer;
import haveno.core.offer.OfferBookService;
import haveno.core.offer.OfferDirection;
import haveno.core.provider.price.MarketPrice;
import haveno.core.provider.price.PriceFeedService;
import haveno.core.trade.statistics.TradeStatistics3;
import haveno.core.trade.statistics.TradeStatisticsManager;
import haveno.network.p2p.P2PService;
import haveno.orderbook.model.Dto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class OrderbookAggregatorTest {

    private static final long XMR = 1_000_000_000_000L; // 1 XMR in atomic units

    private OfferBookService offerBookService;
    private PriceFeedService priceFeedService;
    private TradeStatisticsManager tradeStatisticsManager;
    private P2PService p2PService;
    private OrderbookAggregator aggregator;

    @BeforeEach
    void setUp() {
        offerBookService = mock(OfferBookService.class);
        priceFeedService = mock(PriceFeedService.class);
        tradeStatisticsManager = mock(TradeStatisticsManager.class);
        p2PService = mock(P2PService.class);

        when(tradeStatisticsManager.getTradeStatisticsListCopy()).thenReturn(new ArrayList<>());
        when(priceFeedService.getMarketPrice("EUR"))
                .thenReturn(new MarketPrice("EUR", 151.0, System.currentTimeMillis(), true));

        aggregator = new OrderbookAggregator(offerBookService, priceFeedService,
                tradeStatisticsManager, p2PService, OrderbookConfig.fromEnv(), "XMR_LOCAL");
    }

    private Offer offer(String code, OfferDirection dir, double price, long amountXmr) {
        Offer o = mock(Offer.class);
        lenient().when(o.getCounterCurrencyCode()).thenReturn(code);
        lenient().when(o.getDirection()).thenReturn(dir);
        lenient().when(o.getAmount()).thenReturn(BigInteger.valueOf(amountXmr * XMR));
        lenient().when(o.getMinAmount()).thenReturn(BigInteger.valueOf(amountXmr * XMR));
        lenient().when(o.getId()).thenReturn("offer-" + code + "-" + dir + "-" + price);
        lenient().when(o.getOwnerNodeAddress()).thenReturn(null);
        Price p = mock(Price.class);
        lenient().when(p.getDoubleValue()).thenReturn(price);
        lenient().when(o.getPrice()).thenReturn(p);
        return o;
    }

    @Test
    void orderbookBuildsSortedCumulativeDepth() {
        List<Offer> offers = List.of(
                offer("EUR", OfferDirection.BUY, 150.0, 2),
                offer("EUR", OfferDirection.BUY, 149.0, 1),
                offer("EUR", OfferDirection.SELL, 152.0, 1),
                offer("EUR", OfferDirection.SELL, 151.0, 3));
        when(offerBookService.getOffers()).thenReturn(offers);

        Dto.Orderbook ob = aggregator.orderbook("eur", 0);

        assertEquals("XMR/EUR", ob.market);
        assertEquals("fiat", ob.currencyType);
        assertEquals(151.0, ob.indexPrice);

        // Bids: highest price first, cumulative accumulates.
        assertEquals(2, ob.bids.size());
        assertEquals(150.0, ob.bids.get(0).price);
        assertEquals(2.0, ob.bids.get(0).amountXmr);
        assertEquals(2.0, ob.bids.get(0).cumulativeXmr);
        assertEquals(149.0, ob.bids.get(1).price);
        assertEquals(3.0, ob.bids.get(1).cumulativeXmr);

        // Asks: lowest price first.
        assertEquals(2, ob.asks.size());
        assertEquals(151.0, ob.asks.get(0).price);
        assertEquals(3.0, ob.asks.get(0).amountXmr);
        assertEquals(3.0, ob.asks.get(0).cumulativeXmr);
        assertEquals(152.0, ob.asks.get(1).price);
        assertEquals(4.0, ob.asks.get(1).cumulativeXmr);

        assertEquals(0, ob.unpricedOfferCount);
    }

    @Test
    void samePriceOffersAggregateIntoOneLevel() {
        List<Offer> offers = List.of(
                offer("EUR", OfferDirection.BUY, 150.0, 2),
                offer("EUR", OfferDirection.BUY, 150.0, 3));
        when(offerBookService.getOffers()).thenReturn(offers);

        Dto.Orderbook ob = aggregator.orderbook("EUR", 0);
        assertEquals(1, ob.bids.size());
        assertEquals(5.0, ob.bids.get(0).amountXmr);
        assertEquals(2, ob.bids.get(0).offerCount);
    }

    @Test
    void unpricedMarketBasedOffersAreCountedNotListed() {
        Offer priced = offer("EUR", OfferDirection.BUY, 150.0, 1);
        Offer unpriced = offer("EUR", OfferDirection.BUY, 0, 1);
        when(unpriced.getPrice()).thenReturn(null);
        when(offerBookService.getOffers()).thenReturn(List.of(priced, unpriced));

        Dto.Orderbook ob = aggregator.orderbook("EUR", 0);
        assertEquals(1, ob.bids.size());
        assertEquals(1, ob.unpricedOfferCount);
    }

    @Test
    void depthLimitTruncatesLevels() {
        List<Offer> offers = List.of(
                offer("EUR", OfferDirection.SELL, 151.0, 1),
                offer("EUR", OfferDirection.SELL, 152.0, 1),
                offer("EUR", OfferDirection.SELL, 153.0, 1));
        when(offerBookService.getOffers()).thenReturn(offers);

        Dto.Orderbook ob = aggregator.orderbook("EUR", 2);
        assertEquals(2, ob.asks.size());
        assertEquals(151.0, ob.asks.get(0).price);
        assertEquals(152.0, ob.asks.get(1).price);
    }

    @Test
    void marketsSummaryComputesSpreadLiquidityAndMakers() {
        List<Offer> offers = List.of(
                offer("EUR", OfferDirection.BUY, 150.0, 2),
                offer("EUR", OfferDirection.SELL, 152.0, 3));
        when(offerBookService.getOffers()).thenReturn(offers);

        List<Dto.MarketSummary> markets = aggregator.markets();
        assertEquals(1, markets.size());
        Dto.MarketSummary s = markets.get(0);
        assertEquals("XMR/EUR", s.market);
        assertEquals(1, s.buyOfferCount);
        assertEquals(1, s.sellOfferCount);
        assertEquals(2.0, s.buyLiquidityXmr);
        assertEquals(3.0, s.sellLiquidityXmr);
        assertEquals(150.0, s.bestBid);
        assertEquals(152.0, s.bestAsk);
        assertEquals(2.0, s.spread);
        assertEquals(151.0, s.midPrice);
        assertEquals(2, s.uniqueMakers); // distinct offer ids used as maker fallback
    }

    @Test
    void marketsSummaryComputesSideVwaps() {
        List<Offer> offers = List.of(
                offer("EUR", OfferDirection.BUY, 150.0, 1),
                offer("EUR", OfferDirection.BUY, 140.0, 3),
                offer("EUR", OfferDirection.SELL, 160.0, 2),
                offer("EUR", OfferDirection.SELL, 170.0, 2));
        when(offerBookService.getOffers()).thenReturn(offers);

        Dto.MarketSummary s = aggregator.markets().get(0);
        assertEquals(142.5, s.bidVwap, 1e-9); // (150*1 + 140*3) / 4
        assertEquals(165.0, s.askVwap, 1e-9); // (160*2 + 170*2) / 4
        assertNull(s.open24h); // no trade statistics
    }

    @Test
    void pricesReturnsIndexPricesForActiveMarkets() {
        Offer eur = offer("EUR", OfferDirection.BUY, 150.0, 1);
        when(offerBookService.getOffers()).thenReturn(List.of(eur));

        Map<String, Double> prices = aggregator.prices();
        assertEquals(151.0, prices.get("EUR"));
    }

    @Test
    void emptyBookYieldsEmptyStructures() {
        when(offerBookService.getOffers()).thenReturn(Collections.emptyList());
        assertTrue(aggregator.markets().isEmpty());
        Dto.Orderbook ob = aggregator.orderbook("EUR", 0);
        assertTrue(ob.bids.isEmpty());
        assertTrue(ob.asks.isEmpty());
        assertTrue(ob.timestamp > 0);
    }
}
