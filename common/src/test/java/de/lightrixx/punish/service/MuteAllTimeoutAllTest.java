package de.lightrixx.punish.service;

import de.lightrixx.punish.core.OfflineVerzeichnis;
import de.lightrixx.punish.core.OnlineSpieler;
import de.lightrixx.punish.core.Permissions;
import de.lightrixx.punish.core.SpielerVerzeichnis;
import de.lightrixx.punish.hook.LuckPermsZugang;
import de.lightrixx.punish.model.MuteRecord;
import de.lightrixx.punish.model.ServerScope;
import de.lightrixx.punish.model.TimeoutRecord;
import de.lightrixx.punish.storage.LogWriter;
import de.lightrixx.punish.storage.MuteRepository;
import de.lightrixx.punish.storage.TimeoutRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MuteAllTimeoutAllTest {

    private record Setup(SpielerVerzeichnis verzeichnis, ProtectionService schutz, UUID selbst, UUID frei,
            UUID geschuetzt) {
    }

    private static Setup setupMit(boolean wildcardFrei) {
        UUID selbst = UUID.randomUUID();
        UUID frei = UUID.randomUUID();
        UUID geschuetzt = UUID.randomUUID();
        var onlineSelbst = new OnlineSpieler(selbst, "Chef", "1.1.1.1", false);
        var onlineFrei = new OnlineSpieler(frei, "Frei", "1.1.1.2", false);
        var onlineGeschuetzt = new OnlineSpieler(geschuetzt, "Sicher", "1.1.1.3", false);
        SpielerVerzeichnis verzeichnis = mock(SpielerVerzeichnis.class);
        when(verzeichnis.onlineSpieler()).thenReturn(List.of(onlineSelbst, onlineFrei, onlineGeschuetzt));
        when(verzeichnis.onlineSpieler(any(UUID.class))).thenReturn(Optional.empty());
        when(verzeichnis.darfDirektAusfuehren()).thenReturn(true);
        when(verzeichnis.hatRecht(eq(geschuetzt), any(String.class))).thenReturn(false);
        when(verzeichnis.hatRecht(eq(frei), any(String.class))).thenReturn(false);
        when(verzeichnis.hatRecht(eq(selbst), any(String.class))).thenReturn(false);
        when(verzeichnis.hatRecht(eq(geschuetzt), eq(Permissions.IGNORE_STAR.node()))).thenReturn(false);
        when(verzeichnis.hatRecht(eq(geschuetzt), eq("punish.ignore.mute"))).thenReturn(true);
        when(verzeichnis.hatRecht(eq(geschuetzt), eq("punish.ignore.timeout"))).thenReturn(true);
        when(verzeichnis.hatRecht(eq(frei), eq(Permissions.IGNORE_STAR.node()))).thenReturn(wildcardFrei);
        OfflineVerzeichnis offline = mock(OfflineVerzeichnis.class);
        LuckPermsZugang zugang = mock(LuckPermsZugang.class);
        return new Setup(verzeichnis, new ProtectionService(verzeichnis, offline, zugang), selbst, frei,
                geschuetzt);
    }

    private static MuteService muteService(Setup setup, MuteRepository repository) {
        return new MuteService(repository, mock(HistoryService.class), mock(BroadcastService.class),
                mock(LogWriter.class), mock(MessageService.class), mock(de.lightrixx.punish.core.AuftragsPlaner.class),
                setup.schutz(), setup.verzeichnis());
    }

    private static TimeoutService timeoutService(Setup setup, TimeoutRepository repository) {
        return new TimeoutService(repository, mock(de.lightrixx.punish.core.Konfiguration.class),
                mock(HistoryService.class), mock(BroadcastService.class), mock(LogWriter.class),
                mock(de.lightrixx.punish.core.AuftragsPlaner.class), setup.schutz(), setup.verzeichnis());
    }

    @Test
    void muteAlleUeberspringtSelbstUndGeschuetzte() {
        Setup setup = setupMit(false);
        MuteRepository repository = mock(MuteRepository.class);
        when(repository.mutenAlleWennFrei(any())).thenAnswer(inv -> {
            java.util.Collection<de.lightrixx.punish.model.MuteRecord> alle = inv.getArgument(0);
            return List.copyOf(alle);
        });
        MuteService service = muteService(setup, repository);
        de.lightrixx.punish.service.MassenErgebnis ergebnis = service.muteAlle(3_600_000L, "Spam", "Konsole", setup.selbst(),
                ServerScope.unbekannt(), ServerScope.UNBEKANNT_WERT);
        assertEquals(1, ergebnis.anzahl());
        assertEquals(0, ergebnis.gekuerzt());
        assertEquals(1, service.zaehleMuteAlleKandidaten(setup.selbst()));
    }

    @Test
    void entmutenAlleHebtAlleAuf() {
        Setup setup = setupMit(false);
        MuteRepository repository = mock(MuteRepository.class);
        var scope = ServerScope.unbekannt();
        var erster = new MuteRecord(setup.frei(), "Frei", "Spam", "Konsole",
                System.currentTimeMillis(), System.currentTimeMillis() + 3_600_000L, scope);
        var zweiter = new MuteRecord(setup.geschuetzt(), "Sicher", "Spam", "Konsole",
                System.currentTimeMillis(), System.currentTimeMillis() + 3_600_000L, scope);
        when(repository.alle()).thenReturn(List.of(erster, zweiter));
        when(repository.entmuten(any(UUID.class), any(ServerScope.class)))
                .thenAnswer(inv -> Optional.of(new MuteRecord(inv.getArgument(0), "X", "Spam", "Konsole",
                        System.currentTimeMillis(), System.currentTimeMillis() + 60_000L,
                        inv.getArgument(1))));
        MuteService service = muteService(setup, repository);
        assertEquals(2, service.entmutenAlle("Fehler", "Konsole", ServerScope.UNBEKANNT_WERT));
    }

    @Test
    void entmutenOhneEintragWirftNichtBeiNebenlaeufigemEintrag() {
        Setup setup = setupMit(false);
        MuteRepository repository = mock(MuteRepository.class);
        var scope = ServerScope.unbekannt();
        when(repository.finden(any(UUID.class), any(ServerScope.class))).thenReturn(Optional.empty());
        when(repository.entmutenWenn(any(UUID.class), any(ServerScope.class), any()))
                .thenAnswer(inv -> {
                    java.util.function.Predicate<MuteRecord> bedingung = inv.getArgument(2);
                    bedingung.test(new MuteRecord(setup.frei(), "Frei", "Spam", "Konsole",
                            System.currentTimeMillis(), System.currentTimeMillis() + 60_000L, scope));
                    return Optional.empty();
                });
        MuteService service = muteService(setup, repository);
        assertFalse(service.entmuten(setup.frei(), "Frei", "Konsole", scope, ServerScope.UNBEKANNT_WERT));
    }

    @Test
    void timeoutAlleUeberspringtSelbstUndGeschuetzte() {
        Setup setup = setupMit(false);
        TimeoutRepository repository = mock(TimeoutRepository.class);
        when(repository.timeoutAlleWennFrei(any())).thenAnswer(inv -> {
            java.util.Collection<de.lightrixx.punish.model.TimeoutRecord> alle = inv.getArgument(0);
            return List.copyOf(alle);
        });
        TimeoutService service = timeoutService(setup, repository);
        de.lightrixx.punish.service.MassenErgebnis ergebnis = service.timeoutAlle(600_000L, "Streit", "Konsole", setup.selbst(),
                ServerScope.unbekannt(), ServerScope.UNBEKANNT_WERT);
        assertEquals(1, ergebnis.anzahl());
        assertEquals(0, ergebnis.gekuerzt());
        assertEquals(1, service.zaehleTimeoutAlleKandidaten(setup.selbst()));
    }

    @Test
    void timeoutAufhebenAlleHebtAlleAuf() {
        Setup setup = setupMit(false);
        TimeoutRepository repository = mock(TimeoutRepository.class);
        var scope = ServerScope.unbekannt();
        var erster = new TimeoutRecord(setup.frei(), "Frei", "Streit", "Konsole",
                System.currentTimeMillis(), System.currentTimeMillis() + 600_000L, scope);
        when(repository.alle()).thenReturn(List.of(erster));
        when(repository.timeoutAufheben(any(UUID.class), any(ServerScope.class)))
                .thenAnswer(inv -> Optional.of(new TimeoutRecord(inv.getArgument(0), "X", "Streit", "Konsole",
                        System.currentTimeMillis(), System.currentTimeMillis() + 60_000L,
                        inv.getArgument(1))));
        TimeoutService service = timeoutService(setup, repository);
        assertEquals(1, service.timeoutAufhebenAlle("Fehler", "Konsole", ServerScope.UNBEKANNT_WERT));
    }
    @Test
    void hoeherrangigeSpielerWerdenBeiMassenaktionenUndVorschauUebersprungen() {
        UUID mod = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        SpielerVerzeichnis verzeichnis = mock(SpielerVerzeichnis.class);
        when(verzeichnis.onlineSpieler()).thenReturn(List.of(
                new OnlineSpieler(mod, "Mod", "1.1.1.1", false),
                new OnlineSpieler(admin, "Admin", "1.1.1.2", false)));
        when(verzeichnis.darfDirektAusfuehren()).thenReturn(true);
        ProtectionService schutz = mock(ProtectionService.class);
        when(schutz.istRanghoeher(mod, admin)).thenReturn(true);
        Setup setup = new Setup(verzeichnis, schutz, mod, admin, admin);
        var muteRepo = mock(MuteRepository.class);
        var timeoutRepo = mock(TimeoutRepository.class);
        when(muteRepo.mutenAlleWennFrei(any())).thenAnswer(inv -> List.copyOf(inv.getArgument(0)));
        when(timeoutRepo.timeoutAlleWennFrei(any())).thenAnswer(inv -> List.copyOf(inv.getArgument(0)));
        var mute = muteService(setup, muteRepo);
        var timeout = timeoutService(setup, timeoutRepo);
        var ban = new BanService(mock(de.lightrixx.punish.storage.BanRepository.class),
                mock(HistoryService.class), mock(BroadcastService.class), mock(LogWriter.class),
                schutz, mock(BanKickHelper.class), verzeichnis);
        var config = mock(de.lightrixx.punish.core.Konfiguration.class);
        when(config.standardScope()).thenReturn(ServerScope.global());
        var kick = new KickService(config,
                mock(HistoryService.class), mock(BroadcastService.class), mock(LogWriter.class),
                schutz, verzeichnis);
        assertEquals(0, mute.zaehleMuteAlleKandidaten(mod));
        assertEquals(0, timeout.zaehleTimeoutAlleKandidaten(mod));
        assertEquals(0, ban.zaehleBanAlleKandidaten(mod));
        assertEquals(0, kick.zaehleKickAlleKandidaten(mod));
        assertEquals(0, mute.muteAlle(60_000L, "Test", "Mod", mod,
                ServerScope.global(), "lobby").anzahl());
        assertEquals(0, timeout.timeoutAlle(60_000L, "Test", "Mod", mod,
                ServerScope.global(), "lobby").anzahl());
        assertEquals(0, kick.kickenAlle("Test", "Mod", mod).size());
        org.mockito.Mockito.verify(muteRepo).mutenAlleWennFrei(org.mockito.ArgumentMatchers.argThat(
                java.util.Collection::isEmpty));
        org.mockito.Mockito.verify(timeoutRepo).timeoutAlleWennFrei(org.mockito.ArgumentMatchers.argThat(
                java.util.Collection::isEmpty));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void muteCleanupErhaeltGleichzeitigVerlaengerteStrafe(boolean verlaengert) {
        var uuid = UUID.randomUUID();
        var scope = ServerScope.server("survival");
        long jetzt = System.currentTimeMillis();
        var alt = new MuteRecord(uuid, "Steve", "Test", "Mod", jetzt - 2000L, jetzt - 1000L, scope);
        var neu = new MuteRecord(uuid, "Steve", "Test", "Mod", jetzt, jetzt + 60_000L, scope);
        var repository = org.mockito.Mockito.spy(new MuteRepository(
                mock(de.lightrixx.punish.storage.mysql.MysqlVerbindung.class),
                mock(de.lightrixx.punish.storage.IoExecutor.class)));
        repository.mutenWennFrei(alt);
        org.mockito.Mockito.doAnswer(inv -> {
            if (verlaengert) {
                repository.ersetzenWenn(uuid, scope, alt::equals, neu);
            }
            return Optional.of(alt);
        }).doCallRealMethod().when(repository).finden(uuid, scope);
        var service = muteService(setupMit(false), repository);

        int entfernt = service.bereinigeAbgelaufeneMitHistory("System");

        assertEquals(verlaengert ? 0 : 1, entfernt);
        assertEquals(verlaengert ? Optional.of(neu) : Optional.empty(), repository.finden(uuid, scope));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false, false", "false, true", "true, false", "true, true"})
    void timeoutCleanupErhaeltGleichzeitigVerlaengerteStrafe(boolean verlaengert, boolean einzeln) {
        var uuid = UUID.randomUUID();
        var scope = ServerScope.server("survival");
        long jetzt = System.currentTimeMillis();
        var alt = new TimeoutRecord(uuid, "Steve", "Test", "Mod", jetzt - 2000L, jetzt - 1000L, scope);
        var neu = new TimeoutRecord(uuid, "Steve", "Test", "Mod", jetzt, jetzt + 60_000L, scope);
        var repository = org.mockito.Mockito.spy(new TimeoutRepository(
                mock(de.lightrixx.punish.storage.mysql.MysqlVerbindung.class),
                mock(de.lightrixx.punish.storage.IoExecutor.class)));
        repository.timeoutVerhaengenWennFrei(alt);
        if (einzeln) {
            org.mockito.Mockito.doAnswer(inv -> {
            if (verlaengert) {
                repository.ersetzenWenn(uuid, scope, alt::equals, neu);
            }
                return List.of(alt);
            }).doCallRealMethod().when(repository).alleFuer(uuid);
        } else {
        org.mockito.Mockito.doAnswer(inv -> {
            if (verlaengert) {
                repository.ersetzenWenn(uuid, scope, alt::equals, neu);
            }
            return Optional.of(alt);
        }).doCallRealMethod().when(repository).finden(uuid, scope);
        }
        var service = timeoutService(setupMit(false), repository);

        int entfernt = einzeln ? service.bereinigeAbgelaufeneFuerUuid(uuid, "System")
                : service.bereinigeAbgelaufeneMitHistory("System");

        assertEquals(verlaengert ? 0 : 1, entfernt);
        assertEquals(verlaengert ? Optional.of(neu) : Optional.empty(), repository.finden(uuid, scope));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void freezeCleanupErhaeltGleichzeitigVerlaengerteStrafe(boolean verlaengert) {
        var uuid = UUID.randomUUID();
        var scope = ServerScope.server("survival");
        long jetzt = System.currentTimeMillis();
        var alt = new de.lightrixx.punish.model.FreezeRecord(uuid, "Steve", "Test", "Mod", jetzt - 2000L, jetzt - 1000L, scope);
        var neu = new de.lightrixx.punish.model.FreezeRecord(uuid, "Steve", "Test", "Mod", jetzt, jetzt + 60_000L, scope);
        var repository = org.mockito.Mockito.spy(new de.lightrixx.punish.storage.FreezeRepository(
                mock(de.lightrixx.punish.storage.mysql.MysqlVerbindung.class),
                mock(de.lightrixx.punish.storage.IoExecutor.class)));
        repository.freezenWennFrei(alt);
        org.mockito.Mockito.doAnswer(inv -> {
            if (verlaengert) {
                repository.ersetzenWenn(uuid, scope, alt::equals, neu);
            }
            return Optional.of(alt);
        }).doCallRealMethod().when(repository).finden(uuid, scope);
        var service = new FreezeService(repository, mock(HistoryService.class), mock(BroadcastService.class),
                mock(LogWriter.class), mock(MessageService.class), mock(de.lightrixx.punish.core.AuftragsPlaner.class),
                mock(ProtectionService.class), mock(SpielerVerzeichnis.class));

        int entfernt = service.bereinigeAbgelaufeneMitHistory("System");

        assertEquals(verlaengert ? 0 : 1, entfernt);
        assertEquals(verlaengert ? Optional.of(neu) : Optional.empty(), repository.finden(uuid, scope));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void ipMuteCleanupErhaeltGleichzeitigVerlaengerteStrafe(boolean verlaengert) {
        var scope = ServerScope.server("survival");
        long jetzt = System.currentTimeMillis();
        var alt = new de.lightrixx.punish.model.IpMuteRecord("1.2.3.4", "Test", "Mod", jetzt - 2000L, jetzt - 1000L, scope);
        var neu = new de.lightrixx.punish.model.IpMuteRecord("1.2.3.4", "Test", "Mod", jetzt, jetzt + 60_000L, scope);
        var repository = org.mockito.Mockito.spy(new de.lightrixx.punish.storage.IpMuteRepository(
                mock(de.lightrixx.punish.storage.mysql.MysqlVerbindung.class),
                mock(de.lightrixx.punish.storage.IoExecutor.class)));
        repository.mutenWennFrei(alt);
        org.mockito.Mockito.doAnswer(inv -> {
            if (verlaengert) {
                repository.ersetzenWenn("1.2.3.4", scope, alt::equals, neu);
            }
            return Optional.of(alt);
        }).doCallRealMethod().when(repository).finden("1.2.3.4", scope);
        var service = new IpMuteService(repository, mock(HistoryService.class), mock(BroadcastService.class),
                mock(LogWriter.class), mock(MessageService.class), mock(de.lightrixx.punish.core.AuftragsPlaner.class),
                mock(ProtectionService.class), mock(SpielerVerzeichnis.class));

        int entfernt = service.bereinigeAbgelaufeneMitHistory("System");

        assertEquals(verlaengert ? 0 : 1, entfernt);
        assertEquals(verlaengert ? Optional.of(neu) : Optional.empty(), repository.finden("1.2.3.4", scope));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false, false", "false, true", "true, false", "true, true"})
    void ipTimeoutCleanupErhaeltGleichzeitigVerlaengerteStrafe(boolean verlaengert, boolean einzeln) {
        var scope = ServerScope.server("survival");
        long jetzt = System.currentTimeMillis();
        var alt = new de.lightrixx.punish.model.IpTimeoutRecord("1.2.3.4", "Test", "Mod", jetzt - 2000L, jetzt - 1000L, scope);
        var neu = new de.lightrixx.punish.model.IpTimeoutRecord("1.2.3.4", "Test", "Mod", jetzt, jetzt + 60_000L, scope);
        var repository = org.mockito.Mockito.spy(new de.lightrixx.punish.storage.IpTimeoutRepository(
                mock(de.lightrixx.punish.storage.mysql.MysqlVerbindung.class),
                mock(de.lightrixx.punish.storage.IoExecutor.class)));
        repository.timeoutVerhaengenWennFrei(alt);
        if (einzeln) {
            org.mockito.Mockito.doAnswer(inv -> {
            if (verlaengert) {
                repository.ersetzenWenn("1.2.3.4", scope, alt::equals, neu);
            }
                return List.of(alt);
            }).doCallRealMethod().when(repository).alleFuer("1.2.3.4");
        } else {
        org.mockito.Mockito.doAnswer(inv -> {
            if (verlaengert) {
                repository.ersetzenWenn("1.2.3.4", scope, alt::equals, neu);
            }
            return Optional.of(alt);
        }).doCallRealMethod().when(repository).finden("1.2.3.4", scope);
        }
        var service = new IpTimeoutService(repository, mock(de.lightrixx.punish.core.Konfiguration.class),
                mock(HistoryService.class), mock(BroadcastService.class), mock(LogWriter.class),
                mock(de.lightrixx.punish.core.AuftragsPlaner.class), mock(ProtectionService.class),
                mock(BanKickHelper.class), mock(SpielerVerzeichnis.class));

        int entfernt = einzeln ? service.bereinigeAbgelaufeneFuerIp("1.2.3.4", "System")
                : service.bereinigeAbgelaufeneMitHistory("System");

        assertEquals(verlaengert ? 0 : 1, entfernt);
        assertEquals(verlaengert ? Optional.of(neu) : Optional.empty(), repository.finden("1.2.3.4", scope));
    }
}
