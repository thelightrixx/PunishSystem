package de.lightrixx.punish.velocity;

import de.lightrixx.punish.storage.BanRepository;
import de.lightrixx.punish.storage.FreezeRepository;
import de.lightrixx.punish.storage.MuteRepository;
import de.lightrixx.punish.storage.TimeoutRepository;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Objects;
import java.util.UUID;

public final class LuckPermsKontextRechner {

    public static final String KEY_FROZEN = "punish-frozen";
    public static final String KEY_MUTED = "punish-muted";
    public static final String KEY_BANNED = "punish-banned";
    public static final String KEY_TIMEOUT = "punish-timeout";
    public static final String KEY_PUNISHED = "punish-punished";

    private final Logger logger;
    private final BanRepository banRepository;
    private final MuteRepository muteRepository;
    private final TimeoutRepository timeoutRepository;
    private volatile FreezeRepository freezeRepository;

    private final Object sperre = new Object();
    private volatile Object rechnerProxy;

    public LuckPermsKontextRechner(Logger logger, BanRepository banRepository,
                                   MuteRepository muteRepository,
                                   TimeoutRepository timeoutRepository) {
        this.logger = Objects.requireNonNull(logger, "Der Logger darf nicht null sein.");
        this.banRepository = Objects.requireNonNull(banRepository,
                "Das BanRepository darf nicht null sein.");
        this.muteRepository = Objects.requireNonNull(muteRepository,
                "Das MuteRepository darf nicht null sein.");
        this.timeoutRepository = Objects.requireNonNull(timeoutRepository,
                "Das TimeoutRepository darf nicht null sein.");
    }

    public void setFreezeRepository(FreezeRepository freezeRepository) {
        this.freezeRepository = freezeRepository;
    }

    public boolean isAvailable() {
        try {
            Class.forName("net.luckperms.api.LuckPermsProvider");
            return luckPermsApi() != null;
        } catch (Throwable t) {
            logger.debug("LuckPerms-Kontexte nicht verfügbar: {}", t.toString());
            return false;
        }
    }

    public boolean isRegistered() {
        return rechnerProxy != null;
    }

    public void register() {
        synchronized (sperre) {
            if (rechnerProxy != null) {
                return;
            }
            Object api;
            try {
                api = luckPermsApi();
            } catch (Throwable t) {
                logger.debug("LuckPerms-Kontexte nicht registriert (keine API).", t);
                return;
            }
            if (api == null) {
                return;
            }
            try {
                Object manager = api.getClass().getMethod("getContextManager").invoke(api);
                if (manager == null) {
                    return;
                }
                Class<?> rechnerTyp = Class.forName("net.luckperms.api.context.ContextCalculator");
                Object proxy = Proxy.newProxyInstance(api.getClass().getClassLoader(),
                        new Class<?>[]{rechnerTyp},
                        (proxyObj, methode, args) -> {
                            String name = methode.getName();
                            if ("calculate".equals(name)) {
                                if (args != null && args.length == 2) {
                                    berechne(args[0], args[1]);
                                }
                                return null;
                            }
                            if ("estimatePotentialContexts".equals(name)) {
                                return leereKontexte();
                            }
                            if ("toString".equals(name)) {
                                return "PunishKontextRechner";
                            }
                            if ("hashCode".equals(name)) {
                                return System.identityHashCode(proxyObj);
                            }
                            if ("equals".equals(name)) {
                                return args != null && args.length == 1 && proxyObj == args[0];
                            }
                            return null;
                        });
                findeMethode(manager, "registerCalculator", 1).invoke(manager, proxy);
                rechnerProxy = proxy;
                logger.info("LuckPerms-Kontexte registriert "
                        + "(punish-frozen/punish-muted/punish-banned/punish-timeout/punish-punished).");
            } catch (Throwable t) {
                logger.debug("LuckPerms-Kontexte konnten nicht registriert werden: {}", t.toString());
            }
        }
    }

    public void unregister() {
        synchronized (sperre) {
            Object proxy = rechnerProxy;
            rechnerProxy = null;
            if (proxy == null) {
                return;
            }
            try {
                Object api = luckPermsApi();
                if (api == null) {
                    return;
                }
                Object manager = api.getClass().getMethod("getContextManager").invoke(api);
                if (manager == null) {
                    return;
                }
                findeMethode(manager, "unregisterCalculator", 1).invoke(manager, proxy);
            } catch (Throwable t) {
                logger.debug("LuckPerms-Kontexte konnten nicht abgemeldet werden: {}", t.toString());
            }
        }
    }

    public void signalUpdate() {
        if (rechnerProxy == null) {
            return;
        }
        try {
            Object api = luckPermsApi();
            if (api == null) {
                return;
            }
            Object manager = api.getClass().getMethod("getContextManager").invoke(api);
            if (manager == null) {
                return;
            }
            findeMethode(manager, "signalContextUpdate", 0).invoke(manager);
        } catch (Throwable t) {
            logger.debug("LuckPerms-Kontext-Update fehlgeschlagen: {}", t.toString());
        }
    }

    private static final long KONTEXT_CACHE_MILLIS = 500L;
    private static final int KONTEXT_CACHE_MAX = 10_000;

    private record KontextSnapshot(long zeitpunkt, Boolean muted, Boolean banned,
                                   Boolean timeouted, Boolean frozen) {
    }

