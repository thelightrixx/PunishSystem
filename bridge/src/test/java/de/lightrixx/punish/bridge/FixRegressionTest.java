package de.lightrixx.punish.bridge;

import de.lightrixx.punish.bridge.chatfilter.ChatfilterKonfiguration;
import de.lightrixx.punish.bridge.chatfilter.ChatfilterService;
import de.lightrixx.punish.bridge.gui.GuiManager;
import de.lightrixx.punish.bridge.gui.GuiMenues;
import de.lightrixx.punish.chatfilter.FilterKanal;
import de.lightrixx.punish.chatfilter.WortlistenFilter;
import de.lightrixx.punish.net.Protokoll;
import de.lightrixx.punish.storage.PlayerCache;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FixRegressionTest {

    private BridgeEnforcementListener listenerMit(MuteCache muteCache, FreezeCache freezeCache,
            ChatfilterService chatfilter) {
        BridgeKonfiguration config = Mockito.mock(BridgeKonfiguration.class);
        when(config.serverName()).thenReturn("lobby");
        when(config.nachricht(anyString())).thenReturn("<red>Blockiert");
        when(config.praefix()).thenReturn("<red>[Punish]");
        when(config.muteBlockierteBefehle()).thenReturn(List.of("/msg", "/tell"));
        KanalClient kanal = Mockito.mock(KanalClient.class);
        PlayerCache playerCache = Mockito.mock(PlayerCache.class);
        IpMuteCache ipMute = new IpMuteCache();
        BanCache ban = new BanCache();
        TimeoutCache timeout = new TimeoutCache();
        FreezeCache freeze = freezeCache == null ? new FreezeCache() : freezeCache;
        var listener = new BridgeEnforcementListener(muteCache, ipMute, ban, timeout, freeze,
                config, kanal, MiniMessage.miniMessage(), playerCache);
        listener.setChatfilter(chatfilter);
        return listener;
    }

    private ChatfilterService serviceMit(WortlistenFilter liste, double kiWert, Set<String> ausnahmen) {
        ChatfilterKonfiguration config = Mockito.mock(ChatfilterKonfiguration.class);
        when(config.aktiv()).thenReturn(true);
        when(config.modusFuer(any())).thenReturn(ChatfilterKonfiguration.Modus.KI);
        when(config.schwelle()).thenReturn(0.7d);
        when(config.kiTimeoutMillis()).thenReturn(150L);
        when(config.maxZeichen()).thenReturn(500);
        when(config.bypassPermission()).thenReturn("punish.chatfilter.bypass");
        when(config.befehlAusnahmen()).thenReturn(ausnahmen == null ? Set.of() : ausnahmen);
        when(config.klartextUbertragen()).thenReturn(true);
        de.lightrixx.punish.chatfilter.ToxizitaetsModell modell = text -> kiWert;
        Player mockSpieler = Mockito.mock(Player.class);
        return new ChatfilterService(config, liste, modell, (a, b, c, d) -> {
        });
    }

    @Test
    void c005_sperrfensterNur10sUndNurBeiFehlendemSync() {
        assertEquals(10_000L, BridgeEnforcementListener.SYNC_SPERRFENSTER_MILLIS);
        assertTrue(BridgeEnforcementListener.sperrfensterAktiv(0L, false, false, false));
        assertTrue(BridgeEnforcementListener.sperrfensterAktiv(9_999L, true, true, false));
        assertFalse(BridgeEnforcementListener.sperrfensterAktiv(0L, true, true, true));
        assertFalse(BridgeEnforcementListener.sperrfensterAktiv(10_000L, false, false, false));
        assertFalse(BridgeEnforcementListener.sperrfensterAktiv(60_000L, false, false, false));
    }

    @Test
    void c022_leerenSetztJeSynchronisiertZurueck() {
        MuteCache mute = new MuteCache();
        BanCache ban = new BanCache();
        TimeoutCache timeout = new TimeoutCache();
        FreezeCache freeze = new FreezeCache();
        IpMuteCache ipMute = new IpMuteCache();
        long jetzt = System.currentTimeMillis();
        UUID uuid = UUID.randomUUID();
        mute.ersetzeAlle(List.of(new Protokoll.MuteEintrag(uuid, "S", "G", "M", jetzt, jetzt + 60000, "global")));
        ban.ersetzeAlle(List.of(new Protokoll.BanEintrag(uuid, "S", "G", "M", jetzt, jetzt + 60000, "global")));
        timeout.ersetzeAlle(List.of(new Protokoll.TimeoutEintrag(uuid, "S", "G", "M", jetzt, jetzt + 60000, "global")));
        freeze.ersetzeAlle(List.of(new Protokoll.FreezeEintrag(uuid, "S", "G", "M", jetzt, jetzt + 60000, "global")));
        assertTrue(mute.istJeSynchronisiert());
        assertTrue(ban.istJeSynchronisiert());
        assertTrue(timeout.istJeSynchronisiert());
        assertTrue(freeze.istJeSynchronisiert());
        mute.leeren();
        ban.leeren();
        timeout.leeren();
        freeze.leeren();
        ipMute.leeren();
        assertFalse(mute.istJeSynchronisiert());
        assertFalse(ban.istJeSynchronisiert());
        assertFalse(timeout.istJeSynchronisiert());
        assertFalse(freeze.istJeSynchronisiert());
        assertFalse(ipMute.istJeSynchronisiert());
    }

    @Test
    void c012_ambossCooldownNurEinmalProSekunde() {
        assertTrue(BridgeEnforcementListener.ambossCooldownFaellig(0L, 1000L));
        assertTrue(BridgeEnforcementListener.ambossCooldownFaellig(0L, 1L));
        assertFalse(BridgeEnforcementListener.ambossCooldownFaellig(1000L, 1500L));
        assertTrue(BridgeEnforcementListener.ambossCooldownFaellig(1000L, 2000L));
        assertTrue(BridgeEnforcementListener.ambossCooldownFaellig(1000L, 2001L));
    }

    @Test
    void d002_archCheckErkenntOhneNatives() {
        assertTrue(PunishBridge.archOhneOnnxNatives("arm"));
        assertTrue(PunishBridge.archOhneOnnxNatives("armv7l"));
        assertTrue(PunishBridge.archOhneOnnxNatives("riscv64"));
        assertFalse(PunishBridge.archOhneOnnxNatives("amd64"));
        assertFalse(PunishBridge.archOhneOnnxNatives("x86_64"));
        assertTrue(PunishBridge.archOhneOnnxNatives("aarch64"));
        assertTrue(PunishBridge.archOhneOnnxNatives("arm64"));
        assertFalse(PunishBridge.archOhneOnnxNatives(null));
        assertFalse(PunishBridge.archOhneOnnxNatives(""));
    }

    @Test
    @SuppressWarnings("unchecked")
    void d003_quitRaeumtAmbossUndFreezeCooldown() throws Exception {
        BridgeEnforcementListener listener = listenerMit(new MuteCache(), null, null);
        UUID uuid = UUID.randomUUID();
        java.lang.reflect.Field fAmboss = listener.getClass().getDeclaredField("ambossHinweisCooldown");
        fAmboss.setAccessible(true);
        java.lang.reflect.Field fFreeze = listener.getClass().getDeclaredField("freezeHinweisCooldown");
        fFreeze.setAccessible(true);
        var amboss = (java.util.concurrent.ConcurrentHashMap<UUID, Long>) fAmboss.get(listener);
        var freeze = (java.util.concurrent.ConcurrentHashMap<UUID, Long>) fFreeze.get(listener);
        amboss.put(uuid, System.currentTimeMillis());
        freeze.put(uuid, System.currentTimeMillis());
        Player spieler = Mockito.mock(Player.class);
        Mockito.when(spieler.getUniqueId()).thenReturn(uuid);
        PlayerQuitEvent event = Mockito.mock(PlayerQuitEvent.class);
        Mockito.when(event.getPlayer()).thenReturn(spieler);
        listener.beiQuit(event);
        assertFalse(amboss.containsKey(uuid));
        assertFalse(freeze.containsKey(uuid));
    }

    @Test
    void c003_muteErstSyncFehltVorUndNachSync() {
        MuteCache mute = new MuteCache();
        var listener = listenerMit(mute, null, null);
        assertTrue(listener.muteErstSyncFehlt());
        long jetzt = System.currentTimeMillis();
        mute.ersetzeAlle(List.of(new Protokoll.MuteEintrag(UUID.randomUUID(), "S", "G", "M",
                jetzt, jetzt + 60000, "global")));
        assertFalse(listener.muteErstSyncFehlt());
        mute.leeren();
        assertTrue(listener.muteErstSyncFehlt());
    }

    @Test
    void c004_filterAusnahmeNurExplizitSonstAlle() {
        var ohne = serviceMit(new WortlistenFilter(List.of("hurensohn")), 0.0d, Set.of());
        assertFalse(ohne.istFilterAusnahme("/msg"));
        assertFalse(ohne.istFilterAusnahme("/irgendeinbefehl"));
        var mit = serviceMit(new WortlistenFilter(List.of("hurensohn")), 0.0d, Set.of("/msg"));
        assertTrue(mit.istFilterAusnahme("/msg"));
        assertTrue(mit.istFilterAusnahme("msg"));
        assertFalse(mit.istFilterAusnahme("/tell"));
        ohne.close();
        mit.close();
    }

    @Test
    void c013_pruefeBlockierendNutztKiSynchron() {
        var dienst = serviceMit(new WortlistenFilter(List.of("hurensohn")), 0.95d, Set.of());
        Player spieler = Mockito.mock(Player.class);
        when(spieler.hasPermission(anyString())).thenReturn(false);
        var trefferWort = dienst.pruefeBlockierend(spieler, FilterKanal.SCHILD, "Du Hurensohn");
        assertTrue(trefferWort.isPresent());
        var trefferKi = dienst.pruefeBlockierend(spieler, FilterKanal.BUCH, "Du bist ein Idiot");
        assertTrue(trefferKi.isPresent());
        assertEquals(de.lightrixx.punish.chatfilter.TrefferQuelle.KI, trefferKi.get().quelle());
        dienst.close();
        var harmlos = serviceMit(new WortlistenFilter(List.of("hurensohn")), 0.1d, Set.of());
        Player spieler2 = Mockito.mock(Player.class);
        when(spieler2.hasPermission(anyString())).thenReturn(false);
        assertTrue(harmlos.pruefeBlockierend(spieler2, FilterKanal.AMBOSS, "Hallo Welt").isEmpty());
        harmlos.close();
    }

    @Test
    void b007_tunnelOhneRechtWirdLokalAbgelehnt() {
        KanalClient kanal = Mockito.mock(KanalClient.class);
        var weiter = new BridgeBefehlWeiterleitung(kanal, "mute", "punish.mute", true);
        CommandSender sender = Mockito.mock(CommandSender.class);
        when(sender.hasPermission(anyString())).thenReturn(false);
        when(sender.getName()).thenReturn("NoPerm");
        weiter.fuehreAus(sender, new String[]{"Steve", "10m", "Spam"});
        verify(kanal, never()).sendeBefehl(any(), anyString());
        verify(sender, times(1)).sendMessage(any(net.kyori.adventure.text.Component.class));
    }

    @Test
    void b007_reportOhneRechtWirdLokalAbgelehnt() {
        KanalClient kanal = Mockito.mock(KanalClient.class);
        var weiter = new ReportWeiterleitung(kanal, "report", "punish.report");
        CommandSender sender = Mockito.mock(CommandSender.class);
        when(sender.hasPermission(anyString())).thenReturn(false);
        when(sender.getName()).thenReturn("NoPerm");
        weiter.fuehreAus(sender, new String[]{"Steve", "Spam"});
        verify(kanal, never()).sendeBefehl(any(), anyString());
    }

    @Test
    void b007_tunnelMitRechtWirdGesendet() {
        KanalClient kanal = Mockito.mock(KanalClient.class);
        var weiter = new BridgeBefehlWeiterleitung(kanal, "mute", "punish.mute", true);
        CommandSender sender = Mockito.mock(CommandSender.class);
        when(sender.hasPermission("punish.mute")).thenReturn(true);
        when(sender.hasPermission(anyString())).thenAnswer(a -> "punish.mute".equals(a.getArgument(0)));
        weiter.fuehreAus(sender, new String[]{"Steve"});
        verify(kanal, times(1)).sendeBefehl(isNull(), contains("mute Steve"));
    }

    private GuiManager guiManagerMit(KanalClient kanal) {
        Plugin plugin = Mockito.mock(Plugin.class);
        BridgeKonfiguration config = Mockito.mock(BridgeKonfiguration.class);
        when(config.guiTitel()).thenReturn("Punish");
        de.lightrixx.punish.bridge.gui.GuiDatenDienst daten =
                Mockito.mock(de.lightrixx.punish.bridge.gui.GuiDatenDienst.class);
        return new GuiManager(plugin, kanal, config, daten);
    }

    @Test
    void c002_guiKlickOhneRechtWirdAbgebrochen() {
        KanalClient kanal = Mockito.mock(KanalClient.class);
        GuiManager manager = guiManagerMit(kanal);
        Player spieler = Mockito.mock(Player.class);
        UUID uuid = UUID.randomUUID();
        when(spieler.getUniqueId()).thenReturn(uuid);
        when(spieler.hasPermission(anyString())).thenReturn(false);
        boolean gehandhabt = manager.klick(spieler, new GuiMenues.GuiHolder(GuiMenues.Menue.HAUPT), 10);
        assertTrue(gehandhabt);
        verify(spieler, times(1)).closeInventory();
        verify(spieler, atLeastOnce()).sendMessage(any(net.kyori.adventure.text.Component.class));
        verify(kanal, never()).sendeBefehl(any(), anyString());
    }

    @Test
    void c011_listeZurueckBeiSeite1WirdIgnoriert() {
        KanalClient kanal = Mockito.mock(KanalClient.class);
        GuiManager manager = guiManagerMit(kanal);
        Player spieler = Mockito.mock(Player.class);
        UUID uuid = UUID.randomUUID();
        when(spieler.getUniqueId()).thenReturn(uuid);
        when(spieler.hasPermission("punish.gui")).thenReturn(true);
        when(spieler.hasPermission(anyString())).thenAnswer(a -> "punish.gui".equals(a.getArgument(0)));
        var sitzung = manager.sitzung(spieler);
        sitzung.seite(1);
        boolean gehandhabt = manager.klick(spieler, new GuiMenues.GuiHolder(GuiMenues.Menue.STATUS), 48);
        assertTrue(gehandhabt);
        assertEquals(1, sitzung.seite());
        verify(kanal, never()).sendeBefehl(any(), anyString());
    }

    @Test
    void c020_fahrzeugMitGefrorenemBlockiertDritte() {
        FreezeCache freeze = new FreezeCache();
        long jetzt = System.currentTimeMillis();
        UUID gefrorenUuid = UUID.randomUUID();
        freeze.ersetzeAlle(List.of(new Protokoll.FreezeEintrag(gefrorenUuid, "Frost", "Check", "Mod",
                jetzt, jetzt + 600000, "global")));
        var listener = listenerMit(new MuteCache(), freeze, null);
        Player gefroren = Mockito.mock(Player.class);
        when(gefroren.getUniqueId()).thenReturn(gefrorenUuid);
        Player einsteiger = Mockito.mock(Player.class);
        UUID einsteigerUuid = UUID.randomUUID();
        when(einsteiger.getUniqueId()).thenReturn(einsteigerUuid);
        org.bukkit.entity.Vehicle fahrzeug = Mockito.mock(org.bukkit.entity.Vehicle.class);
        when(fahrzeug.getPassengers()).thenReturn(List.of(gefroren));
        var event = Mockito.mock(org.bukkit.event.vehicle.VehicleEnterEvent.class);
        when(event.getVehicle()).thenReturn(fahrzeug);
        when(event.getEntered()).thenReturn(einsteiger);
        listener.beiFreezeAufsitzen(event);
        verify(event, times(1)).setCancelled(true);
    }
}
