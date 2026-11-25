package de.lightrixx.punish.velocity;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import de.lightrixx.punish.model.CachedPlayer;
import de.lightrixx.punish.net.Protokoll;
import de.lightrixx.punish.storage.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ModerationsGuiTest {
    GuiBrueckenDienst gui;
    ModerationsDienst moderation;
    VelocityKonfiguration config;
    UUID absender = UUID.randomUUID();
    UUID ziel = UUID.randomUUID();
    Set<String> rechte = new HashSet<>(Set.of("punish.gui"));

    @BeforeEach
    void vorbereiten() {
        var proxy = mock(ProxyServer.class);
        var spieler = mock(Player.class);
        when(proxy.getPlayer(absender)).thenReturn(Optional.of(spieler));
        when(spieler.hasPermission(anyString())).thenAnswer(a -> rechte.contains(a.getArgument(0, String.class)));
        var cache = mock(PlayerCache.class);
        when(cache.findenNachName("Spieler")).thenReturn(Optional.of(new CachedPlayer(ziel, "Spieler", "127.0.0.1", 1)));
        config = mock(VelocityKonfiguration.class);
        moderation = mock(ModerationsDienst.class);
        gui = new GuiBrueckenDienst(proxy, LoggerFactory.getLogger(getClass()), config, cache,
                mock(BanRepository.class), mock(MuteRepository.class), mock(TimeoutRepository.class),
                mock(IpBanRepository.class), mock(IpMuteRepository.class), mock(IpTimeoutRepository.class),
                mock(VerstossRepository.class), mock(WarnRepository.class), null, null);
        gui.mitModeration(moderation);
    }

    Protokoll.GuiAntwort anfrage(String typ, String extra) {
        return gui.beantworte(new Protokoll.GuiAnfrage(1, absender, typ, "Spieler", "", 1, extra));
    }

    @Test
    void guiRechtAlleinGibtKeinenZugriffAufFaelleOderPrueftexte() {
        assertFalse(anfrage(Protokoll.GUI_FAELLE, "").erfolg());
        assertFalse(anfrage(Protokoll.GUI_PRUEFUNG, "").erfolg());
        verifyNoInteractions(moderation);
    }

    @Test
    void hashOnlyVerbirgtAuchBereitsVorhandenenKlartextInDerGui() {
        rechte.add("punish.chatfilter.pruefung");
        when(config.chatfilterKlartextUbertragen()).thenReturn(false);
        when(moderation.pruefungen(ziel)).thenReturn(List.of(new ModerationsDienst.Pruefung(1, ziel, "Spieler", 1,
                "CHAT", "KI", .9, "ein-hash", "Alter Klartext", false, ModerationsDienst.PruefStatus.OFFEN, "", List.of())));
        var antwort = anfrage(Protokoll.GUI_PRUEFUNG, "");
        assertTrue(antwort.erfolg());
        assertFalse(antwort.eintraege().getFirst().details().contains("Alter Klartext"));
        assertTrue(antwort.eintraege().getFirst().details().contains("ein-hash"));
    }

    @Test
    void katalogGuiBietetOhneStrafrechtKeinenAusfuehrbarenVorschlag() {
        rechte.add("punish.katalog");
        when(config.moderationsAbschnitt("strafkatalog")).thenReturn(Map.of("regeln", Map.of("spam",
                Map.of("stufen", List.of(Map.of("befehl", "warn"))))));
        var antwort = anfrage(Protokoll.GUI_STRAFKATALOG, "");
        assertTrue(antwort.erfolg());
        assertEquals("", antwort.eintraege().getFirst().aktion());
        rechte.add("punish.warn");
        assertEquals("punish-katalog Spieler spam --global", anfrage(Protokoll.GUI_STRAFKATALOG, "").eintraege().getFirst().aktion());
    }
}