    private final java.util.concurrent.ConcurrentHashMap<UUID, KontextSnapshot> kontextCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    private void berechne(Object subjekt, Object kontextVerbraucher) {
        if (subjekt == null || kontextVerbraucher == null) {
            return;
        }
        UUID uuid;
        try {
            Object raw = subjekt.getClass().getMethod("getUniqueId").invoke(subjekt);
            if (!(raw instanceof UUID id)) {
                return;
            }
            uuid = id;
        } catch (Throwable t) {
            logger.debug("LuckPerms-Kontext: UUID konnte nicht gelesen werden: {}", t.toString());
            return;
        }
        long jetzt = System.currentTimeMillis();
        // LuckPerms fragt ContextCalculator bei jeder Perm-Prüfung ab – ohne Cache laufen pro
        // Aufruf 4 Repository-Abfragen. Snapshot mit kurzer TTL: max. 500 ms veraltete Kontexte.
        KontextSnapshot snapshot = kontextCache.get(uuid);
        if (snapshot == null || jetzt - snapshot.zeitpunkt() >= KONTEXT_CACHE_MILLIS) {
            Boolean muted = istStumm(uuid, jetzt);
            Boolean banned = istGebannt(uuid, jetzt);
            Boolean timeouted = hatTimeout(uuid, jetzt);
            Boolean frozen = istGefreezt(uuid, jetzt);
            snapshot = new KontextSnapshot(jetzt, muted, banned, timeouted, frozen);
            if (kontextCache.size() >= KONTEXT_CACHE_MAX) {
                kontextCache.clear();
            }
            kontextCache.put(uuid, snapshot);
        }
        setzeKontext(kontextVerbraucher, KEY_FROZEN, snapshot.frozen());
        setzeKontext(kontextVerbraucher, KEY_MUTED, snapshot.muted());
        setzeKontext(kontextVerbraucher, KEY_BANNED, snapshot.banned());
        setzeKontext(kontextVerbraucher, KEY_TIMEOUT, snapshot.timeouted());
        setzeKontext(kontextVerbraucher, KEY_PUNISHED, bestraft(snapshot));
    }

    private static Boolean bestraft(KontextSnapshot snapshot) {
        Boolean[] werte = {snapshot.muted(), snapshot.banned(), snapshot.timeouted(), snapshot.frozen()};
        for (Boolean wert : werte) {
            if (Boolean.TRUE.equals(wert)) {
                return Boolean.TRUE;
            }
        }
        for (Boolean wert : werte) {
            if (wert == null) {
                return null;
            }
        }
        return Boolean.FALSE;
    }

    private Boolean istStumm(UUID uuid, long jetzt) {
        try {
            for (var mute : muteRepository.alleFuer(uuid)) {
                if (mute != null && mute.istAktiv(jetzt)) {
                    return Boolean.TRUE;
                }
            }
            return Boolean.FALSE;
        } catch (Exception e) {
            logger.warn("LuckPerms-Kontext: Mutes nicht lesbar – Kontext bleibt ungesetzt.", e);
            return null;
        }
    }

    private Boolean istGebannt(UUID uuid, long jetzt) {
        try {
            for (var bann : banRepository.alleFuer(uuid)) {
                if (bann != null && bann.istAktiv(jetzt)) {
                    return Boolean.TRUE;
                }
            }
            return Boolean.FALSE;
        } catch (Exception e) {
            logger.warn("LuckPerms-Kontext: Bans nicht lesbar – Kontext bleibt ungesetzt.", e);
            return null;
        }
    }

    private Boolean hatTimeout(UUID uuid, long jetzt) {
        try {
            for (var timeout : timeoutRepository.alleFuer(uuid)) {
                if (timeout != null && timeout.istAktiv(jetzt)) {
                    return Boolean.TRUE;
                }
            }
            return Boolean.FALSE;
        } catch (Exception e) {
            logger.warn("LuckPerms-Kontext: Timeouts nicht lesbar – Kontext bleibt ungesetzt.", e);
            return null;
        }
    }

    private Boolean istGefreezt(UUID uuid, long jetzt) {
        try {
            FreezeRepository freezes = freezeRepository;
            if (freezes == null) {
                return Boolean.FALSE;
            }
            for (var freeze : freezes.alleFuer(uuid)) {
                if (freeze != null && freeze.istAktiv(jetzt)) {
                    return Boolean.TRUE;
                }
            }
            return Boolean.FALSE;
        } catch (Exception e) {
            logger.warn("LuckPerms-Kontext: Freezes nicht lesbar – Kontext bleibt ungesetzt.", e);
            return null;
        }
    }

    private void setzeKontext(Object kontextVerbraucher, String schluessel, Boolean wert) {
        if (wert == null) {
            return;
        }
        try {
            Method accept = kontextVerbraucher.getClass().getMethod("accept",
                    String.class, String.class);
            accept.invoke(kontextVerbraucher, schluessel, wert ? "true" : "false");
        } catch (Throwable t) {
            logger.debug("LuckPerms-Kontext '{}' konnte nicht gesetzt werden: {}",
                    schluessel, t.toString());
        }
    }

    private static Object luckPermsApi() {
        try {
            Class<?> provider = Class.forName("net.luckperms.api.LuckPermsProvider");
            return provider.getMethod("getApi").invoke(null);
        } catch (IllegalStateException e) {
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static Object leereKontexte() {
        try {
            Class<?> satz = Class.forName("net.luckperms.api.context.ImmutableContextSet");
            return satz.getMethod("empty").invoke(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static Method findeMethode(Object ziel, String name, int parameterAnzahl) throws Exception {
        for (Method methode : ziel.getClass().getMethods()) {
            if (methode.getName().equals(name)
                    && methode.getParameterCount() == parameterAnzahl) {
                return methode;
            }
        }
        throw new NoSuchMethodException(name + "/" + parameterAnzahl);
    }
}
