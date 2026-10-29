package de.lightrixx.punish.hook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

public final class LuckPermsZugang {

    private static final Logger LOG = LoggerFactory.getLogger(LuckPermsZugang.class);
    private static final long LOAD_TIMEOUT_MS = 800L;

    private final BooleanSupplier luckPermsAktiv;
    private final BooleanSupplier mainThread;

    public LuckPermsZugang(BooleanSupplier luckPermsAktiv, BooleanSupplier mainThread) {
        this.luckPermsAktiv = Objects.requireNonNull(luckPermsAktiv, "luckPermsAktiv darf nicht null sein.");
        this.mainThread = Objects.requireNonNull(mainThread, "mainThread darf nicht null sein.");
    }

    public enum OfflineStatus {
        GESCHUETZT,
        NICHT_GESCHUETZT,
        UNBEKANNT
    }

    public boolean isAvailable() {
        try {
            if (!luckPermsAktiv.getAsBoolean()) {
                return false;
            }
            Class.forName("net.luckperms.api.LuckPermsProvider");
            return true;
        } catch (Throwable t) {
            LOG.debug("LuckPerms-Zugang nicht verfügbar: {}", t.toString());
            return false;
        }
    }

    public boolean isUserCached(UUID uuid) {
        if (uuid == null || !isAvailable()) {
            return false;
        }
        try {
            Optional<Object> apiOpt = getApi();
            if (apiOpt.isEmpty()) {
                return false;
            }
            Object api = apiOpt.get();
            Object userManager = invoke(api, "getUserManager");
            if (userManager == null) {
                return false;
            }
            Object user = invoke(userManager, "getUser", new Class[]{UUID.class}, uuid);
            return user != null;
        } catch (Throwable t) {
            LOG.debug("Fehler bei LuckPerms-Cache-Check für {}: {}", uuid, t.toString());
            return false;
        }
    }

    public boolean hasPermissionCachedOnly(UUID uuid, String permission) {
        if (uuid == null || permission == null || permission.isBlank()) {
            return false;
        }
        if (!isAvailable()) {
            return false;
        }
        try {
            Optional<Object> apiOpt = getApi();
            if (apiOpt.isEmpty()) {
                return false;
            }
            Object api = apiOpt.get();
            Object userManager = invoke(api, "getUserManager");
            if (userManager == null) {
                return false;
            }
            Object user = invoke(userManager, "getUser", new Class[]{UUID.class}, uuid);
            if (user == null) {
                return false;
            }
            return checkCachedPermission(user, permission);
        } catch (Throwable t) {
            LOG.debug("Fehler bei LuckPerms-Cache-Permission-Abfrage für {}: {}", uuid, t.toString());
            return false;
        }
    }

    public OfflineStatus offlineStatusCachedOnly(UUID uuid, String permission) {
        if (uuid == null || !isAvailable()) {
            return OfflineStatus.UNBEKANNT;
        }
        try {
            Optional<Object> apiOpt = getApi();
            if (apiOpt.isEmpty()) {
                return OfflineStatus.UNBEKANNT;
            }
            Object api = apiOpt.get();
            Object userManager = invoke(api, "getUserManager");
            if (userManager == null) {
                return OfflineStatus.UNBEKANNT;
            }
            Object user = invoke(userManager, "getUser", new Class[]{UUID.class}, uuid);
            if (user == null) {
                return OfflineStatus.UNBEKANNT;
            }
            return checkCachedPermission(user, permission)
                    ? OfflineStatus.GESCHUETZT
                    : OfflineStatus.NICHT_GESCHUETZT;
        } catch (Throwable t) {
            LOG.debug("Fehler bei LuckPerms-Cache-Status für {}: {}", uuid, t.toString());
            return OfflineStatus.UNBEKANNT;
        }
    }

    public OfflineStatus offlineStatusBlocking(UUID uuid, String permission) {
        if (uuid == null || !isAvailable()) {
            return OfflineStatus.UNBEKANNT;
        }
        if (mainThread.getAsBoolean()) {
            return offlineStatusCachedOnly(uuid, permission);
        }
        try {
            Optional<Object> apiOpt = getApi();
            if (apiOpt.isEmpty()) {
                return OfflineStatus.UNBEKANNT;
            }
            Object api = apiOpt.get();
            Object userManager = invoke(api, "getUserManager");
            if (userManager == null) {
                return OfflineStatus.UNBEKANNT;
            }
            Object user = invoke(userManager, "getUser", new Class[]{UUID.class}, uuid);
            if (user == null) {
                Optional<Object> geladen = loadUserBlocking(userManager, uuid);
                if (geladen.isEmpty()) {
                    return OfflineStatus.UNBEKANNT;
                }
                user = geladen.get();
            }
            return checkCachedPermission(user, permission)
                    ? OfflineStatus.GESCHUETZT
                    : OfflineStatus.NICHT_GESCHUETZT;
        } catch (Throwable t) {
            LOG.debug("Fehler bei LuckPerms-Statusabfrage für {}: {}", uuid, t.toString());
            return OfflineStatus.UNBEKANNT;
        }
    }

