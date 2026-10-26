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
import java.util.Set;
import java.util.function.Supplier;

public final class PunishDiagnosticsCommand extends PunishCommand {

    public record Kontext(
            String plattform,
            String version,
            String speicherTyp,
            boolean speicherOk,
            String speicherDetail,
            int serverAnzahl,
            boolean bridgeOk,
            String bridgeDetail,
            boolean luckPermsVorhanden,
            boolean chatfilterAktiv,
            String chatfilterDetail,
            boolean versionenOk,
            String versionenDetail) {
        public Kontext {
            plattform = plattform == null || plattform.isBlank() ? "unbekannt" : plattform;
            version = version == null || version.isBlank() ? "unbekannt" : version;
            speicherTyp = speicherTyp == null || speicherTyp.isBlank() ? "unbekannt" : speicherTyp;
            speicherDetail = speicherDetail == null ? "" : speicherDetail;
            bridgeDetail = bridgeDetail == null ? "" : bridgeDetail;
            chatfilterDetail = chatfilterDetail == null ? "" : chatfilterDetail;
            versionenDetail = versionenDetail == null ? "" : versionenDetail;
        }
    }

    private final Supplier<Kontext> kontextLieferant;

    public PunishDiagnosticsCommand(MessageService messageService,
                                    Konfiguration configManager,
                                    ProtectionService protectionService,
                                    PlayerCache playerCache,
                                    SpielerVerzeichnis verzeichnis,
                                    Supplier<Kontext> kontextLieferant) {
        super(messageService, configManager, protectionService, playerCache, null,
                Objects.requireNonNull(verzeichnis, "Das Spielerverzeichnis darf nicht null sein."));
        this.kontextLieferant = Objects.requireNonNull(kontextLieferant, "Der Kontextlieferant darf nicht null sein.");
    }

    @Override
    protected String recht() {
        return Permissions.PUNISH_DIAGNOSTICS.node();
    }

    @Override
    public boolean unterstuetztScope() {
        return false;
    }

    @Override
    public void ausfuehren(BefehlsSender sender, String[] args) {
        Kontext kontext = leseKontext();
        List<Zeile> zeilen = pruefe(kontext);
        List<Component> ausgabe = new ArrayList<>();
        ausgabe.add(messageService.komponente(MessageKeys.PUNISH_DIAGNOSTICS_KOPF, Map.of(
                "plattform", kontext.plattform(),
                "version", kontext.version())));
        int warnungen = 0;
        for (Zeile zeile : zeilen) {
            if (!zeile.ok()) {
                warnungen++;
            }
            ausgabe.add(messageService.komponenteOhnePraefix(MessageKeys.PUNISH_DIAGNOSTICS_ZEILE, Map.of(
                    "status", zeile.ok() ? "<green>OK" : "<yellow>WARN",
                    "name", zeile.name(),
                    "detail", zeile.detail()), Set.of("status")));
        }
        ausgabe.add(messageService.komponenteOhnePraefix(MessageKeys.PUNISH_DIAGNOSTICS_FUSS, Map.of(
                "warnungen", String.valueOf(warnungen),
                "anzahl", String.valueOf(zeilen.size()))));
        messageService.sendeBlock(sender, ausgabe);
    }

    private Kontext leseKontext() {
        try {
            Kontext kontext = kontextLieferant.get();
            return kontext == null
                    ? new Kontext("unbekannt", "unbekannt", "unbekannt", false, "kein Kontext",
                            0, false, "kein Kontext", false, false, "", false, "kein Kontext")
                    : kontext;
        } catch (Exception e) {
            LOG.debug("Diagnose-Kontext konnte nicht gelesen werden.", e);
            return new Kontext("unbekannt", "unbekannt", "unbekannt", false, "Kontextfehler",
                    0, false, "Kontextfehler", false, false, "", false, "Kontextfehler");
        }
    }

    record Zeile(String name, boolean ok, String detail) {
    }

