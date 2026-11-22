param(
    [Parameter(Mandatory = $true)][string]$Sdk,
    [Parameter(Mandatory = $true)][string]$Jdk,
    [string]$Platform = 'android-36',
    [string]$BuildTools = '35.0.0',
    [string]$Destination = (Join-Path $PSScriptRoot 'build\Data-Warner.apk'),
    [string]$SigningKey = (Join-Path $PSScriptRoot 'build\mobil-veri.jks'),
    [string]$SigningAlias = 'mobil-veri'
)

$ErrorActionPreference = 'Stop'
if (!$env:DATA_WARNER_STORE_PASSWORD -or !$env:DATA_WARNER_KEY_PASSWORD) {
    throw 'Set DATA_WARNER_STORE_PASSWORD and DATA_WARNER_KEY_PASSWORD before signing.'
}
$tools = Join-Path $Sdk "build-tools\$BuildTools"
$jar = Join-Path $Sdk "platforms\$Platform\android.jar"
$bin = Join-Path $Jdk 'bin'
$env:JAVA_HOME = $Jdk
$build = Join-Path $PSScriptRoot 'build'
$app = Join-Path $PSScriptRoot 'app\src\main'
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
$manifest = Join-Path $build 'AndroidManifest.xml'
$gradleConfig = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'app\build.gradle.kts') -Raw
[xml]$appManifest = Get-Content -LiteralPath (Join-Path $app 'AndroidManifest.xml') -Raw -Encoding utf8
$androidNamespace = 'http://schemas.android.com/apk/res/android'
$sdkElement = $appManifest.CreateElement('uses-sdk')
foreach ($name in @('minSdk', 'targetSdk', 'versionCode', 'versionName')) {
    $setting = [regex]::Match($gradleConfig, "(?m)^\s*$name\s*=\s*`"?([\d.]+)")
    if (!$setting.Success) { throw "Missing Android setting $name in app/build.gradle.kts." }
    if ($name.EndsWith('Sdk')) {
        $sdkElement.SetAttribute("${name}Version", $androidNamespace, $setting.Groups[1].Value)
    } else {
        $appManifest.DocumentElement.SetAttribute($name, $androidNamespace, $setting.Groups[1].Value)
    }
}
$appManifest.DocumentElement.PrependChild($sdkElement) | Out-Null

function Invoke-Checked([string]$Program, [string[]]$Arguments) {
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Program failed with exit code $LASTEXITCODE" }
}

New-Item -ItemType Directory -Force $classes, $generated, $dex, (Split-Path $Destination -Parent), (Split-Path $SigningKey -Parent) | Out-Null
$appManifest.DocumentElement.SetAttribute('package', 'tr.suleyman.mobilveri')
$appManifest.Save($manifest)
Invoke-Checked $aapt2 @('compile', '--dir', (Join-Path $app 'res'), '-o', $resources)
Invoke-Checked $aapt2 @('link', '-I', $jar, '--manifest', $manifest, '--java', $generated, '-o', $base, $resources)
$sources = @(Get-ChildItem (Join-Path $app 'java'), $generated -Recurse -Filter '*.java' | Select-Object -ExpandProperty FullName)
Invoke-Checked (Join-Path $bin 'javac.exe') (@('--release', '8', '-encoding', 'UTF-8', '-classpath', $jar, '-d', $classes) + $sources)
$inputs = @(Get-ChildItem $classes -Recurse -Filter '*.class' | Select-Object -ExpandProperty FullName)
Invoke-Checked (Join-Path $bin 'java.exe') (@('-cp', (Join-Path $tools 'lib\d8.jar'), 'com.android.tools.r8.D8', '--release', '--min-api', '30', '--lib', $jar, '--output', $dex) + $inputs)
Copy-Item -Force -LiteralPath $base -Destination $unsigned
Invoke-Checked (Join-Path $bin 'jar.exe') @('uf', $unsigned, '-C', $dex, 'classes.dex')
Invoke-Checked $zipalign @('-f', '4', $unsigned, $aligned)
if (!(Test-Path -LiteralPath $SigningKey)) {
    Invoke-Checked $keytool @('-genkeypair', '-storetype', 'JKS', '-keystore', $SigningKey, '-storepass:env', 'DATA_WARNER_STORE_PASSWORD', '-keypass:env', 'DATA_WARNER_KEY_PASSWORD', '-alias', $SigningAlias, '-keyalg', 'RSA', '-keysize', '2048', '-validity', '10000', '-dname', 'CN=Data Warner APK')
}
Invoke-Checked $apksigner @('sign', '--ks', $SigningKey, '--ks-key-alias', $SigningAlias, '--ks-pass', 'env:DATA_WARNER_STORE_PASSWORD', '--key-pass', 'env:DATA_WARNER_KEY_PASSWORD', '--v4-signing-enabled', 'false', '--out', $Destination, $aligned)
Invoke-Checked $apksigner @('verify', '--verbose', $Destination)
Invoke-Checked $aapt2 @('dump', 'badging', $Destination)
