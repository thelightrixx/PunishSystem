package de.lightrixx.punish.bridge;

import de.lightrixx.punish.bridge.chatfilter.ChatfilterService;
import de.lightrixx.punish.chatfilter.FilterKanal;
import de.lightrixx.punish.chatfilter.FilterTreffer;
import de.lightrixx.punish.chatfilter.TrefferQuelle;
import de.lightrixx.punish.model.CachedPlayer;
import de.lightrixx.punish.net.Protokoll;
import de.lightrixx.punish.storage.PlayerCache;
import de.lightrixx.punish.util.MiniMessageUtil;
import de.lightrixx.punish.util.ZeitFormat;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class BridgeEnforcementListener implements Listener {

    private static final Logger LOG = LoggerFactory.getLogger(BridgeEnforcementListener.class);

    private volatile Set<String> blockierteBefehleCache = null;
    private volatile long blockierteBefehleCacheZeit = 0L;
    private static final long BLOCKLISTE_TTL_MILLIS = 30_000L;
    static final long SYNC_SPERRFENSTER_MILLIS = 10_000L;
    private final long startMillis = System.currentTimeMillis();

    private final MuteCache muteCache;
    private final IpMuteCache ipMuteCache;
    private final BanCache banCache;
    private final TimeoutCache timeoutCache;
    private final FreezeCache freezeCache;
    private final java.util.concurrent.ConcurrentHashMap<UUID, Long> freezeHinweisCooldown =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final long FREEZE_HINWEIS_COOLDOWN_MILLIS = 3000L;
    private final java.util.concurrent.ConcurrentHashMap<UUID, Long> ambossHinweisCooldown =
            new java.util.concurrent.ConcurrentHashMap<>();
    static final long AMBOSS_HINWEIS_COOLDOWN_MILLIS = 1000L;
    private final BridgeKonfiguration config;
    private final KanalClient kanal;
    private final MiniMessage miniMessage;
    private final PlayerCache playerCache;
    private volatile ChatfilterService chatfilterService;

    public BridgeEnforcementListener(MuteCache muteCache, BridgeKonfiguration config,
                                     KanalClient kanal, MiniMessage miniMessage,
                                     PlayerCache playerCache) {
        this(muteCache, null, null, null, null, config, kanal, miniMessage, playerCache);
    }

    public BridgeEnforcementListener(MuteCache muteCache, IpMuteCache ipMuteCache, BridgeKonfiguration config,
                                     KanalClient kanal, MiniMessage miniMessage,
                                     PlayerCache playerCache) {
        this(muteCache, ipMuteCache, null, null, null, config, kanal, miniMessage, playerCache);
    }

    public BridgeEnforcementListener(MuteCache muteCache, IpMuteCache ipMuteCache, BanCache banCache,
                                     TimeoutCache timeoutCache, BridgeKonfiguration config,
                                     KanalClient kanal, MiniMessage miniMessage,
                                     PlayerCache playerCache) {
        this(muteCache, ipMuteCache, banCache, timeoutCache, null, config, kanal, miniMessage, playerCache);
    }

    public BridgeEnforcementListener(MuteCache muteCache, IpMuteCache ipMuteCache, BanCache banCache,
                                     TimeoutCache timeoutCache, FreezeCache freezeCache, BridgeKonfiguration config,
                                     KanalClient kanal, MiniMessage miniMessage,
                                     PlayerCache playerCache) {
        this.muteCache = Objects.requireNonNull(muteCache, "Der Mute-Cache darf nicht null sein.");
        this.ipMuteCache = ipMuteCache;
        this.banCache = banCache;
        this.timeoutCache = timeoutCache;
        this.freezeCache = freezeCache;
        this.config = Objects.requireNonNull(config, "Die Konfiguration darf nicht null sein.");
        this.kanal = Objects.requireNonNull(kanal, "Der Kanal darf nicht null sein.");
        this.miniMessage = Objects.requireNonNull(miniMessage, "MiniMessage darf nicht null sein.");
        this.playerCache = Objects.requireNonNull(playerCache, "Der PlayerCache darf nicht null sein.");
    }

    private String aktuellerServer() {
        try {
            return config.serverName();
        } catch (Exception e) {
            return "unbekannt";
        }
    }

    private Optional<Protokoll.MuteEintrag> findeAktiv(UUID uuid) {
        return muteCache.findeAktiv(uuid, aktuellerServer(), System.currentTimeMillis());
    }

    private Optional<Protokoll.FreezeEintrag> findeAktivenFreeze(Player spieler) {
        if (freezeCache == null || spieler == null) {
            return Optional.empty();
        }
        try {
            return freezeCache.findeAktiv(spieler.getUniqueId(), aktuellerServer(), System.currentTimeMillis());
        } catch (Exception e) {
            LOG.debug("Freeze-Check fehlgeschlagen (Fail-Open).", e);
            return Optional.empty();
        }
    }

    private Optional<Protokoll.IpMuteEintrag> findeAktivenIpMute(Player spieler) {
        if (ipMuteCache == null || spieler == null) {
            return Optional.empty();
        }
        String ip = ipVon(spieler);
        if (ip == null || ip.isBlank() || "unbekannt".equalsIgnoreCase(ip)) {
            return Optional.empty();
        }
        return ipMuteCache.findeAktiv(ip, aktuellerServer(), System.currentTimeMillis());
    }

    private static String ipVon(Player spieler) {
        try {
            java.net.InetSocketAddress adresse = spieler.getAddress();
            if (adresse != null && adresse.getAddress() != null) {
                String roh = adresse.getAddress().getHostAddress();
                if (roh != null && !roh.isBlank()) {
                    return roh;
                }
            }
        } catch (Exception e) {
            LOG.trace("Spieler-IP konnte nicht gelesen werden.", e);
        }
        return "unbekannt";
    }

    boolean muteErstSyncFehlt() {
        try {
            return muteCache == null || !muteCache.istJeSynchronisiert();
        } catch (Exception e) {
            return false;
        }
    }

    static boolean ambossCooldownFaellig(long letzter, long jetzt) {
        return letzter <= 0L || jetzt - letzter >= AMBOSS_HINWEIS_COOLDOWN_MILLIS;
    }

    private boolean darfAmbossHinweis(UUID uuid) {
        long jetzt = System.currentTimeMillis();
        try {
            Long letzter = ambossHinweisCooldown.get(uuid);
            long wert = letzter == null ? 0L : letzter;
            if (!ambossCooldownFaellig(wert, jetzt)) {
                return false;
            }
            ambossHinweisCooldown.put(uuid, jetzt);
            return true;
        } catch (Exception e) {
            return true;
        }
    }

    private void sendeSyncLaeuft(Player spieler) {
        String vorlage;
        try {
            vorlage = config.nachricht(de.lightrixx.punish.core.MessageKeys.MUTE_SYNC_LAEUFT);
        } catch (Exception e) {
            vorlage = "<yellow>Strafen werden geladen, bitte kurz warten.";
        }
        if (vorlage == null || vorlage.isBlank()) {
            vorlage = "<yellow>Strafen werden geladen, bitte kurz warten.";
        }
        try {
            spieler.sendMessage(mitPraefix(vorlage));
        } catch (Exception e) {
            try {
                spieler.sendMessage(Component.text("Strafen werden geladen, bitte kurz warten."));
            } catch (Exception fallbackFehler) {
                LOG.debug("Sync-Hinweis konnte nicht gesendet werden.", fallbackFehler);
            }
        }
    }

    boolean istFilterAusnahme(String befehl) {
        try {
            ChatfilterService chatfilter = chatfilterService;
            if (chatfilter == null) {
                return false;
            }
            return chatfilter.istFilterAusnahme(befehl);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Fallback-Deny für Direktjoins ohne Proxy (zweite Schicht hinter Proxy-Enforcement +
     * Firewall). Greift nur bei synchronisiertem Cache; vor Erst-Sync wird im
     * Startfenster (10s) fail-closed mit Hinweis abgewiesen, danach fail-open.
     * Der Proxy bleibt Source of Truth.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void beiPreLogin(org.bukkit.event.player.AsyncPlayerPreLoginEvent event) {
        java.util.UUID uuid;
        try {
            uuid = event.getUniqueId();
        } catch (Exception e) {
            LOG.debug("Bridge-PreLogin ohne UUID, überspringe Fallback-Deny.", e);
            return;
        }
        if (uuid == null) {
            return;
        }
        long jetzt = System.currentTimeMillis();
        String server = aktuellerServer();
        if (istSyncSperrfensterAktiv()) {
            try {
                event.disallow(org.bukkit.event.player.AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                        syncKick());
            } catch (Exception e) {
                LOG.debug("Bridge-PreLogin-Disallow (Sync-Fenster) fehlgeschlagen.", e);
            }
            return;
        }
        try {
            if (banCache != null && banCache.istJeSynchronisiert()) {
                var bann = banCache.findeAktiv(uuid, server, jetzt);
                if (bann.isPresent()) {
                    LOG.warn("Bridge-Fallback-Deny: gebannter Spieler {} abgewiesen (Direktjoin?).", uuid);
                    try {
                        event.disallow(org.bukkit.event.player.AsyncPlayerPreLoginEvent.Result.KICK_BANNED,
                                fallbackKick());
                    } catch (Exception e) {
                        LOG.debug("Bridge-PreLogin-Disallow (Bann) fehlgeschlagen.", e);
                    }
                    return;
                }
            }
        } catch (Exception e) {
            LOG.warn("Bridge-Bann-Fallback-Check fehlgeschlagen – Login wird zur Sicherheit abgewiesen.", e);
            try {
                event.disallow(org.bukkit.event.player.AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                        fallbackKick());
            } catch (Exception disallowFehler) {
                LOG.debug("Bridge-PreLogin-Disallow (Bann-Fehler) fehlgeschlagen.", disallowFehler);
            }
        }
        try {
            if (timeoutCache != null && timeoutCache.istJeSynchronisiert()) {
                var timeout = timeoutCache.findeAktiv(uuid, server, jetzt);
                if (timeout.isPresent()) {
                    LOG.warn("Bridge-Fallback-Deny: Timeout-Spieler {} abgewiesen (Direktjoin?).", uuid);
                    try {
                        event.disallow(org.bukkit.event.player.AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                                fallbackKick());
                    } catch (Exception e) {
                        LOG.debug("Bridge-PreLogin-Disallow (Timeout) fehlgeschlagen.", e);
                    }
                }
            }
        } catch (Exception e) {
            LOG.warn("Bridge-Timeout-Fallback-Check fehlgeschlagen – Login wird zur Sicherheit abgewiesen.", e);
            try {
                event.disallow(org.bukkit.event.player.AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                        fallbackKick());
            } catch (Exception disallowFehler) {
                LOG.debug("Bridge-PreLogin-Disallow (Timeout-Fehler) fehlgeschlagen.", disallowFehler);
            }
        }
    }

    private Component fallbackKick() {
        try {
            return miniMessage.deserialize("<red>Login derzeit nicht möglich. Bitte erneut versuchen.");
        } catch (Exception e) {
            return Component.text("Login derzeit nicht möglich. Bitte erneut versuchen.");
        }
    }

    static boolean sperrfensterAktiv(long alterMillis, boolean banSync, boolean timeoutSync, boolean muteSync) {
        if (alterMillis >= SYNC_SPERRFENSTER_MILLIS) {
            return false;
        }
        return !(banSync && timeoutSync && muteSync);
    }

    boolean istSyncSperrfensterAktiv() {
        try {
            long alter = jetztMinusStart();
            boolean banSync = banCache != null && banCache.istJeSynchronisiert();
            boolean timeoutSync = timeoutCache != null && timeoutCache.istJeSynchronisiert();
            boolean muteSync = muteCache != null && muteCache.istJeSynchronisiert();
            return sperrfensterAktiv(alter, banSync, timeoutSync, muteSync);
        } catch (Exception e) {
            return false;
        }
    }

    private long jetztMinusStart() {
        try {
            return Math.max(0L, System.currentTimeMillis() - startMillis);
        } catch (Exception e) {
            return SYNC_SPERRFENSTER_MILLIS;
        }
    }

    private Component syncKick() {
        try {
            return miniMessage.deserialize(
                    "<yellow>Server startet noch (Sync läuft).<newline><gray>Bitte in wenigen Sekunden erneut joinen.");
        } catch (Exception e) {
            return Component.text("Server startet noch (Sync läuft). Bitte in wenigen Sekunden erneut joinen.");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void beiJoin(PlayerJoinEvent event) {
        try {
            Player spieler = event.getPlayer();
            String ip = "unbekannt";
            try {
                java.net.InetSocketAddress adresse = spieler.getAddress();
                if (adresse != null && adresse.getAddress() != null) {
                    String roh = adresse.getAddress().getHostAddress();
                    if (roh != null && !roh.isBlank()) {
                        ip = roh;
                    }
                }
            } catch (Exception e) {
                LOG.trace("Join-IP konnte nicht gelesen werden.", e);
            }
            playerCache.aktualisieren(new CachedPlayer(spieler.getUniqueId(), spieler.getName(),
                    ip, System.currentTimeMillis()));
        } catch (Exception e) {
            LOG.debug("Bridge-Cache-Update fehlgeschlagen.", e);
        }
        try {
            kanal.sendeHello(aktuellerServer());
        } catch (Exception e) {
            LOG.debug("HELLO-Heilung fehlgeschlagen.", e);
        }
        try {
            Player spieler = event.getPlayer();
            Optional<Protokoll.MuteEintrag> mute = findeAktiv(spieler.getUniqueId());
            if (mute.isPresent()) {
                sendeBlockiert(spieler, mute.get());
                return;
            }
            var ipMute = findeAktivenIpMute(spieler);
            ipMute.ifPresent(eintrag -> sendeIpBlockiert(spieler, eintrag));
            findeAktivenFreeze(spieler).ifPresent(eintrag -> sendeFreezeBlockiert(spieler, eintrag));
        } catch (Exception e) {
            LOG.debug("Mute-Join-Hinweis fehlgeschlagen.", e);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void beiQuit(PlayerQuitEvent event) {
        try {
            Player spieler = event.getPlayer();
            freezeHinweisCooldown.remove(spieler.getUniqueId());
            ambossHinweisCooldown.remove(spieler.getUniqueId());
            long jetzt = System.currentTimeMillis();
            playerCache.finden(spieler.getUniqueId()).ifPresent(alt -> {
                try {
                    playerCache.aktualisieren(alt.mitZuletztGesehen(jetzt));
                } catch (Exception e) {
                    LOG.debug("Quit-Cache-Update fehlgeschlagen.", e);
                }
            });
        } catch (Exception e) {
            LOG.debug("Bridge-Cache-Quit fehlgeschlagen.", e);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void beiChat(AsyncChatEvent event) {
        Player spieler = event.getPlayer();
        Optional<Protokoll.FreezeEintrag> freeze = findeAktivenFreeze(spieler);
        if (freeze.isPresent()) {
            event.setCancelled(true);
            sendeFreezeBlockiert(spieler, freeze.get());
            return;
        }
        if (muteErstSyncFehlt()) {
            event.setCancelled(true);
            sendeSyncLaeuft(spieler);
            return;
        }
        Optional<Protokoll.MuteEintrag> mute = findeAktiv(spieler.getUniqueId());
        if (mute.isPresent()) {
            event.setCancelled(true);
            sendeBlockiert(spieler, mute.get());
            return;
        }
        var ipMute = findeAktivenIpMute(spieler);
        if (ipMute.isPresent()) {
            event.setCancelled(true);
            sendeIpBlockiert(spieler, ipMute.get());
            return;
        }
        ChatfilterService chatfilter = chatfilterService;
        if (chatfilter == null) {
            return;
        }
        String text;
        try {
            text = PlainTextComponentSerializer.plainText().serialize(event.message());
        } catch (Exception e) {
            LOG.debug("Chat-Text konnte nicht gelesen werden.", e);
            return;
        }
        Optional<FilterTreffer> treffer;
        try {
            treffer = chatfilter.pruefeChat(spieler, text);
        } catch (Exception e) {
            LOG.debug("Chatfilter-Prüfung fehlgeschlagen (Fail-Open).", e);
            return;
        }
        if (treffer.isPresent()) {
            event.setCancelled(true);
            sendeChatfilterBlockiert(spieler, treffer.get());
            chatfilter.melden(spieler, text, treffer.get());
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void beiBefehl(PlayerCommandPreprocessEvent event) {
        Player spieler = event.getPlayer();
        Optional<Protokoll.FreezeEintrag> freeze = findeAktivenFreeze(spieler);
        if (freeze.isPresent()) {
            event.setCancelled(true);
            sendeFreezeBlockiert(spieler, freeze.get());
            return;
        }
        if (muteErstSyncFehlt()) {
            event.setCancelled(true);
            sendeSyncLaeuft(spieler);
            return;
        }
        String befehl = extrahiereBefehl(event.getMessage().toLowerCase(Locale.ROOT));
        boolean muteRelevant = istMuteBlockiert(befehl);
        if (muteRelevant) {
            Optional<Protokoll.MuteEintrag> mute = findeAktiv(spieler.getUniqueId());
            if (mute.isPresent()) {
                event.setCancelled(true);
                sendeBlockiert(spieler, mute.get());
                return;
            }
            var ipMute = findeAktivenIpMute(spieler);
            if (ipMute.isPresent()) {
                event.setCancelled(true);
                sendeIpBlockiert(spieler, ipMute.get());
                return;
            }
        }
        if (istFilterAusnahme(befehl)) {
            return;
        }
        ChatfilterService chatfilter = chatfilterService;
        if (chatfilter == null || chatfilter.umgeht(spieler)) {
            return;
        }
        String inhalt = befehlsInhalt(event.getMessage());
        if (inhalt.isBlank()) {
            return;
        }
        Optional<FilterTreffer> treffer;
        try {
            treffer = chatfilter.pruefeStufe1(spieler, FilterKanal.PRIVATNACHRICHT, inhalt);
        } catch (Exception e) {
            LOG.debug("Chatfilter-Prüfung (Befehl) fehlgeschlagen (Fail-Open).", e);
            return;
        }
        if (treffer.isPresent()) {
            event.setCancelled(true);
            sendeChatfilterBlockiert(spieler, treffer.get());
            chatfilter.melden(spieler, inhalt, treffer.get());
            return;
        }
        try {
            chatfilter.pruefeKiNachgelagert(spieler.getUniqueId(), spieler.getName(),
                    FilterKanal.PRIVATNACHRICHT, inhalt);
        } catch (Exception e) {
            LOG.debug("Nachgelagerte KI-Prüfung (Befehl) fehlgeschlagen.", e);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void beiBuch(PlayerEditBookEvent event) {
        Player spieler = event.getPlayer();
        if (muteErstSyncFehlt()) {
            event.setCancelled(true);
            sendeSyncLaeuft(spieler);
            return;
        }
        var freeze = findeAktivenFreeze(spieler);
        if (freeze.isPresent()) {
            event.setCancelled(true);
            sendeFreezeBlockiert(spieler, freeze.get());
            return;
        }
        Optional<Protokoll.MuteEintrag> mute = findeAktiv(spieler.getUniqueId());
        if (mute.isPresent()) {
            event.setCancelled(true);
            sendeBlockiert(spieler, mute.get());
            return;
        }
        var ipMute = findeAktivenIpMute(spieler);
        if (ipMute.isPresent()) {
            event.setCancelled(true);
            sendeIpBlockiert(spieler, ipMute.get());
            return;
        }
        pruefeInhaltNachMute(spieler, FilterKanal.BUCH, buchText(event), b -> event.setCancelled(Boolean.TRUE.equals(b)));
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void beiSchild(SignChangeEvent event) {
        Player spieler = event.getPlayer();
        if (muteErstSyncFehlt()) {
            event.setCancelled(true);
            sendeSyncLaeuft(spieler);
            return;
        }
        var freeze = findeAktivenFreeze(spieler);
        if (freeze.isPresent()) {
            event.setCancelled(true);
            sendeFreezeBlockiert(spieler, freeze.get());
            return;
        }
        Optional<Protokoll.MuteEintrag> mute = findeAktiv(spieler.getUniqueId());
        if (mute.isPresent()) {
            event.setCancelled(true);
            sendeBlockiert(spieler, mute.get());
            return;
        }
        var ipMute = findeAktivenIpMute(spieler);
        if (ipMute.isPresent()) {
            event.setCancelled(true);
            sendeIpBlockiert(spieler, ipMute.get());
            return;
        }
        pruefeInhaltNachMute(spieler, FilterKanal.SCHILD, schildText(event), b -> event.setCancelled(Boolean.TRUE.equals(b)));
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    @SuppressWarnings({"deprecation", "removal"})
    public void beiAmboss(PrepareAnvilEvent event) {
        if (!(event.getView().getPlayer() instanceof Player spieler)) {
            return;
        }
        if (muteErstSyncFehlt()) {
            event.setResult(null);
            if (darfAmbossHinweis(spieler.getUniqueId())) {
                sendeSyncLaeuft(spieler);
            }
            return;
        }
        var freeze = findeAktivenFreeze(spieler);
        if (freeze.isPresent()) {
            event.setResult(null);
            sendeFreezeBlockiert(spieler, freeze.get());
            return;
        }
        if (event.getResult() == null) {
            return;
        }
        Optional<Protokoll.MuteEintrag> mute = findeAktiv(spieler.getUniqueId());
        if (mute.isPresent()) {
            event.setResult(null);
            if (darfAmbossHinweis(spieler.getUniqueId())) {
                sendeBlockiert(spieler, mute.get());
            }
            return;
        }
        var ipMute = findeAktivenIpMute(spieler);
        if (ipMute.isPresent()) {
            event.setResult(null);
            if (darfAmbossHinweis(spieler.getUniqueId())) {
                sendeIpBlockiert(spieler, ipMute.get());
            }
            return;
        }
        String name;
        try {
            name = event.getInventory().getRenameText();
        } catch (Exception e) {
            LOG.debug("Amboss-Text konnte nicht gelesen werden.", e);
            return;
        }
        if (name == null || name.isBlank()) {
            return;
        }
        ChatfilterService chatfilter = chatfilterService;
        if (chatfilter == null || chatfilter.umgeht(spieler)) {
            return;
        }
        Optional<FilterTreffer> treffer;
        try {
            treffer = chatfilter.pruefeBlockierend(spieler, FilterKanal.AMBOSS, name);
        } catch (de.lightrixx.punish.chatfilter.ChatfilterAusfall e) {
            LOG.debug("Synchroner KI-Check (AMBOSS) nicht möglich (Timeout/Server-Thread), Fail-Open + nachgelagerte Staffel-Meldung.", e);
            try {
                chatfilter.pruefeKiNachgelagert(spieler.getUniqueId(), spieler.getName(),
                        FilterKanal.AMBOSS, name);
            } catch (Exception ex) {
                LOG.debug("Nachgelagerte KI-Prüfung (Amboss) fehlgeschlagen.", ex);
            }
            return;
        } catch (Exception e) {
            LOG.debug("Chatfilter-Prüfung (Amboss) fehlgeschlagen (Fail-Open).", e);
            return;
        }
        if (treffer.isPresent()) {
            event.setResult(null);
            if (darfAmbossHinweis(spieler.getUniqueId())) {
                sendeChatfilterBlockiert(spieler, treffer.get());
            }
            chatfilter.melden(spieler, name, treffer.get());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void beiFreezeBewegung(PlayerMoveEvent event) {
        var freeze = findeAktivenFreeze(event.getPlayer());
        if (freeze.isEmpty()) {
            return;
        }
        var von = event.getFrom();
        var nach = event.getTo();
        if (nach == null) {
            return;
        }
        if (von.getX() != nach.getX() || von.getY() != nach.getY() || von.getZ() != nach.getZ()
                || !von.getWorld().equals(nach.getWorld())) {
            event.setCancelled(true);
            sendeFreezeBlockiert(event.getPlayer(), freeze.get());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void beiFreezeFahrzeug(VehicleMoveEvent event) {
        boolean eingefroren = false;
        try {
            for (org.bukkit.entity.Entity passagier : event.getVehicle().getPassengers()) {
                if (passagier instanceof Player spieler) {
                    var freeze = findeAktivenFreeze(spieler);
                    if (freeze.isPresent()) {
                        eingefroren = true;
                        sendeFreezeBlockiert(spieler, freeze.get());
                    }
                }
            }
        } catch (Exception e) {
            LOG.debug("Freeze-Fahrzeugcheck fehlgeschlagen.", e);
        }
        if (eingefroren) {
            try {
                event.getVehicle().teleport(event.getFrom());
            } catch (Exception e) {
                LOG.debug("Freeze-Fahrzeug konnte nicht zurückgesetzt werden.", e);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void beiFreezeAufsitzen(VehicleEnterEvent event) {
        try {
            if (event.getEntered() instanceof Player spieler) {
                var freeze = findeAktivenFreeze(spieler);
                if (freeze.isPresent()) {
                    event.setCancelled(true);
                    sendeFreezeBlockiert(spieler, freeze.get());
                    return;
                }
            }
            Optional<Protokoll.FreezeEintrag> gefrorenerInsasse = findeGefrorenenInsassen(event.getVehicle());
            if (gefrorenerInsasse.isPresent()) {
                event.setCancelled(true);
                if (event.getEntered() instanceof Player einsteiger) {
                    sendeFreezeBlockiert(einsteiger, gefrorenerInsasse.get());
                }
            }
        } catch (Exception e) {
            LOG.debug("Freeze-Aufsitzcheck fehlgeschlagen.", e);
        }
    }

    private Optional<Protokoll.FreezeEintrag> findeGefrorenenInsassen(org.bukkit.entity.Vehicle fahrzeug) {
        if (fahrzeug == null || freezeCache == null) {
            return Optional.empty();
        }
        try {
            for (org.bukkit.entity.Entity passagier : fahrzeug.getPassengers()) {
                if (passagier instanceof Player spieler) {
                    var freeze = findeAktivenFreeze(spieler);
                    if (freeze.isPresent()) {
                        return freeze;
                    }
                }
            }
        } catch (Exception e) {
            LOG.debug("Freeze-Insassen-Check fehlgeschlagen.", e);
        }
        return Optional.empty();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void beiFreezeTeleport(PlayerTeleportEvent event) {
        var freeze = findeAktivenFreeze(event.getPlayer());
        if (freeze.isEmpty()) {
            return;
        }
        switch (event.getCause()) {
            case PLUGIN, UNKNOWN -> {
            }
            default -> {
                event.setCancelled(true);
                sendeFreezeBlockiert(event.getPlayer(), freeze.get());
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void beiFreezeAbbau(BlockBreakEvent event) {
        var freeze = findeAktivenFreeze(event.getPlayer());
        if (freeze.isPresent()) {
            event.setCancelled(true);
            sendeFreezeBlockiert(event.getPlayer(), freeze.get());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void beiFreezePlatzieren(BlockPlaceEvent event) {
        var freeze = findeAktivenFreeze(event.getPlayer());
        if (freeze.isPresent()) {
            event.setCancelled(true);
            sendeFreezeBlockiert(event.getPlayer(), freeze.get());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void beiFreezeInteraktion(PlayerInteractEvent event) {
        if (event.getPlayer() == null) {
            return;
        }
        var freeze = findeAktivenFreeze(event.getPlayer());
        if (freeze.isPresent()) {
            event.setCancelled(true);
            sendeFreezeBlockiert(event.getPlayer(), freeze.get());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void beiFreezeEntitaetInteraktion(PlayerInteractEntityEvent event) {
        var freeze = findeAktivenFreeze(event.getPlayer());
        if (freeze.isPresent()) {
            event.setCancelled(true);
            sendeFreezeBlockiert(event.getPlayer(), freeze.get());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void beiFreezeAngriff(EntityDamageByEntityEvent event) {
        Player angreifer = null;
        if (event.getDamager() instanceof Player direkt) {
            angreifer = direkt;
        } else if (event.getDamager() instanceof Projectile projektil
                && projektil.getShooter() instanceof Player schuetze) {
            angreifer = schuetze;
        }
        if (angreifer == null) {
            return;
        }
        var freeze = findeAktivenFreeze(angreifer);
        if (freeze.isPresent()) {
            event.setCancelled(true);
            sendeFreezeBlockiert(angreifer, freeze.get());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void beiFreezeBogen(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player spieler)) {
            return;
        }
        var freeze = findeAktivenFreeze(spieler);
        if (freeze.isPresent()) {
            event.setCancelled(true);
            sendeFreezeBlockiert(spieler, freeze.get());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void beiFreezeVerbrauchen(PlayerItemConsumeEvent event) {
        var freeze = findeAktivenFreeze(event.getPlayer());
        if (freeze.isPresent()) {
            event.setCancelled(true);
            sendeFreezeBlockiert(event.getPlayer(), freeze.get());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void beiFreezeDrop(PlayerDropItemEvent event) {
        var freeze = findeAktivenFreeze(event.getPlayer());
        if (freeze.isPresent()) {
            event.setCancelled(true);
            sendeFreezeBlockiert(event.getPlayer(), freeze.get());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void beiFreezePickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player spieler)) {
            return;
        }
        var freeze = findeAktivenFreeze(spieler);
        if (freeze.isPresent()) {
            event.setCancelled(true);
            sendeFreezeBlockiert(spieler, freeze.get());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void beiFreezeInventarKlick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player spieler)) {
            return;
        }
        var freeze = findeAktivenFreeze(spieler);
        if (freeze.isPresent()) {
            event.setCancelled(true);
            sendeFreezeBlockiert(spieler, freeze.get());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void beiFreezeInventarZiehen(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player spieler)) {
            return;
        }
        var freeze = findeAktivenFreeze(spieler);
        if (freeze.isPresent()) {
            event.setCancelled(true);
            sendeFreezeBlockiert(spieler, freeze.get());
        }
    }

    private void sendeFreezeBlockiert(Player spieler, Protokoll.FreezeEintrag freeze) {
        long jetzt = System.currentTimeMillis();
        try {
            Long letzter = freezeHinweisCooldown.get(spieler.getUniqueId());
            if (letzter != null && jetzt - letzter < FREEZE_HINWEIS_COOLDOWN_MILLIS) {
                return;
            }
            freezeHinweisCooldown.put(spieler.getUniqueId(), jetzt);
        } catch (Exception e) {
            LOG.trace("Freeze-Cooldown konnte nicht geprüft werden.", e);
        }
        long rest = freeze.istPermanent() ? Long.MAX_VALUE : Math.max(0L, freeze.ablaufAm() - jetzt);
        Map<String, String> platzhalter = Map.of(
                "verbleibend", freeze.istPermanent() ? "Permanent" : ZeitFormat.formatRestzeit(rest),
                "grund", freeze.grund());
        String vorlage = config.nachricht(de.lightrixx.punish.core.MessageKeys.FREEZE_BLOCKIERT);
        String ersetzt = MiniMessageUtil.ersetzen(vorlage, platzhalter);
        try {
            spieler.sendMessage(mitPraefix(ersetzt));
        } catch (Exception e) {
            try {
                spieler.sendMessage(Component.text(ersetzt));
            } catch (Exception fallbackFehler) {
                LOG.debug("Fallback-Nachricht konnte nicht gesendet werden.", fallbackFehler);
            }
        }
    }

    private void pruefeInhaltNachMute(Player spieler, FilterKanal kanal, String text,                                              java.util.function.Consumer<Boolean> abbrechen) {
        if (text == null || text.isBlank()) {
            return;
        }
        ChatfilterService chatfilter = chatfilterService;
        if (chatfilter == null || chatfilter.umgeht(spieler)) {
            return;
        }
        Optional<FilterTreffer> treffer;
        try {
            treffer = chatfilter.pruefeBlockierend(spieler, kanal, text);
        } catch (de.lightrixx.punish.chatfilter.ChatfilterAusfall e) {
            LOG.debug("Synchroner KI-Check ({} ) nicht möglich (Timeout/Server-Thread), Fail-Open + nachgelagerte Staffel-Meldung.", kanal, e);
            try {
                chatfilter.pruefeKiNachgelagert(spieler.getUniqueId(), spieler.getName(), kanal, text);
            } catch (Exception ex) {
                LOG.debug("Nachgelagerte KI-Prüfung fehlgeschlagen.", ex);
            }
            return;
        } catch (Exception e) {
            LOG.debug("Chatfilter-Prüfung fehlgeschlagen (Fail-Open).", e);
            return;
        }
        if (treffer.isPresent()) {
            try {
                abbrechen.accept(true);
            } catch (Exception e) {
                LOG.debug("Event-Abbruch fehlgeschlagen.", e);
            }
            sendeChatfilterBlockiert(spieler, treffer.get());
            chatfilter.melden(spieler, text, treffer.get());
            return;
        }
    }

    private static String buchText(PlayerEditBookEvent event) {
        try {
            var meta = event.getNewBookMeta();
            if (meta == null || !meta.hasPages()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            for (Component seite : meta.pages()) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                try {
                    sb.append(PlainTextComponentSerializer.plainText().serialize(seite));
                } catch (Exception e) {
                    LOG.trace("Buch-Seite konnte nicht gelesen werden.", e);
                }
            }
            return sb.toString();
        } catch (Exception e) {
            LOG.debug("Buch-Text konnte nicht gelesen werden.", e);
            return "";
        }
    }

    private static String schildText(SignChangeEvent event) {
        try {
            StringBuilder sb = new StringBuilder();
            for (Component zeile : event.lines()) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                try {
                    sb.append(PlainTextComponentSerializer.plainText().serialize(zeile));
                } catch (Exception e) {
                    LOG.trace("Schild-Zeile konnte nicht gelesen werden.", e);
                }
            }
            return sb.toString();
        } catch (Exception e) {
            LOG.debug("Schild-Text konnte nicht gelesen werden.", e);
            return "";
        }
    }

    private static String befehlsInhalt(String nachricht) {
        if (nachricht == null) {
            return "";
        }
        String getrimmt = nachricht.trim();
        int leerzeichen = getrimmt.indexOf(' ');
        if (leerzeichen < 0) {
            return "";
        }
        return getrimmt.substring(leerzeichen + 1).trim();
    }

    private void sendeChatfilterBlockiert(Player spieler, FilterTreffer treffer) {
        String quelle = treffer.quelle() == TrefferQuelle.KI ? "KI" : "Wortliste";
        Map<String, String> platzhalter = Map.of("quelle", quelle);
        String vorlage = config.nachricht(de.lightrixx.punish.core.MessageKeys.CHATFILTER_BLOCKIERT);
        String ersetzt = MiniMessageUtil.ersetzen(vorlage, platzhalter);
        try {
            spieler.sendMessage(mitPraefix(ersetzt));
        } catch (Exception e) {
            try {
                spieler.sendMessage(Component.text(ersetzt));
            } catch (Exception fallbackFehler) {
                LOG.debug("Fallback-Nachricht konnte nicht gesendet werden.", fallbackFehler);
            }
        }
    }

    private Component mitPraefix(String ersetzt) {
        Component praefix;
        try {
            praefix = miniMessage.deserialize(config.praefix());
        } catch (Exception e) {
            praefix = Component.text("[PunishSystem]");
        }
        Component inhalt;
        try {
            inhalt = miniMessage.deserialize(ersetzt);
        } catch (Exception e) {
            inhalt = Component.text(ersetzt);
        }
        return praefix.append(Component.space()).append(inhalt);
    }

    private void sendeBlockiert(Player spieler, Protokoll.MuteEintrag mute) {
        long jetzt = System.currentTimeMillis();
        Map<String, String> platzhalter = Map.of(
                "verbleibend", ZeitFormat.formatRestzeit(mute.verbleibend(jetzt)),
                "grund", mute.grund());
        String vorlage = config.nachricht(de.lightrixx.punish.core.MessageKeys.MUTE_BLOCKIERT);
        String ersetzt = MiniMessageUtil.ersetzen(vorlage, platzhalter);
        try {
            spieler.sendMessage(mitPraefix(ersetzt));
        } catch (Exception e) {
            try {
                spieler.sendMessage(Component.text(ersetzt));
            } catch (Exception fallbackFehler) {
                LOG.debug("Fallback-Nachricht konnte nicht gesendet werden.", fallbackFehler);
            }
        }
    }

    private void sendeIpBlockiert(Player spieler, Protokoll.IpMuteEintrag mute) {
        long jetzt = System.currentTimeMillis();
        Map<String, String> platzhalter = Map.of(
                "verbleibend", ZeitFormat.formatRestzeit(mute.verbleibend(jetzt)),
                "grund", mute.grund());
        String vorlage = config.nachricht(de.lightrixx.punish.core.MessageKeys.MUTE_BLOCKIERT);
        String ersetzt = MiniMessageUtil.ersetzen(vorlage, platzhalter);
        try {
            spieler.sendMessage(mitPraefix(ersetzt));
        } catch (Exception e) {
            try {
                spieler.sendMessage(Component.text(ersetzt));
            } catch (Exception fallbackFehler) {
                LOG.debug("Fallback-Nachricht konnte nicht gesendet werden.", fallbackFehler);
            }
        }
    }

    private String extrahiereBefehl(String nachricht) {
        if (nachricht == null) {
            return "/";
        }
        String getrimmt = nachricht.trim();
        int leerzeichenIndex = getrimmt.indexOf(' ');
        String befehl = (leerzeichenIndex == -1) ? getrimmt : getrimmt.substring(0, leerzeichenIndex);
        befehl = befehl.replaceAll("^/+", "/");
        int namespaceIndex = befehl.lastIndexOf(':');
        if (namespaceIndex >= 0) {
            befehl = "/" + befehl.substring(namespaceIndex + 1);
        }
        int atIndex = befehl.indexOf('@');
        if (atIndex >= 0) {
            befehl = befehl.substring(0, atIndex);
        }
        int doppelpunktIndex = befehl.indexOf(':', 1);
        if (doppelpunktIndex >= 0) {
            befehl = befehl.substring(0, doppelpunktIndex);
        }
        if (!befehl.startsWith("/")) {
            befehl = "/" + befehl;
        }
        return befehl;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    @SuppressWarnings("null")
    public void beiTabComplete(com.destroystokyo.paper.event.server.AsyncTabCompleteEvent event) {
        if (!(event.getSender() instanceof Player spieler)) {
            return;
        }
        try {
            if (findeAktivenFreeze(spieler).isPresent()) {
                event.setCompletions(List.of());
                return;
            }
        } catch (Exception e) {
            LOG.debug("Freeze-Tab-Complete-Filter fehlgeschlagen.", e);
        }
        String befehl;
        try {
            befehl = extrahiereBefehl(event.getBuffer().toLowerCase(Locale.ROOT));
        } catch (Exception e) {
            return;
        }
        if (!istMuteBlockiert(befehl)) {
            return;
        }
        try {
            if (findeAktiv(spieler.getUniqueId()).isPresent()
                    || findeAktivenIpMute(spieler).isPresent()) {
                event.setCompletions(List.of());
            }
        } catch (Exception e) {
            LOG.debug("Tab-Complete-Filter fehlgeschlagen.", e);
        }
    }

    private boolean istMuteBlockiert(String befehl) {
        long jetzt = System.currentTimeMillis();
        Set<String> gecacht = blockierteBefehleCache;
        if (gecacht == null || jetzt - blockierteBefehleCacheZeit > BLOCKLISTE_TTL_MILLIS) {
            Set<String> neu;
            try {
                neu = Set.copyOf(config.muteBlockierteBefehle());
            } catch (Exception e) {
                neu = (gecacht != null) ? gecacht : Set.of(
                        "/msg", "/tell", "/w", "/whisper", "/t",
                        "/r", "/reply",
                        "/me", "/action",
                        "/tellraw", "/say", "/teammsg",
                        "/pm", "/dm", "/message",
                        "/party", "/p",
                        "/guild", "/g",
                        "/plot",
                        "/channel", "/ch",
                        "/mail", "/nick", "/emsg", "/ereply", "/m");
            }
            gecacht = neu;
            blockierteBefehleCache = neu;
            blockierteBefehleCacheZeit = jetzt;
        }
        for (String blockiert : gecacht) {
            if (blockiert == null || blockiert.isBlank()) {
                continue;
            }
            String eintrag = blockiert.trim();
            if (eintrag.endsWith("*")) {
                String praefix = eintrag.substring(0, eintrag.length() - 1).trim();
                if (!praefix.isEmpty() && befehl.toLowerCase(Locale.ROOT).startsWith(praefix.toLowerCase(Locale.ROOT))) {
                    return true;
                }
                continue;
            }
            if (eintrag.equalsIgnoreCase(befehl)) {
                return true;
            }
        }
        return false;
    }

    public void invalidiereBlockliste() {
        blockierteBefehleCacheZeit = 0L;
    }

    public void setChatfilter(ChatfilterService chatfilterService) {
        this.chatfilterService = chatfilterService;
    }
}
