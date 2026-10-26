package de.lightrixx.punish.audit;

import de.lightrixx.punish.command.DeleteIpCommand;
import de.lightrixx.punish.command.MuteAllCommand;
import de.lightrixx.punish.command.PunishHelpCommand;
import de.lightrixx.punish.command.VerstossCommand;
import de.lightrixx.punish.core.AuftragsPlaner;
import de.lightrixx.punish.core.BefehlsSender;
import de.lightrixx.punish.core.Konfiguration;
import de.lightrixx.punish.core.MessageKeys;
import de.lightrixx.punish.core.SpielerVerzeichnis;
import de.lightrixx.punish.model.CachedPlayer;
import de.lightrixx.punish.model.ParseResult;
import de.lightrixx.punish.service.AutoBannService;
import de.lightrixx.punish.service.BanService;
import de.lightrixx.punish.service.BroadcastService;
import de.lightrixx.punish.service.HistoryService;
import de.lightrixx.punish.service.MessageService;
import de.lightrixx.punish.service.MuteService;
import de.lightrixx.punish.service.ProtectionService;
import de.lightrixx.punish.service.VerstossService;
import de.lightrixx.punish.storage.IpBanRepository;
import de.lightrixx.punish.storage.IpMuteRepository;
import de.lightrixx.punish.storage.IpTimeoutRepository;
import de.lightrixx.punish.storage.LogWriter;
import de.lightrixx.punish.storage.PlayerCache;
import de.lightrixx.punish.storage.VerstossRepository;
import de.lightrixx.punish.util.PluginVersion;
import de.lightrixx.punish.util.TimeParser;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReleaseVorbereitungRegressionTest {

    private BefehlsSender senderMit(String... rechte) {
        BefehlsSender sender = mock(BefehlsSender.class);
        when(sender.hatRecht(anyString())).thenAnswer(inv -> {
            String knoten = inv.getArgument(0);
            for (String r : rechte) {
                if (r.equals(knoten)) {
                    return true;
                }
            }
            return false;
        });
        when(sender.istSpieler()).thenReturn(true);
        when(sender.name()).thenReturn("Mod");
        return sender;
    }

    private static String clickBefehl(Component komponente) {
        if (komponente == null) {
            return null;
        }
        if (komponente.clickEvent() != null) {
            return komponente.clickEvent().value();
        }
        for (Component kind : komponente.children()) {
            String treffer = clickBefehl(kind);
            if (treffer != null) {
                return treffer;
            }
        }
        return null;
    }

    // B-706: Help listet punish-gui und die punish.own-Pfade (self)
    @Test
    void hilfeZeigtGuiEintragUndOwnPfade() {
        var messageService = mock(MessageService.class);
        List<String> befehle = new ArrayList<>();
        when(messageService.komponenteOhnePraefix(anyString(), anyMap())).thenAnswer(inv -> {
            Map<?, ?> platzhalter = inv.getArgument(1);
            if (platzhalter.get("befehl") != null) {
                befehle.add(String.valueOf(platzhalter.get("befehl")));
            }
            return Component.empty();
        });
        when(messageService.komponente(anyString(), anyMap())).thenReturn(Component.empty());
        var cmd = new PunishHelpCommand(messageService, mock(Konfiguration.class),
                mock(ProtectionService.class), mock(PlayerCache.class), mock(SpielerVerzeichnis.class));

        cmd.ausfuehren(senderMit("punish.gui"), new String[0]);
        assertTrue(befehle.contains("punish-gui (psgui)"), "GUI-Eintrag fehlt: " + befehle);

        befehle.clear();
        cmd.ausfuehren(senderMit("punish.own"), new String[0]);
        assertTrue(befehle.contains("punish-status (psstatus)"), "Status-Eintrag für punish.own fehlt: " + befehle);
        assertTrue(befehle.contains("punish-history (pshistory)"), "History-Eintrag für punish.own fehlt: " + befehle);
        assertTrue(befehle.stream().noneMatch(b -> b.startsWith("ban ")),
                "punish.own darf keine Straf-Befehle sehen: " + befehle);
    }

    // F-012: Bestätigungsvorschlag enthält confirm genau einmal, Flags am Ende
    @Test
    void muteAllBestaetigungOhneDoppeltesConfirm() {
        var messageService = mock(MessageService.class);
        when(messageService.komponente(anyString(), anyMap())).thenReturn(Component.empty());
        var config = mock(Konfiguration.class);
        when(config.standardScope()).thenReturn(de.lightrixx.punish.model.ServerScope.global());
        when(config.bestaetigungMuteAll()).thenReturn(true);
        when(config.muteMaxMinuten()).thenReturn(525_600L);
        var timeParser = mock(TimeParser.class);
        when(timeParser.parsen("1d")).thenReturn(new ParseResult.Geltzeit(86_400_000L));
        var cmd = new MuteAllCommand(messageService, config, mock(ProtectionService.class),
                mock(PlayerCache.class), mock(MuteService.class), timeParser, null,
                mock(SpielerVerzeichnis.class));
        BefehlsSender sender = senderMit("punish.muteall");

        cmd.ausfuehren(sender, new String[]{"1d", "--global", "confirm", "Grund"});

        var captor = org.mockito.ArgumentCaptor.forClass(Component.class);
        verify(messageService).sendeKomponente(eq(sender), captor.capture());
        String vorschlag = clickBefehl(captor.getValue());
        assertEquals("/mute-all 1d confirm Grund --global", vorschlag);
    }

    // F-013: geschützte Ziele werden wie bei /alts abgewiesen
    @Test
    void deleteIpBlockiertGeschuetzteZiele() {
        var messageService = mock(MessageService.class);
        var cache = mock(PlayerCache.class);
        var protection = mock(ProtectionService.class);
        var ipBanRepo = mock(IpBanRepository.class);
        UUID uuid = UUID.randomUUID();
        when(cache.finden(uuid)).thenReturn(Optional.of(
                new CachedPlayer(uuid, "Opfer", "10.0.0.1", System.currentTimeMillis())));
        when(protection.istGeschuetzt(uuid, ProtectionService.IgnoreTyp.ALTS)).thenReturn(true);
        var cmd = new DeleteIpCommand(messageService, mock(Konfiguration.class), protection, cache,
                ipBanRepo, mock(IpMuteRepository.class), mock(IpTimeoutRepository.class),
                mock(SpielerVerzeichnis.class));
        BefehlsSender sender = senderMit("punish.deleteip");

        cmd.ausfuehren(sender, new String[]{uuid.toString()});

        verify(messageService).senden(sender, MessageKeys.GESCHUETZT);
        verify(ipBanRepo, never()).alleFuer(anyString());
    }

    // F-013: -s wird aus den Argumenten entfernt (Syntaxfehler statt IP-Eingabe)
    @Test
    void deleteIpEntferntSilentFlagAusArgumenten() {
        var messageService = mock(MessageService.class);
        var cmd = new DeleteIpCommand(messageService, mock(Konfiguration.class),
                mock(ProtectionService.class), mock(PlayerCache.class),
                mock(IpBanRepository.class), mock(IpMuteRepository.class),
                mock(IpTimeoutRepository.class), mock(SpielerVerzeichnis.class));
        BefehlsSender sender = senderMit("punish.deleteip", "punish.allow-silent-syntax");

        cmd.ausfuehren(sender, new String[]{"-s"});

        verify(messageService).senden(sender, MessageKeys.DELETE_IP_SYNTAX);
        verify(messageService, never()).senden(eq(sender), eq(MessageKeys.SPIELER_NICHT_GEFUNDEN), anyMap());
    }

    // F-013: alle drei Repositories sind Pflicht
    @Test
    void deleteIpVerlangtAlleRepositories() {
        assertThrows(NullPointerException.class, () -> new DeleteIpCommand(
                mock(MessageService.class), mock(Konfiguration.class), mock(ProtectionService.class),
                mock(PlayerCache.class), mock(IpBanRepository.class), null,
                mock(IpTimeoutRepository.class), mock(SpielerVerzeichnis.class)));
        assertThrows(NullPointerException.class, () -> new DeleteIpCommand(
                mock(MessageService.class), mock(Konfiguration.class), mock(ProtectionService.class),
                mock(PlayerCache.class), mock(IpBanRepository.class), mock(IpMuteRepository.class),
                null, mock(SpielerVerzeichnis.class)));
    }

    // F-014: Schutzfälle sind typisiert erkennbar (kein String-Sniffing)
    @Test
    void verstossServiceWirftTypisierteSchutzAusnahme() {
        UUID uuid = UUID.randomUUID();
        var schutz = mock(ProtectionService.class);
        when(schutz.istGeschuetzt(uuid, ProtectionService.IgnoreTyp.VERSTOSS)).thenReturn(true);
        var service = neuerVerstossService(schutz);
        assertThrows(VerstossService.ZielGeschuetztException.class,
                () -> service.hinzufuegen(uuid, "Opfer", "Grund", "Mod"));

        var unsicher = mock(ProtectionService.class);
        when(unsicher.istGeschuetzt(uuid, ProtectionService.IgnoreTyp.VERSTOSS))
                .thenThrow(new RuntimeException("Schutz fällt aus"));
        var serviceUnsicher = neuerVerstossService(unsicher);
        assertThrows(VerstossService.ZielGeschuetztException.class,
                () -> serviceUnsicher.hinzufuegen(uuid, "Opfer", "Grund", "Mod"));
    }

    @Test
    void verstossAddMeldetSchutzStattAutoBann() {
        var messageService = mock(MessageService.class);
        var cache = mock(PlayerCache.class);
        var verstossService = mock(VerstossService.class);
        UUID uuid = UUID.randomUUID();
        when(cache.finden(uuid)).thenReturn(Optional.of(
                new CachedPlayer(uuid, "Opfer", "10.0.0.1", System.currentTimeMillis())));
        when(verstossService.hinzufuegen(any(UUID.class), anyString(), anyString(), anyString(), any(), anyBoolean()))
                .thenThrow(new VerstossService.ZielGeschuetztException("geschützt"));
        var cmd = new VerstossCommand(messageService, mock(Konfiguration.class),
                mock(ProtectionService.class), cache, verstossService, null,
                mock(SpielerVerzeichnis.class));
        BefehlsSender sender = senderMit("punish.verstoss");

        cmd.ausfuehren(sender, new String[]{"add", uuid.toString(), "Grund"});

        verify(messageService).senden(sender, MessageKeys.GESCHUETZT);
        verify(messageService, never()).senden(eq(sender), eq(MessageKeys.AUTO_BANN_AKTIV), anyMap());
    }

    @Test
    void verstossAddMeldetAutoBannBeiAnderemFehler() {
        var messageService = mock(MessageService.class);
        var cache = mock(PlayerCache.class);
        var verstossService = mock(VerstossService.class);
        UUID uuid = UUID.randomUUID();
        when(cache.finden(uuid)).thenReturn(Optional.of(
                new CachedPlayer(uuid, "Opfer", "10.0.0.1", System.currentTimeMillis())));
        when(verstossService.hinzufuegen(any(UUID.class), anyString(), anyString(), anyString(), any(), anyBoolean()))
                .thenThrow(new IllegalStateException("Verstoß-Add bei aktivem Auto-Bann ist nicht erlaubt."));
        var cmd = new VerstossCommand(messageService, mock(Konfiguration.class),
                mock(ProtectionService.class), cache, verstossService, null,
                mock(SpielerVerzeichnis.class));
        BefehlsSender sender = senderMit("punish.verstoss");

        cmd.ausfuehren(sender, new String[]{"add", uuid.toString(), "Grund"});

        verify(messageService).senden(eq(sender), eq(MessageKeys.AUTO_BANN_AKTIV), anyMap());
        verify(messageService, never()).senden(sender, MessageKeys.GESCHUETZT);
    }

    // F-015: gemischte Suffix-Schreibweisen werden akzeptiert und richtig einsortiert
    @Test
    void pluginVersionAkzeptiertGemischteSuffixSchreibweisen() {
        assertEquals(0, PluginVersion.parse("v4.0-Beta+EA01a").kanalRang());
        assertEquals(0, PluginVersion.parse("4.0-ALPHA").kanalRang());
        assertEquals(1, PluginVersion.parse("4.0-Rc").kanalRang());
        assertEquals(1, PluginVersion.parse("4.0-RC").kanalRang());
        assertEquals(2, PluginVersion.parse("4.0").kanalRang());
        assertTrue(PluginVersion.parse("4.0-Beta+EA01a").istBeta());
        assertTrue(PluginVersion.parse("4.0-Rc").istReleaseCandidate());
    }

    private VerstossService neuerVerstossService(ProtectionService schutz) {
        return new VerstossService(mock(VerstossRepository.class), mock(BanService.class),
                mock(AutoBannService.class), mock(Konfiguration.class), mock(HistoryService.class),
                mock(BroadcastService.class), mock(LogWriter.class), mock(MessageService.class),
                mock(AuftragsPlaner.class), schutz, mock(SpielerVerzeichnis.class));
    }
}
