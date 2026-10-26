package de.lightrixx.punish.update;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HangarRedirectTest {

    @Test
    void httpsBleibtErlaubt() {
        assertDoesNotThrow(() -> HangarUpdateService.pruefeHttpsNachRedirect(
                java.net.URI.create("https://hangar.papermc.io/api/v1/x")));
    }

    @Test
    void httpFremderHostUndSubdomainTrickWerdenAbgebrochen() {
        assertThrows(IllegalArgumentException.class, () -> HangarUpdateService.pruefeHttpsNachRedirect(
                java.net.URI.create("https://boese.example/api/v1/x")));
        assertThrows(IllegalArgumentException.class, () -> HangarUpdateService.pruefeHttpsNachRedirect(
                java.net.URI.create("https://hangar.papermc.io.evil/api/v1/x")));
    }

    @Test
    void grossUndKleinschreibungDesHostsBleibtErlaubt() {
        assertDoesNotThrow(() -> HangarUpdateService.pruefeHttpsNachRedirect(
                java.net.URI.create("https://HANGAR.PAPERMC.IO/api/v1/x")));
    }

    @Test
    void httpRedirectWirdAbgebrochen() {
        assertThrows(IllegalArgumentException.class, () -> HangarUpdateService.pruefeHttpsNachRedirect(
                java.net.URI.create("http://hangar.papermc.io/api/v1/x")));
    }

    @Test
    void nullZielWirdAbgebrochen() {
        assertThrows(IllegalArgumentException.class,
                () -> HangarUpdateService.pruefeHttpsNachRedirect(null));
    }

    @Test
    void hangarCdnBleibtErlaubt() {
        assertDoesNotThrow(() -> HangarUpdateService.pruefeHttpsNachRedirect(
                java.net.URI.create("https://hangarcdn.papermc.io/versions/x.jar")));
    }

    @Test
    void standardAbrufLehntHttpVorDemNetzwerkAb() {
        assertThrows(IllegalArgumentException.class, () -> HangarUpdateService.standardAbruf()
                .ladeBytes("http://hangar.papermc.io/api/v1/x", 1024L));
    }
}
