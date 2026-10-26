package de.lightrixx.punish.storage;

import de.lightrixx.punish.model.HistoryEntry;
import de.lightrixx.punish.model.HistoryType;
import de.lightrixx.punish.storage.mysql.MysqlSchema;
import de.lightrixx.punish.storage.mysql.MysqlVerbindung;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression fuer S2-03 (Audit-Fix F2): Der Ueberschuss-Trimmung nach MySQL-Archiv darf
 * nur passieren, wenn ARCHIVIEREN erfolgreich war. MySQL-Fehlversuch = Bestand bleibt im Memory.
 *
 * <p>Archiv- und Shard-Anweisungen haben getrennte Statements, damit ein Archivfehler nicht
 * zugleich die normalen Shard-Schreibvorgaenge simuliert (und umgekehrt).</p>
 */
class HistoryArchivUeberschussTest {

    private static final int MAX_EINTRAEGE = 1000;

    private IoExecutor io;
    private MysqlVerbindung mysql;
    private Connection verbindung;
    private PreparedStatement archivEinfuegen;
    private PreparedStatement archivLoeschen;
    private PreparedStatement shardEinfuegen;
    private PreparedStatement shardLoeschen;
    private HistoryRepository repository;

    @BeforeEach
    void setup() throws Exception {
        io = new IoExecutor();
        mysql = mock(MysqlVerbindung.class);
        verbindung = mock(Connection.class);
        archivEinfuegen = mock(PreparedStatement.class);
        archivLoeschen = mock(PreparedStatement.class);
        shardEinfuegen = mock(PreparedStatement.class);
        shardLoeschen = mock(PreparedStatement.class);
        when(mysql.verbindung()).thenReturn(verbindung);
        when(verbindung.getAutoCommit()).thenReturn(true);
        when(verbindung.prepareStatement(anyString())).thenAnswer(aufruf -> {
            String sql = aufruf.getArgument(0);
            if (sql.contains(MysqlSchema.T_HISTORY_ARCHIV)) {
                return archivEinfuegen;
            }
            if (sql.startsWith("DELETE FROM " + MysqlSchema.T_HISTORY)
                    && sql.contains("WHERE id = ?")) {
                return archivLoeschen;
            }
            if (sql.startsWith("DELETE FROM " + MysqlSchema.T_HISTORY)) {
                return shardLoeschen;
            }
            return shardEinfuegen;
        });
        repository = new HistoryRepository(mysql, io);
    }

    @AfterEach
    void teardown() {
        io.herunterfahren(5, TimeUnit.SECONDS);
    }

    @Test
    void fehlgeschlageneArchivierung_laesstBestandUnveraendert() throws Exception {
        when(archivEinfuegen.executeBatch()).thenThrow(new SQLException("Archiv-Aus"));

        UUID ziel = UUID.randomUUID();
        for (int i = 1; i <= MAX_EINTRAEGE + 1; i++) {
            repository.hinzufuegen(eintrag(i), ziel);
        }

        assertBestand(ziel, MAX_EINTRAEGE + 1);
        verify(archivEinfuegen).executeBatch();
        verify(archivLoeschen, never()).executeBatch();
        verify(shardEinfuegen, atLeastOnce()).executeUpdate();
    }

    @Test
    void erfolgreicheArchivierung_kuerztAufLimit() throws Exception {
        when(archivEinfuegen.executeBatch()).thenReturn(new int[0]);

        UUID ziel = UUID.randomUUID();
        for (int i = 1; i <= MAX_EINTRAEGE + 3; i++) {
            repository.hinzufuegen(eintrag(i), ziel);
        }

        assertBestand(ziel, MAX_EINTRAEGE);
        ArgumentCaptor<Long> ids = ArgumentCaptor.forClass(Long.class);
        verify(archivEinfuegen, atLeastOnce()).setLong(eq(1), ids.capture());
        assertTrue(ids.getAllValues().containsAll(List.of(1L, 2L, 3L)),
                "Die drei aeltesten Eintraege muessen im Archiv landen: " + ids.getAllValues());
        assertEquals(3L, ids.getAllValues().stream().distinct().count());
        verify(archivLoeschen, atLeastOnce()).executeBatch();
        verify(archivLoeschen, atLeastOnce()).setLong(eq(1), anyLong());
        verify(shardEinfuegen, atLeastOnce()).executeUpdate();
    }

    private void assertBestand(UUID ziel, int erwartet) throws Exception {
        assertTrue(io.warteAufIdle(5, TimeUnit.SECONDS), "IO-Queue sollte leer sein.");
        List<HistoryEntry> bestand = repository.finden(ziel, 5000);
        assertEquals(erwartet, bestand.size());
    }

    private static HistoryEntry eintrag(int laufnummer) {
        return new HistoryEntry(laufnummer, HistoryType.BAN, "Console", "Test-" + laufnummer,
                "Grund " + laufnummer, 1_000_000L + laufnummer * 1000L, null, true);
    }
}
