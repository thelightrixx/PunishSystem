package de.lightrixx.punish.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class VerstossStufeTest {

    @ParameterizedTest
    @ValueSource(strings = {"low", "LOW", " Low ", "leicht", "klein", "minor", "niedrig"})
    void parseLowMitAliasen(String roh) {
        assertEquals(VerstossStufe.LOW, VerstossStufe.parse(roh).orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(strings = {"medium", "MEDIUM", " mittel ", "normal", "moderat"})
    void parseMediumMitAliasen(String roh) {
        assertEquals(VerstossStufe.MEDIUM, VerstossStufe.parse(roh).orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(strings = {"high", "HIGH", " schwer ", "gravierend", "major", "hoch", "schwerwiegend"})
    void parseHighMitAliasen(String roh) {
        assertEquals(VerstossStufe.HIGH, VerstossStufe.parse(roh).orElseThrow());
    }

    @Test
    void parseUnbekanntLiefertLeer() {
        assertTrue(VerstossStufe.parse("extrem").isEmpty());
        assertTrue(VerstossStufe.parse("").isEmpty());
        assertTrue(VerstossStufe.parse(null).isEmpty());
    }

    @Test
    void standardIstMedium() {
        assertEquals(VerstossStufe.MEDIUM, VerstossStufe.standard());
        assertEquals(VerstossStufe.MEDIUM, VerstossStufe.parseOderStandard("quatsch"));
        assertEquals(VerstossStufe.HIGH, VerstossStufe.parseOderStandard("level-high".substring(6)));
    }

    @Test
    void standardDauernUndPunkte() {
        assertEquals(30L * 24 * 60 * 60 * 1000, VerstossStufe.LOW.standardDauerMillis());
        assertEquals(90L * 24 * 60 * 60 * 1000, VerstossStufe.MEDIUM.standardDauerMillis());
        assertEquals(365L * 24 * 60 * 60 * 1000, VerstossStufe.HIGH.standardDauerMillis());
        assertEquals(1, VerstossStufe.LOW.standardPunkte());
        assertEquals(2, VerstossStufe.MEDIUM.standardPunkte());
        assertEquals(3, VerstossStufe.HIGH.standardPunkte());
        assertEquals("low", VerstossStufe.LOW.schluessel());
        assertEquals("medium", VerstossStufe.MEDIUM.schluessel());
        assertEquals("high", VerstossStufe.HIGH.schluessel());
    }
}
