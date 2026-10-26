package de.lightrixx.punish.bridge;

import de.lightrixx.punish.net.Protokoll;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class KanalClient implements PluginMessageListener {

    private static final Logger LOG = LoggerFactory.getLogger(KanalClient.class);

    private final Plugin plugin;
    private final MuteCache muteCache;
    private final IpMuteCache ipMuteCache;
    private final BanCache banCache;
    private final TimeoutCache timeoutCache;
    private final FreezeCache freezeCache;
    private final java.util.concurrent.ConcurrentHashMap<Long,
            java.util.concurrent.CompletableFuture<Protokoll.GuiAntwort>> guiWarter =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<Long,
            java.util.concurrent.CompletableFuture<Protokoll.PlatzhalterAntwort>> platzhalterWarter =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.security.SecureRandom zufall = new java.security.SecureRandom();
    private volatile int proxyVersion = -1;
    private volatile String proxyPluginVersion = "";
    private volatile String pluginVersion = "";
    private volatile String gemeldeteProxyPluginVersion;
    private volatile java.util.function.Consumer<String> helloAckHandler;
    private volatile Runnable reloadHandler;
    private volatile java.util.function.Consumer<de.lightrixx.punish.net.Protokoll.ChatfilterSteuerung> steuerungsHandler;
    private volatile java.util.function.Consumer<String> wortHandler;
    private volatile de.lightrixx.punish.bridge.chatfilter.ModellEmpfaenger modellEmpfaenger;
    private volatile String modellStandModell = "";
    private volatile String modellStandVocab = "";
    private volatile de.lightrixx.punish.bridge.TextEmpfaenger textEmpfaenger;
    private volatile String textStand = "";
    private volatile String variante = "";
    private volatile de.lightrixx.punish.bridge.JarEmpfaenger updateEmpfaenger;
    private volatile String updateStandVersion = "";
    private volatile Runnable updateStandSender;
    private volatile byte[] proxyZuBridgeSchluessel;
    private volatile byte[] bridgeZuProxySchluessel;
    private volatile long letzteAuthWarnung = 0L;
    private static final long AUTH_WARN_INTERVALL_MILLIS = 60L * 1000L;
    private final java.util.concurrent.atomic.AtomicLong sendeSeq =
            new java.util.concurrent.atomic.AtomicLong();
    private final Protokoll.SequenzPruefer empfangsPruefer = new Protokoll.SequenzPruefer();
    private final TunnelAuthBremse authBremse = new TunnelAuthBremse();

    public KanalClient(Plugin plugin, MuteCache muteCache) {
        this(plugin, muteCache, null, null, null);
    }

    public KanalClient(Plugin plugin, MuteCache muteCache, IpMuteCache ipMuteCache) {
        this(plugin, muteCache, ipMuteCache, null, null);
    }

    public KanalClient(Plugin plugin, MuteCache muteCache, IpMuteCache ipMuteCache,
                       BanCache banCache, TimeoutCache timeoutCache) {
        this(plugin, muteCache, ipMuteCache, banCache, timeoutCache, null);
    }

    public KanalClient(Plugin plugin, MuteCache muteCache, IpMuteCache ipMuteCache,
                       BanCache banCache, TimeoutCache timeoutCache, FreezeCache freezeCache) {
        this.plugin = Objects.requireNonNull(plugin, "Das Plugin darf nicht null sein.");
        this.muteCache = Objects.requireNonNull(muteCache, "Der Mute-Cache darf nicht null sein.");
        this.ipMuteCache = ipMuteCache;
        this.banCache = banCache;
        this.timeoutCache = timeoutCache;
        this.freezeCache = freezeCache;
    }

    public void registrieren() {
        try {
            plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, Protokoll.KANAL);
            plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, Protokoll.KANAL, this);
            LOG.info("Bridge-Kanal '{}' registriert.", Protokoll.KANAL);
        } catch (Exception e) {
            LOG.warn("Bridge-Kanal konnte nicht registriert werden.", e);
        }
    }

    public void abmelden() {
        try {
            alleWarterAbbrechen();
        } catch (Exception e) {
            LOG.debug("Warter konnten beim Abmelden nicht abgebrochen werden.", e);
        }
        try {
            plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, Protokoll.KANAL, this);
            plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, Protokoll.KANAL);
        } catch (Exception e) {
            LOG.debug("Bridge-Kanal konnte beim Abmelden nicht entladen werden.", e);
        }
    }

    public void alleWarterAbbrechen() {
        for (java.util.Map.Entry<Long, java.util.concurrent.CompletableFuture<Protokoll.GuiAntwort>> eintrag : guiWarter.entrySet()) {
            try {
                eintrag.getValue().complete(null);
            } catch (Exception e) {
                LOG.trace("GUI-Warter konnte nicht abgebrochen werden.", e);
            }
        }
        guiWarter.clear();
        for (java.util.Map.Entry<Long, java.util.concurrent.CompletableFuture<Protokoll.PlatzhalterAntwort>> eintrag : platzhalterWarter.entrySet()) {
            try {
                eintrag.getValue().complete(null);
            } catch (Exception e) {
                LOG.trace("Platzhalter-Warter konnte nicht abgebrochen werden.", e);
            }
        }
        platzhalterWarter.clear();
    }

    public int proxyVersion() {
        return proxyVersion;
    }

    public String proxyPluginVersion() {
        return proxyPluginVersion;
    }

    public String pluginVersion() {
        return pluginVersion;
    }

    public void mitPluginVersion(String pluginVersion) {
        this.pluginVersion = pluginVersion == null ? "" : pluginVersion.trim();
    }

    public void setTunnelSecret(String geheimnis) {
        if (de.lightrixx.punish.net.TunnelSignatur.istKonfiguriert(geheimnis)) {
            this.proxyZuBridgeSchluessel = de.lightrixx.punish.net.TunnelSignatur.schluesselAus(
                    geheimnis.trim(),
                    de.lightrixx.punish.net.TunnelSignatur.Richtung.PROXY_ZU_BRIDGE);
            this.bridgeZuProxySchluessel = de.lightrixx.punish.net.TunnelSignatur.schluesselAus(
                    geheimnis.trim(),
                    de.lightrixx.punish.net.TunnelSignatur.Richtung.BRIDGE_ZU_PROXY);
            LOG.info("Tunnel-Signatur aktiv (eigenes Bridge-Secret, richtungsgebunden).");
            if (de.lightrixx.punish.net.TunnelSignatur.istSchwach(geheimnis)) {
                LOG.warn("netzwerk.tunnel-secret ist kürzer als {} Zeichen – schwaches Geheimnis, "
                        + "per Wörterbuch-/Online-Raten angreifbar. Bitte ein langes Zufallsgeheimnis "
                        + "verwenden.", de.lightrixx.punish.net.TunnelSignatur.MINDESTLAENGE);
            }
        } else {
            this.proxyZuBridgeSchluessel = null;
            this.bridgeZuProxySchluessel = null;
            LOG.warn("Tunnel-Signatur inaktiv (netzwerk.tunnel-secret leer): "
                    + "Fail-closed – Tunnel-Nachrichten werden abgewiesen, bis ein eigenes "
                    + "Secret für diese Bridge gesetzt ist.");
        }
    }

    public boolean tunnelSigniert() {
        return proxyZuBridgeSchluessel != null && bridgeZuProxySchluessel != null;
    }

    private void syncStagingVerwerfen() {
        try {
            muteCache.syncVerwerfen();
        } catch (Exception e) {
            LOG.debug("Mute-Sync-Staging konnte nicht verworfen werden.", e);
        }
        if (ipMuteCache != null) {
            try {
                ipMuteCache.syncVerwerfen();
            } catch (Exception e) {
                LOG.debug("IP-Mute-Sync-Staging konnte nicht verworfen werden.", e);
            }
        }
        if (banCache != null) {
            try {
                banCache.syncVerwerfen();
            } catch (Exception e) {
                LOG.debug("Ban-Sync-Staging konnte nicht verworfen werden.", e);
            }
        }
        if (timeoutCache != null) {
            try {
                timeoutCache.syncVerwerfen();
            } catch (Exception e) {
                LOG.debug("Timeout-Sync-Staging konnte nicht verworfen werden.", e);
            }
        }
        if (freezeCache != null) {
            try {
                freezeCache.syncVerwerfen();
            } catch (Exception e) {
                LOG.debug("Freeze-Sync-Staging konnte nicht verworfen werden.", e);
            }
        }
    }

    public void sendeHello(String serverName) {
        sendeSeq.set(0L);
        empfangsPruefer.zuruecksetzen();
        syncStagingVerwerfen();
        try {
            sendeAnProxy(Protokoll.hello(serverName, pluginVersion, variante));
            LOG.info("Bridge-HELLO als '{}' gesendet.", serverName);
        } catch (Exception e) {
            LOG.warn("HELLO konnte nicht gesendet werden (kein Spieler online?).");
            LOG.debug("HELLO-Details.", e);
            return;
        }
        try {
            sendeAnProxy(Protokoll.modellStand(modellStandModell, modellStandVocab));
            LOG.debug("Modell-Stand gesendet.");
        } catch (Exception e) {
            LOG.debug("Modell-Stand konnte nicht gesendet werden.", e);
        }
        try {
            sendeAnProxy(Protokoll.textStand(textStand));
            LOG.debug("Text-Stand gesendet.");
        } catch (Exception e) {
            LOG.debug("Text-Stand konnte nicht gesendet werden.", e);
        }
        try {
            sendeAnProxy(Protokoll.updateStand(
                    new Protokoll.UpdateStand(updateStandVersion, variante)));
            LOG.debug("Update-Stand gesendet.");
        } catch (Exception e) {
            LOG.debug("Update-Stand konnte nicht gesendet werden.", e);
        }
    }

    public void mitVariante(String neueVariante) {
        this.variante = neueVariante == null ? "" : neueVariante.trim().toLowerCase(java.util.Locale.ROOT);
    }

    public void mitUpdateHandler(de.lightrixx.punish.bridge.JarEmpfaenger empfaenger) {
        this.updateEmpfaenger = empfaenger;
    }

    public void setUpdateStand(String version) {
        this.updateStandVersion = version == null ? "" : version.trim();
    }

    public void mitUpdateStandSender(Runnable sender) {
        this.updateStandSender = sender;
    }

    public void sendeUpdateStand() {
        try {
            sendeAnProxy(Protokoll.updateStand(
                    new Protokoll.UpdateStand(updateStandVersion, variante)));
        } catch (Exception e) {
            LOG.debug("Update-Stand konnte nicht gesendet werden.", e);
        }
    }

    public void sendeUpdateQuittung(String updateVariante, String version, boolean erfolg) {
        try {
            sendeAnProxy(Protokoll.updateQuittung(
                    new Protokoll.UpdateQuittung(updateVariante == null ? "" : updateVariante,
                            version == null ? "" : version, erfolg)));
        } catch (Exception e) {
            LOG.debug("Update-Quittung konnte nicht gesendet werden.", e);
        }
    }

    public void setTextStand(String paketHash) {
        this.textStand = paketHash == null ? "" : paketHash;
    }

    public void mitTextHandler(
            de.lightrixx.punish.bridge.TextEmpfaenger empfaenger) {
        this.textEmpfaenger = empfaenger;
    }

    public void sendeTextQuittung(String paketHash, boolean erfolg) {
        try {
            sendeAnProxy(Protokoll.textQuittung(
                    new Protokoll.TextQuittung(paketHash == null ? "" : paketHash, erfolg)));
        } catch (Exception e) {
            LOG.debug("Text-Quittung konnte nicht gesendet werden.", e);
        }
    }

    public void setModellStand(String modellHash, String vocabHash) {
        this.modellStandModell = modellHash == null ? "" : modellHash;
        this.modellStandVocab = vocabHash == null ? "" : vocabHash;
    }

    public void mitModellHandler(
            de.lightrixx.punish.bridge.chatfilter.ModellEmpfaenger empfaenger) {
        this.modellEmpfaenger = empfaenger;
    }

    public void sendeModellQuittung(String dateiname, boolean erfolg) {
        if (dateiname == null || dateiname.isBlank()) {
            return;
        }
        try {
            sendeAnProxy(Protokoll.modellQuittung(
                    new Protokoll.ModellQuittung(dateiname.trim(), erfolg)));
        } catch (Exception e) {
            LOG.debug("Modell-Quittung konnte nicht gesendet werden.", e);
        }
    }

    public void sendeBefehl(UUID alsUuid, String zeile) {
        if (zeile == null || zeile.isBlank()) {
            return;
        }
        String sauber = zeile.trim();
        if (sauber.length() > 500) {
            LOG.warn("Befehl abgewiesen (zu lang, {} Zeichen).", sauber.length());
            return;
        }
        try {
            sendeAnProxy(Protokoll.befehlAusfuehren(alsUuid, sauber));
        } catch (Exception e) {
            LOG.warn("Befehl konnte nicht an den Proxy gesendet werden: /{}",
                    sanierenFuerLog(sauber), e);
            throw new IllegalStateException("Kein Proxy-Träger (Spieler online nötig).", e);
        }
    }

    public void mitReloadHandler(Runnable handler) {
        this.reloadHandler = handler;
    }

    public void mitHelloAckHandler(java.util.function.Consumer<String> handler) {
        this.helloAckHandler = handler;
    }

    public void mitSteuerungsHandler(
            java.util.function.Consumer<de.lightrixx.punish.net.Protokoll.ChatfilterSteuerung> handler) {
        this.steuerungsHandler = handler;
    }

    public void mitWortHandler(java.util.function.Consumer<String> handler) {
        this.wortHandler = handler;
    }

    public void sendeChatfilterTreffer(Protokoll.ChatfilterTreffer treffer) {
        if (treffer == null) {
            return;
        }
        try {
            sendeAnProxy(Protokoll.chatfilterTreffer(treffer));
        } catch (Exception e) {
            LOG.debug("Chatfilter-Treffer konnte nicht an den Proxy gesendet werden.", e);
        }
    }

    public long naechsteGuiId() {
        for (int versuch = 0; versuch < 8; versuch++) {
            long id = zufall.nextLong() & Long.MAX_VALUE;
            if (id <= 0) {
                continue;
            }
            if (!guiWarter.containsKey(id) && !platzhalterWarter.containsKey(id)) {
                return id;
            }
        }
        long fallback = (System.nanoTime() ^ System.currentTimeMillis()) & Long.MAX_VALUE;
        return fallback <= 0 ? 1L : fallback;
    }

    public Protokoll.GuiAntwort frageGuiAn(Protokoll.GuiAnfrage anfrage, long timeoutMillis) {
        Objects.requireNonNull(anfrage, "Die Anfrage darf nicht null sein.");
        java.util.concurrent.CompletableFuture<Protokoll.GuiAntwort> zukunft =
                new java.util.concurrent.CompletableFuture<>();
        guiWarter.put(anfrage.requestId(), zukunft);
        try {
            sendeAnProxy(Protokoll.guiAnfrage(anfrage));
        } catch (Exception e) {
            guiWarter.remove(anfrage.requestId());
            LOG.debug("GUI-Anfrage konnte nicht gesendet werden.", e);
            return null;
        }
        try {
            return zukunft.get(Math.max(500L, timeoutMillis),
                    java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            LOG.debug("GUI-Anfrage {} timed out.", anfrage.requestId());
            return null;
        } catch (Exception e) {
            LOG.debug("GUI-Anfrage {} fehlgeschlagen.", anfrage.requestId(), e);
            return null;
        } finally {
            guiWarter.remove(anfrage.requestId());
        }
    }

    public Protokoll.PlatzhalterAntwort fragePlatzhalterAn(Protokoll.PlatzhalterAnfrage anfrage,
                                                           long timeoutMillis) {
        Objects.requireNonNull(anfrage, "Die Anfrage darf nicht null sein.");
        java.util.concurrent.CompletableFuture<Protokoll.PlatzhalterAntwort> zukunft =
                new java.util.concurrent.CompletableFuture<>();
        platzhalterWarter.put(anfrage.requestId(), zukunft);
        try {
            sendeAnProxy(Protokoll.platzhalterAnfrage(anfrage));
        } catch (Exception e) {
            platzhalterWarter.remove(anfrage.requestId());
            LOG.debug("Platzhalter-Anfrage konnte nicht gesendet werden.", e);
            return null;
        }
        try {
            return zukunft.get(Math.max(500L, timeoutMillis),
                    java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            LOG.debug("Platzhalter-Anfrage {} timed out.", anfrage.requestId());
            return null;
        } catch (Exception e) {
            LOG.debug("Platzhalter-Anfrage {} fehlgeschlagen.", anfrage.requestId(), e);
            return null;
        } finally {
            platzhalterWarter.remove(anfrage.requestId());
        }
    }

    private void vergleichePluginVersion() {
        String eigen = pluginVersion == null ? "" : pluginVersion.trim();
        String proxy = proxyPluginVersion == null ? "" : proxyPluginVersion.trim();
        if (eigen.isEmpty() || "unbekannt".equalsIgnoreCase(eigen)
                || proxy.isEmpty() || "unbekannt".equalsIgnoreCase(proxy)) {
            return;
        }
        if (eigen.equals(proxy)) {
            if (gemeldeteProxyPluginVersion != null) {
                gemeldeteProxyPluginVersion = null;
                LOG.info("Plugin-Version wieder einheitlich ({}).", eigen);
            }
            return;
        }
        if (proxy.equals(gemeldeteProxyPluginVersion)) {
            return;
        }
        gemeldeteProxyPluginVersion = proxy;
        LOG.warn("Plugin-Version weicht ab: Bridge={}, Proxy={} – bitte alle Module auf dieselbe Version bringen (Sync läuft weiter).",
                eigen, proxy);
    }

    private void sendeAnProxy(byte[] paket) {
        byte[] schluessel = bridgeZuProxySchluessel;
        if (schluessel == null) {
            long jetzt = System.currentTimeMillis();
            long letzte = letzteAuthWarnung;
            if (jetzt - letzte > AUTH_WARN_INTERVALL_MILLIS) {
                letzteAuthWarnung = jetzt;
                LOG.warn("Tunnel-Versand blockiert (netzwerk.tunnel-secret nicht gesetzt): "
                        + "Fail-closed – eigenes Secret für diese Bridge setzen.");
            } else {
                LOG.debug("Tunnel-Versand ohne Secret blockiert (Fail-closed).");
            }
            throw new IllegalStateException("Tunnel-Versand ohne Secret blockiert "
                    + "(netzwerk.tunnel-secret fehlt).");
        }
        byte[] versand = paket;
        try {
            long seq = sendeSeq.incrementAndGet();
            if (seq <= 0) {
                sendeSeq.set(1L);
                seq = 1L;
            }
            versand = Protokoll.rahmeMitSequenz(paket, seq);
            versand = de.lightrixx.punish.net.TunnelSignatur.signieren(versand, schluessel);
        } catch (Exception e) {
            LOG.warn("Tunnel-Paket konnte nicht signiert werden (nicht gesendet).", e);
            throw new IllegalStateException("Tunnel-Paket konnte nicht signiert werden.", e);
        }
        try {
            if (!plugin.getServer().isPrimaryThread()) {
                final byte[] versandFinal = versand;
                final java.util.concurrent.CountDownLatch latch =
                        new java.util.concurrent.CountDownLatch(1);
                final java.util.concurrent.atomic.AtomicReference<Throwable> fehler =
                        new java.util.concurrent.atomic.AtomicReference<>();
                try {
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        try {
                            sendeAnProxySync(versandFinal);
                        } catch (Throwable t) {
                            fehler.set(t);
                        } finally {
                            latch.countDown();
                        }
                    });
                } catch (Exception e) {
                    throw new IllegalStateException("Kanal-Versand konnte nicht auf Main geparkt werden.", e);
                }
                try {
                    boolean fertig = latch.await(5L, java.util.concurrent.TimeUnit.SECONDS);
                    if (!fertig) {
                        throw new IllegalStateException("Kanal-Versand auf Main timed out.");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Kanal-Versand unterbrochen.", e);
                }
                Throwable t = fehler.get();
                if (t instanceof RuntimeException re) {
                    throw re;
                }
                if (t instanceof Error err) {
                    throw err;
                }
                if (t != null) {
                    throw new IllegalStateException("Kanal-Versand fehlgeschlagen.", t);
                }
                return;
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            LOG.debug("Main-Thread-Check fehlgeschlagen, versuche Direktversand.", e);
        }
        sendeAnProxySync(versand);
    }

    private void sendeAnProxySync(byte[] paket) {
        Player traeger = null;
        try {
            for (Player online : plugin.getServer().getOnlinePlayers()) {
                if (online != null) {
                    traeger = online;
                    break;
                }
            }
        } catch (Exception e) {
            LOG.debug("Kein Nachrichten-Träger verfügbar.", e);
        }
        if (traeger == null) {
            throw new IllegalStateException("Kein Online-Spieler als Nachrichten-Träger.");
        }
        traeger.sendPluginMessage(plugin, Protokoll.KANAL, paket);
    }

    @Override
    public void onPluginMessageReceived(String kanal, Player spieler, byte[] nachricht) {
        if (!Protokoll.KANAL.equals(kanal) || nachricht == null || nachricht.length == 0) {
            return;
        }
        long jetztMillis = System.currentTimeMillis();
        if (spieler != null && (authBremse.gesperrt(spieler.getUniqueId(), jetztMillis)
                || authBremse.globalGesperrt(jetztMillis))) {
            return;
        }
        byte[] klar = nachricht;
        byte[] schluessel = proxyZuBridgeSchluessel;
        if (schluessel == null) {
            long jetzt = System.currentTimeMillis();
            long letzte = letzteAuthWarnung;
            if (jetzt - letzte > AUTH_WARN_INTERVALL_MILLIS) {
                letzteAuthWarnung = jetzt;
                LOG.warn("Tunnel-Nachricht vom Proxy abgewiesen (netzwerk.tunnel-secret nicht gesetzt): "
                        + "Fail-closed – eigenes Secret für diese Bridge setzen.");
            } else {
                LOG.debug("Tunnel-Nachricht ohne Secret abgewiesen (Fail-closed).");
            }
            return;
        }
        try {
            klar = de.lightrixx.punish.net.TunnelSignatur.pruefenUndAbziehen(nachricht, schluessel);
            if (spieler != null) {
                authBremse.erfolg(spieler.getUniqueId());
            }
        } catch (Exception e) {
            if (spieler != null) {
                authBremse.fehlschlag(spieler.getUniqueId(), System.currentTimeMillis());
            }
            long jetzt = System.currentTimeMillis();
            long letzte = letzteAuthWarnung;
            if (jetzt - letzte > AUTH_WARN_INTERVALL_MILLIS) {
                letzteAuthWarnung = jetzt;
                LOG.warn("Tunnel-Nachricht vom Proxy abgewiesen (Signatur fehlt/ungültig): "
                        + "netzwerk.tunnel-secret auf das Secret dieser Bridge setzen.");
            } else {
                LOG.debug("Tunnel-Nachricht vom Proxy abgewiesen (Signatur fehlt/ungültig).", e);
            }
            return;
        }
        try {
            klar = sequenzGeprueft(klar);
        } catch (Exception e) {
            LOG.debug("Tunnel-Sequenz verworfen.", e);
            return;
        }
        if (klar == null) {
            return;
        }
        Protokoll.Paket paket;
        try {
            paket = Protokoll.paket(klar);
        } catch (Exception e) {
            LOG.debug("Ungültiges Proxy-Paket verworfen.", e);
            return;
        }
        try {
            switch (paket.typ()) {
                case Protokoll.HELLO_ACK -> {
                    Protokoll.HelloAck ack = Protokoll.leseHelloAckMitName(paket.nutzdaten());
                    int version = ack.proxyVersion();
                    if (version < Protokoll.MIN_VERSION || version > Protokoll.VERSION) {
                        LOG.error("Proxy meldet Protokoll v{} (unterstützt v{}-v{}). Proxy-Update "
                                + "erforderlich, HELLO_ACK verworfen.",
                                version, Protokoll.MIN_VERSION, Protokoll.VERSION);
                        return;
                    }
                    proxyVersion = version;
                    proxyPluginVersion = ack.proxyPluginVersion() == null ? "" : ack.proxyPluginVersion().trim();
                    vergleichePluginVersion();
                    LOG.debug("Proxy-Protokoll v{} bestätigt.", version);
                    String kanonisch = ack.kanonischerServerName();
                    if (kanonisch != null && !kanonisch.isBlank()) {
                        try {
                            var handler = helloAckHandler;
                            if (handler != null) {
                                handler.accept(kanonisch.trim());
                            }
                        } catch (Exception e) {
                            LOG.debug("HELLO_ACK-Name konnte nicht übernommen werden.", e);
                        }
                    }
                }
                case Protokoll.SYNC_BEGIN -> {
                    long generation = Protokoll.leseSyncGeneration(paket.nutzdaten());
                    muteCache.syncBeginn(generation);
                    if (ipMuteCache != null) {
                        ipMuteCache.syncBeginn(generation);
                    }
                    if (banCache != null) {
                        banCache.syncBeginn(generation);
                    }
                    if (timeoutCache != null) {
                        timeoutCache.syncBeginn(generation);
                    }
                    if (freezeCache != null) {
                        freezeCache.syncBeginn(generation);
                    }
                    LOG.debug("Sync-Beginn erhalten (generation={}).", generation);
                }
                case Protokoll.SYNC_END -> {
                    long generation = Protokoll.leseSyncGeneration(paket.nutzdaten());
                    muteCache.syncEnde(generation);
                    if (ipMuteCache != null) {
                        ipMuteCache.syncEnde(generation);
                    }
                    if (banCache != null) {
                        banCache.syncEnde(generation);
                    }
                    if (timeoutCache != null) {
                        timeoutCache.syncEnde(generation);
                    }
                    if (freezeCache != null) {
                        freezeCache.syncEnde(generation);
                    }
                    LOG.debug("Sync-Ende erhalten (generation={}).", generation);
                }
                case Protokoll.MUTE_SYNC -> {
                    List<Protokoll.MuteEintrag> eintraege = Protokoll.leseMuteSync(paket.nutzdaten());
                    muteCache.ersetzeAlle(eintraege);
                    LOG.debug("Mute-Sync erhalten ({} Einträge).", eintraege.size());
                }
                case Protokoll.MUTE_SETZEN -> muteCache.setze(Protokoll.leseMute(paket.nutzdaten()));
                case Protokoll.MUTE_ENTFERNEN ->
                        muteCache.entferne(Protokoll.leseUuidListe(paket.nutzdaten()));
                case Protokoll.IP_MUTE_SYNC -> {
                    List<Protokoll.IpMuteEintrag> eintraege = Protokoll.leseIpMuteSync(paket.nutzdaten());
                    if (ipMuteCache != null) {
                        ipMuteCache.ersetzeAlle(eintraege);
                    }
                    LOG.debug("IP-Mute-Sync erhalten ({} Einträge).", eintraege.size());
                }
                case Protokoll.IP_MUTE_SETZEN -> {
                    if (ipMuteCache != null) {
                        ipMuteCache.setze(Protokoll.leseIpMute(paket.nutzdaten()));
                    } else {
                        Protokoll.leseIpMute(paket.nutzdaten());
                    }
                }
                case Protokoll.IP_MUTE_ENTFERNEN -> {
                    if (ipMuteCache != null) {
                        ipMuteCache.entferne(Protokoll.leseIpListe(paket.nutzdaten()));
                    } else {
                        Protokoll.leseIpListe(paket.nutzdaten());
                    }
                }
                case Protokoll.BAN_SYNC -> {
                    List<Protokoll.BanEintrag> eintraege = Protokoll.leseBanSync(paket.nutzdaten());
                    if (banCache != null) {
                        banCache.ersetzeAlle(eintraege);
                    }
                    LOG.debug("Ban-Sync erhalten ({} Einträge).", eintraege.size());
                }
                case Protokoll.BAN_SETZEN -> {
                    Protokoll.BanEintrag eintrag = Protokoll.leseBan(paket.nutzdaten());
                    if (banCache != null) {
                        banCache.setze(eintrag);
                    }
                }
                case Protokoll.BAN_ENTFERNEN -> {
                    List<UUID> uuids = Protokoll.leseUuidListe(paket.nutzdaten());
                    if (banCache != null) {
                        banCache.entferne(uuids);
                    }
                }
                case Protokoll.TIMEOUT_SYNC -> {
                    List<Protokoll.TimeoutEintrag> eintraege = Protokoll.leseTimeoutSync(paket.nutzdaten());
                    if (timeoutCache != null) {
                        timeoutCache.ersetzeAlle(eintraege);
                    }
                    LOG.debug("Timeout-Sync erhalten ({} Einträge).", eintraege.size());
                }
                case Protokoll.TIMEOUT_SETZEN -> {
                    Protokoll.TimeoutEintrag eintrag = Protokoll.leseTimeout(paket.nutzdaten());
                    if (timeoutCache != null) {
                        timeoutCache.setze(eintrag);
                    }
                }
                case Protokoll.TIMEOUT_ENTFERNEN -> {
                    List<UUID> uuids = Protokoll.leseUuidListe(paket.nutzdaten());
                    if (timeoutCache != null) {
                        timeoutCache.entferne(uuids);
                    }
                }
                case Protokoll.FREEZE_SYNC -> {
                    List<Protokoll.FreezeEintrag> eintraege = Protokoll.leseFreezeSync(paket.nutzdaten());
                    if (freezeCache != null) {
                        freezeCache.ersetzeAlle(eintraege);
                    }
                    LOG.debug("Freeze-Sync erhalten ({} Einträge).", eintraege.size());
                }
                case Protokoll.FREEZE_SETZEN -> {
                    Protokoll.FreezeEintrag eintrag = Protokoll.leseFreeze(paket.nutzdaten());
                    if (freezeCache != null) {
                        freezeCache.setze(eintrag);
                    }
                }
                case Protokoll.FREEZE_ENTFERNEN -> {
                    List<UUID> uuids = Protokoll.leseUuidListe(paket.nutzdaten());
                    if (freezeCache != null) {
                        freezeCache.entferne(uuids);
                    }
                }
                case Protokoll.GUI_ANTWORT -> {
                    Protokoll.GuiAntwort antwort = Protokoll.leseGuiAntwort(paket.nutzdaten());
                    var zukunft = guiWarter.remove(antwort.requestId());
                    if (zukunft != null) {
                        zukunft.complete(antwort);
                    } else {
                        LOG.debug("GUI-Antwort ohne Warter verworfen (id={}).", antwort.requestId());
                    }
                }
                case Protokoll.PLATZHALTER_ANTWORT -> {
                    Protokoll.PlatzhalterAntwort antwort =
                            Protokoll.lesePlatzhalterAntwort(paket.nutzdaten());
                    var zukunft = platzhalterWarter.remove(antwort.requestId());
                    if (zukunft != null) {
                        zukunft.complete(antwort);
                    } else {
                        LOG.debug("Platzhalter-Antwort ohne Warter verworfen (id={}).", antwort.requestId());
                    }
                }
                case Protokoll.CONFIG_RELOAD -> {
                    Runnable handler = reloadHandler;
                    if (handler == null) {
                        LOG.debug("Reload-Push ohne Handler verworfen.");
                    } else {
                        try {
                            handler.run();
                        } catch (Exception e) {
                            LOG.debug("Reload-Handler fehlgeschlagen.", e);
                        }
                    }
                }
                case Protokoll.CHATFILTER_STEUERUNG -> {
                    var handler = steuerungsHandler;
                    if (handler == null) {
                        LOG.debug("Chatfilter-Steuerung ohne Handler verworfen.");
                    } else {
                        try {
                            handler.accept(Protokoll.leseChatfilterSteuerung(paket.nutzdaten()));
                        } catch (Exception e) {
                            LOG.debug("Chatfilter-Steuerung fehlgeschlagen.", e);
                        }
                    }
                }
                case Protokoll.CHATFILTER_WORT -> {
                    var handler = wortHandler;
                    if (handler == null) {
                        LOG.debug("Chatfilter-Wort ohne Handler verworfen.");
                    } else {
                        try {
                            handler.accept(Protokoll.leseChatfilterWort(paket.nutzdaten()));
                        } catch (Exception e) {
                            LOG.debug("Chatfilter-Wort fehlgeschlagen.", e);
                        }
                    }
                }
                case Protokoll.MODELL_META -> {
                    var empfaenger = modellEmpfaenger;
                    if (empfaenger == null) {
                        LOG.debug("Modell-Meta ohne Empfänger verworfen.");
                    } else {
                        try {
                            empfaenger.empfangeMeta(Protokoll.leseModellMeta(paket.nutzdaten()));
                        } catch (Exception e) {
                            LOG.debug("Modell-Meta fehlgeschlagen.", e);
                        }
                    }
                }
                case Protokoll.MODELL_BLOCK -> {
                    var empfaenger = modellEmpfaenger;
                    if (empfaenger == null) {
                        LOG.debug("Modell-Block ohne Empfänger verworfen.");
                    } else {
                        try {
                            empfaenger.empfangeBlock(Protokoll.leseModellBlock(paket.nutzdaten()));
                        } catch (Exception e) {
                            LOG.debug("Modell-Block fehlgeschlagen.", e);
                        }
                    }
                }
                case Protokoll.MODELL_FERTIG -> {
                    var empfaenger = modellEmpfaenger;
                    if (empfaenger == null) {
                        LOG.debug("Modell-Fertig ohne Empfänger verworfen.");
                    } else {
                        try {
                            empfaenger.empfangeFertig(Protokoll.leseModellFertig(paket.nutzdaten()));
                        } catch (Exception e) {
                            LOG.debug("Modell-Fertig fehlgeschlagen.", e);
                        }
                    }
                }
                case Protokoll.TEXT_META -> {
                    var empfaenger = textEmpfaenger;
                    if (empfaenger == null) {
                        LOG.debug("Text-Meta ohne Empfänger verworfen.");
                    } else {
                        try {
                            empfaenger.empfangeMeta(Protokoll.leseTextMeta(paket.nutzdaten()));
                        } catch (Exception e) {
                            LOG.debug("Text-Meta fehlgeschlagen.", e);
                        }
                    }
                }
                case Protokoll.TEXT_BLOCK -> {
                    var empfaenger = textEmpfaenger;
                    if (empfaenger == null) {
                        LOG.debug("Text-Block ohne Empfänger verworfen.");
                    } else {
                        try {
                            empfaenger.empfangeBlock(Protokoll.leseTextBlock(paket.nutzdaten()));
                        } catch (Exception e) {
                            LOG.debug("Text-Block fehlgeschlagen.", e);
                        }
                    }
                }
                case Protokoll.TEXT_FERTIG -> {
                    var empfaenger = textEmpfaenger;
                    if (empfaenger == null) {
                        LOG.debug("Text-Fertig ohne Empfänger verworfen.");
                    } else {
                        try {
                            empfaenger.empfangeFertig(Protokoll.leseTextFertig(paket.nutzdaten()));
                        } catch (Exception e) {
                            LOG.debug("Text-Fertig fehlgeschlagen.", e);
                        }
                    }
                }
                case Protokoll.UPDATE_META -> {
                    var empfaenger = updateEmpfaenger;
                    if (empfaenger == null) {
                        LOG.debug("Update-Meta ohne Empfänger verworfen.");
                    } else {
                        try {
                            empfaenger.empfangeMeta(Protokoll.leseUpdateMeta(paket.nutzdaten()));
                        } catch (Exception e) {
                            LOG.debug("Update-Meta fehlgeschlagen.", e);
                        }
                    }
                }
                case Protokoll.UPDATE_BLOCK -> {
                    var empfaenger = updateEmpfaenger;
                    if (empfaenger == null) {
                        LOG.debug("Update-Block ohne Empfänger verworfen.");
                    } else {
                        try {
                            empfaenger.empfangeBlock(Protokoll.leseUpdateBlock(paket.nutzdaten()));
                        } catch (Exception e) {
                            LOG.debug("Update-Block fehlgeschlagen.", e);
                        }
                    }
                }
                case Protokoll.UPDATE_FERTIG -> {
                    var empfaenger = updateEmpfaenger;
                    if (empfaenger == null) {
                        LOG.debug("Update-Fertig ohne Empfänger verworfen.");
                    } else {
                        try {
                            empfaenger.empfangeFertig(Protokoll.leseUpdateFertig(paket.nutzdaten()));
                        } catch (Exception e) {
                            LOG.debug("Update-Fertig fehlgeschlagen.", e);
                        }
                    }
                }
                default -> LOG.debug("Unbekannter Proxy-Typ {} ignoriert.", paket.typ());
            }
        } catch (Exception e) {
            LOG.debug("Proxy-Nachricht konnte nicht verarbeitet werden.", e);
        }
    }

    byte[] sequenzGeprueft(byte[] klar) throws java.io.IOException {
        Protokoll.SequenzRahmen rahmen = Protokoll.entrahmeSequenz(klar);
        if (!rahmen.sequenziert()) {
            LOG.warn("Tunnel-Nachricht ohne Sequenzrahmen verworfen (Protokoll v{} verlangt Sequenz).",
                    Protokoll.VERSION);
            return null;
        }
        if (empfangsPruefer.akzeptiere(rahmen.sequenz())) {
            return rahmen.paket();
        }
        if (empfangsPruefer.brauchtResync(rahmen.sequenz())) {
            LOG.warn("Tunnel-Replay-Fenster verlassen (seq {}), warte auf Re-HELLO.", rahmen.sequenz());
        } else {
            LOG.debug("Tunnel-Replay verworfen (seq {}).", rahmen.sequenz());
        }
        return null;
    }

    static String sanierenFuerLog(String wert) {
        if (wert == null) {
            return "";
        }
        StringBuilder bereinigt = new StringBuilder(Math.min(wert.length(), 128));
        for (int i = 0; i < wert.length() && bereinigt.length() < 80; i++) {
            char c = wert.charAt(i);
            if (c == '\r' || c == '\n' || c == 0x1B) {
                bereinigt.append(' ');
            } else if (c < 0x20 || c == 0x7F) {
                continue;
            } else {
                bereinigt.append(c);
            }
        }
        return bereinigt.toString();
    }
}
