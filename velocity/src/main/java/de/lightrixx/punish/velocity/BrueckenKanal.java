package de.lightrixx.punish.velocity;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import de.lightrixx.punish.core.MessageKeys;
import de.lightrixx.punish.core.RangBuendel;
import de.lightrixx.punish.model.IpMuteRecord;
import de.lightrixx.punish.model.FreezeRecord;
import de.lightrixx.punish.model.MuteRecord;
import de.lightrixx.punish.net.Protokoll;
import de.lightrixx.punish.service.MessageService;
import de.lightrixx.punish.storage.IpMuteRepository;
import de.lightrixx.punish.storage.MuteRepository;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class BrueckenKanal {

    private final PunishVelocity plugin;
    private final ProxyServer proxy;
    private final Logger logger;
    private final MuteRepository muteRepository;
    private final IpMuteRepository ipMuteRepository;
    private volatile de.lightrixx.punish.storage.BanRepository banRepository;
    private volatile de.lightrixx.punish.storage.TimeoutRepository timeoutRepository;
    private volatile de.lightrixx.punish.storage.FreezeRepository freezeRepository;
    private volatile MessageService nachrichten;
    private volatile ModellVerteilung modellVerteilung;
    private volatile TextVerteilung textVerteilung;
    private volatile de.lightrixx.punish.service.BroadcastService broadcastService;
    private volatile String pluginVersion = "";
    private record BrueckenSchluessel(byte[] proxyZuBridge, byte[] bridgeZuProxy) {
    }
    private volatile Map<String, BrueckenSchluessel> brueckenSchluessel = Map.of();
    private final java.util.concurrent.ConcurrentHashMap<String, Long> letzteAuthWarnung =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final long AUTH_WARN_INTERVALL_MILLIS = 60L * 1000L;
    private final java.util.concurrent.ConcurrentHashMap<String, String> bridgeVersionen =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Set<String> gemeldeteVersionen =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.ConcurrentHashMap<String, Long> letzterVollSync =
            new java.util.concurrent.ConcurrentHashMap<>();
    static final long VOLL_SYNC_INTERVALL_MILLIS = 30L * 60L * 1000L;
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong> sendeSeq =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, Protokoll.SequenzPruefer> empfangsPruefer =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, Integer> protokollVersionen =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Set<String> helloBekannt =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.ConcurrentHashMap<String, Long> letzteSeqWarnung =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.ArrayDeque<String>>
            helloSessionen = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int MAX_HELLO_SESSIONEN = 128;

    private final ChannelIdentifier kanalId =
            MinecraftChannelIdentifier.from(Protokoll.KANAL);

    public BrueckenKanal(PunishVelocity plugin, ProxyServer proxy, Logger logger,
                         MuteRepository muteRepository) {
        this(plugin, proxy, logger, muteRepository, null);
    }

    public BrueckenKanal(PunishVelocity plugin, ProxyServer proxy, Logger logger,
                         MuteRepository muteRepository, IpMuteRepository ipMuteRepository) {
        Objects.requireNonNull(plugin, "Das Plugin darf nicht null sein.");
        this.plugin = plugin;
        this.proxy = Objects.requireNonNull(proxy, "Der Proxy darf nicht null sein.");
        this.logger = Objects.requireNonNull(logger, "Der Logger darf nicht null sein.");
        this.muteRepository = Objects.requireNonNull(muteRepository, "Das MuteRepository darf nicht null sein.");
        this.ipMuteRepository = ipMuteRepository;
    }

    public void registrieren() {
        try {
            proxy.getChannelRegistrar().register(kanalId);
            logger.info("Bridge-Kanal '{}' registriert.", Protokoll.KANAL);
        } catch (Exception e) {
            logger.warn("Bridge-Kanal konnte nicht registriert werden.", e);
        }
    }

    public ChannelIdentifier kanalId() {
        return kanalId;
    }

    public void setBanRepository(de.lightrixx.punish.storage.BanRepository banRepository) {
        this.banRepository = banRepository;
    }

    public void setTimeoutRepository(de.lightrixx.punish.storage.TimeoutRepository timeoutRepository) {
        this.timeoutRepository = timeoutRepository;
    }

    public void setFreezeRepository(de.lightrixx.punish.storage.FreezeRepository freezeRepository) {
        this.freezeRepository = freezeRepository;
    }

    public void setMessageService(MessageService nachrichten) {
        this.nachrichten = nachrichten;
    }

    public void setModellVerteilung(ModellVerteilung modellVerteilung) {
        this.modellVerteilung = modellVerteilung;
    }

    public void setTextVerteilung(TextVerteilung textVerteilung) {
        this.textVerteilung = textVerteilung;
    }

    public boolean textSyncAnAlle() {
        TextVerteilung verteilung = textVerteilung;
        if (verteilung == null) {
            logger.debug("Text-Sync ohne Dienst verworfen.");
            return false;
        }
        try {
            if (!verteilung.aktiv()) {
                return false;
            }
            verteilung.syncAnAlle();
            return true;
        } catch (Exception e) {
            logger.warn("Text-Sync konnte nicht angestoßen werden.", e);
            return false;
        }
    }

    public boolean textWortHinzufuegen(String wort) {
        TextVerteilung verteilung = textVerteilung;
        if (verteilung == null) {
            logger.debug("Text-Wort ohne Dienst verworfen.");
            return false;
        }
        try {
            return verteilung.wortHinzufuegen(wort);
        } catch (Exception e) {
            logger.warn("Text-Wort konnte nicht übernommen werden.", e);
            return false;
        }
    }

    public List<String> textWoerter() {
        TextVerteilung verteilung = textVerteilung;
        if (verteilung == null) {
            return List.of();
        }
        try {
            return verteilung.woerter();
        } catch (Exception e) {
            logger.warn("Wortliste konnte nicht gelesen werden.", e);
            return List.of();
        }
    }

    public boolean textWortEntfernen(String wort) {
        TextVerteilung verteilung = textVerteilung;
        if (verteilung == null) {
            logger.debug("Text-Wort-Entfernen ohne Dienst verworfen.");
            return false;
        }
        try {
            return verteilung.wortEntfernen(wort);
        } catch (Exception e) {
            logger.warn("Wort konnte nicht entfernt werden.", e);
            return false;
        }
    }

    public void textNeuLadenAnAlle() {
        TextVerteilung verteilung = textVerteilung;
        if (verteilung == null) {
            return;
        }
        try {
            verteilung.laden();
        } catch (Exception e) {
            logger.warn("Text-Dateien konnten nach Reload nicht neu gelesen werden.", e);
            return;
        }
        textSyncAnAlle();
    }

    public void setBroadcastService(de.lightrixx.punish.service.BroadcastService broadcastService) {
        this.broadcastService = broadcastService;
    }

    public void setPluginVersion(String pluginVersion) {
        this.pluginVersion = pluginVersion == null ? "" : pluginVersion.trim();
    }

    static List<String> doppelteSecretNamen(Map<String, String> secrets) {
        List<String> doppelt = new ArrayList<>();
        if (secrets == null) {
            return doppelt;
        }
        Map<String, String> erste = new LinkedHashMap<>();
        for (Map.Entry<String, String> eintrag : secrets.entrySet()) {
            String wert = eintrag.getValue() == null ? "" : eintrag.getValue().trim();
            if (wert.isEmpty()) {
                continue;
            }
            if (erste.putIfAbsent(wert, eintrag.getKey()) != null) {
                doppelt.add(eintrag.getKey());
            }
        }
        return doppelt;
    }

    /**
     * Setzt pro Bridge ein eigenes Secret. Der Servername ist der kanonische
     * Velocity-Name. Fehlt ein Eintrag, wird die Bridge fail-closed abgewiesen.
     */
    public void setBridgeSecrets(Map<String, String> secrets) {
        List<String> doppelte = doppelteSecretNamen(secrets);
        if (!doppelte.isEmpty()) {
            logger.warn("netzwerk.bridge-secrets: identisches Secret für {} – eine "
                    + "kompromittierte Bridge kann damit Pakete der anderen signieren. "
                    + "Pro Bridge ein eigenes Secret setzen.", String.join(", ", doppelte));
        }
        Map<String, BrueckenSchluessel> neu = new LinkedHashMap<>();
        if (secrets != null) {
            for (Map.Entry<String, String> eintrag : secrets.entrySet()) {
                String name = de.lightrixx.punish.model.ServerScope
                        .normalisiereServerName(eintrag.getKey());
                if (name == null) {
                    logger.warn("netzwerk.bridge-secrets: ungültiger Servername '{}' ignoriert.",
                            eintrag.getKey());
                    continue;
                }
                String secret = eintrag.getValue() == null ? "" : eintrag.getValue().trim();
                if (secret.isEmpty()) {
                    continue;
                }
                if (de.lightrixx.punish.net.TunnelSignatur.istSchwach(secret)) {
                    logger.warn("Tunnel-Secret für '{}' ist kürzer als {} Zeichen – schwaches "
                            + "Geheimnis, per Wörterbuch-/Online-Raten angreifbar.",
                            name, de.lightrixx.punish.net.TunnelSignatur.MINDESTLAENGE);
                }
                byte[] hin = de.lightrixx.punish.net.TunnelSignatur.schluesselAus(secret,
                        de.lightrixx.punish.net.TunnelSignatur.Richtung.PROXY_ZU_BRIDGE);
                byte[] zurueck = de.lightrixx.punish.net.TunnelSignatur.schluesselAus(secret,
                        de.lightrixx.punish.net.TunnelSignatur.Richtung.BRIDGE_ZU_PROXY);
                neu.put(name, new BrueckenSchluessel(hin, zurueck));
            }
        }
        this.brueckenSchluessel = Map.copyOf(neu);
        if (neu.isEmpty()) {
            logger.warn("Tunnel-Signatur inaktiv (keine netzwerk.bridge-secrets): "
                    + "Fail-closed – Tunnel-Nachrichten werden abgewiesen, bis pro Bridge ein "
                    + "eigenes Secret gesetzt ist.");
        } else {
            logger.info("Tunnel-Signatur aktiv für {} Bridge(s), richtungsgebunden.", neu.size());
        }
    }

    public boolean tunnelSigniert() {
        return !brueckenSchluessel.isEmpty();
    }

    public void sendeAn(RegisteredServer server, byte[] paket) {
        if (server == null || paket == null) {
            return;
        }
        BrueckenSchluessel paar = signaturSchluessel(server);
        if (paar == null) {
            versandOhneSecretBlockiert();
            return;
        }
        byte[] versand = paket;
        try {
            versand = rahmeFuerVersand(server, paket);
            versand = de.lightrixx.punish.net.TunnelSignatur.signieren(versand, paar.proxyZuBridge());
        } catch (Exception e) {
            logger.warn("Tunnel-Paket konnte nicht signiert werden (nicht gesendet).", e);
            return;
        }
        try {
            server.sendPluginMessage(kanalId, versand);
        } catch (Exception e) {
            logger.debug("Tunnel-Versand an {} fehlgeschlagen.",
                    server.getServerInfo().getName(), e);
        }
    }

    private void versandOhneSecretBlockiert() {
        long jetzt = System.currentTimeMillis();
        Long letzte = letzteAuthWarnung.get("?");
        if (letzte == null || jetzt - letzte > AUTH_WARN_INTERVALL_MILLIS) {
            letzteAuthWarnung.put("?", jetzt);
            logger.warn("Tunnel-Versand blockiert (kein netzwerk.bridge-secrets-Eintrag): "
                    + "Für jede Bridge ein eigenes Secret setzen.");
        } else {
            logger.debug("Tunnel-Versand ohne Secret blockiert (Fail-closed).");
        }
    }

    public byte[] eingehendPruefen(RegisteredServer server, byte[] roh) {
        if (roh == null || roh.length == 0) {
            return null;
        }
        BrueckenSchluessel paar = signaturSchluessel(server);
        if (paar == null) {
            String name = schluesselFuer(server);
            long jetzt = System.currentTimeMillis();
            Long letzte = letzteAuthWarnung.get(name);
            if (letzte == null || jetzt - letzte > AUTH_WARN_INTERVALL_MILLIS) {
                letzteAuthWarnung.put(name, jetzt);
                logger.warn("Tunnel-Nachricht von '{}' abgewiesen (kein netzwerk.bridge-secrets-Eintrag): "
                        + "Für jede Bridge ein eigenes Secret setzen.", name);
            } else {
                logger.debug("Tunnel-Nachricht von '{}' ohne Secret abgewiesen (Fail-closed).", name);
            }
            return null;
        }
        try {
            return de.lightrixx.punish.net.TunnelSignatur.pruefenUndAbziehen(roh,
                    paar.bridgeZuProxy());
        } catch (Exception e) {
            String name = "?";
            try {
                name = server == null ? "?" : server.getServerInfo().getName();
            } catch (Exception ex) {
                logger.debug("Servername für Abweisungswarnung nicht lesbar.", ex);
            }
            long jetzt = System.currentTimeMillis();
            Long letzte = letzteAuthWarnung.get(name);
            if (letzte == null || jetzt - letzte > AUTH_WARN_INTERVALL_MILLIS) {
                letzteAuthWarnung.put(name, jetzt);
                logger.warn("Tunnel-Nachricht von '{}' abgewiesen (Signatur fehlt/ungültig): "
                        + "netzwerk.bridge-secrets-Eintrag und das Secret der Bridge prüfen, "
                        + "sonst bleibt die Bridge stumm.", name);
            } else {
                logger.debug("Tunnel-Nachricht von '{}' abgewiesen (Signatur fehlt/ungültig).", name);
            }
            return null;
        }
    }

    private byte[] rahmeFuerVersand(RegisteredServer server, byte[] paket) throws java.io.IOException {
        Integer version = protokollVersionen.get(schluesselFuer(server));
        if (version == null || version < Protokoll.MIN_VERSION) {
            return paket;
        }
        var zaehler = sendeSeq.computeIfAbsent(schluesselFuer(server),
                k -> new java.util.concurrent.atomic.AtomicLong());
        long seq = zaehler.incrementAndGet();
        if (seq <= 0) {
            zaehler.set(1L);
            seq = 1L;
        }
        return Protokoll.rahmeMitSequenz(paket, seq);
    }

    private String schluesselFuer(RegisteredServer server) {
        try {
            String name = de.lightrixx.punish.model.ServerScope.normalisiereServerName(
                    server == null ? null : server.getServerInfo().getName());
            if (name != null) {
                return name;
            }
        } catch (Exception e) {
            logger.debug("Servername nicht lesbar (Kanalschlüssel).", e);
        }
        return "?";
    }

    private BrueckenSchluessel signaturSchluessel(RegisteredServer server) {
        return brueckenSchluessel.get(schluesselFuer(server));
    }

    /**
     * B-003/B-012: Replay- und Hello-Gating nach der Signaturprüfung. Gibt das
     * entrahmente Paket oder {@code null} bei Drop zurück. HELLO setzt die
     * Zähler beider Richtungen zurück; Nicht-HELLO vor dem ersten HELLO wird
     * verworfen (Re-HELLO kommt via Join-/Refresh-Heilung der Bridge).
     */
    public byte[] sequenzPruefen(RegisteredServer server, byte[] klar) {
        if (klar == null || klar.length == 0) {
            return null;
        }
        String schluessel = schluesselFuer(server);
        Protokoll.SequenzRahmen rahmen;
        try {
            rahmen = Protokoll.entrahmeSequenz(klar);
        } catch (Exception e) {
            logger.debug("Tunnel-Sequenzrahmen ungültig, verworfen.", e);
            return null;
        }
        byte[] paket = rahmen.paket();
        if (paket.length < 1) {
            return null;
        }
        int typ = paket[0] & 0xFF;
        if (typ == Protokoll.HELLO) {
            String session;
            try {
                session = Protokoll.leseHello(Protokoll.paket(paket).nutzdaten()).sessionId();
            } catch (Exception e) {
                logger.debug("HELLO ohne lesbare Session-ID verworfen.", e);
                return null;
            }
            if (session == null || session.isBlank()) {
                logger.warn("HELLO von '{}' ohne Session-ID verworfen (Protokoll v{}).",
                        schluessel, Protokoll.VERSION);
                return null;
            }
            if (!helloSessionNeu(schluessel, session)) {
                logger.warn("HELLO-Replay von '{}' verworfen (Session bereits gesehen).", schluessel);
                return null;
            }
            empfangsPruefer.computeIfAbsent(schluessel,
                    k -> new Protokoll.SequenzPruefer()).zuruecksetzen();
            sendeSeq.computeIfAbsent(schluessel,
                    k -> new java.util.concurrent.atomic.AtomicLong()).set(0L);
            return paket;
        }
        if (!helloBekannt.contains(schluessel)) {
            logger.debug("Tunnel-Nachricht von '{}' vor HELLO verworfen (Hello-Gating).", schluessel);
            return null;
        }
        if (!rahmen.sequenziert()) {
            seqWarnung(schluessel);
            return null;
        }
        Integer version = protokollVersionen.get(schluessel);
        if (version == null || version < Protokoll.MIN_VERSION) {
            seqWarnung(schluessel);
            return null;
        }
        var pruefer = empfangsPruefer.computeIfAbsent(schluessel,
                k -> new Protokoll.SequenzPruefer());
        if (pruefer.akzeptiere(rahmen.sequenz())) {
            return paket;
        }
        if (pruefer.brauchtResync(rahmen.sequenz())) {
            logger.warn("Tunnel-Replay-Fenster für '{}' verlassen (seq {}), "
                    + "warte auf Re-HELLO (Join-/Refresh-Heilung der Bridge).",
                    schluessel, rahmen.sequenz());
        } else {
            logger.debug("Tunnel-Replay für '{}' verworfen (seq {}).", schluessel, rahmen.sequenz());
        }
        return null;
    }

    private boolean helloSessionNeu(String server, String session) {
        java.util.ArrayDeque<String> liste = helloSessionen.computeIfAbsent(
                server, k -> new java.util.ArrayDeque<>());
        synchronized (liste) {
            if (liste.contains(session)) {
                return false;
            }
            liste.addLast(session);
            while (liste.size() > MAX_HELLO_SESSIONEN) {
                liste.removeFirst();
            }
            return true;
        }
    }

    private void seqWarnung(String schluessel) {
        long jetzt = System.currentTimeMillis();
        Long letzte = letzteSeqWarnung.get(schluessel);
        if (letzte == null || jetzt - letzte > AUTH_WARN_INTERVALL_MILLIS) {
            letzteSeqWarnung.put(schluessel, jetzt);
            logger.warn("Tunnel-Nachricht von '{}' ohne gültigen Sequenzrahmen/Protokoll verworfen "
                    + "(erwartet v{}).", schluessel, Protokoll.VERSION);
        } else {
            logger.debug("Tunnel-Nachricht von '{}' ohne gültigen Sequenzrahmen verworfen.", schluessel);
        }
    }

    public java.util.Map<String, String> bridgeVersionen() {
        return java.util.Map.copyOf(bridgeVersionen);
    }

    private final java.util.concurrent.ConcurrentHashMap<String, String> bridgeVarianten =
            new java.util.concurrent.ConcurrentHashMap<>();
    private volatile UpdateVerteilung updateVerteilung;

    public void setUpdateVerteilung(UpdateVerteilung verteilung) {
        this.updateVerteilung = verteilung;
    }

    public java.util.Map<String, String> bridgeVarianten() {
        return java.util.Map.copyOf(bridgeVarianten);
    }

    public void merkeBridgeVariante(String server, String variante) {
        String s = server == null ? "" : server.trim();
        String v = variante == null ? "" : variante.trim().toLowerCase(java.util.Locale.ROOT);
        if (s.isEmpty() || v.isEmpty()) {
            return;
        }
        if (Protokoll.UPDATE_VARIANTEN.contains(v)) {
            bridgeVarianten.put(s, v);
        }
    }

    public boolean modellSyncAnAlle() {
        ModellVerteilung verteilung = modellVerteilung;
        if (verteilung == null) {
            logger.debug("Modell-Sync ohne Dienst verworfen.");
            return false;
        }
        try {
            if (!verteilung.aktiv()) {
                return false;
            }
            verteilung.syncAnAlle();
            return true;
        } catch (Exception e) {
            logger.warn("Modell-Sync konnte nicht angestoßen werden.", e);
            return false;
        }
    }

    private static final int SYNC_CHUNK_GROESSE = 200;
    private final java.security.SecureRandom syncZufall = new java.security.SecureRandom();

    private long naechsteSyncGeneration() {
        long generation = syncZufall.nextLong() & Long.MAX_VALUE;
        return generation <= 0 ? 1L : generation;
    }

    private void sendeSyncRahmen(RegisteredServer server, long generation, boolean beginn) {
        try {
            byte[] paket = beginn ? Protokoll.syncBegin(generation) : Protokoll.syncEnd(generation);
            sendeAn(server, paket);
        } catch (Exception e) {
            logger.debug("Sync-Rahmen konnte nicht gesendet werden.", e);
        }
    }

    public void mutePushAnAlle() {
        for (RegisteredServer server : proxy.getAllServers()) {
            mutePushAn(server);
        }
    }

    public void reloadAnAlle() {        byte[] paket;
        try {
            paket = Protokoll.configReload();
        } catch (Exception e) {
            logger.warn("Reload-Paket konnte nicht kodiert werden.", e);
            return;
        }
        for (RegisteredServer server : proxy.getAllServers()) {
            try {
                sendeAn(server, paket);
            } catch (Exception e) {
                logger.debug("Reload-Push an {} fehlgeschlagen.",
                        server.getServerInfo().getName(), e);
            }
        }
        logger.info("Reload-Push an Bridges gesendet.");
    }

    public void chatfilterSteuerungAnAlle(Boolean filterAn, Boolean kiAn) {
        byte[] paket;
        try {
            paket = Protokoll.chatfilterSteuerung(filterAn, kiAn);
        } catch (Exception e) {
            logger.warn("Chatfilter-Steuerung konnte nicht kodiert werden.", e);
            return;
        }
        for (RegisteredServer server : proxy.getAllServers()) {
            try {
                sendeAn(server, paket);
            } catch (Exception e) {
                logger.debug("Chatfilter-Steuerung an {} fehlgeschlagen.",
                        server.getServerInfo().getName(), e);
            }
        }
        logger.info("Chatfilter-Steuerung gesendet (Filter={}, KI={}).", filterAn, kiAn);
    }

    public void chatfilterWortAn(RegisteredServer server, String wort) {
        if (server == null || wort == null || wort.isBlank()) {
            return;
        }
        try {
            sendeAn(server, Protokoll.chatfilterWort(wort.trim()));
            logger.info("Chatfilter-Wort an '{}' gesendet.", server.getServerInfo().getName());
        } catch (Exception e) {
            logger.warn("Chatfilter-Wort an '{}' fehlgeschlagen.",
                    server.getServerInfo().getName(), e);
        }
    }
    public boolean mutePushAn(RegisteredServer server) {
        if (server == null) {
            return false;
        }
        List<Protokoll.MuteEintrag> eintraege;
        List<Protokoll.IpMuteEintrag> ipEintraege;
        try {
            eintraege = aktiveMutes();
            ipEintraege = aktiveIpMutes();
        } catch (RuntimeException e) {
            logger.warn("Mute-Sync abgebrochen (Repository nicht lesbar), Bridge-Caches bleiben unverändert.", e);
            return false;
        }
        if (eintraege.size() <= SYNC_CHUNK_GROESSE && ipEintraege.size() <= SYNC_CHUNK_GROESSE) {
            try {
                byte[] paket = Protokoll.muteSync(eintraege);
                byte[] ipPaket = Protokoll.ipMuteSync(ipEintraege);
                try {
                    sendeAn(server, paket);
                    sendeAn(server, ipPaket);
                } catch (Exception e) {
                    logger.debug("Mute-Sync an {} fehlgeschlagen.", server.getServerInfo().getName(), e);
                    return false;
                }
                return sendeBanTimeoutFreezeSync(server, true);
            } catch (Exception e) {
                logger.warn("Mute-Sync konnte nicht kodiert werden, weiche auf Einzel-Sync aus.", e);
                sendeEinzelMutes(server, eintraege, ipEintraege);
            }
        }
        return sendeBanTimeoutFreezeSync(server, false);
    }

    private void sendeEinzelMutes(RegisteredServer server, List<Protokoll.MuteEintrag> mutes,
                                  List<Protokoll.IpMuteEintrag> ipMutes) {
        long generation = naechsteSyncGeneration();
        sendeSyncRahmen(server, generation, true);
        boolean abgebrochen = false;
        for (Protokoll.MuteEintrag eintrag : mutes) {
            try {
                sendeAn(server, Protokoll.nachricht(Protokoll.MUTE_SETZEN,
                        out -> Protokoll.schreibeMute(out, eintrag)));
            } catch (Exception e) {
                logger.warn("Mute-Einzel-Sync konnte nicht gesendet werden.", e);
                abgebrochen = true;
                break;
            }
        }
        if (!abgebrochen) {
            for (Protokoll.IpMuteEintrag eintrag : ipMutes) {
                try {
                    sendeAn(server, Protokoll.nachricht(Protokoll.IP_MUTE_SETZEN,
                            out -> Protokoll.schreibeIpMute(out, eintrag)));
                } catch (Exception e) {
                    logger.warn("IP-Mute-Einzel-Sync konnte nicht gesendet werden.", e);
                    abgebrochen = true;
                    break;
                }
            }
        }
        if (!abgebrochen) {
            sendeSyncRahmen(server, generation, false);
        }
    }

    private boolean sendeBanTimeoutFreezeSync(RegisteredServer server, boolean mutesEingeschlossen) {
        // Mutes/IP-Mutes wurden von mutePushAn bereits gesendet (Bulk- oder Einzel-Sync);
        // sie nur dann erneut mitsenden, wenn sie oben wegen der Chunk-Grenze ausgelassen wurden.
        List<Protokoll.BanEintrag> banns;
        List<Protokoll.TimeoutEintrag> timeouts;
        List<Protokoll.FreezeEintrag> freezes;
        List<Protokoll.MuteEintrag> mutes = List.of();
        List<Protokoll.IpMuteEintrag> ipMutes = List.of();
        try {
            banns = aktiveBanns();
            timeouts = aktiveTimeouts();
            freezes = aktiveFreezes();
            if (!mutesEingeschlossen) {
                mutes = aktiveMutes();
                ipMutes = aktiveIpMutes();
            }
        } catch (RuntimeException e) {
            logger.warn("Ban/Timeout/Freeze-Sync abgebrochen (Repository nicht lesbar), "
                    + "Bridge-Caches bleiben unverändert.", e);
            return false;
        }
        boolean mutesImBulk = mutesEingeschlossen
                || (mutes.size() <= SYNC_CHUNK_GROESSE && ipMutes.size() <= SYNC_CHUNK_GROESSE);
        if (banns.size() <= SYNC_CHUNK_GROESSE && timeouts.size() <= SYNC_CHUNK_GROESSE
                && freezes.size() <= SYNC_CHUNK_GROESSE && mutesImBulk) {
            try {
                byte[] banPaket = Protokoll.banSync(banns);
                byte[] timeoutPaket = Protokoll.timeoutSync(timeouts);
                byte[] freezePaket = Protokoll.freezeSync(freezes);
                try {
                    sendeAn(server, banPaket);
                    sendeAn(server, timeoutPaket);
                    sendeAn(server, freezePaket);
                } catch (Exception e) {
                    logger.debug("Ban/Timeout/Freeze-Sync an {} fehlgeschlagen.", server.getServerInfo().getName(), e);
                    return false;
                }
                return true;
            } catch (Exception e) {
                logger.warn("Ban/Timeout/Freeze-Sync konnte nicht kodiert werden, weiche auf Einzel-Sync aus.", e);
            }
        }
        long generation = naechsteSyncGeneration();
        sendeSyncRahmen(server, generation, true);
        boolean abgebrochen = false;
        for (Protokoll.MuteEintrag eintrag : mutes) {
            try {
                sendeAn(server, Protokoll.nachricht(Protokoll.MUTE_SETZEN,
                        out -> Protokoll.schreibeMute(out, eintrag)));
            } catch (Exception e) {
                logger.warn("Mute-Einzel-Sync konnte nicht gesendet werden.", e);
                abgebrochen = true;
                break;
            }
        }
        if (!abgebrochen) {
            for (Protokoll.IpMuteEintrag eintrag : ipMutes) {
                try {
                    sendeAn(server, Protokoll.nachricht(Protokoll.IP_MUTE_SETZEN,
                            out -> Protokoll.schreibeIpMute(out, eintrag)));
                } catch (Exception e) {
                    logger.warn("IP-Mute-Einzel-Sync konnte nicht gesendet werden.", e);
                    abgebrochen = true;
                    break;
                }
            }
        }
        for (Protokoll.BanEintrag eintrag : banns) {
            if (abgebrochen) {
                break;
            }
            try {
                sendeAn(server, Protokoll.nachricht(Protokoll.BAN_SETZEN,
                        out -> Protokoll.schreibeBan(out, eintrag)));
            } catch (Exception e) {
                logger.warn("Ban-Einzel-Sync konnte nicht gesendet werden.", e);
                abgebrochen = true;
                break;
            }
        }
        for (Protokoll.TimeoutEintrag eintrag : timeouts) {
            if (abgebrochen) {
                break;
            }
            try {
                sendeAn(server, Protokoll.nachricht(Protokoll.TIMEOUT_SETZEN,
                        out -> Protokoll.schreibeTimeout(out, eintrag)));
            } catch (Exception e) {
                logger.warn("Timeout-Einzel-Sync konnte nicht gesendet werden.", e);
                abgebrochen = true;
                break;
            }
        }
        for (Protokoll.FreezeEintrag eintrag : freezes) {
            if (abgebrochen) {
                break;
            }
            try {
                sendeAn(server, Protokoll.nachricht(Protokoll.FREEZE_SETZEN,
                        out -> Protokoll.schreibeFreeze(out, eintrag)));
            } catch (Exception e) {
                logger.warn("Freeze-Einzel-Sync konnte nicht gesendet werden.", e);
                abgebrochen = true;
                break;
            }
        }
        if (!abgebrochen) {
            sendeSyncRahmen(server, generation, false);
        }
        logger.info("Voll-Sync gechunkt an '{}' gesendet ({} Banns, {} Timeouts, {} Freezes, "
                + "{} Mutes, {} IP-Mutes).",
                server.getServerInfo().getName(), banns.size(), timeouts.size(), freezes.size(),
                mutes.size(), ipMutes.size());
        return !abgebrochen;
    }

    private List<Protokoll.BanEintrag> aktiveBanns() {
        List<Protokoll.BanEintrag> eintraege = new ArrayList<>();
        de.lightrixx.punish.storage.BanRepository repo = banRepository;
        if (repo == null) {
            return eintraege;
        }
        long jetzt = System.currentTimeMillis();
        try {
            for (de.lightrixx.punish.model.BanRecord record : repo.alle()) {
                if (record != null && record.istAktiv(jetzt)) {
                    long ablauf = record.ablaufAm() == null ? Long.MAX_VALUE : record.ablaufAm();
                    eintraege.add(new Protokoll.BanEintrag(record.spielerUuid(), record.spielerName(),
                            record.grund(), record.ausfuehrer(), record.erstelltAm(),
                            ablauf, record.scope().speicherwert()));
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Aktive Banns konnten nicht gelesen werden.", e);
        }
        return eintraege;
    }

    private List<Protokoll.TimeoutEintrag> aktiveTimeouts() {
        List<Protokoll.TimeoutEintrag> eintraege = new ArrayList<>();
        de.lightrixx.punish.storage.TimeoutRepository repo = timeoutRepository;
        if (repo == null) {
            return eintraege;
        }
        long jetzt = System.currentTimeMillis();
        try {
            for (de.lightrixx.punish.model.TimeoutRecord record : repo.alle()) {
                if (record != null && record.istAktiv(jetzt)) {
                    eintraege.add(new Protokoll.TimeoutEintrag(record.spielerUuid(), record.spielerName(),
                            record.grund(), record.ausfuehrer(), record.erstelltAm(),
                            record.ablaufAm(), record.scope().speicherwert()));
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Aktive Timeouts konnten nicht gelesen werden.", e);
        }
        return eintraege;
    }

    private List<Protokoll.FreezeEintrag> aktiveFreezes() {
        List<Protokoll.FreezeEintrag> eintraege = new ArrayList<>();
        de.lightrixx.punish.storage.FreezeRepository repo = freezeRepository;
        if (repo == null) {
            return eintraege;
        }
        long jetzt = System.currentTimeMillis();
        try {
            for (FreezeRecord record : repo.alle()) {
                if (record != null && record.istAktiv(jetzt)) {
                    eintraege.add(new Protokoll.FreezeEintrag(record.spielerUuid(), record.spielerName(),
                            record.grund(), record.ausfuehrer(), record.erstelltAm(),
                            record.ablaufAm(), record.scope().speicherwert()));
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Aktive Freezes konnten nicht gelesen werden.", e);
        }
        return eintraege;
    }

    public void sendeBanSetzen(Protokoll.BanEintrag eintrag) {
        if (eintrag == null) {
            return;
        }
        try {
            byte[] paket = Protokoll.nachricht(Protokoll.BAN_SETZEN, out -> Protokoll.schreibeBan(out, eintrag));
            for (RegisteredServer server : proxy.getAllServers()) {
                try {
                    sendeAn(server, paket);
                } catch (Exception e) {
                    logger.debug("Ban-Push an {} fehlgeschlagen.", server.getServerInfo().getName(), e);
                }
            }
        } catch (Exception e) {
            logger.warn("Ban-Push konnte nicht kodiert werden.", e);
        }
    }

    public void sendeBanEntfernen(java.util.UUID uuid) {
        if (uuid == null) {
            return;
        }
        try {
            byte[] paket = Protokoll.nachricht(Protokoll.BAN_ENTFERNEN, out -> {
                out.writeInt(1);
                Protokoll.schreibeUuid(out, uuid);
            });
            for (RegisteredServer server : proxy.getAllServers()) {
                try {
                    sendeAn(server, paket);
                } catch (Exception e) {
                    logger.debug("Ban-Remove an {} fehlgeschlagen.", server.getServerInfo().getName(), e);
                }
            }
        } catch (Exception e) {
            logger.warn("Ban-Remove konnte nicht kodiert werden.", e);
        }
    }

    public void sendeTimeoutSetzen(Protokoll.TimeoutEintrag eintrag) {
        if (eintrag == null) {
            return;
        }
        try {
            byte[] paket = Protokoll.nachricht(Protokoll.TIMEOUT_SETZEN, out -> Protokoll.schreibeTimeout(out, eintrag));
            for (RegisteredServer server : proxy.getAllServers()) {
                try {
                    sendeAn(server, paket);
                } catch (Exception e) {
                    logger.debug("Timeout-Push an {} fehlgeschlagen.", server.getServerInfo().getName(), e);
                }
            }
        } catch (Exception e) {
            logger.warn("Timeout-Push konnte nicht kodiert werden.", e);
        }
    }

    public void sendeTimeoutEntfernen(java.util.UUID uuid) {
        if (uuid == null) {
            return;
        }
        try {
            byte[] paket = Protokoll.nachricht(Protokoll.TIMEOUT_ENTFERNEN, out -> {
                out.writeInt(1);
                Protokoll.schreibeUuid(out, uuid);
            });
            for (RegisteredServer server : proxy.getAllServers()) {
                try {
                    sendeAn(server, paket);
                } catch (Exception e) {
                    logger.debug("Timeout-Remove an {} fehlgeschlagen.", server.getServerInfo().getName(), e);
                }
            }
        } catch (Exception e) {
            logger.warn("Timeout-Remove konnte nicht kodiert werden.", e);
        }
    }

    public void sendeFreezeSetzen(Protokoll.FreezeEintrag eintrag) {
        if (eintrag == null) {
            return;
        }
        try {
            byte[] paket = Protokoll.nachricht(Protokoll.FREEZE_SETZEN, out -> Protokoll.schreibeFreeze(out, eintrag));
            for (RegisteredServer server : proxy.getAllServers()) {
                try {
                    sendeAn(server, paket);
                } catch (Exception e) {
                    logger.debug("Freeze-Push an {} fehlgeschlagen.", server.getServerInfo().getName(), e);
                }
            }
        } catch (Exception e) {
            logger.warn("Freeze-Push konnte nicht kodiert werden.", e);
        }
    }

    public void sendeFreezeEntfernen(java.util.UUID uuid) {
        if (uuid == null) {
            return;
        }
        try {
            byte[] paket = Protokoll.nachricht(Protokoll.FREEZE_ENTFERNEN, out -> {
                out.writeInt(1);
                Protokoll.schreibeUuid(out, uuid);
            });
            for (RegisteredServer server : proxy.getAllServers()) {
                try {
                    sendeAn(server, paket);
                } catch (Exception e) {
                    logger.debug("Freeze-Remove an {} fehlgeschlagen.", server.getServerInfo().getName(), e);
                }
            }
        } catch (Exception e) {
            logger.warn("Freeze-Remove konnte nicht kodiert werden.", e);
        }
    }

    private List<Protokoll.MuteEintrag> aktiveMutes() {
        long jetzt = System.currentTimeMillis();
        List<Protokoll.MuteEintrag> eintraege = new ArrayList<>();
        try {
            for (MuteRecord record : muteRepository.alle()) {
                if (record != null && record.istAktiv(jetzt)) {
                    eintraege.add(new Protokoll.MuteEintrag(record.spielerUuid(), record.spielerName(),
                            record.grund(), record.ausfuehrer(), record.erstelltAm(),
                            record.ablaufAm(), record.scope().speicherwert()));
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Aktive Mutes konnten nicht gelesen werden.", e);
        }
        return eintraege;
    }

    private List<Protokoll.IpMuteEintrag> aktiveIpMutes() {
        long jetzt = System.currentTimeMillis();
        List<Protokoll.IpMuteEintrag> eintraege = new ArrayList<>();
        if (ipMuteRepository == null) {
            return eintraege;
        }
        try {
            for (IpMuteRecord record : ipMuteRepository.alle()) {
                if (record != null && record.istAktiv(jetzt)) {
                    eintraege.add(new Protokoll.IpMuteEintrag(record.ip(),
                            record.grund(), record.ausfuehrer(), record.erstelltAm(),
                            record.ablaufAm(), record.scope().speicherwert()));
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Aktive IP-Mutes konnten nicht gelesen werden.", e);
        }
        return eintraege;
    }

    public void beantworteHello(RegisteredServer server, String serverName, int version,
                                String bridgePluginVersion) {
        beantworteHello(server, serverName, version, bridgePluginVersion, "");
    }

    public void beantworteHello(RegisteredServer server, String serverName, int version,
                                String bridgePluginVersion, String variante) {
        boolean inkompatibel = version < Protokoll.MIN_VERSION || version > Protokoll.VERSION;
        String sauber = serverName == null ? "unbekannt" : serverName.replace('\r', ' ').replace('\n', ' ').replace('\u001B', ' ').trim();
        if (sauber.isBlank()) {
            sauber = "unbekannt";
        }
        if (sauber.length() > 32) {
            sauber = sauber.substring(0, 32);
        }
        if (inkompatibel) {
            logger.error("Bridge auf '{}' meldet Protokoll v{} (unterstützt v{}-v{}). Update erforderlich, Sync ABGELEHNT.",
                    server == null ? "?" : server.getServerInfo().getName(),
                    version, Protokoll.MIN_VERSION, Protokoll.VERSION);
            return;
        }
        String kanonisch = "?";
        try {
            kanonisch = server == null ? sauber : server.getServerInfo().getName();
        } catch (Exception ignored) {
            kanonisch = sauber;
        }
        if (!"unbekannt".equalsIgnoreCase(sauber) && !sauber.equalsIgnoreCase(kanonisch)) {
            logger.error("Bridge-HELLO von '{}' nennt sich '{}' – Servername passt nicht zur "
                    + "Velocity-Verbindung. Sync ABGELEHNT (netzwerk.server-name prüfen).",
                    kanonisch, sauber);
            return;
        }
        String kanalName = schluesselFuer(server);
        protokollVersionen.put(kanalName, version);
        helloBekannt.add(kanalName);
        merkeBridgeVersion(kanonisch, bridgePluginVersion);
        merkeBridgeVariante(kanonisch, variante);
        UpdateVerteilung updates = updateVerteilung;
        if (updates != null) {
            try {
                updates.standEmpfangen(server, kanonisch, variante);
            } catch (Exception e) {
                logger.debug("Update-Stand nach HELLO fehlgeschlagen.", e);
            }
        }
        if (server != null) {
            try {
                sendeAn(server, Protokoll.helloAck(Protokoll.VERSION, kanonisch, pluginVersion));
            } catch (Exception e) {
                logger.debug("HELLO-ACK konnte nicht gesendet werden.", e);
            }
        }
        if (vollSyncFaellig(kanonisch, System.currentTimeMillis())) {
            logger.info("Bridge-HELLO von '{}' (kanonisch '{}', Protokoll v{}).", sauber, kanonisch, version);
            // Voll-Sync vom Netty-Thread verlagern: Bei großen Straflisten kostet der
            // Versand Serialisierung + HMAC je Paket. Das sofortige Reservieren verhindert
            // Doppel-Tasks bei parallelen HELLOs; bei Fehlschlag wird die Reservierung
            // gelöst, damit der nächste HELLO es erneut versucht.
            merkeVollSync(kanonisch, System.currentTimeMillis());
            if (!planeVollSync(server, kanonisch) && !mutePushAn(server)) {
                letzterVollSync.remove(syncSchluessel(kanonisch));
                logger.warn("Voll-Sync für '{}' unvollständig – nächster HELLO versucht es erneut.", kanonisch);
            }
        } else {
            logger.debug("Bridge-HELLO-Refresh von '{}' (Voll-Sync aktuell).", kanonisch);
        }
    }

    private boolean planeVollSync(RegisteredServer server, String kanonisch) {
        try {
            proxy.getScheduler().buildTask(plugin, () -> {
                try {
                    if (!mutePushAn(server)) {
                        letzterVollSync.remove(syncSchluessel(kanonisch));
                        logger.warn("Voll-Sync für '{}' unvollständig – nächster HELLO versucht es erneut.", kanonisch);
                    }
                } catch (Exception e) {
                    logger.debug("Voll-Sync nach HELLO fehlgeschlagen.", e);
                }
            }).schedule();
            return true;
        } catch (Exception e) {
            logger.debug("Voll-Sync konnte nicht geplant werden, läuft synchron.", e);
            return false;
        }
    }

    boolean vollSyncFaellig(String kanonisch, long jetzt) {
        Long letzter = letzterVollSync.get(syncSchluessel(kanonisch));
        return letzter == null || jetzt - letzter > VOLL_SYNC_INTERVALL_MILLIS;
    }

    void merkeVollSync(String kanonisch, long jetzt) {
        letzterVollSync.put(syncSchluessel(kanonisch), jetzt);
    }

    private static String syncSchluessel(String kanonisch) {
        return kanonisch == null || kanonisch.isBlank() ? "unbekannt" : kanonisch.trim();
    }

    private void merkeBridgeVersion(String kanonisch, String bridgePluginVersion) {
        String bridge = bridgePluginVersion == null ? "" : bridgePluginVersion.trim();
        String server = kanonisch == null || kanonisch.isBlank() ? "unbekannt" : kanonisch.trim();
        if (!bridge.isEmpty() && !"unbekannt".equalsIgnoreCase(bridge)) {
            bridgeVersionen.put(server, bridge);
        }
        String eigen = pluginVersion == null ? "" : pluginVersion.trim();
        if (eigen.isEmpty() || "unbekannt".equalsIgnoreCase(eigen)
                || bridge.isEmpty() || "unbekannt".equalsIgnoreCase(bridge)) {
            return;
        }
        String schluessel = server + "|" + bridge;
        if (eigen.equals(bridge)) {
            if (gemeldeteVersionen.remove(schluessel)) {
                logger.info("Plugin-Version auf '{}' wieder einheitlich ({}).", server, eigen);
            }
            return;
        }
        if (!gemeldeteVersionen.add(schluessel)) {
            return;
        }
        logger.warn("Plugin-Version weicht ab: Proxy={}, Bridge '{}'={} – bitte alle Module auf dieselbe Version bringen (Sync läuft weiter).",
                eigen, server, bridge);
        var broadcast = broadcastService;
        if (broadcast != null) {
            try {
                broadcast.senden(de.lightrixx.punish.core.MessageKeys.BROADCAST_VERSION_MISMATCH,
                        java.util.Map.of("proxy", eigen, "server", server, "bridge", bridge));
            } catch (Exception e) {
                logger.debug("Versions-Broadcast fehlgeschlagen.", e);
            }
        }
    }

    public void sendeGuiAntwort(RegisteredServer server, byte[] paket) {
        sendeAn(server, paket);
    }

    public void sendePlatzhalterAntwort(RegisteredServer server, byte[] paket) {
        sendeAn(server, paket);
    }

    private static final java.util.Map<String, String> ERLAUBTE_BRIDGE_BEFEHLE = java.util.Map.ofEntries(
            java.util.Map.entry("ban", "punish.ban"),
            java.util.Map.entry("permabann", "punish.permabann"),
            java.util.Map.entry("ban-ip", "punish.banip"),
            java.util.Map.entry("unban-ip", "punish.unbanip"),
            java.util.Map.entry("ban-all", "punish.banall"),
            java.util.Map.entry("mute", "punish.mute"),
            java.util.Map.entry("mute-all", "punish.muteall"),
            java.util.Map.entry("unmute", "punish.unmute"),
            java.util.Map.entry("unmute-all", "punish.unmuteall"),
            java.util.Map.entry("mute-ip", "punish.muteip"),
            java.util.Map.entry("unmute-ip", "punish.unmuteip"),
            java.util.Map.entry("timeout", "punish.timeout"),
            java.util.Map.entry("timeout-all", "punish.timeoutall"),
            java.util.Map.entry("untimeout", "punish.untimeout"),
            java.util.Map.entry("untimeout-all", "punish.untimeoutall"),
            java.util.Map.entry("freeze", "punish.freeze"),
            java.util.Map.entry("unfreeze", "punish.unfreeze"),
            java.util.Map.entry("timeout-ip", "punish.timeoutip"),
            java.util.Map.entry("untimeout-ip", "punish.untimeoutip"),
            java.util.Map.entry("kick", "punish.kick"),
            java.util.Map.entry("kick-ip", "punish.kickip"),
            java.util.Map.entry("kick-all", "punish.kickall"),
            java.util.Map.entry("pardon", "punish.pardon"),
            java.util.Map.entry("unban", "punish.pardon"),
            java.util.Map.entry("pardon-all", "punish.pardonall"),
            java.util.Map.entry("unban-all", "punish.pardonall"),
            java.util.Map.entry("warn", "punish.warn"),
            java.util.Map.entry("punish", "punish.templates"),
            java.util.Map.entry("punish-undo", "punish.undo"),
            java.util.Map.entry("punish-stats", "punish.stats"),
            java.util.Map.entry("punish-export", "punish.export"),
            java.util.Map.entry("verstoss", "punish.verstoss"),
            java.util.Map.entry("verstoß", "punish.verstoss"),
            java.util.Map.entry("punish-note", "punish.note"),
            java.util.Map.entry("report", "punish.report"),
            java.util.Map.entry("reports", "punish.reports"),
            java.util.Map.entry("punish-status", "punish.status"),
            java.util.Map.entry("pscheck", "punish.status"),
            java.util.Map.entry("punish-history", "punish.history"),
            java.util.Map.entry("punish-edit", "punish.edit"),
            java.util.Map.entry("alts", "punish.alts"),
            java.util.Map.entry("punish-delete-ip", "punish.deleteip"),
            java.util.Map.entry("punish-version", "punish.version"),
            java.util.Map.entry("psversion", "punish.version"),
            java.util.Map.entry("punish-help", "punish.help"),
            java.util.Map.entry("pshelp", "punish.help"),
            java.util.Map.entry("chatfilter", "punish.chatfilter"));

    static String bridgeErlaubnisFuer(String befehlOhneSlash) {
        if (befehlOhneSlash == null || befehlOhneSlash.isBlank()) {
            return null;
        }
        String roh = befehlOhneSlash.trim();
        while (roh.startsWith("/")) {
            roh = roh.substring(1).trim();
        }
        if (roh.isEmpty()) {
            return null;
        }
        String basis = roh.split("\\s+")[0].toLowerCase(java.util.Locale.ROOT);
        int doppelpunkt = basis.lastIndexOf(':');
        if (doppelpunkt >= 0) {
            basis = basis.substring(doppelpunkt + 1);
        }
        return ERLAUBTE_BRIDGE_BEFEHLE.get(basis);
    }

    static String tunnelBasisFuer(String befehlOhneSlash) {
        if (befehlOhneSlash == null || befehlOhneSlash.isBlank()) {
            return "";
        }
        String roh = befehlOhneSlash.trim();
        while (roh.startsWith("/")) {
            roh = roh.substring(1).trim();
        }
        if (roh.isEmpty()) {
            return "";
        }
        String basis = roh.split("\\s+")[0].toLowerCase(java.util.Locale.ROOT);
        int doppelpunkt = basis.lastIndexOf(':');
        if (doppelpunkt >= 0) {
            basis = basis.substring(doppelpunkt + 1);
        }
        return basis;
    }

    static java.util.List<String> tunnelAlternativRechte(String basis) {
        if (basis == null || basis.isBlank()) {
            return List.of();
        }
        return switch (basis.toLowerCase(java.util.Locale.ROOT)) {
            case "verstoss", "verstoß" -> List.of("punish.verstoss",
                    "punish.verstoss.add", "punish.verstoss.remove", "punish.verstoss.clear");
            case "punish-edit", "psedit" -> List.of("punish.edit",
                    "punish.verstoss", "punish.verstoss.edit", "punish.warn", "punish.note",
                    "punish.ban", "punish.mute", "punish.timeout",
                    "punish.banip", "punish.muteip", "punish.timeoutip");
            case "punish-status", "psstatus" -> List.of("punish.status",
                    "punish.own", "punish.status.server");
            case "pscheck" -> List.of("punish.status", "punish.own", "punish.status.server");
            case "punish-history", "pshistory" -> List.of("punish.history",
                    "punish.own", "punish.history.server");
            default -> List.of();
        };
    }

    static boolean tunnelErlaubt(Player ausfuehrer, String basis, String benoetigt) {
        if (ausfuehrer == null || benoetigt == null || benoetigt.isBlank()) {
            return false;
        }
        try {
            if (RangBuendel.gewaehrt(ausfuehrer::hasPermission, benoetigt)) {
                return true;
            }
        } catch (Exception e) {
            return false;
        }
        for (String alternative : tunnelAlternativRechte(basis)) {
            try {
                if (RangBuendel.gewaehrt(ausfuehrer::hasPermission, alternative)) {
                    return true;
                }
            } catch (Exception e) {
                return false;
            }
        }
        return false;
    }

    public void fuehreAus(UUID alsUuid, String zeile) {
        fuehreAus(alsUuid, zeile, null);
    }

    public void fuehreAus(UUID alsUuid, String zeile, RegisteredServer quelle) {
        String befehl = zeile == null ? "" : zeile.trim();
        while (befehl.startsWith("/")) {
            befehl = befehl.substring(1).trim();
        }
        if (befehl.isEmpty()) {
            logger.warn("Bridge-Aktion abgewiesen (leere Zeile).");
            benachrichtigeBeiAbweisung(alsUuid);
            return;
        }
        if (befehl.length() > 500) {
            logger.warn("Bridge-Aktion abgewiesen (zu lang, {} Zeichen).", befehl.length());
            benachrichtigeBeiAbweisung(alsUuid);
            return;
        }
        String benoetigt = bridgeErlaubnisFuer(befehl);
        if (benoetigt == null) {
            logger.warn("Bridge-Aktion abgewiesen (nicht auf Allowlist): /{}",
                    sanierenFuerLog(befehl.length() > 80 ? befehl.substring(0, 80) + "…" : befehl));
            benachrichtigeBeiAbweisung(alsUuid);
            return;
        }
        final String ausfuehren = befehl;
        try {
            Optional<Player> spieler = alsUuid == null ? Optional.empty() : proxy.getPlayer(alsUuid);
            if (spieler.isPresent()) {
                Player ausfuehrer = spieler.get();
                if (quelle != null) {
                    try {
                        var aktuelleVerbindung = ausfuehrer.getCurrentServer();
                        if (aktuelleVerbindung.isEmpty()) {
                            logger.warn("Bridge-Aktion abgewiesen (kein aktiver Server bekannt, Quelle {}).",
                                    sanierenFuerLog(quelle.getServerInfo().getName()));
                            benachrichtigeBeiAbweisung(alsUuid);
                            return;
                        }
                        RegisteredServer aktuellerServer = aktuelleVerbindung.get().getServer();
                        String erwartet = quelle.getServerInfo().getName();
                        String aktuell = aktuellerServer.getServerInfo().getName();
                        if (!erwartet.equalsIgnoreCase(aktuell)) {
                            logger.warn("Bridge-Aktion abgewiesen (Server-Bindung: {} ist auf {}, Quelle {}).",
                                    sanierenFuerLog(ausfuehrer.getUsername()), sanierenFuerLog(aktuell),
                                    sanierenFuerLog(erwartet));
                            benachrichtigeBeiAbweisung(alsUuid);
                            return;
                        }
                    } catch (Exception e) {
                        logger.warn("Bridge Server-Bindungscheck fehlgeschlagen, Aktion abgewiesen: /{}",
                                sanierenFuerLog(ausfuehren), e);
                        benachrichtigeBeiAbweisung(alsUuid);
                        return;
                    }
                }
                boolean erlaubt;
                try {
                    erlaubt = tunnelErlaubt(ausfuehrer, tunnelBasisFuer(befehl), benoetigt);
                } catch (Exception e) {
                    logger.warn("Bridge-Permission-Re-Check fehlgeschlagen, Aktion abgewiesen: /{}",
                            sanierenFuerLog(ausfuehren), e);
                    return;
                }
                if (!erlaubt) {
                    logger.warn("Bridge-Aktion abgewiesen ({} fehlt {} für /{}).",
                            sanierenFuerLog(ausfuehrer.getUsername()), benoetigt, sanierenFuerLog(ausfuehren));
                    sendeKeinRecht(ausfuehrer);
                    return;
                }
                try {
                    // Direkt auf dem Proxy ausfuehren. NIEMALS spoofChatInput():
                    // Spoofed Input durchlaeuft die Chat-Pipeline und wird - wenn der
                    // Proxy-Befehl z. B. wegen fehlender Rechte nicht greift - ans
                    // Backend zurueckgereicht. Das Backend leitet erneut an den Proxy
                    // weiter -> Endlosschleife bis disconnect.exceeded_packet_rate.
                    proxy.getCommandManager().executeAsync(ausfuehrer, ausfuehren);
                    logger.info("Bridge-Aktion als {} ausgeführt: /{}", sanierenFuerLog(ausfuehrer.getUsername()),
                            sanierenFuerLog(ausfuehren));
                } catch (Exception e) {
                    logger.warn("Bridge-Aktion konnte nicht als Spieler ausgeführt werden: /{}",
                            sanierenFuerLog(ausfuehren), e);
                }
                return;
            }
            logger.warn("Bridge-Aktion als Konsole abgewiesen (kein Konsolen-Fallback für Bridge): /{}",
                    sanierenFuerLog(ausfuehren));
        } catch (Exception e) {
            logger.warn("Bridge-Aktion fehlgeschlagen: /{}", sanierenFuerLog(ausfuehren), e);
        }
    }

    private void benachrichtigeBeiAbweisung(UUID alsUuid) {
        if (alsUuid == null) {
            return;
        }
        try {
            proxy.getPlayer(alsUuid).ifPresent(this::sendeKeinRecht);
        } catch (Exception e) {
            logger.debug("Abweisungs-Hinweis konnte nicht zugestellt werden.", e);
        }
    }

    private void sendeKeinRecht(Player ausfuehrer) {
        MessageService dienst = nachrichten;
        if (dienst == null || ausfuehrer == null) {
            return;
        }
        try {
            dienst.senden(new VelocitySender(ausfuehrer), MessageKeys.KEIN_RECHT);
        } catch (Exception e) {
            logger.debug("Kein-Recht-Hinweis konnte nicht gesendet werden.", e);
        }
    }

    private static String sanierenFuerLog(String wert) {
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
