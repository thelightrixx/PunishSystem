#Requires -Version 5.1
<#
.SYNOPSIS
    Erhoeht stabile Patch-/Minor-Versionen oder setzt eine explizite Vorab-Buildnummer.

.DESCRIPTION
    Stabile Releases verwenden vX.Y.Z; Increment erhoeht Patch oder Minor.
    Vorabversionen verwenden Basis+Build, z.B. v4.0.0-preview+011126-1853.
    Der Build ist das lokale Datum (TTMMJJ), die Stunde (00-23) und die Minute (00-59).
    Das fuehrende kleine 'v' ist auch Teil der Maven- und Plugin-Version.
    Mehrere Builds in derselben Minute tragen dieselbe Nummer.
    Datei ist bewusst ASCII-only (keine Umlaute), damit
    Windows PowerShell 5.1 sie auch ohne BOM korrekt liest.

.PARAMETER Build
    Buildnummer explizit setzen (DDMMYY-HHMM, z.B. "011126-1853").
    Ohne Angabe wird das aktuelle lokale Datum und die aktuelle Minute verwendet.

.PARAMETER Base
    Basis-Version aendern (z.B. "v4.0-RC", "v4.0", "v4.1-preview").
    Die Buildnummer wird dabei auf den aktuellen Zeitpunkt gesetzt.

.PARAMETER Increment
    patch erhoeht die dritte Zahl, minor die zweite und setzt die dritte auf 0.
    Gilt fuer stabile Versionen vX.Y.Z und ist nicht mit Base oder Build kombinierbar.

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts/bump-build.ps1 -Increment patch
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts/bump-build.ps1 -Increment minor
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts/bump-build.ps1
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts/bump-build.ps1 -Build 011126-1853
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts/bump-build.ps1 -Base "v4.0-RC"
#>
[CmdletBinding()]
param(
    [string]$Build = "",
    [string]$Base = "",
    [ValidateSet("patch", "minor")]
    [string]$Increment = ""
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
if ($AltBasis -notmatch '^[vV]?\d+\.\d+(\.\d+)?(-alpha|-beta|-preview|-RC|-rc)?(-SNAPSHOT)?$') {
    throw "Basis '$AltBasis' ungueltig (erwartet z.B. v4.0-preview, v4.0-RC oder v4.0)."
}

$Basis = $AltBasis
if (-not [string]::IsNullOrWhiteSpace($Base)) {
    $Base = $Base.Trim()
    if ($Base -notmatch '^[vV]?\d+\.\d+(\.\d+)?(-alpha|-beta|-preview|-RC|-rc)?(-SNAPSHOT)?$') {
        throw "Base '$Base' ungueltig (erwartet z.B. v4.0, v4.0-RC oder v4.1-preview)."
    }
    $Basis = $Base
}
$Basis = 'v' + ($Basis -replace '^[vV]', '')

if (-not [string]::IsNullOrWhiteSpace($Increment)) {
    if (-not [string]::IsNullOrWhiteSpace($Base) -or -not [string]::IsNullOrWhiteSpace($Build)) {
        throw "Increment ist nicht mit Base oder Build kombinierbar."
    }
    if ($AltBuild -ne "" -or $AltBasis -notmatch '^[vV]?(?<major>\d+)\.(?<minor>\d+)\.(?<patch>\d+)$') {
        throw "Increment verlangt eine stabile Version vX.Y.Z ohne Buildnummer."
    }
    $Hauptversion = [int]$Matches["major"]
    $Minor = [int]$Matches["minor"]
    $Patch = [int]$Matches["patch"]
    if ($Increment -eq "minor") {
        $Minor = [int]($Minor + 1)
        $Patch = 0
    } else {
        $Patch = [int]($Patch + 1)
    }
    $Neu = "v$Hauptversion.$Minor.$Patch"
} else {
    $IstBeta = $AltBasis -match '-(alpha|beta)(-|$)'
    $IstPreview = $AltBasis -match '-preview(-|$)'
    $IstRc = $AltBasis -match '-(RC|rc)$'
    $IstSnapshot = $AltBasis -match '-SNAPSHOT$'
    if ([string]::IsNullOrWhiteSpace($AltBuild) -and
        [string]::IsNullOrWhiteSpace($Base) -and
        [string]::IsNullOrWhiteSpace($Build) -and
        -not $IstBeta -and -not $IstPreview -and -not $IstRc -and -not $IstSnapshot) {
        Write-Host "Finale Version '$Aktuell' ohne Buildnummer - kein Datums-Build."
        exit 3
    }

    if ([string]::IsNullOrWhiteSpace($Build)) {
        $Build = Get-Date -Format "ddMMyy-HHmm"
    } else {
        $Build = $Build.Trim()
    }
    if ($Build -notmatch '^[0-9]{6}-[0-9]{4}$') {
        throw "Build '$Build' ungueltig (erwartet DDMMYY-HHMM, z.B. 011126-1853)."
    }
    $Tag = [int]($Build.Substring(0, 2))
    $Monat = [int]($Build.Substring(2, 2))
    $Jahr = 2000 + [int]($Build.Substring(4, 2))
    $Stunde = [int]($Build.Substring(7, 2))
    $Minute = [int]($Build.Substring(9, 2))
    try {
        $Zeitpunkt = [datetime]::new($Jahr, $Monat, $Tag, $Stunde, $Minute, 0)
    } catch {
        throw "Build '$Build' enthaelt kein gueltiges Datum, keine gueltige Stunde oder keine gueltige Minute (DDMMYY-HHMM)."
    }
    $Build = $Zeitpunkt.ToString("ddMMyy-HHmm")
    $Neu = "$Basis+$Build"
}

if ($Neu -eq $Aktuell) {
    Write-Host "Version bleibt: $Aktuell"
    exit 0
}
$PomText = [IO.File]::ReadAllText($PomPath)
$VersionsMuster = [regex]'<version>[^<]+</version>'
[IO.File]::WriteAllText($PomPath, $VersionsMuster.Replace($PomText, "<version>$Neu</version>", 1),
    (New-Object Text.UTF8Encoding($true)))
Write-Host "Version: $Aktuell -> $Neu"
Write-Host "Naechster Schritt: build_all_jars.bat nobump ci - es synchronisiert"
Write-Host "die neue Version in alle Modul-poms und PunishVelocity.java."
Write-Host '(velocity-plugin.json nutzt ${project.version} und wird von Maven gefiltert.)'
