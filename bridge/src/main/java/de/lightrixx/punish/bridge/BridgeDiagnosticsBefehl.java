package de.lightrixx.punish.bridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;

public final class BridgeDiagnosticsBefehl implements CommandExecutor, TabCompleter {

    private static final long SYNC_WARN_MILLIS = 10L * 60L * 1000L;

    public record ChatfilterInfo(boolean aktiv, String detail) {
    }

    private final BridgeKonfiguration config;
    private final KanalClient kanal;
    private final MuteCache muteCache;
    private final Supplier<String> versionLieferant;
    private final Supplier<String> proxyVersionLieferant;
    private final Supplier<ChatfilterInfo> chatfilterLieferant;
    private final MiniMessage miniMessage;

    public BridgeDiagnosticsBefehl(BridgeKonfiguration config,
                                   KanalClient kanal,
                                   MuteCache muteCache,
                                   Supplier<String> versionLieferant,
                                   Supplier<String> proxyVersionLieferant,
                                   Supplier<ChatfilterInfo> chatfilterLieferant) {
        this.config = Objects.requireNonNull(config, "Die Konfiguration darf nicht null sein.");
        this.kanal = kanal;
        this.muteCache = muteCache;
        this.versionLieferant = Objects.requireNonNull(versionLieferant, "Der Versionslieferant darf nicht null sein.");
        this.proxyVersionLieferant = proxyVersionLieferant;
        this.chatfilterLieferant = Objects.requireNonNull(chatfilterLieferant, "Der Chatfilterlieferant darf nicht null sein.");
        this.miniMessage = MiniMessage.miniMessage();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        List<Zeile> zeilen = pruefe();
        sender.sendMessage(kopf());
        int warnungen = 0;
        for (Zeile zeile : zeilen) {
            if (!zeile.ok()) {
                warnungen++;
            }
            sender.sendMessage(zeilenText(zeile));
        }
        sender.sendMessage(fuss(warnungen, zeilen.size()));
        return true;
    }

    private Component kopf() {
        return prefixiert("<yellow>Diagnose <gray>(Bridge " + escape(version()) + ")");
    }

    private Component zeilenText(Zeile zeile) {
        String status = zeile.ok() ? "<green>OK" : "<yellow>WARN";
        return mitPrefix("<gray>[" + status + "<gray>] <white>" + escape(zeile.name())
                + "<gray>: " + escape(zeile.detail()));
    }

    private Component fuss(int warnungen, int anzahl) {
        return mitPrefix("<gray>" + warnungen + " von " + anzahl + " Prüfungen mit WARN.");
    }

    private Component prefixiert(String mini) {
        try {
            Component praefix = miniMessage.deserialize(config.praefix());
            return praefix.append(Component.space()).append(miniMessage.deserialize(mini));
        } catch (Exception e) {
            return Component.text().append(Component.text("[PunishSystem] ", NamedTextColor.RED))
                    .append(Component.text(klartext(mini), NamedTextColor.GRAY)).build();
        }
    }

    private Component mitPrefix(String mini) {
        try {
            return miniMessage.deserialize(mini);
        } catch (Exception e) {
            return Component.text(klartext(mini));
        }
    }

    private static String klartext(String mini) {
        if (mini == null) {
            return "";
        }
        return mini.replaceAll("<[^>]*>", "");
    }

