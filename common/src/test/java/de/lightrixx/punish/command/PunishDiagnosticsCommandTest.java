package de.lightrixx.punish.command;

import de.lightrixx.punish.core.BefehlsSender;
import de.lightrixx.punish.core.Konfiguration;
import de.lightrixx.punish.core.SpielerVerzeichnis;
import de.lightrixx.punish.model.VerstossStufe;
import de.lightrixx.punish.service.BroadcastService;
import de.lightrixx.punish.service.MessageService;
import de.lightrixx.punish.storage.PlayerCache;
import de.lightrixx.punish.service.ProtectionService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PunishDiagnosticsCommandTest {

    private Konfiguration gesundeConfig() {
        Konfiguration config = mock(Konfiguration.class);
        when(config.praefix()).thenReturn("<dark_gray>[<red>PunishSystem<dark_gray>]");
        when(config.nachricht(anyString())).thenAnswer(inv -> "<white>" + inv.getArgument(0));
        when(config.schutzFailClosed()).thenReturn(true);
        when(config.offlineAllowNeverSeen()).thenReturn(false);
        when(config.anzeigeZone()).thenReturn(ZoneId.of("Europe/Berlin"));
        when(config.timeoutMaxMinuten()).thenReturn(1440L);
        when(config.muteMaxMinuten()).thenReturn(43200L);
        when(config.banMaxMinuten()).thenReturn(525600L);
        when(config.banMinMinuten()).thenReturn(1440L);
        when(config.verstoesseMaxPunkte()).thenReturn(6);
        when(config.warnEskalationAktiv()).thenReturn(true);
        when(config.warnEskalationsSchwellen()).thenReturn(Map.of(3, VerstossStufe.LOW));
        return config;
    }

    private PunishDiagnosticsCommand befehl(Konfiguration config,
                                            PunishDiagnosticsCommand.Kontext kontext) {
        MessageService messages = new MessageService(config);
        ProtectionService schutz = mock(ProtectionService.class);
        PlayerCache cache = mock(PlayerCache.class);
        SpielerVerzeichnis verzeichnis = mock(SpielerVerzeichnis.class);
        return new PunishDiagnosticsCommand(messages, config, schutz, cache, verzeichnis, () -> kontext);
    }

    private PunishDiagnosticsCommand.Kontext gesund() {
        return new PunishDiagnosticsCommand.Kontext("Velocity", "4.0-test", "mysql", true,
                "MySQL aktiv", 2, true, "Kanal registriert", true, false, "deaktiviert",
                true, "einheitlich (Proxy=4.0-test)");
    }

    @Test
    void diagnose_okBeiGesunderConfig() {
        var cmd = befehl(gesundeConfig(), gesund());
        var zeilen = cmd.pruefe(gesund());
        assertFalse(zeilen.isEmpty());
        long warnungen = zeilen.stream().filter(z -> !z.ok()).count();
        assertEquals(0, warnungen);
    }

    @Test
    void diagnose_warntBeiYamlFailOpenNeverSeenUndFehlenderAnbindung() {
        Konfiguration config = gesundeConfig();
        when(config.schutzFailClosed()).thenReturn(false);
        when(config.offlineAllowNeverSeen()).thenReturn(true);
        var kontext = new PunishDiagnosticsCommand.Kontext("Velocity", "4.0-test", "yaml", true,
                "YAML aktiv", 0, false, "kein Brückenkanal", false, true,
                "aktiv, aber Dienst fehlt (Wortliste/Modell fehlt?)", false,
                "ABWEICHUNG (Proxy=4.0-test, lobby=4.0-alt)");
        var cmd = befehl(config, kontext);
        var zeilen = cmd.pruefe(kontext);
        assertTrue(zeilen.stream().filter(z -> !z.ok()).count() >= 5);
        assertTrue(zeilen.stream().anyMatch(z -> z.name().contains("Speicher-Backend") && !z.ok()));
        assertTrue(zeilen.stream().anyMatch(z -> z.name().contains("fail-closed") && !z.ok()));
        assertTrue(zeilen.stream().anyMatch(z -> z.name().contains("Offline") && !z.ok()));
        assertTrue(zeilen.stream().anyMatch(z -> z.name().contains("Serverliste") && !z.ok()));
        assertTrue(zeilen.stream().anyMatch(z -> z.name().contains("Bridge") && !z.ok()));
        assertTrue(zeilen.stream().anyMatch(z -> z.name().contains("LuckPerms") && !z.ok()));
        assertTrue(zeilen.stream().anyMatch(z -> z.name().contains("Chatfilter") && !z.ok()));
    }

    @Test
    void diagnose_warntBeiUngesunderPersistenz() {
        var kontext = new PunishDiagnosticsCommand.Kontext("Velocity", "4.0-test", "mysql", false,
                "MySQL aktiv – WARNUNG: 3 Persistenzfehler, Strafen ggf. nicht dauerhaft gespeichert",
                2, true, "Kanal registriert", true, false, "deaktiviert",
                true, "einheitlich (Proxy=4.0-test)");
        var cmd = befehl(gesundeConfig(), kontext);
        var zeilen = cmd.pruefe(kontext);
        assertTrue(zeilen.stream().anyMatch(z -> z.name().startsWith("Speicher (") && !z.ok()),
                "A-001: ungesunde Persistenz muss in /punish-diagnostics als WARN erscheinen");
    }

    @Test
    void diagnose_warntBeiAbweichenderBridgeVersion() {
        var kontext = new PunishDiagnosticsCommand.Kontext("Velocity", "v4.0-beta+EA16c", "mysql", true,
                "MySQL aktiv", 1, true, "Kanal registriert", true, false, "deaktiviert", false,
                "ABWEICHUNG (Proxy=v4.0-beta+EA16c, lobby=v4.0-beta+EA15c)");
        var cmd = befehl(gesundeConfig(), kontext);
        var zeilen = cmd.pruefe(kontext);
        assertTrue(zeilen.stream().anyMatch(z -> z.name().contains("Plugin-Versionen") && !z.ok()));
    }

    @Test
    void anSpieler_enthaeltPrefix() {
        Konfiguration config = gesundeConfig();
        when(config.nachricht("ziel-gemutet")).thenReturn("<red>Du wurdest gemutet.");
        MessageService messages = new MessageService(config);
        SpielerVerzeichnis verzeichnis = mock(SpielerVerzeichnis.class);
        UUID ziel = UUID.randomUUID();
        when(verzeichnis.onlineSpieler(ziel)).thenReturn(Optional.of(mock(de.lightrixx.punish.core.OnlineSpieler.class)));
        List<Component> gesendet = new ArrayList<>();
        doAnswer(inv -> {
            gesendet.add(inv.getArgument(1));
            return null;
        }).when(verzeichnis).mitteilen(eq(ziel), any(Component.class));
        messages.anSpieler(ziel, "ziel-gemutet", Map.of(), verzeichnis);
        assertFalse(gesendet.isEmpty());
        String klar = MiniMessage.miniMessage().serialize(gesendet.get(1));
        assertTrue(klar.contains("PunishSystem"), "Ziel-Nachricht ohne Prefix: " + klar);
    }

    @Test
    void broadcast_enthaeltPrefix() {
        Konfiguration config = gesundeConfig();
        when(config.broadcastAktiv("mute")).thenReturn(true);
        when(config.broadcastNachricht("mute")).thenReturn("<gray>[Mute] Test");
        var planer = mock(de.lightrixx.punish.core.AuftragsPlaner.class);
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(planer).aufgabe(any(Runnable.class));
        SpielerVerzeichnis verzeichnis = mock(SpielerVerzeichnis.class);
        UUID uuid = UUID.randomUUID();
        var online = mock(de.lightrixx.punish.core.OnlineSpieler.class);
        when(online.uuid()).thenReturn(uuid);
        when(verzeichnis.onlineSpieler()).thenReturn(List.of(online));
        when(verzeichnis.darfDirektAusfuehren()).thenReturn(true);
        when(verzeichnis.hatRecht(eq(uuid), anyString())).thenReturn(true);
        List<Component> gesendet = new ArrayList<>();
        doAnswer(inv -> {
            gesendet.add(inv.getArgument(1));
            return null;
        }).when(verzeichnis).mitteilen(eq(uuid), any(Component.class));
        BroadcastService broadcast = new BroadcastService(config, () -> true, planer, verzeichnis);
        broadcast.senden("mute", Map.of());
        assertFalse(gesendet.isEmpty());
        String klar = MiniMessage.miniMessage().serialize(gesendet.get(1));
        assertTrue(klar.contains("PunishSystem"), "Broadcast ohne Prefix: " + klar);
        assertTrue(klar.contains("Mute"), "Broadcast-Kategorie fehlt: " + klar);
    }

    @Test
    void diagnose_rechtIstDiagnostics() {
        var cmd = befehl(gesundeConfig(), gesund());
        BefehlsSender ohne = mock(BefehlsSender.class);
        when(ohne.hatRecht("punish.diagnostics")).thenReturn(false);
        assertFalse(cmd.hatRecht(ohne, new String[0]));
        BefehlsSender mit = mock(BefehlsSender.class);
        when(mit.hatRecht("punish.diagnostics")).thenReturn(true);
        assertTrue(cmd.hatRecht(mit, new String[0]));
    }
}
