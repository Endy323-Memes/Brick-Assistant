<#
  Records the real "Microsoft David Desktop" voice for every built-in David response and bundles the
  results into the app (app/src/main/assets/david). The phone then plays genuine David for these lines
  without any server. Rerun after changing the defaults in Category.kt, then rebuild the APK.

  File names match the app's cache key: SHA-1 of "0|<text>" (speaking rate 0).
#>
param([string[]]$Extra = @())
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$categoryFile = Join-Path $root "app\src\main\java\com\brick\assistant\data\Category.kt"
$outDir = Join-Path $root "app\src\main\assets\david"
New-Item -ItemType Directory -Force $outDir | Out-Null

# Every string literal inside listOf(...) in Category.kt is a default David response.
$src = [IO.File]::ReadAllText($categoryFile)
$lines = New-Object System.Collections.Generic.List[string]
foreach ($m in [regex]::Matches($src, 'listOf\(([^)]*)\)')) {
    foreach ($s in [regex]::Matches($m.Groups[1].Value, '"((?:[^"\\]|\\.)*)"')) { $lines.Add($s.Groups[1].Value.Replace('\"', '"')) }
}
# Default lines of the response-pool rules (ResponseRules.kt): only full sentences, not rule labels.
$rules = [IO.File]::ReadAllText((Join-Path $root "app\src\main\java\com\brick\assistant\data\ResponseRules.kt"))
foreach ($m in [regex]::Matches($rules, '"((?:[^"\\]|\\.)*[.!?])"')) { $lines.Add($m.Groups[1].Value.Replace('\"', '"')) }
# Phrases the app itself speaks with David (Settings test button).
$lines.Add("Hello. I am David, your assistant.")
$Extra | ForEach-Object { $lines.Add($_) }

Add-Type -AssemblyName System.Speech
$synth = New-Object System.Speech.Synthesis.SpeechSynthesizer
$synth.SelectVoice("Microsoft David Desktop")
$synth.Rate = 0
$sha = [System.Security.Cryptography.SHA1]::Create()
$index = New-Object System.Collections.Generic.List[string]
Get-ChildItem $outDir -Filter *.wav | Remove-Item
foreach ($text in ($lines | Select-Object -Unique)) {
    $hash = ($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes("0|$text")) | ForEach-Object { $_.ToString("x2") }) -join ""
    $synth.SetOutputToWaveFile((Join-Path $outDir "$hash.wav"))
    $synth.Speak($text)
    $synth.SetOutputToNull()
    $index.Add("$hash`t$text")
}
$synth.Dispose()
[IO.File]::WriteAllLines((Join-Path $outDir "index.tsv"), $index, (New-Object Text.UTF8Encoding $false))
"Recorded $($index.Count) David lines into $outDir"
