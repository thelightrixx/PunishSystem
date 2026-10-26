#Requires -Version 5.1
<#
.SYNOPSIS
    1-Klick Ingame-Test: baut das Plugin und startet einen lokalen Paper-Testserver.

.DESCRIPTION
    Datei ist bewusst ASCII-only (keine Umlaute), damit Windows PowerShell 5.1
    sie auch ohne BOM korrekt liest.

    Ablauf:
      1. Sucht JDK 25 (temurin-25 im User-Profil, sonst JAVA_HOME, sonst PATH).
      2. Sucht Maven (PATH, sonst IntelliJ-bundled Maven).
      3. Baut per "mvn -q package -DskipTests" (mit -NoBuild ueberspringbar, Module velocity+bridge).
      4. Legt test-server/ an (EULA auto, server.properties, ops.json).
      5. Laedt Paper 1.21.x einmalig herunter (PaperMC-API, Fallback feste Version).
      6. Kopiert die frische Bridge-Jar nach test-server/plugins/.
         Hinweis: Bridge-only Testnetz. Fuer Velocity-Netz: zusaetzlich
         velocity/target Jar auf einen Velocity-Proxy (3.4.0) legen.
      7. Startet den Server. Beenden mit "stop" in der Konsole.

    Der Ordner test-server/ wird bei Bedarf neu angelegt.

.PARAMETER SpielerName
    Dein Minecraft-Name (Offline-Modus). Wird als OP eingetragen.
    Kann auch nachtraeglich in test-server/ops.json geaendert werden.

.PARAMETER McVersion
    Minecraft-Version, z.B. "1.21.8". Default: "1.21.8".
    Muss zur api-version aus plugin.yml passen (1.21).

.PARAMETER Ram
    RAM fuer den Server, z.B. "2G". Default: "2G".

.PARAMETER ServerPort
    Server-Port. Default: 25565.

.PARAMETER OnlineMode
    Wenn gesetzt, startet der Server mit online-mode=true (nur echte
    Mojang-Accounts). Default ist offline (false), damit du ohne
    Auth und mit Zweit-Client (z.B. "Tester123")joinen kannst.

.PARAMETER NoBuild
    Build ueberspringen (nimmt die neueste Jar aus target/).

.PARAMETER ResetData
    Loescht Plugin-Daten (test-server/plugins/PunishSystemBridge/) vor dem Start,
    fuer frische Tests (Banns/Mutes/Verstoesse weg).

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts/dev-test.ps1 -SpielerName "DeinName"

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts/dev-test.ps1 -SpielerName "DeinName" -ResetData
#>
[CmdletBinding()]
param(
    [string]$SpielerName = "",
    [string]$McVersion = "1.21.8",
    [string]$Ram = "2G",
    [int]$ServerPort = 25565,
    [switch]$OnlineMode,
    [switch]$NoBuild,
    [switch]$ResetData
)

$ErrorActionPreference = "Stop"

# --- 0. Root pruefen ---
$Root = Get-Location
$PomPath = Join-Path $Root "pom.xml"
if (-not (Test-Path -LiteralPath $PomPath)) {
    throw "pom.xml nicht gefunden. Skript aus dem Projekt-Root starten (Ordner mit pom.xml)."
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
            $Out = & $J -version 2>&1 | Out-String
            if ($Out -match '"25\.' -or $Out -match ' 25\.' -or $Out -match '25\.0\.') {
                return $J
            }
        } catch { }
    }
    return $null
}

$JavaExe = Find-Jdk25
if (-not $JavaExe) {
    throw "Kein JDK 25 gefunden. Erwartet: %USERPROFILE%\.jdks\temurin-25\bin\java.exe oder JAVA_HOME auf JDK 25 setzen. Projekt baut mit JDK 25 (maven.compiler.release=21)."
}
$JavaHome = (Get-Item (Split-Path (Split-Path $JavaExe -Parent) -Parent)).FullName
Write-Host "JDK 25: $JavaExe"

# --- 2. Maven aufloesen ---
function Find-Maven {
    $M = Get-Command "mvn.cmd" -ErrorAction SilentlyContinue
    if ($M) { return $M.Source }
    $M2 = Get-Command "mvn" -ErrorAction SilentlyContinue
    if ($M2) { return $M2.Source }
    $Bundled = Get-ChildItem "C:\Program Files\JetBrains\IntelliJ IDEA*\plugins\maven\lib\maven3\bin\mvn.cmd" -ErrorAction SilentlyContinue |
        Select-Object -First 1 -ExpandProperty FullName
    if ($Bundled) { return $Bundled }
    $Tools = Join-Path (Get-Location) ".tools\apache-maven-3.9.11\bin\mvn.cmd"
    if (Test-Path -LiteralPath $Tools) { return $Tools }
    return $null
}

$Mvn = Find-Maven

