# PunishSystem v4.5.1

PunishSystem verwaltet die Moderation eines Minecraft-Netzwerks mit Velocity und Paper/Purpur. Auf dem Proxy liegen Befehle, Konfiguration und Strafdaten. Eine Bridge auf jedem Backend setzt die Strafen durch und ergänzt Menüs, Chatfilter und PlaceholderAPI.

Du kannst Bans, Mutes, Timeouts und weitere Strafen global oder für einzelne Server vergeben. Reports, Fallnotizen und Beweise lassen sich in Moderationsfällen zusammenführen. Ein Strafkatalog hilft bei wiederholten Verstößen; VPN-Schutz, Chatfilter und Discord-Meldungen sind ebenfalls enthalten.

## Einrichten

| Einsatzort | Voraussetzung | JAR-Endung |
|---|---|---|
| Proxy | Velocity ab 3.4.0, Java 21 | `velocity.jar` |
| Backend | Paper/Purpur 1.21.x, Java 21 | `bridge-1.21.jar` |
| Backend | Paper/Purpur 26.1, Java 25 | `bridge.jar` |

Installiere die Proxy-JAR und je Backend genau eine Bridge, jeweils in derselben Version. Die Bridge ist kein eigenständiges Moderationsplugin. Folia wird nicht unterstützt.

Die 1.21-Bridge behält ihre bisherige API-Kennung `1.21` und wird gegen Paper 1.21.8 gebaut.

Richte zuerst den Speicher auf dem Proxy ein: MySQL ist die Vorgabe, YAML eine Alternative. Danach braucht jede Bridge ein eigenes Tunnel-Secret, das auf Proxy und Backend übereinstimmt. Die [Installation](docs/index.html#/installation) enthält alle Dateipfade und Beispiele. Mit `/punish-setup check` und `/punish-diagnostics` prüfst du die Einrichtung.

Updates auf dem Proxy benötigen unter Linux einen Start-Hook; die Backends verwenden ihren Update-Ordner. Lies dafür [Betrieb und Updates](docs/index.html#/betrieb), bevor du `/punish-update apply confirm` verwendest.

## Nachlesen

Das [Handbuch](https://thelightrixx.github.io/PunishSystem/) erklärt [erste Moderationsschritte](docs/index.html#/schnellstart), [Befehle](docs/index.html#/befehle), [Rechte](docs/index.html#/rechte) und [Konfigurationsdateien](docs/index.html#/config-uebersicht). Du kannst auch `docs/index.html` direkt im Browser öffnen; Suche, Schriften und Druckansicht funktionieren offline.

v4.5.2 wurde zurückgezogen: Sie enthielt nur Änderungen an Werkzeugen und Dokumentation. Aktuell bleibt v4.5.1 mit den ursprünglichen JARs. Wer v4.5.2 bereits installiert hat, kann bei gestoppten Servern Proxy und Bridges gemeinsam manuell durch v4.5.1 ersetzen. Der automatische Updater bietet keine niedrigeren Versionen an.

## Download und eigener Build

Im [Release v4.5.1](https://github.com/thelightrixx/PunishSystem/releases/tag/v4.5.1) findest du die JARs und `SHA256SUMS.txt`. Prüfe den Hash der gewählten Datei vor der Installation.

Zum Bauen brauchst du JDK 25 oder neuer und Python ab 3.10. Unter Windows führt `build_all_jars.bat nobump ci` alle Prüfungen aus und stellt die Ergebnisse in `dist/` bereit. Maven wird gefunden oder als Version 3.9.11 heruntergeladen. Die [Entwicklungsanleitung](docs/index.html#/projektstandard) beschreibt auch den Build unter Linux und macOS. Ein Build erhöht keine Versionsnummer.

## Lizenz

Plugin und Quellcode: [MIT](LICENSE). Die Lizenzhinweise der Handbuchschriften liegen unter [docs/licenses/](docs/licenses/).
