package de.lightrixx.punish.velocity;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import de.lightrixx.punish.command.PunishCommand;
import de.lightrixx.punish.core.AuftragsPlaner;
import de.lightrixx.punish.core.BefehlsSender;
import de.lightrixx.punish.core.MessageKeys;
import de.lightrixx.punish.core.Permissions;
import de.lightrixx.punish.model.ServerScope;
import de.lightrixx.punish.service.MessageService;
import de.lightrixx.punish.service.ProtectionService;
import de.lightrixx.punish.storage.PlayerCache;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Supplier;

public final class VelocitySetupBefehl extends PunishCommand {

    private static final SecureRandom ZUFALL = new SecureRandom();
    private static final int SECRET_BYTES = 32;

    private final ProxyServer proxy;
    private final VelocityKonfiguration konfiguration;
    private final BrueckenKanal kanal;
    private final File datenOrdner;
    private final Supplier<String> versionLieferant;

    @SuppressWarnings("checkstyle:parameternumber")
    public VelocitySetupBefehl(MessageService messageService,
                               VelocityKonfiguration konfiguration,
                               ProtectionService protectionService,
                               PlayerCache playerCache,
                               AuftragsPlaner planer,
                               ProxyServer proxy,
                               BrueckenKanal kanal,
                               File datenOrdner,
                               Supplier<String> versionLieferant) {
        super(messageService, konfiguration, protectionService, playerCache, planer, null);
        this.konfiguration = Objects.requireNonNull(konfiguration,
                "Die Konfiguration darf nicht null sein.");
        this.proxy = Objects.requireNonNull(proxy, "Der Proxy darf nicht null sein.");
        this.kanal = kanal;
        this.datenOrdner = Objects.requireNonNull(datenOrdner,
                "Der Datenordner darf nicht null sein.");
        this.versionLieferant = versionLieferant;
    }

    @Override
    protected String recht() {
        return Permissions.PUNISH_SETUP.node();
    }

    @Override
    public boolean unterstuetztScope() {
        return false;
    }

    @Override
    public void ausfuehren(BefehlsSender sender, String[] args) {
        String unterbefehl = args == null || args.length == 0 || args[0] == null
                ? "check" : args[0].trim().toLowerCase(Locale.ROOT);
        switch (unterbefehl) {
            case "check" -> pruefe(sender);
            case "secrets" -> erzeugeSecrets(sender);
            case "bridge" -> bridge(sender, args);
            default -> messageService.senden(sender, MessageKeys.SETUP_UNBEKANNT);
        }
    }

    private void pruefe(BefehlsSender sender) {
        String version = "unbekannt";
        try {
            if (versionLieferant != null) {
                String geliefert = versionLieferant.get();
                if (geliefert != null && !geliefert.isBlank()) {
                    version = geliefert.trim();
                }
            }
        } catch (Exception e) {
            LOG.debug("Setup-Version konnte nicht gelesen werden.", e);
        }
        messageService.senden(sender, MessageKeys.SETUP_CHECK_KOPF, Map.of("version", version));

        String speicher = "unbekannt";
        try {
            speicher = konfiguration.speicherTyp().name().toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            LOG.debug("Setup-Speichertyp konnte nicht gelesen werden.", e);
        }
        messageService.senden(sender, MessageKeys.SETUP_CHECK_SPEICHER, Map.of("typ", speicher));

        List<String> server = serverNamen();
        if (server.isEmpty()) {
            messageService.senden(sender, MessageKeys.SETUP_KEINE_SERVER);
        }
        Map<String, String> secrets = konfiguration.brueckenSecrets();
        List<String> fehlend = new ArrayList<>();
        for (String name : server) {
            if (!secrets.containsKey(name)) {
                fehlend.add(name);
            }
        }
        messageService.senden(sender, MessageKeys.SETUP_CHECK_SECRETS, Map.of(
                "anzahl", String.valueOf(server.size() - fehlend.size()),
                "max", String.valueOf(server.size())));
        if (!fehlend.isEmpty()) {
            messageService.senden(sender, MessageKeys.SETUP_CHECK_SECRETS_FEHLT,
                    Map.of("server", String.join(", ", fehlend)));
        }
        messageService.senden(sender, MessageKeys.SETUP_CHECK_BRUECKEN,
                Map.of("status", brueckenStatus()));

        String ladefehler = konfiguration.letzterLadefehler();
        if (ladefehler != null && !ladefehler.isBlank()) {
            messageService.senden(sender, MessageKeys.SETUP_CHECK_FEHLER,
                    Map.of("detail", ladefehler));
        }
    }

    private void erzeugeSecrets(BefehlsSender sender) {
        List<String> server = serverNamen();
        if (server.isEmpty()) {
            messageService.senden(sender, MessageKeys.SETUP_KEINE_SERVER);
            return;
        }
        Map<String, String> alle = new LinkedHashMap<>(konfiguration.brueckenSecrets());
        List<String> erzeugt = new ArrayList<>();
        for (String name : server) {
            if (!alle.containsKey(name)) {
                alle.put(name, generiereSecret());
                erzeugt.add(name);
            }
        }
        if (erzeugt.isEmpty()) {
            messageService.senden(sender, MessageKeys.SETUP_SECRETS_KEINE);
            return;
        }
        try {
            konfiguration.speichereBrueckenSecrets(alle);
        } catch (Exception e) {
            LOG.warn("Setup-Secrets konnten nicht gespeichert werden.", e);
            messageService.senden(sender, MessageKeys.SETUP_FEHLER, Map.of("detail", kurz(e)));
            return;
        }
        if (kanal != null) {
            kanal.setBridgeSecrets(konfiguration.brueckenSecrets());
        }
        messageService.senden(sender, MessageKeys.SETUP_SECRETS_ERZEUGT, Map.of(
                "anzahl", String.valueOf(erzeugt.size()),
                "server", String.join(", ", erzeugt)));
        for (String name : erzeugt) {
            try {
                Path pfad = schreibeBridgeDatei(datenOrdner.toPath(), name, alle.get(name));
                messageService.senden(sender, MessageKeys.SETUP_BRIDGE_DATEI,
                        Map.of("server", name, "pfad", pfad.toString()));
            } catch (IOException e) {
                LOG.warn("Setup-Datei für '{}' konnte nicht geschrieben werden.", name, e);
                messageService.senden(sender, MessageKeys.SETUP_FEHLER,
                        Map.of("detail", kurz(e)));
            }
        }
        messageService.senden(sender, MessageKeys.SETUP_BRIDGE_HINWEIS);
    }

