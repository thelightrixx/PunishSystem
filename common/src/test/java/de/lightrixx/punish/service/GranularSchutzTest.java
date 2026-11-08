package de.lightrixx.punish.service;

import de.lightrixx.punish.core.BefehlsSender;
import de.lightrixx.punish.core.OfflineVerzeichnis;
import de.lightrixx.punish.core.OnlineSpieler;
import de.lightrixx.punish.core.Permissions;
import de.lightrixx.punish.core.SpielerVerzeichnis;
import de.lightrixx.punish.hook.LuckPermsZugang;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GranularSchutzTest {

    private static ProtectionService schutzMitRechten(UUID uuid, String name, boolean wildcard, String... nodes) {
        var online = new OnlineSpieler(uuid, name, "1.2.3.4", false);
        SpielerVerzeichnis verzeichnis = mock(SpielerVerzeichnis.class);
        when(verzeichnis.onlineSpieler(uuid)).thenReturn(Optional.of(online));
        when(verzeichnis.hatRecht(eq(uuid), eq(Permissions.IGNORE_STAR.node()))).thenReturn(wildcard);
        for (var typ : ProtectionService.IgnoreTyp.values()) {
            boolean hat = false;
            for (String node : nodes) {
                if (typ.node().equals(node)) {
                    hat = true;
                    break;
                }
            }
            when(verzeichnis.hatRecht(eq(uuid), eq(typ.node()))).thenReturn(hat);
        }
        OfflineVerzeichnis offline = mock(OfflineVerzeichnis.class);
        when(offline.findeNachUuid(any(UUID.class))).thenReturn(Optional.empty());
        LuckPermsZugang zugang = mock(LuckPermsZugang.class);
        return new ProtectionService(verzeichnis, offline, zugang);
    }

    @Test
    void knotenNamenSindAsciiUndErwartet() {
        assertEquals("punish.ignore.*", Permissions.IGNORE_STAR.node());
        assertEquals("punish.ignore.ban", ProtectionService.IgnoreTyp.BAN.node());
        assertEquals("punish.ignore.mute", ProtectionService.IgnoreTyp.MUTE.node());
        assertEquals("punish.ignore.timeout", ProtectionService.IgnoreTyp.TIMEOUT.node());
        assertEquals("punish.ignore.kick", ProtectionService.IgnoreTyp.KICK.node());
        assertEquals("punish.ignore.warn", ProtectionService.IgnoreTyp.WARN.node());
        assertEquals("punish.ignore.verstoss", ProtectionService.IgnoreTyp.VERSTOSS.node());
        assertEquals("punish.ignore.note", ProtectionService.IgnoreTyp.NOTE.node());
        assertEquals("punish.ignore.alts", ProtectionService.IgnoreTyp.ALTS.node());
        assertEquals("punish.ignore.report", ProtectionService.IgnoreTyp.REPORT.node());
    }

    @Test
    void nurPassenderTypSchuetzt() {
        UUID uuid = UUID.randomUUID();
        ProtectionService schutz = schutzMitRechten(uuid, "Ziel", false, "punish.ignore.ban");
        assertTrue(schutz.istGeschuetzt(uuid, ProtectionService.IgnoreTyp.BAN));
        assertFalse(schutz.istGeschuetzt(uuid, ProtectionService.IgnoreTyp.MUTE));
        assertFalse(schutz.istGeschuetzt(uuid, ProtectionService.IgnoreTyp.KICK));
    }

    @Test
    void wildcardSchuetztAlleTypen() {
        UUID uuid = UUID.randomUUID();
        ProtectionService schutz = schutzMitRechten(uuid, "Ziel", true);
        for (var typ : ProtectionService.IgnoreTyp.values()) {
            assertTrue(schutz.istGeschuetzt(uuid, typ), "Wildcard muss " + typ + " schützen");
        }
    }

    @Test
    void altesGlobalesIgnoreSchuetztNichtMehr() {
        UUID uuid = UUID.randomUUID();
        var online = new OnlineSpieler(uuid, "Ziel", "1.2.3.4", false);
        SpielerVerzeichnis verzeichnis = mock(SpielerVerzeichnis.class);
        when(verzeichnis.onlineSpieler(uuid)).thenReturn(Optional.of(online));
        when(verzeichnis.hatRecht(eq(uuid), anyString())).thenAnswer(inv -> {
            String knoten = inv.getArgument(1);
            return "punish.ignore".equals(knoten);
        });
        OfflineVerzeichnis offline = mock(OfflineVerzeichnis.class);
        when(offline.findeNachUuid(any(UUID.class))).thenReturn(Optional.empty());
        LuckPermsZugang zugang = mock(LuckPermsZugang.class);
        ProtectionService schutz = new ProtectionService(verzeichnis, offline, zugang);
        assertFalse(schutz.istGeschuetzt(uuid, ProtectionService.IgnoreTyp.BAN));
        assertFalse(schutz.istGeschuetzt(uuid));
    }

    @Test
    void offlineWildcardOderTypSchuetzt() {
        UUID uuid = UUID.randomUUID();
        var extern = new OfflineVerzeichnis.ExternSpieler(
                uuid, "Alt", true, false, System.currentTimeMillis(), true);
        SpielerVerzeichnis verzeichnis = mock(SpielerVerzeichnis.class);
        when(verzeichnis.onlineSpieler(uuid)).thenReturn(Optional.empty());
        OfflineVerzeichnis offline = mock(OfflineVerzeichnis.class);
        when(offline.findeNachUuid(uuid)).thenReturn(Optional.of(extern));
        when(offline.istMainThread()).thenReturn(false);
        LuckPermsZugang zugang = mock(LuckPermsZugang.class);
        when(zugang.isAvailable()).thenReturn(true);
        when(zugang.offlineStatusFuerMehrere(eq(uuid), anyList()))
                .thenAnswer(inv -> {
                    java.util.List<String> nodes = inv.getArgument(1);
                    java.util.List<LuckPermsZugang.OfflineStatus> ergebnis = new java.util.ArrayList<>();
                    for (String node : nodes) {
                        ergebnis.add("punish.ignore.mute".equals(node)
                                ? LuckPermsZugang.OfflineStatus.GESCHUETZT
                                : LuckPermsZugang.OfflineStatus.NICHT_GESCHUETZT);
                    }
                    return ergebnis;
                });
        ProtectionService schutz = new ProtectionService(verzeichnis, offline, zugang);
        assertEquals(ProtectionService.SchutzErgebnis.GESCHUETZT,
                schutz.pruefeSchutz(uuid, ProtectionService.IgnoreTyp.MUTE));
        assertEquals(ProtectionService.SchutzErgebnis.NICHT_GESCHUETZT,
                schutz.pruefeSchutz(uuid, ProtectionService.IgnoreTyp.BAN));
    }

    @Test
    void selbstbestrafungStandardBlockiertUndPerFlagErlaubt() {
        UUID uuid = UUID.randomUUID();
        ProtectionService schutz = schutzMitRechten(uuid, "Selbst", false);
        BefehlsSender sender = mock(BefehlsSender.class);
        when(sender.istSpieler()).thenReturn(true);
        when(sender.spielerUuid()).thenReturn(uuid);

        assertFalse(schutz.istSelbstbestrafungErlaubt());
        assertFalse(schutz.istErlaubteSelbstbestrafung(sender, uuid));
        assertTrue(schutz.istSelbstbestrafung(sender, uuid));

        schutz.setSelbstbestrafungErlaubt(true);
        assertTrue(schutz.istSelbstbestrafungErlaubt());
        assertTrue(schutz.istErlaubteSelbstbestrafung(sender, uuid));
        assertFalse(schutz.istErlaubteSelbstbestrafung(sender, UUID.randomUUID()));
        assertFalse(schutz.istSelbstbestrafung(sender, uuid));
    }
    @Test
    void erlaubteSelbstbestrafungErreichtAuchServiceTrotzIgnoreRecht() {
        UUID uuid = UUID.randomUUID();
        ProtectionService schutz = schutzMitRechten(uuid, "Selbst", true);
        var sender = mock(BefehlsSender.class);
        when(sender.istSpieler()).thenReturn(true);
        when(sender.spielerUuid()).thenReturn(uuid);
        var repository = mock(de.lightrixx.punish.storage.MuteRepository.class);
        when(repository.mutenWennFrei(any())).thenReturn(true);
        var verzeichnis = mock(SpielerVerzeichnis.class);
        when(verzeichnis.darfDirektAusfuehren()).thenReturn(true);
        var service = new MuteService(repository, mock(HistoryService.class), mock(BroadcastService.class),
                mock(de.lightrixx.punish.storage.LogWriter.class), mock(MessageService.class),
                mock(de.lightrixx.punish.core.AuftragsPlaner.class), schutz, verzeichnis);
        var scope = de.lightrixx.punish.model.ServerScope.global();
        assertFalse(service.muten(uuid, "Selbst", 60_000L, "Test", "Selbst", scope, "lobby", false, sender));
        schutz.setSelbstbestrafungErlaubt(true);
        assertTrue(service.muten(uuid, "Selbst", 60_000L, "Test", "Selbst", scope, "lobby", false, sender));
        assertFalse(service.muten(uuid, "Selbst", 60_000L, "Test", "Selbst", scope, "lobby", false));
        when(sender.spielerUuid()).thenReturn(UUID.randomUUID());
        assertFalse(service.muten(uuid, "Selbst", 60_000L, "Test", "Selbst", scope, "lobby", false, sender));
        org.mockito.Mockito.verify(repository).mutenWennFrei(any());
    }
    @Test
    void banBefehlUebermitteltIdentitaetFuerErlaubteSelbstbestrafung() {
        UUID uuid = UUID.randomUUID();
        var schutz = schutzMitRechten(uuid, "Selbst", true);
        schutz.setSelbstbestrafungErlaubt(true);
        var sender = mock(BefehlsSender.class);
        when(sender.istSpieler()).thenReturn(true);
        when(sender.spielerUuid()).thenReturn(uuid);
        when(sender.name()).thenReturn("Selbst");
        var verzeichnis = mock(SpielerVerzeichnis.class);
        when(verzeichnis.onlineSpielerNachName("Selbst")).thenReturn(Optional.of(
                new OnlineSpieler(uuid, "Selbst", "1.2.3.4", false)));
        var config = mock(de.lightrixx.punish.core.Konfiguration.class);
        when(config.standardScope()).thenReturn(de.lightrixx.punish.model.ServerScope.global());
        when(config.banMaxMinuten()).thenReturn(60L);
        var repository = mock(de.lightrixx.punish.storage.BanRepository.class);
        when(repository.bannenWennFreiDauerhaft(any()))
                .thenReturn(de.lightrixx.punish.storage.BanRepository.BannPersistenz.ERFOLG);
        var service = new BanService(repository, mock(HistoryService.class), mock(BroadcastService.class),
                mock(de.lightrixx.punish.storage.LogWriter.class), schutz, mock(BanKickHelper.class), verzeichnis);
        var befehl = new de.lightrixx.punish.command.BanCommand(mock(MessageService.class), config, schutz,
                mock(de.lightrixx.punish.storage.PlayerCache.class), service,
                new de.lightrixx.punish.util.TimeParser(), mock(de.lightrixx.punish.core.AuftragsPlaner.class),
                verzeichnis);
        befehl.ausfuehren(sender, new String[]{"Selbst", "1m", "Test", "--global"});
        org.mockito.Mockito.verify(repository).bannenWennFreiDauerhaft(org.mockito.ArgumentMatchers.argThat(
                bann -> bann.spielerUuid().equals(uuid) && bann.grund().equals("Test")));
    }
}
