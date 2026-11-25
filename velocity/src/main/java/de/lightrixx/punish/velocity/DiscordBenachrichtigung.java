package de.lightrixx.punish.velocity;

import de.lightrixx.punish.model.HistoryEntry;
import de.lightrixx.punish.model.HistoryType;
import de.lightrixx.punish.model.ReportRecord;
import de.lightrixx.punish.storage.Karten;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** Ausgehende, optionale Team-Meldungen; Netzwerkzugriffe laufen getrennt von Speicheraufträgen. */
public final class DiscordBenachrichtigung implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(DiscordBenachrichtigung.class);
    private static final List<String> STANDARD = List.of("report", "bann", "mute", "timeout", "freeze", "kick",
            "ip", "verwarnung", "verstoss", "aufhebung", "bearbeitung");
    record Antwort(int status, long warteMillis) {
    }
    @FunctionalInterface
    interface Transport {
        Antwort senden(URI uri, String json) throws Exception;
    }

    private final Supplier<Map<String, Object>> konfiguration;
    private final ModerationsDienst moderation;
    private final ScheduledExecutorService executor;
    private final AtomicInteger wartend = new AtomicInteger();
    private final Transport transport;
    private final HttpClient client;
    private volatile boolean geschlossen;
    private long letzteWarnung;
    private long pausiertBis;
    private URI aktiverWebhook;
    private boolean webhookUngueltig;

    public DiscordBenachrichtigung(VelocityKonfiguration config, ModerationsDienst moderation) {
        this(() -> config.moderationsAbschnitt("discord"), moderation, null);
    }

    DiscordBenachrichtigung(Supplier<Map<String, Object>> konfiguration, ModerationsDienst moderation, Transport transport) {
        this.konfiguration = konfiguration;
        this.moderation = moderation;
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "Punish-Discord");
            thread.setDaemon(true);
            return thread;
        });
        this.client = transport == null ? HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build() : null;
        this.transport = transport == null ? (uri, json) -> {
            var request = HttpRequest.newBuilder(URI.create(uri + "?wait=true")).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(json)).build();
            var antwort = client.send(request, HttpResponse.BodyHandlers.discarding());
            double sekunden;
            try {
                sekunden = Double.parseDouble(antwort.headers().firstValue("Retry-After").orElse("2"));
            } catch (NumberFormatException e) {
                sekunden = 2;
            }
            return new Antwort(antwort.statusCode(), warteMillis(sekunden));
        } : transport;
    }

    public void history(UUID uuid, HistoryEntry h) {
        if (h.typ() == HistoryType.REPORT_ADD || h.typ() == HistoryType.REPORT_CLOSE) {
            return;
        }
        String art = gruppe(h.typ());
        anstellen(art, h.typ().name(), () -> {
            String ziel = art.equals("ip") ? "IP-Adresse (ausgeblendet)" : h.ziel();
            String fall = moderation.faelle(uuid).stream().filter(f -> f.eintraege().stream()
                    .anyMatch(e -> e.typ().equals("HISTORY") && e.referenz() == h.id()))
                    .findFirst().map(f -> " · Fall #" + f.id()).orElse("");
            return h.typ().anzeigename() + " · " + ziel + fall + "\nGrund: " + h.grund()
                    + "\nTeam: " + h.ausfuehrer() + " · Scope: " + h.scope() + " · History #" + h.id();
        });
    }

    public void history(de.lightrixx.punish.model.HistoryEreignis ereignis) {
        if (!ereignis.silent()) {
            history(ereignis.uuid(), ereignis.eintrag());
        }
    }

    public void report(ReportRecord report, boolean silent) {
        if (!silent) {
            report(report);
        }
    }

    public void report(ReportRecord r) {
        anstellen("report", r.status() == de.lightrixx.punish.model.ReportStatus.OFFEN ? "REPORT_ADD" : "REPORT_CLOSE", () -> {
            String fall = moderation.faelle(r.zielUuid()).stream().filter(f -> f.eintraege().stream()
                    .anyMatch(e -> e.typ().equals("REPORT") && e.referenz() == r.id()))
                    .findFirst().map(f -> " · Fall #" + f.id()).orElse("");
            return "Report #" + r.id() + fall + " · " + r.zielName() + " · " + r.status().name()
                    + "\nGrund: " + r.grund() + "\nServer: " + r.server()
                    + (r.bearbeiter().isBlank() ? "" : "\nZuständig: " + r.bearbeiter());
        });
    }

    private void anstellen(String art, String typ, Supplier<String> text) {
        Map<String, Object> config = konfiguration.get();
        if (geschlossen || !aktiv(config, art, typ)) {
            return;
        }
        if (wartend.incrementAndGet() > 250) {
            wartend.decrementAndGet();
            warnung("Discord-Warteschlange ist voll; eine Meldung wurde verworfen.");
            return;
        }
        try {
            executor.execute(() -> sende(art, typ, text, 0));
        } catch (java.util.concurrent.RejectedExecutionException e) {
            wartend.decrementAndGet();
            if (!geschlossen) {
                LOG.warn("Discord-Auftrag konnte nicht angenommen werden.");
            }
        }
    }

    private void sende(String art, String typ, Supplier<String> text, int versuch) {
        boolean wiederholen = false;
        boolean eingeplant = false;
        long pause = 2000;
        try {
            Map<String, Object> config = konfiguration.get();
            if (geschlossen || !aktiv(config, art, typ)) {
                return;
            }
            URI uri = webhook(Karten.text(config, "webhook-url", ""));
            if (!uri.equals(aktiverWebhook)) {
                aktiverWebhook = uri;
                pausiertBis = 0;
                webhookUngueltig = false;
            }
            if (webhookUngueltig) {
                return;
            }
            long verbleibendePause = pausiertBis - System.currentTimeMillis();
            if (verbleibendePause > 0) {
                // Ein Webhook-Limit gilt auch für bereits wartende andere Meldungen.
                executor.schedule(() -> sende(art, typ, text, versuch), verbleibendePause, TimeUnit.MILLISECONDS);
                eingeplant = true;
                return;
            }
            var antwort = transport.senden(uri, json(text.get()));
            if (antwort.status() >= 200 && antwort.status() < 300) {
                return;
            }
            wiederholen = versuch < 3 && (antwort.status() == 429 || antwort.status() >= 500);
            pause = antwort.status() == 429 ? Math.max(1000, antwort.warteMillis()) : 2000;
            if (antwort.status() == 429) {
                long jetzt = System.currentTimeMillis();
                pausiertBis = pause > Long.MAX_VALUE - jetzt ? Long.MAX_VALUE : jetzt + pause;
            } else if (antwort.status() == 401 || antwort.status() == 404) {
                // Ein gelöschter Webhook oder ungültiger Token erholt sich nicht durch Wiederholungen.
                webhookUngueltig = true;
            } else if (antwort.status() >= 400 && antwort.status() < 500) {
                pausiertBis = System.currentTimeMillis() + 60_000L;
            }
            if (!wiederholen) {
                warnung("Discord-Meldung konnte nicht zugestellt werden (HTTP " + antwort.status() + ")."
                        + (webhookUngueltig ? " Neue gültige Webhook-Adresse einrichten." : ""));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // Weder Exception-Text noch Webhook-URL loggen: HTTP-Fehler können den Token enthalten.
            wiederholen = versuch < 3 && !(e instanceof IllegalArgumentException);
            if (!wiederholen) {
                warnung("Discord-Meldung konnte nicht zugestellt werden; Einrichtung und Verbindung prüfen.");
            }
        } finally {
            if (wiederholen && !geschlossen) {
                try {
                    executor.schedule(() -> sende(art, typ, text, versuch + 1), pause, TimeUnit.MILLISECONDS);
                    eingeplant = true;
                } catch (java.util.concurrent.RejectedExecutionException e) {
                    eingeplant = false;
                }
            }
            if (!eingeplant) {
                wartend.decrementAndGet();
            }
        }
    }

    static long warteMillis(double sekunden) {
        return Double.isFinite(sekunden) && sekunden >= 0 ? Math.max(1000L, (long) Math.ceil(sekunden * 1000)) : 2000L;
    }

    static boolean aktiv(Map<String, Object> config, String art) {
        return aktiv(config, art, "");
    }

    static boolean aktiv(Map<String, Object> config, String art, String typ) {
        if (!Karten.wahr(config, "aktiv", false)) {
            return false;
        }
        if (Karten.liste(config, "ausgeschlossene-typen").stream().anyMatch(e -> typ.equalsIgnoreCase(String.valueOf(e)))) {
            return false;
        }
        List<?> ereignisse = config.containsKey("ereignisse") ? Karten.liste(config, "ereignisse") : STANDARD;
        return ereignisse.stream().anyMatch(e -> art.equals(String.valueOf(e).toLowerCase(Locale.ROOT)));
    }

    static URI webhook(String text) {
        URI uri = URI.create(text.trim());
        if (!"https".equals(uri.getScheme()) || !List.of("discord.com", "discordapp.com").contains(uri.getHost())
                || uri.getUserInfo() != null || uri.getPort() != -1 || uri.getFragment() != null || uri.getQuery() != null
                || !uri.getPath().matches("/api/webhooks/[0-9]+/[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("Ungültiger Discord-Webhook.");
        }
        return uri;
    }

    static String json(String text) {
        String begrenzt = text.codePoints().limit(1900).collect(StringBuilder::new,
                StringBuilder::appendCodePoint, StringBuilder::append).toString();
        StringBuilder escaped = new StringBuilder();
        for (char c : begrenzt.toCharArray()) {
            switch (c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (c < 32) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return "{\"content\":\"" + escaped + "\",\"allowed_mentions\":{\"parse\":[]}}";
    }

    static String gruppe(HistoryType typ) {
        String name = typ.name();
        if (name.contains("IP")) return "ip";
        if (typ.istEdit() || name.endsWith("_EDIT")) return "bearbeitung";
        if (typ.istAufhebung() || name.endsWith("REMOVE")) return "aufhebung";
        if (name.startsWith("WARN")) return "verwarnung";
        if (name.startsWith("VERSTOSS")) return "verstoss";
        if (name.contains("MUTE")) return "mute";
        if (name.contains("TIMEOUT")) return "timeout";
        if (name.contains("FREEZE")) return "freeze";
        if (name.contains("KICK")) return "kick";
        return "bann";
    }

    private synchronized void warnung(String text) {
        long jetzt = System.currentTimeMillis();
        if (jetzt - letzteWarnung > 60_000) {
            letzteWarnung = jetzt;
            LOG.warn("{}", text);
        }
    }

    @Override
    public void close() {
        geschlossen = true;
        executor.shutdownNow();
        if (client != null) {
            client.shutdownNow();
        }
    }
}
