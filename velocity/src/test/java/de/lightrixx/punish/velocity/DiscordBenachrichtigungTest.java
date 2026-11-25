package de.lightrixx.punish.velocity;

import de.lightrixx.punish.model.HistoryEntry;
import de.lightrixx.punish.model.HistoryType;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class DiscordBenachrichtigungTest {
    @Test
    void stilleStrafenUndStilleReportBearbeitungWerdenNichtGesendet() throws Exception {
        var angekommen = new CountDownLatch(1);
        var texte = new java.util.concurrent.CopyOnWriteArrayList<String>();
        try (var discord = new DiscordBenachrichtigung(() -> Map.of("aktiv", true,
                "webhook-url", "https://discord.com/api/webhooks/123/beispiel"), mock(ModerationsDienst.class),
                (uri, json) -> {
                    texte.add(json);
                    if (json.contains("Normale Strafe")) angekommen.countDown();
                    return new DiscordBenachrichtigung.Antwort(200, 0);
                })) {
            UUID uuid = UUID.randomUUID();
            discord.history(new de.lightrixx.punish.model.HistoryEreignis(uuid, history(), true));
            discord.report(new de.lightrixx.punish.model.ReportRecord(1, uuid, "Spieler", UUID.randomUUID(), "Melder",
                    "Stiller Report", "lobby", 1, de.lightrixx.punish.model.ReportStatus.ERLEDIGT, "Mod", 2, ""), true);
            discord.history(new de.lightrixx.punish.model.HistoryEreignis(uuid,
                    new HistoryEntry(2, HistoryType.WARN_ADD, "Mod", "Spieler", "Normale Strafe", 1, null, true), false));
            assertTrue(angekommen.await(2, TimeUnit.SECONDS));
            assertEquals(1, texte.size());
            assertFalse(texte.getFirst().contains("Stiller Report"));
        }
    }
    private HistoryEntry history() {
        return new HistoryEntry(1, HistoryType.MUTE, "Mod", "Spieler", "Grund", 1, 10L, true, "global", "lobby");
    }

    @Test
    void standardIstAusUndLeereEreignislisteSchaltetAlleMeldungenAb() {
        assertFalse(DiscordBenachrichtigung.aktiv(Map.of(), "mute"));
        assertTrue(DiscordBenachrichtigung.aktiv(Map.of("aktiv", true), "mute"));
        assertFalse(DiscordBenachrichtigung.aktiv(Map.of("aktiv", true, "ereignisse", java.util.List.of()), "mute"));
        assertFalse(DiscordBenachrichtigung.aktiv(Map.of("aktiv", true, "ereignisse", java.util.List.of("report")), "mute"));
        assertFalse(DiscordBenachrichtigung.aktiv(Map.of("aktiv", true,
                "ausgeschlossene-typen", java.util.List.of("BAN_IP")), "ip", "BAN_IP"));
        assertTrue(DiscordBenachrichtigung.aktiv(Map.of("aktiv", true,
                "ausgeschlossene-typen", java.util.List.of("BAN_IP")), "ip", "IP_MUTE"));
    }

    @Test
    void jsonEscapedNutzereingabenUndUnterbindetErwaehnungen() {
        String json = DiscordBenachrichtigung.json("Zitat \" \\ @everyone\nNeue Zeile");
        var daten = ModerationsSpeicher.dekodiere(json);
        assertEquals("Zitat \" \\ @everyone\nNeue Zeile", daten.get("content"));
        assertEquals(Map.of("parse", java.util.List.of()), daten.get("allowed_mentions"));
        var kurz = ModerationsSpeicher.dekodiere(DiscordBenachrichtigung.json("x".repeat(3000)));
        assertEquals(1900, String.valueOf(kurz.get("content")).length());
    }

    @Test
    void nurOffizielleHttpsWebhookAdressenOhneUmleitungenOderZugangsdaten() {
        assertNotNull(DiscordBenachrichtigung.webhook("https://discord.com/api/webhooks/123/beispiel"));
        for (String adresse : java.util.List.of("http://discord.com/api/webhooks/123/beispiel",
                "https://example.org/api/webhooks/123/beispiel", "https://discord.com.evil.test/api/webhooks/123/beispiel",
                "https://user@discord.com/api/webhooks/123/beispiel", "https://discord.com/api/webhooks/123/beispiel?redirect=x")) {
            assertThrows(IllegalArgumentException.class, () -> DiscordBenachrichtigung.webhook(adresse));
        }
    }

    @Test
    void rateLimitWirdImHintergrundBegrenztWiederholt() throws Exception {
        CountDownLatch angekommen = new CountDownLatch(1);
        AtomicInteger versuche = new AtomicInteger();
        try (var discord = new DiscordBenachrichtigung(() -> Map.of("aktiv", true,
                "webhook-url", "https://discord.com/api/webhooks/123/beispiel"), mock(ModerationsDienst.class),
                (uri, json) -> {
                    if (versuche.incrementAndGet() == 1) return new DiscordBenachrichtigung.Antwort(429, 1000);
                    angekommen.countDown();
                    return new DiscordBenachrichtigung.Antwort(200, 0);
                })) {
            discord.history(UUID.randomUUID(), history());
            assertTrue(angekommen.await(4, TimeUnit.SECONDS));
            assertEquals(2, versuche.get());
        }
    }

    @Test
    void webhookLimitPausiertAuchAndereBereitsWartendeMeldungen() throws Exception {
        assertEquals(65_000L, DiscordBenachrichtigung.warteMillis(65));
        assertEquals(1337_000L, DiscordBenachrichtigung.warteMillis(1337));
        var zugestellt = new CountDownLatch(2);
        var zeiten = new java.util.concurrent.CopyOnWriteArrayList<Long>();
        try (var discord = new DiscordBenachrichtigung(() -> Map.of("aktiv", true,
                "webhook-url", "https://discord.com/api/webhooks/123/beispiel"), mock(ModerationsDienst.class),
                (uri, json) -> {
                    zeiten.add(System.nanoTime());
                    if (zeiten.size() == 1) return new DiscordBenachrichtigung.Antwort(429, 1000);
                    zugestellt.countDown();
                    return new DiscordBenachrichtigung.Antwort(200, 0);
                })) {
            discord.history(UUID.randomUUID(), history());
            discord.history(UUID.randomUUID(), history());
            assertTrue(zugestellt.await(4, TimeUnit.SECONDS));
            assertEquals(3, zeiten.size());
            assertTrue(zeiten.get(1) - zeiten.get(0) >= TimeUnit.MILLISECONDS.toNanos(950));
        }
    }

    @Test
    void ipZielWirdImAusgehendenTextAusgeblendet() throws Exception {
        CountDownLatch angekommen = new CountDownLatch(1);
        AtomicReference<String> gesendet = new AtomicReference<>();
        try (var discord = new DiscordBenachrichtigung(() -> Map.of("aktiv", true,
                "webhook-url", "https://discord.com/api/webhooks/123/beispiel"), mock(ModerationsDienst.class),
                (uri, json) -> {
                    gesendet.set(json);
                    angekommen.countDown();
                    return new DiscordBenachrichtigung.Antwort(200, 0);
                })) {
            discord.history(UUID.randomUUID(), new HistoryEntry(1, HistoryType.BAN_IP, "Mod", "203.0.113.44",
                    "Grund", 1, null, true));
            assertTrue(angekommen.await(2, TimeUnit.SECONDS));
            assertFalse(gesendet.get().contains("203.0.113.44"));
        }
    }
}
