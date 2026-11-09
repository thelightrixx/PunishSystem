# PunishSystem v4.0.0-preview

PunishSystem verwaltet Strafen für Minecraft-Netzwerke mit einem Velocity-Proxy und Paper-/Purpur-Backends. Der Proxy führt Befehle aus und speichert die Daten. Je Backend setzt eine passende Bridge die Strafen durch und stellt GUI, Chatfilter und PlaceholderAPI bereit. Ein Betrieb der Bridge ohne Velocity ist nicht vorgesehen.

## Dokumentation

Die vollständige Dokumentation liegt in [docs/index.html](docs/index.html). Öffne diese Datei lokal im Browser; sie funktioniert ohne Internetverbindung. Die Navigation ist nach Aufgaben geordnet, die Suche findet auch Befehle, Permissions und Konfigurationsschlüssel.

| Aufgabe | Einstieg |
|---|---|
| Netzwerk zum ersten Mal einrichten | [Installation und Voraussetzungen](docs/index.html#/installation) |
| Einen Moderationsfall durchspielen | [Moderations-Schnellstart](docs/index.html#/schnellstart) |
| Syntax, Beispiele oder Aliase finden | [Befehlsreferenz](docs/index.html#/befehle) |
| Rechte an Teamgruppen vergeben | [Rang-Bündel und LuckPerms](docs/index.html#/rang-buendel), [alle Permissions](docs/index.html#/rechte) |
| Eine Einstellung oder Nachricht ändern | [Dateien und Zuständigkeiten](docs/index.html#/config-uebersicht) |
| MySQL einrichten oder YAML-Daten übernehmen | [Speicher und Migration](docs/index.html#/speicher) |
| Eine bestehende Installation aktualisieren | [Updates und Sicherungen](docs/index.html#/betrieb) |
| Ein Problem untersuchen | [Fehlerbehebung](docs/index.html#/fehlerbehebung) |
| Das Projekt bauen | [Bauen und Entwickeln](docs/index.html#/entwicklung) |

Bei `/verstoss add` zeigt Tab nach einem Leerzeichen am Ende des Grundes `level=low`, `level=medium` und `level=high`. Die Vorschläge gibt es auch beim [Bearbeiten eines Verstoßes](docs/index.html#/befehle-bearbeiten/punish-edit).

## Voraussetzungen und Jars

| Einsatzort | Voraussetzung | Jar-Variante |
|---|---|---|
| Proxy | Velocity 3.4.0+, Java 21 | `velocity` |
| Backend mit Minecraft 1.21.x | Paper/Purpur 1.21.x, Java 21 | `bridge-1.21` |
| Backend mit Minecraft 26.1+ | Paper/Purpur 26.1+, Java 25 | `bridge` |

Installiere die Proxy-Jar und **genau eine passende Bridge pro Backend** in den jeweiligen `plugins/`-Ordner. Alle installierten Jars müssen dieselbe Version und Buildnummer haben. Folia wird nicht unterstützt. LuckPerms und PlaceholderAPI sind optional; ohne LuckPerms ist die Schutzprüfung bekannter Offline-Spieler eingeschränkt.

## Reihenfolge der Ersteinrichtung

1. Jars einspielen, Proxy und Backends einmal starten und wieder stoppen, damit die Konfigurationsdateien entstehen.
2. **Zuerst den Proxy-Speicher einrichten:** MySQL ist Standard und benötigt eine erreichbare Datenbank samt Benutzer. Alternativ `speicher.typ: "yaml"` setzen. Scheitert der Speicherstart, läuft Velocity ohne PunishSystem-Strafverwaltung weiter.
3. **Pro Bridge ein eigenes zufälliges Tunnel-Secret** mit mindestens 16 Zeichen nach dem Trimmen setzen. Auf dem Proxy steht es unter `netzwerk.bridge-secrets.<Servername>`, auf der passenden Bridge unter `netzwerk.tunnel-secret`. Der Bridge-`server-name` muss zum registrierten Velocity-Server passen. Bei laufendem Proxy-Plugin kann `/punish-setup secrets` fehlende Secrets erzeugen.
4. Rechte auf dem Proxy vergeben, Server starten und `/punish-setup check` sowie `/punish-diagnostics` prüfen. Für Sync und Tunnel muss auf dem betroffenen Backend mindestens ein Spieler verbunden sein.
5. Mit einem ungeschützten Testaccount eine Strafe setzen, ihre Wirkung prüfen und im gleichen Scope wieder aufheben.

Geschützt sind ausschließlich Spieler mit direkt vergebenen `punish.ignore.*`- oder `punish.ignore.<typ>`-Rechten; der OP-Status ist keine Schutzquelle. Die Rang-Hierarchie bleibt davon unberührt. Geschützte Spieler bleiben in Status, History und den GUI-Aktenansichten sichtbar und werden dort gekennzeichnet.

Setze das [Scope-Flag](docs/index.html#/scopes) ans Ende des Befehls. Unvollständige oder widersprüchliche Flags werden abgelehnt. `/pardon` verlangt `--global`, `--server=<Name>`, `--<Server>` oder `--unbekannt`.

Die [Installationsanleitung](docs/index.html#/installation) enthält die vollständigen YAML-Beispiele, Dateipfade und Prüfschritte. Regeln und Texte werden zentral auf dem Proxy gepflegt; synchronisierte Bridge-Dateien sind keine unabhängige Konfiguration.

## Funktionen

- Banns, Timeouts, Mutes, Freezes, Kicks und IP-Strafen mit globalem oder serverbezogenem Scope.
- Verwarnungen, Verstoß-Punkte mit Auto-Bann, interne Notizen, Reports und IP-basierte Alt-Erkennung.
- Status, Hauptverlauf, Bearbeitung, Vorlagen, Undo, Statistik und JSON-Export.
- PunishGUI, Wortlistenfilter und optionales lokales KI-Modell für den Chatfilter.
- MySQL-Speicherung mit YAML-Migration; YAML bleibt als Alternative nutzbar. Bridges halten Strafdaten im RAM.
- GitHub-Updates mit SHA-256-Prüfung: Jars werden bereitgestellt und anschließend manuell ausgetauscht; danach alle Server neu starten.

## Aus dem Quellcode bauen

JDK 25 und Maven werden benötigt. Die eigenen Klassen verwenden Java-Release 21. Unter Windows baut `build_all_jars.bat nobump` alle Jars mit Tests, ohne die Buildnummer zu ändern. Alternativ:

```shell
mvn verify
```

Das Projekt enthält `common`, `velocity`, `bridge` und `bridge-1.21`. Gemeinsame Bridge-Quellen und -Tests liegen unter `bridge/src/`; `bridge-1.21` übernimmt sie beim Build. Vor dem ersten IDE-Start `mvn -pl bridge-1.21 -am test-compile` ausführen. Weitere Build-Befehle und die separat gepflegten API-Klassen erklärt [Bauen und Entwickeln](docs/index.html#/entwicklung).

## Lizenz

Plugin und Quellcode stehen unter der [MIT-Lizenz](LICENSE).

Das optionale Chatfilter-Modell `ankekat1000/toxic-bert-german` und sein Vokabular werden nicht mit den Jars ausgeliefert. Das Repository führt dafür die Lizenzangabe **CC BY-NC-SA 4.0**. Hinweise zur separaten Bereitstellung stehen unter [Chatfilter](docs/index.html#/chatfilter).
