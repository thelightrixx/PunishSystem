package de.lightrixx.punish.velocity;

import de.lightrixx.punish.command.PunishCommand;
import de.lightrixx.punish.command.ScopeParser;
import de.lightrixx.punish.core.AuftragsPlaner;
import de.lightrixx.punish.core.BefehlsSender;
import de.lightrixx.punish.core.SpielerVerzeichnis;
import de.lightrixx.punish.model.CachedPlayer;
import de.lightrixx.punish.model.ServerScope;
import de.lightrixx.punish.service.BanService;
import de.lightrixx.punish.service.FreezeService;
import de.lightrixx.punish.service.MessageService;
import de.lightrixx.punish.service.MuteService;
import de.lightrixx.punish.service.ProtectionService;
import de.lightrixx.punish.service.TimeoutService;
import de.lightrixx.punish.service.WarnService;
import de.lightrixx.punish.storage.PlayerCache;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class StrafkatalogBefehl extends PunishCommand {

    public record Dienste(WarnService warn, MuteService mute, TimeoutService timeout,
                           BanService ban, FreezeService freeze) {
    }

    private record Vorschlag(String token, CachedPlayer ziel, Strafkatalog.Regel regel, long anzahl,
                              ServerScope scope, long fallId, long ablauf) {
    }

    private final VelocityKonfiguration config;
    private final ModerationsDienst moderation;
    private final Dienste dienste;
    private final Map<String, Vorschlag> vorschlaege = new ConcurrentHashMap<>();

    public StrafkatalogBefehl(MessageService texte, VelocityKonfiguration config, ProtectionService schutz,
                              PlayerCache spieler, AuftragsPlaner planer, SpielerVerzeichnis verzeichnis,
                              ModerationsDienst moderation, Dienste dienste) {
        super(texte, config, schutz, spieler, planer, verzeichnis);
        this.config = config;
        this.moderation = moderation;
        this.dienste = dienste;
    }

    @Override
    protected String recht() {
        return "punish.katalog";
    }

    @Override
    public void ausfuehren(BefehlsSender sender, String[] args) {
        if (!hatRecht(sender, args)) {
            hinweis(sender, "Keine Berechtigung.");
            return;
        }
        try {
            if (args.length == 2 && args[0].equalsIgnoreCase("confirm")) {
                bestaetigen(sender, args[1]);
                return;
            }
            var regeln = Strafkatalog.lesen(config.moderationsAbschnitt("strafkatalog"));
            if (args.length < 2) {
                hinweis(sender, "/punish-katalog <Spieler> <Regel> [fall=<Nr>] [--global|--server=<Name>]");
                hinweis(sender, "Regeln: " + String.join(", ", regeln.keySet()));
                return;
            }
            var scope = ScopeParser.parsen(args, ServerScope.global(), bekannteServer());
            if (blockiereBeiUnbekanntemScope(sender, scope)) {
                return;
            }
            if (scope.silent()) {
                throw new IllegalArgumentException("Der Strafkatalog unterstützt keine stillen Strafen.");
            }
            var sauber = scope.bereinigteArgs();
            if (sauber.length < 2) {
                throw new IllegalArgumentException("Spieler und Regel fehlen.");
            }
            CachedPlayer ziel = zielAufloesen(sauber[0])
                    .orElseThrow(() -> new IllegalArgumentException("Spieler nicht gefunden."));
            Strafkatalog.Regel regel = regeln.get(sauber[1].toLowerCase(Locale.ROOT));
            if (regel == null) {
                throw new IllegalArgumentException("Unbekannte oder ungültige Strafregel.");
            }
            long fallId = 0;
            for (String argument : Arrays.copyOfRange(sauber, 2, sauber.length)) {
                if (!argument.startsWith("fall=") || fallId != 0 || !sender.hatRecht("punish.fall.bearbeiten")) {
                    throw new IllegalArgumentException("Optional ist genau eine Fallnummer mit fall=<Nr> erlaubt (punish.fall.bearbeiten).");
                }
                fallId = Long.parseLong(argument.substring(5));
                if (fallId < 1) {
                    throw new IllegalArgumentException("Die Fallnummer muss positiv sein.");
                }
            }
            ServerScope wirksam = scope.hatteFlag() ? scope.scope() : ServerScope.global();
            long anzahl = moderation.anzahlRegel(ziel.uuid(), regel.id(), regel.fensterMillis());
            var stufe = regel.stufen().get(regel.stufenNummer(anzahl) - 1);
            pruefe(sender, ziel, stufe, wirksam, fallId);
            vorschlaege.entrySet().removeIf(e -> e.getValue().ablauf() < System.currentTimeMillis());
            if (vorschlaege.size() >= 1000) {
                throw new IllegalStateException("Zu viele offene Bestätigungen. Bitte kurz warten.");
            }
            String token = UUID.randomUUID().toString().substring(0, 12);
            Vorschlag v = new Vorschlag(token, ziel, regel, anzahl, wirksam, fallId, System.currentTimeMillis() + 60_000L);
            vorschlaege.put(sender.abstandsSchluessel(), v);
            hinweis(sender, ziel.name() + ": " + regel.grund() + " · Stufe " + regel.stufenNummer(anzahl)
                    + "/" + regel.stufen().size() + " · " + stufe.anzeige() + " · " + wirksam.anzeige()
                    + " · bisher " + anzahl + " innerhalb von " + regel.fensterMillis() / 86_400_000L + " Tagen.");
            sender.nachricht(Component.text("[Strafe bestätigen – 60 Sekunden]", NamedTextColor.YELLOW)
                    .clickEvent(ClickEvent.runCommand("/punish-katalog confirm " + token)));
        } catch (IllegalArgumentException | IllegalStateException e) {
            hinweis(sender, e.getMessage());
        }
    }

    private void bestaetigen(BefehlsSender sender, String token) {
        Vorschlag v = vorschlaege.get(sender.abstandsSchluessel());
        if (v == null || !v.token().equals(token) || v.ablauf() < System.currentTimeMillis()
                || !vorschlaege.remove(sender.abstandsSchluessel(), v)) {
            throw new IllegalArgumentException("Bestätigung fehlt oder ist abgelaufen. Bitte einen neuen Vorschlag öffnen.");
        }
        var stufe = v.regel().stufen().get(v.regel().stufenNummer(v.anzahl()) - 1);
        var typ = switch (stufe.befehl()) {
            case "warn" -> ProtectionService.IgnoreTyp.WARN;
            case "mute" -> ProtectionService.IgnoreTyp.MUTE;
            case "timeout" -> ProtectionService.IgnoreTyp.TIMEOUT;
            case "freeze" -> ProtectionService.IgnoreTyp.FREEZE;
            default -> ProtectionService.IgnoreTyp.BAN;
        };
        mitSchutz(sender, new String[0], v.ziel(), typ, () -> {
            try {
                synchronized (moderation) {
                    if (v.ablauf() < System.currentTimeMillis()
                            || !v.regel().equals(Strafkatalog.lesen(config.moderationsAbschnitt("strafkatalog")).get(v.regel().id()))
                            || moderation.anzahlRegel(v.ziel().uuid(), v.regel().id(), v.regel().fensterMillis()) != v.anzahl()) {
                        throw new IllegalStateException("Der Vorschlag hat sich geändert. Bitte erneut öffnen und prüfen.");
                    }
                    pruefe(sender, v.ziel(), stufe, v.scope(), v.fallId());
                    boolean[] erfolgreich = {false};
                    var ereignisse = moderation.erfasse(() -> erfolgreich[0] = anwenden(sender, v, stufe));
                    if (!erfolgreich[0]) {
                        throw new IllegalStateException("Die Strafe wurde abgewiesen, etwa wegen einer bestehenden Strafe oder eines Speicherfehlers.");
                    }
                    var fall = moderation.katalogAngewendet(v.ziel().uuid(), v.ziel().name(), v.regel(),
                            v.regel().stufenNummer(v.anzahl()), v.fallId(), ausfuehrerName(sender), ereignisse);
                    hinweis(sender, "Strafe ausgeführt und in Fall #" + fall.id() + " dokumentiert.");
                }
            } catch (IllegalArgumentException | IllegalStateException e) {
                hinweis(sender, e.getMessage());
            }
        });
    }

    private void pruefe(BefehlsSender sender, CachedPlayer ziel, Strafkatalog.Stufe stufe,
                        ServerScope scope, long fallId) {
        if (!sender.hatRecht(recht()) || !sender.hatRecht(stufe.recht())) {
            throw new IllegalArgumentException("Erforderliches Recht fehlt: " + stufe.recht());
        }
        if (fallId > 0) {
            var fall = moderation.fall(fallId);
            if (!sender.hatRecht("punish.fall.bearbeiten") || !fall.uuid().equals(ziel.uuid())
                    || fall.geschlossen() || !fall.regel().isBlank()) {
                throw new IllegalArgumentException("Der Fall gehört nicht zum Spieler, ist geschlossen oder enthält bereits eine Katalog-Strafe.");
            }
            if (fall.eintraege().size() > 490) {
                throw new IllegalArgumentException("Der Fall hat zu wenig Platz für die neue Strafentscheidung.");
            }
        } else if (moderation.faelle(null).size() >= 20_000) {
            throw new IllegalStateException("Das Fall-Limit ist erreicht.");
        }
        if (stufe.befehl().equals("warn") && scope.typ() != ServerScope.Typ.GLOBAL) {
            throw new IllegalArgumentException("Verwarnungen gelten global. Bitte --global wählen.");
        }
        long maximal = switch (stufe.befehl()) {
            case "mute" -> config.muteMaxMinuten();
            case "ban" -> config.banMaxMinuten();
            case "timeout" -> config.timeoutMaxMinuten();
            case "freeze" -> config.freezeMaxMinuten();
            default -> Long.MAX_VALUE / 60_000L;
        };
        if (stufe.millis() > maximal * 60_000L || (stufe.millis() > 0 && !istAblaufMoeglich(stufe.millis()))) {
            throw new IllegalArgumentException("Die konfigurierte Dauer überschreitet das erlaubte Limit.");
        }
        if (stufe.befehl().equals("ban") && stufe.millis() < config.banMinMinuten() * 60_000L) {
            throw new IllegalArgumentException("Die Bann-Dauer unterschreitet das konfigurierte Minimum.");
        }
    }

    private boolean anwenden(BefehlsSender sender, Vorschlag v, Strafkatalog.Stufe stufe) {
        var z = v.ziel();
        String autor = ausfuehrerName(sender);
        String grund = v.regel().grund();
        return switch (stufe.befehl()) {
            case "warn" -> dienste.warn().hinzufuegen(z.uuid(), z.name(), grund, autor, false, sender) != null;
            case "mute" -> dienste.mute().muten(z.uuid(), z.name(), stufe.millis(), grund, autor,
                    v.scope(), herkunftVon(sender), false, sender);
            case "timeout" -> dienste.timeout().timeoutVerhaengen(z.uuid(), z.name(), stufe.millis(), grund, autor,
                    v.scope(), herkunftVon(sender), false, sender);
            case "freeze" -> dienste.freeze().freezen(z.uuid(), z.name(), stufe.millis(), grund, autor,
                    v.scope(), herkunftVon(sender), false, sender);
            case "ban" -> dienste.ban().bannen(z.uuid(), z.name(), stufe.millis(), grund, autor,
                    v.scope(), herkunftVon(sender), false, sender);
            case "permabann" -> dienste.ban().permabannMitErgebnis(z.uuid(), z.name(), grund, autor,
                    v.scope(), herkunftVon(sender), false, sender) == BanService.BanErgebnis.ERFOLG;
            default -> false;
        };
    }

    static void hinweis(BefehlsSender sender, String text) {
        sender.nachricht(Component.text("[Punish] " + text, NamedTextColor.GRAY));
    }

    @Override
    public List<String> vervollstaendigen(BefehlsSender sender, String[] args) {
        if (args.length <= 1) {
            return spielerVorschlaegeSmart(sender, args.length == 0 ? "" : args[0]);
        }
        if (args.length == 2) {
            return filtere(Strafkatalog.lesen(config.moderationsAbschnitt("strafkatalog")).keySet().stream().sorted().toList(), args[1]);
        }
        return scopeVorschlaege(args[args.length - 1]);
    }
}
