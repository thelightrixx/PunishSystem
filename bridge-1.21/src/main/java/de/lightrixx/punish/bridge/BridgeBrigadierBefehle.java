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
        if (registrar == null || eintraege == null || eintraege.isEmpty()) {
            return;
        }
        for (Eintrag eintrag : eintraege) {
            if (eintrag == null || eintrag.name() == null || eintrag.name().isBlank()
                    || eintrag.tunnel() == null) {
                continue;
            }
            try {
                registriere(registrar, eintrag);
            } catch (Exception e) {
                LOG.warn("Brigadier-Registrierung für /{} fehlgeschlagen (Legacy-TAB bleibt aktiv).",
                        eintrag.name(), e);
            }
        }
    }

    private static void registriere(Commands registrar, Eintrag eintrag) {
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
                            if (roh == null) {
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
        String beschreibung = eintrag.beschreibung() == null ? "" : eintrag.beschreibung();
        try {
            registrar.register(node, beschreibung, aliase);
        } catch (IllegalArgumentException e) {
            LOG.warn("Registrierung von /{} mit Aliasen fehlgeschlagen ({}), versuche ohne Aliase.",
                    eintrag.name(), e.getMessage());
            registrar.register(node, beschreibung, List.of());
        }
        LOG.debug("Brigadier-Command /{} registriert ({} Aliase).", eintrag.name(), aliase.size());
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
