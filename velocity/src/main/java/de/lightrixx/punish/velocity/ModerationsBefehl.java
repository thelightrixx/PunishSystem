package de.lightrixx.punish.velocity;

import de.lightrixx.punish.command.PunishCommand;
import de.lightrixx.punish.core.AuftragsPlaner;
import de.lightrixx.punish.core.BefehlsSender;
import de.lightrixx.punish.core.SpielerVerzeichnis;
import de.lightrixx.punish.model.HistoryType;
import de.lightrixx.punish.model.ServerScope;
import de.lightrixx.punish.service.HistoryService;
import de.lightrixx.punish.service.MessageService;
import de.lightrixx.punish.service.MuteService;
import de.lightrixx.punish.service.ProtectionService;
import de.lightrixx.punish.service.ReportService;
import de.lightrixx.punish.service.WarnService;
import de.lightrixx.punish.storage.Karten;
import de.lightrixx.punish.storage.PlayerCache;
import de.lightrixx.punish.storage.StoreLock;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ModerationsBefehl extends PunishCommand {

    public enum Bereich { FALL, PRUEFUNG }

    private record Korrektur(String token, long pruefung, long history, long ablauf) {
    }

    private final Bereich bereich;
    private final ModerationsDienst dienst;
    private final HistoryService history;
    private final ReportService reports;
    private final ChatfilterStaffelService staffel;
    private final WarnService warns;
    private final MuteService mutes;
    private final Map<String, Korrektur> korrekturen = new ConcurrentHashMap<>();

    public ModerationsBefehl(Bereich bereich, MessageService texte, VelocityKonfiguration config,
                             ProtectionService schutz, PlayerCache cache, AuftragsPlaner planer,
                             SpielerVerzeichnis verzeichnis, ModerationsDienst dienst, HistoryService history,
                             ReportService reports, ChatfilterStaffelService staffel, WarnService warns, MuteService mutes) {
        super(texte, config, schutz, cache, planer, verzeichnis);
        this.bereich = bereich;
        this.dienst = dienst;
        this.history = history;
        this.reports = reports;
        this.staffel = staffel;
        this.warns = warns;
        this.mutes = mutes;
    }

    @Override
    protected String recht() {
        return bereich == Bereich.FALL ? "punish.fall" : "punish.chatfilter.pruefung";
    }

    @Override
    public boolean unterstuetztScope() {
        return false;
    }

    @Override
    public void ausfuehren(BefehlsSender sender, String[] args) {
        if (!hatRecht(sender, args)) {
            hinweis(sender, "Keine Berechtigung.");
            return;
        }
        try {
            if (bereich == Bereich.FALL) {
                fall(sender, args);
            } else {
                pruefung(sender, args);
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            hinweis(sender, e.getMessage());
        }
    }

    private void fall(BefehlsSender sender, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
            UUID uuid = args.length > 1 ? zielAufloesen(args[1])
                    .orElseThrow(() -> new IllegalArgumentException("Spieler nicht gefunden.")).uuid() : null;
            var faelle = dienst.faelle(uuid);
            int seite = args.length > 2 ? seite(args[2]) : 1;
            hinweis(sender, "Fälle: " + faelle.size() + " · Seite " + seite + ". /punish-fall list [Spieler] [Seite]");
            for (var f : faelle.stream().skip((seite - 1L) * 10).limit(10).toList()) {
                klick(sender, "#" + f.id() + " · " + f.name() + " · " + f.titel()
                        + (f.geschlossen() ? " · geschlossen" : " · offen"), "/punish-fall info " + f.id());
            }
            return;
        }
        if (args[0].equalsIgnoreCase("create") && args.length >= 3) {
            bearbeiten(sender);
            var ziel = zielAufloesen(args[1]).orElseThrow(() -> new IllegalArgumentException("Spieler nicht gefunden."));
            var fall = dienst.erstellen(ziel.uuid(), ziel.name(), rest(args, 2), ausfuehrerName(sender));
            hinweis(sender, "Fall #" + fall.id() + " angelegt.");
            return;
        }
        if (args.length < 2) {
            hinweis(sender, "/punish-fall list|info|create|notiz|beweis|report|strafe|close|reopen …");
            return;
        }
        long id = positiveId(args[1]);
        var f = dienst.fall(id);
        switch (args[0].toLowerCase(java.util.Locale.ROOT)) {
            case "info" -> {
                int seite = args.length > 2 ? seite(args[2]) : 1;
                hinweis(sender, "Fall #" + id + " · " + f.name() + " · " + f.titel() + " · "
                        + (f.geschlossen() ? "geschlossen" : "offen") + " · Seite " + seite);
                for (var e : f.eintraege().stream().skip((seite - 1L) * 10).limit(10).toList()) {
                    hinweis(sender, de.lightrixx.punish.util.ZeitFormat.formatZeitpunkt(e.zeit())
                            + " · " + e.autor() + " · " + e.typ() + ": " + e.text());
                }
            }
            case "notiz", "beweis" -> {
                bearbeiten(sender);
                String text = rest(args, 2);
                boolean beweis = args[0].equalsIgnoreCase("beweis");
                dienst.eintrag(id, ausfuehrerName(sender), beweis ? "BEWEIS" : "NOTIZ", 0,
                        beweis ? ModerationsDienst.beweisLink(text) : text);
                hinweis(sender, "Eintrag in Fall #" + id + " gespeichert.");
            }
            case "strafe" -> {
                bearbeiten(sender);
                if (!sender.hatRecht("punish.history") || args.length != 3) {
                    throw new IllegalArgumentException("Erforderlich: punish.history und /punish-fall strafe <Fall> <History-ID>.");
                }
                long historyId = positiveId(args[2]);
                var eintrag = history.findeSpielerHistory(f.uuid(), Integer.MAX_VALUE).stream()
                        .filter(h -> h.id() == historyId).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Kein History-Eintrag dieses Spielers mit dieser ID."));
                dienst.historyVerknuepfen(id, new ModerationsDienst.Ereignis(f.uuid(), eintrag));
                hinweis(sender, "History-Eintrag mit Fall #" + id + " verknüpft.");
            }
            case "report" -> {
                bearbeiten(sender);
                if (!sender.hatRecht("punish.reports") || args.length != 3) {
                    throw new IllegalArgumentException("Erforderlich: punish.reports und /punish-fall report <Fall> <Report-ID>.");
                }
                var report = reports.findenNachId(positiveId(args[2]))
                        .orElseThrow(() -> new IllegalArgumentException("Report nicht gefunden."));
                if (!f.uuid().equals(report.zielUuid())) {
                    throw new IllegalArgumentException("Der Report gehört zu einem anderen Spieler.");
                }
                dienst.eintrag(id, ausfuehrerName(sender), "REPORT", report.id(),
                        "Report #" + report.id() + ": " + report.grund() + " · " + report.status().name());
                hinweis(sender, "Report mit Fall #" + id + " verknüpft.");
            }
            case "close", "reopen" -> {
                bearbeiten(sender);
                dienst.schliessen(id, args[0].equalsIgnoreCase("close"), ausfuehrerName(sender));
                hinweis(sender, "Fallstatus geändert.");
            }
            default -> throw new IllegalArgumentException("Unbekannter Fall-Unterbefehl.");
        }
    }

    private void pruefung(BefehlsSender sender, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
            UUID uuid = args.length > 1 && !args[1].equalsIgnoreCase("alle") ? zielAufloesen(args[1])
                    .orElseThrow(() -> new IllegalArgumentException("Spieler nicht gefunden.")).uuid() : null;
            boolean alle = args.length > 1 && args[1].equalsIgnoreCase("alle");
            int seite = args.length > 2 ? seite(args[2]) : 1;
            var liste = dienst.pruefungen(uuid).stream().filter(p -> alle || p.status() == ModerationsDienst.PruefStatus.OFFEN).toList();
            hinweis(sender, "Chatfilter-Prüfung: " + liste.size() + " · Seite " + seite
                    + ". /chatfilter-pruefung list [alle|Spieler] [Seite]");
            for (var p : liste.stream().skip((seite - 1L) * 10).limit(10).toList()) {
                klick(sender, "#" + p.id() + " · " + p.name() + " · " + p.quelle() + " · " + p.status().name(),
                        "/chatfilter-pruefung info " + p.id());
            }
            return;
        }
        if (args[0].equalsIgnoreCase("confirm") && args.length == 2) {
            bestaetigeKorrektur(sender, args[1]);
            return;
        }
        if (args.length < 2) {
            throw new IllegalArgumentException("/chatfilter-pruefung list|info|berechtigt|fehlalarm|korrektur …");
        }
        var p = dienst.pruefung(positiveId(args[1]));
        switch (args[0].toLowerCase(java.util.Locale.ROOT)) {
            case "info" -> {
                hinweis(sender, "Treffer #" + p.id() + " · " + p.name() + " · " + p.kanal() + " · " + p.quelle()
                        + " · Score " + p.score() + " · " + p.status() + (p.beobachtung() ? " · Beobachtungsmodus" : ""));
                hinweis(sender, ((VelocityKonfiguration) configManager).chatfilterKlartextUbertragen() && !p.text().isBlank()
                        ? p.text() : "Hash: " + p.hash());
                if (sender.hatRecht(recht() + ".bearbeiten")) {
                    klick(sender, "[Berechtigt]", "/chatfilter-pruefung berechtigt " + p.id());
                    klick(sender, "[Als Fehlalarm markieren]", "/chatfilter-pruefung fehlalarm " + p.id());
                }
                korrekturenAnzeigen(sender, p.uuid());
            }
            case "berechtigt", "fehlalarm" -> {
                bearbeiten(sender);
                boolean falsch = args[0].equalsIgnoreCase("fehlalarm");
                synchronized (dienst) {
                    dienst.pruefen(p.id(), falsch ? ModerationsDienst.PruefStatus.FEHLALARM : ModerationsDienst.PruefStatus.BERECHTIGT,
                            ausfuehrerName(sender));
                    if (falsch) {
                        staffel.entferneTreffer(p.uuid(), p.id());
                    }
                }
                hinweis(sender, falsch ? "Fehlalarm gespeichert und aus der Staffelzählung entfernt." : "Treffer als berechtigt markiert.");
                korrekturenAnzeigen(sender, p.uuid());
            }
            case "korrektur" -> {
                bearbeiten(sender);
                if (args.length != 3) {
                    throw new IllegalArgumentException("/chatfilter-pruefung korrektur <Treffer-ID> <History-ID>");
                }
                long historyId = positiveId(args[2]);
                var aktion = aktion(p, historyId);
                pruefeKorrektur(sender, p, aktion);
                korrekturen.entrySet().removeIf(e -> e.getValue().ablauf() < System.currentTimeMillis());
                if (korrekturen.size() >= 1000) {
                    throw new IllegalStateException("Zu viele offene Korrektur-Bestätigungen.");
                }
                String token = UUID.randomUUID().toString().substring(0, 12);
                korrekturen.put(sender.abstandsSchluessel(), new Korrektur(token, p.id(), historyId, System.currentTimeMillis() + 60_000L));
                hinweis(sender, "Aufheben: " + Karten.text(aktion, "typ", "") + " für " + p.name() + ": "
                        + Karten.text(aktion, "grund", "") + ". Verknüpfte Folgeaktionen müssen gesondert geprüft werden.");
                klick(sender, "[Korrektur bestätigen – 60 Sekunden]", "/chatfilter-pruefung confirm " + token);
            }
            default -> throw new IllegalArgumentException("Unbekannter Prüf-Unterbefehl.");
        }
    }

    private void korrekturenAnzeigen(BefehlsSender sender, UUID uuid) {
        for (var p : dienst.pruefungen(uuid)) {
            for (var aktion : p.aktionen()) {
                if (!dienst.korrekturNoetig(p, aktion)) {
                    continue;
                }
                String typ = Karten.text(aktion, "typ", "");
                if (typ.equals("WARN_ADD") || typ.equals("MUTE")) {
                    klick(sender, "[Korrektur prüfen: " + typ + ", History #" + Karten.zahl(aktion, "history", 0) + "]",
                            "/chatfilter-pruefung korrektur " + p.id() + " " + Karten.zahl(aktion, "history", 0));
                } else {
                    hinweis(sender, "Verknüpfte Folgeaktion: " + typ + " · History #" + Karten.zahl(aktion, "history", 0));
                }
            }
        }
    }

    private Map<String, Object> aktion(ModerationsDienst.Pruefung p, long historyId) {
        return p.aktionen().stream().filter(a -> Karten.zahl(a, "history", 0) == historyId).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Die Aktion gehört nicht zu diesem Treffer."));
    }

    private void pruefeKorrektur(BefehlsSender sender, ModerationsDienst.Pruefung p, Map<String, Object> aktion) {
        bearbeiten(sender);
        if (!dienst.korrekturNoetig(p, aktion)) {
            throw new IllegalArgumentException("Für diese Aktion ist keine belegte Korrektur nötig.");
        }
        String typ = Karten.text(aktion, "typ", "");
        String grund = Karten.text(aktion, "grund", "");
        long erstellt = Karten.zahl(aktion, "erstellt", 0);
        if (typ.equals("WARN_ADD")) {
            if (!sender.hatRecht("punish.warn") || warns.findeWarns(p.uuid()).stream().noneMatch(w ->
                    w.nummer() == Karten.zahl(aktion, "warn-nummer", 0) && w.erstelltAm() == erstellt
                            && w.ausfuehrer().equals("Chatfilter") && w.grund().equals(grund))) {
                throw new IllegalArgumentException("Verwarnung wurde geändert/entfernt oder punish.warn fehlt.");
            }
        } else if (typ.equals("MUTE")) {
            var mute = mutes.finden(p.uuid(), ServerScope.ausSpeicherwert(Karten.text(aktion, "scope", "")));
            if (!sender.hatRecht("punish.unmute") || mute.isEmpty() || mute.get().erstelltAm() != erstellt
                    || !mute.get().ausfuehrer().equals("Chatfilter") || !mute.get().grund().equals(grund)
                    || mute.get().ablaufAm() != Karten.zahl(aktion, "ablauf", 0)
                    || !mute.get().istAktiv(System.currentTimeMillis())) {
                throw new IllegalArgumentException("Mute wurde geändert/entfernt, ist abgelaufen oder punish.unmute fehlt.");
            }
        } else {
            throw new IllegalArgumentException("Diese Folgeaktion bitte über die Spielerakte prüfen und korrigieren.");
        }
    }

    private void bestaetigeKorrektur(BefehlsSender sender, String token) {
        var k = korrekturen.get(sender.abstandsSchluessel());
        if (k == null || !k.token().equals(token) || k.ablauf() < System.currentTimeMillis()
                || !korrekturen.remove(sender.abstandsSchluessel(), k)) {
            throw new IllegalArgumentException("Bestätigung fehlt oder ist abgelaufen.");
        }
        synchronized (dienst) {
            StoreLock.LOCK.writeLock().lock();
            try {
                var p = dienst.pruefung(k.pruefung());
                var aktion = aktion(p, k.history());
                pruefeKorrektur(sender, p, aktion);
                boolean[] ok = {false};
                var ereignisse = dienst.erfasse(() -> {
                    if (Karten.text(aktion, "typ", "").equals("WARN_ADD")) {
                        ok[0] = warns.entfernen(p.uuid(), p.name(), (int) Karten.zahl(aktion, "warn-nummer", 0), ausfuehrerName(sender)).isPresent();
                    } else {
                        ok[0] = mutes.entmuten(p.uuid(), p.name(), ausfuehrerName(sender),
                                ServerScope.ausSpeicherwert(Karten.text(aktion, "scope", "")), herkunftVon(sender));
                    }
                });
                if (ok[0]) {
                    dienst.korrekturVerknuepfen(p.uuid(), k.history(), ereignisse);
                }
                hinweis(sender, ok[0] ? "Korrektur ausgeführt und im Strafverlauf dokumentiert." : "Die Aktion konnte nicht aufgehoben werden.");
            } finally {
                StoreLock.LOCK.writeLock().unlock();
            }
        }
    }

    private void bearbeiten(BefehlsSender sender) {
        if (!sender.hatRecht(recht() + ".bearbeiten")) {
            throw new IllegalArgumentException("Erforderliches Recht fehlt: " + recht() + ".bearbeiten");
        }
    }

    private static void hinweis(BefehlsSender sender, String text) {
        StrafkatalogBefehl.hinweis(sender, text);
    }

    private static void klick(BefehlsSender sender, String text, String befehl) {
        sender.nachricht(Component.text(text, NamedTextColor.YELLOW).clickEvent(ClickEvent.runCommand(befehl)));
    }

    private static String rest(String[] args, int ab) {
        return ModerationsDienst.text(String.join(" ", Arrays.copyOfRange(args, ab, args.length)), 2000);
    }

    private static long positiveId(String text) {
        long id = Long.parseLong(text);
        if (id < 1) {
            throw new IllegalArgumentException("Die ID muss positiv sein.");
        }
        return id;
    }

    private static int seite(String text) {
        long seite = positiveId(text);
        if (seite > 100_000) {
            throw new IllegalArgumentException("Ungültige Seite.");
        }
        return (int) seite;
    }

    @Override
    public List<String> vervollstaendigen(BefehlsSender sender, String[] args) {
        if (args.length <= 1) {
            return filtere(bereich == Bereich.FALL
                    ? List.of("list", "info", "create", "notiz", "beweis", "report", "strafe", "close", "reopen")
                    : List.of("list", "info", "berechtigt", "fehlalarm", "korrektur"), args.length == 0 ? "" : args[0]);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("create") || args[0].equalsIgnoreCase("list"))) {
            return spielerVorschlaegeSmart(sender, args[1]);
        }
        return List.of();
    }
}
