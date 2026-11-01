package de.lightrixx.punish.update;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateVergleichTest {

    @Test
    void gleicheVersionIstKeinUpdate() {
        assertFalse(UpdateVergleich.neuerVerfuegbar("4.0-beta+011126-1853", "4.0-beta+011126-1853"));
    }

    @Test
    void hoehererBuildIstUpdate() {
        assertTrue(UpdateVergleich.neuerVerfuegbar("4.0-beta+011126-1853", "4.0-beta+011126-1854"));
    }

    @Test
    void niedrigereVersionIstKeinUpdate() {
        assertFalse(UpdateVergleich.neuerVerfuegbar("4.0-beta+011126-1854", "4.0-beta+011126-1853"));
    }

    @Test
    void leereVersionIstKeinUpdate() {
        assertFalse(UpdateVergleich.neuerVerfuegbar("4.0-beta+011126-1853", ""));
        assertFalse(UpdateVergleich.neuerVerfuegbar("", "4.0-beta+011126-1854"));
    }

    @Test
    void finalWirdBetaNutzernAlsUpdateGemeldet() {
        assertTrue(UpdateVergleich.neuerVerfuegbar("4.0-beta+011126-1853", "4.0+011126-1854"));
        assertTrue(UpdateVergleich.neuerVerfuegbar("4.0-beta+011126-1853", "4.0+011126-1855"));
    }

    @Test
    void betaWirdFinalNutzernNichtAlsUpdateGemeldet() {
        assertFalse(UpdateVergleich.neuerVerfuegbar("4.0+011126-1854", "4.0-beta+011126-1855"));
        assertFalse(UpdateVergleich.neuerVerfuegbar("4.0", "4.0-beta+011126-1855"));
    }

    @Test
    void releaseCandidateLiegtZwischenBetaUndFinal() {
        assertTrue(UpdateVergleich.neuerVerfuegbar("4.0-beta+011126-1853", "4.0-RC+011126-1854"));
        assertTrue(UpdateVergleich.neuerVerfuegbar("4.0-RC+011126-1854", "4.0+011126-1855"));
        assertFalse(UpdateVergleich.neuerVerfuegbar("4.0+011126-1855", "4.0-RC+011126-1854"));
        assertFalse(UpdateVergleich.neuerVerfuegbar("4.0-RC+011126-1854", "4.0-beta+011126-1855"));
        assertFalse(UpdateVergleich.neuerVerfuegbar("4.0-RC+011126-1854", "4.0-RC+011126-1854"));
    }

    @Test
    void releasePfadRcZuFinalOhneDowngradeOderEndlosschleife() {
        assertTrue(UpdateVergleich.neuerVerfuegbar("4.0-RC+011126-1853", "4.0"),
                "Final (4.0) muss RC-Bestandsservern als Update gemeldet werden");
        assertFalse(UpdateVergleich.neuerVerfuegbar("4.0", "4.0-RC+011126-1853"),
                "Nach dem Update darf kein Downgrade gemeldet werden (keine Endlosschleife)");
        assertTrue(UpdateVergleich.neuerVerfuegbar("4.0", "4.0.1"),
                "Patch-Release ist ein Update");
        assertFalse(UpdateVergleich.neuerVerfuegbar("4.0.1", "4.0"),
                "Patch-Downgrade ist kein Update");
        assertTrue(UpdateVergleich.neuerVerfuegbar("4.0-RC+011126-1853", "4.0-RC+011126-1854"),
                "Hoeherer Datums-Build im selben Kanal ist ein Update");
        assertFalse(UpdateVergleich.neuerVerfuegbar("4.0-RC+011126-1854", "4.0-RC+011126-1853"));
    }

    @Test
    void snapshotWirdFinalAlsUpdateGemeldet() {
        assertTrue(UpdateVergleich.neuerVerfuegbar("4.0-SNAPSHOT", "4.0"),
                "Final muss Snapshot-Nutzern als Update gemeldet werden");
        assertFalse(UpdateVergleich.neuerVerfuegbar("4.0", "4.0-SNAPSHOT"),
                "Snapshot darf kein Downgrade empfehlen");
    }

    @Test
    void datumsBuildWirdChronologischVerglichen() {
        assertTrue(UpdateVergleich.neuerVerfuegbar("4.0-beta+011126-1853", "4.0-beta+011126-1854"));
        assertFalse(UpdateVergleich.neuerVerfuegbar("4.0-beta+011126-1854", "4.0-beta+011126-1853"));
        assertTrue(UpdateVergleich.neuerVerfuegbar("4.0-beta+311226-2359", "4.0-beta+010127-0000"));
    }

    @Test
    void unparbareVersionWirdNichtAlsUpdateEmpfohlen() {
        assertFalse(UpdateVergleich.neuerVerfuegbar("4.0-beta+011126-1853", "2027.1-irgendwas"));
        assertFalse(UpdateVergleich.neuerVerfuegbar("2027.1-irgendwas", "4.0-beta+011126-1853"));
    }

    @Test
    void hangarAntwortWirdGeparst() {
        String json = "{\"name\":\"4.0-beta+011126-1854\",\"channel\":{\"name\":\"Release\"},"
                + "\"downloads\":[{\"platform\":\"VELOCITY\",\"downloadUrl\":\"https://example.test/v.jar\","
                + "\"hash\":\"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef\"}]}";
        HangarUpdateService.ReleaseInfo info =
                HangarUpdateService.parseLatestRelease(json, "autor", "slug", "Release");
        assertEquals("4.0-beta+011126-1854", info.version(),
                "Der Parser darf der Version keinen Zusatztext anhaengen.");
        assertTrue(UpdateVergleich.neuerVerfuegbar("4.0-beta+011126-1853", info.version()));
    }

    @Test
    void hangarAntwortWaehltVersionsAehnlichesNamensFeld() {
        String json = "{\"project\":{\"name\":\"PunishSystem\"},\"channel\":{\"name\":\"Release\"},"
                + "\"name\":\"4.0-RC+011126-1853\",\"downloads\":[]}";
        HangarUpdateService.ReleaseInfo info =
                HangarUpdateService.parseLatestRelease(json, "autor", "slug", "Release");
        assertEquals("4.0-RC+011126-1853", info.version());
    }
}
