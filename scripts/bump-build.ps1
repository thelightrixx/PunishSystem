#Requires -Version 5.1
<#
.SYNOPSIS
    Erhoeht die Buildnummer (LLNN- oder LLNNS-Schema, z.B. EA26 oder EA01c) in pom.xml.

.DESCRIPTION
    Versionsschema: Basis[+Build], z.B. 4.0-beta+EA01c oder 4.0-RC+EA26.
    Maven-Version ohne fuehrendes 'v' (Konvention); das Anzeige-'v'
    ergaenzen StartBanner und PluginVersion automatisch.
    Build: 2 Buchstaben Versionscode + 2 Ziffern Nummer (00-99).
    Der Stufenbuchstabe (a-z, klein) steht NUR bei Beta-Basis (z.B. EA01c).
    RC- und Final-Basis haben keinen Buchstaben (z.B. EA26).
    Versionscode: Major.Minor mit A=0, B=1, ... (4.0 -> EA, 4.1 -> EB).
    Er wird IMMER aus der Basis abgeleitet, nie von Hand vergeben -
    jede Buildnummer identifiziert damit ihre Release-Linie.
    Beta-Stufen (Apple-Prinzip: a ist am stabilsten, je weiter hinten im
    Alphabet, desto instabiler):
    Stufen: a=Final (stabilste, Release-reif), b=RC (fast final),
            c=Beta (feature-fertig/Test), d=Alpha (unfertig/instabil),
            e-z=experimentell (absteigend).
    Datei ist bewusst ASCII-only (keine Umlaute), damit
    Windows PowerShell 5.1 sie auch ohne BOM korrekt liest.

.PARAMETER Next
    Nummer +1 (Standard bei Aufruf ohne Parameter).
    Explizite -Number gewinnt exakt (kein +1 obendrauf).

.PARAMETER Stability
    Neue Stufe setzen (a-z, z.B. "c" fuer Beta). Nur bei Beta-Basis erlaubt,
    bei RC-/Final-Basis Abbruch (dort gibt es keinen Buchstaben).
    Kombinierbar mit -Next/-Number (nur Beta-Basis).

.PARAMETER Number
    Nummer 0-99 explizit setzen (wird zweistellig formatiert).

.PARAMETER Build
    Buildnummer explizit setzen (z.B. "EA26" fuer RC/Final, "EA01c" fuer Beta),
    ueberschreibt Number/Stability-Berechnung. Buchstabe nur bei Beta-Basis,
    sonst Abbruch. Der Versionscode muss zur Basis passen, sonst Abbruch.

.PARAMETER Base
    Basis-Version aendern (z.B. "v4.0-RC" fuer Release Candidate, "v4.0" fuer
    Release, "v4.1-beta" fuer naechsten Zyklus). Neue Release-Linie startet
    bei 01/d (Beta) bzw. 01 ohne Buchstabe (RC/Final),
    ausser -Number/-Stability ist angegeben.

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts/bump-build.ps1
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts/bump-build.ps1 -Stability a
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts/bump-build.ps1 -Number 05
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts/bump-build.ps1 -Build EA26
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts/bump-build.ps1 -Base "v4.0-RC"
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts/bump-build.ps1 -Base "v4.1-beta"
#>
[CmdletBinding()]
param(
    [switch]$Next,
    [string]$Stability = "",
    [string]$Number = "",
    [string]$Build = "",
    [string]$Base = ""
)

$ErrorActionPreference = "Stop"

function Train-Aus-Basis([string]$BasisVersion) {
    $BasisVersion = ($BasisVersion -replace '^[vV]', '')
    if ($BasisVersion -notmatch '^(?<major>\d+)\.(?<minor>\d+)') {
        throw "Basis '$BasisVersion' ungueltig (erwartet z.B. v4.0-beta)."
    }
    $Major = [int]$Matches["major"]
    $Minor = [int]$Matches["minor"]
    if ($Major -gt 25 -or $Minor -gt 25) {
        throw "Major.Minor $Major.$Minor ist nicht als Buchstabe kodierbar (erlaubt: 0-25)."
    }
    return ([char]([int][char]'A' + $Major)).ToString() + ([char]([int][char]'A' + $Minor)).ToString()
}

function Pruefe-Build-Passt-Zu-Basis([string]$BuildCode, [string]$BasisVersion) {
    $Soll = Train-Aus-Basis $BasisVersion
    $Ist = $BuildCode.Substring(0, 2).ToUpper()
    if ($Ist -ne $Soll) {
        throw "Build '$BuildCode' passt nicht zu Basis '$BasisVersion' (erwartet Train $Soll)."
    }
}

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
$AltTrain = ""
$AltNummer = -1
$AltStufe = ""
if ($AltBuild -ne "") {
    if ($AltBuild -notmatch '^(?<train>[A-Za-z]{2})(?<nummer>[0-9]{2})(?<stufe>[A-Za-z])?$') {
        throw "Build '$AltBuild' ungueltig (erwartet LLNN oder LLNNS, z.B. EA26 oder EA01c)."
    }
    $AltTrain = $Matches["train"].ToUpper()
    $AltNummer = [int]$Matches["nummer"]
    $AltStufe = $Matches["stufe"]
    if ($null -eq $AltStufe) { $AltStufe = "" }
    $AltStufe = $AltStufe.ToLower()
    Pruefe-Build-Passt-Zu-Basis $AltBuild $AltBasis
}

