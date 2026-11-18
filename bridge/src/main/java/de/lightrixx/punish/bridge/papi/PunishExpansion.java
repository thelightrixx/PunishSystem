package de.lightrixx.punish.bridge.papi;

import de.lightrixx.punish.core.RangBuendel;
import de.lightrixx.punish.net.Protokoll;
import de.lightrixx.punish.util.ZeitFormat;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

public final class PunishExpansion extends PlaceholderExpansion {

    private final Plugin plugin;
    private final PlatzhalterCache cache;

    public PunishExpansion(Plugin plugin, PlatzhalterCache cache) {
        this.plugin = Objects.requireNonNull(plugin, "Das Plugin darf nicht null sein.");
        this.cache = Objects.requireNonNull(cache, "Der Cache darf nicht null sein.");
    }

    @Override
    public @NotNull String getIdentifier() {
        return "punish";
    }

    @Override
    public @NotNull String getAuthor() {
        return "lightrixx";
    }

    @Override
    public @NotNull String getVersion() {
        try {
            return plugin.getPluginMeta().getVersion();
        } catch (Exception e) {
            return "unbekannt";
        }
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer betrachter, @NotNull String params) {
        if (params == null || params.isBlank()) {
            return null;
        }
        String key = params.trim().toLowerCase(java.util.Locale.ROOT);
        String fremdName = null;
        int doppelpunkt = key.indexOf(':');
        if (doppelpunkt >= 0) {
            fremdName = key.substring(doppelpunkt + 1).trim();
            key = key.substring(0, doppelpunkt).trim();
            if (fremdName.isEmpty()) {
                fremdName = null;
            }
        }
        Player onlineBetrachter = betrachter != null ? betrachter.getPlayer() : null;
        if (fremdName != null) {
            if (onlineBetrachter == null
                    || !RangBuendel.gewaehrt(onlineBetrachter::hasPermission, "punish.status")) {
                return "";
            }
        }
        String zielName = fremdName != null ? fremdName
                : (betrachter == null ? "" : betrachter.getName());
        if (zielName == null || zielName.isBlank()) {
            return "";
        }
        Protokoll.PlatzhalterAntwort antwort = cache.aktuelle(onlineBetrachter, zielName);
        if (antwort == null || !antwort.erfolg()) {
            return standardFuer(key);
        }
        long jetzt = System.currentTimeMillis();
        boolean muted = antwort.muted() && antwort.muteAblauf() > jetzt;
        boolean banned = antwort.banned() && antwort.banAblauf() > jetzt;
        boolean timeouted = antwort.timeouted() && antwort.timeoutAblauf() > jetzt;
        boolean frozen = antwort.frozen() && antwort.freezeAblauf() > jetzt;
        return switch (key) {
            case "muted" -> muted ? "true" : "false";
            case "mute_rest" -> restText(muted, antwort.muteAblauf());
            case "mute_rest_seconds" -> restSekunden(muted, antwort.muteAblauf());
            case "mute_permanent" -> permanent(muted, antwort.muteAblauf());
            case "mute_reason" -> muted ? detailText(antwort.muteGrund()) : "-";
            case "mute_by" -> muted ? detailText(antwort.muteVon()) : "-";
            case "mute_since" -> zeitpunktText(muted, antwort.muteSeit());
            case "banned" -> banned ? "true" : "false";
            case "ban_rest" -> restText(banned, antwort.banAblauf());
            case "ban_rest_seconds" -> restSekunden(banned, antwort.banAblauf());
            case "ban_permanent" -> permanent(banned, antwort.banAblauf());
            case "ban_reason" -> banned ? detailText(antwort.banGrund()) : "-";
            case "ban_by" -> banned ? detailText(antwort.banVon()) : "-";
            case "ban_since" -> zeitpunktText(banned, antwort.banSeit());
            case "timeouted" -> timeouted ? "true" : "false";
            case "timeout_rest" -> restText(timeouted, antwort.timeoutAblauf());
            case "timeout_rest_seconds" -> restSekunden(timeouted, antwort.timeoutAblauf());
            case "timeout_permanent" -> permanent(timeouted, antwort.timeoutAblauf());
            case "timeout_reason" -> timeouted ? detailText(antwort.timeoutGrund()) : "-";
            case "timeout_by" -> timeouted ? detailText(antwort.timeoutVon()) : "-";
            case "timeout_since" -> zeitpunktText(timeouted, antwort.timeoutSeit());
            case "frozen" -> frozen ? "true" : "false";
            case "freeze_rest" -> restText(frozen, antwort.freezeAblauf());
            case "freeze_rest_seconds" -> restSekunden(frozen, antwort.freezeAblauf());
            case "freeze_permanent" -> permanent(frozen, antwort.freezeAblauf());
            case "freeze_reason" -> frozen ? detailText(antwort.freezeGrund()) : "-";
            case "freeze_by" -> frozen ? detailText(antwort.freezeVon()) : "-";
            case "freeze_since" -> zeitpunktText(frozen, antwort.freezeSeit());
            case "is_punished" -> muted || banned || timeouted || frozen ? "true" : "false";
            case "active_count" -> String.valueOf((muted ? 1 : 0) + (banned ? 1 : 0)
                    + (timeouted ? 1 : 0) + (frozen ? 1 : 0));
            case "status" -> status(muted, banned, timeouted, frozen);
            case "verstoss_anzahl" -> String.valueOf(antwort.verstossAnzahl());
            case "verstoss_punkte" -> String.valueOf(antwort.verstossPunkte());
            case "verstoss_stufe" -> detailText(antwort.verstossStufe());
            case "warns" -> String.valueOf(antwort.warns());
            case "warns_last_reason" -> detailText(antwort.warnsLetzterGrund());
            case "notes" -> String.valueOf(antwort.notizen());
            case "history_count" -> String.valueOf(antwort.historyAnzahl());
            case "reports" -> String.valueOf(antwort.reportsGegen());
            case "reports_offen" -> String.valueOf(antwort.reportsOffen());
            default -> null;
        };
    }

