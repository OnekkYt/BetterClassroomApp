param([switch]$Bump, [string]$DevZip)   # -DevZip <zip>: bundle that extension and never use the Web Store   # -Bump raises the minor number first (1.4 -> 1.5)
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
$bt = (Get-ChildItem "$sdk\build-tools" -Directory | Sort-Object { [version]($_.Name -replace '-.*') } -Descending | Select-Object -First 1).FullName
$jar = "$sdk\platforms\android-35\android.jar"
if (-not (Test-Path $jar)) {
    $jar = (Get-ChildItem "$sdk\platforms" -Directory | Sort-Object Name -Descending | Select-Object -First 1).FullName + '\android.jar'
}
$out = "$here\build"
$ks = "$here\debug.keystore"

$vf = "$here\version.txt"
$ver = (Get-Content $vf -Raw).Trim()
if ($Bump) {
    $p = $ver.Split('.')
    $ver = "$($p[0]).$([int]$p[1] + 1)"
    Set-Content $vf $ver -NoNewline
}
$p = $ver.Split('.')
$code = [int]$p[0] * 100 + [int]$p[1]   # 1.4 -> 104, so every new version installs as an update

Remove-Item $out -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory "$out\classes", "$out\dex" | Out-Null

$assets = "$here\assets"
$name = $ver
$apkName = "BetterClassroom.apk"
if ($DevZip) {
    $assets = "$out\assets"
    Copy-Item "$here\assets" $assets -Recurse
    Remove-Item "$assets\ext" -Recurse -Force
    Expand-Archive $DevZip "$assets\ext" -Force
    Remove-Item "$assets\ext\README.md" -Force -ErrorAction SilentlyContinue
    Set-Content "$assets\dev.flag" "dev" -NoNewline
    $name = "$ver-dev"
    $apkName = "BetterClassroom-dev.apk"
}

& "$bt\aapt2.exe" compile --dir "$here\res" -o "$out\res.zip"
if ($LASTEXITCODE) { throw 'aapt2 compile failed' }
& "$bt\aapt2.exe" link -o "$out\base.apk" -I $jar --manifest "$here\AndroidManifest.xml" `
    --min-sdk-version 24 --target-sdk-version 34 --version-code $code --version-name $name `
    "$out\res.zip"
if ($LASTEXITCODE) { throw 'aapt2 link failed' }

$src = Get-ChildItem "$here\src" -Recurse -Filter *.java | ForEach-Object FullName
& javac --release 8 -encoding UTF-8 -Xlint:-options -cp $jar -d "$out\classes" $src
if ($LASTEXITCODE) { throw 'javac failed' }

$cls = Get-ChildItem "$out\classes" -Recurse -Filter *.class | ForEach-Object FullName
& "$bt\d8.bat" --lib $jar --min-api 24 --output "$out\dex" $cls
if ($LASTEXITCODE) { throw 'd8 failed' }

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open("$out\base.apk", 'Update')
[void][System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$out\dex\classes.dex", 'classes.dex')
# assets go in by hand: aapt2 on Windows writes their folder separators as backslashes
$assetRoot = (Resolve-Path $assets).Path
foreach ($f in Get-ChildItem $assetRoot -Recurse -File) {
    $rel = $f.FullName.Substring($assetRoot.Length + 1).Replace('\','/')
    [void][System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $f.FullName, "assets/$rel")
}
$zip.Dispose()

if (-not (Test-Path $ks)) {
    & keytool -genkeypair -keystore $ks -storepass android -keypass android -alias debug `
        -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=Better Classroom'
}
& "$bt\zipalign.exe" -f -p 4 "$out\base.apk" "$out\aligned.apk"
if ($LASTEXITCODE) { throw 'zipalign failed' }
& "$bt\apksigner.bat" sign --ks $ks --ks-pass pass:android --key-pass pass:android `
    --out "$here\$apkName" "$out\aligned.apk"
if ($LASTEXITCODE) { throw 'apksigner failed' }
Write-Host "Built version $name (code $code): $here\$apkName"
