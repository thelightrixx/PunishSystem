package de.lightrixx.punish.command;

import de.lightrixx.punish.core.BefehlsSender;
import de.lightrixx.punish.core.Konfiguration;
import de.lightrixx.punish.core.MessageKeys;
import de.lightrixx.punish.core.Permissions;
import de.lightrixx.punish.core.SpielerVerzeichnis;
import de.lightrixx.punish.model.CachedPlayer;
import de.lightrixx.punish.model.HistoryEntry;
import de.lightrixx.punish.model.HistoryType;
import de.lightrixx.punish.model.WarnRecord;
import de.lightrixx.punish.service.HistoryService;
import de.lightrixx.punish.service.MessageService;
import de.lightrixx.punish.service.ProtectionService;
import de.lightrixx.punish.service.WarnService;
import de.lightrixx.punish.storage.PlayerCache;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class PunishUndoCommand extends PunishCommand {

    private static final long WARN_ZEITFENSTER_MILLIS = 60_000L;

    private static final Set<HistoryType> STRAF_TYPEN = Set.of(
            HistoryType.BAN, HistoryType.BAN_EDIT, HistoryType.PERMABANN,
            HistoryType.MUTE, HistoryType.MUTE_EDIT, HistoryType.TIMEOUT,
            HistoryType.TIMEOUT_EDIT, HistoryType.FREEZE, HistoryType.KICK,
            HistoryType.KICK_ALL, HistoryType.BAN_IP, HistoryType.IP_BAN_EDIT,
            HistoryType.IP_MUTE, HistoryType.IP_MUTE_EDIT, HistoryType.IP_TIMEOUT,
            HistoryType.IP_TIMEOUT_EDIT, HistoryType.IP_KICK, HistoryType.BAN_ALL,
            HistoryType.WARN_ADD, HistoryType.AUTO_BANN, HistoryType.VERSTOSS_ADD);

    private static final Comparator<HistoryEntry> NEUESTE_ZUERST =
            Comparator.comparingLong((HistoryEntry eintrag) -> eintrag.zeitstempel()).reversed()
                    .thenComparing(Comparator.comparingLong((HistoryEntry eintrag) -> eintrag.id()).reversed());

    private final HistoryService historyService;
    private final WarnService warnService;
    private final Map<String, PunishCommand> zielBefehle;

    public PunishUndoCommand(MessageService messageService,
                             Konfiguration configManager,
                             ProtectionService protectionService,
                             PlayerCache playerCache,
                             SpielerVerzeichnis verzeichnis,
                             HistoryService historyService,
                             WarnService warnService,
                             Map<String, PunishCommand> zielBefehle) {
        super(messageService, configManager, protectionService, playerCache, null,
                Objects.requireNonNull(verzeichnis, "Das Spielerverzeichnis darf nicht null sein."));
        this.historyService = Objects.requireNonNull(historyService,
                "Der HistoryService darf nicht null sein.");
        this.warnService = Objects.requireNonNull(warnService,
                "Der WarnService darf nicht null sein.");
        this.zielBefehle = Map.copyOf(Objects.requireNonNull(zielBefehle,
                "Die Zielbefehle dürfen nicht null sein."));
    }

    @Override
    protected String recht() {
        return Permissions.PUNISH_UNDO.node();
    }

    @Override
    public boolean unterstuetztScope() {
        return false;
    }

    @Override
    public void ausfuehren(BefehlsSender sender, String[] args) {
        if (args != null && args.length > 1) {
            messageService.senden(sender, MessageKeys.PUNISH_UNDO_SYNTAX);
            return;
        }
        String zielFilter = args != null && args.length == 1 && args[0] != null
                ? args[0].trim().toLowerCase(Locale.ROOT) : null;
        String ausfuehrer = ausfuehrerName(sender);
        HistoryEntry letzte = letzteAktion(ausfuehrer, zielFilter);
        if (letzte == null) {
            messageService.senden(sender, MessageKeys.PUNISH_UNDO_KEINE,
                    Map.of("ausfuehrer", ausfuehrer));
            return;
        }
        if (!letzte.istEffektivAktiv(System.currentTimeMillis())) {
            messageService.senden(sender, MessageKeys.PUNISH_UNDO_KEINE,
                    Map.of("ausfuehrer", ausfuehrer));
            return;
        }
        Delegation delegation = delegationFuer(letzte);
        if (delegation == null) {
            messageService.senden(sender, MessageKeys.PUNISH_UNDO_NICHT,
                    Map.of("typ", letzte.typ().anzeigename(), "ziel", letzte.ziel()));
            return;
        }
        PunishCommand zielBefehl = zielBefehle.get(delegation.befehl());
        if (zielBefehl == null) {
            messageService.senden(sender, MessageKeys.PUNISH_UNDO_NICHT,
                    Map.of("typ", letzte.typ().anzeigename(), "ziel", letzte.ziel()));
            return;
        }
        if (!zielBefehl.hatRecht(sender, delegation.argumente())) {
            messageService.senden(sender, MessageKeys.KEIN_RECHT);
            return;
        }
        messageService.senden(sender, MessageKeys.PUNISH_UNDO_AKTION, Map.of(
                "typ", letzte.typ().anzeigename(),
                "ziel", letzte.ziel(),
                "grund", letzte.grund()));
        zielBefehl.ausfuehren(sender, delegation.argumente());
    }

    private HistoryEntry letzteAktion(String ausfuehrer, String zielFilter) {
        List<HistoryEntry> verlauf;
        try {
            verlauf = historyService.findeAlle();
        } catch (Exception e) {
            LOG.warn("Verlauf für /punish-undo konnte nicht geladen werden.", e);
            return null;
        }
        if (verlauf == null || verlauf.isEmpty()) {
            return null;
        }
        long jetzt = System.currentTimeMillis();
        return verlauf.stream()
                .filter(Objects::nonNull)
                .filter(eintrag -> eintrag.istEffektivAktiv(jetzt))
                .filter(eintrag -> eintrag.ausfuehrer() != null
                        && eintrag.ausfuehrer().equalsIgnoreCase(ausfuehrer))
                .filter(eintrag -> zielFilter == null
                        || (eintrag.ziel() != null
                        && eintrag.ziel().toLowerCase(Locale.ROOT).equals(zielFilter)))
                .filter(eintrag -> STRAF_TYPEN.contains(eintrag.typ()))
                .sorted(NEUESTE_ZUERST)
                .findFirst()
                .orElse(null);
    }

    private Delegation delegationFuer(HistoryEntry eintrag) {
        String ziel = eintrag.ziel();
        if (ziel == null || ziel.isBlank()) {
            return null;
        }
        return switch (eintrag.typ()) {
            case BAN, PERMABANN, AUTO_BANN -> new Delegation("pardon", new String[]{ziel});
            case MUTE -> new Delegation("unmute", new String[]{ziel});
            case TIMEOUT -> new Delegation("untimeout", new String[]{ziel});
            case FREEZE -> new Delegation("unfreeze", new String[]{ziel});
            case BAN_IP -> new Delegation("unban-ip", new String[]{ziel});
            case IP_MUTE -> new Delegation("unmute-ip", new String[]{ziel});
            case IP_TIMEOUT -> new Delegation("untimeout-ip", new String[]{ziel});
            case WARN_ADD -> warnDelegation(eintrag);
            default -> null;
        };
    }

    private Delegation warnDelegation(HistoryEntry eintrag) {
        Optional<CachedPlayer> ziel = zielAufloesen(eintrag.ziel());
        if (ziel.isEmpty()) {
            return null;
        }
        WarnRecord treffer = null;
        long besteAbweichung = Long.MAX_VALUE;
        try {
            for (WarnRecord warn : warnService.findeWarns(ziel.get().uuid())) {
                if (warn == null || !warn.grund().equals(eintrag.grund())) {
                    continue;
                }
                long abweichung = Math.abs(warn.erstelltAm() - eintrag.zeitstempel());
                if (abweichung < besteAbweichung) {
                    besteAbweichung = abweichung;
                    treffer = warn;
                }
            }
        } catch (Exception e) {
            LOG.warn("Warns für /punish-undo konnten nicht geladen werden.", e);
            return null;
        }
        if (treffer == null || besteAbweichung > WARN_ZEITFENSTER_MILLIS) {
            return null;
        }
        return new Delegation("warn", new String[]{
                "delete", ziel.get().name(), String.valueOf(treffer.nummer())});
    }

    private record Delegation(String befehl, String[] argumente) {
    }

    @Override
    public List<String> vervollstaendigen(BefehlsSender sender, String[] args) {
        if (args == null || args.length != 1) {
            return List.of();
        }
        return spielerVorschlaegeSmart(sender, args[0] == null ? "" : args[0]);
    }

    @Override
    public String tooltipFuer(String vorschlag, String[] args) {
        String kategorie = kategorieTooltip(vorschlag);
        return kategorie == null || kategorie.isBlank() ? "Spieler" : kategorie;
    }
}