    List<Zeile> pruefe(Kontext kontext) {
        List<Zeile> ergebnis = new ArrayList<>();
        ergebnis.add(new Zeile("Speicher (" + kontext.speicherTyp() + ")",
                kontext.speicherOk(), kontext.speicherDetail()));
        boolean yamlVeraltet = "yaml".equalsIgnoreCase(kontext.speicherTyp().trim());
        ergebnis.add(new Zeile("Speicher-Backend aktuell",
                !yamlVeraltet, yamlVeraltet ? "YAML ist deprecated, speicher.typ=mysql empfohlen" : "aktuell"));
        boolean failClosed = true;
        try {
            failClosed = configManager.schutzFailClosed();
        } catch (Exception e) {
            LOG.debug("Fail-Closed-Lookup (Diagnose) fehlgeschlagen.", e);
        }
        ergebnis.add(new Zeile("Schutz fail-closed",
                failClosed, failClosed ? "aktiv" : "fail-open: true ist Legacy, für Team-Server false vermeiden"));
        boolean neverSeen = false;
        try {
            neverSeen = configManager.offlineAllowNeverSeen();
        } catch (Exception e) {
            LOG.debug("Offline-Resolution-Lookup (Diagnose) fehlgeschlagen.", e);
        }
        ergebnis.add(new Zeile("Offline-Auflösung",
                !neverSeen, neverSeen ? "allow-never-seen=true (Squatting-Risiko)" : "fail-closed (empfohlen)"));
        try {
            String zone = String.valueOf(configManager.anzeigeZone());
            ergebnis.add(new Zeile("Anzeige-Zone", true, zone));
        } catch (Exception e) {
            ergebnis.add(new Zeile("Anzeige-Zone", false, "ungültig, nutze Europe/Berlin"));
        }
        ergebnis.add(pruefeLimits());
        ergebnis.add(pruefeEskalation());
        ergebnis.add(new Zeile("Version",
                !"unbekannt".equals(kontext.version()), kontext.version()));
        ergebnis.add(new Zeile("Serverliste",
                kontext.serverAnzahl() > 0, kontext.serverAnzahl() + " Server registriert"));
        ergebnis.add(new Zeile("Bridge-Anbindung", kontext.bridgeOk(), kontext.bridgeDetail()));
        ergebnis.add(new Zeile("LuckPerms",
                kontext.luckPermsVorhanden(),
                kontext.luckPermsVorhanden() ? "gefunden" : "nicht gefunden (Offline-Schutz nur via OP)"));
        ergebnis.add(new Zeile("Chatfilter",
                !kontext.chatfilterAktiv() || !kontext.chatfilterDetail().isBlank() && !kontext.chatfilterDetail().toLowerCase(java.util.Locale.ROOT).contains("fehlt"),
                kontext.chatfilterAktiv() ? kontext.chatfilterDetail() : "deaktiviert"));
        ergebnis.add(new Zeile("Plugin-Versionen", kontext.versionenOk(), kontext.versionenDetail()));
        return List.copyOf(ergebnis);
    }

    private Zeile pruefeLimits() {
        try {
            long timeoutMax = configManager.timeoutMaxMinuten();
            long muteMax = configManager.muteMaxMinuten();
            long banMax = configManager.banMaxMinuten();
            long banMin = configManager.banMinMinuten();
            boolean ok = timeoutMax > 0 && muteMax > 0 && banMax > 0 && banMin > 0 && banMin <= banMax;
            String detail = "timeout-max=" + timeoutMax + ", mute-max=" + muteMax
                    + ", ban-min=" + banMin + ", ban-max=" + banMax;
            if (!ok) {
                detail += " (ban-min muss <= ban-max sein, alle > 0)";
            }
            return new Zeile("Limits", ok, detail);
        } catch (Exception e) {
            return new Zeile("Limits", false, "Konfiguration nicht lesbar");
        }
    }

    private Zeile pruefeEskalation() {
        try {
            int max = configManager.verstoesseMaxPunkte();
            boolean aktiv = configManager.warnEskalationAktiv();
            var schwellen = configManager.warnEskalationsSchwellen();
            if (!aktiv) {
                return new Zeile("Warn-Eskalation", true, "deaktiviert (max-punkte=" + max + ")");
            }
            boolean ok = max >= 1 && schwellen != null && !schwellen.isEmpty();
            String detail = ok
                    ? "aktiv, " + schwellen.size() + " Schwellen, max-punkte=" + max
                    : "aktiv, aber keine gültigen Schwellen (warns.eskalation.schwellen prüfen)";
            return new Zeile("Warn-Eskalation", ok, detail);
        } catch (Exception e) {
            return new Zeile("Warn-Eskalation", false, "Konfiguration nicht lesbar");
        }
    }

    @Override
    public List<String> vervollstaendigen(BefehlsSender sender, String[] args) {
        return List.of();
    }
}
