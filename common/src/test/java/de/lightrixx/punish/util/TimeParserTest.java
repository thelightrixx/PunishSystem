package de.lightrixx.punish.util;

import de.lightrixx.punish.model.ParseResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class TimeParserTest {

    private TimeParser parser;

    @BeforeEach
    void setUp() {
        parser = new TimeParser();
    }

    @ParameterizedTest
    @CsvSource({
            "10m, 600000",
            "1h, 3600000",
            "1d, 86400000",
            "1w, 604800000",
            "1mo, 2592000000",
            "30s, 30000",
            "1h30m, 5400000",
            "1d12h, 129600000",
            "7d, 604800000"
    })
    void parsenGueltigeAngaben(String eingabe, long erwartetMillis) {
        ParseResult result = parser.parsen(eingabe);
        assertInstanceOf(ParseResult.Geltzeit.class, result);
        assertEquals(erwartetMillis, ((ParseResult.Geltzeit) result).millis());
    }

    @Test
    void parsenMitLeerzeichen() {
        ParseResult result = parser.parsen("1h 30m");
        assertInstanceOf(ParseResult.Geltzeit.class, result);
        assertEquals(5400000L, ((ParseResult.Geltzeit) result).millis());
    }

    @Test
    void parsenMitGrossbuchstaben() {
        ParseResult result = parser.parsen("1H30M");
        assertInstanceOf(ParseResult.Geltzeit.class, result);
        assertEquals(5400000L, ((ParseResult.Geltzeit) result).millis());
    }

    @ParameterizedTest
    @ValueSource(strings = {"perm", "permanent", "PERM", "Permanent"})
    void parsenPermanent(String eingabe) {
        ParseResult result = parser.parsen(eingabe);
        assertInstanceOf(ParseResult.Permanent.class, result);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "abc", "1x", "0m", "0", "-1h", "h", "1h-30m"})
    void parsenUngueltig(String eingabe) {
        ParseResult result = parser.parsen(eingabe);
        assertInstanceOf(ParseResult.Ungueltig.class, result);
    }

    @Test
    void parsenNullIstUngueltig() {
        ParseResult result = parser.parsen(null);
        assertInstanceOf(ParseResult.Ungueltig.class, result);
    }

    @Test
    void parsenUeberlaufIstUngueltig() {

        ParseResult result = parser.parsen("999999999999999999d");
        assertInstanceOf(ParseResult.Ungueltig.class, result);
    }

    @Test
    void parsenMinutenOhneSuffix() {
        ParseResult result = parser.parsen("10");
        assertInstanceOf(ParseResult.Geltzeit.class, result);
        assertEquals(600000L, ((ParseResult.Geltzeit) result).millis());
    }

    @Test
    void parsenAbsurdeDauerIstUngueltig() {
        assertInstanceOf(ParseResult.Ungueltig.class, parser.parsen("9999y"));
        assertInstanceOf(ParseResult.Ungueltig.class, parser.parsen("101y"));
    }

    @Test
    void parsenGrenze100JahreIstGueltig() {
        assertInstanceOf(ParseResult.Geltzeit.class, parser.parsen("100y"));
        assertInstanceOf(ParseResult.Geltzeit.class, parser.parsen("1y"));
    }

    @Test
    void parsenWhitespaceFusioniertKeineTokens() {

        assertInstanceOf(ParseResult.Ungueltig.class, parser.parsen("5m o"));
        assertInstanceOf(ParseResult.Ungueltig.class, parser.parsen("7d x"));
        assertInstanceOf(ParseResult.Ungueltig.class, parser.parsen("perm 7d"));

        ParseResult result = parser.parsen("1h 30m");
        assertInstanceOf(ParseResult.Geltzeit.class, result);
        assertEquals(5400000L, ((ParseResult.Geltzeit) result).millis());
        ParseResult tabs = parser.parsen("2d\t12h");
        assertInstanceOf(ParseResult.Geltzeit.class, tabs);
    }
}
