package de.lightrixx.punish.service;

import de.lightrixx.punish.core.BefehlsSender;
import de.lightrixx.punish.core.OfflineVerzeichnis;
import de.lightrixx.punish.core.OnlineSpieler;
import de.lightrixx.punish.core.Permissions;
import de.lightrixx.punish.core.SpielerVerzeichnis;
import de.lightrixx.punish.hook.LuckPermsZugang;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class ProtectionService {

    private static final Logger LOG = LoggerFactory.getLogger(ProtectionService.class);

    private volatile boolean failClosed = true;

    private final SpielerVerzeichnis verzeichnis;
    private final OfflineVerzeichnis offlineVerzeichnis;
    private final LuckPermsZugang luckPerms;

    public ProtectionService(SpielerVerzeichnis verzeichnis,
                             OfflineVerzeichnis offlineVerzeichnis,
                             LuckPermsZugang luckPerms) {
        this.verzeichnis = Objects.requireNonNull(verzeichnis, "Das Spielerverzeichnis darf nicht null sein.");
        this.offlineVerzeichnis = Objects.requireNonNull(offlineVerzeichnis,
                "Das Offline-Verzeichnis darf nicht null sein.");
        this.luckPerms = Objects.requireNonNull(luckPerms, "Der LuckPerms-Zugang darf nicht null sein.");
    }

    public void setFailClosed(boolean failClosed) {
        this.failClosed = failClosed;
    }

    public boolean istFailClosed() {
        return failClosed;
    }

    public enum SchutzErgebnis {
        NICHT_GESCHUETZT,
        GESCHUETZT,
        UNSICHER
    }

    public enum IgnoreTyp {
        BAN(Permissions.IGNORE_BAN),
        MUTE(Permissions.IGNORE_MUTE),
        TIMEOUT(Permissions.IGNORE_TIMEOUT),
        FREEZE(Permissions.IGNORE_FREEZE),
        KICK(Permissions.IGNORE_KICK),
        WARN(Permissions.IGNORE_WARN),
        VERSTOSS(Permissions.IGNORE_VERSTOSS),
        NOTE(Permissions.IGNORE_NOTE),
        ALTS(Permissions.IGNORE_ALTS),
        REPORT(Permissions.IGNORE_REPORT);

        private final Permissions permission;

        IgnoreTyp(Permissions permission) {
            this.permission = permission;
        }

        public String node() {
            return permission.node();
        }

        public Permissions permission() {
            return permission;
        }
    }

    public boolean istGeschuetzt(OnlineSpieler spieler) {
        return istOnlineGeschuetzt(spieler);
    }

    public boolean istGeschuetzt(OnlineSpieler spieler, IgnoreTyp typ) {
        Objects.requireNonNull(spieler, "Der Spieler darf nicht null sein.");
        Objects.requireNonNull(typ, "Der Ignore-Typ darf nicht null sein.");
        return istOnlineGeschuetzt(spieler, typ);
    }

    public boolean istGeschuetzt(UUID zielUuid) {
        Objects.requireNonNull(zielUuid, "Die Ziel-UUID darf nicht null sein.");
        SchutzErgebnis ergebnis;
        try {
            ergebnis = pruefeSchutz(zielUuid);
        } catch (Exception e) {

            ergebnis = SchutzErgebnis.UNSICHER;
        }
        return blockiertBei(ergebnis, failClosed);
    }

    public boolean istGeschuetzt(UUID zielUuid, IgnoreTyp typ) {
        Objects.requireNonNull(zielUuid, "Die Ziel-UUID darf nicht null sein.");
        Objects.requireNonNull(typ, "Der Ignore-Typ darf nicht null sein.");
        SchutzErgebnis ergebnis;
        try {
            ergebnis = pruefeSchutz(zielUuid, typ);
        } catch (Exception e) {
            ergebnis = SchutzErgebnis.UNSICHER;
        }
        return blockiertBei(ergebnis, failClosed);
    }

    public SchutzErgebnis pruefeSchutz(UUID zielUuid) {
        Objects.requireNonNull(zielUuid, "Die Ziel-UUID darf nicht null sein.");

        Optional<OnlineSpieler> onlineSpieler;
        try {
            onlineSpieler = verzeichnis.onlineSpieler(zielUuid);
        } catch (Exception e) {
            return SchutzErgebnis.UNSICHER;
        }
        if (onlineSpieler.isPresent()) {
            return istOnlineGeschuetzt(onlineSpieler.get()) ? SchutzErgebnis.GESCHUETZT : SchutzErgebnis.NICHT_GESCHUETZT;
        }

        Optional<OfflineVerzeichnis.ExternSpieler> offlineSpieler;
        try {
            offlineSpieler = offlineVerzeichnis.findeNachUuid(zielUuid);
        } catch (Exception e) {
            return SchutzErgebnis.UNSICHER;
        }
        if (offlineSpieler.isEmpty() || istNieGesehen(offlineSpieler.get())) {
            return SchutzErgebnis.NICHT_GESCHUETZT;
        }

        if (!luckPerms.isAvailable()) {
            return SchutzErgebnis.UNSICHER;
        }
        java.util.List<String> nodes = new java.util.ArrayList<>(IgnoreTyp.values().length + 1);
        nodes.add(Permissions.IGNORE_STAR.node());
        for (IgnoreTyp typ : IgnoreTyp.values()) {
            nodes.add(typ.node());
        }
        java.util.List<LuckPermsZugang.OfflineStatus> statusListe =
                luckPerms.offlineStatusFuerMehrere(zielUuid, nodes);
        boolean unsicher = false;
        for (LuckPermsZugang.OfflineStatus status : statusListe) {
            if (status == LuckPermsZugang.OfflineStatus.GESCHUETZT) {
                return SchutzErgebnis.GESCHUETZT;
            }
            if (status == LuckPermsZugang.OfflineStatus.UNBEKANNT) {
                unsicher = true;
            }
        }
        return unsicher ? SchutzErgebnis.UNSICHER : SchutzErgebnis.NICHT_GESCHUETZT;
    }

    public SchutzErgebnis pruefeSchutz(UUID zielUuid, IgnoreTyp typ) {
        Objects.requireNonNull(zielUuid, "Die Ziel-UUID darf nicht null sein.");
        Objects.requireNonNull(typ, "Der Ignore-Typ darf nicht null sein.");

        Optional<OnlineSpieler> onlineSpieler;
        try {
            onlineSpieler = verzeichnis.onlineSpieler(zielUuid);
        } catch (Exception e) {
            return SchutzErgebnis.UNSICHER;
        }
        if (onlineSpieler.isPresent()) {
            return istOnlineGeschuetzt(onlineSpieler.get(), typ) ? SchutzErgebnis.GESCHUETZT : SchutzErgebnis.NICHT_GESCHUETZT;
        }

        Optional<OfflineVerzeichnis.ExternSpieler> offlineSpieler;
        try {
            offlineSpieler = offlineVerzeichnis.findeNachUuid(zielUuid);
        } catch (Exception e) {
            return SchutzErgebnis.UNSICHER;
        }
        if (offlineSpieler.isEmpty() || istNieGesehen(offlineSpieler.get())) {
            return SchutzErgebnis.NICHT_GESCHUETZT;
        }

        if (!luckPerms.isAvailable()) {
            return SchutzErgebnis.UNSICHER;
        }
        java.util.List<LuckPermsZugang.OfflineStatus> statusListe = luckPerms.offlineStatusFuerMehrere(
                zielUuid, java.util.List.of(Permissions.IGNORE_STAR.node(), typ.node()));
        return kombiniereOfflineStatus(statusListe.get(0), statusListe.get(1));
    }

    static SchutzErgebnis entscheideOfflineSchutz(LuckPermsZugang.OfflineStatus status) {
        if (status == null) {
            return SchutzErgebnis.UNSICHER;
        }
        return switch (status) {
            case GESCHUETZT -> SchutzErgebnis.GESCHUETZT;
            case NICHT_GESCHUETZT -> SchutzErgebnis.NICHT_GESCHUETZT;
            case UNBEKANNT -> SchutzErgebnis.UNSICHER;
        };
    }

    static boolean blockiertBei(SchutzErgebnis ergebnis, boolean failClosed) {
        if (ergebnis == null || ergebnis == SchutzErgebnis.NICHT_GESCHUETZT) {
            return false;
        }
        if (ergebnis == SchutzErgebnis.GESCHUETZT) {
            return true;
        }
        return failClosed;
    }

    static SchutzErgebnis kombiniereOfflineStatus(LuckPermsZugang.OfflineStatus wildcard,
                                                      LuckPermsZugang.OfflineStatus spezifisch) {
        if (wildcard == LuckPermsZugang.OfflineStatus.GESCHUETZT
                || spezifisch == LuckPermsZugang.OfflineStatus.GESCHUETZT) {
            return SchutzErgebnis.GESCHUETZT;
        }
        if (wildcard == LuckPermsZugang.OfflineStatus.NICHT_GESCHUETZT
                && spezifisch == LuckPermsZugang.OfflineStatus.NICHT_GESCHUETZT) {
            return SchutzErgebnis.NICHT_GESCHUETZT;
        }
        return SchutzErgebnis.UNSICHER;
    }

    public java.util.Optional<SchutzErgebnis> pruefeSchutzSofort(UUID zielUuid) {
        Objects.requireNonNull(zielUuid, "Die Ziel-UUID darf nicht null sein.");
        try {
            Optional<OnlineSpieler> onlineSpieler = verzeichnis.onlineSpieler(zielUuid);
            if (onlineSpieler.isPresent()) {
                return java.util.Optional.of(istOnlineGeschuetzt(onlineSpieler.get())
                        ? SchutzErgebnis.GESCHUETZT
                        : SchutzErgebnis.NICHT_GESCHUETZT);
            }
            Optional<OfflineVerzeichnis.ExternSpieler> offlineSpieler =
                    offlineVerzeichnis.findeNachUuid(zielUuid);
            if (offlineSpieler.isEmpty() || istNieGesehen(offlineSpieler.get())) {
                return java.util.Optional.of(SchutzErgebnis.NICHT_GESCHUETZT);
            }
            if (!luckPerms.isAvailable()) {
                return java.util.Optional.of(SchutzErgebnis.UNSICHER);
            }
            if (!offlineVerzeichnis.istMainThread()) {
                return java.util.Optional.of(pruefeSchutz(zielUuid));
            }
            LuckPermsZugang.OfflineStatus wildcard =
                    luckPerms.offlineStatusCachedOnly(zielUuid, Permissions.IGNORE_STAR.node());
            if (wildcard == LuckPermsZugang.OfflineStatus.GESCHUETZT) {
                return java.util.Optional.of(SchutzErgebnis.GESCHUETZT);
            }
            boolean unsicher = wildcard == LuckPermsZugang.OfflineStatus.UNBEKANNT;
            for (IgnoreTyp typ : IgnoreTyp.values()) {
                LuckPermsZugang.OfflineStatus status =
                        luckPerms.offlineStatusCachedOnly(zielUuid, typ.node());
                if (status == LuckPermsZugang.OfflineStatus.GESCHUETZT) {
                    return java.util.Optional.of(SchutzErgebnis.GESCHUETZT);
                }
                if (status == LuckPermsZugang.OfflineStatus.UNBEKANNT) {
                    unsicher = true;
                }
            }
            if (unsicher) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(SchutzErgebnis.NICHT_GESCHUETZT);
        } catch (Exception e) {
            return java.util.Optional.of(SchutzErgebnis.UNSICHER);
        }
    }

    public java.util.Optional<SchutzErgebnis> pruefeSchutzSofort(UUID zielUuid, IgnoreTyp typ) {
        Objects.requireNonNull(zielUuid, "Die Ziel-UUID darf nicht null sein.");
        Objects.requireNonNull(typ, "Der Ignore-Typ darf nicht null sein.");
        try {
            Optional<OnlineSpieler> onlineSpieler = verzeichnis.onlineSpieler(zielUuid);
            if (onlineSpieler.isPresent()) {
                return java.util.Optional.of(istOnlineGeschuetzt(onlineSpieler.get(), typ)
                        ? SchutzErgebnis.GESCHUETZT
                        : SchutzErgebnis.NICHT_GESCHUETZT);
            }
            Optional<OfflineVerzeichnis.ExternSpieler> offlineSpieler =
                    offlineVerzeichnis.findeNachUuid(zielUuid);
            if (offlineSpieler.isEmpty() || istNieGesehen(offlineSpieler.get())) {
                return java.util.Optional.of(SchutzErgebnis.NICHT_GESCHUETZT);
            }
            if (!luckPerms.isAvailable()) {
                return java.util.Optional.of(SchutzErgebnis.UNSICHER);
            }
            if (!offlineVerzeichnis.istMainThread()) {
                return java.util.Optional.of(pruefeSchutz(zielUuid, typ));
            }
            LuckPermsZugang.OfflineStatus wildcard =
                    luckPerms.offlineStatusCachedOnly(zielUuid, Permissions.IGNORE_STAR.node());
            LuckPermsZugang.OfflineStatus spezifisch =
                    luckPerms.offlineStatusCachedOnly(zielUuid, typ.node());
            SchutzErgebnis ergebnis = kombiniereOfflineStatus(wildcard, spezifisch);
            if (ergebnis == SchutzErgebnis.UNSICHER) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(ergebnis);
        } catch (Exception e) {
            return java.util.Optional.of(SchutzErgebnis.UNSICHER);
        }
    }

    public boolean istOnlineGeschuetzt(OnlineSpieler spieler) {
        Objects.requireNonNull(spieler, "Der Spieler darf nicht null sein.");
        try {
            if (verzeichnis.hatRecht(spieler.uuid(), Permissions.IGNORE_STAR.node())) {
                return true;
            }
            for (IgnoreTyp typ : IgnoreTyp.values()) {
                try {
                    if (verzeichnis.hatRecht(spieler.uuid(), typ.node())) {
                        return true;
                    }
                } catch (Exception e) {
                    return failClosed;
                }
            }
            return false;
        } catch (Exception e) {
            return failClosed;
        }
    }

    public boolean istOnlineGeschuetzt(OnlineSpieler spieler, IgnoreTyp typ) {
        Objects.requireNonNull(spieler, "Der Spieler darf nicht null sein.");
        Objects.requireNonNull(typ, "Der Ignore-Typ darf nicht null sein.");
        try {
            if (verzeichnis.hatRecht(spieler.uuid(), Permissions.IGNORE_STAR.node())) {
                return true;
            }
            return verzeichnis.hatRecht(spieler.uuid(), typ.node());
        } catch (Exception e) {
            return failClosed;
        }
    }

    private static boolean istNieGesehen(OfflineVerzeichnis.ExternSpieler offlineSpieler) {
        try {
            return !offlineSpieler.hatGespielt() && !offlineSpieler.online();
        } catch (Exception e) {
            return false;
        }
    }

    public boolean istRangBlockiert(BefehlsSender sender, UUID zielUuid) {
        Objects.requireNonNull(zielUuid, "Die Ziel-UUID darf nicht null sein.");
        if (sender == null || !sender.istSpieler() || sender.spielerUuid() == null) {
            return false;
        }
        UUID ausfuehrer = sender.spielerUuid();
        if (zielUuid.equals(ausfuehrer)) {
            return false;
        }
        return istRanghoeher(ausfuehrer, zielUuid);
    }

    public boolean istRanghoeher(UUID ausfuehrerUuid, UUID zielUuid) {
        Objects.requireNonNull(ausfuehrerUuid, "Die Ausführenden-UUID darf nicht null sein.");
        Objects.requireNonNull(zielUuid, "Die Ziel-UUID darf nicht null sein.");
        if (ausfuehrerUuid.equals(zielUuid)) {
            return false;
        }
        int ausfuehrerGewicht = rangGewichtFuer(ausfuehrerUuid);
        int zielGewicht = rangGewichtFuer(zielUuid);
        return zielGewicht > ausfuehrerGewicht;
    }

    int rangGewichtFuer(UUID uuid) {
        // Bewusst fail-open: Liefert diese Methode für beide Beteiligten 0 (Rangdaten
        // unlesbar), greift die Rang-Hierarchie-Sperre nicht – verbleibender Schutz
        // sind die Ignore-Prüfungen (istGeschuetzt), die fail-closed laufen.
        try {
            java.util.OptionalInt lp = luckPerms.rangGewicht(uuid);
            if (lp.isPresent()) {
                return Math.max(0, lp.getAsInt());
            }
        } catch (Exception e) {
            LOG.warn("Rang-Gewicht (LuckPerms) für {} nicht ladbar – Fallback.", uuid, e);
        }
        try {
            Optional<OnlineSpieler> online = verzeichnis.onlineSpieler(uuid);
            if (online.isPresent()) {
                OnlineSpieler spieler = online.get();
                try {
                    java.util.function.Predicate<String> hat = knoten -> {
                        try {
                            return verzeichnis.hatRecht(uuid, knoten);
                        } catch (Exception ex) {
                            return false;
                        }
                    };
                    return de.lightrixx.punish.core.RangBuendel.maxGewichtFuer(hat);
                } catch (Exception e) {
                    return 0;
                }
            }
        } catch (Exception e) {
            LOG.warn("Rang-Gewicht (Online-Rechte) für {} nicht ladbar – Fallback 0.", uuid, e);
        }
        try {
            var offline = offlineVerzeichnis.findeNachUuid(uuid);
            if (offline.isPresent()) {
                LuckPermsZugang.OfflineStatus admin = offlineVerzeichnis.istMainThread()
                        ? luckPerms.offlineStatusCachedOnly(uuid, "punish.admin")
                        : luckPerms.offlineStatusBlocking(uuid, "punish.admin");
                if (admin == LuckPermsZugang.OfflineStatus.GESCHUETZT) {
                    return de.lightrixx.punish.core.RangBuendel.gewichtFuerGruppe("admin");
                }
            }
        } catch (Exception e) {
            LOG.warn("Offline-Admin-Status für {} nicht ladbar – Fallback 0.", uuid, e);
        }
        return 0;
    }

    public boolean istSelbstbestrafung(BefehlsSender sender, UUID zielUuid) {        Objects.requireNonNull(zielUuid, "Die Ziel-UUID darf nicht null sein.");

        if (sender == null || !sender.istSpieler()) {
            return false;
        }

        return zielUuid.equals(sender.spielerUuid());
    }

    public boolean istSelbstbestrafung(BefehlsSender sender, Optional<UUID> zielUuid) {
        Objects.requireNonNull(zielUuid, "Die optionale Ziel-UUID darf nicht null sein.");

        return zielUuid
                .map(uuid -> istSelbstbestrafung(sender, uuid))
                .orElse(false);
    }
}