    private static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("<", "\\<").replace("%", "％");
    }

    private String version() {
        try {
            String version = versionLieferant.get();
            return version == null || version.isBlank() ? "unbekannt" : version.trim();
        } catch (Exception e) {
            return "unbekannt";
        }
    }

    record Zeile(String name, boolean ok, String detail) {
    }

    List<Zeile> pruefe() {
        List<Zeile> ergebnis = new ArrayList<>();
        String version = version();
        ergebnis.add(new Zeile("Version", !"unbekannt".equals(version), version));
        String serverName = "unbekannt";
        try {
            serverName = config.serverName();
        } catch (Exception e) {
            serverName = "unbekannt";
        }
        boolean nameOk = serverName != null && !"unbekannt".equalsIgnoreCase(serverName)
                && !serverName.isBlank();
        ergebnis.add(new Zeile("Server-Name", nameOk,
                nameOk ? serverName : "nicht gesetzt (Lern-Modus, fail-closed bis HELLO_ACK)"));
        ergebnis.add(pruefeSync());
        ergebnis.add(pruefeTextSync());
        ergebnis.add(pruefeProxyVersion(version));
        ergebnis.add(new Zeile("Speicher", true, "RAM-Cache, Proxy ist Source of Truth"));
        boolean failClosed = true;
        try {
            failClosed = config.schutzFailClosed();
        } catch (Exception e) {
            failClosed = true;
        }
        ergebnis.add(new Zeile("Schutz fail-closed", failClosed,
                failClosed ? "aktiv" : "fail-open (Legacy)"));
        boolean neverSeen = false;
        try {
            neverSeen = config.offlineAllowNeverSeen();
        } catch (Exception e) {
            neverSeen = false;
        }
        ergebnis.add(new Zeile("Offline-Auflösung", !neverSeen,
                neverSeen ? "allow-never-seen=true (Squatting-Risiko)" : "fail-closed (empfohlen)"));
        try {
            String zone = String.valueOf(config.anzeigeZone());
            ergebnis.add(new Zeile("Anzeige-Zone", true, zone));
        } catch (Exception e) {
            ergebnis.add(new Zeile("Anzeige-Zone", false, "ungültig, nutze Europe/Berlin"));
        }
        ergebnis.add(pruefeLimits());
        ergebnis.add(pruefeEskalation());
        ChatfilterInfo info = leseChatfilter();
        boolean chatOk = !info.aktiv() || !info.detail().toLowerCase(Locale.ROOT).contains("fehlt");
        ergebnis.add(new Zeile("Chatfilter", chatOk,
                info.aktiv() ? info.detail() : "deaktiviert"));
        return List.copyOf(ergebnis);
    }

    private Zeile pruefeProxyVersion(String eigeneVersion) {
        String proxy;
        try {
            proxy = proxyVersionLieferant == null ? "" : proxyVersionLieferant.get();
        } catch (Exception e) {
            proxy = "";
        }
        proxy = proxy == null ? "" : proxy.trim();
        String eigen = eigeneVersion == null ? "unbekannt" : eigeneVersion;
        if (proxy.isEmpty() || "unbekannt".equalsIgnoreCase(proxy)) {
            return new Zeile("Proxy-Version", true, "noch kein HELLO_ACK (Proxy erreichbar?)");
        }
        boolean ok = eigen.equals(proxy);
        return new Zeile("Proxy-Version", ok,
                ok ? "einheitlich (" + eigen + ")"
                        : "ABWEICHUNG (Bridge=" + eigen + ", Proxy=" + proxy + ")");
    }

    private Zeile pruefeSync() {
        if (kanal == null || muteCache == null) {
            return new Zeile("Proxy-Sync", false, "kein Kanal/Cache");
        }
        try {
            if (!muteCache.istJeSynchronisiert()) {
                return new Zeile("Proxy-Sync", false,
                        "noch kein Sync (Proxy erreichbar? mind. 1 Spieler online?)");
            }
            long alter = System.currentTimeMillis() - muteCache.letzteSyncMillis();
            boolean ok = alter <= SYNC_WARN_MILLIS;
            return new Zeile("Proxy-Sync", ok,
                    ok ? "synchronisiert vor " + Math.max(1L, alter / 1000L) + "s"
                            : "letzter Sync vor " + (alter / 60000L) + " Min (Proxy-Neustart?)");
        } catch (Exception e) {
            return new Zeile("Proxy-Sync", false, "Sync-Status nicht lesbar");
        }
    }

    private Zeile pruefeTextSync() {
        try {
            java.io.File syncOrdner = new java.io.File("plugins/PunishSystemBridge/config-sync");
            java.io.File datenOrdner = null;
            try {
                if (config instanceof BridgeKonfiguration bridge) {
                    datenOrdner = bridge.syncOrdner();
                }
            } catch (Exception e) {
                datenOrdner = null;
            }
            java.io.File ordner = datenOrdner != null ? datenOrdner : syncOrdner;
            if (!ordner.isDirectory()) {
                return new Zeile("Text-Sync", false, "noch kein Proxy-Kontakt (Fallback-Texte aktiv)");
            }
            String[] dateien = ordner.list();
            int anzahl = dateien == null ? 0 : dateien.length;
            boolean ok = anzahl > 0;
            return new Zeile("Text-Sync", ok,
                    ok ? anzahl + " Datei(en) vom Proxy (Proxy-Master aktiv)"
                            : "leer (Fallback-Texte aktiv)");
        } catch (Exception e) {
            return new Zeile("Text-Sync", false, "Status nicht lesbar");
        }
    }

    private Zeile pruefeLimits() {
        try {
            long timeoutMax = config.timeoutMaxMinuten();
            long muteMax = config.muteMaxMinuten();
            long banMax = config.banMaxMinuten();
            long banMin = config.banMinMinuten();
            boolean ok = timeoutMax > 0 && muteMax > 0 && banMax > 0 && banMin > 0 && banMin <= banMax;
            return new Zeile("Limits", ok, "timeout-max=" + timeoutMax + ", mute-max=" + muteMax
                    + ", ban-min=" + banMin + ", ban-max=" + banMax);
        } catch (Exception e) {
            return new Zeile("Limits", false, "Konfiguration nicht lesbar");
        }
    }

    private Zeile pruefeEskalation() {
        try {
            int max = config.verstoesseMaxPunkte();
            boolean aktiv = config.warnEskalationAktiv();
            var schwellen = config.warnEskalationsSchwellen();
            if (!aktiv) {
                return new Zeile("Warn-Eskalation", true, "deaktiviert (max-punkte=" + max + ")");
            }
            int anzahlSchwellen = schwellen == null ? 0 : schwellen.size();
            boolean ok = max >= 1 && anzahlSchwellen > 0;
            return new Zeile("Warn-Eskalation", ok, ok
                    ? "aktiv, " + anzahlSchwellen + " Schwellen, max-punkte=" + max
                    : "aktiv, aber keine gültigen Schwellen");
        } catch (Exception e) {
            return new Zeile("Warn-Eskalation", false, "Konfiguration nicht lesbar");
        }
    }

    private ChatfilterInfo leseChatfilter() {
        try {
            ChatfilterInfo info = chatfilterLieferant.get();
            return info == null ? new ChatfilterInfo(false, "deaktiviert") : info;
        } catch (Exception e) {
            return new ChatfilterInfo(false, "Status nicht lesbar");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return List.of();
    }
}
