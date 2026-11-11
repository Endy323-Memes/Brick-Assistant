<#
  Builds Brick's real-David word library (app/src/main/assets/david_words).

  Every word in tools/david-words.txt, plus every word in the default responses (Category.kt), is
  plus the most common words from tools/vocab (conversational English, Wikipedia English and first names),
  is spoken by "Microsoft David Desktop" between silent breaks ("Now <word> please.") so it gets natural
  mid-sentence intonation. The word is cut out at the silences, trimmed and stored as 22.05 kHz 4-bit
  IMA ADPCM in one file (words.adpcm) with an index (index.tsv: word, byte offset, sample count).
  On the phone, new responses are stitched from these recordings.

  Run on Windows (needs the David voice), then rebuild the APK.
#>
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$outDir = Join-Path $root "app\src\main\assets\david_words"
New-Item -ItemType Directory -Force $outDir | Out-Null

$words = New-Object System.Collections.Generic.HashSet[string]
foreach ($l in [IO.File]::ReadAllLines((Join-Path $PSScriptRoot "david-words.txt"))) {
    $w = $l.Trim().ToLowerInvariant()
    if ($w -and -not $w.StartsWith("#") -and $w -match "^[a-z']+$") { [void]$words.Add($w) }
}
function Add-List([string]$file, [int]$top) {
    $n = 0
    foreach ($l in [IO.File]::ReadAllLines((Join-Path $PSScriptRoot "vocab\$file"))) {
        $w = $l.Trim().ToLowerInvariant()
        if ($w -match "^[a-z']{1,18}$" -and $w -notmatch "^'|'$") { [void]$words.Add($w); $n++ }
        if ($n -ge $top) { break }
    }
}
Add-List "us_tv_and_film.txt" 15000
Add-List "english_wikipedia.txt" 6000
Add-List "male_names.txt" 700
Add-List "female_names.txt" 1000
$cat = [IO.File]::ReadAllText((Join-Path $root "app\src\main\java\com\brick\assistant\data\Category.kt"))
foreach ($m in [regex]::Matches($cat, '"((?:[^"\\]|\\.)*)"')) {
    foreach ($t in ($m.Groups[1].Value.ToLowerInvariant() -split "[^a-z']+")) { $t = $t.Trim("'"); if ($t.Length -gt 0) { [void]$words.Add($t) } }
}

Add-Type -ReferencedAssemblies System.Speech -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.IO;
using System.Speech.AudioFormat;
using System.Speech.Synthesis;
using System.Text;

public static class DavidWordRecorder {
    const int SR = 22050;

    static readonly int[] StepTable = { 7,8,9,10,11,12,13,14,16,17,19,21,23,25,28,31,34,37,41,45,50,55,60,66,73,80,88,97,107,118,130,143,157,173,190,209,230,253,279,307,337,371,408,449,494,544,598,658,724,796,876,963,1060,1166,1282,1411,1552,1707,1878,2066,2272,2499,2749,3024,3327,3660,4026,4428,4871,5358,5894,6484,7132,7845,8630,9493,10442,11487,12635,13899,15289,16818,18500,20350,22385,24623,27086,29794,32767 };
    static readonly int[] IndexTable = { -1,-1,-1,-1,2,4,6,8,-1,-1,-1,-1,2,4,6,8 };

    /** IMA ADPCM, 2 samples per byte (low nibble first), starting from predictor 0 / index 0. */
    static byte[] Adpcm(short[] x) {
        var o = new byte[(x.Length + 1) / 2];
        int pred = 0, idx = 0;
        for (int i = 0; i < x.Length; i++) {
            int step = StepTable[idx];
            int diff = x[i] - pred, code = 0;
            if (diff < 0) { code = 8; diff = -diff; }
            int vp = step >> 3;
            if (diff >= step) { code |= 4; diff -= step; vp += step; }
            step >>= 1;
            if (diff >= step) { code |= 2; diff -= step; vp += step; }
            step >>= 1;
            if (diff >= step) { code |= 1; vp += step; }
            pred += (code & 8) != 0 ? -vp : vp;
            if (pred > 32767) pred = 32767; else if (pred < -32768) pred = -32768;
            idx += IndexTable[code]; if (idx < 0) idx = 0; else if (idx > 88) idx = 88;
            if ((i & 1) == 0) o[i >> 1] = (byte)code; else o[i >> 1] |= (byte)(code << 4);
        }
        return o;
    }

