package de.lightrixx.punish.velocity;

import de.lightrixx.punish.storage.IoExecutor;
import de.lightrixx.punish.storage.mysql.MysqlVerbindung;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ModerationsSpeicherMysqlTest {
    @TempDir File ordner;

    @Test
    void yamlDatenWerdenNurBeiFehlendemMysqlMetastandTransaktionalImportiert() throws Exception {
        var yaml = new SnakeYamlSpeicher();
        yaml.speichern(Map.of("zaehler", 17L, "eintraege", List.of(Map.of("id", 12L, "name", "Fall"))),
                new File(ordner, "faelle.yml"));
        var mysql = mock(MysqlVerbindung.class);
        var verbindung = mock(Connection.class);
        var lesen = mock(PreparedStatement.class);
        var schreiben = mock(PreparedStatement.class);
        var rs = mock(ResultSet.class);
        when(mysql.verbindung()).thenReturn(verbindung);
        when(verbindung.prepareStatement(startsWith("SELECT"))).thenReturn(lesen);
        when(verbindung.prepareStatement(startsWith("INSERT"))).thenReturn(schreiben);
        when(lesen.executeQuery()).thenReturn(rs);
        var io = new IoExecutor();
        try {
            var speicher = new ModerationsSpeicher(ordner, yaml, io, mysql, "faelle");
            speicher.laden();
            assertEquals(1, speicher.alle().size());
            assertEquals(18L, speicher.naechsteId());
            verify(verbindung).commit();
            verify(schreiben).setLong(2, 12L);
            verify(schreiben).setLong(2, 17L);
            clearInvocations(verbindung, schreiben);
            when(rs.next()).thenReturn(true, false);
            when(rs.getLong(1)).thenReturn(0L);
            when(rs.getLong(3)).thenReturn(17L);
            when(rs.getString(2)).thenReturn("{}");
            var leererBestand = new ModerationsSpeicher(ordner, yaml, io, mysql, "faelle");
            leererBestand.laden();
            assertTrue(leererBestand.alle().isEmpty());
            assertEquals(18, leererBestand.naechsteId());
            verify(verbindung, never()).commit();
        } finally {
            assertTrue(io.herunterfahren(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void defekteYamlStrukturWirdNichtAlsLeererBestandUeberschrieben() {
        var yaml = new SnakeYamlSpeicher();
        yaml.speichern(Map.of("eintraege", "kaputt"), new File(ordner, "faelle.yml"));
        var io = new IoExecutor();
        try {
            assertThrows(IllegalStateException.class, () -> new ModerationsSpeicher(ordner, yaml, io, null, "faelle").laden());
        } finally {
            assertTrue(io.herunterfahren(5, TimeUnit.SECONDS));
        }
    }
}
