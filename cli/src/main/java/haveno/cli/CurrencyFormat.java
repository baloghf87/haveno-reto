/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package haveno.cli;

import com.google.common.annotations.VisibleForTesting;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.NumberFormat;
import java.util.Locale;

import static java.lang.String.format;
import static java.math.RoundingMode.HALF_UP;
import static java.math.RoundingMode.UNNECESSARY;

/**
 * Utility for formatting amounts, volumes and fees;  there is no i18n support in the CLI.
 */
@VisibleForTesting
public class CurrencyFormat {

    // Use the US locale as a base for all DecimalFormats, but commas should be omitted from number strings.
    private static final DecimalFormatSymbols DECIMAL_FORMAT_SYMBOLS = DecimalFormatSymbols.getInstance(Locale.US);

    // Use the US locale as a base for all NumberFormats, but commas should be omitted from number strings.
    private static final NumberFormat US_LOCALE_NUMBER_FORMAT = NumberFormat.getInstance(Locale.US);

    // Formats numbers for internal use, i.e., grpc request parameters.
    private static final DecimalFormat INTERNAL_FIAT_DECIMAL_FORMAT = new DecimalFormat("##############0.0000");

    // Haveno's daemon represents XMR amounts as atomic units (piconeros), i.e. 1 XMR = 1e12 atomic units.
    // (This differs from the Bisq-derived satoshi factor of 1e8 that this CLI was originally built on.)
    static final BigDecimal XMR_ATOMIC_UNIT_DIVISOR = new BigDecimal("1000000000000");
    static final DecimalFormat XMR_FORMAT = new DecimalFormat("###,##0.000000000000", DECIMAL_FORMAT_SYMBOLS);
    static final DecimalFormat XMR_TRIMMED_FORMAT = new DecimalFormat("###,##0.############", DECIMAL_FORMAT_SYMBOLS);
    static final DecimalFormat XMR_TX_FEE_FORMAT = new DecimalFormat("###,###,##0", DECIMAL_FORMAT_SYMBOLS);

    static final BigDecimal BSQ_SATOSHI_DIVISOR = new BigDecimal(100);
    static final DecimalFormat BSQ_FORMAT = new DecimalFormat("###,###,###,##0.00", DECIMAL_FORMAT_SYMBOLS);

    public static String formatXmr(String atomicUnits) {
        //noinspection BigDecimalMethodWithoutRoundingCalled
        return XMR_FORMAT.format(new BigDecimal(atomicUnits).divide(XMR_ATOMIC_UNIT_DIVISOR));
    }

    @SuppressWarnings("BigDecimalMethodWithoutRoundingCalled")
    public static String formatXmr(long atomicUnits) {
        return XMR_FORMAT.format(new BigDecimal(atomicUnits).divide(XMR_ATOMIC_UNIT_DIVISOR));
    }

    @SuppressWarnings("BigDecimalMethodWithoutRoundingCalled")
    public static String formatXmrTrimmed(long atomicUnits) {
        return XMR_TRIMMED_FORMAT.format(new BigDecimal(atomicUnits).divide(XMR_ATOMIC_UNIT_DIVISOR));
    }

    @SuppressWarnings("BigDecimalMethodWithoutRoundingCalled")
    public static String formatBsq(long sats) {
        return BSQ_FORMAT.format(new BigDecimal(sats).divide(BSQ_SATOSHI_DIVISOR));
    }

    public static String formatInternalFiatPrice(BigDecimal price) {
        INTERNAL_FIAT_DECIMAL_FORMAT.setMinimumFractionDigits(4);
        INTERNAL_FIAT_DECIMAL_FORMAT.setMaximumFractionDigits(4);
        return INTERNAL_FIAT_DECIMAL_FORMAT.format(price);
    }

    public static String formatInternalFiatPrice(double price) {
        US_LOCALE_NUMBER_FORMAT.setMinimumFractionDigits(4);
        US_LOCALE_NUMBER_FORMAT.setMaximumFractionDigits(4);
        return US_LOCALE_NUMBER_FORMAT.format(price);
    }

    public static String formatPrice(long price) {
        US_LOCALE_NUMBER_FORMAT.setMinimumFractionDigits(4);
        US_LOCALE_NUMBER_FORMAT.setMaximumFractionDigits(4);
        US_LOCALE_NUMBER_FORMAT.setRoundingMode(UNNECESSARY);
        return US_LOCALE_NUMBER_FORMAT.format((double) price / 10_000);
    }

    public static String formatFiatVolume(long volume) {
        US_LOCALE_NUMBER_FORMAT.setMinimumFractionDigits(0);
        US_LOCALE_NUMBER_FORMAT.setMaximumFractionDigits(0);
        US_LOCALE_NUMBER_FORMAT.setRoundingMode(HALF_UP);
        return US_LOCALE_NUMBER_FORMAT.format((double) volume / 10_000);
    }

    public static long toAtomicUnits(String xmr) {
        if (xmr.startsWith("-"))
            throw new IllegalArgumentException(format("'%s' is not a positive number", xmr));

        try {
            return new BigDecimal(xmr).multiply(XMR_ATOMIC_UNIT_DIVISOR).longValue();
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(format("'%s' is not a number", xmr));
        }
    }

    public static String formatFeeAtomicUnits(long atomicUnits) {
        return XMR_TX_FEE_FORMAT.format(BigDecimal.valueOf(atomicUnits));
    }
}
