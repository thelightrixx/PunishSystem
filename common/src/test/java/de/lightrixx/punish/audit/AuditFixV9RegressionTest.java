package de.lightrixx.punish.audit;

import de.lightrixx.punish.core.OfflineVerzeichnis;
import de.lightrixx.punish.model.BanRecord;
import de.lightrixx.punish.model.ServerScope;
import de.lightrixx.punish.storage.BanRepository;
import de.lightrixx.punish.storage.IoExecutor;
import de.lightrixx.punish.storage.YamlSpeicher;
import de.lightrixx.punish.storage.mysql.MysqlSchema;
import de.lightrixx.punish.storage.mysql.MysqlVerbindung;
import de.lightrixx.punish.storage.mysql.YamlNachMysqlMigrator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.File;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditFixV9RegressionTest {

    @TempDir
    Path temp;

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

    private record Umgebung(IoExecutor io, MysqlVerbindung mysql, MapSpeicher yaml,
                            File datenOrdner, File pluginOrdner, Map<String, Long> zaehlungen) {
    }

    private Umgebung umgebung(boolean idle) {
        IoExecutor io = mock(IoExecutor.class);
        when(io.warteAufIdle(anyLong(), any())).thenReturn(idle);
        MapSpeicher yaml = new MapSpeicher();
        Map<String, Long> zaehlungen = new HashMap<>();
        MysqlVerbindung mysql = mock(MysqlVerbindung.class);
        try {
            Connection verbindung = mock(Connection.class);
            Statement statement = mock(Statement.class);
            when(mysql.verbindung()).thenReturn(verbindung);
            when(verbindung.createStatement()).thenReturn(statement);
            when(statement.executeQuery(anyString())).thenAnswer(aufruf -> {
                String sql = aufruf.getArgument(0);
                String tabelle = sql.substring(sql.lastIndexOf(' ') + 1);
                ResultSet ergebnis = mock(ResultSet.class);
                when(ergebnis.next()).thenReturn(true);
                when(ergebnis.getLong(1)).thenReturn(zaehlungen.getOrDefault(tabelle, 0L));
                return ergebnis;
            });
        } catch (Exception e) {
            throw new IllegalStateException("MySQL-Attrappe konnte nicht aufgebaut werden.", e);
        }
        File datenOrdner = temp.resolve("daten").toFile();
        File pluginOrdner = temp.resolve("plugin").toFile();
        assertTrue(datenOrdner.isDirectory() || datenOrdner.mkdirs());
        assertTrue(pluginOrdner.isDirectory() || pluginOrdner.mkdirs());
        return new Umgebung(io, mysql, yaml, datenOrdner, pluginOrdner, zaehlungen);
    }

    private int migriere(Umgebung umgebung) {
        OfflineVerzeichnis verzeichnis = mock(OfflineVerzeichnis.class);
        return YamlNachMysqlMigrator.migrieren(umgebung.datenOrdner(), umgebung.pluginOrdner(),
                umgebung.yaml(), umgebung.io(), verzeichnis, umgebung.mysql(), 30);
    }

    private void banInYamlAnlegen(Umgebung umgebung) {
        IoExecutor ioEcht = new IoExecutor();
        try {
            BanRepository repo = new BanRepository(umgebung.datenOrdner(), umgebung.yaml(), ioEcht);
            assertTrue(repo.bannenWennFrei(new BanRecord(UUID.randomUUID(), "TestSpieler",
                    "Migrationstest", "Konsole", System.currentTimeMillis(), null, false,
                    ServerScope.global())));
            assertTrue(ioEcht.warteAufIdle(5L, TimeUnit.SECONDS));
        } finally {
            ioEcht.herunterfahren(5L, TimeUnit.SECONDS);
        }
    }

    @Test
    void fehlgeschlagenerImportWirdNichtAlsMigrationErfolgGemeldet() {
        Umgebung umgebung = umgebung(true);
        banInYamlAnlegen(umgebung);
        IllegalStateException fehler = assertThrows(IllegalStateException.class,
                () -> migriere(umgebung));
        assertTrue(fehler.getMessage().contains("unvollständig"), fehler.getMessage());
        assertTrue(fehler.getMessage().contains("Banns"), fehler.getMessage());
    }

    @Test
    void verifizierteMigrationMeldetSummeUndWartetAufIdle() {
        Umgebung umgebung = umgebung(true);
        banInYamlAnlegen(umgebung);
        umgebung.zaehlungen().put(MysqlSchema.T_BANS, 1L);
        assertEquals(1, migriere(umgebung));
        verify(umgebung.io(), atLeastOnce()).warteAufIdle(120L, TimeUnit.SECONDS);
    }

    @Test
    void nichtLeereSchreibQueueVerweigertDenStart() {
        Umgebung umgebung = umgebung(false);
        IllegalStateException fehler = assertThrows(IllegalStateException.class,
                () -> migriere(umgebung));
        assertTrue(fehler.getMessage().contains("nicht leer"), fehler.getMessage());
    }

    @Test
    void leereBestandsdatenMigrierenOhneAbweichung() {
        Umgebung umgebung = umgebung(true);
        assertEquals(0, migriere(umgebung));
    }
}