    /**
     * Prüft mehrere Permission-Nodes gegen denselben User und lädt diesen dabei
     * höchstens einmal blocking. Ergebnis je Node in Reihenfolge der Eingabe;
     * UNBEKANNT, wenn der User nicht gelesen werden konnte.
     */
    public java.util.List<OfflineStatus> offlineStatusFuerMehrere(UUID uuid, java.util.List<String> permissions) {
        int anzahl = permissions == null ? 0 : permissions.size();
        if (uuid == null || anzahl == 0 || !isAvailable()) {
            return java.util.Collections.nCopies(anzahl, OfflineStatus.UNBEKANNT);
        }
        try {
            Optional<Object> apiOpt = getApi();
            if (apiOpt.isEmpty()) {
                return java.util.Collections.nCopies(anzahl, OfflineStatus.UNBEKANNT);
            }
            Object api = apiOpt.get();
            Object userManager = invoke(api, "getUserManager");
            if (userManager == null) {
                return java.util.Collections.nCopies(anzahl, OfflineStatus.UNBEKANNT);
            }
            Object user = invoke(userManager, "getUser", new Class[]{UUID.class}, uuid);
            if (user == null) {
                if (mainThread.getAsBoolean()) {
                    return java.util.Collections.nCopies(anzahl, OfflineStatus.UNBEKANNT);
                }
                Optional<Object> geladen = loadUserBlocking(userManager, uuid);
                if (geladen.isEmpty()) {
                    return java.util.Collections.nCopies(anzahl, OfflineStatus.UNBEKANNT);
                }
                user = geladen.get();
            }
            java.util.List<OfflineStatus> ergebnis = new java.util.ArrayList<>(anzahl);
            for (String permission : permissions) {
                ergebnis.add(checkCachedPermission(user, permission)
                        ? OfflineStatus.GESCHUETZT
                        : OfflineStatus.NICHT_GESCHUETZT);
            }
            return ergebnis;
        } catch (Throwable t) {
            LOG.debug("Fehler bei LuckPerms-Batch-Statusabfrage für {}: {}", uuid, t.toString());
            return java.util.Collections.nCopies(anzahl, OfflineStatus.UNBEKANNT);
        }
    }

    public java.util.OptionalInt rangGewicht(UUID uuid) {
        if (uuid == null || !isAvailable()) {
            return java.util.OptionalInt.empty();
        }
        try {
            java.util.Optional<Object> apiOpt = getApi();
            if (apiOpt.isEmpty()) {
                return java.util.OptionalInt.empty();
            }
            Object api = apiOpt.get();
            Object userManager = invoke(api, "getUserManager");
            if (userManager == null) {
                return java.util.OptionalInt.empty();
            }
            Object user = invoke(userManager, "getUser", new Class[]{UUID.class}, uuid);
            if (user == null) {
                if (mainThread.getAsBoolean()) {
                    return java.util.OptionalInt.empty();
                }
                java.util.Optional<Object> geladen = loadUserBlocking(userManager, uuid);
                if (geladen.isEmpty()) {
                    return java.util.OptionalInt.empty();
                }
                user = geladen.get();
            }
            String gruppe = primaireGruppe(user);
            if (gruppe == null || gruppe.isBlank()) {
                return java.util.OptionalInt.empty();
            }
            Integer gewicht = gruppenGewicht(api, gruppe.trim());
            if (gewicht == null) {
                return java.util.OptionalInt.empty();
            }
            return java.util.OptionalInt.of(Math.max(0, gewicht));
        } catch (Throwable t) {
            LOG.debug("Fehler bei LuckPerms-Ranggewicht für {}: {}", uuid, t.toString());
            return java.util.OptionalInt.empty();
        }
    }

