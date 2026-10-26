package de.lightrixx.punish.command;

import de.lightrixx.punish.core.BefehlsSender;
import de.lightrixx.punish.core.Konfiguration;
import de.lightrixx.punish.core.MessageKeys;
import de.lightrixx.punish.core.Permissions;
import de.lightrixx.punish.core.SpielerVerzeichnis;
import de.lightrixx.punish.service.MessageService;
import de.lightrixx.punish.service.ProtectionService;
import de.lightrixx.punish.storage.PlayerCache;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

public final class PunishVersionCommand extends PunishCommand {

    private final Supplier<String> versionLieferant;

    public PunishVersionCommand(MessageService messageService,
                                Konfiguration configManager,
                                ProtectionService protectionService,
                                PlayerCache playerCache,
                                SpielerVerzeichnis verzeichnis,
                                Supplier<String> versionLieferant) {
        super(messageService, configManager, protectionService, playerCache, null,
                Objects.requireNonNull(verzeichnis, "Das Spielerverzeichnis darf nicht null sein."));
        this.versionLieferant = Objects.requireNonNull(versionLieferant, "Der Versionslieferant darf nicht null sein.");
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
        String version = "unbekannt";
        try {
            String geliefert = versionLieferant.get();
            if (geliefert != null && !geliefert.isBlank()) {
                version = geliefert.trim();
                if (!version.startsWith("v") && !version.startsWith("V")) {
                    version = "v" + version;
                }
            }
        } catch (Exception e) {
            LOG.debug("Versionsabfrage fehlgeschlagen.", e);
        }
        messageService.senden(sender, MessageKeys.PUNISH_VERSION, Map.of("version", version));
    }

    @Override
    public List<String> vervollstaendigen(BefehlsSender sender, String[] args) {
        return List.of();
    }
}
