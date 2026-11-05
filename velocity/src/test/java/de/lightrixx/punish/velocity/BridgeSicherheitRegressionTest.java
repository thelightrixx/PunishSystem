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
                case de.lightrixx.punish.net.Protokoll.SYNC_BEGIN -> {
                    beginn++;
                    gesendeteMutes.clear();
                    gesendeteIpMutes.clear();
                }
                case de.lightrixx.punish.net.Protokoll.SYNC_END -> ende++;
                case de.lightrixx.punish.net.Protokoll.MUTE_SETZEN -> gesendeteMutes.add(
                        de.lightrixx.punish.net.Protokoll.leseMute(paket.nutzdaten()).uuid());
                case de.lightrixx.punish.net.Protokoll.MUTE_SYNC -> de.lightrixx.punish.net.Protokoll
                        .leseMuteSync(paket.nutzdaten()).forEach(
                                eintrag -> gesendeteMutes.add(eintrag.uuid()));
                case de.lightrixx.punish.net.Protokoll.IP_MUTE_SETZEN -> gesendeteIpMutes.add(
                        de.lightrixx.punish.net.Protokoll.leseIpMute(paket.nutzdaten()).ip());
                case de.lightrixx.punish.net.Protokoll.IP_MUTE_SYNC -> de.lightrixx.punish.net.Protokoll
                        .leseIpMuteSync(paket.nutzdaten()).forEach(
                                eintrag -> gesendeteIpMutes.add(eintrag.ip()));
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
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {0, 1, 2, 206})
    void fehlenderSyncRahmenWirdNichtAlsErfolgGemeldet(int fehlerBei) throws Exception {
        var repo = mock(de.lightrixx.punish.storage.MuteRepository.class);
        var mutes = new java.util.ArrayList<de.lightrixx.punish.model.MuteRecord>();
        for (int i = 0; i < 205; i++) {
            mutes.add(new de.lightrixx.punish.model.MuteRecord(java.util.UUID.randomUUID(),
                    "Spieler" + i, "Grund", "Mod", 1L, Long.MAX_VALUE,
                    de.lightrixx.punish.model.ServerScope.global()));
        }
        when(repo.alle()).thenReturn(mutes);
        var kanal = new BrueckenKanal(mock(PunishVelocity.class),
                mock(com.velocitypowered.api.proxy.ProxyServer.class), mock(org.slf4j.Logger.class), repo);
        kanal.setBridgeSecrets(Map.of("lobby", "punish-test-secret-lange"));
        var server = mock(com.velocitypowered.api.proxy.server.RegisteredServer.class);
        var info = mock(com.velocitypowered.api.proxy.server.ServerInfo.class);
        when(info.getName()).thenReturn("lobby");
        when(server.getServerInfo()).thenReturn(info);
        var nummer = new java.util.concurrent.atomic.AtomicInteger();
        var gesendet = new java.util.ArrayList<Integer>();
        when(server.sendPluginMessage(any(), any(byte[].class))).thenAnswer(inv -> {
            int nr = nummer.getAndIncrement();
            int typ = de.lightrixx.punish.net.Protokoll.paket(inv.getArgument(1, byte[].class)).typ();
            if (nr == fehlerBei) {
                return false;
            }
            gesendet.add(typ);
            return true;
        });
        assertFalse(kanal.mutePushAn(server));
        assertFalse(gesendet.contains(de.lightrixx.punish.net.Protokoll.SYNC_END),
                "Ein unvollständiger Snapshot darf nicht übernommen werden");
    }

    private static StrafListener listener(com.velocitypowered.api.proxy.ProxyServer proxy,
            de.lightrixx.punish.service.IpTimeoutService ipTimeout) {
        return listener(proxy, ipTimeout, mock(de.lightrixx.punish.service.MuteService.class),
                null, mock(VelocityKonfiguration.class));
    }

    private static StrafListener listener(com.velocitypowered.api.proxy.ProxyServer proxy,
            de.lightrixx.punish.service.IpTimeoutService ipTimeout,
            de.lightrixx.punish.service.MuteService mutes,
            de.lightrixx.punish.service.IpMuteService ipMutes, VelocityKonfiguration config) {
        return new StrafListener(proxy, mock(org.slf4j.Logger.class),
                mock(de.lightrixx.punish.storage.PlayerCache.class),
                mock(de.lightrixx.punish.service.BanService.class),
                mock(de.lightrixx.punish.service.IpBanService.class),
                mock(de.lightrixx.punish.service.TimeoutService.class),
                mutes, ipMutes, ipTimeout,
                mock(de.lightrixx.punish.service.AutoBannService.class),
                config, mock(de.lightrixx.punish.service.MessageService.class),
                mock(de.lightrixx.punish.core.AuftragsPlaner.class),
                mock(de.lightrixx.punish.storage.BanRepository.class), mock(BrueckenKanal.class));
    }

    @Test
    void serverIpTimeoutBlockiertNichtDenGesamtenProxyLogin() throws Exception {
        var ipTimeout = mock(de.lightrixx.punish.service.IpTimeoutService.class);
        long jetzt = System.currentTimeMillis();
        var serverTimeout = new de.lightrixx.punish.model.IpTimeoutRecord(
                "1.2.3.4", "Test", "Mod", jetzt - 1000L, jetzt + 60_000L,
                de.lightrixx.punish.model.ServerScope.ausSpeicherwert("survival"));
        when(ipTimeout.findeWirksamOhneCleanup("1.2.3.4", "unbekannt", jetzt))
                .thenReturn(java.util.Optional.of(serverTimeout));
        when(ipTimeout.baueIpTimeoutScreen(serverTimeout, jetzt))
                .thenReturn(net.kyori.adventure.text.Component.text("Gesperrt"));
        var listener = listener(mock(com.velocitypowered.api.proxy.ProxyServer.class), ipTimeout);
        var pruefung = StrafListener.class.getDeclaredMethod("loginSperre",
                java.util.UUID.class, String.class, String.class, long.class);
        pruefung.setAccessible(true);
        assertNull(pruefung.invoke(listener, java.util.UUID.randomUUID(), "Steve", "1.2.3.4", jetzt));
        verify(ipTimeout).findeAbdeckend("1.2.3.4", de.lightrixx.punish.model.ServerScope.global());
        org.mockito.Mockito.verify(ipTimeout, org.mockito.Mockito.never())
                .findeWirksamOhneCleanup(any(), any(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void dossierBenoetigtRechteFuerAlleEnthaltenenDaten() throws Exception {
        var proxy = mock(com.velocitypowered.api.proxy.ProxyServer.class);
        var player = mock(com.velocitypowered.api.proxy.Player.class);
        var uuid = java.util.UUID.randomUUID();
        when(proxy.getPlayer(uuid)).thenReturn(java.util.Optional.of(player));
        var rechte = new java.util.HashSet<>(java.util.List.of(
                "punish.gui", "punish.status", "punish.history", "punish.reports"));
        when(player.hasPermission(any())).thenAnswer(inv -> rechte.contains(inv.getArgument(0)));
        var listener = listener(proxy, null);
        var pruefung = StrafListener.class.getDeclaredMethod("darfGuiSehen",
                java.util.UUID.class, String.class, String.class);
        pruefung.setAccessible(true);
        assertEquals(true, pruefung.invoke(listener, uuid,
                de.lightrixx.punish.net.Protokoll.GUI_DOSSIER, "Steve"));
        rechte.remove("punish.reports");
        assertEquals(false, pruefung.invoke(listener, uuid,
                de.lightrixx.punish.net.Protokoll.GUI_DOSSIER, "Steve"));
    }

    @Test
    void guiRechteWerdenVomProxyAufgeloestUndWiderrufen() {
        var proxy = mock(com.velocitypowered.api.proxy.ProxyServer.class);
        var player = mock(com.velocitypowered.api.proxy.Player.class);
        var uuid = java.util.UUID.randomUUID();
        when(proxy.getPlayer(uuid)).thenReturn(java.util.Optional.of(player));
        var rechte = new java.util.HashSet<>(java.util.List.of("punish.gui", "punish.mute"));
        when(player.hasPermission(any())).thenAnswer(inv -> rechte.contains(inv.getArgument(0)));
        var dienst = new GuiBrueckenDienst(proxy, mock(org.slf4j.Logger.class), mock(VelocityKonfiguration.class),
                mock(de.lightrixx.punish.storage.PlayerCache.class),
                mock(de.lightrixx.punish.storage.BanRepository.class),
                mock(de.lightrixx.punish.storage.MuteRepository.class),
                mock(de.lightrixx.punish.storage.TimeoutRepository.class),
                null, null, null, null, null, null, null);
        var anfrage = new de.lightrixx.punish.net.Protokoll.GuiAnfrage(7L, uuid,
                de.lightrixx.punish.net.Protokoll.GUI_RECHTE, "", "", 1, "");
        var antwort = dienst.beantworte(anfrage);
        assertTrue(antwort.erfolg());
        assertEquals(java.util.Set.of("punish.gui", "punish.mute"), antwort.eintraege().stream()
                .map(zeile -> zeile.titel()).collect(java.util.stream.Collectors.toSet()));
        rechte.clear();
        assertFalse(dienst.beantworte(anfrage).erfolg());
        assertTrue(dienst.beantworte(anfrage).eintraege().isEmpty());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(com.velocitypowered.api.permission.Tristate.class)
    void ausdruecklichesNeinWirdNichtDurchWildcardUeberschrieben(
            com.velocitypowered.api.permission.Tristate wert) {
        var spieler = mock(com.velocitypowered.api.proxy.Player.class);
        when(spieler.getPermissionValue("punish.gui")).thenReturn(wert);
        when(spieler.hasPermission("punish.gui")).thenReturn(wert == com.velocitypowered.api.permission.Tristate.TRUE);
        when(spieler.hasPermission("punish.*")).thenReturn(true);
        assertEquals(wert != com.velocitypowered.api.permission.Tristate.FALSE,
                new VelocitySender(spieler).hatRecht("punish.gui"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void verpassteBroadcastsVerlierenWederDenRestNochBestaetigenSieEinenDisconnect(boolean getrennt) throws Exception {
        var uuid = java.util.UUID.randomUUID();
        var proxy = mock(com.velocitypowered.api.proxy.ProxyServer.class);
        var player = mock(com.velocitypowered.api.proxy.Player.class);
        when(proxy.getPlayer(uuid)).thenReturn(java.util.Optional.of(player))
                .thenReturn(getrennt ? java.util.Optional.empty() : java.util.Optional.of(player));
        var config = mock(VelocityKonfiguration.class);
        when(config.verpassteBroadcastsAktiv()).thenReturn(true);
        var listener = listener(proxy, null, mock(de.lightrixx.punish.service.MuteService.class), null, config);
        var ablage = mock(de.lightrixx.punish.storage.VerpassteBroadcastsRepository.class);
        var verzeichnis = mock(de.lightrixx.punish.core.SpielerVerzeichnis.class);
        when(verzeichnis.hatRecht(org.mockito.ArgumentMatchers.eq(uuid), any())).thenReturn(true);
        when(verzeichnis.onlineSpieler(uuid)).thenReturn(java.util.Optional.of(
                new de.lightrixx.punish.core.OnlineSpieler(uuid, "Team", "127.0.0.1", false)));
        when(ablage.cursorFuer(uuid)).thenReturn(java.util.Optional.of(1L));
        var snapshot = java.util.stream.LongStream.rangeClosed(1, 5001)
                .mapToObj(i -> new de.lightrixx.punish.model.VerpassteNachricht(i, "mute", "Eintrag " + i, 2L)).toList();
        var leseZeit = new java.util.concurrent.atomic.AtomicLong();
        when(ablage.momentaufnahme()).thenAnswer(inv -> {
            leseZeit.set(System.currentTimeMillis());
            return snapshot;
        });
        var broadcast = mock(de.lightrixx.punish.service.BroadcastService.class);
        var methode = StrafListener.class.getDeclaredMethod("liefereVerpasst", java.util.UUID.class,
                de.lightrixx.punish.storage.VerpassteBroadcastsRepository.class,
                de.lightrixx.punish.core.SpielerVerzeichnis.class, de.lightrixx.punish.service.BroadcastService.class);
        methode.setAccessible(true);
        methode.invoke(listener, uuid, ablage, verzeichnis, broadcast);
        org.mockito.ArgumentCaptor<java.util.List<de.lightrixx.punish.model.VerpassteNachricht>> teile =
                org.mockito.ArgumentCaptor.captor();
        verify(broadcast, org.mockito.Mockito.times(2)).liefereVerpassteNach(
                org.mockito.ArgumentMatchers.eq(uuid), teile.capture());
        assertEquals(5001, teile.getAllValues().stream().mapToInt(java.util.List::size).sum());
        if (getrennt) {
            verify(ablage, org.mockito.Mockito.never()).cursorSetzen(any(), org.mockito.ArgumentMatchers.anyLong());
        } else {
            verify(ablage).cursorSetzen(org.mockito.ArgumentMatchers.eq(uuid),
                    org.mockito.ArgumentMatchers.longThat(wert -> wert < leseZeit.get()));
        }
    }

    @Test
    void diagnoseBehauptetOhneHelloOderSecretsKeineBridgeAnbindung() throws Exception {
        var proxy = mock(com.velocitypowered.api.proxy.ProxyServer.class);
        var server = java.util.List.of(server("lobby"), server("survival"));
        when(proxy.getAllServers()).thenReturn(server);
        var config = mock(VelocityKonfiguration.class);
        var kanal = mock(BrueckenKanal.class);
        var methode = VelocityBefehle.class.getDeclaredMethod("diagnoseKontext",
                com.velocitypowered.api.proxy.ProxyServer.class, VelocityKonfiguration.class,
                BrueckenKanal.class, de.lightrixx.punish.storage.IoExecutor.class);
        methode.setAccessible(true);
        for (int anzahl = 0; anzahl <= 2; anzahl++) {
            when(kanal.bridgeVersionen()).thenReturn(switch (anzahl) {
                case 0 -> java.util.Map.of();
                case 1 -> java.util.Map.of("lobby", "4.0");
                default -> java.util.Map.of("lobby", "4.0", "survival", "4.0");
            });
            for (String secret : java.util.List.of("", "kurz", "123456789012345", "1234567890123456",
                    " 1234567890123456 ")) {
                when(config.brueckenSecrets()).thenReturn(secret.isEmpty() ? java.util.Map.of()
                        : java.util.Map.of("lobby", secret, "survival", secret));
                var kontext = (de.lightrixx.punish.command.PunishDiagnosticsCommand.Kontext)
                        methode.invoke(null, proxy, config, kanal, null);
                assertEquals(anzahl == 2 && secret.trim().length() >= 16, kontext.bridgeOk());
                assertTrue(kontext.bridgeDetail().contains(anzahl + "/2"));
                assertFalse(kontext.bridgeDetail().contains("verbunden"));
            }
        }
    }
    private static com.velocitypowered.api.proxy.server.RegisteredServer server(String name) {
        var server = mock(com.velocitypowered.api.proxy.server.RegisteredServer.class);
        var info = mock(com.velocitypowered.api.proxy.server.ServerInfo.class);
        when(info.getName()).thenReturn(name);
        when(server.getServerInfo()).thenReturn(info);
        return server;
    }

    private static StrafListener serverwechselListener(de.lightrixx.punish.service.TimeoutService timeouts) {
        return new StrafListener(mock(com.velocitypowered.api.proxy.ProxyServer.class),
                mock(org.slf4j.Logger.class), mock(de.lightrixx.punish.storage.PlayerCache.class),
                mock(de.lightrixx.punish.service.BanService.class),
                mock(de.lightrixx.punish.service.IpBanService.class), timeouts,
                mock(de.lightrixx.punish.service.MuteService.class),
                mock(de.lightrixx.punish.service.AutoBannService.class),
                mock(VelocityKonfiguration.class), mock(de.lightrixx.punish.service.MessageService.class),
                mock(de.lightrixx.punish.core.AuftragsPlaner.class),
                mock(de.lightrixx.punish.storage.BanRepository.class), mock(BrueckenKanal.class));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void serversperreTrenntNurBeimErstbeitritt(boolean bereitsVerbunden) {
        var spieler = mock(com.velocitypowered.api.proxy.Player.class);
        var uuid = java.util.UUID.randomUUID();
        when(spieler.getUniqueId()).thenReturn(uuid);
        var timeouts = mock(de.lightrixx.punish.service.TimeoutService.class);
        long jetzt = System.currentTimeMillis();
        var timeout = new de.lightrixx.punish.model.TimeoutRecord(uuid, "Steve", "Test", "Mod",
                jetzt - 1000L, jetzt + 60_000L, de.lightrixx.punish.model.ServerScope.server("survival"));
        when(timeouts.findeWirksamOhneCleanup(org.mockito.ArgumentMatchers.eq(uuid),
                org.mockito.ArgumentMatchers.eq("survival"), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(java.util.Optional.of(timeout));
        var grund = net.kyori.adventure.text.Component.text("Gesperrt");
        when(timeouts.baueTimeoutScreen(org.mockito.ArgumentMatchers.eq(timeout),
                org.mockito.ArgumentMatchers.anyLong())).thenReturn(grund);
        var event = new com.velocitypowered.api.event.player.ServerPreConnectEvent(
                spieler, server("survival"), bereitsVerbunden ? server("lobby") : null);

        serverwechselListener(timeouts).onPreConnect(event);

        assertFalse(event.getResult().isAllowed());
        if (bereitsVerbunden) {
            verify(spieler, org.mockito.Mockito.never()).disconnect(any());
            verify(spieler).sendMessage(grund);
        } else {
            verify(spieler).disconnect(grund);
        }
    }

    @Test
    void umgeleiteterZielserverWirdGeprueft() {
        var spieler = mock(com.velocitypowered.api.proxy.Player.class);
        var uuid = java.util.UUID.randomUUID();
        when(spieler.getUniqueId()).thenReturn(uuid);
        var timeouts = mock(de.lightrixx.punish.service.TimeoutService.class);
        var ziel = server("survival");
        var event = new com.velocitypowered.api.event.player.ServerPreConnectEvent(
                spieler, server("lobby"), server("lobby"));
        event.setResult(com.velocitypowered.api.event.player.ServerPreConnectEvent.ServerResult.allowed(ziel));

        serverwechselListener(timeouts).onPreConnect(event);

        verify(timeouts).findeWirksamOhneCleanup(org.mockito.ArgumentMatchers.eq(uuid),
                org.mockito.ArgumentMatchers.eq("survival"), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void bereitsVerweigerterServerwechselWirdNichtErneutGeprueft() {
        var spieler = mock(com.velocitypowered.api.proxy.Player.class);
        when(spieler.getUniqueId()).thenReturn(java.util.UUID.randomUUID());
        var timeouts = mock(de.lightrixx.punish.service.TimeoutService.class);
        var event = new com.velocitypowered.api.event.player.ServerPreConnectEvent(spieler, server("survival"));
        event.setResult(com.velocitypowered.api.event.player.ServerPreConnectEvent.ServerResult.denied());

        serverwechselListener(timeouts).onPreConnect(event);

        org.mockito.Mockito.verifyNoInteractions(timeouts);
        verify(spieler, org.mockito.Mockito.never()).disconnect(any());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "mute, UNSIGNED", "mute, SIGNED_WITHOUT_ARGS", "mute, SIGNED_WITH_ARGS", "mute, UNSUPPORTED",
            "ipmute, UNSIGNED", "ipmute, SIGNED_WITHOUT_ARGS", "ipmute, SIGNED_WITH_ARGS", "ipmute, UNSUPPORTED",
            "freeze, UNSIGNED", "freeze, SIGNED_WITHOUT_ARGS", "freeze, SIGNED_WITH_ARGS", "freeze, UNSUPPORTED"
    })
    void proxyBefehleRespektierenStrafenUndSignaturen(String typ,
            com.velocitypowered.api.event.command.CommandExecuteEvent.SignedState signatur) throws Exception {
        var proxy = mock(com.velocitypowered.api.proxy.ProxyServer.class);
        var spieler = mock(com.velocitypowered.api.proxy.Player.class);
        var uuid = java.util.UUID.randomUUID();
        when(spieler.getUniqueId()).thenReturn(uuid);
        when(spieler.getRemoteAddress()).thenReturn(new java.net.InetSocketAddress("1.2.3.4", 12345));
        var verbindung = mock(com.velocitypowered.api.proxy.ServerConnection.class);
        var lobby = server("lobby");
        var lobbyInfo = lobby.getServerInfo();
        when(verbindung.getServerInfo()).thenReturn(lobbyInfo);
        when(spieler.getCurrentServer()).thenReturn(java.util.Optional.of(verbindung));
        var mutes = mock(de.lightrixx.punish.service.MuteService.class);
        var ipMutes = mock(de.lightrixx.punish.service.IpMuteService.class);
        var config = mock(VelocityKonfiguration.class);
        var listener = listener(proxy, null, mutes, ipMutes, config);
        when(config.muteBlockierteBefehle()).thenReturn(java.util.List.of("/msg", "/shout*"));
        long jetzt = System.currentTimeMillis();
        var scope = de.lightrixx.punish.model.ServerScope.server("lobby");
        if (typ.equals("mute")) {
            when(mutes.findeAktivenMuteFuer(org.mockito.ArgumentMatchers.eq(uuid),
                    org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.eq("lobby")))
                    .thenReturn(java.util.Optional.of(new de.lightrixx.punish.model.MuteRecord(
                            uuid, "Steve", "Test", "Mod", jetzt, jetzt + 60_000L, scope)));
        } else if (typ.equals("ipmute")) {
            when(ipMutes.findeAktivenMuteFuer(org.mockito.ArgumentMatchers.eq("1.2.3.4"),
                    org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.eq("lobby")))
                    .thenReturn(java.util.Optional.of(new de.lightrixx.punish.model.IpMuteRecord(
                            "1.2.3.4", "Test", "Mod", jetzt, jetzt + 60_000L, scope)));
        } else {
            var freezes = mock(de.lightrixx.punish.service.FreezeService.class);
            when(freezes.findeAktivenFreezeFuer(org.mockito.ArgumentMatchers.eq(uuid),
                    org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.eq("lobby")))
                    .thenReturn(java.util.Optional.of(new de.lightrixx.punish.model.FreezeRecord(
                            uuid, "Steve", "Test", "Mod", jetzt, jetzt + 60_000L, scope)));
            listener.setFreezeService(freezes);
        }
        String befehl = switch (typ) {
            case "mute" -> "  essentials:MsG Steve Hallo";
            case "ipmute" -> "shoutall Hallo";
            default -> "server survival";
        };
        var event = new com.velocitypowered.api.event.command.CommandExecuteEvent(spieler, befehl,
                new com.velocitypowered.api.event.command.CommandExecuteEvent.InvocationInfo(signatur,
                        com.velocitypowered.api.event.command.CommandExecuteEvent.Source.PLAYER));

        listener.onCommand(event);

        if (signatur == com.velocitypowered.api.event.command.CommandExecuteEvent.SignedState.SIGNED_WITH_ARGS) {
            assertTrue(event.getResult().isForwardToServer());
        } else {
            assertFalse(event.getResult().isAllowed());
            assertFalse(event.getResult().isForwardToServer());
        }
        verify(spieler, org.mockito.Mockito.never()).disconnect(any());
    }

    @Test
    void proxyBefehlOhneWirksameStrafeBleibtErlaubt() {
        var listener = listener(mock(com.velocitypowered.api.proxy.ProxyServer.class), null);
        var spieler = mock(com.velocitypowered.api.proxy.Player.class);
        when(spieler.getUniqueId()).thenReturn(java.util.UUID.randomUUID());
        var event = new com.velocitypowered.api.event.command.CommandExecuteEvent(spieler, "server lobby");

        listener.onCommand(event);

        assertTrue(event.getResult().isAllowed());
        assertFalse(event.getResult().isForwardToServer());
    }
}
