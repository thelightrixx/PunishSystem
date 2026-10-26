package de.lightrixx.punish.velocity;

import com.velocitypowered.api.proxy.ProxyServer;
import de.lightrixx.punish.chatfilter.WortlistenFilter;
import de.lightrixx.punish.command.PunishCommand;
import de.lightrixx.punish.core.AuftragsPlaner;
import de.lightrixx.punish.core.BefehlsSender;
import de.lightrixx.punish.core.MessageKeys;
import de.lightrixx.punish.core.Permissions;
import de.lightrixx.punish.core.SpielerVerzeichnis;
import de.lightrixx.punish.service.MessageService;
import de.lightrixx.punish.service.ProtectionService;
import de.lightrixx.punish.storage.PlayerCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class ChatfilterBefehl extends PunishCommand {

    private static final Logger LOG = LoggerFactory.getLogger(ChatfilterBefehl.class);

    private static final List<String> UNTERBEFEHLE = List.of("on", "off", "ai_on", "ai_off", "add", "sync");

    private volatile BrueckenKanal brueckenKanal;

    public ChatfilterBefehl(MessageService messageService,
                            VelocityKonfiguration configManager,
                            ProtectionService protectionService,
                            PlayerCache playerCache,
                            AuftragsPlaner taskScheduler,
                            SpielerVerzeichnis verzeichnis,
                            ProxyServer proxy) {
        super(messageService, configManager, protectionService, playerCache, taskScheduler,
                Objects.requireNonNull(verzeichnis, "Das Spielerverzeichnis darf nicht null sein."));
        Objects.requireNonNull(proxy, "Der Proxy darf nicht null sein.");
    }

    public void setBrueckenKanal(BrueckenKanal kanal) {
        this.brueckenKanal = kanal;
    }

    @Override
    protected String recht() {
        return Permissions.PUNISH_CHATFILTER.node();
    }

    @Override
    public boolean hatRecht(BefehlsSender sender, String[] args) {
        if (istSync(args)) {
            return sender != null && sender.hatRecht(Permissions.PUNISH_CHATFILTER_SYNC.node());
        }
        return super.hatRecht(sender, args);
    }

    @Override
    public boolean sichtbarFuer(BefehlsSender sender) {
        return super.sichtbarFuer(sender)
                || (sender != null && sender.hatRecht(Permissions.PUNISH_CHATFILTER_SYNC.node()));
    }

    private static boolean istSync(String[] args) {
        return args != null && args.length > 0 && args[0] != null
                && "sync".equalsIgnoreCase(args[0].trim());
    }

    @Override
    public boolean unterstuetztScope() {
        return false;
    }

    @Override
    public void ausfuehren(BefehlsSender sender, String[] args) {
        if (args.length == 0) {
            messageService.senden(sender, MessageKeys.CHATFILTER_SYNTAX);
            return;
        }
        BrueckenKanal kanal = brueckenKanal;
        if (kanal == null) {
            LOG.warn("Chatfilter-Befehl ohne Brückenkanal.");
            messageService.senden(sender, MessageKeys.RELOAD_FEHLER);
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "on" -> {
                kanal.chatfilterSteuerungAnAlle(Boolean.TRUE, null);
                messageService.senden(sender, MessageKeys.CHATFILTER_AN);
            }
            case "off" -> {
                kanal.chatfilterSteuerungAnAlle(Boolean.FALSE, null);
                messageService.senden(sender, MessageKeys.CHATFILTER_AUS);
            }
            case "ai_on" -> {
                kanal.chatfilterSteuerungAnAlle(null, Boolean.TRUE);
                messageService.senden(sender, MessageKeys.CHATFILTER_AI_AN);
            }
            case "ai_off" -> {
                kanal.chatfilterSteuerungAnAlle(null, Boolean.FALSE);
                messageService.senden(sender, MessageKeys.CHATFILTER_AI_AUS);
            }
            case "add" -> verarbeiteAdd(sender, kanal, args);
            case "sync" -> {
                boolean gestartet;
                try {
                    boolean modell = kanal.modellSyncAnAlle();
                    boolean texte = kanal.textSyncAnAlle();
                    gestartet = modell || texte;
                } catch (Exception e) {
                    LOG.warn("Modell-/Text-Sync konnte nicht angestoßen werden.", e);
                    gestartet = false;
                }
                messageService.senden(sender, gestartet
                        ? MessageKeys.CHATFILTER_SYNC_START
                        : MessageKeys.CHATFILTER_SYNC_AUS);
            }
            default -> messageService.senden(sender, MessageKeys.CHATFILTER_SYNTAX);
        }
    }

    private void verarbeiteAdd(BefehlsSender sender, BrueckenKanal kanal, String[] args) {
        if (args.length < 2) {
            messageService.senden(sender, MessageKeys.CHATFILTER_SYNTAX);
            return;
        }
        String roh = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)).trim();
        Optional<String> wort = WortlistenFilter.gueltigerEintrag(roh);
        if (wort.isEmpty()) {
            messageService.senden(sender, MessageKeys.CHATFILTER_WORT_UNGUELTIG);
            return;
        }
        boolean erfolg;
        try {
            erfolg = kanal.textWortHinzufuegen(wort.get());
        } catch (Exception e) {
            LOG.warn("Chatfilter-Wort konnte nicht übernommen werden.", e);
            erfolg = false;
        }
        if (!erfolg) {
            messageService.senden(sender, MessageKeys.CHATFILTER_WORT_UNGUELTIG);
            return;
        }
        messageService.senden(sender, MessageKeys.CHATFILTER_ADD_ERFOLG,
                Map.of("wort", wort.get(), "server", "alle Server"));
    }

    @Override
    public String tooltipFuer(String vorschlag, String[] args) {
        return "Unterbefehl";
    }

    @Override
    public List<String> vervollstaendigen(BefehlsSender sender, String[] args) {
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0];
            return filtere(UNTERBEFEHLE, prefix);
        }
        return List.of();
    }
}
