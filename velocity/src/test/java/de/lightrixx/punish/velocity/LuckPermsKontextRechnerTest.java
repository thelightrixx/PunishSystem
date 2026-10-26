package de.lightrixx.punish.velocity;

import de.lightrixx.punish.model.FreezeRecord;
import de.lightrixx.punish.model.ServerScope;
import de.lightrixx.punish.storage.BanRepository;
import de.lightrixx.punish.storage.FreezeRepository;
import de.lightrixx.punish.storage.MuteRepository;
import de.lightrixx.punish.storage.TimeoutRepository;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LuckPermsKontextRechnerTest {

    public static final class FakeSubjekt {
        private final UUID uuid;

        FakeSubjekt(UUID uuid) {
            this.uuid = uuid;
        }

        public UUID getUniqueId() {
            return uuid;
        }
    }

    public static final class FakeVerbraucher {
        final Map<String, String> kontexte = new HashMap<>();

        public void accept(String schluessel, String wert) {
            kontexte.put(schluessel, wert);
        }
    }

    private static Map<String, String> berechne(LuckPermsKontextRechner rechner, UUID uuid)
            throws Exception {
        Method methode = LuckPermsKontextRechner.class.getDeclaredMethod("berechne",
                Object.class, Object.class);
        methode.setAccessible(true);
        FakeVerbraucher verbraucher = new FakeVerbraucher();
        methode.invoke(rechner, new FakeSubjekt(uuid), verbraucher);
        return verbraucher.kontexte;
    }

    @Test
    void freezeSetztAlleKontexte() throws Exception {
        Logger logger = mock(Logger.class);
        UUID uuid = UUID.randomUUID();
        long jetzt = System.currentTimeMillis();
        FreezeRepository freezeRepository = mock(FreezeRepository.class);
        when(freezeRepository.alleFuer(uuid)).thenReturn(List.of(
                new FreezeRecord(uuid, "Frosty", "Verdacht", "Admin", jetzt - 1000L,
                        jetzt + 3600000L, ServerScope.global())));

        LuckPermsKontextRechner rechner = new LuckPermsKontextRechner(logger,
                mock(BanRepository.class), mock(MuteRepository.class),
                mock(TimeoutRepository.class));
        rechner.setFreezeRepository(freezeRepository);

        Map<String, String> kontexte = berechne(rechner, uuid);

        assertEquals("true", kontexte.get(LuckPermsKontextRechner.KEY_FROZEN));
        assertEquals("false", kontexte.get(LuckPermsKontextRechner.KEY_MUTED));
        assertEquals("false", kontexte.get(LuckPermsKontextRechner.KEY_BANNED));
        assertEquals("false", kontexte.get(LuckPermsKontextRechner.KEY_TIMEOUT));
        assertEquals("true", kontexte.get(LuckPermsKontextRechner.KEY_PUNISHED));
    }

    @Test
    void ohneStrafenSindAlleKontexteFalse() throws Exception {
        Logger logger = mock(Logger.class);
        UUID uuid = UUID.randomUUID();

        LuckPermsKontextRechner rechner = new LuckPermsKontextRechner(logger,
                mock(BanRepository.class), mock(MuteRepository.class),
                mock(TimeoutRepository.class));

        Map<String, String> kontexte = berechne(rechner, uuid);

        assertEquals("false", kontexte.get(LuckPermsKontextRechner.KEY_FROZEN));
        assertEquals("false", kontexte.get(LuckPermsKontextRechner.KEY_MUTED));
        assertEquals("false", kontexte.get(LuckPermsKontextRechner.KEY_BANNED));
        assertEquals("false", kontexte.get(LuckPermsKontextRechner.KEY_TIMEOUT));
        assertEquals("false", kontexte.get(LuckPermsKontextRechner.KEY_PUNISHED));
    }

    @Test
    void kontextCacheDeckeltRepositoryAbfragenProSpieler() throws Exception {
        Logger logger = mock(Logger.class);
        UUID uuid = UUID.randomUUID();
        BanRepository banRepository = mock(BanRepository.class);
        MuteRepository muteRepository = mock(MuteRepository.class);
        TimeoutRepository timeoutRepository = mock(TimeoutRepository.class);
        FreezeRepository freezeRepository = mock(FreezeRepository.class);
        when(banRepository.alleFuer(uuid)).thenReturn(List.of());
        when(muteRepository.alleFuer(uuid)).thenReturn(List.of());
        when(timeoutRepository.alleFuer(uuid)).thenReturn(List.of());
        when(freezeRepository.alleFuer(uuid)).thenReturn(List.of());

        LuckPermsKontextRechner rechner = new LuckPermsKontextRechner(logger,
                banRepository, muteRepository, timeoutRepository);
        rechner.setFreezeRepository(freezeRepository);

        Map<String, String> erste = berechne(rechner, uuid);
        Map<String, String> zweite = berechne(rechner, uuid);

        assertEquals("false", zweite.get(LuckPermsKontextRechner.KEY_MUTED));
        assertEquals(erste, zweite, "Kurz hintereinander müssen die Kontexte identisch sein.");
        org.mockito.Mockito.verify(muteRepository, org.mockito.Mockito.times(1)).alleFuer(uuid);
        org.mockito.Mockito.verify(banRepository, org.mockito.Mockito.times(1)).alleFuer(uuid);
        org.mockito.Mockito.verify(timeoutRepository, org.mockito.Mockito.times(1)).alleFuer(uuid);
        org.mockito.Mockito.verify(freezeRepository, org.mockito.Mockito.times(1)).alleFuer(uuid);
    }

    @Test
    void lesefehlerLaesstKontextUngesetzt() throws Exception {
        UUID uuid = UUID.randomUUID();
        MuteRepository muteRepository = mock(MuteRepository.class);
        when(muteRepository.alleFuer(uuid)).thenThrow(new IllegalStateException("Repository weg"));

        LuckPermsKontextRechner rechner = new LuckPermsKontextRechner(mock(Logger.class),
                mock(BanRepository.class), muteRepository, mock(TimeoutRepository.class));

        Map<String, String> kontexte = berechne(rechner, uuid);

        assertFalse(kontexte.containsKey(LuckPermsKontextRechner.KEY_MUTED),
                "Bei Lesefehler darf kein falsches 'false' gemeldet werden");
        assertFalse(kontexte.containsKey(LuckPermsKontextRechner.KEY_PUNISHED),
                "Ohne belastbaren Mute-Stand ist auch punish-punished unbekannt");
        assertEquals("false", kontexte.get(LuckPermsKontextRechner.KEY_BANNED));
    }

    @Test
    void ohneLuckPermsIstRegisterEinNoOp() {
        LuckPermsKontextRechner rechner = new LuckPermsKontextRechner(mock(Logger.class),
                mock(BanRepository.class), mock(MuteRepository.class),
                mock(TimeoutRepository.class));
        rechner.register();
        assertFalse(rechner.isRegistered());
    }
}
