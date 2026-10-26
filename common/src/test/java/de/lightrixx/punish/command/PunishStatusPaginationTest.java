package de.lightrixx.punish.command;

import de.lightrixx.punish.core.BefehlsSender;
import de.lightrixx.punish.core.Konfiguration;
import de.lightrixx.punish.core.MessageKeys;
import de.lightrixx.punish.core.SpielerVerzeichnis;
import de.lightrixx.punish.model.BanRecord;
import de.lightrixx.punish.model.CachedPlayer;
import de.lightrixx.punish.model.ServerScope;
import de.lightrixx.punish.service.MessageService;
import de.lightrixx.punish.service.ProtectionService;
import de.lightrixx.punish.service.VerstossService;
import de.lightrixx.punish.service.WarnService;
import de.lightrixx.punish.storage.BanRepository;
import de.lightrixx.punish.storage.FreezeRepository;
import de.lightrixx.punish.storage.IpBanRepository;
import de.lightrixx.punish.storage.IpMuteRepository;
import de.lightrixx.punish.storage.IpTimeoutRepository;
import de.lightrixx.punish.storage.MuteRepository;
import de.lightrixx.punish.storage.PlayerCache;
import de.lightrixx.punish.storage.TimeoutRepository;
import de.lightrixx.punish.storage.VerstossRepository;
import de.lightrixx.punish.storage.WarnRepository;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PunishStatusPaginationTest {

    private static final UUID ZIEL = UUID.randomUUID();
    private static final long JETZT = System.currentTimeMillis();

    private MessageService messages;
    private Konfiguration config;
    private ProtectionService schutz;
    private PlayerCache cache;
    private VerstossService verstossService;
    private WarnService warnService;
    private BanRepository banRepository;
    private List<List<Component>> bloecke;
    private PunishStatusCommand befehl;

    @BeforeEach
    void aufbauen() {
        messages = mock(MessageService.class);
        bloecke = new ArrayList<>();
        when(messages.komponente(anyString(), any(Map.class)))
                .thenAnswer(inv -> Component.text(String.valueOf((Object) inv.getArgument(0))));
        when(messages.komponenteOhnePraefix(anyString(), any(Map.class)))
                .thenAnswer(inv -> Component.text(String.valueOf((Object) inv.getArgument(0))));
        org.mockito.Mockito.doAnswer(inv -> {
            bloecke.add(new ArrayList<>(inv.getArgument(1, List.class)));
            return null;
        }).when(messages).sendeBlock(any(BefehlsSender.class), any(List.class));

        config = mock(Konfiguration.class);
        when(config.historyEintraegeProSeite()).thenReturn(10);
        when(config.verstoesseMaxPunkte()).thenReturn(10);
        when(config.bekannteServer()).thenReturn(List.of("lobby"));

        schutz = mock(ProtectionService.class);
        when(schutz.istGeschuetzt(any(UUID.class))).thenReturn(false);
        when(schutz.istGeschuetzt(any(de.lightrixx.punish.core.OnlineSpieler.class))).thenReturn(false);
        when(schutz.istFailClosed()).thenReturn(false);

        cache = mock(PlayerCache.class);
        CachedPlayer spieler = new CachedPlayer(ZIEL, "Ziel", "10.0.0.1", JETZT);
        when(cache.findenNachName("Ziel")).thenReturn(Optional.of(spieler));
        when(cache.finden(ZIEL)).thenReturn(Optional.of(spieler));
        when(cache.istFrischeNamensZuordnung(anyString())).thenReturn(true);

        banRepository = mock(BanRepository.class);
        List<BanRecord> banns = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            banns.add(new BanRecord(UUID.randomUUID(), "Ziel", "Grund " + i, "Mod",
                    JETZT - 1000L, JETZT + 3_600_000L, false));
        }
        when(banRepository.alleFuer(ZIEL)).thenReturn(banns);

        MuteRepository muteRepository = mock(MuteRepository.class);
        when(muteRepository.alleFuer(ZIEL)).thenReturn(List.of());
        TimeoutRepository timeoutRepository = mock(TimeoutRepository.class);
        when(timeoutRepository.alleFuer(ZIEL)).thenReturn(List.of());
        FreezeRepository freezeRepository = mock(FreezeRepository.class);
        when(freezeRepository.alleFuer(ZIEL)).thenReturn(List.of());
        IpBanRepository ipBanRepository = mock(IpBanRepository.class);
        when(ipBanRepository.alleFuer(anyString())).thenReturn(List.of());
        IpMuteRepository ipMuteRepository = mock(IpMuteRepository.class);
        when(ipMuteRepository.alleFuer(anyString())).thenReturn(List.of());
        IpTimeoutRepository ipTimeoutRepository = mock(IpTimeoutRepository.class);
        when(ipTimeoutRepository.alleFuer(anyString())).thenReturn(List.of());

        verstossService = mock(VerstossService.class);
        when(verstossService.findeVerstoesse(ZIEL)).thenReturn(List.of());
        when(verstossService.punkte(ZIEL)).thenReturn(0);
        warnService = mock(WarnService.class);
        when(warnService.findeWarns(ZIEL)).thenReturn(List.of());

        befehl = new PunishStatusCommand(messages, config, schutz, cache, verstossService, warnService,
                banRepository, muteRepository, timeoutRepository, freezeRepository,
                ipBanRepository, ipMuteRepository, ipTimeoutRepository,
                mock(VerstossRepository.class), mock(WarnRepository.class), mock(SpielerVerzeichnis.class));
    }

    private static BefehlsSender sender() {
        BefehlsSender sender = mock(BefehlsSender.class);
        when(sender.hatRecht(anyString())).thenReturn(true);
        when(sender.istSpieler()).thenReturn(false);
        when(sender.name()).thenReturn("Konsole");
        return sender;
    }

    private static long eintraege(List<Component> block) {
        return block.stream().filter(component -> component.clickEvent() != null
                && component.clickEvent().value().contains("/pardon")).count();
    }

    @Test
    void seiteZweiZeigtDieRestlichenEintraege() {
        befehl.ausfuehren(sender(), new String[]{"Ziel"});
        befehl.ausfuehren(sender(), new String[]{"Ziel", "page:2"});

        assertEquals(2, bloecke.size());
        assertEquals(10, eintraege(bloecke.get(0)), "Seite 1 muss zehn Einträge zeigen");
        assertEquals(5, eintraege(bloecke.get(1)), "Seite 2 muss die restlichen fünf Einträge zeigen");
        assertTrue(bloecke.get(0).stream().anyMatch(component -> component.clickEvent() != null),
                "Seite 1 braucht eine Navigation zur zweiten Seite");
        assertTrue(bloecke.get(1).stream().anyMatch(component -> component.clickEvent() != null),
                "Seite 2 braucht eine Navigation zurück");
    }

    @Test
    void seiteUeberDemMaximumWirdAufDieLetzteSeiteBegrenzt() {
        befehl.ausfuehren(sender(), new String[]{"Ziel", "page:99"});

        assertEquals(1, bloecke.size());
        assertEquals(5, eintraege(bloecke.get(0)));
    }

    @Test
    void kicksFilterIstImStatusUnbekannt() {
        befehl.ausfuehren(sender(), new String[]{"Ziel", "filter:kicks"});

        verify(messages).senden(any(BefehlsSender.class), eq(MessageKeys.PUNISH_STATUS_FILTER_UNGUELTIG),
                any(Map.class));
        verify(messages).senden(any(BefehlsSender.class), eq(MessageKeys.PUNISH_STATUS_SYNTAX));
        assertTrue(bloecke.isEmpty(), "filter:kicks darf keine Statusansicht senden");
        verify(banRepository, never()).alleFuer(any(UUID.class));
    }

    @Test
    void tabVervollstaendigungBietetKeineKicksAn() {
        List<String> vorschlaege = befehl.vervollstaendigen(sender(), new String[]{"Ziel", "filter:"});

        assertNotNull(vorschlaege);
        assertFalse(vorschlaege.isEmpty(), "Es müssen weiterhin Filter vorgeschlagen werden");
        assertTrue(vorschlaege.stream().noneMatch(v -> v.toLowerCase().contains("kicks")),
                "filter:kicks ist im Status-Befehl nicht verfügbar: " + vorschlaege);
    }
}
