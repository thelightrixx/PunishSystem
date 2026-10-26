package de.lightrixx.punish.service;

import de.lightrixx.punish.core.Konfiguration;
import de.lightrixx.punish.model.VerstossRecord;
import de.lightrixx.punish.model.VerstossStufe;
import de.lightrixx.punish.storage.IoExecutor;
import de.lightrixx.punish.storage.WarnRepository;
import de.lightrixx.punish.storage.YamlSpeicher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WarnEskalationTest {

    private static WarnRepository repository(File datenOrdner) {
        YamlSpeicher speicher = mock(YamlSpeicher.class);
        when(speicher.laden(any(File.class))).thenReturn(Map.of());
        return new WarnRepository(datenOrdner, speicher, mock(IoExecutor.class));
    }

    private static Konfiguration eskalationsConfig(Map<Integer, VerstossStufe> schwellen) {
        Konfiguration config = mock(Konfiguration.class);
        when(config.warnEskalationAktiv()).thenReturn(true);
        when(config.warnEskalationsStufeFuer(anyInt()))
                .thenAnswer(inv -> Optional.ofNullable(schwellen.get(inv.getArgument(0))));
        when(config.standardGrund(anyString())).thenReturn("Auto %anzahl% %stufe% %grund%");
        when(config.broadcastAktiv(de.lightrixx.punish.core.MessageKeys.BROADCAST_WARN_ESKALATION))
                .thenReturn(true);
        when(config.broadcastNachricht(de.lightrixx.punish.core.MessageKeys.BROADCAST_WARN_ESKALATION))
                .thenReturn("Eskalation %spieler% %anzahl% %stufe%");
        return config;
    }

    private static Map<String, Object> schwelle(Object ab, Object stufe) {
        Map<String, Object> eintrag = new HashMap<>();
        if (ab != null) {
            eintrag.put("ab", ab);
        }
        if (stufe != null) {
            eintrag.put("stufe", stufe);
        }
        return eintrag;
    }

    @Test
    void dreiWarnsLoesenLeichtenVerstossAus(@TempDir File datenOrdner) {
        WarnService service = new WarnService(repository(datenOrdner));
        VerstossService verstoesse = mock(VerstossService.class);
        service.mitEskalation(
                eskalationsConfig(Map.of(3, VerstossStufe.LOW, 5, VerstossStufe.MEDIUM)),
                verstoesse);

        UUID uuid = UUID.randomUUID();
        service.hinzufuegen(uuid, "Steve", "Spam 1", "Admin");
        service.hinzufuegen(uuid, "Steve", "Spam 2", "Admin");
        verify(verstoesse, never()).hinzufuegen(
                any(), any(), any(), any(), any(VerstossStufe.class), eq(true));

        service.hinzufuegen(uuid, "Steve", "Spam 3", "Admin");
        assertEquals(3, service.anzahl(uuid));
        verify(verstoesse, times(1)).hinzufuegen(
                eq(uuid), eq("Steve"), anyString(), eq("Admin"), eq(VerstossStufe.LOW), eq(true));

        service.hinzufuegen(uuid, "Steve", "Spam 4", "Admin");
        verify(verstoesse, times(1)).hinzufuegen(
                any(), any(), any(), any(), any(VerstossStufe.class), eq(true));
    }

    @Test
    void eskalationSendetEigenenBroadcastStattStandard(@TempDir File datenOrdner) {
        BroadcastService broadcast = mock(BroadcastService.class);
        WarnService service = new WarnService(repository(datenOrdner), broadcast);
        VerstossService verstoesse = mock(VerstossService.class);
        when(verstoesse.hinzufuegen(
                any(), any(), any(), any(), any(VerstossStufe.class), eq(true)))
                .thenReturn(new VerstossRecord(7, "Steve", "Auto", "Admin",
                        System.currentTimeMillis(), VerstossStufe.LOW));
        service.mitEskalation(
                eskalationsConfig(Map.of(3, VerstossStufe.LOW)),
                verstoesse);

        UUID uuid = UUID.randomUUID();
        service.hinzufuegen(uuid, "Steve", "Spam 1", "Admin");
        service.hinzufuegen(uuid, "Steve", "Spam 2", "Admin");
        service.hinzufuegen(uuid, "Steve", "Spam 3", "Admin");

        verify(verstoesse, times(1)).hinzufuegen(
                eq(uuid), eq("Steve"), anyString(), eq("Admin"), eq(VerstossStufe.LOW), eq(true));
        verify(broadcast, times(1)).senden(
                eq(de.lightrixx.punish.core.MessageKeys.BROADCAST_WARN_ESKALATION),
                org.mockito.ArgumentMatchers.argThat((Map<String, String> platzhalter) ->
                        platzhalter != null
                                && "Steve".equals(platzhalter.get("spieler"))
                                && "Admin".equals(platzhalter.get("ausfuehrer"))
                                && "3".equals(platzhalter.get("anzahl"))
                                && "low".equals(platzhalter.get("stufe"))
                                && "Spam 3".equals(platzhalter.get("warn-grund"))
                                && "7".equals(platzhalter.get("id"))),
                eq(false));
    }

    @Test
    void standardBroadcastWennCustomDeaktiviert(@TempDir File datenOrdner) {
        BroadcastService broadcast = mock(BroadcastService.class);
        WarnService service = new WarnService(repository(datenOrdner), broadcast);
        VerstossService verstoesse = mock(VerstossService.class);
        Konfiguration config = eskalationsConfig(Map.of(1, VerstossStufe.LOW));
        when(config.broadcastAktiv(de.lightrixx.punish.core.MessageKeys.BROADCAST_WARN_ESKALATION))
                .thenReturn(false);
        service.mitEskalation(config, verstoesse);

        UUID uuid = UUID.randomUUID();
        service.hinzufuegen(uuid, "Steve", "Spam", "Admin");

        verify(verstoesse, times(1)).hinzufuegen(
                eq(uuid), eq("Steve"), anyString(), eq("Admin"), eq(VerstossStufe.LOW), eq(true));
        verify(broadcast, never()).senden(
                eq(de.lightrixx.punish.core.MessageKeys.BROADCAST_WARN_ESKALATION),
                any(), eq(false));
    }

    @Test
    void silentWarnMachtAuchEskalationSilent(@TempDir File datenOrdner) {
        BroadcastService broadcast = mock(BroadcastService.class);
        WarnService service = new WarnService(repository(datenOrdner), broadcast);
        VerstossService verstoesse = mock(VerstossService.class);
        when(verstoesse.hinzufuegen(
                any(), any(), any(), any(), any(VerstossStufe.class), eq(true)))
                .thenReturn(new VerstossRecord(1, "Steve", "Auto", "Admin",
                        System.currentTimeMillis(), VerstossStufe.LOW));
        service.mitEskalation(
                eskalationsConfig(Map.of(1, VerstossStufe.LOW)),
                verstoesse);

        UUID uuid = UUID.randomUUID();
        service.hinzufuegen(uuid, "Steve", "Spam", "Admin", true);

        verify(verstoesse, times(1)).hinzufuegen(
                any(), any(), any(), any(), any(VerstossStufe.class), eq(true));
        verify(broadcast, times(1)).senden(
                eq(de.lightrixx.punish.core.MessageKeys.BROADCAST_WARN_ESKALATION),
                any(), eq(true));
    }

    @Test
    void eskalationOhneEigenenBroadcastSendetGenauEinenVerstossBroadcast(@TempDir File datenOrdner) {
        BroadcastService broadcast = mock(BroadcastService.class);
        WarnService service = new WarnService(repository(datenOrdner), broadcast);
        VerstossService verstoesse = mock(VerstossService.class);
        when(verstoesse.hinzufuegen(
                any(), any(), any(), any(), any(VerstossStufe.class), anyBoolean()))
                .thenReturn(new VerstossRecord(9, "Steve", "Auto", "Admin",
                        System.currentTimeMillis(), VerstossStufe.LOW));
        Konfiguration config = eskalationsConfig(Map.of(1, VerstossStufe.LOW));
        when(config.broadcastAktiv(de.lightrixx.punish.core.MessageKeys.BROADCAST_WARN_ESKALATION))
                .thenReturn(false);
        service.mitEskalation(config, verstoesse);

        UUID uuid = UUID.randomUUID();
        service.hinzufuegen(uuid, "Steve", "Spam", "Admin");

        verify(verstoesse, times(1)).hinzufuegen(
                eq(uuid), eq("Steve"), anyString(), eq("Admin"), eq(VerstossStufe.LOW), eq(true));
        verify(broadcast, times(1)).senden(
                eq(de.lightrixx.punish.core.MessageKeys.BROADCAST_VERSTOSS_ADD),
                org.mockito.ArgumentMatchers.argThat((Map<String, String> platzhalter) ->
                        platzhalter != null
                                && "Steve".equals(platzhalter.get("spieler"))
                                && "1".equals(platzhalter.get("anzahl"))
                                && "low".equals(platzhalter.get("stufe"))),
                eq(false));
        verify(broadcast, never()).senden(
                eq(de.lightrixx.punish.core.MessageKeys.BROADCAST_WARN_ESKALATION),
                any(), anyBoolean());
    }

    @Test
    void eskalationSendetNieZweiVerstossBroadcasts(@TempDir File datenOrdner) {
        BroadcastService broadcast = mock(BroadcastService.class);
        WarnService service = new WarnService(repository(datenOrdner), broadcast);
        VerstossService verstoesse = mock(VerstossService.class);
        when(verstoesse.hinzufuegen(
                any(), any(), any(), any(), any(VerstossStufe.class), anyBoolean()))
                .thenReturn(new VerstossRecord(2, "Steve", "Auto", "Admin",
                        System.currentTimeMillis(), VerstossStufe.LOW));
        service.mitEskalation(eskalationsConfig(Map.of(1, VerstossStufe.LOW)), verstoesse);

        UUID uuid = UUID.randomUUID();
        service.hinzufuegen(uuid, "Steve", "Spam", "Admin");

        verify(verstoesse, times(1)).hinzufuegen(
                eq(uuid), eq("Steve"), anyString(), eq("Admin"), eq(VerstossStufe.LOW), eq(true));
    }

    @Test
    void keineEskalationWennDeaktiviert(@TempDir File datenOrdner) {
        WarnService service = new WarnService(repository(datenOrdner));
        VerstossService verstoesse = mock(VerstossService.class);
        Konfiguration config = mock(Konfiguration.class);
        when(config.warnEskalationAktiv()).thenReturn(false);
        service.mitEskalation(config, verstoesse);

        UUID uuid = UUID.randomUUID();
        service.hinzufuegen(uuid, "Steve", "Spam", "Admin");
        assertEquals(1, service.anzahl(uuid));
        verify(verstoesse, never()).hinzufuegen(
                any(), any(), any(), any(), any(VerstossStufe.class), eq(false));
    }

    @Test
    void warnBleibtBestehenWennEskalationFehlschlaegt(@TempDir File datenOrdner) {
        WarnService service = new WarnService(repository(datenOrdner));
        VerstossService verstoesse = mock(VerstossService.class);
        when(verstoesse.hinzufuegen(
                any(), any(), any(), any(), any(VerstossStufe.class), eq(true)))
                .thenThrow(new IllegalStateException("geschützt"));
        service.mitEskalation(eskalationsConfig(Map.of(1, VerstossStufe.LOW)), verstoesse);

        UUID uuid = UUID.randomUUID();
        service.hinzufuegen(uuid, "Steve", "Spam", "Admin");
        assertEquals(1, service.anzahl(uuid));
    }

    @Test
    void blockierteEskalationWirdBeimNaechstenWarnNachgeholt(@TempDir File datenOrdner) {
        WarnService service = new WarnService(repository(datenOrdner));
        VerstossService verstoesse = mock(VerstossService.class);
        when(verstoesse.hinzufuegen(
                any(), any(), any(), any(), any(VerstossStufe.class), eq(true)))
                .thenThrow(new IllegalStateException("Auto-Bann aktiv"))
                .thenReturn(new VerstossRecord(1, "Steve", "Auto", "Admin",
                        System.currentTimeMillis(), VerstossStufe.LOW));
        service.mitEskalation(
                eskalationsConfig(Map.of(3, VerstossStufe.LOW)),
                verstoesse);

        UUID uuid = UUID.randomUUID();
        service.hinzufuegen(uuid, "Steve", "Spam 1", "Admin");
        service.hinzufuegen(uuid, "Steve", "Spam 2", "Admin");
        service.hinzufuegen(uuid, "Steve", "Spam 3", "Admin");
        assertEquals(3, service.anzahl(uuid));

        service.hinzufuegen(uuid, "Steve", "Spam 4", "Admin");
        verify(verstoesse, times(2)).hinzufuegen(
                eq(uuid), eq("Steve"), anyString(), eq("Admin"), eq(VerstossStufe.LOW), eq(true));
    }

    @Test
    void standardSchwellenSindDreiFuenfZehn() {
        assertEquals(Map.of(3, VerstossStufe.LOW, 5, VerstossStufe.MEDIUM, 10, VerstossStufe.HIGH),
                Konfiguration.standardWarnSchwellen());
    }

    @Test
    void normierungFiltertUngueltigesErsterGewinnt() {
        List<Object> roh = new ArrayList<>();
        roh.add(schwelle(3, "low"));
        roh.add(schwelle(5, "mittel"));
        roh.add(schwelle(10, "SCHWER"));
        roh.add(schwelle(0, "low"));
        roh.add(schwelle(-2, "high"));
        roh.add(schwelle("keine-zahl", "low"));
        roh.add(schwelle(7, "unbekannt"));
        roh.add(schwelle(8, null));
        roh.add(schwelle(null, "low"));
        roh.add(schwelle(3, "high"));
        roh.add("kein-eintrag");
        roh.add(null);

        assertEquals(Map.of(3, VerstossStufe.LOW, 5, VerstossStufe.MEDIUM, 10, VerstossStufe.HIGH),
                Konfiguration.normiereWarnSchwellen(roh));
        assertEquals(Map.of(), Konfiguration.normiereWarnSchwellen(null));
        assertEquals(Map.of(), Konfiguration.normiereWarnSchwellen(List.of()));
    }
}
