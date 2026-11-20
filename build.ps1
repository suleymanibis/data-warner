param(
    [Parameter(Mandatory = $true)][string]$Sdk,
    [Parameter(Mandatory = $true)][string]$Jdk,
    [string]$Platform = 'android-36',
    [string]$BuildTools = '35.0.0',
    [string]$Destination = (Join-Path $PSScriptRoot 'build\Mobil-Veri.apk'),
    [string]$SigningKey = (Join-Path $PSScriptRoot 'build\mobil-veri.jks')
)

$ErrorActionPreference = 'Stop'
$tools = Join-Path $Sdk "build-tools\$BuildTools"
$jar = Join-Path $Sdk "platforms\$Platform\android.jar"
$bin = Join-Path $Jdk 'bin'
$env:JAVA_HOME = $Jdk
$build = Join-Path $PSScriptRoot 'build'
$classes = Join-Path $build 'classes'
$generated = Join-Path $build 'generated'
$dex = Join-Path $build 'dex'
$resources = Join-Path $build 'resources.zip'
$base = Join-Path $build 'resources.apk'
$unsigned = Join-Path $build 'unsigned.apk'
$aligned = Join-Path $build 'aligned.apk'
$aapt2 = Join-Path $tools 'aapt2.exe'
$zipalign = Join-Path $tools 'zipalign.exe'
$apksigner = Join-Path $tools 'apksigner.bat'
$keytool = Join-Path $bin 'keytool.exe'

function Invoke-Checked([string]$Program, [string[]]$Arguments) {
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Program failed with exit code $LASTEXITCODE" }
}

New-Item -ItemType Directory -Force $classes, $generated, $dex, (Split-Path $Destination -Parent), (Split-Path $SigningKey -Parent) | Out-Null
Invoke-Checked $aapt2 @('compile', '--dir', (Join-Path $PSScriptRoot 'res'), '-o', $resources)
Invoke-Checked $aapt2 @('link', '-I', $jar, '--manifest', (Join-Path $PSScriptRoot 'AndroidManifest.xml'), '--java', $generated, '-o', $base, $resources)
$sources = @(Get-ChildItem (Join-Path $PSScriptRoot 'src'), $generated -Recurse -Filter '*.java' | Select-Object -ExpandProperty FullName)
Invoke-Checked (Join-Path $bin 'javac.exe') (@('--release', '8', '-encoding', 'UTF-8', '-classpath', $jar, '-d', $classes) + $sources)
$inputs = @(Get-ChildItem $classes -Recurse -Filter '*.class' | Select-Object -ExpandProperty FullName)
Invoke-Checked (Join-Path $bin 'java.exe') (@('-cp', (Join-Path $tools 'lib\d8.jar'), 'com.android.tools.r8.D8', '--release', '--min-api', '30', '--lib', $jar, '--output', $dex) + $inputs)
Copy-Item -Force -LiteralPath $base -Destination $unsigned
Invoke-Checked (Join-Path $bin 'jar.exe') @('uf', $unsigned, '-C', $dex, 'classes.dex')
Invoke-Checked $zipalign @('-f', '4', $unsigned, $aligned)
if (!(Test-Path -LiteralPath $SigningKey)) {
    Invoke-Checked $keytool @('-genkeypair', '-keystore', $SigningKey, '-storepass', 'android', '-keypass', 'android', '-alias', 'mobil-veri', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '10000', '-dname', 'CN=Mobil Veri Personal APK')
}
Invoke-Checked $apksigner @('sign', '--ks', $SigningKey, '--ks-key-alias', 'mobil-veri', '--ks-pass', 'pass:android', '--key-pass', 'pass:android', '--v4-signing-enabled', 'false', '--out', $Destination, $aligned)
Invoke-Checked $apksigner @('verify', '--verbose', $Destination)
Invoke-Checked $aapt2 @('dump', 'badging', $Destination)
