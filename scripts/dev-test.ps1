#Requires -Version 5.1
<#
.SYNOPSIS
    Baut PunishSystem und bereitet einen lokalen Paper-Server für Bridge-Tests vor.
.DESCRIPTION
    Der Server liegt unter test-server/ und muss an 127.0.0.1 gebunden sein.
    Für Tests von Strafen und Scopes ist zusätzlich ein eingerichteter Velocity-Proxy nötig.
    -PrepareOnly bereitet nur vor, -NoBuild verwendet die aktuelle gebaute Version.
    -AcceptEula bestätigt die Minecraft-EULA für diesen Testserver.
    -ResetData verschiebt Bridge-Daten in eine Sicherung unter .tools/test-backups/.
    Welten und vorhandene Servereinstellungen bleiben erhalten.
#>
[CmdletBinding()]
param(
    [string]$SpielerName = "",
    [string]$McVersion = "1.21.8",
    [string]$Ram = "2G",
    [int]$ServerPort = 25565,
    [switch]$OnlineMode,
    [switch]$NoBuild,
    [switch]$ResetData,
    [switch]$PrepareOnly,
    [switch]$AcceptEula
)

$ErrorActionPreference = "Stop"

# --- 0. Root prüfen ---
$Root = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$PomPath = Join-Path $Root "pom.xml"
if (-not (Test-Path -LiteralPath $PomPath)) {
    throw "pom.xml nicht gefunden. Der Projektordner muss pom.xml enthalten."
}
$ServerDir = Join-Path $Root "test-server"
$PluginsDir = Join-Path $ServerDir "plugins"
$PaperJar = Join-Path $ServerDir "paper.jar"

# --- 1. JDK 25 aufloesen ---
function Find-Jdk25 {
    $Kandidaten = @()
    $Temurin25 = Join-Path $env:USERPROFILE ".jdks\temurin-25\bin\java.exe"
    if (Test-Path -LiteralPath $Temurin25) { $Kandidaten += $Temurin25 }
    $Temurin21 = Join-Path $env:USERPROFILE ".jdks\temurin-21.0.9\bin\java.exe"
    if (Test-Path -LiteralPath $Temurin21) { $Kandidaten += $Temurin21 }
    if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
        $Kandidaten += (Join-Path $env:JAVA_HOME "bin\java.exe")
    }
    $Jbr = Get-ChildItem "C:\Program Files\JetBrains\IntelliJ IDEA*\jbr\bin\java.exe" -ErrorAction SilentlyContinue |
        Select-Object -First 1 -ExpandProperty FullName
    if ($Jbr) { $Kandidaten += $Jbr }
    $PathJava = (Get-Command "java.exe" -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source -ErrorAction SilentlyContinue)
    if ($PathJava) { $Kandidaten += $PathJava }
    foreach ($J in $Kandidaten) {
        if (-not (Test-Path -LiteralPath $J)) { continue }
        try {
            $Out = & $J --version | Out-String
            if ($Out -match '(?:openjdk|java) (?<major>\d+)' -and [int]$Matches.major -ge 25 -and (Test-Path -LiteralPath (Join-Path (Split-Path $J -Parent) "javac.exe"))) {
                return $J
            }
        } catch { Write-Verbose "JDK-Prüfung für $J fehlgeschlagen: $_" }
    }
    return $null
}

$JavaExe = Find-Jdk25
if (-not $JavaExe) {
    throw "Kein JDK 25 gefunden. Erwartet: %USERPROFILE%\.jdks\temurin-25\bin\java.exe oder JAVA_HOME auf JDK 25 setzen. Projekt baut mit JDK 25 (maven.compiler.release=21)."
}
$JavaHome = (Get-Item (Split-Path (Split-Path $JavaExe -Parent) -Parent)).FullName
Write-Host "JDK 25: $JavaExe"

# Derselbe vollständige Build wie in CI; -NoBuild verwendet vorhandene geprüfte Dateien.
if (!$NoBuild) { & (Join-Path $PSScriptRoot 'build.ps1') }

# Bridge-Modul passend zur Zielversion: 1.21.x -> bridge-1.21, neuere Paper -> bridge.
$BridgeModul = "bridge-1.21"
if (-not $McVersion.TrimStart("vV").StartsWith("1.21")) {
    $BridgeModul = "bridge"
}
Write-Host "Bridge-Modul für Paper ${McVersion}: $BridgeModul"
[xml]$ProjectPom = Get-Content -LiteralPath $PomPath -Raw -Encoding UTF8
$Version = $ProjectPom.project.version
$JarPath = Join-Path $Root "$BridgeModul/target/PunishSystem-$Version-$BridgeModul.jar"
if (!(Test-Path -LiteralPath $JarPath -PathType Leaf)) { throw "JAR fehlt: $JarPath. Zuerst vollständig bauen." }
$Jar = Get-Item -LiteralPath $JarPath
Write-Host "Bridge: $($Jar.FullName)"
Write-Host 'Für Moderations- und Scope-Tests muss die Bridge mit einem Velocity-Proxy verbunden sein.'

