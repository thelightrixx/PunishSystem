package de.lightrixx.punish.velocity;

import de.lightrixx.punish.core.Konfiguration;
import de.lightrixx.punish.model.WarnRecord;
import de.lightrixx.punish.service.WarnService;
import de.lightrixx.punish.storage.WarnRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

class WarnAblaufTest {

    private static final long TAG = 24L * 60L * 60L * 1000L;

    private WarnRepository repository;
    private Konfiguration config;
    private WarnService service;
    private UUID ziel;

    @BeforeEach
    void aufsetzen() {
        repository = Mockito.mock(WarnRepository.class);
        config = Mockito.mock(Konfiguration.class);
        service = new WarnService(repository);
        service.mitEskalation(config, null);
        ziel = UUID.randomUUID();
    }

    private void mitWarns(long alterTage, long ablaufMillis) {
        long jetzt = System.currentTimeMillis();
        when(config.warnAblaufMillis()).thenReturn(ablaufMillis);
        when(repository.finden(ziel)).thenReturn(List.of(
                new WarnRecord(1, "Steve", "Alt", "ModA", jetzt - alterTage * TAG),
                new WarnRecord(2, "Steve", "Neu", "ModA", jetzt - TAG)));
        when(repository.anzahl(ziel)).thenReturn(2);
    }

    @Test
    void abgelaufeneWarnsZaehlenNichtMehr() {
        mitWarns(31, 30 * TAG);
        assertEquals(1, service.anzahl(ziel));
        assertEquals(1, service.aktiveWarns(ziel).size());
        assertEquals("Neu", service.aktiveWarns(ziel).getFirst().grund());
    }

    @Test
    void ohneAblaufZaehlenAlleWarns() {
        mitWarns(31, 0L);
        assertEquals(2, service.anzahl(ziel));
        assertEquals(2, service.aktiveWarns(ziel).size());
    }
}
