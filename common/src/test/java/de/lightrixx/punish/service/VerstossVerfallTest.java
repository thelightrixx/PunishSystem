package de.lightrixx.punish.service;

import de.lightrixx.punish.core.AuftragsPlaner;
import de.lightrixx.punish.core.Konfiguration;
import de.lightrixx.punish.core.SpielerVerzeichnis;
import de.lightrixx.punish.model.HistoryType;
import de.lightrixx.punish.model.VerstossRecord;
import de.lightrixx.punish.model.VerstossStufe;
import de.lightrixx.punish.storage.IoExecutor;
import de.lightrixx.punish.storage.LogWriter;
import de.lightrixx.punish.storage.VerstossRepository;
import de.lightrixx.punish.storage.YamlSpeicher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VerstossVerfallTest {

    private record TestUmgebung(VerstossRepository repository, VerstossService service,
                                AutoBannService autoBann, HistoryService history, LogWriter logWriter) {
    }

    private static YamlSpeicher leererSpeicher() {
        YamlSpeicher speicher = mock(YamlSpeicher.class);
        when(speicher.laden(any(File.class))).thenReturn(Map.of());
        return speicher;
    }

    private static IoExecutor stillerExecutor() {
        IoExecutor executor = mock(IoExecutor.class);
        when(executor.warteAufIdle(anyLong(), any())).thenReturn(true);
        return executor;
    }

    private static TestUmgebung umgebung(File datenOrdner, YamlSpeicher speicher, Konfiguration config) {
        VerstossRepository repository = new VerstossRepository(datenOrdner, speicher, stillerExecutor());
        BanService banService = mock(BanService.class);
        when(banService.finden(any(UUID.class), any())).thenReturn(Optional.empty());
        AutoBannService autoBann = mock(AutoBannService.class);
        when(autoBann.autoBann(any(), any(), any(), anyInt(), anyInt())).thenReturn(true);
        HistoryService history = mock(HistoryService.class);
        BroadcastService broadcast = mock(BroadcastService.class);
        LogWriter logWriter = mock(LogWriter.class);
        MessageService messages = mock(MessageService.class);
        AuftragsPlaner planer = mock(AuftragsPlaner.class);
        SpielerVerzeichnis verzeichnis = mock(SpielerVerzeichnis.class);
        when(verzeichnis.darfDirektAusfuehren()).thenReturn(true);
        VerstossService service = new VerstossService(repository, banService, autoBann, config,
                history, broadcast, logWriter, messages, planer,
                mock(de.lightrixx.punish.service.ProtectionService.class), verzeichnis);
        return new TestUmgebung(repository, service, autoBann, history, logWriter);
    }

    private static Konfiguration configMitStufen(int maxPunkte, Map<VerstossStufe, Long> dauern) {
        Konfiguration config = mock(Konfiguration.class);
        when(config.limitSnapshot()).thenReturn(new Konfiguration.LimitSnapshot(maxPunkte, "7d"));
        when(config.verstossStandardStufe()).thenReturn(VerstossStufe.MEDIUM);
        when(config.verstossStufenDauerMillis(any())).thenAnswer(inv -> {
            VerstossStufe stufe = inv.getArgument(0);
            if (stufe == null) {
                stufe = VerstossStufe.MEDIUM;
            }
            return dauern.getOrDefault(stufe, 86_400_000L);
        });
        when(config.verstossStufenPunkte(any())).thenAnswer(inv -> {
            VerstossStufe stufe = inv.getArgument(0);
            return stufe == null ? 2 : stufe.standardPunkte();
        });
        return config;
    }

    private static Konfiguration configMitStandardDauern(int maxPunkte) {
        return configMitStufen(maxPunkte, Map.of(
                VerstossStufe.LOW, VerstossStufe.LOW.standardDauerMillis(),
                VerstossStufe.MEDIUM, VerstossStufe.MEDIUM.standardDauerMillis(),
                VerstossStufe.HIGH, VerstossStufe.HIGH.standardDauerMillis()));
    }

    private static Map<String, Object> eintrag(int nummer, String name, String grund,
                                               String ausfuehrer, long erstelltAm, String stufe) {
        Map<String, Object> eintrag = new HashMap<>();
        eintrag.put("nummer", (long) nummer);
        eintrag.put("spieler-name", name);
        eintrag.put("grund", grund);
        eintrag.put("ausfuehrer", ausfuehrer);
        eintrag.put("erstellt-am", erstelltAm);
        if (stufe != null) {
            eintrag.put("stufe", stufe);
        }
        return eintrag;
    }

    private static void legeDateiAn(File datenOrdner, UUID uuid) throws Exception {
        File verzeichnis = new File(datenOrdner, "verstoesse");
        assertTrue(verzeichnis.mkdirs() || verzeichnis.isDirectory());
        File datei = new File(verzeichnis, uuid + ".yml");
        assertTrue(datei.createNewFile() || datei.isFile());
    }

    @Test
    void punkteZaehlenGewichtetNachStufe(@TempDir File datenOrdner) {
        var umgebung = umgebung(datenOrdner, leererSpeicher(), configMitStandardDauern(99));
        UUID uuid = UUID.randomUUID();
        umgebung.service().hinzufuegen(uuid, "Steve", "Kleinigkeit", "Admin", VerstossStufe.LOW);
        umgebung.service().hinzufuegen(uuid, "Steve", "Spam", "Admin", VerstossStufe.MEDIUM);
        umgebung.service().hinzufuegen(uuid, "Steve", "Cheating", "Admin", VerstossStufe.HIGH);
        assertEquals(3, umgebung.service().anzahl(uuid));
        assertEquals(1 + 2 + 3, umgebung.service().punkte(uuid));
    }

    @Test
    void ohneStufenAngabeGiltStandard(@TempDir File datenOrdner) {
        var umgebung = umgebung(datenOrdner, leererSpeicher(), configMitStandardDauern(99));
        UUID uuid = UUID.randomUUID();
        VerstossRecord record = umgebung.service().hinzufuegen(uuid, "Steve", "Spam", "Admin");
        assertEquals(VerstossStufe.MEDIUM, record.stufe());
        assertEquals(2, umgebung.service().punkte(uuid));
    }

    @Test
    void repositoryEntferntNurVerfalleneProStufe(@TempDir File datenOrdner) {
        var umgebung = umgebung(datenOrdner, leererSpeicher(), configMitStandardDauern(99));
        UUID uuid = UUID.randomUUID();
        VerstossRecord low = umgebung.repository().hinzufuegen(
                uuid, "Steve", "Kleinigkeit", "Admin", VerstossStufe.LOW);
        VerstossRecord high = umgebung.repository().hinzufuegen(
                uuid, "Steve", "Cheating", "Admin", VerstossStufe.HIGH);
        long jetzt = System.currentTimeMillis();
        Map<VerstossStufe, Long> dauern = Map.of(
                VerstossStufe.LOW, 1L,
                VerstossStufe.MEDIUM, Long.MAX_VALUE / 2,
                VerstossStufe.HIGH, Long.MAX_VALUE / 2);
        List<VerstossRecord> entfernt = umgebung.repository().entferneVerfallene(uuid,
                record -> dauern.getOrDefault(record.stufe(), Long.MAX_VALUE / 2), jetzt + 60_000L);
        assertEquals(1, entfernt.size());
        assertEquals(low.nummer(), entfernt.getFirst().nummer());
        assertEquals(1, umgebung.repository().anzahl(uuid));
        assertEquals(high.nummer(), umgebung.repository().finden(uuid).getFirst().nummer());
    }

    @Test
    void verfalleneZaehlenNichtFuerAutoBann(@TempDir File datenOrdner) throws Exception {
        UUID uuid = UUID.randomUUID();
        long jetzt = System.currentTimeMillis();
        legeDateiAn(datenOrdner, uuid);

        YamlSpeicher speicher = mock(YamlSpeicher.class);
        when(speicher.laden(any(File.class))).thenReturn(Map.of("verstoesse", List.of(
                eintrag(1, "Steve", "Kleinigkeit vor langer Zeit", "Admin", 1000L, "low"),
                eintrag(2, "Steve", "Spam aktuell", "Admin", jetzt, "medium"))));

        Konfiguration config = configMitStufen(6, Map.of(
                VerstossStufe.LOW, 60_000L,
                VerstossStufe.MEDIUM, 86_400_000L,
                VerstossStufe.HIGH, 31_536_000_000L));

        var umgebung = umgebung(datenOrdner, speicher, config);
        umgebung.repository().laden();
        assertEquals(2, umgebung.repository().anzahl(uuid));

        umgebung.service().hinzufuegen(uuid, "Steve", "Cheating", "Admin", VerstossStufe.HIGH);
        assertEquals(2, umgebung.service().anzahl(uuid));
        assertEquals(2 + 3, umgebung.service().punkte(uuid));
        verify(umgebung.autoBann(), never()).autoBann(any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void punkteSchwelleLoestAutoBannAus(@TempDir File datenOrdner) throws Exception {
        UUID uuid = UUID.randomUUID();
        long jetzt = System.currentTimeMillis();
        legeDateiAn(datenOrdner, uuid);

        YamlSpeicher speicher = mock(YamlSpeicher.class);
        when(speicher.laden(any(File.class))).thenReturn(Map.of("verstoesse", List.of(
                eintrag(1, "Steve", "Spam aktuell", "Admin", jetzt, "medium"),
                eintrag(2, "Steve", "Cheating aktuell", "Admin", jetzt, "high"))));

        var umgebung = umgebung(datenOrdner, speicher, configMitStandardDauern(5));
        umgebung.repository().laden();

        umgebung.service().hinzufuegen(uuid, "Steve", "Kleinigkeit", "Admin", VerstossStufe.LOW);
        assertEquals(6, umgebung.service().punkte(uuid));
        verify(umgebung.autoBann(), times(1)).autoBann(any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void eintragOhneStufeBekommtStandardStufe(@TempDir File datenOrdner) throws Exception {
        UUID uuid = UUID.randomUUID();
        legeDateiAn(datenOrdner, uuid);

        YamlSpeicher speicher = mock(YamlSpeicher.class);
        when(speicher.laden(any(File.class))).thenReturn(Map.of("verstoesse", List.of(
                eintrag(1, "Steve", "Alter Eintrag", "Admin", System.currentTimeMillis(), null))));

        var umgebung = umgebung(datenOrdner, speicher, configMitStandardDauern(99));
        umgebung.repository().laden();
        List<VerstossRecord> gefunden = umgebung.repository().finden(uuid);
        assertEquals(1, gefunden.size(),
                "Altbestand ohne stufe-Schlüssel (EA-Upgrade) darf nicht in die Quarantäne.");
        assertEquals(VerstossStufe.standard(), gefunden.getFirst().stufe());
    }

    @Test
    void editAendertStufeUndPunkte(@TempDir File datenOrdner) {
        var umgebung = umgebung(datenOrdner, leererSpeicher(), configMitStandardDauern(99));
        UUID uuid = UUID.randomUUID();
        VerstossRecord record = umgebung.service().hinzufuegen(
                uuid, "Steve", "Spam", "Admin", VerstossStufe.LOW);
        assertEquals(1, umgebung.service().punkte(uuid));
        assertTrue(umgebung.service().bearbeiten(
                uuid, "Steve", record.nummer(), "Spam", VerstossStufe.HIGH, "Admin"));
        assertEquals(3, umgebung.service().punkte(uuid));
        assertEquals(VerstossStufe.HIGH,
                umgebung.repository().finden(uuid).getFirst().stufe());
    }

    @Test
    void entfernenLiefertStufeAnHistoryWeiter(@TempDir File datenOrdner) {
        var umgebung = umgebung(datenOrdner, leererSpeicher(), configMitStandardDauern(99));
        UUID uuid = UUID.randomUUID();
        VerstossRecord record = umgebung.service().hinzufuegen(
                uuid, "Steve", "Spam", "Admin", VerstossStufe.HIGH);

        umgebung.service().entfernen(uuid, "Steve", record.nummer(), "Admin");

        ArgumentCaptor<String> historyText = ArgumentCaptor.forClass(String.class);
        verify(umgebung.history(), times(1)).hinzufuegen(
                eq(HistoryType.VERSTOSS_REMOVE), anyString(), eq(uuid), anyString(),
                historyText.capture(), isNull());
        assertTrue(historyText.getValue().contains("Stufe: high"));
        assertTrue(historyText.getValue().contains("Grund war: Spam"));

        ArgumentCaptor<String> logText = ArgumentCaptor.forClass(String.class);
        verify(umgebung.logWriter(), times(1)).schreiben(
                eq(HistoryType.VERSTOSS_REMOVE), anyString(), anyString(), logText.capture());
        assertTrue(logText.getValue().contains("Stufe: high"));
    }

    @Test
    void bearbeitenLiefertStufeAnHistoryWeiter(@TempDir File datenOrdner) {
        var umgebung = umgebung(datenOrdner, leererSpeicher(), configMitStandardDauern(99));
        UUID uuid = UUID.randomUUID();
        VerstossRecord record = umgebung.service().hinzufuegen(
                uuid, "Steve", "Spam", "Admin", VerstossStufe.LOW);

        umgebung.service().bearbeiten(uuid, "Steve", record.nummer(), "Spam", VerstossStufe.HIGH, "Admin");

        ArgumentCaptor<String> historyText = ArgumentCaptor.forClass(String.class);
        verify(umgebung.history(), times(1)).hinzufuegen(
                eq(HistoryType.VERSTOSS_EDIT), anyString(), eq(uuid), anyString(),
                historyText.capture(), isNull());
        assertTrue(historyText.getValue().contains("Stufe: high"));
        assertTrue(historyText.getValue().contains("Grund: Spam"));

        ArgumentCaptor<String> logText = ArgumentCaptor.forClass(String.class);
        verify(umgebung.logWriter(), times(1)).schreiben(
                eq(HistoryType.VERSTOSS_EDIT), anyString(), anyString(), logText.capture());
        assertTrue(logText.getValue().contains("Stufe: high"));
    }

    @Test
    void ungueltigeDauerLaesstVerstossBestehen(@TempDir File datenOrdner) {
        Konfiguration config = configMitStufen(99, Map.of(
                VerstossStufe.LOW, 0L,
                VerstossStufe.MEDIUM, -5L,
                VerstossStufe.HIGH, 0L));
        var umgebung = umgebung(datenOrdner, leererSpeicher(), config);
        UUID uuid = UUID.randomUUID();
        umgebung.service().hinzufuegen(uuid, "Steve", "Spam", "Admin");
        assertEquals(0, umgebung.service().bereinigeVerfallene(uuid));
        assertEquals(1, umgebung.service().anzahl(uuid));
    }
}
