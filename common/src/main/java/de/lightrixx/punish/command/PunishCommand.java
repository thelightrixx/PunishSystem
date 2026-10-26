package de.lightrixx.punish.command;

import de.lightrixx.punish.core.AuftragsPlaner;
import de.lightrixx.punish.core.BefehlsSender;
import de.lightrixx.punish.core.Konfiguration;
import de.lightrixx.punish.core.MessageKeys;
import de.lightrixx.punish.core.OnlineSpieler;
import de.lightrixx.punish.core.Permissions;
import de.lightrixx.punish.model.CachedPlayer;
import de.lightrixx.punish.service.MessageService;
import de.lightrixx.punish.service.ProtectionService;
import de.lightrixx.punish.storage.PlayerCache;
import de.lightrixx.punish.util.TabHinweis;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public abstract class PunishCommand {

    protected static final Logger LOG = LoggerFactory.getLogger(PunishCommand.class);

    /**
     * @deprecated Nutze {@link de.lightrixx.punish.service.Limits#MAX_GRUND_LAENGE}.
     */
    @Deprecated
    public static final int MAX_GRUND_LAENGE =
            de.lightrixx.punish.service.Limits.MAX_GRUND_LAENGE;

    protected static final String BESTAETIGUNGS_ARGUMENT = "confirm";

    protected final MessageService messageService;
    protected final Konfiguration configManager;
    protected final ProtectionService protectionService;
    protected final PlayerCache playerCache;
    protected final de.lightrixx.punish.core.SpielerVerzeichnis verzeichnis;

    protected final AuftragsPlaner taskScheduler;
    private volatile de.lightrixx.punish.storage.IoExecutor ioExecutor;

    protected PunishCommand(MessageService messageService,
                            Konfiguration configManager,
                            ProtectionService protectionService,
                            PlayerCache playerCache) {
        this(messageService, configManager, protectionService, playerCache, null, null);
    }

    protected PunishCommand(MessageService messageService,
                            Konfiguration configManager,
                            ProtectionService protectionService,
                            PlayerCache playerCache,
                            AuftragsPlaner taskScheduler) {
        this(messageService, configManager, protectionService, playerCache, taskScheduler, null);
    }

    protected PunishCommand(MessageService messageService,
                            Konfiguration configManager,
                            ProtectionService protectionService,
                            PlayerCache playerCache,
                            AuftragsPlaner taskScheduler,
                            de.lightrixx.punish.core.SpielerVerzeichnis verzeichnis) {
        this.messageService = Objects.requireNonNull(messageService);
        this.configManager = Objects.requireNonNull(configManager);
        this.protectionService = Objects.requireNonNull(protectionService);
        this.playerCache = Objects.requireNonNull(playerCache);
        this.taskScheduler = taskScheduler;
        this.verzeichnis = verzeichnis;
    }

    protected abstract String recht();

    public abstract void ausfuehren(BefehlsSender sender, String[] args);

    public abstract List<String> vervollstaendigen(BefehlsSender sender, String[] args);

    public boolean hatRecht(BefehlsSender sender, String[] args) {
        return sender != null && sender.hatRecht(recht());
    }

    public boolean sichtbarFuer(BefehlsSender sender) {
        return hatRecht(sender, new String[0]);
    }

    public boolean unterstuetztScope() {
        return true;
    }

    protected String[] ohneScopeFlags(String[] args) {
        if (args == null || args.length == 0) {
            return args == null ? new String[0] : args;
        }
        try {
            return ScopeParser.strippeScopeFlags(args, bekannteServer(), true);
        } catch (Exception e) {
            return args;
        }
    }

    protected List<String> namen() {
        return List.of();
    }

    protected String ausfuehrerName(BefehlsSender sender) {
        if (sender == null) {
            return "Konsole";
        }
        String name;
        try {
            name = sender.name();
        } catch (Exception e) {
            name = null;
        }
        return name == null || name.isBlank() ? "Konsole" : name;
    }

    protected Optional<UUID> eigeneUuid(BefehlsSender sender) {
        if (sender == null || !sender.istSpieler()) {
            return Optional.empty();
        }
        return Optional.ofNullable(sender.spielerUuid());
    }

    public void setIoExecutor(de.lightrixx.punish.storage.IoExecutor ioExecutor) {
        this.ioExecutor = ioExecutor;
    }

    /**
     * Bricht Massenaktionen ab, solange der I/O-Executor bereits einen zu großen Rückstau hat.
     * Das begrenzt das Queue-Wachstum an der Quelle, statt Schreibvorgänge zu verwerfen.
     */
    protected boolean blockiereBeiIoRueckstau(BefehlsSender sender) {
        de.lightrixx.punish.storage.IoExecutor io = ioExecutor;
        if (io == null || !io.istUeberlastet()) {
            return false;
        }
        messageService.senden(sender, MessageKeys.MASSEN_IO_RUECKSTAU, Map.of(
                "ausstehend", String.valueOf(io.anzahlAusstehend()),
                "max", String.valueOf(de.lightrixx.punish.storage.IoExecutor.UEBERLAST_SCHWELLE)));
        return true;
    }

    protected Optional<CachedPlayer> zielAufloesen(String name) {
        if (name == null || name.isBlank()) return Optional.empty();

        String getrimmt = name.trim();
        if (getrimmt.length() == 36 && getrimmt.indexOf('-') >= 0) {
            try {
                UUID direkt = UUID.fromString(getrimmt);
                Optional<CachedPlayer> gecacht = playerCache.finden(direkt);
                if (gecacht.isPresent()) return gecacht;
                if (verzeichnis != null) {
                    try {
                        var extern = verzeichnis.onlineSpieler(direkt);
                        if (extern.isPresent()) {
                            OnlineSpieler o = extern.get();
                            CachedPlayer neu = new CachedPlayer(o.uuid(), o.name(), o.ip(),
                                    System.currentTimeMillis());
                            try {
                                playerCache.aktualisieren(neu);
                            } catch (Exception e) {
                                LOG.debug("Auto-Cache (UUID-online) fehlgeschlagen.", e);
                            }
                            return Optional.of(neu);
                        }
                    } catch (Exception e) {
                        LOG.debug("Online-UUID-Lookup für '{}' fehlgeschlagen.", getrimmt, e);
                    }

                    try {
                        if (playerCache != null) {

                            for (CachedPlayer kandidat : playerCache.alle()) {
                                if (kandidat != null && direkt.equals(kandidat.uuid())) {
                                    return Optional.of(kandidat);
                                }
                            }
                        }
                    } catch (Exception e) {
                        LOG.debug("Cache-UUID-Scan für '{}' fehlgeschlagen.", getrimmt, e);
                    }
                }

                return Optional.empty();
            } catch (IllegalArgumentException e) {
                LOG.trace("Kein UUID-Format, Fallback auf Namenssuche.", e);
            } catch (Exception e) {
                LOG.warn("UUID-Auflösung für '{}' fehlgeschlagen, fallback auf Namens-Pfad.", getrimmt, e);
            }
        }
        OnlineSpieler online = null;
        if (verzeichnis != null) {
            try {
                online = verzeichnis.onlineSpielerNachName(getrimmt).orElse(null);
                if (online == null) {
                    for (OnlineSpieler kandidat : verzeichnis.onlineSpieler()) {
                        if (kandidat != null && kandidat.name() != null
                                && kandidat.name().equalsIgnoreCase(getrimmt)) {
                            online = kandidat;
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                LOG.warn("Online-Spieler-Scan für '{}' fehlgeschlagen, Offline-Pfad wird genutzt.", getrimmt, e);
            }
        }
        if (online != null) {
            Optional<CachedPlayer> gecacht = playerCache.finden(online.uuid());
            if (gecacht.isPresent()) return gecacht;
            return Optional.of(new CachedPlayer(online.uuid(), online.name(), online.ip(),
                    System.currentTimeMillis()));
        }
        Optional<CachedPlayer> gecacht = playerCache.findenNachName(name);
        if (gecacht.isPresent() && playerCache.istFrischeNamensZuordnung(name)) {
            return gecacht;
        }

        return playerCache.findeExternOffline(name);
    }

    protected boolean schutzFehlerSenden(BefehlsSender sender, CachedPlayer ziel,
                                         ProtectionService.SchutzErgebnis ergebnis) {
        if (ergebnis == ProtectionService.SchutzErgebnis.GESCHUETZT) {
            messageService.senden(sender, MessageKeys.GESCHUETZT);
            return true;
        }
        if (ergebnis == ProtectionService.SchutzErgebnis.UNSICHER) {

            if (protectionService.istFailClosed()) {
                messageService.senden(sender, MessageKeys.SCHUTZ_OFFLINE_UNBEKANNT,
                        Map.of("spieler", ziel.name()));
                return true;
            }
            return false;
        }
        return false;
    }

    protected void mitSchutz(BefehlsSender sender, String[] args, CachedPlayer ziel, Runnable fortsetzung) {
        mitSchutz(sender, args, ziel, null, fortsetzung);
    }

    protected void mitSchutz(BefehlsSender sender, String[] args, CachedPlayer ziel,
                             ProtectionService.IgnoreTyp typ, Runnable fortsetzung) {
        mitSchutz(sender, args, ziel, typ, null, fortsetzung);
    }

    protected void mitSchutz(BefehlsSender sender, String[] args, CachedPlayer ziel,
                             ProtectionService.IgnoreTyp typ, String rechtTyp, Runnable fortsetzung) {
        Objects.requireNonNull(fortsetzung, "Die Fortsetzung darf nicht null sein.");
        if (protectionService.istSelbstbestrafung(sender, ziel.uuid())) {
            messageService.senden(sender, MessageKeys.SELBSTBESTRAFUNG);
            return;
        }
        try {
            if (protectionService.istRangBlockiert(sender, ziel.uuid())) {
                messageService.senden(sender, MessageKeys.GESCHUETZT);
                return;
            }
        } catch (Exception e) {
            LOG.debug("Rang-Hierarchie-Check übersprungen.", e);
        }
        java.util.Optional<ProtectionService.SchutzErgebnis> sofort;
        try {
            sofort = typ == null
                    ? protectionService.pruefeSchutzSofort(ziel.uuid())
                    : protectionService.pruefeSchutzSofort(ziel.uuid(), typ);
        } catch (Exception e) {
            sofort = java.util.Optional.of(ProtectionService.SchutzErgebnis.UNSICHER);
        }
        if (sofort.isPresent()) {
            if (schutzFehlerSenden(sender, ziel, sofort.get())) {
                return;
            }
            fortsetzung.run();
            return;
        }
        if (taskScheduler == null) {

            if (schutzFehlerSenden(sender, ziel, ProtectionService.SchutzErgebnis.UNSICHER)) {
                return;
            }
            fortsetzung.run();
            return;
        }
        messageService.senden(sender, MessageKeys.SCHUTZ_PRUEFUNG_LAEUFT, Map.of("spieler", ziel.name()));
        try {
            taskScheduler.aufgabeAsync(() -> {
                ProtectionService.SchutzErgebnis async;
                try {
                    async = typ == null
                            ? protectionService.pruefeSchutz(ziel.uuid())
                            : protectionService.pruefeSchutz(ziel.uuid(), typ);
                } catch (Exception e) {
                    async = ProtectionService.SchutzErgebnis.UNSICHER;
                }
                ProtectionService.SchutzErgebnis ergebnis = async;
                try {
                    taskScheduler.aufgabe(() -> {
                        boolean erlaubt = rechtTyp == null
                                ? hatRecht(sender, args)
                                : hatRecht(sender, new String[]{rechtTyp});
                        if (!erlaubt) {
                            messageService.senden(sender, MessageKeys.KEIN_RECHT);
                            return;
                        }
                        if (schutzFehlerSenden(sender, ziel, ergebnis)) {
                            return;
                        }

                        fortsetzung.run();
                    });
                } catch (IllegalStateException e) {
                    messageService.senden(sender, MessageKeys.AKTION_FEHLGESCHLAGEN);
                }
            });
        } catch (IllegalStateException e) {
            LOG.debug("Schutzprüfung konnte nicht geplant werden.", e);
            if (!schutzFehlerSenden(sender, ziel, ProtectionService.SchutzErgebnis.UNSICHER)) {
                messageService.senden(sender, MessageKeys.AKTION_FEHLGESCHLAGEN);
            }
        }
    }

    protected List<String> filtere(List<String> vorschlaege, String aktuellesArg) {
        if (vorschlaege == null || vorschlaege.isEmpty()) {
            return List.of();
        }
        if (aktuellesArg == null || aktuellesArg.isEmpty()) {
            List<String> sauber = new ArrayList<>(vorschlaege.size());
            for (String vorschlag : vorschlaege) {
                if (vorschlag != null) {
                    sauber.add(vorschlag);
                }
            }
            return sauber;
        }
        String vergleich = aktuellesArg.toLowerCase(Locale.ROOT);
        List<String> ergebnis = new ArrayList<>();
        for (String vorschlag : vorschlaege) {
            if (vorschlag == null) {
                continue;
            }
            if (vorschlag.toLowerCase(Locale.ROOT).startsWith(vergleich)) {
                ergebnis.add(vorschlag);
            }
        }
        return ergebnis;
    }

    protected List<String> alleSpielerNamen() {
        return alleSpielerNamen(50);
    }

    protected List<String> alleSpielerNamen(int limit) {
        if (limit <= 0) {
            return List.of();
        }

        List<String> alle = playerCache.alleNamen().stream().filter(Objects::nonNull).distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList();
        if (alle.size() <= limit) {
            return new ArrayList<>(alle);
        }
        return new ArrayList<>(alle.subList(0, limit));
    }

    protected List<String> spielerNamenMitPrefix(String prefix, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        String vergleich = prefix == null ? "" : prefix.trim().toLowerCase(Locale.ROOT);
        List<String> treffer = new ArrayList<>();
        for (String name : playerCache.alleNamen()) {
            if (name == null) {
                continue;
            }
            if (vergleich.isEmpty() || name.toLowerCase(Locale.ROOT).startsWith(vergleich)) {
                treffer.add(name);
            }
            if (treffer.size() >= limit) {
                break;
            }
        }
        treffer.sort(String.CASE_INSENSITIVE_ORDER);
        return treffer.stream().distinct().toList();
    }

    public String tooltipFuer(String vorschlag, String[] args) {
        return kategorieTooltip(vorschlag);
    }

    public static String kategorieTooltip(String vorschlag) {
        return de.lightrixx.punish.util.TabHinweis.kategorieTooltip(vorschlag);
    }

    public static boolean dauerFoermig(String vorschlag) {
        return de.lightrixx.punish.util.TabHinweis.dauerFoermig(vorschlag);
    }

    protected String tooltipFuerVorlage(String befehlSchluessel, String vorschlag) {
        if (vorschlag == null || vorschlag.isBlank() || befehlSchluessel == null || befehlSchluessel.isBlank()) {
            return "";
        }
        try {
            String dauer = configManager.empfohleneDauer(befehlSchluessel, vorschlag.trim());
            return TabHinweis.tooltipText(vorschlag, dauer);
        } catch (Exception e) {
            LOG.debug("Tooltip-Vorlage konnte nicht gelesen werden.", e);
            return "";
        }
    }

    protected List<String> bestrafbareSpielerNamen() {
        return bestrafbareSpielerNamen(null);
    }

    protected List<String> bestrafbareSpielerNamen(ProtectionService.IgnoreTyp typ) {
        Set<String> namen = new LinkedHashSet<>();
        if (verzeichnis != null) {
            try {
                for (OnlineSpieler online : verzeichnis.onlineSpieler()) {
                    if (online == null) {
                        continue;
                    }
                    try {
                        boolean geschuetzt = typ == null
                                ? protectionService.istOnlineGeschuetzt(online)
                                : protectionService.istOnlineGeschuetzt(online, typ);
                        if (geschuetzt) {
                            continue;
                        }
                    } catch (Exception e) {
                        LOG.warn("Online-Schutzcheck für Tab-Complete fehlgeschlagen, überspringe.", e);
                        continue;
                    }
                    namen.add(online.name());
                    if (namen.size() >= 50) {
                        break;
                    }
                }
            } catch (Exception e) {
                LOG.warn("Online-Scan für Tab-Complete fehlgeschlagen.", e);
            }
        }
        List<String> ergebnis = new ArrayList<>(namen);
        ergebnis.sort(String.CASE_INSENSITIVE_ORDER);
        return ergebnis;
    }

    protected List<String> bestrafbareSpielerVorschlaege(BefehlsSender sender, String prefix) {
        return bestrafbareSpielerVorschlaege(sender, prefix, null);
    }

    protected List<String> bestrafbareSpielerVorschlaege(BefehlsSender sender, String prefix,
                                                        ProtectionService.IgnoreTyp typ) {
        String eigenerName = null;
        if (sender != null) {
            try {
                eigenerName = sender.istSpieler() ? sender.name() : null;
            } catch (Exception e) {
                eigenerName = null;
            }
        }
        List<String> ergebnis = new ArrayList<>();
        for (String name : bestrafbareSpielerNamen(typ)) {
            if (name == null || name.isBlank()) {
                continue;
            }
            if (eigenerName != null && name.equalsIgnoreCase(eigenerName.trim())) {
                continue;
            }
            ergebnis.add(name);
        }
        return TabHinweis.begrenzeDistinct(TabHinweis.filtere(ergebnis, prefix == null ? "" : prefix.trim()), 50);
    }

    protected List<String> spielerVorschlaegeSmart(BefehlsSender sender, String prefix) {
        Set<String> kombiniert = new LinkedHashSet<>(bestrafbareSpielerVorschlaege(sender, prefix));
        for (String offline : offlineSpielerVorschlaege(prefix)) {
            if (offline == null || offline.isBlank()) {
                continue;
            }
            boolean schon = false;
            for (String kandidat : kombiniert) {
                if (kandidat != null && kandidat.equalsIgnoreCase(offline)) {
                    schon = true;
                    break;
                }
            }
            if (!schon) {
                kombiniert.add(offline);
            }
        }
        List<String> ergebnis = new ArrayList<>(kombiniert);
        ergebnis.sort(String.CASE_INSENSITIVE_ORDER);
        return TabHinweis.begrenzeDistinct(ergebnis, 50);
    }

    protected List<String> nurEnde(BefehlsSender sender, String aktuelles) {
        List<String> flags = new ArrayList<>(scopeVorschlaege("--"));
        flags.addAll(silentVorschlag(sender, ""));
        return TabHinweis.filtere(flags, aktuelles);
    }

    protected List<String> grundOderEnde(BefehlsSender sender, String[] args, int grundStartIndex,
                                         String grundSchluessel) {
        String aktuelles = args == null || args.length == 0 ? "" : args[args.length - 1];
        if (TabHinweis.hatGrundBegonnen(args, grundStartIndex)) {
            return nurEnde(sender, aktuelles);
        }
        List<String> gemischt = new ArrayList<>(grundVorschlaege(grundSchluessel));
        gemischt.addAll(scopeVorschlaege("--"));
        gemischt.addAll(silentVorschlag(sender, ""));
        return TabHinweis.filtere(gemischt, aktuelles);
    }

    protected List<String> offlineSpielerVorschlaege(String prefix) {
        if (prefix == null || prefix.trim().length() < 3) {
            return List.of();
        }
        String vergleich = prefix.trim().toLowerCase(Locale.ROOT);
        List<String> treffer = new ArrayList<>(25);
        for (CachedPlayer spieler : playerCache.alle()) {
            if (spieler.name().toLowerCase(Locale.ROOT).startsWith(vergleich)) {
                treffer.add(spieler.name());
                if (treffer.size() >= 25) {
                    break;
                }
            }
        }
        return treffer;
    }

    protected List<String> dauerVorschlaege() {
        return configManager.tabDauerListe();
    }

    protected List<String> dauerVorschlaegeFuer(String befehl) {
        try {
            return configManager.dauerListe(befehl);
        } catch (Exception e) {
            LOG.debug("Befehls-Dauern für '{}' fehlgeschlagen, nutze globale Liste.", befehl, e);
            return dauerVorschlaege();
        }
    }

    protected List<String> dauerVorschlaegeOhnePermFuer(String befehl) {
        List<String> vorschlaege = new ArrayList<>();
        for (String eintrag : dauerVorschlaegeFuer(befehl)) {
            if (eintrag == null) {
                continue;
            }
            String normiert = eintrag.trim().toLowerCase(Locale.ROOT);
            if (normiert.equals("perm") || normiert.equals("permanent") || normiert.equals("perma")) {
                continue;
            }
            vorschlaege.add(eintrag);
        }
        return vorschlaege;
    }

    protected List<String> dauerVorschlaegeOhnePerm() {
        List<String> vorschlaege = new ArrayList<>();
        for (String eintrag : dauerVorschlaege()) {
            if (eintrag == null) {
                continue;
            }
            String normiert = eintrag.trim().toLowerCase(Locale.ROOT);
            if (normiert.equals("perm") || normiert.equals("permanent") || normiert.equals("perma")) {
                continue;
            }
            vorschlaege.add(eintrag);
        }
        return vorschlaege;
    }

    protected List<String> aktiveStrafNamen(int limit, java.util.Collection<String> aktiveNamen) {
        if (limit <= 0 || aktiveNamen == null || aktiveNamen.isEmpty()) {
            return List.of();
        }
        Set<String> treffer = new LinkedHashSet<>();
        for (String name : aktiveNamen) {
            if (name == null || name.isBlank()) {
                continue;
            }
            treffer.add(name);
            if (treffer.size() >= limit) {
                break;
            }
        }
        return new ArrayList<>(treffer);
    }

    protected Optional<CachedPlayer> zielAufloesenFuerTab(String ip) {
        if (ip == null || ip.isBlank()) {
            return Optional.empty();
        }
        String gesucht = ip.trim();
        for (CachedPlayer kandidat : playerCache.alle()) {
            if (kandidat == null || kandidat.letzteIp() == null) {
                continue;
            }
            if (kandidat.letzteIp().trim().equalsIgnoreCase(gesucht)) {
                return Optional.of(kandidat);
            }
        }
        return Optional.empty();
    }

    protected boolean istAblaufMoeglich(de.lightrixx.punish.model.ParseResult parsed) {
        return parsed instanceof de.lightrixx.punish.model.ParseResult.Geltzeit;
    }

    protected boolean istAblaufMoeglich(long dauerMillis) {
        return dauerMillis > 0 && dauerMillis <= Long.MAX_VALUE - System.currentTimeMillis() - 1000L;
    }

    protected List<String> spielerVorschlaege(String prefix) {
        Set<String> kombiniert = new LinkedHashSet<>(bestrafbareSpielerNamen());
        kombiniert.addAll(offlineSpielerVorschlaege(prefix));
        return new ArrayList<>(kombiniert);
    }

    protected List<String> grundVorschlaege() {
        return configManager.tabGrundListe();
    }

    protected List<String> grundVorschlaege(String aktion) {
        if (aktion == null || aktion.isBlank()) {
            return grundVorschlaege();
        }
        if (aktion.equalsIgnoreCase("note") || aktion.equalsIgnoreCase("punish-note")) {
            return List.of();
        }
        try {
            return configManager.grundListe(aktion);
        } catch (Exception e) {
            LOG.debug("Befehls-Gründe für '{}' fehlgeschlagen, nutze globale Liste.", aktion, e);
            return grundVorschlaege();
        }
    }

    protected String grundAusArgumenten(String[] args, int startIndex, String standardSchluessel) {
        if (args.length <= startIndex) {
            return configManager.standardGrund(standardSchluessel);
        }
        String grund = normalisiereGrund(grundAusArgumentenOhneStandard(args, startIndex));
        if (grund.isBlank()) {
            return configManager.standardGrund(standardSchluessel);
        }
        return grund;
    }

    protected String grundAusArgumentenOhneStandard(String[] args, int startIndex) {
        if (args.length <= startIndex) return "";
        StringBuilder grund = new StringBuilder();
        for (int i = startIndex; i < args.length; i++) {
            if (!grund.isEmpty()) grund.append(" ");
            grund.append(args[i]);
        }
        return normalisiereGrund(grund.toString());
    }

    public static String normalisiereGrund(String roh) {
        if (roh == null) {
            return "";
        }
        String normalisiert = roh.trim().replaceAll("\\s+", " ");
        if (normalisiert.length() > de.lightrixx.punish.service.Limits.MAX_GRUND_LAENGE) {
            normalisiert = normalisiert.substring(0, de.lightrixx.punish.service.Limits.MAX_GRUND_LAENGE);
        }
        return normalisiert;
    }

    public static boolean wurdeGekuerzt(String roh) {
        if (roh == null) {
            return false;
        }
        String normalisiert = roh.trim().replaceAll("\\s+", " ");
        return normalisiert.length() > de.lightrixx.punish.service.Limits.MAX_GRUND_LAENGE;
    }

    protected void sendeKuerzungsHinweisWennNoetig(BefehlsSender sender, String roh) {
        try {
            if (sender == null || roh == null || !wurdeGekuerzt(roh)) {
                return;
            }
            java.util.Map<String, String> platzhalter = new java.util.HashMap<>();
            platzhalter.put("max", String.valueOf(de.lightrixx.punish.service.Limits.MAX_GRUND_LAENGE));
            messageService.senden(sender, MessageKeys.GRUND_GEKUERZT, platzhalter);
        } catch (Exception e) {
            LOG.debug("Kürzungs-Hinweis konnte nicht gesendet werden.", e);
        }
    }

    protected de.lightrixx.punish.model.ServerScope standardScope() {
        try {
            return configManager.standardScope();
        } catch (Exception e) {
            return de.lightrixx.punish.model.ServerScope.unbekannt();
        }
    }

    protected String herkunftServer() {
        try {
            return configManager.serverName();
        } catch (Exception e) {
            return de.lightrixx.punish.model.ServerScope.UNBEKANNT_WERT;
        }
    }

    protected String herkunftVon(BefehlsSender sender) {
        if (sender != null) {
            try {
                String h = sender.herkunft();
                if (h != null && !h.isBlank()) {
                    return h.trim().toLowerCase(Locale.ROOT);
                }
            } catch (Exception e) {
                LOG.debug("Sender-Herkunft fehlgeschlagen, nutze Config-Fallback.", e);
            }
        }
        return herkunftServer();
    }

    protected java.util.List<String> bekannteServer() {
        try {
            return configManager.bekannteServer();
        } catch (Exception e) {
            return java.util.List.of();
        }
    }

    protected ScopeParser.Ergebnis scopeAusArgs(String[] args) {
        ScopeParser.Ergebnis ergebnis = ScopeParser.parsen(args == null ? new String[0] : args,
                standardScope(), bekannteServer());
        warneBeiUnbekanntemScope(ergebnis);
        return ergebnis;
    }

    protected ScopeParser.Ergebnis scopeAusArgsMitUnbekannt(String[] args) {
        ScopeParser.Ergebnis ergebnis = ScopeParser.parsen(args == null ? new String[0] : args,
                standardScope(), bekannteServer(), true);
        warneBeiUnbekanntemScope(ergebnis);
        return ergebnis;
    }

    protected boolean darfSilent(BefehlsSender sender) {
        return sender != null && sender.hatRecht(Permissions.ALLOW_SILENT.node());
    }

    protected boolean silentAuswerten(BefehlsSender sender, ScopeParser.Ergebnis ergebnis) {
        if (ergebnis == null || !ergebnis.silent()) {
            return false;
        }
        if (darfSilent(sender)) {
            return true;
        }
        try {
            messageService.senden(sender, MessageKeys.SILENT_OHNE_RECHT);
        } catch (Exception e) {
            LOG.debug("Silent-Hinweis konnte nicht gesendet werden.", e);
        }
        return false;
    }

    protected ScopeParser.SilentErgebnis silentAusArgsOhneScope(BefehlsSender sender, String[] args) {
        String[] roh = args == null ? new String[0] : args;
        ScopeParser.SilentErgebnis silent = ScopeParser.parsenSilent(roh);
        String[] ohneScope = ohneScopeFlags(silent.bereinigteArgs());

        boolean wirksam = silent.silent();
        if (wirksam && !darfSilent(sender)) {
            try {
                messageService.senden(sender, MessageKeys.SILENT_OHNE_RECHT);
            } catch (Exception e) {
                LOG.debug("Silent-Hinweis konnte nicht gesendet werden.", e);
            }
            wirksam = false;
        }
        return new ScopeParser.SilentErgebnis(ohneScope, wirksam);
    }

    protected List<String> silentVorschlag(BefehlsSender sender, String aktuellesArg) {
        if (!darfSilent(sender)) {
            return List.of();
        }
        if (aktuellesArg == null || aktuellesArg.isEmpty() || "-s".startsWith(aktuellesArg)) {
            return List.of("-s");
        }
        return List.of();
    }

    private void warneBeiUnbekanntemScope(ScopeParser.Ergebnis ergebnis) {
        try {
            if (ergebnis == null || !ergebnis.hatteFlag() || ergebnis.scope() == null) {
                return;
            }
            if (ergebnis.scope().typ() != de.lightrixx.punish.model.ServerScope.Typ.SERVER) {
                return;
            }
            java.util.List<String> bekannt = bekannteServer();
            if (bekannt == null || bekannt.isEmpty()) {
                return;
            }
            String neu = ergebnis.scope().server();
            boolean treffer = false;
            for (String b : bekannt) {
                if (b != null && b.trim().equalsIgnoreCase(neu)) {
                    treffer = true;
                    break;
                }
            }
            if (!treffer) {
                LOG.warn("Scope '{}' ist kein bekannter Server {} – Strafe wirkt dort "
                        + "ggf. nirgends (Tippfehler? --survial statt --survival?).",
                        neu, bekannt);
            }
        } catch (Exception e) {
            LOG.debug("Scope-Warncheck fehlgeschlagen.", e);
        }
    }

    protected boolean istUnbekannterServerScope(ScopeParser.Ergebnis ergebnis) {
        try {
            if (ergebnis == null || !ergebnis.hatteFlag() || ergebnis.scope() == null) {
                return false;
            }
            if (ergebnis.scope().typ() != de.lightrixx.punish.model.ServerScope.Typ.SERVER) {
                return false;
            }
            java.util.List<String> bekannt = bekannteServer();
            if (bekannt == null || bekannt.isEmpty()) {
                return false;
            }
            String neu = ergebnis.scope().server();
            for (String b : bekannt) {
                if (b != null && b.trim().equalsIgnoreCase(neu)) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    protected boolean blockiereBeiUnbekanntemScope(de.lightrixx.punish.core.BefehlsSender sender,
                                                   ScopeParser.Ergebnis ergebnis) {
        if (!istUnbekannterServerScope(ergebnis)) {
            return false;
        }
        sendeScopeWarnungWennNoetig(sender, ergebnis);
        return true;
    }

    protected void sendeScopeWarnungWennNoetig(de.lightrixx.punish.core.BefehlsSender sender,
                                               ScopeParser.Ergebnis ergebnis) {
        try {
            if (sender == null || ergebnis == null || !ergebnis.hatteFlag() || ergebnis.scope() == null) {
                return;
            }
            if (ergebnis.scope().typ() != de.lightrixx.punish.model.ServerScope.Typ.SERVER) {
                return;
            }
            java.util.List<String> bekannt = bekannteServer();
            if (bekannt == null || bekannt.isEmpty()) {
                return;
            }
            String neu = ergebnis.scope().server();
            for (String b : bekannt) {
                if (b != null && b.trim().equalsIgnoreCase(neu)) {
                    return;
                }
            }
            java.util.Map<String, String> platzhalter = new java.util.HashMap<>();
            platzhalter.put("scope", neu == null ? "" : neu);
            platzhalter.put("bekannt", String.join(", ", bekannt));
            try {
                messageService.senden(sender,
                        de.lightrixx.punish.core.MessageKeys.SCOPE_UNBEKANNT_WARNUNG, platzhalter);
            } catch (Exception e) {
                LOG.debug("Scope-Ingame-Warnung konnte nicht gesendet werden.", e);
            }
        } catch (Exception e) {
            LOG.debug("Scope-Warncheck (Sender) fehlgeschlagen.", e);
        }
    }

    protected List<String> scopeVorschlaege(String aktuellesArg) {
        return ScopeParser.vorschlaege(aktuellesArg, bekannteServer());
    }

    protected List<String> scopeVorschlaegeMitUnbekannt(String aktuellesArg) {
        return ScopeParser.vorschlaege(aktuellesArg, bekannteServer(), true);
    }

    protected int ipGeteiltWarnAb() {
        try {
            return Math.max(1, configManager.ipGeteiltWarnAb());
        } catch (Exception e) {
            return 4;
        }
    }

    protected int ipGeteiltBlockAb() {
        try {
            return Math.max(2, configManager.ipGeteiltBlockAb());
        } catch (Exception e) {
            return 11;
        }
    }

    protected int ipGeteiltBestaetigungAb() {
        try {
            int wert = configManager.ipGeteiltBestaetigungAb();
            return wert <= 0 ? Integer.MAX_VALUE : Math.max(1, wert);
        } catch (Exception e) {
            return 2;
        }
    }

    protected boolean istBestaetigt(String[] bereinigt) {
        return bereinigt != null && bereinigt.length >= 2
                && BESTAETIGUNGS_ARGUMENT.equalsIgnoreCase(bereinigt[1]);
    }

    protected String[] ohneBestaetigung(String[] bereinigt) {
        if (!istBestaetigt(bereinigt)) {
            return bereinigt;
        }
        String[] neu = new String[bereinigt.length - 1];
        neu[0] = bereinigt[0];
        System.arraycopy(bereinigt, 2, neu, 1, bereinigt.length - 2);
        return neu;
    }

    protected boolean pruefeGeteilteIp(BefehlsSender sender, String befehlLabel, String[] args,
                                       String kanonischeIp, int betroffen, boolean bestaetigt) {
        if (betroffen >= ipGeteiltBlockAb()) {
            messageService.senden(sender, MessageKeys.IP_GETEILT_BLOCK, Map.of("ip", kanonischeIp));
            return false;
        }
        if (betroffen >= ipGeteiltBestaetigungAb() && !bestaetigt) {
            sendeIpBestaetigung(sender, befehlLabel, args, kanonischeIp, betroffen);
            return false;
        }
        return true;
    }

    private void sendeIpBestaetigung(BefehlsSender sender, String befehlLabel, String[] args,
                                     String kanonischeIp, int betroffen) {
        StringBuilder befehl = new StringBuilder("/").append(befehlLabel);
        if (args != null && args.length > 0 && args[0] != null) {
            befehl.append(" ").append(args[0].trim());
        } else {
            befehl.append(" ").append(kanonischeIp);
        }
        befehl.append(" ").append(BESTAETIGUNGS_ARGUMENT);
        if (args != null) {
            for (int i = 1; i < args.length; i++) {
                if (args[i] != null && !args[i].isBlank()) {
                    befehl.append(" ").append(args[i].trim());
                }
            }
        }
        try {
            Component hinweis = messageService.komponente(MessageKeys.BESTAETIGUNG_NOETIG, Map.of());
            Component umfang = Component.text("Betroffen sind " + betroffen
                    + " Online-Spieler dieser IP (" + kanonischeIp + ").",
                    NamedTextColor.YELLOW);
            Component klickbar = Component.text(befehl.toString(), NamedTextColor.WHITE)
                    .clickEvent(ClickEvent.suggestCommand(befehl.toString()));
            messageService.sendeKomponente(sender, hinweis.append(Component.newline()).append(umfang)
                    .append(Component.newline()).append(klickbar));
        } catch (Exception e) {
            LOG.debug("IP-Bestätigungsaufforderung konnte nicht gesendet werden.", e);
        }
    }
}
