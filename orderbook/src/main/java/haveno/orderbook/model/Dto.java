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

package haveno.orderbook.model;

import java.util.List;

/**
 * Plain data-transfer objects serialized to JSON by the REST API. Field names are the
 * public API contract; see {@code openapi.yaml}. All monetary XMR amounts are expressed in
 * whole XMR (not atomic units); prices are in the market's counter currency per 1 XMR.
 */
public final class Dto {

    private Dto() {
    }

    /** GET /api/v1/health */
    public static class Health {
        public String status;              // UP | STARTING | DOWN
        public boolean bootstrapped;
        public String network;
        public int numConnections;
        public int numOffers;
        public int numMarkets;
        public int numTradeStatistics;
        public boolean priceFeedAvailable;
        public long uptimeSeconds;
        public String version;
        public long timestamp;
    }

    /** One aggregated price level in the orderbook. */
    public static class Level {
        public double price;               // counter currency per 1 XMR
        public double amountXmr;           // total XMR available at this price
        public int offerCount;             // number of offers aggregated into this level
        public double cumulativeXmr;       // running total from best price to here

        public Level(double price, double amountXmr, int offerCount, double cumulativeXmr) {
            this.price = price;
            this.amountXmr = amountXmr;
            this.offerCount = offerCount;
            this.cumulativeXmr = cumulativeXmr;
        }
    }

    /** GET /api/v1/orderbook/{market} */
    public static class Orderbook {
        public String market;              // e.g. "XMR/EUR"
        public String counterCurrency;     // e.g. "EUR"
        public String currencyType;        // fiat | crypto
        public Double indexPrice;          // reference price, may be null
        public List<Level> bids;           // buy offers, highest price first
        public List<Level> asks;           // sell offers, lowest price first
        public int unpricedOfferCount;     // market-based offers with no resolvable price
        public long timestamp;
    }

    /** Element of GET /api/v1/markets and GET /api/v1/ticker */
    public static class MarketSummary {
        public String market;              // "XMR/EUR"
        public String baseCurrency;        // always "XMR"
        public String counterCurrency;     // "EUR"
        public String currencyType;        // fiat | crypto
        public int buyOfferCount;
        public int sellOfferCount;
        public double buyLiquidityXmr;
        public double sellLiquidityXmr;
        public int uniqueMakers;
        public Double bestBid;             // highest buy price, may be null
        public Double bestAsk;             // lowest sell price, may be null
        public Double spread;              // bestAsk - bestBid, may be null
        public Double spreadPct;           // spread / midPrice * 100, may be null
        public Double midPrice;            // (bestBid + bestAsk) / 2, may be null
        public Double indexPrice;          // external reference price, may be null
        public Double lastTradePrice;      // most recent completed trade price, may be null
        public double volume24hXmr;
        public int trades24h;
        public Double bidVwap;             // size-weighted average price of the priced buy offers, may be null
        public Double askVwap;             // size-weighted average price of the priced sell offers, may be null
        public Double open24h;             // first trade price of the last 24h, may be null
        public Double high24h;             // highest trade price of the last 24h, may be null
        public Double low24h;              // lowest trade price of the last 24h, may be null
    }

    /** A single open offer (GET /api/v1/offers). */
    public static class Offer {
        public String id;
        public String market;
        public String direction;           // BUY | SELL (maker perspective on XMR)
        public String currencyType;        // fiat | crypto
        public Double price;               // resolved price, null if market-based & no feed
        public boolean useMarketBasedPrice;
        public double marketPriceMarginPct;
        public double amountXmr;
        public double minAmountXmr;
        public boolean isRange;
        public Double volume;              // in counter currency, null if price null
        public String paymentMethod;
        public double makerFeePct;
        public double takerFeePct;
        public double penaltyFeePct;
        public double buyerSecurityDepositPct;
        public double sellerSecurityDepositPct;
        public Double maxTradeLimitXmr;
        public long maxTradePeriodMs;
        public String countryCode;         // fiat offers, may be null
        public String f2fCity;             // face-to-face offers, may be null
        public boolean isPrivate;
        public String makerNodeAddress;    // null unless HAVENO_EXPOSE_MAKER_ADDRESS=true
        public long date;
    }

    /** A completed trade (GET /api/v1/trades/{market}). */
    public static class Trade {
        public String market;
        public String counterCurrency;
        public double price;
        public double amountXmr;
        public Double volume;              // counter currency
        public String paymentMethod;
        public long date;
    }
}
