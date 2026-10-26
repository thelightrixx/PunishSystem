<# ================================================================
# PunishSystem - chatfilter-modell-laden.ps1
# Lädt die Chatfilter-Artefakte (vocab + ONNX-Modell) mit
# SHA-256-Prüfung in den Bridge-Ordner chatfilter/.
#
# Aufruf:
#   .\scripts\chatfilter-modell-laden.ps1 -Zielordner "E:\Server\plugins\PunishSystemBridge\chatfilter"
#   .\scripts\chatfilter-modell-laden.ps1 -Zielordner "...\chatfilter" -ModellUrl "https://..."
#
# KI-Modell: ankekat1000/toxic-bert-german
# (https://huggingface.co/ankekat1000/toxic-bert-german),
# CC-BY-NC-SA-4.0 (Namensnennung, nur nicht-kommerziell, gleiche
# Lizenz bei Weitergabe), exportiert nach ONNX + dynamisch INT8.
# Wird NICHT mit dem Plugin verteilt - Verantwortung und Lizenz-
# Compliance liegen beim Serverbetreiber (bei Shop/Kommerz pruefen!).
# Ohne -ModellUrl wird nur vocab.txt geladen und die Anleitung
# für den Selbst-Export ausgegeben (einmalig, Build-PC mit Python).
# ================================================================
param(
    [string]$Zielordner = "",
    [string]$ModellUrl = ""
)

$ErrorActionPreference = "Stop"
$VocabUrl = "https://huggingface.co/ankekat1000/toxic-bert-german/resolve/main/vocab.txt"
$VocabHash = "982F8396EC746DB0ED414DCC4789398AB6B365663CADA50F776AFB905DACBB61"
$ModellHash = "95979FBCDC34784670CBD11BEAA7952B743BEEA7B1DD7E64908AEE5AD00AC825"

if ([string]::IsNullOrWhiteSpace($Zielordner)) {
    Write-Host "[FEHLER] Bitte -Zielordner angeben (plugins\PunishSystemBridge\chatfilter auf dem Server)."
    exit 1
}
New-Item -ItemType Directory -Force -Path $Zielordner | Out-Null

function Lade-DateiMitHash($url, $ziel, $erwarteterHash, $pflicht) {
    Write-Host "[INFO] Lade $(Split-Path $ziel -Leaf) ..."
    Invoke-WebRequest -Uri $url -OutFile $ziel -UseBasicParsing
    $hash = (Get-FileHash $ziel -Algorithm SHA256).Hash
    if ($hash -ne $erwarteterHash) {
        if ($pflicht) {
            Write-Host "[FEHLER] SHA-256 stimmt nicht: $ziel"
            exit 1
        }
        Write-Host "[WARN] SHA-256 abweichend (Upstream-Update möglich): $ziel"
        return
    }
    Write-Host "[INFO] SHA-256 ok: $(Split-Path $ziel -Leaf)"
}

Lade-DateiMitHash $VocabUrl (Join-Path $Zielordner "vocab.txt") $VocabHash $false

$modellDatei = Join-Path $Zielordner "modell_int8.onnx"
if ([string]::IsNullOrWhiteSpace($ModellUrl)) {
    Write-Host ""
    Write-Host "Modelldatei fehlt noch. Selbst-Export (einmalig auf einem PC mit Python):"
    Write-Host "  pip install torch --index-url https://download.pytorch.org/whl/cpu"
    Write-Host "  pip install `"optimum[onnxruntime]`" transformers"
    Write-Host "  optimum-cli export onnx --model ankekat1000/toxic-bert-german --task text-classification --framework pt ./tbg-onnx"
    Write-Host "  python -c `"from onnxruntime.quantization import quantize_dynamic,QuantType; quantize_dynamic('./tbg-onnx/model.onnx','./modell_int8.onnx',weight_type=QuantType.QInt8)`""
    Write-Host "Erwartetes SHA-256: $ModellHash"
    Write-Host "Datei danach als modell_int8.onnx nach $Zielordner legen."
    Write-Host "Bis dahin läuft nur Stufe 1 (Wortliste)."
    exit 0
}
Lade-DateiMitHash $ModellUrl $modellDatei $ModellHash $true
Write-Host "[INFO] Fertig. In config/chatfilter.yml: chatfilter.aktiv=true setzen."
