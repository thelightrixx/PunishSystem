#Requires -Version 5.1
<#
.SYNOPSIS
    Setzt die Buildnummer in pom.xml auf das aktuelle Datum und die aktuelle Stunde (DDMMYY-HH).

.DESCRIPTION
    Versionsschema: Basis+Build, z.B. 4.0-beta+011126-19.
    Der Build ist das lokale Datum (TTMMJJ) und die Stunde (00-23).
    Maven-Version ohne fuehrendes 'v' (Konvention); das Anzeige-'v'
    ergaenzen StartBanner und PluginVersion automatisch.
    Mehrere Builds in derselben Stunde tragen dieselbe Nummer.
    Datei ist bewusst ASCII-only (keine Umlaute), damit
    Windows PowerShell 5.1 sie auch ohne BOM korrekt liest.

.PARAMETER Build
    Buildnummer explizit setzen (DDMMYY-HH, z.B. "011126-19").
    Ohne Angabe wird das aktuelle lokale Datum und die aktuelle Stunde verwendet.

.PARAMETER Base
    Basis-Version aendern (z.B. "v4.0-RC", "v4.0", "v4.1-beta").
    Die Buildnummer wird dabei auf den aktuellen Zeitpunkt gesetzt.

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts/bump-build.ps1
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts/bump-build.ps1 -Build 011126-19
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts/bump-build.ps1 -Base "v4.0-RC"
#>
[CmdletBinding()]
param(
    [string]$Build = "",
    [string]$Base = ""
)

$ErrorActionPreference = "Stop"

$PomPath = Join-Path (Get-Location) "pom.xml"
if (-not (Test-Path -LiteralPath $PomPath)) {
    throw "pom.xml nicht gefunden. Skript aus dem Projekt-Root starten."
}
$PomXml = New-Object xml
$PomXml.Load($PomPath)
$Aktuell = $PomXml.project.version.Trim()
if ([string]::IsNullOrWhiteSpace($Aktuell)) {
    throw "Keine <version> in pom.xml gefunden."
}

# Aktuell zerlegen: Basis[+Build]
$AltBasis = $Aktuell
$AltBuild = ""
if ($Aktuell -match '^(?<basis>[^+]+)(\+(?<build>.+))?$') {
    $AltBasis = $Matches["basis"].Trim()
    $AltBuild = $Matches["build"]
    if ($null -eq $AltBuild) { $AltBuild = "" }
    $AltBuild = $AltBuild.Trim()
}
if ($AltBasis -notmatch '^[vV]?\d+\.\d+(\.\d+)?(-alpha|-beta|-RC|-rc)?(-SNAPSHOT)?$') {
    throw "Basis '$AltBasis' ungueltig (erwartet z.B. v4.0-beta, v4.0-RC oder v4.0)."
}

$Basis = $AltBasis
if (-not [string]::IsNullOrWhiteSpace($Base)) {
    $Base = $Base.Trim()
    if ($Base -notmatch '^[vV]?\d+\.\d+(\.\d+)?(-alpha|-beta|-RC|-rc)?(-SNAPSHOT)?$') {
        throw "Base '$Base' ungueltig (erwartet z.B. v4.0, v4.0-RC oder v4.1-beta)."
    }
    $Basis = $Base
}
$Basis = $Basis -replace '^[vV]', ''

# Finale Version ohne Buildnummer (z. B. 4.0) ist die veroeffentlichte Freigabe.
# Nur eine ausdrueckliche Basis-/Build-Angabe darf ihr eine Buildnummer anhaengen.
$IstBeta = $AltBasis -match '-(alpha|beta)(-|$)'
$IstRc = $AltBasis -match '-(RC|rc)$'
$IstSnapshot = $AltBasis -match '-SNAPSHOT$'
if ([string]::IsNullOrWhiteSpace($AltBuild) -and
    [string]::IsNullOrWhiteSpace($Base) -and
    [string]::IsNullOrWhiteSpace($Build) -and
    -not $IstBeta -and -not $IstRc -and -not $IstSnapshot) {
    throw ("Finale Version '$Aktuell' hat keine Buildnummer - Bump abgelehnt. " +
        "Eine neue Linie mit -Base starten (z. B. -Base v4.0.1 oder -Base v4.1-beta); " +
        "zum reinen Bauen build_all_jars.bat verwenden.")
}

if ([string]::IsNullOrWhiteSpace($Build)) {
    $Build = Get-Date -Format "ddMMyy-HH"
} else {
    $Build = $Build.Trim()
}
if ($Build -notmatch '^[0-9]{6}-[0-9]{2}$') {
    throw "Build '$Build' ungueltig (erwartet DDMMYY-HH, z.B. 011126-19)."
}
$Tag = [int]($Build.Substring(0, 2))
$Monat = [int]($Build.Substring(2, 2))
$Jahr = 2000 + [int]($Build.Substring(4, 2))
$Stunde = [int]($Build.Substring(7, 2))
try {
    $Zeitpunkt = [datetime]::new($Jahr, $Monat, $Tag, $Stunde, 0, 0)
} catch {
    throw "Build '$Build' enthaelt kein gueltiges Datum oder keine gueltige Stunde (DDMMYY-HH)."
}
$Build = $Zeitpunkt.ToString("ddMMyy-HH")

$Neu = "$Basis+$Build"
if ($Neu -eq $Aktuell) {
    Write-Host "Version bleibt: $Aktuell"
    exit 0
}
$PomXml.project.version = $Neu
$PomXml.Save($PomPath)
Write-Host "Version: $Aktuell -> $Neu"
Write-Host "Naechster Schritt: build_all_jars.bat (OHNE nobump) - es synchronisiert"
Write-Host "die neue Version in alle Modul-poms und PunishVelocity.java."
Write-Host '(velocity-plugin.json nutzt ${project.version} und wird von Maven gefiltert.)'
Write-Host "(Dieses Skript aendert nur die Root-pom; build_all_jars.bat mit nobump wuerde den Build danach brechen.)"
