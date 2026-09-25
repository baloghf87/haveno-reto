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

import org.junit.jupiter.api.Test;

import static haveno.cli.CurrencyFormat.formatXmr;
import static haveno.cli.CurrencyFormat.formatXmrTrimmed;
import static haveno.cli.CurrencyFormat.toAtomicUnits;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class CurrencyFormatTest {

    // Haveno's daemon treats offer/trade amounts as XMR atomic units (piconeros),
    // i.e. 1 XMR = 1_000_000_000_000 atomic units. The CLI must scale by 1e12, not
    // the Bisq-derived 1e8 satoshi factor.

    @Test
    public void testToAtomicUnitsScalesByOneTrillion() {
        assertEquals(1_000_000_000_000L, toAtomicUnits("1"));
        assertEquals(1_000_000_000_000L, toAtomicUnits("1.0"));
        assertEquals(500_000_000_000L, toAtomicUnits("0.5"));
        assertEquals(2_500_000_000_000L, toAtomicUnits("2.5"));
        assertEquals(0L, toAtomicUnits("0"));
    }

    @Test
    public void testToAtomicUnitsHandlesSmallestUnit() {
        // 1 atomic unit = 1e-12 XMR
        assertEquals(1L, toAtomicUnits("0.000000000001"));
        assertEquals(1_000L, toAtomicUnits("0.000000001"));
    }

    @Test
    public void testToAtomicUnitsRejectsNegative() {
        Throwable exception = assertThrows(IllegalArgumentException.class, () ->
                toAtomicUnits("-1.0"));
        assertEquals("'-1.0' is not a positive number", exception.getMessage());
    }

    @Test
    public void testToAtomicUnitsRejectsNonNumber() {
        Throwable exception = assertThrows(IllegalArgumentException.class, () ->
                toAtomicUnits("abc"));
        assertEquals("'abc' is not a number", exception.getMessage());
    }

    @Test
    public void testFormatXmrScalesByOneTrillion() {
        assertEquals("1.000000000000", formatXmr(1_000_000_000_000L));
        assertEquals("0.500000000000", formatXmr(500_000_000_000L));
        assertEquals("0.000000000001", formatXmr(1L));
    }

    @Test
    public void testFormatXmrTrimmedDropsTrailingZeros() {
        assertEquals("1", formatXmrTrimmed(1_000_000_000_000L));
        assertEquals("0.5", formatXmrTrimmed(500_000_000_000L));
        assertEquals("2.25", formatXmrTrimmed(2_250_000_000_000L));
    }

    @Test
    public void testRoundTripThroughAtomicUnits() {
        assertEquals("1", formatXmrTrimmed(toAtomicUnits("1.0")));
        assertEquals("0.0001", formatXmrTrimmed(toAtomicUnits("0.0001")));
    }
}
