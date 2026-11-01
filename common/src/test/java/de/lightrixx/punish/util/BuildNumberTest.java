package de.lightrixx.punish.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class BuildNumberTest {

    @Test
    void parseDatumMitMinute() {
        BuildNumber nummer = BuildNumber.parse("011126-1853");
        assertEquals("011126-1853", nummer.toString());
        assertEquals(LocalDateTime.of(2026, 11, 1, 18, 53), nummer.zeitpunkt().orElseThrow());
        assertEquals("011126-1853 (Build vom 01.11.2026, 18:53 Uhr)", nummer.beschreibung());
    }

    @Test
    void parseAlteStundenangabeBleibtLesbar() {
        BuildNumber nummer = BuildNumber.parse("011126-18");
        assertEquals("011126-1800", nummer.toString());
        assertEquals(LocalDateTime.of(2026, 11, 1, 18, 0), nummer.zeitpunkt().orElseThrow());
        assertEquals(nummer, BuildNumber.parse("011126-1800"));
    }

    @Test
    void datumFabrikNormalisiertAufMinute() {
        BuildNumber nummer = BuildNumber.datum(LocalDateTime.of(2026, 11, 1, 19, 45, 30));
        assertEquals("011126-1945", nummer.toString());
        assertEquals(LocalDateTime.of(2026, 11, 1, 19, 45), nummer.zeitpunkt().orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "A12b", "AB12b",
            "011126-24", "011126-1860", "320126-1200", "011326-1200", "290226-1200",
            "011126-9", "011126", "011126-1853-1", "011126_1853", "011126-18-53",
            "011126-185", "011126-18533"})
    void parseUngueltig(String eingabe) {
        assertTrue(BuildNumber.tryParse(eingabe).isEmpty(), eingabe);
        assertFalse(BuildNumber.istGueltig(eingabe));
        assertThrows(IllegalArgumentException.class, () -> BuildNumber.parse(eingabe));
    }

    @Test
    void parseNullIstUngueltig() {
        assertTrue(BuildNumber.tryParse(null).isEmpty());
        assertFalse(BuildNumber.istGueltig(null));
    }

    @Test
    void vergleichAufsteigend() {
        BuildNumber a = BuildNumber.parse("011126-1853");
        BuildNumber b = BuildNumber.parse("011126-1854");
        BuildNumber c = BuildNumber.parse("011126-1900");
        BuildNumber d = BuildNumber.parse("021126-0000");
        assertTrue(a.compareTo(b) < 0);
        assertTrue(b.compareTo(c) < 0);
        assertTrue(c.compareTo(d) < 0);
        assertEquals(0, a.compareTo(BuildNumber.parse("011126-1853")));
        assertTrue(a.compareTo(null) > 0);
    }

    @Test
    void naechsterBuild() {
        assertEquals("011126-1854", BuildNumber.parse("011126-1853").naechsterBuild().toString());
        assertEquals("010127-0000", BuildNumber.parse("311226-2359").naechsterBuild().toString());
    }

    @Test
    void equalsUndHashCode() {
        BuildNumber a = BuildNumber.parse("011126-1853");
        BuildNumber b = BuildNumber.parse("011126-1853");
        BuildNumber c = BuildNumber.parse("011126-1854");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
        assertNotEquals(a, BuildNumber.parse("011126-18"));
    }

    @Test
    void pluginVersionReleaseCandidate() {
        PluginVersion rc = PluginVersion.parse("v4.0-RC+011126-1853");
        assertEquals("v4.0-RC", rc.basis());
        assertEquals(4, rc.major());
        assertEquals(0, rc.minor());
        assertEquals("011126-1853", rc.build().toString());
        assertTrue(rc.hatBuild());
        assertFalse(rc.istBeta());
        assertTrue(rc.istReleaseCandidate());
        assertEquals("v4.0-RC+011126-1853", rc.voll());
        assertEquals("v4.0-RC+011126-1853", rc.logZeile());

        PluginVersion beta = PluginVersion.parse("v4.0-beta+011126-1854");
        assertTrue(beta.istBeta());
        assertFalse(beta.istReleaseCandidate());

        PluginVersion fin = PluginVersion.parse("v4.0+011126-1855");
        assertFalse(fin.istBeta());
        assertFalse(fin.istReleaseCandidate());
        assertEquals(2, fin.kanalRang());
        assertEquals(1, rc.kanalRang());
        assertEquals(0, beta.kanalRang());

        assertTrue(PluginVersion.istGueltig("v4.0-RC"));
        assertTrue(PluginVersion.istGueltig("v4.0-rc+011126-1853"));
        assertFalse(PluginVersion.istGueltig("v4.0-gamma+011126-1853"));
    }

    @Test
    void pluginVersionMitUndOhneBuild() {
        PluginVersion mit = PluginVersion.parse("v4.0-beta+011126-1853");
        assertEquals("v4.0-beta", mit.basis());
        assertEquals(4, mit.major());
        assertEquals(0, mit.minor());
        assertEquals("011126-1853", mit.build().toString());
        assertTrue(mit.hatBuild());
        assertTrue(mit.istBeta());
        assertEquals("v4.0-beta+011126-1853", mit.voll());
        assertEquals("v4.0-beta+011126-1853", mit.logZeile());

        PluginVersion ohne = PluginVersion.parse("v4.0-beta");
        assertFalse(ohne.hatBuild());
        assertEquals("v4.0-beta", ohne.voll());

        assertEquals("v4.0", PluginVersion.parse("v4.0").voll());
        assertEquals("v4.0.1-beta+011126-1800", PluginVersion.parse("v4.0.1-beta+011126-1800").voll());
        assertEquals("v4.0-beta+011126-1801", PluginVersion.parse("v4.0-beta+011126-1801").voll());
        assertEquals("v4.0-beta+011126-1802", PluginVersion.parse("4.0-beta+011126-1802").voll());
        assertEquals("v4.0-beta", PluginVersion.parse("4.0-beta").voll());
    }

    @Test
    void pluginVersionDatumBuildOhneTrain() {
        PluginVersion version = PluginVersion.parse("v4.0-beta+011126-1853");
        assertEquals("v4.0-beta+011126-1853", version.voll());
        assertTrue(version.hatBuild());
        assertEquals("v4.0-beta+011126-1853", version.logZeile());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "v4.0-beta+",
            "beta", "4-beta", "v4.0-beta+011126-1853+extra", "v4.0-beta+011126-24",
            "v4.0-beta+011126-9", "v4.0-beta+011126-1860"})
    void pluginVersionUngueltig(String eingabe) {
        assertTrue(PluginVersion.tryParse(eingabe).isEmpty(), eingabe);
        assertFalse(PluginVersion.istGueltig(eingabe));
    }
}
