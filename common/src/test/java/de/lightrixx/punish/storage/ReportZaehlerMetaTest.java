package de.lightrixx.punish.storage;

import de.lightrixx.punish.storage.mysql.MysqlSchema;
import de.lightrixx.punish.storage.mysql.MysqlVerbindung;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReportZaehlerMetaTest {

    @Test
    void gespeicherterZaehlerUeberlebtLeerenBestand() throws Exception {
        MysqlVerbindung mysql = mock(MysqlVerbindung.class);
        Connection verbindung = mock(Connection.class);
        Statement reportAbfrage = mock(Statement.class);
        ResultSet leereReports = mock(ResultSet.class);
        PreparedStatement metaLesen = mock(PreparedStatement.class);
        PreparedStatement metaSchreiben = mock(PreparedStatement.class);
        PreparedStatement zeileSchreiben = mock(PreparedStatement.class);
        ResultSet metaErgebnis = mock(ResultSet.class);

        when(mysql.verbindung()).thenReturn(verbindung);
        when(verbindung.createStatement()).thenReturn(reportAbfrage);
        when(reportAbfrage.executeQuery(anyString())).thenReturn(leereReports);
        when(leereReports.next()).thenReturn(false);
        when(verbindung.prepareStatement(contains("SELECT wert FROM " + MysqlSchema.T_META)))
                .thenReturn(metaLesen);
        when(verbindung.prepareStatement(contains("INSERT INTO " + MysqlSchema.T_META)))
                .thenReturn(metaSchreiben);
        when(verbindung.prepareStatement(contains("INSERT INTO " + MysqlSchema.T_REPORTS)))
                .thenReturn(zeileSchreiben);
        when(metaLesen.executeQuery()).thenReturn(metaErgebnis);
        when(metaErgebnis.next()).thenReturn(true, false);
        when(metaErgebnis.getString(1)).thenReturn("7");

        IoExecutor io = new IoExecutor();
        try {
            ReportRepository repository = new ReportRepository(mysql, io);
            repository.laden();

            assertEquals(7L, repository.zaehlerStand(),
                    "Der persistierte Zähler muss auch bei leerem Report-Bestand gelten");

            var neu = repository.hinzufuegen(UUID.randomUUID(), "Ziel", UUID.randomUUID(),
                    "Alex", "Hacking", "lobby");
            assertEquals(8L, neu.id(), "Nach dem gespeicherten Zähler 7 muss die nächste ID 8 sein");

            assertTrue(io.warteAufIdle(5, TimeUnit.SECONDS));
            ArgumentCaptor<String> werte = ArgumentCaptor.forClass(String.class);
            verify(metaSchreiben, atLeastOnce()).setString(eq(2), werte.capture());
            assertTrue(werte.getAllValues().contains("8"),
                    "Der Zählerstand 8 muss in punish_meta geschrieben werden: " + werte.getAllValues());
        } finally {
            io.herunterfahren(2, TimeUnit.SECONDS);
        }
    }
}