    private void bridge(BefehlsSender sender, String[] args) {
        if (args == null || args.length < 2 || args[1] == null || args[1].isBlank()) {
            messageService.senden(sender, MessageKeys.SETUP_UNBEKANNT);
            return;
        }
        String name = findeServer(args[1]);
        if (name == null) {
            messageService.senden(sender, MessageKeys.SETUP_SERVER_UNBEKANNT,
                    Map.of("server", args[1].trim()));
            return;
        }
        String secret = konfiguration.brueckenSecrets().get(name);
        if (secret == null) {
            messageService.senden(sender, MessageKeys.SETUP_BRIDGE_KEIN_SECRET,
                    Map.of("server", name));
            return;
        }
        try {
            Path pfad = schreibeBridgeDatei(datenOrdner.toPath(), name, secret);
            messageService.senden(sender, MessageKeys.SETUP_BRIDGE_DATEI,
                    Map.of("server", name, "pfad", pfad.toString()));
            messageService.senden(sender, MessageKeys.SETUP_BRIDGE_HINWEIS);
        } catch (IOException e) {
            LOG.warn("Setup-Datei für '{}' konnte nicht geschrieben werden.", name, e);
            messageService.senden(sender, MessageKeys.SETUP_FEHLER, Map.of("detail", kurz(e)));
        }
    }

    private List<String> serverNamen() {
        List<String> namen = new ArrayList<>();
        for (RegisteredServer server : proxy.getAllServers()) {
            try {
                String name = ServerScope.normalisiereServerName(
                        server.getServerInfo().getName());
                if (name != null && !namen.contains(name)) {
                    namen.add(name);
                }
            } catch (Exception e) {
                LOG.debug("Servername konnte nicht gelesen werden.", e);
            }
        }
        namen.sort(String.CASE_INSENSITIVE_ORDER);
        return namen;
    }

    private String findeServer(String eingabe) {
        String normiert = ServerScope.normalisiereServerName(eingabe);
        if (normiert == null) {
            return null;
        }
        for (String name : serverNamen()) {
            if (name.equalsIgnoreCase(normiert)) {
                return name;
            }
        }
        if (konfiguration.brueckenSecrets().containsKey(normiert)) {
            return normiert;
        }
        return null;
    }

    private String brueckenStatus() {
        if (kanal == null) {
            return "keine Verbindung";
        }
        Map<String, String> versionen;
        Map<String, String> varianten;
        try {
            versionen = kanal.bridgeVersionen();
            varianten = kanal.bridgeVarianten();
        } catch (Exception e) {
            LOG.debug("Bridge-Status konnte nicht gelesen werden.", e);
            return "nicht lesbar";
        }
        if (versionen.isEmpty()) {
            return "keine verbunden";
        }
        List<String> teile = new ArrayList<>();
        for (Map.Entry<String, String> eintrag : new TreeMap<>(versionen).entrySet()) {
            String variante = varianten.getOrDefault(eintrag.getKey(), "?");
            teile.add(eintrag.getKey() + "=" + eintrag.getValue() + " [" + variante + "]");
        }
        return String.join(", ", teile);
    }

    static String generiereSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        ZUFALL.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static Path schreibeBridgeDatei(Path datenOrdner, String server, String secret)
            throws IOException {
        Path ordner = datenOrdner.resolve("setup");
        Files.createDirectories(ordner);
        String dateiname = server.replaceAll("[^A-Za-z0-9_-]", "_");
        Path ziel = ordner.resolve("bridge-" + dateiname + ".yml");
        String inhalt = "# PunishSystemBridge - Setup für '" + server + "'\n"
                + "# In plugins/PunishSystemBridge/config/general.yml übernehmen.\n"
                + "netzwerk:\n"
                + "  server-name: \"" + server + "\"\n"
                + "  tunnel-secret: \"" + secret + "\"\n";
        Files.writeString(ziel, inhalt, StandardCharsets.UTF_8);
        return ziel;
    }

    private static String kurz(Exception e) {
        String meldung = e == null ? "" : String.valueOf(e.getMessage());
        if (meldung.isBlank()) {
            return e == null ? "unbekannt" : e.getClass().getSimpleName();
        }
        return meldung.length() > 160 ? meldung.substring(0, 160) : meldung;
    }

    @Override
    public List<String> vervollstaendigen(BefehlsSender sender, String[] args) {
        if (args == null || args.length <= 1) {
            String prefix = args == null || args.length == 0 || args[0] == null
                    ? "" : args[0].trim().toLowerCase(Locale.ROOT);
            return filtere(List.of("check", "secrets", "bridge"), prefix);
        }
        if ("bridge".equalsIgnoreCase(args[0]) && args.length == 2) {
            return filtere(serverNamen(), args[1] == null ? "" : args[1].trim());
        }
        return List.of();
    }
}
