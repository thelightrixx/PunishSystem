package de.lightrixx.punish.bridge;

import de.lightrixx.punish.net.Protokoll;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TabVorschlagIpTest {

    private static CommandSender sender(String recht) {
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission(anyString())).thenAnswer(anfrage ->
                recht.equals(anfrage.getArgument(0)));
        return sender;
    }

    private static BridgeBefehlWeiterleitung weiterleitung(String befehl, String recht) {
        return new BridgeBefehlWeiterleitung(mock(KanalClient.class), befehl, recht, true, null);
    }

    @Test
    void unbanIpSchlaegtKeineSpielernamenVor() {
        BridgeBefehlWeiterleitung befehl = weiterleitung("unban-ip", "punish.unbanip");
        CommandSender sender = sender("punish.unbanip");

        assertEquals(List.of(), befehl.vorschlaege(sender, new String[]{""}));
        assertEquals(List.of(), befehl.vorschlaege(sender, new String[]{"Max"}));
    }

    @Test
    void untimeoutIpSchlaegtKeineSpielernamenVor() {
        BridgeBefehlWeiterleitung befehl = weiterleitung("untimeout-ip", "punish.untimeoutip");
        CommandSender sender = sender("punish.untimeoutip");

        assertEquals(List.of(), befehl.vorschlaege(sender, new String[]{""}));
    }

    @Test
    void unmuteIpSchlaegtAktiveIpsVor() {
        BridgeBefehlWeiterleitung befehl = weiterleitung("unmute-ip", "punish.unmuteip");
        IpMuteCache cache = new IpMuteCache();
        cache.ersetzeAlle(List.of(new Protokoll.IpMuteEintrag("203.0.113.7", "Test", "Tester",
                System.currentTimeMillis(), System.currentTimeMillis() + 600_000L, "global")));
        befehl.mitStrafCaches(null, null, null, cache);

        List<String> vorschlaege = befehl.vorschlaege(sender("punish.unmuteip"), new String[]{"203"});

        assertEquals(List.of("203.0.113.7"), vorschlaege);
    }

    @Test
    void statusVervollstaendigungBietetKeineKicksAn() {
        BridgeBefehlWeiterleitung befehl = weiterleitung("punish-status", "punish.status");

        List<String> vorschlaege = befehl.vorschlaege(sender("punish.status"),
                new String[]{"Ziel", "filter:"});

        assertTrue(vorschlaege.stream().noneMatch(v -> v.toLowerCase().contains("kicks")),
                "filter:kicks gehört nicht zum Status-Befehl: " + vorschlaege);
        assertTrue(vorschlaege.contains("filter:bans"), "Andere Filter bleiben verfügbar: " + vorschlaege);
    }

    @Test
    void historyVervollstaendigungBietetKicksAn() {
        BridgeBefehlWeiterleitung befehl = weiterleitung("punish-history", "punish.history");

        List<String> vorschlaege = befehl.vorschlaege(sender("punish.history"),
                new String[]{"Ziel", "filter:"});

        assertTrue(vorschlaege.contains("filter:kicks"), "History bietet Kicks weiter an: " + vorschlaege);
    }
}
