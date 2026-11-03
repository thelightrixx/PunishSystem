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

    @Test
    void guiNutztProxyRechteAuchOhneLokaleRechte() {
        when(kanal.naechsteGuiId()).thenReturn(7L);
        when(spieler.hasPermission(anyString())).thenReturn(false);
        when(kanal.frageGuiAn(any(), anyLong())).thenReturn(new Protokoll.GuiAntwort(
                7L, true, "", 1, 1, 2, List.of(
                new Protokoll.GuiZeile("RECHT", "punish.gui", "", "", ""),
                new Protokoll.GuiZeile("RECHT", "punish.mute", "", "", ""))));
        var rechte = dienst.ladeRechte(spieler.getUniqueId());
        GuiDatenDienst.setzeRechte(spieler.getUniqueId(), rechte);
        try {
            assertTrue(GuiDatenDienst.hatRecht(spieler, "punish.gui"));
            assertTrue(GuiDatenDienst.hatRecht(spieler, "punish.mute"));
            assertFalse(GuiDatenDienst.hatRecht(spieler, "punish.ban"));
            Mockito.verify(kanal).frageGuiAn(argThat(a -> a.typ().equals(Protokoll.GUI_RECHTE)
                    && a.absenderUuid().equals(spieler.getUniqueId())), anyLong());
        } finally {
            GuiDatenDienst.verwerfeRechte(spieler.getUniqueId());
        }
        assertFalse(GuiDatenDienst.hatRecht(spieler, "punish.gui"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void guiOeffnetErstNachProxyAntwortAufDemServerThread(boolean erlaubt) {
        var plugin = Mockito.mock(org.bukkit.plugin.Plugin.class);
        var scheduler = Mockito.mock(org.bukkit.scheduler.BukkitScheduler.class);
        var asynchron = new java.util.ArrayList<Runnable>();
        var synchron = new java.util.ArrayList<Runnable>();
        when(scheduler.runTaskAsynchronously(eq(plugin), any(Runnable.class))).thenAnswer(inv -> {
            asynchron.add(inv.getArgument(1));
            return Mockito.mock(org.bukkit.scheduler.BukkitTask.class);
        });
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(inv -> {
            synchron.add(inv.getArgument(1));
            return Mockito.mock(org.bukkit.scheduler.BukkitTask.class);
        });
        when(kanal.naechsteGuiId()).thenReturn(7L);
        when(spieler.isOnline()).thenReturn(true);
        when(spieler.hasPermission(anyString())).thenReturn(!erlaubt);
        when(kanal.frageGuiAn(any(), anyLong())).thenReturn(new Protokoll.GuiAntwort(
                7L, erlaubt, "", 1, 1, 1, erlaubt
                ? List.of(new Protokoll.GuiZeile("RECHT", "punish.gui", "", "", "")) : List.of()));
        var geoeffnet = new java.util.concurrent.atomic.AtomicBoolean();
        try (var bukkit = Mockito.mockStatic(org.bukkit.Bukkit.class)) {
            bukkit.when(org.bukkit.Bukkit::getScheduler).thenReturn(scheduler);
            var manager = new GuiManager(plugin, kanal, config, dienst);
            manager.pruefeRechteUndOeffne(spieler, () -> geoeffnet.set(true));
            manager.pruefeRechteUndOeffne(spieler, () -> fail("Doppelte Anfrage"));
            assertEquals(1, asynchron.size(), "Nur eine Rechteabfrage pro Spieler gleichzeitig");
            assertFalse(geoeffnet.get());
            asynchron.getFirst().run();
            assertFalse(geoeffnet.get());
            synchron.getFirst().run();
            assertEquals(erlaubt, geoeffnet.get());
            assertEquals(erlaubt, manager.darfOeffnen(spieler));
            manager.schliessen(spieler);
            assertFalse(manager.darfOeffnen(spieler));
        }
    }

    @Test
    void fehlenderProxyWirdNichtDurchLokaleRechteErsetzt() {
        when(kanal.naechsteGuiId()).thenReturn(7L);
        when(spieler.hasPermission(anyString())).thenReturn(true);
        when(kanal.frageGuiAn(any(), anyLong())).thenReturn(null);
        assertThrows(IllegalStateException.class, () -> dienst.ladeRechte(spieler.getUniqueId()));
        assertFalse(GuiDatenDienst.hatRecht(spieler, "punish.gui"));
    }
}
