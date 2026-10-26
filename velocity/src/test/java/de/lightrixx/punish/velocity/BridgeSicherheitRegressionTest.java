package de.lightrixx.punish.velocity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BridgeSicherheitRegressionTest {

    @Test
    @DisplayName("P1: BEFEHL_AUSFUEHREN-Allowlist blockt RCE (luckperms/op/stop)")
    void allowlistBlocktRce() {
        assertEquals("punish.pardon", BrueckenKanal.bridgeErlaubnisFuer("pardon Notch"));
        assertEquals("punish.pardon", BrueckenKanal.bridgeErlaubnisFuer("/pardon Notch"));
        assertEquals("punish.pardon", BrueckenKanal.bridgeErlaubnisFuer("unban Notch"));
        assertEquals("punish.ban", BrueckenKanal.bridgeErlaubnisFuer("ban Notch 7d Spam"));
        assertNull(BrueckenKanal.bridgeErlaubnisFuer("luckperms user test permission set punish.* true"),
                "LuckPerms darf nie via Bridge");
        assertNull(BrueckenKanal.bridgeErlaubnisFuer("op Notch"), "op darf nie via Bridge");
        assertNull(BrueckenKanal.bridgeErlaubnisFuer("stop"), "stop darf nie via Bridge");
        assertNull(BrueckenKanal.bridgeErlaubnisFuer("say hi"), "say darf nie via Bridge");
        assertNull(BrueckenKanal.bridgeErlaubnisFuer(""), "leer darf nie");
        assertNull(BrueckenKanal.bridgeErlaubnisFuer(null), "null darf nie");
        assertEquals("punish.ban", BrueckenKanal.bridgeErlaubnisFuer("velocity:ban Notch 7d x"),
                "Namespace-Präfix muss gestrippt werden");
        assertEquals("punish.version", BrueckenKanal.bridgeErlaubnisFuer("punish-version"));
        assertEquals("punish.version", BrueckenKanal.bridgeErlaubnisFuer("psversion"));
        assertEquals("punish.help", BrueckenKanal.bridgeErlaubnisFuer("punish-help"));
        assertEquals("punish.help", BrueckenKanal.bridgeErlaubnisFuer("pshelp"));
    }

    @Test
    @DisplayName("P2: Abgewiesene Bridge-Aktion meldet kein-recht an den Spieler")
    void abgewieseneAktionMeldetSpieler() {
        var proxy = mock(com.velocitypowered.api.proxy.ProxyServer.class);
        var logger = mock(org.slf4j.Logger.class);
        var muteRepository = mock(de.lightrixx.punish.storage.MuteRepository.class);
        var nachrichten = mock(de.lightrixx.punish.service.MessageService.class);
        var spieler = mock(com.velocitypowered.api.proxy.Player.class);
        java.util.UUID uuid = java.util.UUID.randomUUID();
        when(spieler.getUsername()).thenReturn("SpielerX");
        when(spieler.hasPermission("punish.timeout")).thenReturn(false);
        when(proxy.getPlayer(uuid)).thenReturn(java.util.Optional.of(spieler));
        var kanal = new BrueckenKanal(mock(PunishVelocity.class), proxy, logger, muteRepository, null);
        kanal.setMessageService(nachrichten);
        kanal.fuehreAus(uuid, "timeout Notch 30m Spam");
        verify(nachrichten).senden(any(de.lightrixx.punish.core.BefehlsSender.class),
                org.mockito.ArgumentMatchers.eq(de.lightrixx.punish.core.MessageKeys.KEIN_RECHT));
    }

    @Test
    @DisplayName("S2: SnakeYAML SafeConstructor lehnt !!-Tags ab")
    void safeConstructorLehntTagsAb(@TempDir File ordner) throws Exception {
        SnakeYamlSpeicher speicher = new SnakeYamlSpeicher();
        File datei = new File(ordner, "boese.yml");
        String boese = "exploit: !!javax.script.ScriptEngineManager [null]\n";
        Files.writeString(datei.toPath(), boese, StandardCharsets.UTF_8);
        Map<String, Object> geladen = speicher.laden(datei);
        assertTrue(geladen.isEmpty(), "!!-Tags dürfen nie instanziiert werden, Datei muss quarantäniert/leer sein");
    }

    @Test
    @DisplayName("Refresh-HELLO ist leichtgewichtig: Voll-Sync nur beim ersten/alten Kontakt")
    void helloRefreshOhneVollSync() {
        var proxy = mock(com.velocitypowered.api.proxy.ProxyServer.class);
        var logger = mock(org.slf4j.Logger.class);
        var muteRepository = mock(de.lightrixx.punish.storage.MuteRepository.class);
        var kanal = new BrueckenKanal(mock(PunishVelocity.class), proxy, logger, muteRepository, null);
        assertTrue(kanal.vollSyncFaellig("lobby", 1000L));
        kanal.merkeVollSync("lobby", 1000L);
        assertFalse(kanal.vollSyncFaellig("lobby", 2000L),
                "Refresh innerhalb 30 Min darf keinen Voll-Sync auslösen");
        assertTrue(kanal.vollSyncFaellig("survival", 2000L),
                "anderer Server braucht eigenen Voll-Sync");
        kanal.merkeVollSync("survival", 2000L);
        assertTrue(kanal.vollSyncFaellig("lobby",
                1000L + BrueckenKanal.VOLL_SYNC_INTERVALL_MILLIS + 1L));
    }

    @Test
    @DisplayName("P3: MUTE_SYNC-Roundtrip bleibt kodierbar")
    void muteSyncRoundtrip() throws Exception {
        var eintraege = new java.util.ArrayList<de.lightrixx.punish.net.Protokoll.MuteEintrag>();
        for (int i = 0; i < 10; i++) {
            eintraege.add(new de.lightrixx.punish.net.Protokoll.MuteEintrag(
                    java.util.UUID.randomUUID(), "Spieler" + i, "Spam", "Mod",
                    1000L, 2000L, "global"));
        }
        byte[] paket = de.lightrixx.punish.net.Protokoll.muteSync(eintraege);
        assertTrue(paket.length > 0);
        var gelesen = de.lightrixx.punish.net.Protokoll.leseMuteSync(
                de.lightrixx.punish.net.Protokoll.paket(paket).nutzdaten());
        assertEquals(10, gelesen.size());
    }

    @Test
    @DisplayName("P3: Voll-Sync über der Chunk-Grenze behält Mutes, IP-Mutes und Rahmen")
    void chunkSyncHaeltMutesUndRahmen() throws Exception {
        long jetzt = System.currentTimeMillis();
        var muteRepo = mock(de.lightrixx.punish.storage.MuteRepository.class);
        var ipMuteRepo = mock(de.lightrixx.punish.storage.IpMuteRepository.class);
        var banRepo = mock(de.lightrixx.punish.storage.BanRepository.class);
        var timeoutRepo = mock(de.lightrixx.punish.storage.TimeoutRepository.class);
        var freezeRepo = mock(de.lightrixx.punish.storage.FreezeRepository.class);

        java.util.UUID muteUuid = java.util.UUID.randomUUID();
        when(muteRepo.alle()).thenReturn(java.util.List.of(
                new de.lightrixx.punish.model.MuteRecord(muteUuid, "SpielerM", "Spam", "Mod",
                        jetzt - 1000L, jetzt + 1_000_000L,
                        de.lightrixx.punish.model.ServerScope.global())));
        when(ipMuteRepo.alle()).thenReturn(java.util.List.of(
                new de.lightrixx.punish.model.IpMuteRecord("10.9.9.9", "Spam", "Mod",
                        jetzt - 1000L, jetzt + 1_000_000L)));

        java.util.List<de.lightrixx.punish.model.BanRecord> banns = new java.util.ArrayList<>();
        for (int i = 0; i < 205; i++) {
            banns.add(new de.lightrixx.punish.model.BanRecord(java.util.UUID.randomUUID(),
                    "Gebannt" + i, "Grund", "Mod", jetzt - 1000L, jetzt + 1_000_000L, false));
        }
        when(banRepo.alle()).thenReturn(banns);
        when(timeoutRepo.alle()).thenReturn(java.util.List.of());
        when(freezeRepo.alle()).thenReturn(java.util.List.of());

        var proxy = mock(com.velocitypowered.api.proxy.ProxyServer.class);
        var kanal = new BrueckenKanal(mock(PunishVelocity.class), proxy,
                mock(org.slf4j.Logger.class), muteRepo, ipMuteRepo);
        kanal.setBanRepository(banRepo);
        kanal.setTimeoutRepository(timeoutRepo);
        kanal.setFreezeRepository(freezeRepo);
        kanal.setBridgeSecrets(java.util.Map.of("lobby", "punish-test-secret-lange"));

        var ziel = mock(com.velocitypowered.api.proxy.server.RegisteredServer.class);
        var info = mock(com.velocitypowered.api.proxy.server.ServerInfo.class);
        when(info.getName()).thenReturn("lobby");
        when(ziel.getServerInfo()).thenReturn(info);
        java.util.List<byte[]> rahmen = new java.util.ArrayList<>();
        when(ziel.sendPluginMessage(
                any(com.velocitypowered.api.proxy.messages.ChannelIdentifier.class),
                any(byte[].class))).thenAnswer(einwurf -> {
                    rahmen.add(einwurf.getArgument(1, byte[].class));
                    return true;
                });

        assertTrue(kanal.mutePushAn(ziel), "Voll-Sync muss als erfolgreich gelten");

        java.util.Set<java.util.UUID> gesendeteMutes = new java.util.HashSet<>();
        java.util.Set<String> gesendeteIpMutes = new java.util.HashSet<>();
        int beginn = 0;
        int ende = 0;
        int bannRahmen = 0;
        for (byte[] roh : rahmen) {
            var paket = de.lightrixx.punish.net.Protokoll.paket(roh);
            switch (paket.typ()) {
                case de.lightrixx.punish.net.Protokoll.SYNC_BEGIN -> beginn++;
                case de.lightrixx.punish.net.Protokoll.SYNC_END -> ende++;
                case de.lightrixx.punish.net.Protokoll.MUTE_SETZEN -> gesendeteMutes.add(
                        de.lightrixx.punish.net.Protokoll.leseMute(paket.nutzdaten()).uuid());
                case de.lightrixx.punish.net.Protokoll.IP_MUTE_SETZEN -> gesendeteIpMutes.add(
                        de.lightrixx.punish.net.Protokoll.leseIpMute(paket.nutzdaten()).ip());
                case de.lightrixx.punish.net.Protokoll.BAN_SETZEN -> bannRahmen++;
                default -> {
                }
            }
        }
        assertTrue(beginn >= 1, "Chunk-Sync muss einen SYNC_BEGIN senden");
        assertEquals(beginn, ende, "Jeder SYNC_BEGIN braucht ein passendes SYNC_END");
        assertTrue(gesendeteMutes.contains(muteUuid),
                "Ein vorhandener Mute darf im Voll-Sync nicht verloren gehen");
        assertTrue(gesendeteIpMutes.contains("10.9.9.9"),
                "Ein vorhandener IP-Mute darf im Voll-Sync nicht verloren gehen");
        assertEquals(205, bannRahmen, "Jeder Bann muss einzeln im Rahmen stehen");
        assertTrue(rahmen.size() > 206, "Gechunkter Sync muss mehr Rahmen als Einträge senden");
    }
}
