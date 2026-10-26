package de.lightrixx.punish.model;

public enum HistoryType {
    BAN,
    BAN_EDIT,
    PERMABANN,
    MUTE,
    MUTE_EDIT,
    UNMUTE,
    MUTE_ALL,
    UNMUTE_ALL,
    TIMEOUT,
    TIMEOUT_EDIT,
    TIMEOUT_ALL,
    UNTIMEOUT_ALL,
    FREEZE,
    UNFREEZE,
    KICK,
    KICK_ALL,
    BAN_IP,
    IP_BAN_EDIT,
    IP_MUTE,
    IP_MUTE_EDIT,
    IP_UNMUTE,
    IP_TIMEOUT,
    IP_TIMEOUT_EDIT,
    IP_UNTIMEOUT,
    IP_KICK,
    BAN_ALL,
    PARDON,
    PARDON_ALL,
    VERSTOSS_ADD,
    VERSTOSS_REMOVE,
    VERSTOSS_EDIT,
    WARN_ADD,
    WARN_REMOVE,
    WARN_EDIT,
    AUTO_BANN,
    AUTO_PARDON,
    AUTO_BANN_EXPIRED,
    RESET,
    UNTIMEOUT,
    UNBAN_IP,
    REPORT_ADD,
    REPORT_CLOSE;

    public String anzeigename() {
        return switch (this) {
            case BAN -> "Bann";
            case BAN_EDIT -> "Bann bearbeitet";
            case PERMABANN -> "Permanenter Bann";
            case MUTE -> "Mute";
            case MUTE_EDIT -> "Mute bearbeitet";
            case UNMUTE -> "Entmutung";
            case MUTE_ALL -> "Massen-Mute";
            case UNMUTE_ALL -> "Massen-Entmutung";
            case TIMEOUT -> "Timeout";
            case TIMEOUT_EDIT -> "Timeout bearbeitet";
            case TIMEOUT_ALL -> "Massen-Timeout";
            case UNTIMEOUT_ALL -> "Massen-Timeout-Aufhebung";
            case FREEZE -> "Freeze";
            case UNFREEZE -> "Freeze-Aufhebung";
            case KICK -> "Kick";
            case KICK_ALL -> "Massen-Kick";
            case BAN_IP -> "IP-Bann";
            case IP_BAN_EDIT -> "IP-Bann bearbeitet";
            case IP_MUTE -> "IP-Mute";
            case IP_MUTE_EDIT -> "IP-Mute bearbeitet";
            case IP_UNMUTE -> "IP-Entmutung";
            case IP_TIMEOUT -> "IP-Timeout";
            case IP_TIMEOUT_EDIT -> "IP-Timeout bearbeitet";
            case IP_UNTIMEOUT -> "IP-Timeout-Aufhebung";
            case IP_KICK -> "IP-Kick";
            case BAN_ALL -> "Massen-Bann";
            case PARDON -> "Entbannung";
            case PARDON_ALL -> "Massen-Entbannung";
            case VERSTOSS_ADD -> "Verstoß hinzugefügt";
            case VERSTOSS_REMOVE -> "Verstoß entfernt";
            case VERSTOSS_EDIT -> "Verstoß bearbeitet";
            case WARN_ADD -> "Warnung hinzugefügt";
            case WARN_REMOVE -> "Warnung entfernt";
            case WARN_EDIT -> "Warnung bearbeitet";
            case AUTO_BANN -> "Auto-Bann";
            case AUTO_PARDON -> "Auto-Entbannung";
            case AUTO_BANN_EXPIRED -> "Auto-Bann abgelaufen";
            case RESET -> "Verstoß-Reset";
            case UNTIMEOUT -> "Timeout-Aufhebung";
            case UNBAN_IP -> "IP-Entbannung";
            case REPORT_ADD -> "Report erstellt";
            case REPORT_CLOSE -> "Report geschlossen";
        };
    }

    public boolean istAufhebung() {
        return switch (this) {
            case PARDON, PARDON_ALL, UNMUTE, UNMUTE_ALL, UNTIMEOUT, UNTIMEOUT_ALL, UNBAN_IP, AUTO_PARDON,
                    AUTO_BANN_EXPIRED, RESET, UNFREEZE,
                    BAN_EDIT, MUTE_EDIT, TIMEOUT_EDIT, IP_BAN_EDIT,
                    IP_MUTE_EDIT, IP_TIMEOUT_EDIT, IP_UNMUTE, IP_UNTIMEOUT -> true;
            default -> false;
        };
    }

    public boolean istEdit() {
        return switch (this) {
            case BAN_EDIT, MUTE_EDIT, TIMEOUT_EDIT, IP_BAN_EDIT, IP_MUTE_EDIT, IP_TIMEOUT_EDIT -> true;
            default -> false;
        };
    }
}
