# PunishSystem v4.4.3

Dieser Patch ersetzt die irrtümlich als `v4.5.0` veröffentlichte Chatfilter-Korrektur. Die Chatfilter-Funktionen bleiben gegenüber dieser Veröffentlichung unverändert. Wer `v4.5.0` bereits installiert hat, muss alle drei Module einmalig manuell auf `v4.4.3` umstellen und regulär neu starten; der automatische Updater bietet niedrigere Versionsnummern nicht an.

PunishSystem verwaltet Strafen für Minecraft-Netzwerke mit einem Velocity-Proxy und Paper-/Purpur-Backends. Der Proxy führt Befehle aus und speichert die Daten. Je Backend setzt eine passende Bridge die Strafen durch und stellt GUI, Chatfilter und PlaceholderAPI bereit. Ein Betrieb der Bridge ohne Velocity ist nicht vorgesehen.

## Handbuch

Lies das [PunishSystem Handbuch](https://thelightrixx.github.io/PunishSystem/) oder öffne [docs/index.html](docs/index.html) direkt im Browser. Die 37 Themen führen von der Einrichtung bis zur Fehlerbehebung. Inhalte, Suche, Schriften und interaktive Beispiele funktionieren offline; die Werkzeuge führen keine echten Serverbefehle aus.

| Aufgabe | Einstieg |
|---|---|
| Netzwerk zum ersten Mal einrichten | [Installation und Voraussetzungen](docs/index.html#/installation) |
| Einen Moderationsfall durchspielen | [Moderations-Schnellstart](docs/index.html#/schnellstart) |
| Syntax, Beispiele oder Aliase finden | [Befehlsreferenz](docs/index.html#/befehle) |
| Meldungen bearbeiten oder Moderationsdaten durchsuchen | [Reports](docs/index.html#/reports), [Suche](docs/index.html#/befehle-einsicht/punish-search) |
| Rechte an Teamgruppen vergeben | [Rang-Bündel und LuckPerms](docs/index.html#/rang-buendel), [alle Permissions](docs/index.html#/rechte) |
| Eine Einstellung oder Nachricht ändern | [Dateien und Zuständigkeiten](docs/index.html#/config-uebersicht), [Zahlenwerte und Grenzen](docs/index.html#/config-general/wertebereiche) |
| Menüs, lokale Filterwerte oder Update-Prüfungen anpassen | [gui.yml](docs/index.html#/config-gui), [chatfilter.yml](docs/index.html#/config-chatfilter), [Update-Konfiguration](docs/index.html#/config-updates) |
| Downloads vor der Installation prüfen | [SHA-256 für Windows, Linux und macOS](docs/index.html#/installation/download-pruefen) |
| MySQL einrichten oder YAML-Daten übernehmen | [Speicher und Migration](docs/index.html#/speicher) |
| Eine bestehende Installation aktualisieren | [Updates und Sicherungen](docs/index.html#/betrieb) |
| Änderungen einer Version nachlesen | [Changelog](docs/index.html#/changelog) |
| Ein Problem untersuchen | [Fehlerbehebung](docs/index.html#/fehlerbehebung) |

Mit `Strg+K` (macOS: `⌘+K`) oder `/` durchsuchst du Befehle, Permissions und Konfigurationsschlüssel. Abschnittslinks führen direkt zur passenden Stelle. Die Druckfunktion gibt das ganze Handbuch mit aufgeklappten Details aus und erlaubt das Speichern als PDF.

Bei `/verstoss add` zeigt Tab nach einem Leerzeichen am Ende des Grundes `level=low`, `level=medium` und `level=high`. Die Vorschläge gibt es auch beim [Bearbeiten eines Verstoßes](docs/index.html#/befehle-bearbeiten/punish-edit).

## Voraussetzungen und Jars

Das [Release v4.4.3](https://github.com/thelightrixx/PunishSystem/releases/tag/v4.4.3) enthält alle drei Jars und `SHA256SUMS.txt`. Updates lassen sich für den nächsten Start installieren: Backends übernehmen die Bridge aus ihrem Update-Ordner; auf dem Linux-Proxy erledigt ein einmalig eingerichteter Start-Hook den JAR-Austausch.

| Einsatzort | Voraussetzung | Jar-Variante |
|---|---|---|
| Proxy | Velocity 3.4.0+, Java 21 | `velocity` |
| Backend mit Minecraft 1.21.x | Paper/Purpur 1.21.x, Java 21 | `bridge-1.21` |
| Backend mit Minecraft 26.1+ | Paper/Purpur 26.1+, Java 25 | `bridge` |

Installiere die Proxy-Jar und **genau eine passende Bridge pro Backend** in den jeweiligen `plugins/`-Ordner. Alle installierten Jars müssen dieselbe Version und Buildnummer haben. Folia wird nicht unterstützt. LuckPerms und PlaceholderAPI sind optional; ohne LuckPerms ist die Schutzprüfung bekannter Offline-Spieler eingeschränkt.

## Reihenfolge der Ersteinrichtung

1. Jars einspielen, Proxy und Backends einmal starten und wieder stoppen, damit die Konfigurationsdateien entstehen.
2. **Proxy-Speicher einrichten.** MySQL ist Standard und benötigt eine erreichbare Datenbank samt Benutzer. Für entfernte Datenbanken `speicher.mysql.use-ssl: true` setzen. Mit dieser Einstellung ist eine TLS-Verbindung erforderlich. Als Dateispeicher ist `speicher.typ: "yaml"` verfügbar. Scheitert der Speicherstart, läuft Velocity ohne PunishSystem-Strafverwaltung weiter.
3. **Pro Bridge ein eigenes zufälliges Tunnel-Secret** mit mindestens 16 Zeichen nach dem Trimmen setzen. Auf dem Proxy steht es unter `netzwerk.bridge-secrets.<Servername>`, auf der passenden Bridge unter `netzwerk.tunnel-secret`. Der Bridge-`server-name` muss zum registrierten Velocity-Server passen. Bei laufendem Proxy-Plugin kann `/punish-setup secrets` fehlende Secrets erzeugen.
4. Rechte auf dem Proxy vergeben, Server starten und `/punish-setup check` sowie `/punish-diagnostics` prüfen. Für Sync und Tunnel muss auf dem betroffenen Backend mindestens ein Spieler verbunden sein.
5. Mit einem ungeschützten Testaccount eine Strafe setzen, ihre Wirkung prüfen und im gleichen Scope wieder aufheben.

Geschützt sind ausschließlich Spieler mit direkt vergebenen `punish.ignore.*`- oder `punish.ignore.<typ>`-Rechten; der OP-Status ist keine Schutzquelle. Die Rang-Hierarchie bleibt davon unberührt. Geschützte Spieler bleiben in Status, History und den GUI-Aktenansichten sichtbar und werden dort gekennzeichnet.

Setze das [Scope-Flag](docs/index.html#/scopes) ans Ende des Befehls. Unvollständige oder widersprüchliche Flags werden abgelehnt. `/pardon` verlangt `--global`, `--server=<Name>`, `--<Server>` oder `--unbekannt`.

Die [Installationsanleitung](docs/index.html#/installation) enthält die vollständigen YAML-Beispiele, Dateipfade und Prüfschritte. Regeln und Texte werden zentral auf dem Proxy gepflegt; synchronisierte Bridge-Dateien sind keine unabhängige Konfiguration.

## Funktionen

- Banns, Timeouts, Mutes, Freezes, Kicks und IP-Strafen mit globalem oder serverbezogenem Scope.
- Lokaler VPN-Schutz für IPv4 und IPv6 mit 14 GitHub-Quellen, dauerhaftem Cache und IP-/CIDR-Freigaben. Strenger oder ausgewogener Modus, Quellen-/CIDR-Diagnose mit `/punish-vpn check <IP>` und Quellenstatus. Jeder abgelehnte VPN-Login wird geloggt und bei aktivierten Team-Meldungen ingame gemeldet; nur Routing-Hinweise bleiben gedrosselt. Konfiguration: [vpn-schutz](docs/index.html#/config-general/vpn-schutz).
- Verwarnungen, Verstoß-Punkte mit Auto-Bann, interne Notizen, Reports und IP-basierte Alt-Erkennung.
- Status, Hauptverlauf, Bearbeitung, Vorlagen, Undo, Statistik, JSON-Export und Suche über Historie, Notizen und Reports.
- PunishGUI, Wortlistenfilter und optionales lokales KI-Modell für den Chatfilter. VentureChat v3.8.1 wird vor dem Versand geprüft; bei synchron ausgelöstem Chat blockiert die Wortliste sofort und die KI meldet nachgelagert.
- MySQL-Speicherung mit YAML-Migration; YAML bleibt als Alternative nutzbar. Bridges halten Strafdaten im RAM.
- GitHub-Updates mit SHA-256-Prüfung und automatischer Übernahme beim nächsten Start: nativer Update-Ordner auf den Backends, Start-Hook auf dem Linux-Proxy, auch unter Pelican/Docker.

Beim Wechsel von einer Version vor `v4.2.0` alle JARs einmalig manuell auf `v4.4.3` aktualisieren und Proxy sowie Backends neu starten. Der Proxy erzeugt `plugins/update-pending/apply-update.sh`. Unter Linux/Pelican einmalig `sh plugins/update-pending/apply-update.sh ` vor den bisherigen Java-Startbefehl setzen; dessen Java-Argumente beibehalten. Anschließend den Proxy über diesen Startbefehl neu starten. Der Hook benötigt `sh`, `sha256sum`, `cp`, `mv` und `rm` im Container.

Für künftige stabile Updates `update.channel: "Release"` auf dem Proxy verwenden. `/punish-update apply confirm` lädt, prüft und verteilt die Dateien. Mit `/punish-update status` den aktiven Proxy-Start-Hook und die erfolgreichen Transferquittungen aller Backends prüfen, dann Backends und Proxy neu starten. Für die Verteilung muss auf jedem Backend ein Spieler verbunden sein. Ohne Start-Hook bleibt die Proxy-JAR vorgemerkt; das Plugin weist darauf hin. Die [Update-Anleitung](docs/index.html#/betrieb) beschreibt Einrichtung, Sicherung und Fehlerbehandlung.

Ab v4.4.3 verhindert `chatfilter.ki-freigaben` in der Proxy-`general.yml` KI-Fehlalarme für vollständige Minecraft-Begriffe wie `xpfarm`, `XP-Farm` und `redstone`. Die Vorgabe enthält 31 Einträge. Satzzeichen und Groß-/Kleinschreibung werden für die Freigabe vereinheitlicht: `-xpfarm` und `xpfarm!` sind ebenfalls frei. Zusätzlicher Text wird weiterhin vollständig durch die KI geprüft; die Sperrwortliste hat Vorrang. Freigaben erzeugen weder Meldungen noch Staffel-Treffer. Eigene Begriffe oder vollständige Phrasen lassen sich ergänzen; `[]` deaktiviert die Freigaben. Die fehlende Option wird beim Config-Abgleich ergänzt und per Text-Sync verteilt. Danach Proxy und alle Bridges gemeinsam aktualisieren und regulär neu starten. Das vorhandene KI-Modell und Vokabular bleiben verwendbar. Details und Testfälle: [Minecraft-Freigaben](docs/index.html#/chatfilter/minecraft-freigaben).

Ab v4.4.2 ergänzt die neue Version beim Start und beim Laden der Konfiguration auch die vorhandenen YAML-Dateien um fehlende Schlüssel und aktuelle Standardkommentare. Das gilt auf dem Proxy für `general.yml`, `messages.yml`, `screens.yml`, `broadcasts.yml` und `gui.yml`, auf den Bridges zusätzlich für `chatfilter.yml`. Eigene Werte, Texte, Listen, Zusatzschlüssel und lokale Verbindungsdaten bleiben erhalten; Strafen und Datenbankbestände werden dabei nicht geändert. Vor jeder tatsächlichen Dateianpassung entsteht daneben eine Sicherung mit der Endung `.vor-config-update.bak`. Fehlerhafte oder leere Dateien bleiben unverändert und werden im Log gemeldet. Nach erfolgreichem Abgleich wird bei unverändertem Stand nicht erneut geschrieben. Details: [automatischer Config-Abgleich](docs/index.html#/config-uebersicht/config-abgleich).

Der VPN-Schutz ist ab v4.3.0 auch bei vorhandener Konfiguration standardmäßig aktiv. In v4.4.2 bleibt `vpn-schutz.modus: streng` der Standard: Ein Listentreffer lehnt den Login ab, ohne eine Strafe zu speichern. `ausgewogen` meldet reine Routing-Treffer und sperrt nur Treffer aus VPN-Quellen. Neben den VPN-Kategorien von [X4BNet](https://github.com/X4BNet/lists_vpn), [aaronburt](https://github.com/aaronburt/vpn-block-list) und den [Proton-Routing-Netzen](https://github.com/tn3w/ProtonVPN-IPs) enthalten die Standards zehn [Anbieterlisten](https://github.com/Joe12387/open-source-vpn-ip-lists): AirVPN, IVPN, Mullvad, NordVPN, OVPN, Private Internet Access, ProtonVPN, RiseupVPN, Surfshark und Windscribe. Allgemeine Hosting-/Rechenzentrumslisten werden nicht pauschal eingebunden. Auch Anbieter-Serveradressen können Ein- statt Austrittsadressen sein; öffentliche Listen können VPNs übersehen oder fremdgenutzte Adressen erfassen.

Beim Update auf v4.4.2 alle Module gemeinsam aktualisieren und neu starten. Jeder vom VPN-Schutz abgelehnte Login wird ohne IP-Cooldown oder globale Meldungsbegrenzung im Proxy-Log erfasst und bei `vpn-schutz.team-meldungen: true` ingame gemeldet. Der Cooldown und die globale Begrenzung gelten nur noch für reine Routing-Hinweise im ausgewogenen Modus. Bestehende Rechte gelten weiter: `punish.vpn` für Diagnose, `punish.message.vpn` für Team-Meldungen; vollständige IPs/CIDRs zusätzlich mit `punish.alts`. Standard-Rangbündel enthalten Diagnose und Meldungen ab Moderator; bestehende eigene Bündel gezielt ergänzen oder Einzelrechte vergeben. Eine ausdrücklich gesetzte `vpn-schutz.quellen`-Liste bleibt erhalten; ohne diesen Schlüssel gelten die 14 Standardquellen. Datenbankschema, Konfigurationsschlüssel und Nachrichten bleiben kompatibel. `190.2.154.240` wird weiterhin über `190.2.128.0/19` erfasst und im strengen Modus abgelehnt.

Downloads laufen beim Start, Reload und danach alle sechs Stunden im Hintergrund. Die Login-Prüfung nutzt nur RAM; Spieler-IPs werden nicht an GitHub übermittelt. Ohne gültigen lokalen Stand bleiben Logins erlaubt, spätere Downloadfehler behalten den letzten gültigen Stand. Fehlzuordnungen über `vpn-schutz.freigaben` ausnehmen; Banns bleiben wirksam. `/punish-vpn status` unterscheidet letzten erfolgreichen Download, erste/letzte lokal beobachtete Inhaltsänderung und Downloadfehler. Zeitangaben alter Caches bleiben zunächst unbekannt. Die tatsächliche Datenerzeugung beim Anbieter wird nicht automatisch ermittelt.

Stand der Quellenprüfung am 21.11.2026: Alle 14 Standardquellen wurden mit dem produktiven Downloader und Parser erfolgreich geprüft. Die jüngsten Proton-Upstream-Builds schlugen jedoch fehl; der letzte erfolgreiche Build war am 17.11.2026. Ein erfolgreicher Download bestätigt Erreichbarkeit und gültiges Format, nicht eine neue Datenerzeugung beim Anbieter. Die VentureChat-Korrektur ist durch Regressionstests in beiden Bridge-Modulen geprüft; ein Live-Test mit deinem Server gehört weiterhin zur Update-Prüfung.

## Lizenz

Plugin und Quellcode stehen unter der [MIT-Lizenz](LICENSE).

Das optionale Chatfilter-Modell `ankekat1000/toxic-bert-german` und sein Vokabular werden nicht mit den Jars ausgeliefert. Das Repository führt dafür die Lizenzangabe **CC BY-NC-SA 4.0**. Hinweise zur separaten Bereitstellung stehen unter [Chatfilter](docs/index.html#/chatfilter).