    private static String standardFuer(String key) {
        return switch (key) {
            case "muted", "banned", "timeouted", "frozen", "is_punished",
                    "mute_permanent", "ban_permanent", "timeout_permanent",
                    "freeze_permanent" -> "false";
            case "mute_rest", "ban_rest", "timeout_rest", "freeze_rest",
                    "mute_reason", "mute_by", "mute_since",
                    "ban_reason", "ban_by", "ban_since",
                    "timeout_reason", "timeout_by", "timeout_since",
                    "freeze_reason", "freeze_by", "freeze_since",
                    "warns_last_reason", "verstoss_stufe" -> "-";
            case "mute_rest_seconds", "ban_rest_seconds", "timeout_rest_seconds",
                    "freeze_rest_seconds" -> "-1";
            case "active_count", "verstoss_anzahl", "verstoss_punkte", "warns",
                    "notes", "history_count", "reports", "reports_offen" -> "0";
            case "status" -> "clean";
            default -> null;
        };
    }

    private static String restText(boolean aktiv, long ablauf) {
        if (!aktiv || ablauf == Protokoll.PlatzhalterAntwort.INAKTIV) {
            return "-";
        }
        if (ablauf == Long.MAX_VALUE) {
            return "Permanent";
        }
        try {
            return ZeitFormat.formatRestzeit(Math.max(0L, ablauf - System.currentTimeMillis()));
        } catch (Exception e) {
            return "-";
        }
    }

    private static String restSekunden(boolean aktiv, long ablauf) {
        if (!aktiv || ablauf == Protokoll.PlatzhalterAntwort.INAKTIV) {
            return "-1";
        }
        if (ablauf == Long.MAX_VALUE) {
            return "permanent";
        }
        return String.valueOf(Math.max(0L, (ablauf - System.currentTimeMillis()) / 1000L));
    }

    private static String permanent(boolean aktiv, long ablauf) {
        return aktiv && ablauf == Long.MAX_VALUE ? "true" : "false";
    }

    private static String detailText(String wert) {
        if (wert == null || wert.isBlank()) {
            return "-";
        }
        String sauber = wert.replaceAll("§.?", "").replaceAll("(?i)&[0-9a-fk-orx]", "");
        return sauber.isBlank() ? "-" : sauber;
    }

    private static String zeitpunktText(boolean aktiv, long seit) {
        if (!aktiv || seit <= 0) {
            return "-";
        }
        try {
            return ZeitFormat.formatZeitpunkt(seit);
        } catch (Exception e) {
            return "-";
        }
    }

    private static String status(boolean muted, boolean banned, boolean timeouted, boolean frozen) {
        if (frozen) {
            return "frozen";
        }
        if (banned) {
            return "banned";
        }
        if (timeouted) {
            return "timeouted";
        }
        if (muted) {
            return "muted";
        }
        return "clean";
    }
}
