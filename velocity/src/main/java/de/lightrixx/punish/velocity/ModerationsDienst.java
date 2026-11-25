package de.lightrixx.punish.velocity;

import de.lightrixx.punish.model.HistoryEntry;
import de.lightrixx.punish.model.ReportRecord;
import de.lightrixx.punish.net.Protokoll;
import de.lightrixx.punish.storage.Karten;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;

public final class ModerationsDienst {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ModerationsDienst.class);

    public record Ereignis(UUID uuid, HistoryEntry history) {
    }

    public record Eintrag(long zeit, String autor, String typ, long referenz, String text) {
        Map<String, Object> alsMap() {
            return Map.of("zeit", zeit, "autor", autor, "typ", typ, "referenz", referenz, "text", text);
        }

        static Eintrag lesen(Map<String, Object> map) {
            Objects.requireNonNull(map, "Ein Falleintrag ist unlesbar.");
            return new Eintrag(Karten.zahl(map, "zeit", 0), Karten.text(map, "autor", ""),
                    Karten.text(map, "typ", ""), Karten.zahl(map, "referenz", 0), Karten.text(map, "text", ""));
        }
    }

    public record Fall(long id, UUID uuid, String name, String titel, long erstellt, boolean geschlossen,
                       String regel, int stufe, long angewendet, List<Eintrag> eintraege) {
        public Fall {
            Objects.requireNonNull(uuid);
            if (id < 1 || name == null || name.isBlank() || titel == null || titel.isBlank()) {
                throw new IllegalArgumentException("Ungültiger Moderationsfall.");
            }
            eintraege = List.copyOf(eintraege);
        }

        Map<String, Object> alsMap() {
            return Map.ofEntries(Map.entry("id", id), Map.entry("uuid", uuid.toString()),
                    Map.entry("name", name), Map.entry("titel", titel), Map.entry("erstellt", erstellt),
                    Map.entry("geschlossen", geschlossen), Map.entry("regel", regel), Map.entry("stufe", stufe),
                    Map.entry("angewendet", angewendet),
                    Map.entry("eintraege", eintraege.stream().map(Eintrag::alsMap).toList()));
        }

        static Fall lesen(Map<String, Object> map) {
            return new Fall(Karten.zahl(map, "id", 0), UUID.fromString(Karten.text(map, "uuid", "")),
                    Karten.text(map, "name", ""), Karten.text(map, "titel", ""), Karten.zahl(map, "erstellt", 0),
                    Karten.wahr(map, "geschlossen", false), Karten.text(map, "regel", ""),
                    (int) Karten.zahl(map, "stufe", 0), Karten.zahl(map, "angewendet", 0),
                    Karten.liste(map, "eintraege").stream().map(Karten::abschnitt).map(Eintrag::lesen).toList());
        }
    }

    public enum PruefStatus { OFFEN, BERECHTIGT, FEHLALARM }

    public record Pruefung(long id, UUID uuid, String name, long zeit, String kanal, String quelle,
                           double score, String hash, String text, boolean beobachtung, PruefStatus status,
                           String bearbeiter, List<Map<String, Object>> aktionen) {
        public Pruefung {
            Objects.requireNonNull(uuid);
            Objects.requireNonNull(status);
            if (id < 1 || zeit < 1 || name == null || name.isBlank() || !Double.isFinite(score) || score < 0 || score > 1) {
                throw new IllegalArgumentException("Ungültiger Chatfilter-Prüfeintrag.");
            }
            aktionen = aktionen.stream().map(Map::copyOf).toList();
        }

        Map<String, Object> alsMap() {
            return Map.ofEntries(Map.entry("id", id), Map.entry("uuid", uuid.toString()), Map.entry("name", name),
                    Map.entry("zeit", zeit), Map.entry("kanal", kanal), Map.entry("quelle", quelle),
                    Map.entry("score", score), Map.entry("hash", hash), Map.entry("text", text),
                    Map.entry("beobachtung", beobachtung), Map.entry("status", status.name()),
                    Map.entry("bearbeiter", bearbeiter), Map.entry("aktionen", aktionen));
        }

        static Pruefung lesen(Map<String, Object> map) {
            return new Pruefung(Karten.zahl(map, "id", 0), UUID.fromString(Karten.text(map, "uuid", "")),
                    Karten.text(map, "name", ""), Karten.zahl(map, "zeit", 0), Karten.text(map, "kanal", ""),
                    Karten.text(map, "quelle", ""), Double.parseDouble(Karten.text(map, "score", "0")),
                    Karten.text(map, "hash", ""), Karten.text(map, "text", ""),
                    Karten.wahr(map, "beobachtung", false),
                    PruefStatus.valueOf(Karten.text(map, "status", "OFFEN")), Karten.text(map, "bearbeiter", ""),
                    Karten.liste(map, "aktionen").stream().map(Karten::abschnitt).map(Objects::requireNonNull).toList());
        }
    }

    private final ModerationsSpeicher fallSpeicher;
    private final ModerationsSpeicher pruefSpeicher;
    private final VelocityKonfiguration config;
    private final LongSupplier zeit;
    private final Map<Long, Fall> faelle = new LinkedHashMap<>();
    private final Map<Long, Pruefung> pruefungen = new LinkedHashMap<>();
    private final ThreadLocal<List<Ereignis>> erfassung = new ThreadLocal<>();
    private long letzteWartung;
    private Boolean letzterKlartext;

    public ModerationsDienst(ModerationsSpeicher fallSpeicher, ModerationsSpeicher pruefSpeicher,
                             VelocityKonfiguration config) {
        this(fallSpeicher, pruefSpeicher, config, System::currentTimeMillis);
    }

    ModerationsDienst(ModerationsSpeicher fallSpeicher, ModerationsSpeicher pruefSpeicher,
                       VelocityKonfiguration config, LongSupplier zeit) {
        this.fallSpeicher = Objects.requireNonNull(fallSpeicher);
        this.pruefSpeicher = Objects.requireNonNull(pruefSpeicher);
        this.config = Objects.requireNonNull(config);
        this.zeit = Objects.requireNonNull(zeit);
    }

    public synchronized void laden() {
        fallSpeicher.laden();
        pruefSpeicher.laden();
        for (var map : fallSpeicher.alle()) {
            Fall fall = Fall.lesen(map);
            faelle.put(fall.id(), fall);
        }
        for (var map : pruefSpeicher.alle()) {
            Pruefung pruefung = Pruefung.lesen(map);
            pruefungen.put(pruefung.id(), pruefung);
        }
        wartung();
    }

    public void historyNeu(UUID uuid, HistoryEntry history) {
        var liste = erfassung.get();
        if (liste != null) {
            liste.add(new Ereignis(uuid, history));
        }
    }

    public List<Ereignis> erfasse(Runnable auftrag) {
        var vorher = erfassung.get();
        List<Ereignis> liste = new ArrayList<>();
        erfassung.set(liste);
        try {
            auftrag.run();
            return List.copyOf(liste);
        } finally {
            if (vorher == null) {
                erfassung.remove();
            } else {
                vorher.addAll(liste);
                erfassung.set(vorher);
            }
        }
    }

    public synchronized Fall erstellen(UUID uuid, String name, String titel, String autor) {
        if (faelle.size() >= 20_000) {
            throw new IllegalStateException("Fall-Limit erreicht. Daten sichern und Aufbewahrung prüfen.");
        }
        Fall fall = new Fall(fallSpeicher.naechsteId(), uuid, name, text(titel, 256), zeit.getAsLong(),
                false, "", 0, 0, List.of(new Eintrag(zeit.getAsLong(), autor, "ERSTELLT", 0, titel)));
        speichern(fall);
        return fall;
    }

    public synchronized Fall fall(long id) {
        Fall fall = faelle.get(id);
        if (fall == null) {
            throw new IllegalArgumentException("Fall #" + id + " wurde nicht gefunden.");
        }
        return fall;
    }

    public synchronized List<Fall> faelle(UUID uuid) {
        return faelle.values().stream().filter(f -> uuid == null || f.uuid().equals(uuid))
                .sorted(Comparator.comparingLong(Fall::id).reversed()).toList();
    }

    public synchronized Fall eintrag(long id, String autor, String typ, long referenz, String inhalt) {
        Fall fall = fall(id);
        if (fall.geschlossen()) {
            throw new IllegalStateException("Den Fall zuerst wieder öffnen.");
        }
        if (fall.eintraege().size() >= 500) {
            throw new IllegalStateException("Der Fall enthält bereits 500 Einträge.");
        }
        if (referenz > 0 && fall.eintraege().stream().anyMatch(e -> e.typ().equals(typ) && e.referenz() == referenz)) {
            return fall;
        }
        List<Eintrag> liste = new ArrayList<>(fall.eintraege());
        liste.add(new Eintrag(zeit.getAsLong(), autor, typ, referenz, text(inhalt, 2000)));
        Fall neu = new Fall(fall.id(), fall.uuid(), fall.name(), fall.titel(), fall.erstellt(), false,
                fall.regel(), fall.stufe(), fall.angewendet(), liste);
        speichern(neu);
        return neu;
    }

    public synchronized void schliessen(long id, boolean geschlossen, String autor) {
        Fall fall = fall(id);
        if (fall.geschlossen() == geschlossen) {
            return;
        }
        List<Eintrag> liste = new ArrayList<>(fall.eintraege());
        liste.add(new Eintrag(zeit.getAsLong(), autor, "STATUS", 0, geschlossen ? "Geschlossen" : "Wieder geöffnet"));
        speichern(new Fall(fall.id(), fall.uuid(), fall.name(), fall.titel(), fall.erstellt(), geschlossen,
                fall.regel(), fall.stufe(), fall.angewendet(), liste));
    }

    public synchronized void report(ReportRecord report) {
        Fall fall = faelle.values().stream().filter(f -> f.eintraege().stream()
                .anyMatch(e -> e.typ().equals("REPORT") && e.referenz() == report.id())).findFirst().orElse(null);
        if (fall == null) {
            fall = erstellen(report.zielUuid(), report.zielName(), report.grund(), report.reporterName());
            eintrag(fall.id(), report.reporterName(), "REPORT", report.id(),
                    "Report #" + report.id() + " auf " + report.server() + ": " + report.grund());
        } else {
            systemEintrag(fall.id(), report.bearbeiter().isBlank() ? "System" : report.bearbeiter(), "REPORT-STATUS", 0,
                    "Report #" + report.id() + ": " + report.status().name() + " " + report.closeGrund());
        }
    }

    private void systemEintrag(long id, String autor, String typ, long referenz, String text) {
        Fall f = fall(id);
        if (f.eintraege().size() >= 500) {
            LOG.warn("Systemverweis {} #{} passt nicht mehr in Fall #{}; Spielerakte prüfen.", typ, referenz, id);
            return;
        }
        var eintraege = new ArrayList<>(f.eintraege());
        eintraege.add(new Eintrag(zeit.getAsLong(), autor, typ, referenz, text));
        speichern(new Fall(f.id(), f.uuid(), f.name(), f.titel(), f.erstellt(), f.geschlossen(),
                f.regel(), f.stufe(), f.angewendet(), eintraege));
    }

    public synchronized void korrekturVerknuepfen(UUID uuid, long ursprung, List<Ereignis> ereignisse) {
        for (var f : faelle(uuid)) {
            if (f.eintraege().stream().noneMatch(e -> e.typ().equals("HISTORY") && e.referenz() == ursprung)) {
                continue;
            }
            for (var e : ereignisse) {
                if (uuid.equals(e.uuid())) {
                    var h = e.history();
                    systemEintrag(f.id(), h.ausfuehrer(), "KORREKTUR", h.id(),
                            "Korrektur zu History #" + ursprung + ": " + h.typ().anzeigename() + " · " + h.grund());
                }
            }
        }
    }

    public synchronized Fall historyVerknuepfen(long id, Ereignis ereignis) {
        Fall fall = fall(id);
        if (!fall.uuid().equals(ereignis.uuid())) {
            throw new IllegalArgumentException("Die Strafe gehört zu einem anderen Spieler.");
        }
        HistoryEntry h = ereignis.history();
        return eintrag(id, h.ausfuehrer(), "HISTORY", h.id(),
                h.typ().anzeigename() + " (#" + h.id() + "): " + h.grund() + " [" + h.scope() + "]");
    }

    public synchronized long anzahlRegel(UUID uuid, String regel, long fensterMillis) {
        long seit = zeit.getAsLong() - fensterMillis;
        return faelle.values().stream().filter(f -> f.uuid().equals(uuid) && f.regel().equals(regel)
                && f.angewendet() >= seit && f.angewendet() > 0).count();
    }

    public synchronized Fall katalogAngewendet(UUID uuid, String name, Strafkatalog.Regel regel,
                                               int stufe, long fallId, String autor, List<Ereignis> ereignisse) {
        Fall fall = fallId > 0 ? fall(fallId) : erstellen(uuid, name, regel.grund(), autor);
        if (!fall.uuid().equals(uuid) || fall.geschlossen() || !fall.regel().isBlank()) {
            throw new IllegalStateException("Der Fall kann keine weitere Katalog-Strafe aufnehmen.");
        }
        for (var ereignis : ereignisse) {
            if (uuid.equals(ereignis.uuid())) {
                fall = historyVerknuepfen(fall.id(), ereignis);
            }
        }
        Fall neu = new Fall(fall.id(), uuid, name, fall.titel(), fall.erstellt(), false,
                regel.id(), stufe, zeit.getAsLong(), fall.eintraege());
        speichern(neu);
        return neu;
    }

    public synchronized Pruefung treffer(UUID uuid, String name, Protokoll.ChatfilterTreffer treffer) {
        wartung();
        if (pruefungen.size() >= 20_000) {
            long aeltesteId = pruefungen.keySet().iterator().next();
            pruefungen.remove(aeltesteId);
            pruefSpeicher.entfernenWenn(m -> Karten.zahl(m, "id", 0) == aeltesteId);
        }
        Pruefung p = new Pruefung(pruefSpeicher.naechsteId(), uuid, name, zeit.getAsLong(), treffer.kanal(),
                treffer.quelle(), treffer.bewertung(), treffer.textHash(),
                config.chatfilterKlartextUbertragen() ? kuerzen(treffer.text(), 2000) : "",
                config.chatfilterBeobachten() || treffer.beobachtung(), PruefStatus.OFFEN, "", List.of());
        speichern(p);
        return p;
    }

    public synchronized Pruefung pruefung(long id) {
        wartung();
        Pruefung p = pruefungen.get(id);
        if (p == null) {
            throw new IllegalArgumentException("Prüfeintrag #" + id + " wurde nicht gefunden oder ist abgelaufen.");
        }
        return p;
    }

    public synchronized List<Pruefung> pruefungen(UUID uuid) {
        wartung();
        return pruefungen.values().stream().filter(p -> uuid == null || uuid.equals(p.uuid()))
                .sorted(Comparator.comparingLong(Pruefung::id).reversed()).toList();
    }

    public synchronized void pruefen(long id, PruefStatus status, String autor) {
        Pruefung p = pruefung(id);
        if (p.status() == PruefStatus.FEHLALARM && status != PruefStatus.FEHLALARM) {
            throw new IllegalArgumentException("Ein Fehlalarm bleibt aus der Staffel entfernt.");
        }
        speichern(new Pruefung(p.id(), p.uuid(), p.name(), p.zeit(), p.kanal(), p.quelle(), p.score(), p.hash(),
                p.text(), p.beobachtung(), status, autor, p.aktionen()));
    }

    public synchronized void aktion(long id, int schwelle, long fenster, List<Long> belege,
                                    Map<String, Object> zielDaten, List<Ereignis> ereignisse) {
        Pruefung p = pruefung(id);
        List<Map<String, Object>> aktionen = new ArrayList<>(p.aktionen());
        for (var e : ereignisse) {
            HistoryEntry h = e.history();
            if (!p.uuid().equals(e.uuid())) {
                continue;
            }
            Map<String, Object> a = new LinkedHashMap<>(Map.ofEntries(Map.entry("history", h.id()), Map.entry("typ", h.typ().name()),
                    Map.entry("zeit", h.zeitstempel()), Map.entry("grund", h.grund()),
                    Map.entry("ablauf", h.ablauf() == null ? 0L : h.ablauf()), Map.entry("scope", h.scope()),
                    Map.entry("schwelle", schwelle), Map.entry("fenster", fenster)));
            a.putAll(zielDaten);
            a.put("treffer-ids", List.copyOf(belege));
            aktionen.add(Map.copyOf(a));
        }
        speichern(new Pruefung(p.id(), p.uuid(), p.name(), p.zeit(), p.kanal(), p.quelle(), p.score(), p.hash(),
                p.text(), p.beobachtung(), p.status(), p.bearbeiter(), aktionen));
    }

    public synchronized boolean korrekturNoetig(Pruefung ausloeser, Map<String, Object> aktion) {
        List<Object> belege = Karten.liste(aktion, "treffer-ids");
        if (!belege.isEmpty()) {
            long verbleibend = 0;
            for (Object roh : belege) {
                Pruefung p = pruefungen.get(Long.parseLong(String.valueOf(roh)));
                if (p == null || !p.uuid().equals(ausloeser.uuid())) {
                    return false;
                }
                if (!p.beobachtung() && p.status() != PruefStatus.FEHLALARM) {
                    verbleibend++;
                }
            }
            return verbleibend < Karten.zahl(aktion, "schwelle", 1);
        }
        return false;
    }

    public synchronized void wartung() {
        long jetzt = zeit.getAsLong();
        boolean klartext = config.chatfilterKlartextUbertragen();
        if (letzterKlartext != null && letzterKlartext == klartext && jetzt - letzteWartung < 60_000L) {
            return;
        }
        letzteWartung = jetzt;
        letzterKlartext = klartext;
        long seit = jetzt - config.chatfilterPruefungAufbewahrungTage() * 86_400_000L;
        pruefungen.values().removeIf(p -> p.zeit() < seit);
        pruefSpeicher.entfernenWenn(m -> Karten.zahl(m, "zeit", 0) < seit);
        if (!config.chatfilterKlartextUbertragen()) {
            for (var p : List.copyOf(pruefungen.values())) {
                if (!p.text().isEmpty()) {
                    speichern(new Pruefung(p.id(), p.uuid(), p.name(), p.zeit(), p.kanal(), p.quelle(), p.score(), p.hash(),
                            "", p.beobachtung(), p.status(), p.bearbeiter(), p.aktionen()));
                }
            }
        }
    }

    private void speichern(Fall fall) {
        faelle.put(fall.id(), fall);
        fallSpeicher.speichern(fall.id(), fall.alsMap());
    }

    private void speichern(Pruefung pruefung) {
        pruefungen.put(pruefung.id(), pruefung);
        pruefSpeicher.speichern(pruefung.id(), pruefung.alsMap());
    }

    public static String beweisLink(String roh) {
        String link = text(roh, 1500);
        URI uri;
        try {
            uri = URI.create(link);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Ein gültiger HTTPS-Beweislink ist erforderlich.", e);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("Ein gültiger HTTPS-Beweislink ist erforderlich.");
        }
        return link;
    }

    static String text(String roh, int maximum) {
        if (roh == null || roh.isBlank() || roh.length() > maximum || roh.chars().anyMatch(c -> c < 32)) {
            throw new IllegalArgumentException("Text fehlt, ist zu lang oder enthält Steuerzeichen (max. " + maximum + ").");
        }
        return roh.trim();
    }

    private static String kuerzen(String roh, int maximum) {
        if (roh == null) {
            return "";
        }
        return roh.length() <= maximum ? roh : roh.substring(0, maximum);
    }
}
