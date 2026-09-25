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

import haveno.core.locale.CurrencyUtil;
import haveno.core.monetary.Price;
import haveno.core.monetary.Volume;
import haveno.core.offer.Offer;
import haveno.core.offer.OfferBookService;
import haveno.core.offer.OfferDirection;
import haveno.core.provider.price.MarketPrice;
import haveno.core.provider.price.PriceFeedService;
import haveno.core.trade.statistics.TradeStatistics3;
import haveno.core.trade.statistics.TradeStatisticsManager;
import haveno.network.p2p.P2PService;
import haveno.orderbook.model.Dto;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/**
 * Derives a neutral, network-wide view (orderbook, per-market summaries, tickers and
 * historical trades) from the live P2P data a passive Haveno node already holds.
 *
 * <p>Unlike {@code CoreOffersService.getOffers()}, this reads {@link OfferBookService#getOffers()}
 * unfiltered: no takeable-by-this-node filtering and no own-offer exclusion, so the result is a
 * neutral market view rather than a personalized one.
 */
@Slf4j
public class OrderbookAggregator {

    private static final int XMR_EXPONENT = 12;
    private static final long DAY_MS = TimeUnit.DAYS.toMillis(1);

    private final OfferBookService offerBookService;
    private final PriceFeedService priceFeedService;
    private final TradeStatisticsManager tradeStatisticsManager;
    private final P2PService p2PService;
    private final OrderbookConfig config;
    private final String networkName;
    private final long startTimeMs = System.currentTimeMillis();

