# PunishSystem v4.0-beta

**PunishSystem** ist ein hochperformantes, netzwerkweites Moderations- und Bestrafungssystem für Minecraft. Es wurde speziell für den Einsatz auf **Velocity-Proxies** mit **Paper/Purpur-Backend-Servern** entwickelt.

Der Velocity-Proxy fungiert dabei als **Source of Truth** für alle Netzwerkaktionen und Speicherung. Die Paper-Bridges erzwingen Mutes/Freezes/Timeouts lokal, bieten eine interaktive **PunishGUI** und leiten Befehle über einen kryptographisch signierten Plugin-Message-Tunnel an den Proxy weiter.

---

## Highlights & Features

- **Netzwerkweite Strafverwaltung**: Banns, Mutes, Timeouts, Freezes, Kicks, Verwarnungen, Verstoß-Punkte, Notizen und IP-Strafen.
- **Server-Scopes**: Strafen können global oder auf spezifische Server (`--global`, `--server <name>`) angewendet werden.
- **PunishGUI**: Interaktives In-Game-Menü für Moderatoren mit Live-Daten vom Proxy.
- **Chatfilter**: Flexibler Wortlistenfilter mit optionaler KI-Toxizitätsprüfung (ONNX-Runtime) und automatischem Staffel-Eskalationssystem.
- **Reports & Alt-Erkennung**: Report-System für Spieler und Alt-Account-Erkennung anhand von IP-Adressen.
- **Persistenz**: Speicherung wahlweise in **YAML** (atomar, gesichert) oder **MySQL** (HikariCP-Pool) mit automatischer Schema-Migration.
- **Hangar-Update-Integration**: `/punish-update` liest direkt von Hangar für sichere, gestagte In-Game-Updates mit SHA-256-Integritätsprüfung.
- **Datenschutz & LuckPerms**: IP-Maskierung nach DSGVO (Sichtbarkeit an `punish.alts` gekoppelt), volle LuckPerms-Kontext-Unterstützung mit Hochleistungs-Caching.

---

## Systemanforderungen

- **Java**: Mindestens **Java 21** (gebaut/getestet mit JDK 25, Bytecode Release 21).
- **Proxy**: Velocity **3.4.0**+
- **Backend-Server**: Paper / Purpur **1.21.x** (Modul `bridge-1.21`) oder Paper **26.1**+ (Modul `bridge`).
- **Optionale Abhängigkeiten**: LuckPerms, PlaceholderAPI.

---

## Installation & Schnellstart

### 1. Dateien platzieren
- **`PunishSystem-v4.0-beta-velocity.jar`** in den `plugins/`-Ordner des **Velocity-Proxys** legen.
- **`PunishSystem-v4.0-beta-bridge.jar`** (für Paper 26.1+) oder **`PunishSystem-v4.0-beta-bridge-1.21.jar`** (für Paper 1.21.x) in die `plugins/`-Ordner der **Backend-Server** legen.

Alle drei Jars gehören zusammen und müssen auf derselben Version laufen.

### 2. Tunnel-Secrets konfigurieren
1. Proxy und Backends einmal starten und stoppen.
2. In der `plugins/PunishSystemVelocity/config/general.yml` auf dem Proxy pro Bridge ein eigenes Secret eintragen:
   ```yaml
   netzwerk:
     bridge-secrets:
       lobby: "<SECRET_NUR_FUER_LOBBY>"
       survival: "<ANDERES_SECRET_NUR_FUER_SURVIVAL>"
   ```
3. Auf jeder Bridge in `plugins/PunishSystemBridge/config/general.yml` den eigenen Servernamen und **genau das zugehörige** Secret eintragen:
   ```yaml
   netzwerk:
     server-name: "lobby"
     tunnel-secret: "<SECRET_NUR_FUER_LOBBY>"
   ```
   Jede Bridge hat ein eigenes Secret. Ein gemeinsames Secret für mehrere Bridges ist unsicher und wird nicht unterstützt. Secrets mindestens 16 Zeichen lang und zufällig wählen; kein Beispiel-Secret aus dieser Doku übernehmen. Erzeugen z. B. mit `openssl rand -base64 32` oder PowerShell `[Convert]::ToBase64String((1..32 | ForEach-Object { Get-Random -Maximum 256 }))`.
4. Server starten. Die Bridges verbinden sich automatisch mit dem Proxy.

---

## Befehle & Rechte (Auszug)

| Befehl | Beschreibung | Haupt-Permission |
|---|---|---|
| `/ban <Spieler> <Dauer> [Grund] [--global\|--server]` | Bannt einen Spieler | `punish.ban` |
| `/mute <Spieler> <Dauer> [Grund] [--global\|--server]` | Mutet einen Spieler | `punish.mute` |
| `/timeout <Spieler> <Dauer> [Grund] [--global\|--server]` | Versetzt einen Spieler in Timeout | `punish.timeout` |
| `/freeze <Spieler> [Dauer] [Grund]` | Friert einen Spieler ein | `punish.freeze` |
| `/kick <Spieler> [Grund]` | Kickt einen Spieler vom Netzwerk | `punish.kick` |
| `/warn <Spieler> <Grund>` | Verwarnt einen Spieler | `punish.warn` |
| `/punish-gui [Spieler]` | Öffnet die interaktive PunishGUI | `punish.gui` |
| `/punish-status [Spieler\|self\|server]` | Zeigt aktive Strafen | `punish.status` |
| `/punish-history [Spieler\|self\|server]` | Zeigt den kompletten Strafverlauf | `punish.history` |
| `/alts <Spieler\|IP>` | Zeigt verknüpfte Zweitaccounts | `punish.alts` |
| `/report <Spieler> <Grund>` | Meldet einen Spieler an das Team | `punish.report` |
| `/reports` | Verwaltet offene Reports | `punish.reports` |
| `/punish-update [check\|apply]` | Prüft und installiert Updates | `punish.update` |
| `/punish-reload` | Lädt die Konfiguration neu | `punish.reload` |

Vollständige Befehls-, Rechte- und Konfigurationsübersicht findest du in der mitgelieferten Dokumentation unter `docs/index.html`.

---

## Lizenz

Das Plugin und sein Quellcode stehen unter der **MIT-Lizenz** (siehe [LICENSE](LICENSE)).

*Hinweis zum optionalen KI-Modell:* Das über Skripte ladbare Chatfilter-Modell (`ankekat1000/toxic-bert-german`) sowie dessen Vokabular stehen unter **CC BY-NC-SA 4.0** (nur nicht-kommerzielle Nutzung). Es ist nicht Teil der mitgelieferten JAR-Dateien.
