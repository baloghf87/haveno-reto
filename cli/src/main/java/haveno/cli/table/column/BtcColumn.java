package haveno.cli.table.column;

import java.util.stream.IntStream;

import static com.google.common.base.Strings.padEnd;
import static haveno.cli.CurrencyFormat.formatXmrTrimmed;
import static java.util.Comparator.comparingInt;

public class BtcColumn extends SatoshiColumn {

    public BtcColumn(String name) {
        super(name);
    }

    @Override
    public void addRow(Long value) {
        rows.add(value);

        String s = formatXmrTrimmed(value);
        stringColumn.addRow(s);

        if (isNewMaxWidth.test(s))
            maxWidth = s.length();
    }

    @Override
    public String getRowAsFormattedString(int rowIndex) {
        return formatXmrTrimmed(getRow(rowIndex));
    }

    @Override
    public StringColumn asStringColumn() {
        // We cached the formatted XMR strings, but we did
        // not know how much zero padding each string needed until now.
        int maxColumnValueWidth = stringColumn.getRows().stream()
                .max(comparingInt(String::length))
                .get()
                .length();
        IntStream.range(0, stringColumn.getRows().size()).forEach(rowIndex -> {
            String xmrString = stringColumn.getRow(rowIndex);
            if (xmrString.length() < maxColumnValueWidth) {
                String paddedXmrString = padEnd(xmrString, maxColumnValueWidth, '0');
                stringColumn.updateRow(rowIndex, paddedXmrString);
            }
        });
        return stringColumn.justify();
    }
}
