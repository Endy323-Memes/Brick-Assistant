<#
  David voice server for the Brick Android assistant.

  Renders text with the Windows SAPI voice "Microsoft David Desktop" and returns WAV audio
  over your local network, so the phone can speak with the real David voice.

  Usage:   powershell -ExecutionPolicy Bypass -File david-server.ps1 [-Port 5150] [-Token secret]
  Routes:  GET  /ping                  -> JSON status
           POST /speak?rate=0          -> body = UTF-8 text, returns audio/wav
           GET  /speak?text=hello      -> returns audio/wav (handy for testing in a browser)
#>
param(
    [int]$Port = 5150,
    [string]$Voice = "Microsoft David Desktop",
    [string]$Token = ""
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Speech

$synth = New-Object System.Speech.Synthesis.SpeechSynthesizer
try {
    $synth.SelectVoice($Voice)
} catch {
    Write-Host "Voice '$Voice' is not installed. Installed voices:" -ForegroundColor Red
    $synth.GetInstalledVoices() | ForEach-Object { Write-Host "  - $($_.VoiceInfo.Name)" }
    exit 1
}

function Send-Response($stream, [int]$code, [string]$status, [string]$contentType, [byte[]]$body) {
    if ($null -eq $body) { $body = [byte[]]@() }
    $header = "HTTP/1.1 $code $status`r`nContent-Type: $contentType`r`nContent-Length: $($body.Length)`r`nConnection: close`r`nAccess-Control-Allow-Origin: *`r`n`r`n"
    $hb = [Text.Encoding]::ASCII.GetBytes($header)
    $stream.Write($hb, 0, $hb.Length)
    if ($body.Length -gt 0) { $stream.Write($body, 0, $body.Length) }
    $stream.Flush()
}

function Send-Text($stream, [int]$code, [string]$status, [string]$text, [string]$type = "text/plain; charset=utf-8") {
    Send-Response $stream $code $status $type ([Text.Encoding]::UTF8.GetBytes($text))
}

function Read-Request($stream) {
    $headerBytes = New-Object System.Collections.Generic.List[byte]
    while ($true) {
        $b = $stream.ReadByte()
        if ($b -lt 0) { return $null }
        $headerBytes.Add([byte]$b)
        $n = $headerBytes.Count
        if ($n -ge 4 -and $headerBytes[$n - 4] -eq 13 -and $headerBytes[$n - 3] -eq 10 -and $headerBytes[$n - 2] -eq 13 -and $headerBytes[$n - 1] -eq 10) { break }
        if ($n -gt 16384) { return $null }
    }
    $lines = [Text.Encoding]::ASCII.GetString($headerBytes.ToArray()) -split "`r`n"
    $first = $lines[0] -split ' '
    if ($first.Count -lt 2) { return $null }
    $headers = @{}
    foreach ($l in $lines | Select-Object -Skip 1) {
        $i = $l.IndexOf(':')
        if ($i -gt 0) { $headers[$l.Substring(0, $i).Trim().ToLower()] = $l.Substring($i + 1).Trim() }
    }
    $body = [byte[]]@()
    if ($headers.ContainsKey('content-length')) {
        $len = [Math]::Min([int]$headers['content-length'], 65536)
        $body = New-Object byte[] $len
        $read = 0
        while ($read -lt $len) {
            $r = $stream.Read($body, $read, $len - $read)
            if ($r -le 0) { break }
            $read += $r
        }
    }
    $target = $first[1]
    $path = $target
    $query = @{}
    $q = $target.IndexOf('?')
    if ($q -ge 0) {
        $path = $target.Substring(0, $q)
        foreach ($kv in ($target.Substring($q + 1) -split '&')) {
            $p = $kv -split '=', 2
            if ($p.Count -eq 2) { $query[[Uri]::UnescapeDataString($p[0])] = [Uri]::UnescapeDataString($p[1].Replace('+', ' ')) }
        }
    }
    return @{ Method = $first[0].ToUpper(); Path = $path; Query = $query; Headers = $headers; Body = $body }
}

function Render-Wav([string]$text, [int]$rate) {
    $ms = New-Object System.IO.MemoryStream
    $synth.Rate = [Math]::Max(-10, [Math]::Min(10, $rate))
    $synth.SetOutputToWaveStream($ms)
    try { $synth.Speak($text) } finally { $synth.SetOutputToNull() }
    return , $ms.ToArray()
}

$listener = New-Object System.Net.Sockets.TcpListener([System.Net.IPAddress]::Any, $Port)
try {
    $listener.Start()
} catch {
    Write-Host "Could not listen on port $Port. Is another copy already running?" -ForegroundColor Red
    exit 1
}

Write-Host ""
Write-Host "  David voice server is running  ($Voice)" -ForegroundColor Cyan
Write-Host "  Enter one of these addresses in the Brick app (Settings > David server address):" -ForegroundColor Gray
try {
    Get-NetIPAddress -AddressFamily IPv4 -ErrorAction Stop |
        Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254.*' } |
        ForEach-Object { Write-Host "     http://$($_.IPAddress):$Port" -ForegroundColor Yellow }
} catch {
    Write-Host "     http://<this-pc-ip>:$Port" -ForegroundColor Yellow
}
if ($Token) { Write-Host "  Token required: yes" -ForegroundColor Gray }
Write-Host "  Press Ctrl+C to stop."
Write-Host ""

try {
    while ($true) {
        if (-not $listener.Pending()) { Start-Sleep -Milliseconds 50; continue }
        $client = $listener.AcceptTcpClient()
        try {
            $client.ReceiveTimeout = 5000
            $client.SendTimeout = 10000
            $stream = $client.GetStream()
            $req = Read-Request $stream
            if ($null -eq $req) { continue }

            if ($Token) {
                $given = $req.Headers['x-token']
                if (-not $given) { $given = $req.Query['token'] }
                if ($given -ne $Token) { Send-Text $stream 401 "Unauthorized" "Bad or missing token"; continue }
            }

            switch ($req.Path) {
                '/ping' {
                    Send-Text $stream 200 "OK" ("{""ok"":true,""voice"":""$Voice""}") "application/json"
                }
                '/speak' {
                    $text = if ($req.Method -eq 'POST') { [Text.Encoding]::UTF8.GetString($req.Body) } else { $req.Query['text'] }
                    if ([string]::IsNullOrWhiteSpace($text)) { Send-Text $stream 400 "Bad Request" "No text"; break }
                    if ($text.Length -gt 2000) { $text = $text.Substring(0, 2000) }
                    $rate = 0
                    if ($req.Query.ContainsKey('rate')) { [int]::TryParse($req.Query['rate'], [ref]$rate) | Out-Null }
                    $wav = Render-Wav $text $rate
                    Send-Response $stream 200 "OK" "audio/wav" $wav
                    $short = if ($text.Length -gt 60) { $text.Substring(0, 60) + '...' } else { $text }
                    Write-Host ("[{0:HH:mm:ss}] {1}  ->  {2}" -f (Get-Date), $client.Client.RemoteEndPoint, $short)
                }
                default { Send-Text $stream 404 "Not Found" "Use /ping or /speak" }
            }
        } catch {
            Write-Host "Request failed: $($_.Exception.Message)" -ForegroundColor DarkYellow
        } finally {
            $client.Close()
        }
    }
} finally {
    $listener.Stop()
    $synth.Dispose()
}
