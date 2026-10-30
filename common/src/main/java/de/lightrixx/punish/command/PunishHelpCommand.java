package de.lightrixx.punish.command;

import de.lightrixx.punish.core.BefehlsSender;
import de.lightrixx.punish.core.Konfiguration;
import de.lightrixx.punish.core.MessageKeys;
import de.lightrixx.punish.core.Permissions;
import de.lightrixx.punish.core.SpielerVerzeichnis;
import de.lightrixx.punish.service.MessageService;
import de.lightrixx.punish.service.ProtectionService;
import de.lightrixx.punish.storage.PlayerCache;
import net.kyori.adventure.text.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class PunishHelpCommand extends PunishCommand {

    private record Eintrag(String befehl, String nutzung, String recht, String rechtAlternativ) {

        Eintrag(String befehl, String nutzung, String recht) {
            this(befehl, nutzung, recht, null);
        }
    }

    private static final String BETA_ANHAENGSEL = " (Beta)";

    private static final List<Eintrag> EINTRAEGE = List.of(
            new Eintrag("ban", "<Spieler> <Dauer> [Grund]", Permissions.PUNISH_BAN.node()),
            new Eintrag("permabann", "<Spieler> [Grund]", Permissions.PUNISH_PERMABANN.node()),
            new Eintrag("ban-ip", "<IP|Spieler> [Dauer] [Grund]", Permissions.PUNISH_BANIP.node()),
            new Eintrag("unban-ip", "<IP|Spieler>", Permissions.PUNISH_UNBANIP.node()),
            new Eintrag("ban-all", "<Dauer|perm> confirm [Grund]", Permissions.PUNISH_BANALL.node()),
            new Eintrag("mute", "<Spieler> <Dauer> [Grund]", Permissions.PUNISH_MUTE.node()),
            new Eintrag("mute-all", "<Dauer> confirm [Grund]", Permissions.PUNISH_MUTEALL.node()),
            new Eintrag("unmute", "<Spieler>", Permissions.PUNISH_UNMUTE.node()),
            new Eintrag("unmute-all", "confirm [Grund]", Permissions.PUNISH_UNMUTEALL.node()),
            new Eintrag("mute-ip (muteip)", "<IP|Spieler> <Dauer> [Grund]", Permissions.PUNISH_MUTEIP.node()),
            new Eintrag("unmute-ip (unmuteip)", "<IP>", Permissions.PUNISH_UNMUTEIP.node()),
            new Eintrag("kick", "<Spieler> [Grund]", Permissions.PUNISH_KICK.node()),
            new Eintrag("kick-ip (kickip)", "<IP|Spieler> [Grund]", Permissions.PUNISH_KICKIP.node()),
            new Eintrag("kick-all", "confirm [Grund]", Permissions.PUNISH_KICKALL.node()),
            new Eintrag("pardon (unban)", "<Spieler>", Permissions.PUNISH_PARDON.node()),
            new Eintrag("pardon-all (unban-all)", "confirm [Grund]", Permissions.PUNISH_PARDONALL.node()),
            new Eintrag("timeout", "<Spieler> <Dauer> [Grund]", Permissions.PUNISH_TIMEOUT.node()),
            new Eintrag("timeout-all", "<Dauer> confirm [Grund]", Permissions.PUNISH_TIMEOUTALL.node()),
            new Eintrag("untimeout", "<Spieler>", Permissions.PUNISH_UNTIMEOUT.node()),
            new Eintrag("untimeout-all", "confirm [Grund]", Permissions.PUNISH_UNTIMEOUTALL.node()),
            new Eintrag("timeout-ip (timeoutip)", "<IP|Spieler> <Dauer> [Grund]", Permissions.PUNISH_TIMEOUTIP.node()),
            new Eintrag("untimeout-ip (untimeoutip)", "<IP|Spieler>", Permissions.PUNISH_UNTIMEOUTIP.node()),
            new Eintrag("freeze", "<Spieler> [Dauer] [Grund]", Permissions.PUNISH_FREEZE.node()),
            new Eintrag("unfreeze", "<Spieler>", Permissions.PUNISH_UNFREEZE.node()),
            new Eintrag("verstoss (verstoß)", "<add|remove|clear> <Spieler> [...]", Permissions.PUNISH_VERSTOSS.node()),
            new Eintrag("punish-note (psnote)", "<add|delete|list> <Spieler> [...]", Permissions.PUNISH_NOTE.node()),
            new Eintrag("punish-edit (psedit)", "<Typ> <Ziel> [Nr] [Felder]", Permissions.PUNISH_EDIT.node()),
            new Eintrag("warn (warnung)", "<Spieler> <Grund> | add|delete|list", Permissions.PUNISH_WARN.node()),
            new Eintrag("punish", "<Spieler> <Vorlage> [Text]", Permissions.PUNISH_TEMPLATES.node()),
            new Eintrag("punish-undo (psundo)", "[Spieler]", Permissions.PUNISH_UNDO.node()),
            new Eintrag("punish-stats (psstats)", "[7d|30d|all] [Staff]", Permissions.PUNISH_STATS.node()),
            new Eintrag("punish-export (psexport)", "", Permissions.PUNISH_EXPORT.node()),
            new Eintrag("punish-dossier (psdossier)", "<Spieler>", Permissions.PUNISH_GUI.node()),
            new Eintrag("punish-status (psstatus)", "[Spieler|server] [filter:...]", Permissions.PUNISH_STATUS.node(),
                    Permissions.PUNISH_OWN.node()),
            new Eintrag("punish-history (pshistory)", "[Spieler|server] [filter:...] [Seite]", Permissions.PUNISH_HISTORY.node(),
                    Permissions.PUNISH_OWN.node()),
            new Eintrag("alts" + BETA_ANHAENGSEL, "<Spieler|IP>", Permissions.PUNISH_ALTS.node()),
            new Eintrag("report (melden)", "<Spieler> <Grund>", Permissions.PUNISH_REPORT.node()),
            new Eintrag("reports (reportlist)", "[offen|alle|info|...]", Permissions.PUNISH_REPORTS.node()),
            new Eintrag("punish-gui (psgui)", "[Spieler]", Permissions.PUNISH_GUI.node()),
            new Eintrag("chatfilter" + BETA_ANHAENGSEL, "<on|off|ai_on|ai_off|add|remove|list|test|sync>", Permissions.PUNISH_CHATFILTER.node()),
            new Eintrag("punish-delete-ip (psdelete-ip)", "<IP|Spieler>", Permissions.PUNISH_DELETEIP.node()),
            new Eintrag("punish-reload (psreload)", "", Permissions.PUNISH_RELOAD.node()),
            new Eintrag("punish-update (psupdate)", "check|apply|confirm|status", Permissions.PUNISH_UPDATE.node()),
            new Eintrag("punish-diagnostics (psdiagnostics)", "", Permissions.PUNISH_DIAGNOSTICS.node()),
            new Eintrag("punish-version (psversion)", "", Permissions.PUNISH_VERSION.node()),
            new Eintrag("punish-help (pshelp)", "", Permissions.PUNISH_HELP.node()));

    public PunishHelpCommand(MessageService messageService,
                             Konfiguration configManager,
                             ProtectionService protectionService,
                             PlayerCache playerCache,
                             SpielerVerzeichnis verzeichnis) {
        super(messageService, configManager, protectionService, playerCache, null,
                Objects.requireNonNull(verzeichnis, "Das Spielerverzeichnis darf nicht null sein."));
    }

    @Override
    protected String recht() {
        return Permissions.PUNISH_HELP.node();
    }

    @Override
    public boolean unterstuetztScope() {
        return false;
    }

    @Override
    public void ausfuehren(BefehlsSender sender, String[] args) {
        List<Component> zeilen = new ArrayList<>();
        zeilen.add(messageService.komponente(MessageKeys.PUNISH_HELP_KOPF, Map.of()));
        int sichtbar = 0;
        for (Eintrag eintrag : EINTRAEGE) {
            try {
                if (sender != null && (sender.hatRecht(eintrag.recht())
                        || (eintrag.rechtAlternativ() != null && sender.hatRecht(eintrag.rechtAlternativ())))) {
                    zeilen.add(messageService.komponenteOhnePraefix(MessageKeys.PUNISH_HELP_EINTRAG,
                            Map.of("befehl", eintrag.befehl(), "nutzung", eintrag.nutzung())));
                    sichtbar++;
                }
            } catch (Exception e) {
                LOG.debug("Hilfe-Eintrag übersprungen.", e);
            }
        }
        if (sichtbar == 0) {
            zeilen.add(messageService.komponenteOhnePraefix(MessageKeys.PUNISH_HELP_LEER, Map.of()));
        }
        zeilen.add(messageService.komponenteOhnePraefix(MessageKeys.PUNISH_HELP_FUSS, Map.of()));
        messageService.sendeBlock(sender, zeilen);
    }

    @Override
    public List<String> vervollstaendigen(BefehlsSender sender, String[] args) {
        return List.of();
    }
}
