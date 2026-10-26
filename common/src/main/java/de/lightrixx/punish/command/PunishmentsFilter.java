package de.lightrixx.punish.command;

import de.lightrixx.punish.model.HistoryType;

import java.util.Locale;
import java.util.Optional;

public enum PunishmentsFilter {

    ALL,
    BANS,
    MUTES,
    TIMEOUTS,
    FREEZES,
    VERSTOESSE,
    WARNS,
    KICKS,
    IPBANS,
    IPMUTES,
    IPTIMEOUTS;

    public static Optional<PunishmentsFilter> parsen(String roh) {
        if (roh == null) {
            return Optional.empty();
        }
        String norm = roh.trim().toLowerCase(Locale.ROOT)
                .replace("ß", "ss")
                .replace("ä", "a")
                .replace("ö", "o")
                .replace("ü", "u")
                .replace("-", "")
                .replace("_", "")
                .replace(" ", "");
        return switch (norm) {
            case "all", "alle", "alles" -> Optional.of(ALL);
            case "ban", "bans", "bann", "banns", "permabann", "permaban", "permanent" -> Optional.of(BANS);
            case "mute", "mutes" -> Optional.of(MUTES);
            case "timeout", "timeouts" -> Optional.of(TIMEOUTS);
            case "freeze", "freezes", "frost" -> Optional.of(FREEZES);
            case "verstoss", "verstosse", "verstoesse" -> Optional.of(VERSTOESSE);
            case "warn", "warns", "warnung", "warnungen", "verwarnung", "verwarnungen" -> Optional.of(WARNS);
            case "kick", "kicks" -> Optional.of(KICKS);
            case "ipban", "ipbans", "banip", "banips", "banipbans" -> Optional.of(IPBANS);
            case "ipmute", "ipmutes", "muteip", "muteips" -> Optional.of(IPMUTES);
            case "iptimeout", "iptimeouts", "timeoutip", "timeoutips" -> Optional.of(IPTIMEOUTS);
            default -> Optional.empty();
        };
    }

    public boolean passt(HistoryType typ) {
        if (typ == null) {
            return false;
        }
        return switch (this) {
            case ALL -> true;
            case BANS -> switch (typ) {
                case BAN, BAN_EDIT, PERMABANN, AUTO_BANN, BAN_ALL, PARDON, PARDON_ALL, AUTO_PARDON -> true;
                default -> false;
            };
            case MUTES -> typ == HistoryType.MUTE || typ == HistoryType.MUTE_EDIT
                    || typ == HistoryType.UNMUTE || typ == HistoryType.MUTE_ALL
                    || typ == HistoryType.UNMUTE_ALL;
            case TIMEOUTS -> typ == HistoryType.TIMEOUT || typ == HistoryType.TIMEOUT_EDIT
                    || typ == HistoryType.UNTIMEOUT || typ == HistoryType.TIMEOUT_ALL
                    || typ == HistoryType.UNTIMEOUT_ALL;
            case FREEZES -> typ == HistoryType.FREEZE || typ == HistoryType.UNFREEZE;
            case KICKS -> typ == HistoryType.KICK || typ == HistoryType.KICK_ALL
                    || typ == HistoryType.IP_KICK;
            case VERSTOESSE -> switch (typ) {
                case VERSTOSS_ADD, VERSTOSS_REMOVE, VERSTOSS_EDIT, RESET, AUTO_BANN_EXPIRED -> true;
                default -> false;
            };
            case WARNS -> switch (typ) {
                case WARN_ADD, WARN_REMOVE, WARN_EDIT -> true;
                default -> false;
            };
            case IPBANS -> typ == HistoryType.BAN_IP || typ == HistoryType.IP_BAN_EDIT
                    || typ == HistoryType.UNBAN_IP;
            case IPMUTES -> typ == HistoryType.IP_MUTE || typ == HistoryType.IP_MUTE_EDIT
                    || typ == HistoryType.IP_UNMUTE;
            case IPTIMEOUTS -> typ == HistoryType.IP_TIMEOUT || typ == HistoryType.IP_TIMEOUT_EDIT
                    || typ == HistoryType.IP_UNTIMEOUT;
        };
    }

    public boolean zeigtBanns() {
        return this == ALL || this == BANS;
    }

    public boolean zeigtMutes() {
        return this == ALL || this == MUTES;
    }

    public boolean zeigtTimeouts() {
        return this == ALL || this == TIMEOUTS;
    }

    public boolean zeigtFreezes() {
        return this == ALL || this == FREEZES;
    }

    public boolean zeigtVerstoesse() {
        return this == ALL || this == VERSTOESSE;
    }

    public boolean zeigtWarns() {
        return this == ALL || this == WARNS;
    }

    public boolean zeigtKicks() {
        return this == ALL || this == KICKS;
    }

    public boolean zeigtIpBanns() {
        return this == ALL || this == IPBANS;
    }

    public boolean zeigtIpMutes() {
        return this == ALL || this == IPMUTES;
    }

    public boolean zeigtIpTimeouts() {
        return this == ALL || this == IPTIMEOUTS;
    }

    public String anzeigename() {
        return switch (this) {
            case ALL -> "Alle";
            case BANS -> "Banns";
            case MUTES -> "Mutes";
            case TIMEOUTS -> "Timeouts";
            case FREEZES -> "Freezes";
            case VERSTOESSE -> "Verstöße";
            case WARNS -> "Warns";
            case KICKS -> "Kicks";
            case IPBANS -> "IP-Banns";
            case IPMUTES -> "IP-Mutes";
            case IPTIMEOUTS -> "IP-Timeouts";
        };
    }
}
