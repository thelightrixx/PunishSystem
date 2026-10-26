package de.lightrixx.punish.update;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateVergleichTest {

    @Test
    void gleicheVersionIstKeinUpdate() {
        assertFalse(UpdateVergleich.neuerVerfuegbar("4.0-beta+EA25b", "4.0-beta+EA25b"));
    }

    @Test
    void hoehererBuildIstUpdate() {
        assertTrue(UpdateVergleich.neuerVerfuegbar("4.0-beta+EA25b", "4.0-beta+EA26b"));
    }

    @Test
    void niedrigereVersionIstKeinUpdate() {
        assertFalse(UpdateVergleich.neuerVerfuegbar("4.0-beta+EA26b", "4.0-beta+EA25b"));
    }

    @Test
    void leereVersionIstKeinUpdate() {
        assertFalse(UpdateVergleich.neuerVerfuegbar("4.0-beta+EA25b", ""));
        assertFalse(UpdateVergleich.neuerVerfuegbar("", "4.0-beta+EA26b"));
    }

    @Test
    void finalWirdBetaNutzernAlsUpdateGemeldet() {
        assertTrue(UpdateVergleich.neuerVerfuegbar("4.0-beta+EA25b", "4.0+EA25a"));
        assertTrue(UpdateVergleich.neuerVerfuegbar("4.0-beta+EA25b", "4.0+EA26a"));
    }

    @Test
    void betaWirdFinalNutzernNichtAlsUpdateGemeldet() {
        assertFalse(UpdateVergleich.neuerVerfuegbar("4.0+EA25a", "4.0-beta+EA25b"));
        assertFalse(UpdateVergleich.neuerVerfuegbar("4.0", "4.0-beta+EA26b"));
    }

    @Test
    void stabilereStufeGleicherBuildNummerIstUpdate() {
        assertTrue(UpdateVergleich.neuerVerfuegbar("4.0-beta+EA25c", "4.0-beta+EA25b"),
                "c (Beta) -> b (RC) ist Fortschritt");
        assertTrue(UpdateVergleich.neuerVerfuegbar("4.0-beta+EA25b", "4.0-beta+EA25a"),
                "b (RC) -> a (Final) ist Fortschritt");
        assertFalse(UpdateVergleich.neuerVerfuegbar("4.0-beta+EA25a", "4.0-beta+EA25b"),
                "Final -> RC ist kein Update");
        assertFalse(UpdateVergleich.neuerVerfuegbar("4.0-beta+EA25b", "4.0-beta+EA25c"),
                "RC -> Beta ist kein Update");
    }

    @Test
    void releaseCandidateLiegtZwischenBetaUndFinal() {
        assertTrue(UpdateVergleich.neuerVerfuegbar("4.0-beta+EA25b", "4.0-RC+EA26"));
        assertTrue(UpdateVergleich.neuerVerfuegbar("4.0-RC+EA26", "4.0+EA26"));
        assertFalse(UpdateVergleich.neuerVerfuegbar("4.0+EA26", "4.0-RC+EA26"));
        assertFalse(UpdateVergleich.neuerVerfuegbar("4.0-RC+EA26", "4.0-beta+EA26c"));
        assertFalse(UpdateVergleich.neuerVerfuegbar("4.0-RC+EA26", "4.0-RC+EA26"));
    }

    @Test
    void buildOhneBuchstabeIstNeuerAlsGleicherBuildMitBuchstabe() {
        assertTrue(UpdateVergleich.neuerVerfuegbar("4.0-RC+EA26b", "4.0-RC+EA26"));
        assertFalse(UpdateVergleich.neuerVerfuegbar("4.0-RC+EA26", "4.0-RC+EA26b"));
    }

    @Test
    void releasePfadRc29ZuFinalOhneDowngradeOderEndlosschleife() {
        assertTrue(UpdateVergleich.neuerVerfuegbar("4.0-RC+EA29", "4.0"),
                "Final (4.0) muss RC-Bestandsservern als Update gemeldet werden");
        assertFalse(UpdateVergleich.neuerVerfuegbar("4.0", "4.0-RC+EA29"),
                "Nach dem Update darf kein Downgrade gemeldet werden (keine Endlosschleife)");
        assertTrue(UpdateVergleich.neuerVerfuegbar("4.0", "4.0.1"),
                "Patch-Release ist ein Update");
        assertFalse(UpdateVergleich.neuerVerfuegbar("4.0.1", "4.0"),
                "Patch-Downgrade ist kein Update");
        assertTrue(UpdateVergleich.neuerVerfuegbar("4.0-RC+EA29", "4.0-RC+EA30"),
                "Höherer EA-Build im selben Kanal ist ein Update");
        assertFalse(UpdateVergleich.neuerVerfuegbar("4.0-RC+EA30", "4.0-RC+EA29"));
    }

    @Test
    void snapshotWirdFinalAlsUpdateGemeldet() {
        assertTrue(UpdateVergleich.neuerVerfuegbar("4.0-SNAPSHOT", "4.0"),
                "Final muss Snapshot-Nutzern als Update gemeldet werden");
        assertFalse(UpdateVergleich.neuerVerfuegbar("4.0", "4.0-SNAPSHOT"),
                "Snapshot darf kein Downgrade empfehlen");
    }

    @Test
    void unparbareVersionWirdNichtAlsUpdateEmpfohlen() {
        assertFalse(UpdateVergleich.neuerVerfuegbar("4.0-beta+EA25b", "2027.1-irgendwas"));
        assertFalse(UpdateVergleich.neuerVerfuegbar("2027.1-irgendwas", "4.0-beta+EA25b"));
    }

    @Test
    void hangarAntwortWirdGeparst() {
        String json = "{\"name\":\"4.0-beta+EA26b\",\"channel\":{\"name\":\"Release\"},"
                + "\"downloads\":[{\"platform\":\"VELOCITY\",\"downloadUrl\":\"https://example.test/v.jar\","
                + "\"hash\":\"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef\"}]}";
        HangarUpdateService.ReleaseInfo info =
                HangarUpdateService.parseLatestRelease(json, "autor", "slug", "Release");
        assertEquals("4.0-beta+EA26b", info.version(),
                "Der Parser darf der Version keinen Zusatztext anhängen.");
        assertTrue(UpdateVergleich.neuerVerfuegbar("4.0-beta+EA25b", info.version()));
    }

    @Test
    void hangarAntwortWaehltVersionsAehnlichesNamensFeld() {
        String json = "{\"project\":{\"name\":\"PunishSystem\"},\"channel\":{\"name\":\"Release\"},"
                + "\"name\":\"4.0-RC+EA30\",\"downloads\":[]}";
        HangarUpdateService.ReleaseInfo info =
                HangarUpdateService.parseLatestRelease(json, "autor", "slug", "Release");
        assertEquals("4.0-RC+EA30", info.version());
    }
}
