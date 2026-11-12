param(
    [string]$JdkHome = (Join-Path $PSScriptRoot '.jdk21'),
    [switch]$SkipTests
)

$ErrorActionPreference = 'Stop'
if (!(Test-Path -LiteralPath $JdkHome) -and !$PSBoundParameters.ContainsKey('JdkHome')) {
    $installedJdk = Get-ChildItem -LiteralPath 'C:\Program Files\Eclipse Adoptium' -Directory -ErrorAction SilentlyContinue |
        Where-Object Name -Like 'jdk-21*' | Sort-Object Name -Descending | Select-Object -First 1
    if ($installedJdk) { $JdkHome = $installedJdk.FullName }
}
$javaExe = Join-Path $JdkHome 'bin\java.exe'
if (!(Test-Path -LiteralPath $javaExe)) {
    throw "JDK 21 not found at $JdkHome. Extract a Windows x64 JDK 21 ZIP there, or pass -JdkHome with its extracted directory."
}
$releaseFile = Join-Path $JdkHome 'release'
if (!(Test-Path -LiteralPath $releaseFile) -or
    !((Get-Content -LiteralPath $releaseFile) -match '^JAVA_VERSION="21(?:\.|"|\+)')) {
    throw 'This project requires a JDK 21 installation.'
}
if (!(Test-Path -LiteralPath (Join-Path $JdkHome 'bin\javac.exe'))) {
    throw 'A full JDK is required, not a JRE.'
}
$previousJava = $env:JAVA_HOME
$previousSdk = $env:ANDROID_HOME
Push-Location $PSScriptRoot
try {
    $env:JAVA_HOME = (Resolve-Path -LiteralPath $JdkHome).Path
    if (!$env:ANDROID_HOME) {
        $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
    }
    $tasks = @(':app:assembleDebug')
    if (!$SkipTests) { $tasks += ':app:testDebugUnitTest' }
    & .\gradlew.bat "-Dorg.gradle.java.home=$env:JAVA_HOME" @tasks --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }
    Write-Output "APK: $(Join-Path $PSScriptRoot 'app\build\outputs\apk\debug\app-debug.apk')"
} finally {
    $env:JAVA_HOME = $previousJava
    $env:ANDROID_HOME = $previousSdk
    Pop-Location
}
