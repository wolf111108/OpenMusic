[CmdletBinding()]
param(
    [string]$JdkPath,
    [switch]$SkipTests,
    [switch]$Install
)

$ErrorActionPreference = 'Stop'
$buildExitCode = 1
$originalJavaHome = $env:JAVA_HOME
$originalPath = $env:PATH
$originalAndroidHome = $env:ANDROID_HOME
$locationPushed = $false

function Test-CompleteJdk([string]$Candidate) {
    if ([string]::IsNullOrWhiteSpace($Candidate)) { return $false }
    return ((Test-Path -LiteralPath (Join-Path $Candidate 'bin\java.exe') -PathType Leaf) -and
        (Test-Path -LiteralPath (Join-Path $Candidate 'bin\javac.exe') -PathType Leaf))
}

function Find-Jdk {
    if (-not [string]::IsNullOrWhiteSpace($JdkPath)) {
        if (-not (Test-CompleteJdk $JdkPath)) {
            throw "JDK directory must contain bin\java.exe and bin\javac.exe: $JdkPath"
        }
        return (Resolve-Path -LiteralPath $JdkPath).Path
    }
    if (Test-CompleteJdk $env:JAVA_HOME) { return $env:JAVA_HOME }

    $candidates = @()
    if ($env:ProgramFiles) {
        $candidates += Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'
    }
    if ($env:LOCALAPPDATA) {
        $candidates += Join-Path $env:LOCALAPPDATA 'Programs\Android Studio\jbr'
        $candidates += Join-Path $env:LOCALAPPDATA 'Android\Android Studio\jbr'
    }
    foreach ($candidate in $candidates) {
        if (Test-CompleteJdk $candidate) { return $candidate }
    }
    $javaCommand = Get-Command 'java.exe' -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($javaCommand) {
        $candidate = Split-Path -Parent (Split-Path -Parent $javaCommand.Source)
        if (Test-CompleteJdk $candidate) { return $candidate }
    }
    throw 'A full JDK is required. Install JDK 17 or use -JdkPath with your Android Studio jbr directory.'
}

try {
    $repoRoot = Split-Path -Parent $PSScriptRoot
    $gradleWrapper = Join-Path $repoRoot 'gradlew.bat'
    if (-not (Test-Path -LiteralPath $gradleWrapper -PathType Leaf)) {
        throw "Gradle Wrapper was not found: $gradleWrapper"
    }
    $selectedJdk = Find-Jdk
    $releaseFile = Join-Path $selectedJdk 'release'
    if (Test-Path -LiteralPath $releaseFile -PathType Leaf) {
        $releaseInfo = Get-Content -LiteralPath $releaseFile -Raw
        if ($releaseInfo -match 'JAVA_VERSION="(?<major>\d+)') {
            $javaMajor = [int]$Matches.major
            if ($javaMajor -lt 17 -or $javaMajor -gt 23) {
                throw "Gradle 8.11.1 requires a compatible JDK. Selected JDK: $javaMajor. Pass -JdkPath with JDK 17 or JBR 21."
            }
        }
    }
    $env:JAVA_HOME = $selectedJdk
    $env:PATH = (Join-Path $selectedJdk 'bin') + [IO.Path]::PathSeparator + $originalPath

    $localProperties = Join-Path $repoRoot 'local.properties'
    $hasLocalSdk = (Test-Path -LiteralPath $localProperties -PathType Leaf) -and
        ((Get-Content -LiteralPath $localProperties -Raw) -match '(?m)^\s*sdk\.dir\s*=')
    if (-not $hasLocalSdk -and [string]::IsNullOrWhiteSpace($env:ANDROID_HOME) -and
        [string]::IsNullOrWhiteSpace($env:ANDROID_SDK_ROOT)) {
        if ($env:LOCALAPPDATA) {
            $defaultSdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
            if (Test-Path -LiteralPath $defaultSdk -PathType Container) {
                $env:ANDROID_HOME = $defaultSdk
            }
        }
        if ([string]::IsNullOrWhiteSpace($env:ANDROID_HOME)) {
            throw 'Android SDK not found. Install SDK Platform 35 and Build-Tools 35.0.0 in Android Studio, then open this project or set ANDROID_HOME.'
        }
    }

    Push-Location -LiteralPath $repoRoot
    $locationPushed = $true
    Write-Host "JDK: $selectedJdk"
    Write-Host "Project: $repoRoot"
    $gradleArguments = @()
    if (-not $SkipTests) { $gradleArguments += @(':core:test', ':app:testDebugUnitTest') }
    $gradleArguments += ':app:assembleDebug'
    if ($Install) { $gradleArguments += ':app:installDebug' }
    $gradleArguments += '--console=plain'

    & $gradleWrapper @gradleArguments
    $buildExitCode = $LASTEXITCODE
    if ($buildExitCode -eq 0) {
        $apkPath = Join-Path $repoRoot 'app\build\outputs\apk\debug\app-debug.apk'
        if (-not (Test-Path -LiteralPath $apkPath -PathType Leaf)) {
            throw 'Gradle completed, but the expected debug APK was not found.'
        }
        Write-Host "APK: $apkPath"
    }
}
catch {
    [Console]::Error.WriteLine($_.Exception.Message)
    if ($buildExitCode -eq 0) { $buildExitCode = 1 }
}
finally {
    if ($locationPushed) { Pop-Location }
    $env:JAVA_HOME = $originalJavaHome
    $env:PATH = $originalPath
    $env:ANDROID_HOME = $originalAndroidHome
}

exit $buildExitCode
