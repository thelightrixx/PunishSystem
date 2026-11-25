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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Supplier;

public final class PunishVersionCommand extends PunishCommand {

    private static final String AUSWAHL_PROXY = "proxy";
    private static final String AUSWAHL_SERVER = "server";
    private static final List<String> AUSWAHLEN = List.of(AUSWAHL_SERVER, AUSWAHL_PROXY);

    private final Supplier<String> versionLieferant;
    private final Supplier<Map<String, String>> serverVersionenLieferant;

    public PunishVersionCommand(MessageService messageService,
                                Konfiguration configManager,
                                ProtectionService protectionService,
                                PlayerCache playerCache,
                                SpielerVerzeichnis verzeichnis,
                                Supplier<String> versionLieferant) {
        this(messageService, configManager, protectionService, playerCache, verzeichnis,
                versionLieferant, Map::of);
    }

    public PunishVersionCommand(MessageService messageService,
                                Konfiguration configManager,
                                ProtectionService protectionService,
                                PlayerCache playerCache,
                                SpielerVerzeichnis verzeichnis,
                                Supplier<String> versionLieferant,
                                Supplier<Map<String, String>> serverVersionenLieferant) {
        super(messageService, configManager, protectionService, playerCache, null,
                Objects.requireNonNull(verzeichnis, "Das Spielerverzeichnis darf nicht null sein."));
        this.versionLieferant = Objects.requireNonNull(versionLieferant, "Der Versionslieferant darf nicht null sein.");
        this.serverVersionenLieferant = serverVersionenLieferant == null ? Map::of : serverVersionenLieferant;
    }

    @Override
    protected String recht() {
        return Permissions.PUNISH_VERSION.node();
    }

    @Override
    public boolean unterstuetztScope() {
        return false;
    }

    @Override
    public void ausfuehren(BefehlsSender sender, String[] args) {
        if (args != null && args.length > 1) {
            messageService.senden(sender, MessageKeys.PUNISH_VERSION_VERWENDUNG);
            return;
        }
        String auswahl = args == null || args.length == 0 || args[0] == null
                ? "" : args[0].trim().toLowerCase(Locale.ROOT);
        switch (auswahl) {
            case "" -> {
                zeigeProxyVersion(sender);
                zeigeServerVersionen(sender, false);
            }
            case AUSWAHL_PROXY -> zeigeProxyVersion(sender);
            case AUSWAHL_SERVER -> zeigeServerVersionen(sender, true);
            default -> messageService.senden(sender, MessageKeys.PUNISH_VERSION_VERWENDUNG);
        }
    }

    private void zeigeProxyVersion(BefehlsSender sender) {
        messageService.senden(sender, MessageKeys.PUNISH_VERSION, Map.of("version", leseProxyVersion()));
    }

    private String leseProxyVersion() {
        try {
            String geliefert = versionLieferant.get();
            if (geliefert != null && !geliefert.isBlank()) {
                return mitV(geliefert);
            }
        } catch (Exception e) {
            LOG.debug("Versionsabfrage fehlgeschlagen.", e);
        }
        return "unbekannt";
    }

    private void zeigeServerVersionen(BefehlsSender sender, boolean leerHinweis) {
        Map<String, String> versionen = leseServerVersionen();
        if (versionen.isEmpty()) {
            if (leerHinweis) {
                messageService.senden(sender, MessageKeys.PUNISH_VERSION_SERVER_LEER);
            }
            return;
        }
        List<Component> zeilen = new ArrayList<>(versionen.size());
        for (Map.Entry<String, String> eintrag : versionen.entrySet()) {
            zeilen.add(messageService.komponente(MessageKeys.PUNISH_VERSION_SERVER, Map.of(
                    "server", eintrag.getKey(),
                    "version", mitV(eintrag.getValue()))));
        }
        messageService.sendeBlock(sender, zeilen);
    }

    private Map<String, String> leseServerVersionen() {
        Map<String, String> roh;
        try {
            roh = serverVersionenLieferant.get();
        } catch (Exception e) {
            LOG.debug("Server-Versionsabfrage fehlgeschlagen.", e);
            return Map.of();
        }
        if (roh == null || roh.isEmpty()) {
            return Map.of();
        }
        Map<String, String> sortiert = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Map.Entry<String, String> eintrag : roh.entrySet()) {
            String server = eintrag.getKey();
            String version = eintrag.getValue();
            if (server == null || server.isBlank() || version == null || version.isBlank()) {
                continue;
            }
            sortiert.put(server.trim(), version.trim());
        }
        return sortiert;
    }

    private static String mitV(String version) {
        if (version == null || version.isBlank()) {
            return "unbekannt";
        }
        String getrimmt = version.trim();
        if (getrimmt.startsWith("v") || getrimmt.startsWith("V")) {
            return getrimmt;
        }
        return "v" + getrimmt;
    }

    @Override
    public List<String> vervollstaendigen(BefehlsSender sender, String[] args) {
        if (args == null || args.length == 0) {
            return AUSWAHLEN;
        }
        if (args.length == 1) {
            return filtere(AUSWAHLEN, args[0]);
        }
        return List.of();
    }
}
