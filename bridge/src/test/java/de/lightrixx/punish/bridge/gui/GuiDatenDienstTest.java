package de.lightrixx.punish.bridge.gui;

import de.lightrixx.punish.bridge.BridgeKonfiguration;
import de.lightrixx.punish.bridge.KanalClient;
import de.lightrixx.punish.net.Protokoll;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

class GuiDatenDienstTest {

    private KanalClient kanal;
    private BridgeKonfiguration config;
    private Player spieler;
    private GuiDatenDienst dienst;

    @BeforeEach
    void aufsetzen() {
        kanal = Mockito.mock(KanalClient.class);
        config = Mockito.mock(BridgeKonfiguration.class);
        spieler = Mockito.mock(Player.class);
        when(spieler.getUniqueId()).thenReturn(UUID.randomUUID());
        when(config.grundListe(anyString())).thenReturn(List.of());
        when(config.dauerListe(anyString())).thenReturn(List.of());
        when(config.bekannteServer()).thenReturn(List.of("lobby"));
        when(config.vorlagenFuer(anyString())).thenReturn(java.util.Map.of());
        when(config.guiFallbackGruende()).thenReturn(List.of("FallbackGrund"));
        when(config.guiFallbackDauern()).thenReturn(List.of("1h"));
        dienst = new GuiDatenDienst(kanal, config);
    }

    private static Protokoll.GuiAntwort seite(List<String> server, int nummer, int seiten) {
        List<Protokoll.GuiZeile> zeilen = new ArrayList<>();
        for (String name : server) {
            zeilen.add(new Protokoll.GuiZeile("SERVER", name, "", "", ""));
        }
        return new Protokoll.GuiAntwort(7L, true, "Katalog", nummer, seiten, server.size(), zeilen);
    }

    @Test
    void katalog_sammeltAlleSeiten() {
        when(kanal.naechsteGuiId()).thenReturn(7L);
        when(kanal.frageGuiAn(any(), anyLong())).thenAnswer(anruf -> {
            Protokoll.GuiAnfrage anfrage = anruf.getArgument(0);
            if (anfrage.seite() == 1) {
                return seite(List.of("s1", "s2"), 1, 2);
            }
            return seite(List.of("s3"), 2, 2);
        });
        GuiDatenDienst.Katalog katalog = dienst.katalog(spieler, "ban");
        assertEquals(List.of("s1", "s2", "s3"), katalog.server());
    }

    @Test
    void katalog_ohneAntwort_nutztFallback() {
        when(kanal.frageGuiAn(any(), anyLong())).thenReturn(null);
        GuiDatenDienst.Katalog katalog = dienst.katalog(spieler, "ban");
        assertEquals(List.of("lobby"), katalog.server());
        assertEquals(List.of("FallbackGrund"), katalog.gruende());
    }
}
