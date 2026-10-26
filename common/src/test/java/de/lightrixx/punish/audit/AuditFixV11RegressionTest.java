package de.lightrixx.punish.audit;

import de.lightrixx.punish.command.PunishStatusCommand;
import de.lightrixx.punish.command.TimeoutCommand;
import de.lightrixx.punish.core.AuftragsPlaner;
import de.lightrixx.punish.core.BefehlsSender;
import de.lightrixx.punish.core.Konfiguration;
import de.lightrixx.punish.core.MessageKeys;
import de.lightrixx.punish.core.SpielerVerzeichnis;
import de.lightrixx.punish.model.CachedPlayer;
import de.lightrixx.punish.model.MuteRecord;
import de.lightrixx.punish.model.ParseResult;
import de.lightrixx.punish.model.ReportRecord;
import de.lightrixx.punish.model.ReportStatus;
import de.lightrixx.punish.model.ServerScope;
import de.lightrixx.punish.model.VerstossStufe;
import de.lightrixx.punish.service.MessageService;
import de.lightrixx.punish.service.ProtectionService;
import de.lightrixx.punish.service.TimeoutService;
import de.lightrixx.punish.storage.IoExecutor;
import de.lightrixx.punish.storage.MuteRepository;
import de.lightrixx.punish.storage.PlayerCache;
import de.lightrixx.punish.storage.ReportRepository;
import de.lightrixx.punish.storage.VerstossRepository;
import de.lightrixx.punish.storage.YamlSpeicher;
import de.lightrixx.punish.storage.mysql.MysqlSchema;
import de.lightrixx.punish.storage.mysql.MysqlVerbindung;
import de.lightrixx.punish.util.TimeParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditFixV11RegressionTest {

    static class MapSpeicher implements YamlSpeicher {
        final Map<String, Map<String, Object>> dateien = new ConcurrentHashMap<>();

        @Override
        public Map<String, Object> laden(File datei) {
            Map<String, Object> daten = dateien.get(datei.getName());
            return daten == null ? Map.of() : new HashMap<>(daten);
        }

        @Override
        public void speichern(Map<String, Object> daten, File datei) {
            dateien.put(datei.getName(), new HashMap<>(daten));
        }
    }

    private IoExecutor io;

    private IoExecutor executor() {
        io = new IoExecutor();
        return io;
    }

    private void idle() {
        try {
            assertTrue(io.warteAufIdle(5, TimeUnit.SECONDS));
        } finally {
            io.herunterfahren(5, TimeUnit.SECONDS);
        }
    }

    private static ReportRecord reportDatensatz(long id) {
        return new ReportRecord(id, UUID.randomUUID(), "Ziel", UUID.randomUUID(),
                "Reporter", "Grund", "lobby", System.currentTimeMillis(),
                ReportStatus.OFFEN, "", 0L, "");
    }

    @Test
    void b501MuteMigrationErsetztTabelleVollstaendig() throws Exception {
        IoExecutor executor = executor();
        MysqlVerbindung mysql = mock(MysqlVerbindung.class);
        Connection verbindung = mock(Connection.class);
        Statement statement = mock(Statement.class);
        PreparedStatement prepared = mock(PreparedStatement.class);
        when(mysql.verbindung()).thenReturn(verbindung);
        when(verbindung.createStatement()).thenReturn(statement);
        when(verbindung.prepareStatement(anyString())).thenReturn(prepared);
        when(verbindung.getAutoCommit()).thenReturn(true);

        MuteRepository repository = new MuteRepository(mysql, executor);
        int anzahl = repository.migrationImport(List.of(new MuteRecord(UUID.randomUUID(),
                "Steve", "Spam", "Mod", System.currentTimeMillis(),
                System.currentTimeMillis() + 60000L, ServerScope.global())));
        assertEquals(1, anzahl);
        idle();

        verify(statement).execute("DELETE FROM " + MysqlSchema.T_MUTES);
        verify(prepared).executeBatch();
    }

    @Test
    void b504ReportZaehlerWirdBeiMigrationNichtZurueckgesetzt() throws Exception {
        IoExecutor executor = executor();
        MysqlVerbindung mysql = mock(MysqlVerbindung.class);
        Connection verbindung = mock(Connection.class);
        Statement statement = mock(Statement.class);
        PreparedStatement prepared = mock(PreparedStatement.class);
        when(mysql.verbindung()).thenReturn(verbindung);
        when(verbindung.createStatement()).thenReturn(statement);
        when(verbindung.prepareStatement(anyString())).thenReturn(prepared);
        when(verbindung.getAutoCommit()).thenReturn(true);

        ReportRepository repository = new ReportRepository(mysql, executor);
        assertEquals(1, repository.migrationImport(List.of(reportDatensatz(3L)), 10L));
        ReportRecord neu = repository.hinzufuegen(UUID.randomUUID(), "Ziel",
                UUID.randomUUID(), "Reporter", "Grund", "lobby");
        assertTrue(neu.id() > 10L,
                "Report-ID darf einen höheren YAML-Zählerstand nach der Migration nicht wiederverwenden.");
        idle();
    }

    @Test
    void b505VerstossOhneStufeLaedtTolerant(@TempDir File ordner) throws Exception {
        MapSpeicher speicher = new MapSpeicher();
        UUID uuid = UUID.randomUUID();
        Map<String, Object> eintrag = new HashMap<>();
        eintrag.put("nummer", 1);
        eintrag.put("spieler-name", "Steve");
        eintrag.put("grund", "Spam");
        eintrag.put("ausfuehrer", "Admin");
        eintrag.put("erstellt-am", System.currentTimeMillis());
        Map<String, Object> daten = new HashMap<>();
        daten.put("verstoesse", List.of(eintrag));
        speicher.dateien.put(uuid + ".yml", daten);
        File unterordner = new File(ordner, "verstoesse");
        assertTrue(unterordner.mkdirs() || unterordner.isDirectory());
        Files.writeString(new File(unterordner, uuid + ".yml").toPath(), "# Testdatei",
                StandardCharsets.UTF_8);

        IoExecutor executor = executor();
        VerstossRepository repository = new VerstossRepository(ordner, speicher, executor);
        repository.laden();
        assertEquals(1, repository.anzahl(uuid),
                "Ein Verstoß ohne stufe-Schlüssel (alter EA-Bestand) darf nicht in die Quarantäne.");
        assertEquals(VerstossStufe.standard(), repository.finden(uuid).get(0).stufe());
        idle();
    }

    @Test
    void b505VerstossMitUngueltigerStufeBleibtInQuarantaene(@TempDir File ordner) throws Exception {
        MapSpeicher speicher = new MapSpeicher();
        UUID uuid = UUID.randomUUID();
        Map<String, Object> eintrag = new HashMap<>();
        eintrag.put("nummer", 1);
        eintrag.put("spieler-name", "Steve");
        eintrag.put("grund", "Spam");
        eintrag.put("ausfuehrer", "Admin");
        eintrag.put("erstellt-am", System.currentTimeMillis());
        eintrag.put("stufe", "garbage!!");
        Map<String, Object> daten = new HashMap<>();
        daten.put("verstoesse", List.of(eintrag));
        speicher.dateien.put(uuid + ".yml", daten);
        File unterordner = new File(ordner, "verstoesse");
        assertTrue(unterordner.mkdirs() || unterordner.isDirectory());
        Files.writeString(new File(unterordner, uuid + ".yml").toPath(), "# Testdatei",
                StandardCharsets.UTF_8);

        IoExecutor executor = executor();
        VerstossRepository repository = new VerstossRepository(ordner, speicher, executor);
        repository.laden();
        assertEquals(0, repository.anzahl(uuid),
                "Eine ungültige Verstoß-Stufe bleibt korrupt und wird quarantänisiert.");
        idle();
    }

    @Test
    void b702IpAnzeigeIstOhneAltsRechtMaskiert() {
        BefehlsSender mitAlts = mock(BefehlsSender.class);
        when(mitAlts.hatRecht("punish.alts")).thenReturn(true);
        assertEquals("203.0.113.7", PunishStatusCommand.maskeIp(mitAlts, "203.0.113.7"));

        BefehlsSender ohneAlts = mock(BefehlsSender.class);
        when(ohneAlts.hatRecht("punish.alts")).thenReturn(false);
        assertEquals("203.0.…", PunishStatusCommand.maskeIp(ohneAlts, "203.0.113.7"));
        assertEquals("2001:db8:…", PunishStatusCommand.maskeIp(ohneAlts, "2001:db8::1"));
        assertEquals("…", PunishStatusCommand.maskeIp(ohneAlts, "unbekannt"),
                "Nicht parsebare Werte werden vollständig maskiert.");
    }

    @Test
    void b701TimeoutFehlschlagMeldetGenerischenFehler() {
        UUID uuid = UUID.randomUUID();
        MessageService messages = mock(MessageService.class);
        TimeoutService timeoutService = mock(TimeoutService.class);
        when(timeoutService.istAbgedeckt(eq(uuid), any())).thenReturn(false);
        when(timeoutService.timeoutVerhaengen(any(), any(), anyLong(), any(), any(), any(),
                any(), anyBoolean())).thenReturn(false);

        Konfiguration config = mock(Konfiguration.class);
        when(config.standardScope()).thenReturn(ServerScope.global());
        when(config.bekannteServer()).thenReturn(List.of("lobby"));
        when(config.timeoutMaxMinuten()).thenReturn(1440L);
        when(config.muteMaxMinuten()).thenReturn(43200L);
        when(config.standardGrund(anyString())).thenReturn("Regelverstoß");
        when(config.serverName()).thenReturn("lobby");

        PlayerCache cache = mock(PlayerCache.class);
        when(cache.findenNachName("Ziel"))
                .thenReturn(Optional.of(new CachedPlayer(uuid, "Ziel", "1.2.3.4",
                        System.currentTimeMillis())));
        when(cache.istFrischeNamensZuordnung("Ziel")).thenReturn(true);

        ProtectionService schutz = mock(ProtectionService.class);
        when(schutz.istSelbstbestrafung(any(BefehlsSender.class), any(UUID.class))).thenReturn(false);
        when(schutz.pruefeSchutzSofort(any(UUID.class), any()))
                .thenReturn(Optional.of(ProtectionService.SchutzErgebnis.NICHT_GESCHUETZT));
        when(schutz.pruefeSchutz(any(UUID.class), any()))
                .thenReturn(ProtectionService.SchutzErgebnis.NICHT_GESCHUETZT);

        SpielerVerzeichnis verzeichnis = mock(SpielerVerzeichnis.class);
        when(verzeichnis.onlineSpielerNachName(anyString())).thenReturn(Optional.empty());
        when(verzeichnis.onlineSpieler()).thenReturn(List.of());

        TimeParser zeit = mock(TimeParser.class);
        when(zeit.parsen("10m")).thenReturn(new ParseResult.Geltzeit(600000L));

        BefehlsSender sender = mock(BefehlsSender.class);
        when(sender.hatRecht(anyString())).thenReturn(true);
        when(sender.istSpieler()).thenReturn(false);
        when(sender.name()).thenReturn("Konsole");

        TimeoutCommand befehl = new TimeoutCommand(messages, config, schutz, cache,
                timeoutService, zeit, mock(AuftragsPlaner.class), verzeichnis);
        befehl.ausfuehren(sender, new String[]{"Ziel", "10m", "Spamming"});
        verify(messages).senden(any(), eq(MessageKeys.AKTION_FEHLGESCHLAGEN));
        verify(messages, never()).senden(any(), eq(MessageKeys.DAUER_UNGUELTIG));
    }
}
