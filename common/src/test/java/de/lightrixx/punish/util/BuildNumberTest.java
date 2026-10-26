package de.lightrixx.punish.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class BuildNumberTest {

    @Test
    void parseGueltig() {
        BuildNumber nummer = BuildNumber.parse("AB12b");
        assertEquals("AB", nummer.train());
        assertEquals(12, nummer.nummer());
        assertEquals(BuildNumber.Stabilitaet.B, nummer.stabilitaet());
        assertEquals("AB12b", nummer.toString());
        assertEquals("AB12b (Release Candidate – fast final)", nummer.beschreibung());
    }

    @Test
    void parsePlatzhalter() {

        BuildNumber nummer = BuildNumber.parse("XX00a");
        assertEquals("XX", nummer.train());
        assertEquals(0, nummer.nummer());
        assertEquals("00", nummer.nummerFormatiert());
    }

    @Test
    void parseNormalisiertGrossUndKlein() {
        assertEquals("AB12b", BuildNumber.parse("ab12B").toString());
        assertEquals("AA01a", BuildNumber.parse("AA01a").toString());
        assertEquals("AA01z", BuildNumber.parse("AA01Z").toString());
    }

    @Test
    void parseOhneStufenbuchstabeIstFreigabe() {
        BuildNumber nummer = BuildNumber.parse("EA26");
        assertEquals("EA", nummer.train());
        assertEquals(26, nummer.nummer());
        assertNull(nummer.stabilitaet());
        assertEquals("EA26", nummer.toString());
        assertEquals("EA26 (Freigabe – ohne Stufenbuchstabe)", nummer.beschreibung());
        assertTrue(nummer.istMindestens(BuildNumber.Stabilitaet.B));
        assertEquals("EA27", nummer.naechsterBuild().toString());
        assertNull(nummer.naechsterBuild().stabilitaet());
    }

    @Test
    void parseVergleichMitUndOhneBuchstabe() {
        BuildNumber freigabe = BuildNumber.parse("EA26");
        BuildNumber rcAlt = BuildNumber.parse("EA26b");
        BuildNumber beta = BuildNumber.parse("EA26c");
        assertTrue(freigabe.compareTo(rcAlt) < 0);
        assertTrue(rcAlt.compareTo(beta) < 0);
        assertTrue(freigabe.compareTo(beta) < 0);
        assertEquals(0, freigabe.compareTo(BuildNumber.parse("EA26")));
        assertEquals("EA26", BuildNumber.von("EA", 26).toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "A12b", "ABC12b", "AB1b", "AB123b",
            "AB121", "AB12-", "AB12!", "12ABb", "AB-b", "AB12bb", "AB 12b"})
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
    void stabilitaetAufloesung() {
        assertEquals(BuildNumber.Stabilitaet.A, BuildNumber.Stabilitaet.vonCode('a'));
        assertEquals(BuildNumber.Stabilitaet.Z, BuildNumber.Stabilitaet.vonCode('Z'));
        assertEquals(BuildNumber.Stabilitaet.G, BuildNumber.Stabilitaet.vonCode('g'));
        assertThrows(IllegalArgumentException.class, () -> BuildNumber.Stabilitaet.vonCode('1'));
        assertThrows(IllegalArgumentException.class, () -> BuildNumber.Stabilitaet.vonCode('-'));
    }

    @Test
    void aIstStabilsteStufe() {

        assertTrue(BuildNumber.Stabilitaet.A.featureFertig());
        assertTrue(BuildNumber.Stabilitaet.B.featureFertig());
        assertTrue(BuildNumber.Stabilitaet.C.featureFertig());
        assertFalse(BuildNumber.Stabilitaet.D.featureFertig());
        assertFalse(BuildNumber.Stabilitaet.E.featureFertig());
        assertFalse(BuildNumber.Stabilitaet.Z.featureFertig());
        assertEquals("Final – stabilste Stufe, Release-reif",
                BuildNumber.Stabilitaet.A.beschreibung());
        assertTrue(BuildNumber.parse("AA01a").istMindestens(BuildNumber.Stabilitaet.B));
        assertTrue(BuildNumber.parse("AA01b").istMindestens(BuildNumber.Stabilitaet.B));
        assertFalse(BuildNumber.parse("AA01c").istMindestens(BuildNumber.Stabilitaet.B));
        assertFalse(BuildNumber.parse("AA01z").istMindestens(BuildNumber.Stabilitaet.B));
    }

    @Test
    void vergleichAufsteigend() {
        BuildNumber a = BuildNumber.parse("AA01a");
        BuildNumber b = BuildNumber.parse("AA01b");
        BuildNumber c = BuildNumber.parse("AA02a");
        BuildNumber d = BuildNumber.parse("AB01a");
        BuildNumber z = BuildNumber.parse("AA01z");
        assertTrue(a.compareTo(b) < 0);
        assertTrue(b.compareTo(c) < 0);
        assertTrue(c.compareTo(d) < 0);
        assertTrue(b.compareTo(z) < 0);
        assertEquals(0, b.compareTo(BuildNumber.parse("AA01b")));
    }

    @Test
    void ersterBuildIstAlpha() {
        assertEquals("AB01d", BuildNumber.erster("AB").toString());
    }

    @Test
    void trainAusVersion() {

        assertEquals("EA", BuildNumber.trainFuer(4, 0));
        assertEquals("AA", BuildNumber.trainFuer(0, 0));
        assertEquals("BV", BuildNumber.trainFuer(1, 21));
        assertEquals("ZZ", BuildNumber.trainFuer(25, 25));
        assertThrows(IllegalArgumentException.class, () -> BuildNumber.trainFuer(26, 0));
        assertThrows(IllegalArgumentException.class, () -> BuildNumber.trainFuer(4, -1));
        assertEquals("EA01d", BuildNumber.ersterFuerVersion(4, 0).toString());
        assertEquals(4, BuildNumber.parse("EA01c").trainMajor());
        assertEquals(0, BuildNumber.parse("EA01c").trainMinor());
    }

    @Test
    void naechsterBuild() {
        assertEquals("AA02b", BuildNumber.parse("AA01b").naechsterBuild().toString());
        assertEquals("XX01a", BuildNumber.parse("XX00a").naechsterBuild().toString());
        assertThrows(IllegalStateException.class, () -> BuildNumber.parse("AA99b").naechsterBuild());
    }

    @Test
    void pluginVersionReleaseCandidateOhneBuchstabe() {
        PluginVersion rc = PluginVersion.parse("v4.0-RC+EA26");
        assertEquals("v4.0-RC", rc.basis());
        assertEquals(4, rc.major());
        assertEquals(0, rc.minor());
        assertEquals("EA26", rc.build().toString());
        assertTrue(rc.hatBuild());
        assertFalse(rc.istBeta());
        assertTrue(rc.istReleaseCandidate());
        assertEquals("v4.0-RC+EA26", rc.voll());
        assertEquals("v4.0-RC+EA26", rc.logZeile());

        PluginVersion beta = PluginVersion.parse("v4.0-beta+EA26c");
        assertTrue(beta.istBeta());
        assertFalse(beta.istReleaseCandidate());

        PluginVersion fin = PluginVersion.parse("v4.0+EA26");
        assertFalse(fin.istBeta());
        assertFalse(fin.istReleaseCandidate());
        assertEquals(2, fin.kanalRang());
        assertEquals(1, rc.kanalRang());
        assertEquals(0, beta.kanalRang());

        assertTrue(PluginVersion.istGueltig("v4.0-RC"));
        assertTrue(PluginVersion.istGueltig("v4.0-rc+EA26"));
        assertFalse(PluginVersion.istGueltig("v4.0-gamma+EA12c"));
    }

    @Test
    void pluginVersionMitUndOhneBuild() {
        PluginVersion mit = PluginVersion.parse("v4.0-beta+EA01c");
        assertEquals("v4.0-beta", mit.basis());
        assertEquals(4, mit.major());
        assertEquals(0, mit.minor());
        assertEquals("EA", mit.erwarteterTrain());
        assertEquals("EA01c", mit.build().toString());
        assertTrue(mit.hatBuild());
        assertTrue(mit.istBeta());
        assertEquals("v4.0-beta+EA01c", mit.voll());
        assertEquals("v4.0-beta+EA01c (Beta – feature-fertig, Testphase)", mit.logZeile());

        PluginVersion ohne = PluginVersion.parse("v4.0-beta");
        assertFalse(ohne.hatBuild());
        assertEquals("v4.0-beta", ohne.voll());

        assertEquals("v4.0", PluginVersion.parse("v4.0").voll());
        assertEquals("v4.0.1-beta+EA00a", PluginVersion.parse("v4.0.1-beta+EA00a").voll());
        assertEquals("v4.0-beta+EA12z", PluginVersion.parse("v4.0-beta+EA12z").voll());
        assertEquals("v4.0-beta+EA01c", PluginVersion.parse("4.0-beta+EA01c").voll());
        assertEquals("v4.0-beta", PluginVersion.parse("4.0-beta").voll());
    }

    @Test
    void pluginVersionInkonsistenterTrainIstUngueltig() {

        assertTrue(PluginVersion.tryParse("v4.0-beta+AB12c").isEmpty());
        IllegalArgumentException fehler = assertThrows(IllegalArgumentException.class,
                () -> PluginVersion.parse("v4.0-beta+AB12c"));
        assertTrue(fehler.getMessage().contains("EA"), fehler.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "v4.0-beta+", "v4.0-beta+EA121",
            "beta", "4-beta", "v4.0-beta+EA12c+extra", "v4.0-beta+AB12c", "v4.0-gamma+EA12c"})
    void pluginVersionUngueltig(String eingabe) {
        assertTrue(PluginVersion.tryParse(eingabe).isEmpty(), eingabe);
        assertFalse(PluginVersion.istGueltig(eingabe));
    }
}
