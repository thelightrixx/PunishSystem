package de.lightrixx.punish.core;

public enum Permissions {

    PUNISH_BAN("punish.ban"),
    PUNISH_PERMABANN("punish.permabann"),
    PUNISH_BANIP("punish.banip"),
    PUNISH_UNBANIP("punish.unbanip"),
    PUNISH_MUTEIP("punish.muteip"),
    PUNISH_UNMUTEIP("punish.unmuteip"),
    PUNISH_TIMEOUTIP("punish.timeoutip"),
    PUNISH_UNTIMEOUTIP("punish.untimeoutip"),
    PUNISH_KICKIP("punish.kickip"),
    PUNISH_BANALL("punish.banall"),
    PUNISH_MUTE("punish.mute"),
    PUNISH_MUTEALL("punish.muteall"),
    PUNISH_UNMUTE("punish.unmute"),
    PUNISH_UNMUTEALL("punish.unmuteall"),
    PUNISH_KICK("punish.kick"),
    PUNISH_KICKALL("punish.kickall"),
    PUNISH_PARDON("punish.pardon"),
    PUNISH_PARDONALL("punish.pardonall"),
    PUNISH_TIMEOUT("punish.timeout"),
    PUNISH_TIMEOUTALL("punish.timeoutall"),
    PUNISH_UNTIMEOUT("punish.untimeout"),
    PUNISH_UNTIMEOUTALL("punish.untimeoutall"),
    PUNISH_FREEZE("punish.freeze"),
    PUNISH_UNFREEZE("punish.unfreeze"),
    PUNISH_VERSTOSS("punish.verstoss"),
    PUNISH_VERSTOSS_ADD("punish.verstoss.add"),
    PUNISH_VERSTOSS_REMOVE("punish.verstoss.remove"),
    PUNISH_VERSTOSS_EDIT("punish.verstoss.edit"),
    PUNISH_VERSTOSS_CLEAR("punish.verstoss.clear"),
    PUNISH_EDIT("punish.edit"),
    PUNISH_WARN("punish.warn"),
    PUNISH_NOTE("punish.note"),
    PUNISH_REPORT("punish.report"),
    PUNISH_REPORTS("punish.reports"),
    PUNISH_OWN("punish.own"),
    PUNISH_STATUS("punish.status"),
    PUNISH_STATUS_SERVER("punish.status.server"),
    PUNISH_HISTORY("punish.history"),
    PUNISH_HISTORY_SERVER("punish.history.server"),
    PUNISH_ALTS("punish.alts"),
    PUNISH_RELOAD("punish.reload"),
    PUNISH_UPDATE("punish.update"),
    PUNISH_VERSION("punish.version"),
    PUNISH_HELP("punish.help"),
    PUNISH_DIAGNOSTICS("punish.diagnostics"),
    PUNISH_DELETEIP("punish.deleteip"),
    PUNISH_CHATFILTER("punish.chatfilter"),
    PUNISH_CHATFILTER_SYNC("punish.chatfilter.sync"),
    PUNISH_CHATFILTER_BYPASS("punish.chatfilter.bypass"),
    PUNISH_GUI("punish.gui"),
    PUNISH_TEMPLATES("punish.templates"),
    PUNISH_UNDO("punish.undo"),
    PUNISH_STATS("punish.stats"),
    PUNISH_EXPORT("punish.export"),
    ALLOW_SILENT("punish.allow-silent-syntax"),

    BROADCAST_RECEIVE_ALL("punish.message.all-punishes"),

    /**
     * @deprecated Veraltet, nur Kompatibilität. Schutz läuft über {@code punish.ignore.*}.
     */
    @Deprecated
    IGNORE_PUNISHMENT("punish.ignore"),
    IGNORE_STAR("punish.ignore.*"),
    IGNORE_BAN("punish.ignore.ban"),
    IGNORE_MUTE("punish.ignore.mute"),
    IGNORE_TIMEOUT("punish.ignore.timeout"),
    IGNORE_FREEZE("punish.ignore.freeze"),
    IGNORE_KICK("punish.ignore.kick"),
    IGNORE_WARN("punish.ignore.warn"),
    IGNORE_VERSTOSS("punish.ignore.verstoss"),
    IGNORE_NOTE("punish.ignore.note"),
    IGNORE_ALTS("punish.ignore.alts"),
    IGNORE_REPORT("punish.ignore.report"),

    ROLE_USER("punish.user"),
    ROLE_MODERATOR("punish.moderator"),
    ROLE_ADMIN("punish.admin"),
    WILDCARD("punish.*"),
    ALL("punish.all");

    private final String node;

    Permissions(String node) {
        this.node = node;
    }

    public String node() {
        return node;
    }

    public boolean hat(BefehlsSender sender) {
        return sender != null && sender.hatRecht(node);
    }

    public boolean hatOder(BefehlsSender sender, Permissions... andere) {
        if (hat(sender)) {
            return true;
        }
        if (sender == null || andere == null) {
            return false;
        }
        for (Permissions perm : andere) {
            if (perm != null && sender.hatRecht(perm.node)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return node;
    }
}