    public OrderbookAggregator(OfferBookService offerBookService,
                               PriceFeedService priceFeedService,
                               TradeStatisticsManager tradeStatisticsManager,
                               P2PService p2PService,
                               OrderbookConfig config,
                               String networkName) {
        this.offerBookService = offerBookService;
        this.priceFeedService = priceFeedService;
        this.tradeStatisticsManager = tradeStatisticsManager;
        this.p2PService = p2PService;
        this.config = config;
        this.networkName = networkName;
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Public API surface
    ///////////////////////////////////////////////////////////////////////////////////////////

    public Dto.Health health() {
        Dto.Health h = new Dto.Health();
        boolean bootstrapped = p2PService.isBootstrapped();
        List<Offer> offers = offerBookService.getOffers();
        h.bootstrapped = bootstrapped;
        h.status = bootstrapped ? "UP" : "STARTING";
        h.network = networkName;
        h.numConnections = p2PService.getNetworkNode().getAllConnections().size();
        h.numOffers = offers.size();
        h.numMarkets = countMarkets(offers);
        h.numTradeStatistics = tradeStatisticsManager.getObservableTradeStatisticsList().size();
        h.priceFeedAvailable = hasAnyRecentPrice();
        h.uptimeSeconds = (System.currentTimeMillis() - startTimeMs) / 1000;
        h.version = OrderbookConfig.VERSION;
        h.timestamp = System.currentTimeMillis();
        return h;
    }

    /** All active markets with liquidity/price summaries. */
    public List<Dto.MarketSummary> markets() {
        Map<String, List<Offer>> byMarket = groupOffersByMarket(offerBookService.getOffers());
        TradeAgg trades = aggregateTrades();
        List<Dto.MarketSummary> result = new ArrayList<>();
        for (Map.Entry<String, List<Offer>> e : byMarket.entrySet()) {
            result.add(buildSummary(e.getKey(), e.getValue(), trades));
        }
        result.sort(Comparator.comparing(s -> s.market));
        return result;
    }

    /** Aggregated bid/ask levels + cumulative depth for one market (counter-currency code). */
    public Dto.Orderbook orderbook(String currencyCode, int depthLimit) {
        String code = currencyCode.toUpperCase();
        List<Offer> offers = new ArrayList<>();
        for (Offer o : offerBookService.getOffers()) {
            if (code.equalsIgnoreCase(o.getCounterCurrencyCode())) offers.add(o);
        }
        Dto.Orderbook ob = new Dto.Orderbook();
        ob.market = "XMR/" + code;
        ob.counterCurrency = code;
        ob.currencyType = currencyType(code);
        ob.indexPrice = indexPrice(code);
        int[] unpriced = new int[1];
        ob.bids = buildLevels(offers, OfferDirection.BUY, true, depthLimit, unpriced);
        ob.asks = buildLevels(offers, OfferDirection.SELL, false, depthLimit, unpriced);
        ob.unpricedOfferCount = unpriced[0];
        ob.timestamp = System.currentTimeMillis();
        return ob;
    }

    /** Raw per-offer listing, optionally filtered by market and/or direction. */
    public List<Dto.Offer> offers(String marketOrNull, String directionOrNull) {
        String code = marketOrNull == null ? null : marketOrNull.toUpperCase();
        OfferDirection dir = directionOrNull == null ? null : OfferDirection.valueOf(directionOrNull.toUpperCase());
        List<Dto.Offer> result = new ArrayList<>();
        for (Offer o : offerBookService.getOffers()) {
            if (code != null && !code.equalsIgnoreCase(o.getCounterCurrencyCode())) continue;
            if (dir != null && o.getDirection() != dir) continue;
            result.add(toOfferDto(o));
        }
        return result;
    }

    /** External reference (index) prices keyed by currency code. */
    public Map<String, Double> prices() {
        Map<String, Double> result = new TreeMap<>();
        for (Offer o : offerBookService.getOffers()) {
            String code = o.getCounterCurrencyCode();
            if (!result.containsKey(code)) {
                Double p = indexPrice(code);
                if (p != null) result.put(code, p);
            }
        }
        return result;
    }

    /** Completed trades for a market, newest first. */
    public List<Dto.Trade> trades(String currencyCode, int limit, long sinceMs) {
        String code = currencyCode.toUpperCase();
        int cap = Math.min(limit <= 0 ? config.maxTradesPerResponse : limit, config.maxTradesPerResponse);
        List<TradeStatistics3> all = tradeStatisticsManager.getTradeStatisticsListCopy();
        List<Dto.Trade> result = new ArrayList<>();
        all.sort(Comparator.comparingLong(TradeStatistics3::getDateAsLong).reversed());
        for (TradeStatistics3 ts : all) {
            if (!code.equalsIgnoreCase(ts.getCurrency())) continue;
            if (sinceMs > 0 && ts.getDateAsLong() < sinceMs) continue;
            result.add(toTradeDto(ts));
            if (result.size() >= cap) break;
        }
        return result;
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Internal helpers
    ///////////////////////////////////////////////////////////////////////////////////////////

    private Dto.MarketSummary buildSummary(String code, List<Offer> offers, TradeAgg trades) {
        Dto.MarketSummary s = new Dto.MarketSummary();
        s.market = "XMR/" + code;
        s.baseCurrency = "XMR";
        s.counterCurrency = code;
        s.currencyType = currencyType(code);

        Set<String> makers = new HashSet<>();
        Double bestBid = null;
        Double bestAsk = null;
        for (Offer o : offers) {
            makers.add(o.getOwnerNodeAddress() == null ? o.getId() : o.getOwnerNodeAddress().getFullAddress());
            double amountXmr = toXmr(o.getAmount());
            Double price = priceOf(o);
            if (o.getDirection() == OfferDirection.BUY) {
                s.buyOfferCount++;
                s.buyLiquidityXmr += amountXmr;
                if (price != null && (bestBid == null || price > bestBid)) bestBid = price;
            } else {
                s.sellOfferCount++;
                s.sellLiquidityXmr += amountXmr;
                if (price != null && (bestAsk == null || price < bestAsk)) bestAsk = price;
            }
        }
        s.uniqueMakers = makers.size();
        s.bestBid = bestBid;
        s.bestAsk = bestAsk;
        if (bestBid != null && bestAsk != null) {
            s.spread = bestAsk - bestBid;
            s.midPrice = (bestAsk + bestBid) / 2;
            s.spreadPct = s.midPrice == 0 ? null : (s.spread / s.midPrice) * 100;
        }
        s.indexPrice = indexPrice(code);
        s.lastTradePrice = trades.lastPrice.get(code);
        Double vol = trades.volume24hXmr.get(code);
        s.volume24hXmr = vol == null ? 0 : vol;
        Integer cnt = trades.count24h.get(code);
        s.trades24h = cnt == null ? 0 : cnt;
        return s;
    }

    private List<Dto.Level> buildLevels(List<Offer> offers, OfferDirection direction,
                                        boolean highToLow, int depthLimit, int[] unpricedCounter) {
        // Aggregate amount + count per price level.
        Map<Double, double[]> byPrice = new java.util.HashMap<>(); // price -> [amountXmr, count]
        for (Offer o : offers) {
            if (o.getDirection() != direction) continue;
            Double price = priceOf(o);
            if (price == null) {
                unpricedCounter[0]++;
                continue;
            }
            double[] agg = byPrice.computeIfAbsent(price, k -> new double[2]);
            agg[0] += toXmr(o.getAmount());
            agg[1] += 1;
        }
        List<Double> prices = new ArrayList<>(byPrice.keySet());
        prices.sort(highToLow ? Comparator.reverseOrder() : Comparator.naturalOrder());
        List<Dto.Level> levels = new ArrayList<>();
        double cumulative = 0;
        for (Double price : prices) {
            double[] agg = byPrice.get(price);
            cumulative += agg[0];
            levels.add(new Dto.Level(price, agg[0], (int) agg[1], cumulative));
            if (depthLimit > 0 && levels.size() >= depthLimit) break;
        }
        return levels;
    }

    private Dto.Offer toOfferDto(Offer o) {
        Dto.Offer d = new Dto.Offer();
        d.id = o.getId();
        d.market = "XMR/" + o.getCounterCurrencyCode();
        d.direction = o.getDirection().name();
        d.currencyType = currencyType(o.getCounterCurrencyCode());
        d.price = priceOf(o);
        d.useMarketBasedPrice = o.isUseMarketBasedPrice();
        d.marketPriceMarginPct = o.getMarketPriceMarginPct();
        d.amountXmr = toXmr(o.getAmount());
        d.minAmountXmr = toXmr(o.getMinAmount());
        d.isRange = o.isRange();
        d.volume = toDouble(safeVolume(o));
        d.paymentMethod = o.getPaymentMethodId();
        d.makerFeePct = o.getMakerFeePct();
        d.takerFeePct = o.getTakerFeePct();
        d.penaltyFeePct = o.getPenaltyFeePct();
        d.buyerSecurityDepositPct = o.getBuyerSecurityDepositPct();
        d.sellerSecurityDepositPct = o.getSellerSecurityDepositPct();
        try {
            d.maxTradeLimitXmr = toXmr(o.getMaxTradeLimit());
        } catch (Exception e) {
            d.maxTradeLimitXmr = null;
        }
        d.maxTradePeriodMs = o.getMaxTradePeriod();
        d.countryCode = o.getCountryCode();
        d.f2fCity = emptyToNull(o.getF2FCity());
        d.isPrivate = o.isPrivateOffer();
        d.makerNodeAddress = config.exposeMakerAddress && o.getOwnerNodeAddress() != null
                ? o.getOwnerNodeAddress().getFullAddress() : null;
        d.date = o.getDate() == null ? 0 : o.getDate().getTime();
        return d;
    }

    private Dto.Trade toTradeDto(TradeStatistics3 ts) {
        Dto.Trade t = new Dto.Trade();
        t.counterCurrency = ts.getCurrency();
        t.market = "XMR/" + ts.getCurrency();
        Price price = ts.getTradePrice();
        t.price = price == null ? 0 : price.getDoubleValue();
        t.amountXmr = toXmr(ts.getTradeAmount());
        t.volume = toDouble(safeTradeVolume(ts));
        t.paymentMethod = ts.getPaymentMethodId();
        t.date = ts.getDateAsLong();
        return t;
    }

    /** One-pass aggregation of trade statistics into per-market last price and 24h volume/count. */
    private TradeAgg aggregateTrades() {
        TradeAgg agg = new TradeAgg();
        long cutoff = System.currentTimeMillis() - DAY_MS;
        Map<String, Long> lastDate = new java.util.HashMap<>();
        for (TradeStatistics3 ts : tradeStatisticsManager.getTradeStatisticsListCopy()) {
            String code = ts.getCurrency();
            if (code == null) continue;
            long date = ts.getDateAsLong();
            Price price = ts.getTradePrice();
            if (price != null) {
                Long prev = lastDate.get(code);
                if (prev == null || date > prev) {
                    lastDate.put(code, date);
                    agg.lastPrice.put(code, price.getDoubleValue());
                }
            }
            if (date >= cutoff) {
                agg.volume24hXmr.merge(code, toXmr(ts.getTradeAmount()), Double::sum);
                agg.count24h.merge(code, 1, Integer::sum);
            }
        }
        return agg;
    }

    private Map<String, List<Offer>> groupOffersByMarket(List<Offer> offers) {
        Map<String, List<Offer>> byMarket = new TreeMap<>();
        for (Offer o : offers) {
            byMarket.computeIfAbsent(o.getCounterCurrencyCode(), k -> new ArrayList<>()).add(o);
        }
        return byMarket;
    }

    private int countMarkets(List<Offer> offers) {
        Set<String> codes = new HashSet<>();
        for (Offer o : offers) codes.add(o.getCounterCurrencyCode());
        return codes.size();
    }

    private boolean hasAnyRecentPrice() {
        for (Offer o : offerBookService.getOffers()) {
            if (indexPrice(o.getCounterCurrencyCode()) != null) return true;
        }
        return false;
    }

    private Double indexPrice(String code) {
        MarketPrice mp = priceFeedService.getMarketPrice(code);
        if (mp == null || !mp.isPriceAvailable()) return null;
        return mp.getPrice();
    }

    private String currencyType(String code) {
        return CurrencyUtil.isCryptoCurrency(code) ? "crypto" : "fiat";
    }

    /** Resolved price of an offer, or null when a market-based offer has no usable reference price. */
    private Double priceOf(Offer o) {
        try {
            Price p = o.getPrice();
            return p == null ? null : p.getDoubleValue();
        } catch (Throwable t) {
            return null;
        }
    }

    private Volume safeVolume(Offer o) {
        try {
            return o.getVolume();
        } catch (Throwable t) {
            return null;
        }
    }

    private Volume safeTradeVolume(TradeStatistics3 ts) {
        try {
            return ts.getTradeVolume();
        } catch (Throwable t) {
            return null;
        }
    }

    private static double toXmr(BigInteger atomicUnits) {
        if (atomicUnits == null) return 0;
        return new BigDecimal(atomicUnits).movePointLeft(XMR_EXPONENT).doubleValue();
    }

    private static Double toDouble(Volume volume) {
        if (volume == null) return null;
        return new BigDecimal(volume.getValue()).movePointLeft(volume.smallestUnitExponent()).doubleValue();
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    private static class TradeAgg {
        final Map<String, Double> lastPrice = new java.util.HashMap<>();
        final Map<String, Double> volume24hXmr = new java.util.HashMap<>();
        final Map<String, Integer> count24h = new java.util.HashMap<>();
    }
}