function Ist-Beta-Basis([string]$BasisVersion) {
    return ($BasisVersion -match '-(alpha|beta)(-|$)')
}

function Pruefe-Stufenregel([string]$BuildCode, [string]$BasisVersion) {
    $HatBuchstabe = $BuildCode.Length -gt 4
    if ((Ist-Beta-Basis $BasisVersion) -and (-not $HatBuchstabe)) {
        throw "Build '$BuildCode' braucht bei Beta-Basis '$BasisVersion' einen Stufenbuchstaben (z.B. EA01c)."
    }
    if ((-not (Ist-Beta-Basis $BasisVersion)) -and $HatBuchstabe) {
        throw "Build '$BuildCode' darf bei Basis '$BasisVersion' keinen Stufenbuchstaben haben (nur Betas, z.B. EA26)."
    }
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
$SollTrain = Train-Aus-Basis $Basis
$IstBetaBasis = Ist-Beta-Basis $Basis

# Finale Version ohne Buildnummer (z. B. 4.0) ist die veroeffentlichte Freigabe.
# Ein Bump haengte ihr eine Vorab-Buildnummer an (4.0 -> 4.0+EA01) und machte
# damit aus der Freigabe wieder eine Vorabversion. Nur eine ausdrueckliche
# Basis/Build-Angabe darf das.
if ([string]::IsNullOrWhiteSpace($AltBuild) -and
    [string]::IsNullOrWhiteSpace($Base) -and
    [string]::IsNullOrWhiteSpace($Build) -and
    -not (Ist-Beta-Basis $AltBasis) -and
    ($AltBasis -notmatch '-RC$')) {
    throw ("Finale Version '$Aktuell' hat keine Buildnummer - Bump abgelehnt. " +
        "Eine neue Linie mit -Base starten (z. B. -Base v4.0.1 oder -Base v4.1-beta); " +
        "zum reinen Bauen build_all_jars.bat verwenden.")
}

$NeuBuild = ""
if (-not [string]::IsNullOrWhiteSpace($Build)) {
    $Build = $Build.Trim()
    if ($Build -notmatch '^[A-Za-z]{2}[0-9]{2}([A-Za-z])?$') {
        throw "Build '$Build' ungueltig (erwartet LLNN oder LLNNS, z.B. EA26 oder EA01c)."
    }
    $NeuBuild = $Build.Substring(0, 2).ToUpper() + $Build.Substring(2, 2)
    if ($Build.Length -gt 4) {
        $NeuBuild += $Build.Substring(4, 1).ToLower()
    }
    Pruefe-Build-Passt-Zu-Basis $NeuBuild $Basis
    Pruefe-Stufenregel $NeuBuild $Basis
} else {
    $HatNumber = -not [string]::IsNullOrWhiteSpace($Number)
    $HatStufe = -not [string]::IsNullOrWhiteSpace($Stability)
    if ($HatStufe -and (-not $IstBetaBasis)) {
        throw "Stability ist nur bei Beta-Basis erlaubt (Basis '$Basis' hat keinen Buchstaben)."
    }

    # Startwerte: weiterzaehlen in derselben Linie, sonst Neustart bei 01/d.
    $NeuTrain = $SollTrain
    $NeueLinie = ($AltTrain -ne $SollTrain)
    if ($NeueLinie) {
        $NeuNummer = 0
        $NeuStufe = "d"
    } else {
        $NeuNummer = $AltNummer
        if ($NeuNummer -lt 0) { $NeuNummer = 0 }
        $NeuStufe = $AltStufe
        if ($NeuStufe -eq "") { $NeuStufe = "d" }
    }

    if ($HatNumber) {
        $Number = $Number.Trim()
        if ($Number -notmatch '^[0-9]{1,2}$') { throw "Number '$Number' ungueltig (0-99)." }
        $NeuNummer = [int]$Number
    } elseif ($NeueLinie) {
        # Neue Release-Linie startet bei 01.
        $NeuNummer = 1
    } elseif ($Next -or (-not $HatStufe)) {
        # Standard (blosser Aufruf ohne Parameter): Nummer +1.
        # Reine Stufenwechsel (-Stability) behalten die Nummer.
        if ($NeuNummer -ge 99) { throw "Build-Zaehler am Limit (99), neue Minor-Version noetig (-Base v4.1-beta)." }
        $NeuNummer = $NeuNummer + 1
    }
    if ($HatStufe) {
        $Stability = $Stability.Trim().ToLower()
        if ($Stability -notmatch '^[a-z]$') { throw "Stability '$Stability' ungueltig (a-z)." }
        $NeuStufe = $Stability
    }
    if ($NeuNummer -lt 0 -or $NeuNummer -gt 99) { throw "Nummer ausserhalb 00-99: $NeuNummer." }
    if ($IstBetaBasis) {
        $NeuBuild = $NeuTrain + ("{0:D2}" -f $NeuNummer) + $NeuStufe
    } else {
        $NeuBuild = $NeuTrain + ("{0:D2}" -f $NeuNummer)
    }
}

$Neu = "$Basis+$NeuBuild"
$PomXml.project.version = $Neu
$PomXml.Save($PomPath)
Write-Host "Version: $Aktuell -> $Neu"
Write-Host "Naechster Schritt: build_all_jars.bat (OHNE nobump) - nur es synchronisiert"
Write-Host "die neue Version in alle Modul-poms, velocity-plugin.json und PunishVelocity.java."
Write-Host "(Dieses Skript aendert nur die Root-pom; build_all_jars.bat mit nobump wuerde den Build danach brechen.)"