    static byte MuLaw(short s) {
        const int BIAS = 0x84, CLIP = 32635;
        int sign = (s >> 8) & 0x80;
        int v = s;
        if (sign != 0) v = -v;
        if (v > CLIP) v = CLIP;
        v += BIAS;
        int exp = 7;
        for (int mask = 0x4000; (v & mask) == 0 && exp > 0; exp--, mask >>= 1) { }
        int mant = (v >> (exp + 3)) & 0x0F;
        return (byte)~(sign | (exp << 4) | mant);
    }

    public static string Record(string[] words, string blobPath, string indexPath) {
        var synth = new SpeechSynthesizer();
        synth.SelectVoice("Microsoft David Desktop");
        synth.Rate = 0;
        var fmt = new SpeechAudioFormatInfo(SR, AudioBitsPerSample.Sixteen, AudioChannel.Mono);
        var index = new StringBuilder();
        long offset = 0;
        int ok = 0, failed = 0;
        double totalSec = 0;
        using (var blob = new FileStream(blobPath, FileMode.Create)) {
            foreach (var word in words) {
                // Silent SSML breaks around the word: clean cut points, natural mid-sentence intonation.
                var ms = new MemoryStream();
                synth.SetOutputToAudioStream(ms, fmt);
                synth.SpeakSsml("<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>Now <break time='300ms'/> "
                    + System.Security.SecurityElement.Escape(word) + " <break time='300ms'/> please.</speak>");
                synth.SetOutputToNull();
                byte[] raw = ms.ToArray();
                int n = raw.Length / 2;
                var pcm = new short[n];
                Buffer.BlockCopy(raw, 0, pcm, 0, n * 2);

                // Split into sounding segments separated by >= 120 ms of silence; the word is the middle one.
                int frame = SR / 100;
                int gpeak = 1;
                for (int i = 0; i < n; i++) gpeak = Math.Max(gpeak, Math.Abs((int)pcm[i]));
                int thr = gpeak / 40;
                var segs = new List<int[]>();
                int st = -1, gap = 0, frames = n / frame;
                for (int fi = 0; fi < frames; fi++) {
                    int m = 0;
                    for (int k = fi * frame; k < (fi + 1) * frame; k++) m = Math.Max(m, Math.Abs((int)pcm[k]));
                    if (m > thr) { if (st < 0) st = fi; gap = 0; }
                    else if (st >= 0) { gap++; if (gap >= 12) { segs.Add(new[] { st * frame, (fi - gap + 1) * frame }); st = -1; gap = 0; } }
                }
                if (st >= 0) segs.Add(new[] { st * frame, frames * frame });
                if (segs.Count < 3) { failed++; continue; }
                // Middle segments (a long word may contain an internal pause) = everything between "Now" and "please".
                int margin = SR * 10 / 1000;
                int a = Math.Max(segs[0][1], segs[1][0] - margin);
                int b = Math.Min(segs[segs.Count - 1][0], segs[segs.Count - 2][1] + margin);
                int len = b - a;
                if (len < SR / 50) { failed++; continue; }
                // 6 ms fades, then ADPCM.
                int fade = SR * 6 / 1000;
                var seg = new short[len];
                for (int i = 0; i < len; i++) {
                    double g = 1.0;
                    if (i < fade) g = (double)i / fade;
                    else if (i >= len - fade) g = (double)(len - 1 - i) / fade;
                    seg[i] = (short)(pcm[a + i] * g);
                }
                var outBytes = Adpcm(seg);
                blob.Write(outBytes, 0, outBytes.Length);
                index.Append(word).Append('\t').Append(offset).Append('\t').Append(len).Append('\n');
                offset += outBytes.Length;
                totalSec += (double)len / SR;
                ok++;
            }
        }
        synth.Dispose();
        File.WriteAllText(indexPath, index.ToString(), new UTF8Encoding(false));
        return string.Format("recorded {0} words, {1} failed, {2:F0} s of audio, {3:F1} MB", ok, failed, totalSec, offset / 1048576.0);
    }
}
'@

$list = [string[]]($words | Sort-Object)
Remove-Item (Join-Path $outDir "words.ulaw") -ErrorAction SilentlyContinue
"Recording $($list.Count) words..."
[DavidWordRecorder]::Record($list, (Join-Path $outDir "words.adpcm"), (Join-Path $outDir "index.tsv"))
