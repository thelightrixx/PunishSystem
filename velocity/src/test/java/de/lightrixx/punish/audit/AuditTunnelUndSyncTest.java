package de.lightrixx.punish.audit;

import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import de.lightrixx.punish.model.MuteRecord;
import de.lightrixx.punish.model.ServerScope;
import de.lightrixx.punish.storage.MuteRepository;
import de.lightrixx.punish.velocity.BrueckenKanal;
import de.lightrixx.punish.velocity.PunishVelocity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditTunnelUndSyncTest {

    private record Umgebung(ProxyServer proxy, CommandManager befehle, BrueckenKanal kanal) {
    }

    private static Umgebung umgebung(MuteRepository muteRepository) {
        ProxyServer proxy = mock(ProxyServer.class);
        CommandManager befehle = mock(CommandManager.class);
        when(proxy.getCommandManager()).thenReturn(befehle);
        MuteRepository repo = muteRepository != null ? muteRepository : mock(MuteRepository.class);
        BrueckenKanal kanal = new BrueckenKanal(mock(PunishVelocity.class), proxy,
                mock(org.slf4j.Logger.class), repo, null);
        kanal.setBridgeSecrets(java.util.Map.of("lobby", "punish-test-secret-lange"));
        return new Umgebung(proxy, befehle, kanal);
    }

    private static RegisteredServer server(String name) {
        RegisteredServer server = mock(RegisteredServer.class);
        ServerInfo info = mock(ServerInfo.class);
        when(info.getName()).thenReturn(name);
        when(server.getServerInfo()).thenReturn(info);
        return server;
    }

    private static Player spielerAuf(RegisteredServer aktuell) {
        Player spieler = mock(Player.class);
        when(spieler.getUsername()).thenReturn("ModX");
        when(spieler.hasPermission("punish.ban")).thenReturn(true);
        if (aktuell == null) {
            when(spieler.getCurrentServer()).thenReturn(Optional.empty());
        } else {
            ServerConnection verbindung = mock(ServerConnection.class);
            when(verbindung.getServer()).thenReturn(aktuell);
            when(spieler.getCurrentServer()).thenReturn(Optional.of(verbindung));
        }
        return spieler;
    }

    @Test
    @DisplayName("Kontrolle C-S1.1: Aktion von fremdem Server wird abgewiesen")
    void tunnelFremderServerBlockiert() {
        Umgebung u = umgebung(null);
        RegisteredServer evil = server("evil");
        RegisteredServer good = server("good");
        Player spieler = spielerAuf(good);
        UUID uuid = UUID.randomUUID();
        when(u.proxy().getPlayer(uuid)).thenReturn(Optional.of(spieler));

        u.kanal().fuehreAus(uuid, "ban Notch 7d X", evil);
        verify(u.befehle(), never()).executeAsync(any(), anyString());
    }

    @Test
    @DisplayName("Kontrolle C-S1.1: Aktion vom eigenen Server läuft")
    void tunnelEigenerServerErlaubt() {
        Umgebung u = umgebung(null);
        RegisteredServer evil = server("evil");
        Player spieler = spielerAuf(evil);
        UUID uuid = UUID.randomUUID();
        when(u.proxy().getPlayer(uuid)).thenReturn(Optional.of(spieler));

        u.kanal().fuehreAus(uuid, "ban Notch 7d X", evil);
        verify(u.befehle()).executeAsync(any(), eq("ban Notch 7d X"));
    }

    @Test
    @DisplayName("Fix C-S1.1: ohne aktuellen Server (Limbo/Wechsel) wird die Aktion fail-closed abgewiesen")
    void tunnelLimboUmgehtServerBindung() {
        Umgebung u = umgebung(null);
        RegisteredServer evil = server("evil");
        Player spieler = spielerAuf(null);
        UUID uuid = UUID.randomUUID();
        when(u.proxy().getPlayer(uuid)).thenReturn(Optional.of(spieler));

        u.kanal().fuehreAus(uuid, "ban Notch 7d X", evil);
        verify(u.befehle(), never()).executeAsync(any(), anyString());
    }

    @Test
    @DisplayName("Fix C-K2.1: mute-all/unmute-all/timeout-all/untimeout-all sind in der Tunnel-Allowlist")
    void cK21FehlendeAllBefehleInAllowlist() throws Exception {
        java.lang.reflect.Method abbild = BrueckenKanal.class
                .getDeclaredMethod("bridgeErlaubnisFuer", String.class);
        abbild.setAccessible(true);

        assertEquals("punish.banall", abbild.invoke(null, "ban-all 7d x"),
                "ban-all ist gelistet (Kontrolle)");
        assertEquals("punish.muteall", abbild.invoke(null, "mute-all 10m x"),
                "Fix B2: mute-all wird wie in PunishBridge.java:195 getunnelt auch erlaubt");
        assertEquals("punish.unmuteall", abbild.invoke(null, "unmute-all x"), "Fix B2: unmute-all");
        assertEquals("punish.timeoutall", abbild.invoke(null, "timeout-all 10m x"), "Fix B2: timeout-all");
        assertEquals("punish.untimeoutall", abbild.invoke(null, "untimeout-all x"), "Fix B2: untimeout-all");
    }

    @Test
    @DisplayName("Fix C-T1.1 + Audit-NET: Feldübergröße weicht auf Einzel-Sync aus (kein Verwerfen aller Mutes)")
    void syncFeldueberGroesseWeichtAufEinzelSyncAus() throws Exception {
        MuteRepository repo = mock(MuteRepository.class);
        long jetzt = System.currentTimeMillis();
        List<MuteRecord> eintraege = new ArrayList<>();
        String langName = "n".repeat(30_000);
        for (int i = 0; i < 200; i++) {
            eintraege.add(new MuteRecord(UUID.randomUUID(), langName, "Grund", "Mod",
                    jetzt - 1000L, jetzt + 1_000_000L, ServerScope.global()));
        }
        when(repo.alle()).thenReturn(eintraege);
        Umgebung u = umgebung(repo);
        RegisteredServer ziel = server("lobby");
        List<byte[]> rahmen = new ArrayList<>();
        when(ziel.sendPluginMessage(any(ChannelIdentifier.class), any(byte[].class)))
                .thenAnswer(einwurf -> {
                    rahmen.add(einwurf.getArgument(1, byte[].class));
                    return true;
                });

        u.kanal().mutePushAn(ziel);

        // 2 Generation-Rahmen + 200 Einzel-Mutes (je unter MAX_NUTZDATEN) + 3 Ban/Timeout/Freeze
        verify(ziel, times(205)).sendPluginMessage(any(ChannelIdentifier.class), any(byte[].class));

        java.util.Set<UUID> gesendet = new java.util.HashSet<>();
        int beginn = 0;
        int ende = 0;
        for (byte[] roh : rahmen) {
            var paket = de.lightrixx.punish.net.Protokoll.paket(roh);
            switch (paket.typ()) {
                case de.lightrixx.punish.net.Protokoll.MUTE_SYNC ->
                        de.lightrixx.punish.net.Protokoll.leseMuteSync(paket.nutzdaten())
                                .forEach(eintrag -> gesendet.add(eintrag.uuid()));
                case de.lightrixx.punish.net.Protokoll.MUTE_SETZEN -> gesendet.add(
                        de.lightrixx.punish.net.Protokoll.leseMute(paket.nutzdaten()).uuid());
                case de.lightrixx.punish.net.Protokoll.SYNC_BEGIN -> beginn++;
                case de.lightrixx.punish.net.Protokoll.SYNC_END -> ende++;
                default -> {
                }
            }
        }
        java.util.Set<UUID> erwartet = new java.util.HashSet<>();
        for (MuteRecord eintrag : eintraege) {
            erwartet.add(eintrag.spielerUuid());
        }
        assertEquals(erwartet, gesendet,
                "Der Einzel-Sync-Fallback darf keinen Mute verlieren");
        assertEquals(beginn, ende, "SYNC_BEGIN und SYNC_END müssen paarig sein");
    }

    @Test
    @DisplayName("Kontrolle C-T1.1: 200 normale Mutes erzeugen genau 5 Sync-Frames")
    void syncNormalSendetFuenfRahmen() throws Exception {
        MuteRepository repo = mock(MuteRepository.class);
        long jetzt = System.currentTimeMillis();
        List<MuteRecord> eintraege = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            eintraege.add(new MuteRecord(UUID.randomUUID(), "Spieler" + i, "Grund", "Mod",
                    jetzt - 1000L, jetzt + 1_000_000L, ServerScope.global()));
        }
        when(repo.alle()).thenReturn(eintraege);
        Umgebung u = umgebung(repo);
        RegisteredServer ziel = server("lobby");
        List<byte[]> rahmen = new ArrayList<>();
        when(ziel.sendPluginMessage(any(ChannelIdentifier.class), any(byte[].class)))
                .thenAnswer(einwurf -> {
                    rahmen.add(einwurf.getArgument(1, byte[].class));
                    return true;
                });

        u.kanal().mutePushAn(ziel);

        verify(ziel, times(5)).sendPluginMessage(any(ChannelIdentifier.class), any(byte[].class));

        java.util.Set<UUID> gesendet = new java.util.HashSet<>();
        int beginn = 0;
        int ende = 0;
        for (byte[] roh : rahmen) {
            var paket = de.lightrixx.punish.net.Protokoll.paket(roh);
            switch (paket.typ()) {
                case de.lightrixx.punish.net.Protokoll.MUTE_SYNC ->
                        de.lightrixx.punish.net.Protokoll.leseMuteSync(paket.nutzdaten())
                                .forEach(eintrag -> gesendet.add(eintrag.uuid()));
                case de.lightrixx.punish.net.Protokoll.MUTE_SETZEN -> gesendet.add(
                        de.lightrixx.punish.net.Protokoll.leseMute(paket.nutzdaten()).uuid());
                case de.lightrixx.punish.net.Protokoll.SYNC_BEGIN -> beginn++;
                case de.lightrixx.punish.net.Protokoll.SYNC_END -> ende++;
                default -> {
                }
            }
        }
        java.util.Set<UUID> erwartet = new java.util.HashSet<>();
        for (MuteRecord eintrag : eintraege) {
            erwartet.add(eintrag.spielerUuid());
        }
        assertEquals(erwartet, gesendet, "Jeder Mute muss im Sync-Rahmen enthalten sein");
        assertEquals(beginn, ende, "SYNC_BEGIN und SYNC_END müssen paarig sein");
    }

    @Test
    @DisplayName("C-S5.2 Gegenprobe: 500 Mutes + 500 IP-Mutes laufen gechunkt, jeder Rahmen unter MAX_NUTZDATEN")
    void mutePush500Plus500RahmenUnterLimit() throws Exception {
        MuteRepository repo = mock(MuteRepository.class);
        de.lightrixx.punish.storage.IpMuteRepository ipRepo =
                mock(de.lightrixx.punish.storage.IpMuteRepository.class);
        long jetzt = System.currentTimeMillis();
        List<MuteRecord> mutes = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            mutes.add(new MuteRecord(UUID.randomUUID(), "Spieler" + i, "x".repeat(1024), "Mod",
                    jetzt - 1000L, jetzt + 1_000_000L, ServerScope.global()));
        }
        List<de.lightrixx.punish.model.IpMuteRecord> ipMutes = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            ipMutes.add(new de.lightrixx.punish.model.IpMuteRecord(
                    "10.0." + (i / 250) + "." + (i % 250), "x".repeat(1024), "Mod",
                    jetzt - 1000L, jetzt + 1_000_000L));
        }
        when(repo.alle()).thenReturn(mutes);
        when(ipRepo.alle()).thenReturn(ipMutes);

        ProxyServer proxy = mock(ProxyServer.class);
        BrueckenKanal kanal = new BrueckenKanal(mock(PunishVelocity.class), proxy,
                mock(org.slf4j.Logger.class), repo, ipRepo);
        kanal.setBridgeSecrets(java.util.Map.of("lobby", "punish-test-secret-lange"));
        RegisteredServer ziel = server("lobby");
        List<byte[]> rahmen = new ArrayList<>();
        when(ziel.sendPluginMessage(any(ChannelIdentifier.class), any(byte[].class)))
                .thenAnswer(einwurf -> {
                    rahmen.add(einwurf.getArgument(1, byte[].class));
                    return true;
                });

        kanal.mutePushAn(ziel);

        // 2 Rahmen + 500 Mutes + 500 IP-Mutes; leere Ban/Timeout/Freeze-Listen erzeugen keine Frames.
        assertEquals(1002, rahmen.size(), "Erwartete Rahmenzahl des gechunkten Pushs");
        for (byte[] rahmenBytes : rahmen) {
            assertTrue(rahmenBytes.length <= de.lightrixx.punish.net.Protokoll.MAX_NUTZDATEN + 1,
                    "Rahmen überschreitet MAX_NUTZDATEN: " + rahmenBytes.length);
        }

        java.util.Set<UUID> gesendeteMutes = new java.util.HashSet<>();
        java.util.Set<String> gesendeteIps = new java.util.HashSet<>();
        int beginn = 0;
        int ende = 0;
        for (byte[] roh : rahmen) {
            var paket = de.lightrixx.punish.net.Protokoll.paket(roh);
            switch (paket.typ()) {
                case de.lightrixx.punish.net.Protokoll.MUTE_SYNC ->
                        de.lightrixx.punish.net.Protokoll.leseMuteSync(paket.nutzdaten())
                                .forEach(eintrag -> gesendeteMutes.add(eintrag.uuid()));
                case de.lightrixx.punish.net.Protokoll.MUTE_SETZEN -> gesendeteMutes.add(
                        de.lightrixx.punish.net.Protokoll.leseMute(paket.nutzdaten()).uuid());
                case de.lightrixx.punish.net.Protokoll.IP_MUTE_SYNC ->
                        de.lightrixx.punish.net.Protokoll.leseIpMuteSync(paket.nutzdaten())
                                .forEach(eintrag -> gesendeteIps.add(eintrag.ip()));
                case de.lightrixx.punish.net.Protokoll.IP_MUTE_SETZEN -> gesendeteIps.add(
                        de.lightrixx.punish.net.Protokoll.leseIpMute(paket.nutzdaten()).ip());
                case de.lightrixx.punish.net.Protokoll.SYNC_BEGIN -> beginn++;
                case de.lightrixx.punish.net.Protokoll.SYNC_END -> ende++;
                default -> {
                }
            }
        }
        java.util.Set<UUID> erwarteteMutes = new java.util.HashSet<>();
        for (MuteRecord eintrag : mutes) {
            erwarteteMutes.add(eintrag.spielerUuid());
        }
        java.util.Set<String> erwarteteIps = new java.util.HashSet<>();
        for (de.lightrixx.punish.model.IpMuteRecord eintrag : ipMutes) {
            erwarteteIps.add(eintrag.ip());
        }
        assertEquals(erwarteteMutes, gesendeteMutes,
                "Alle 500 Mutes müssen den Chunk-Sync überstehen");
        assertEquals(erwarteteIps, gesendeteIps,
                "Alle 500 IP-Mutes müssen den Chunk-Sync überstehen");
        assertEquals(beginn, ende, "SYNC_BEGIN und SYNC_END müssen paarig sein");
    }

    @Test
    @DisplayName("Repository-Ausfall darf keine leeren Ban/Timeout/Freeze-Syncs senden")
    void repositoryAusfallLeertKeineCaches() {
        MuteRepository muteRepo = mock(MuteRepository.class);
        when(muteRepo.alle()).thenReturn(List.of());
        Umgebung u = umgebung(muteRepo);
        de.lightrixx.punish.storage.BanRepository bannRepo =
                mock(de.lightrixx.punish.storage.BanRepository.class);
        when(bannRepo.alle()).thenThrow(new IllegalStateException("DB nicht erreichbar"));
        de.lightrixx.punish.storage.TimeoutRepository timeoutRepo =
                mock(de.lightrixx.punish.storage.TimeoutRepository.class);
        de.lightrixx.punish.storage.FreezeRepository freezeRepo =
                mock(de.lightrixx.punish.storage.FreezeRepository.class);
        u.kanal().setBanRepository(bannRepo);
        u.kanal().setTimeoutRepository(timeoutRepo);
        u.kanal().setFreezeRepository(freezeRepo);
        RegisteredServer ziel = server("lobby");
        List<Integer> typen = new ArrayList<>();
        when(ziel.sendPluginMessage(any(ChannelIdentifier.class), any(byte[].class)))
                .thenAnswer(einwurf -> {
                    typen.add(einwurf.getArgument(1, byte[].class)[0] & 0xFF);
                    return true;
                });

        u.kanal().mutePushAn(ziel);

        assertFalse(typen.contains(de.lightrixx.punish.net.Protokoll.BAN_SYNC),
                "Leerer Ban-Sync würde alle Bridge-Banns löschen");
        assertFalse(typen.contains(de.lightrixx.punish.net.Protokoll.TIMEOUT_SYNC),
                "Leerer Timeout-Sync würde alle Bridge-Timeouts löschen");
        assertFalse(typen.contains(de.lightrixx.punish.net.Protokoll.FREEZE_SYNC),
                "Leerer Freeze-Sync würde alle Bridge-Freezes löschen");
    }

    @Test
    @DisplayName("Fix S1-11: Kodier-Fail im Ban/Timeout/Freeze-Sync weicht auf gechunkten Einzel-Sync aus")
    void banTimeoutFreezeKodierFailWeichtAufEinzelSyncAus() {
        Umgebung u = umgebung(null);
        de.lightrixx.punish.storage.BanRepository bannRepo =
                mock(de.lightrixx.punish.storage.BanRepository.class);
        de.lightrixx.punish.storage.TimeoutRepository timeoutRepo =
                mock(de.lightrixx.punish.storage.TimeoutRepository.class);
        de.lightrixx.punish.storage.FreezeRepository freezeRepo =
                mock(de.lightrixx.punish.storage.FreezeRepository.class);
        long jetzt = System.currentTimeMillis();
        List<de.lightrixx.punish.model.BanRecord> banns = new ArrayList<>();
        List<de.lightrixx.punish.model.TimeoutRecord> timeouts = new ArrayList<>();
        List<de.lightrixx.punish.model.FreezeRecord> freezes = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            banns.add(new de.lightrixx.punish.model.BanRecord(
                    UUID.randomUUID(), "Spieler" + i, "Grund", "Mod",
                    jetzt - 1000L, jetzt + 1_000_000L, false));
            timeouts.add(new de.lightrixx.punish.model.TimeoutRecord(
                    UUID.randomUUID(), "Spieler" + i, "Grund", "Mod",
                    jetzt - 1000L, jetzt + 1_000_000L));
            freezes.add(new de.lightrixx.punish.model.FreezeRecord(
                    UUID.randomUUID(), "Spieler" + i, "Grund", "Mod",
                    jetzt - 1000L, jetzt + 1_000_000L));
        }
        when(bannRepo.alle()).thenReturn(banns);
        when(timeoutRepo.alle()).thenReturn(timeouts);
        when(freezeRepo.alle()).thenReturn(freezes);
        u.kanal().setBanRepository(bannRepo);
        u.kanal().setTimeoutRepository(timeoutRepo);
        u.kanal().setFreezeRepository(freezeRepo);
        RegisteredServer ziel = server("lobby");
        List<Integer> typen = new ArrayList<>();
        when(ziel.sendPluginMessage(any(ChannelIdentifier.class), any(byte[].class)))
                .thenAnswer(einwurf -> {
                    typen.add(de.lightrixx.punish.net.Protokoll
                            .paket(einwurf.getArgument(1, byte[].class)).typ());
                    return true;
                });

        try (org.mockito.MockedStatic<de.lightrixx.punish.net.Protokoll> protokoll =
                     org.mockito.Mockito.mockStatic(de.lightrixx.punish.net.Protokoll.class,
                             org.mockito.Mockito.CALLS_REAL_METHODS)) {
            protokoll.when(() -> de.lightrixx.punish.net.Protokoll.banSync(anyList()))
                    .thenThrow(new RuntimeException("Kodier-Fail"));
            u.kanal().mutePushAn(ziel);
        }

        List<Integer> erwartet = List.of(
                0x02, 0x05,                              // leerer Mute-Sync
                0x15,                                    // SYNC_BEGIN statt BAN_SYNC
                0x0E, 0x0E,                              // 2× BAN_SETZEN
                0x11, 0x11,                              // 2× TIMEOUT_SETZEN
                0x20, 0x20,                              // 2× FREEZE_SETZEN
                0x16);                                   // SYNC_END
        assertEquals(erwartet, typen,
                "Kodier-Fail muss auf gechunkten Einzel-Sync ausweichen statt zu verwerfen");
    }
}