    private static String primaireGruppe(Object user) {
        try {
            Object prim = invoke(user, "getPrimaryGroup");
            return prim == null ? null : String.valueOf(prim);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Integer gruppenGewicht(Object api, String gruppe) {
        try {
            Object groupManager = invoke(api, "getGroupManager");
            if (groupManager == null) {
                return null;
            }
            Object group = invoke(groupManager, "getGroup", new Class[]{String.class}, gruppe);
            if (group == null) {
                return null;
            }
            Object gewicht = invoke(group, "getWeight");
            if (gewicht instanceof Optional<?> opt) {
                Object wert = opt.orElse(null);
                return wert instanceof Number n ? n.intValue() : null;
            }
            return gewicht instanceof Number n ? n.intValue() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    public boolean hasPermission(UUID uuid, String permission) {        if (uuid == null || permission == null || permission.isBlank()) {
            return false;
        }
        if (!isAvailable()) {
            return false;
        }
        try {
            Optional<Object> apiOpt = getApi();
            if (apiOpt.isEmpty()) {
                return false;
            }
            Object api = apiOpt.get();
            Object userManager = invoke(api, "getUserManager");
            if (userManager == null) {
                return false;
            }
            Object user = invoke(userManager, "getUser", new Class[]{UUID.class}, uuid);
            if (user == null) {
                if (mainThread.getAsBoolean()) {
                    LOG.debug("LuckPerms User {} nicht gecacht (Main-Thread, kein Blocking-Load).", uuid);
                    return false;
                }
                Optional<Object> geladen = loadUserBlocking(userManager, uuid);
                if (geladen.isEmpty()) {
                    LOG.debug("LuckPerms User {} nicht ladbar.", uuid);
                    return false;
                }
                user = geladen.get();
            }
            return checkCachedPermission(user, permission);
        } catch (Throwable t) {
            LOG.debug("Fehler bei LuckPerms-Permission-Abfrage für {}: {}", uuid, t.toString());
            return false;
        }
    }

    private static Optional<Object> getApi() {
        try {
            Class<?> providerClass = Class.forName("net.luckperms.api.LuckPermsProvider");
            Method getApiMethod = providerClass.getMethod("getApi");
            return Optional.ofNullable(getApiMethod.invoke(null));
        } catch (IllegalStateException e) {
            LOG.debug("LuckPerms API noch nicht geladen.", e);
            return Optional.empty();
        } catch (Exception e) {
            LOG.debug("LuckPerms API-Zugriff fehlgeschlagen.", e);
            return Optional.empty();
        }
    }

    private static boolean checkCachedPermission(Object user, String permission) throws Exception {
        Object cachedData = invoke(user, "getCachedData");
        if (cachedData == null) {
            return false;
        }
        Object permissionData = invoke(cachedData, "getPermissionData");
        if (permissionData == null) {
            return false;
        }
        Object result = null;
        try {
            result = invoke(permissionData, "checkPermission", new Class[]{String.class}, permission);
        } catch (NoSuchMethodException e) {
            try {
                result = invoke(permissionData, "permissionValue", new Class[]{String.class}, permission);
            } catch (NoSuchMethodException ex) {
                LOG.debug("Keine passende Permission-Abfrage in LuckPerms API gefunden.", ex);
                return false;
            }
        }
        if (result == null) {
            return false;
        }
        Method asBoolean = result.getClass().getMethod("asBoolean");
        Object bool = asBoolean.invoke(result);
        return Boolean.TRUE.equals(bool);
    }

    private static Optional<Object> loadUserBlocking(Object userManager, UUID uuid) {
        try {
            Method loadUserMethod = userManager.getClass().getMethod("loadUser", UUID.class);
            Object futureObj = loadUserMethod.invoke(userManager, uuid);
            if (!(futureObj instanceof CompletableFuture)) {
                return Optional.empty();
            }
            @SuppressWarnings("unchecked")
            CompletableFuture<Object> future = (CompletableFuture<Object>) futureObj;
            return Optional.ofNullable(future.get(LOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS));
        } catch (java.util.concurrent.TimeoutException e) {
            LOG.debug("LuckPerms loadUser Timeout für {} nach {}ms", uuid, LOAD_TIMEOUT_MS);
            return Optional.empty();
        } catch (Throwable t) {
            LOG.debug("Fehler beim Laden des LuckPerms Users für {}: {}", uuid, t.toString());
            return Optional.empty();
        }
    }

    private static Object invoke(Object target, String methodName) throws Exception {
        Method m = target.getClass().getMethod(methodName);
        m.setAccessible(true);
        return m.invoke(target);
    }

    private static Object invoke(Object target, String methodName, Class<?>[] paramTypes, Object... args) throws Exception {
        Method m = target.getClass().getMethod(methodName, paramTypes);
        m.setAccessible(true);
        return m.invoke(target, args);
    }
}