# --- 3. Bauen ---
if (-not $NoBuild) {
    if (-not $Mvn) {
        throw "Maven nicht gefunden (weder PATH noch IntelliJ-bundled). Entweder in IntelliJ bauen (package) und mit -NoBuild starten, oder Maven installieren."
    }
    Write-Host "Baue mit Maven: $Mvn"
    $env:JAVA_HOME = $JavaHome
    & $Mvn -q package -DskipTests
    if ($LASTEXITCODE -ne 0) { throw "Maven-Build fehlgeschlagen." }
} else {
    Write-Host "Build uebersprungen (-NoBuild)."
}

# Bridge-Modul passend zur Zielversion: 1.21.x -> bridge-1.21, neuere Paper -> bridge.
$BridgeModul = "bridge-1.21"
if (-not $McVersion.TrimStart("vV").StartsWith("1.21")) {
    $BridgeModul = "bridge"
}
Write-Host "Bridge-Modul fuer Paper ${McVersion}: $BridgeModul"
$Jar = Get-ChildItem -LiteralPath "$BridgeModul\target" -Filter "*.jar" -ErrorAction SilentlyContinue |
    Where-Object { ($_.Name -notlike "*sources*") -and ($_.Name -notlike "*javadoc*") -and ($_.Name -notlike "original-*") -and ($_.Name -notlike "*-shaded*") } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if (-not $Jar) {
    throw "Keine Jar in $BridgeModul\target/ gefunden. Erst bauen (ohne -NoBuild)."
}
Write-Host "Jar: $($Jar.FullName)"
Write-Warning "Dev-Loop baut mit -DskipTests (keine Unit-Tests). Vor Commits: mvn test laufen lassen."
Write-Warning "Bridge-only Testnetz: kein Proxy-Scope-Test moeglich (Scopes wirken fail-closed, server-name pruefen). Fuer Scope-Tests Velocity-Jar auf Proxy (3.4.0) legen."

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
            throw "SHA256-Pruefsumme stimmt nicht (Datei geloescht, Abbruch)."
        }
        Write-Host "SHA256 ok."
        $DownloadOk = $true
    } catch {
        Remove-Item -LiteralPath $PaperJar -Force -ErrorAction SilentlyContinue
        Write-Warning "Paper-Download/Pruefung fehlgeschlagen: $($_.Exception.Message)"
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
    Set-Content -LiteralPath $Eula -Value "eula=true" -Encoding Ascii
}
$Props = Join-Path $ServerDir "server.properties"
if (-not (Test-Path -LiteralPath $Props)) {
    $OnlineStr = "false"
    if ($OnlineMode) { $OnlineStr = "true" }
    $PropsContent = @(
        "# Auto-generiert von scripts/dev-test.ps1 (nur beim ersten Start, danach von Hand editierbar)",
        "motd=PunishSystem DEV",
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

# OP eintragen
if (-not [string]::IsNullOrWhiteSpace($SpielerName)) {
    $OpsFile = Join-Path $ServerDir "ops.json"
    $Ops = @()
    if (Test-Path -LiteralPath $OpsFile) {
        try { $Ops = Get-Content -LiteralPath $OpsFile -Raw | ConvertFrom-Json } catch { $Ops = @() }
        if ($null -eq $Ops) { $Ops = @() }
        if ($Ops -isnot [System.Array]) { $Ops = @($Ops) }
    }
    $SchonOp = $false
    foreach ($O in $Ops) {
        if ($O.name -eq $SpielerName) { $SchonOp = $true }
    }
    if (-not $SchonOp) {
        $Ops += [ordered]@{ uuid = "00000000-0000-0000-0000-000000000000"; name = $SpielerName; level = 4; bypassesPlayerLimit = $true }
        # Offline-Modus: UUID ist egal, Paper matcht beim Login per Name neu.
        ConvertTo-Json @($Ops) -Depth 5 | Set-Content -LiteralPath $OpsFile -Encoding Ascii
        Write-Host "OP eingetragen: $SpielerName"
    }
} else {
    Write-Host "Hinweis: kein -SpielerName angegeben. Du bist trotzdem OP, wenn du als erster joinst? Nein - trage dich ein: -SpielerName DeinName (oder Konsole: op DeinName)."
}

# --- 7. Plugin kopieren ---
Get-ChildItem -LiteralPath $PluginsDir -Filter "PunishSystem-*.jar" -ErrorAction SilentlyContinue |
    Remove-Item -Force -ErrorAction SilentlyContinue
Copy-Item -LiteralPath $Jar.FullName -Destination (Join-Path $PluginsDir $Jar.Name) -Force
Write-Host "Plugin kopiert nach test-server/plugins/"

if ($ResetData) {
    $DataDir = Join-Path $PluginsDir "PunishSystemBridge"
    if (Test-Path -LiteralPath $DataDir) {
        Remove-Item -LiteralPath $DataDir -Recurse -Force
        Write-Host "Plugin-Daten geloescht (ResetData: plugins/PunishSystemBridge; Proxy-Daten bleiben unberuehrt)."
    }
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
