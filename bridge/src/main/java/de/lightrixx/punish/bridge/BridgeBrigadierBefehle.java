package de.lightrixx.punish.bridge;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import de.lightrixx.punish.util.TabHinweis;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class BridgeBrigadierBefehle {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(BridgeBrigadierBefehle.class);

    private BridgeBrigadierBefehle() {
    }

    public record Eintrag(String name, Collection<String> aliase, String beschreibung,
                          TunnelVorschlag tunnel) {
    }

    public static void registrieren(Commands registrar, Collection<Eintrag> eintraege) {
        registrieren(registrar, eintraege, true);
    }

    public static void registrieren(Commands registrar, Collection<Eintrag> eintraege, boolean ueberschreiben) {
        if (registrar == null || eintraege == null || eintraege.isEmpty()) {
            return;
        }
        for (Eintrag eintrag : eintraege) {
            if (eintrag == null || eintrag.name() == null || eintrag.name().isBlank()
                    || eintrag.tunnel() == null) {
                continue;
            }
            try {
                registriere(registrar, eintrag, ueberschreiben);
            } catch (Exception e) {
                LOG.warn("Brigadier-Registrierung für /{} fehlgeschlagen (Legacy-TAB bleibt aktiv).",
                        eintrag.name(), e);
            }
        }
    }

    private static void registriere(Commands registrar, Eintrag eintrag, boolean ueberschreiben) {
        TunnelVorschlag tunnel = eintrag.tunnel();
        RequiredArgumentBuilder<CommandSourceStack, String> rest =
                RequiredArgumentBuilder.<CommandSourceStack, String>argument(
                                "rest", StringArgumentType.greedyString())
                        .suggests((ctx, builder) -> {
                            CommandSender sender;
                            try {
                                sender = ctx.getSource().getSender();
                            } catch (Throwable t) {
                                return builder.buildFuture();
                            }
                            if (! tunnel.darfSehen(sender)) {
                                return builder.buildFuture();
                            }
                            String roh;
                            try {
                                roh = ctx.getArgument("rest", String.class);
                            } catch (Throwable t) {
                                roh = "";
                            }
                            String[] teile = TabHinweis.teile(roh);
                            List<String> vorschlaege;
                            try {
                                vorschlaege = tunnel.vorschlaege(sender, teile);
                            } catch (Throwable t) {
                                return builder.buildFuture();
                            }
                            if (vorschlaege == null || vorschlaege.isEmpty()) {
                                return builder.buildFuture();
                            }
                            String praefix = TabHinweis.praefixFuer(roh);
                            for (String vorschlag : vorschlaege) {
                                if (vorschlag == null) {
                                    continue;
                                }
                                String hinweis = null;
                                try {
                                    hinweis = tunnel.hinweisFuer(vorschlag, teile);
                                } catch (Throwable e) {
                                    LOG.trace("Hinweis konnte nicht gelesen werden.", e);
                                }
                                if (hinweis == null || hinweis.isBlank()) {
                                    builder.suggest(praefix + vorschlag);
                                } else {
                                    final String text = hinweis;
                                    builder.suggest(praefix + vorschlag, () -> text);
                                }
                            }
                            return builder.buildFuture();
                        })
                        .executes(ctx -> {
                            CommandSender sender;
                            try {
                                sender = ctx.getSource().getSender();
                            } catch (Throwable t) {
                                return 0;
                            }
                            try {
                                if (! tunnel.darfSehen(sender)) {
                                    tunnel.sendeAbweisung(sender);
                                    return 0;
                                }
                            } catch (Throwable t) {
                                return 0;
                            }
                            String roh;
                            try {
                                roh = ctx.getArgument("rest", String.class);
                            } catch (Throwable t) {
                                roh = "";
                            }
                            try {
                                tunnel.fuehreAus(sender, zuArgs(roh));
                            } catch (Throwable t) {
                                LOG.warn("Fehler bei /{} von {}.", eintrag.name(),
                                        sender == null ? "?" : sender.getName(), t);
                            }
                            return 1;
                        });
        LiteralArgumentBuilder<CommandSourceStack> knoten = Commands.literal(eintrag.name())
                .requires(quelle -> {
                    try {
                        return tunnel.darfSehen(quelle.getSender());
                    } catch (Throwable t) {
                        return false;
                    }
                })
                .then(rest)
                .executes(ctx -> {
                    CommandSender sender;
                    try {
                        sender = ctx.getSource().getSender();
                    } catch (Throwable t) {
                        return 0;
                    }
                    try {
                        if (! tunnel.darfSehen(sender)) {
                            tunnel.sendeAbweisung(sender);
                            return 0;
                        }
                    } catch (Throwable t) {
                        return 0;
                    }
                    try {
                        tunnel.fuehreAus(sender, new String[0]);
                    } catch (Throwable t) {
                        LOG.warn("Fehler bei /{} von {}.", eintrag.name(),
                                sender == null ? "?" : sender.getName(), t);
                    }
                    return 1;
                });
        LiteralCommandNode<CommandSourceStack> node = knoten.build();
        List<String> aliase = new ArrayList<>();
        if (eintrag.aliase() != null) {
            for (String alias : eintrag.aliase()) {
                if (alias != null && ! alias.isBlank()) {
                    aliase.add(alias.trim());
                }
            }
        }
        if (ueberschreiben) {
            for (String alias : ueberschreibAliase(eintrag.name())) {
                aliase.add(alias);
            }
        }
        String beschreibung = eintrag.beschreibung() == null ? "" : eintrag.beschreibung();
        try {
            registrar.register(node, beschreibung, aliase);
        } catch (IllegalArgumentException e) {
            LOG.warn("Registrierung von /{} mit Aliasen fehlgeschlagen ({}), versuche ohne Aliase.",
                    eintrag.name(), e.getMessage());
            registrar.register(node, beschreibung, List.of());
        }
        if (ueberschreiben && istStrafBefehl(eintrag.name())) {
            List<String> namen = new ArrayList<>();
            namen.add(eintrag.name());
            aliase.stream().filter(alias -> !alias.contains(":")).forEach(namen::add);
            for (String name : namen) {
                for (String praefix : List.of("", "minecraft:", "bukkit:", "essentials:")) {
                    var uebernahme = new LiteralCommandNode<>(praefix + name, node.getCommand(),
                            node.getRequirement(), node.getRedirect(), node.getRedirectModifier(), node.isFork());
                    node.getChildren().forEach(uebernahme::addChild);
                    registrar.register(uebernahme, beschreibung, List.of());
                }
            }
        }
        LOG.debug("Brigadier-Command /{} registriert ({} Aliase).", eintrag.name(), aliase.size());
    }

    private static boolean istStrafBefehl(String name) {
        return switch (name) {
            case "ban", "permabann", "ban-ip", "unban-ip", "ban-all",
                    "kick", "kick-ip", "kick-all", "pardon", "pardon-all",
                    "mute", "unmute", "mute-ip", "unmute-ip", "mute-all", "unmute-all",
                    "timeout", "untimeout", "timeout-ip", "untimeout-ip", "timeout-all", "untimeout-all",
                    "freeze", "unfreeze", "warn", "verstoss" -> true;
            default -> false;
        };
    }

    private static List<String> ueberschreibAliase(String name) {
        return switch (name) {
            case "ban" -> List.of("eban", "tempban", "etempban");
            case "ban-ip" -> List.of("banip", "ebanip", "tempbanip", "etempbanip");
            case "unban-ip" -> List.of("unbanip", "eunbanip", "pardon-ip");
            case "mute" -> List.of("emute");
            case "kick" -> List.of("ekick");
            case "kick-all" -> List.of("kickall", "ekickall");
            case "pardon" -> List.of("eunban");
            default -> List.of();
        };
    }

    static String[] zuArgs(String rest) {
        if (rest == null || rest.isBlank()) {
            return new String[0];
        }
        List<String> teile = new ArrayList<>();
        for (String token : rest.trim().split("\\s+")) {
            if (token != null && ! token.isEmpty()) {
                teile.add(token);
            }
        }
        return teile.toArray(new String[0]);
    }
}