foreach ($Directory in @($ServerDir, $PluginsDir)) {
    if ((Test-Path -LiteralPath $Directory) -and ((Get-Item -LiteralPath $Directory).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
        throw "Testverzeichnisse dürfen keine Verzeichnislinks sein: $Directory"
    }
}

# --- 4. Server-Ordner anlegen ---
if (-not (Test-Path -LiteralPath $ServerDir)) {
    New-Item -ItemType Directory -Path $ServerDir | Out-Null
}
if (-not (Test-Path -LiteralPath $PluginsDir)) {
    New-Item -ItemType Directory -Path $PluginsDir | Out-Null
}

# --- 5. Paper herunterladen (nur wenn fehlt, mit SHA256-Pruefung, Abbruch bei Mismatch) ---
if (-not (Test-Path -LiteralPath $PaperJar)) {
    Write-Host "Lade Paper $McVersion herunter (einmalig, ca. 50 MB)..."
    $DownloadOk = $false
    try {
        $Antwort = Invoke-RestMethod -Uri "https://fill.papermc.io/v3/projects/paper/versions/$McVersion/builds" -TimeoutSec 30
        if ($Antwort -is [System.Array]) { $BuildListe = $Antwort }
        elseif ($null -ne $Antwort.builds) { $BuildListe = $Antwort.builds } else { $BuildListe = $Antwort }
        $Latest = ($BuildListe | Sort-Object id -Descending | Select-Object -First 1)
        $DlUrl = $Latest.downloads.'server:default'.url
        $ErwartetesSha = $Latest.downloads.'server:default'.checksums.sha256
        if ([string]::IsNullOrWhiteSpace($ErwartetesSha)) { $ErwartetesSha = $Latest.downloads.'server:default'.sha256 }
        if ([string]::IsNullOrWhiteSpace($ErwartetesSha)) { $ErwartetesSha = $Latest.downloads.application.sha256 }
        if ([string]::IsNullOrWhiteSpace($ErwartetesSha)) { $ErwartetesSha = $Latest.checksums.sha256 }
        if ([string]::IsNullOrWhiteSpace($ErwartetesSha)) { $ErwartetesSha = $Latest.sha256 }
        if ([string]::IsNullOrWhiteSpace($DlUrl)) { throw "Kein Download-Link in API-Antwort." }
        if ([string]::IsNullOrWhiteSpace($ErwartetesSha)) { throw "Kein SHA256 in API-Antwort (fail-closed, Abbruch)." }
        Write-Host "Build $($Latest.id) : $DlUrl"
        Invoke-WebRequest -Uri $DlUrl -OutFile $PaperJar -TimeoutSec 600
        $Aktuell = (Get-FileHash -LiteralPath $PaperJar -Algorithm SHA256).Hash
        if ($Aktuell.ToLowerInvariant() -ne $ErwartetesSha.Trim().ToLowerInvariant()) {
            Remove-Item -LiteralPath $PaperJar -Force -ErrorAction SilentlyContinue
            throw "SHA256-Pruefsumme stimmt nicht (Datei gelöscht, Abbruch)."
        }
        Write-Host "SHA256 ok."
        $DownloadOk = $true
    } catch {
        Remove-Item -LiteralPath $PaperJar -Force -ErrorAction SilentlyContinue
        Write-Warning "Paper-Download/Prüfung fehlgeschlagen: $($_.Exception.Message)"
    }
    if (-not $DownloadOk) {
        throw "Paper-Download fehlgeschlagen. Manuell laden (https://papermc.io/downloads/paper) und als test-server/paper.jar ablegen."
    }
    if (-not (Test-Path -LiteralPath $PaperJar)) { throw "Paper-Download fehlgeschlagen." }
    Write-Host "Paper gespeichert: $PaperJar"
} else {
    Write-Host "Paper vorhanden: $PaperJar"
}

# --- 6. Standard-Config (nur anlegen, nie ueberschreiben) ---
$Eula = Join-Path $ServerDir "eula.txt"
if (-not (Test-Path -LiteralPath $Eula)) {
    Set-Content -LiteralPath $Eula -Value "eula=false" -Encoding Ascii
}
if ($AcceptEula) { Set-Content -LiteralPath $Eula -Value "eula=true" -Encoding Ascii }
$Props = Join-Path $ServerDir "server.properties"
if (-not (Test-Path -LiteralPath $Props)) {
    $OnlineStr = "false"
    if ($OnlineMode) { $OnlineStr = "true" }
    $PropsContent = @(
        "# Auto-generiert von scripts/dev-test.ps1 (nur beim ersten Start, danach von Hand editierbar)",
        "motd=PunishSystem DEV",
        "server-ip=127.0.0.1",
        "server-port=$ServerPort",
        "online-mode=$OnlineStr",
        "white-list=false",
        "spawn-protection=0",
        "max-players=10",
        "difficulty=peaceful",
        "gamemode=creative",
        "enable-command-block=false"
    )
    Set-Content -LiteralPath $Props -Value $PropsContent -Encoding Ascii
} else {
    if ($OnlineMode) {
        # Explizit gewuenscht: online-mode einschalten
        $Text = Get-Content -LiteralPath $Props -Raw
        $Text = $Text -replace 'online-mode\s*=\s*(true|false)', 'online-mode=true'
        Set-Content -LiteralPath $Props -Value $Text -Encoding Ascii
    }
}

if ((Get-Content -LiteralPath $Props -Raw) -notmatch '(?m)^server-ip=127\.0\.0\.1\s*$') {
    throw 'Der lokale Testserver muss in server.properties ausdrücklich server-ip=127.0.0.1 verwenden. Vorhandene Einstellungen wurden nicht ersetzt.'
}
if ($SpielerName) { Write-Host "Nach dem Start in der Serverkonsole ausführen: op $SpielerName" }

# --- 7. Plugin kopieren ---
$OtherJars = @(Get-ChildItem -LiteralPath $PluginsDir -File -Filter 'PunishSystem*.jar' | Where-Object { $_.Name -ne 'PunishSystemBridge.jar' })
if ($OtherJars.Count) { throw 'Andere PunishSystem-JARs liegen im Testserver. Vor dem Start prüfen und doppelte Varianten entfernen.' }
Copy-Item -LiteralPath $Jar.FullName -Destination (Join-Path $PluginsDir "PunishSystemBridge.jar") -Force
Write-Host "Plugin kopiert nach test-server/plugins/"

if ($ResetData) {
    $DataDir = Join-Path $PluginsDir "PunishSystemBridge"
    if (Test-Path -LiteralPath $DataDir) {
        $BackupRoot = [IO.Path]::GetFullPath((Join-Path $Root '.tools/test-backups'))
        $Backup = Join-Path $BackupRoot ([Guid]::NewGuid().ToString('N') + '-PunishSystemBridge')
        foreach ($Target in @($DataDir, $Backup)) {
            $AbsoluteTarget = [IO.Path]::GetFullPath($Target)
            if (!$AbsoluteTarget.StartsWith($Root + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Sicherungsziel liegt außerhalb des Projekts.' }
        }
        foreach ($Directory in @((Join-Path $Root '.tools'), $BackupRoot, $DataDir)) {
            if ((Test-Path -LiteralPath $Directory) -and ((Get-Item -LiteralPath $Directory).Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'Sicherungsordner darf kein Verzeichnislink sein.' }
        }
        New-Item -ItemType Directory -Path $BackupRoot -Force | Out-Null
        Move-Item -LiteralPath $DataDir -Destination $Backup
        Write-Host "Bridge-Daten gesichert: $Backup"
    }
}

if ($PrepareOnly) { Write-Host "Testserver vorbereitet: $ServerDir"; return }
if ((Get-Content -LiteralPath $Eula -Raw) -notmatch '(?m)^eula=true\s*$') {
    throw 'Vor dem Start https://aka.ms/MinecraftEULA lesen und danach mit -AcceptEula starten.'
}

# --- 8. Starten ---
Write-Host ""
Write-Host "Starte Paper $McVersion auf Port $ServerPort ..."
if (-not $OnlineMode) {
    Write-Host "Offline-Modus: im Launcher 'Ohne Konto spielen' bzw. beliebiger Name, Server-Adresse: localhost:$ServerPort"
    Write-Host "Tipp: zweiten Client mit anderem Namen starten (z.B. Tester123) zum Bestrafen."
} else {
    Write-Host "Online-Modus: nur echte Accounts, Server-Adresse: localhost:$ServerPort"
}
Write-Host "Beenden: in dieser Konsole 'stop' eingeben."
Write-Host ""

& $JavaExe -Xms$Ram -Xmx$Ram -jar $PaperJar nogui
