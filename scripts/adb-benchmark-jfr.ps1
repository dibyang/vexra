param(
    [string]$Workload = "mixed",
    [int]$Rows = 5000,
    [int]$WarmupOperations = 300,
    [int]$Operations = 3000,
    [int]$Threads = 8,
    [int]$RangeSize = 32,
    [string]$Mode = "jdbc",
    [string]$TableEngine = "adb",
    [string]$SqlDiagnostics = "true",
    [string]$OutputDir = "vexra-adb/build/adb-benchmark/jfr",
    [string]$Url = "",
    [string]$JavaHome = ""
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

function Get-JavaVersionInfo {
    param([string]$JavaExe)

    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $versionLine = (& $JavaExe -version 2>&1 | Select-Object -First 1).ToString()
    $ErrorActionPreference = $previousErrorActionPreference

    $major = 0
    if ($versionLine -match '"1\.(\d+)\.') {
        $major = [int]$Matches[1]
    } elseif ($versionLine -match '"(\d+)') {
        $major = [int]$Matches[1]
    }

    [pscustomobject]@{
        Major = $major
        Line = $versionLine
    }
}

function Use-JavaHome {
    param([string]$HomePath)

    $resolvedJavaHome = Resolve-Path -LiteralPath $HomePath
    $env:JAVA_HOME = $resolvedJavaHome.Path
    $env:Path = (Join-Path $resolvedJavaHome.Path "bin") + [System.IO.Path]::PathSeparator + $env:Path
    Get-Command java -ErrorAction Stop
}

function Find-ReadableJfrJavaHome {
    $candidates = New-Object System.Collections.Generic.List[string]

    if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
        $candidates.Add($env:JAVA_HOME)
    }
    $candidates.Add("C:\Program Files\Java\latest")
    $candidates.Add("C:\Program Files\Java\jdk-21")
    $candidates.Add("C:\Program Files\Java\jdk-17")
    $candidates.Add("C:\Program Files\Java\jdk-11")

    if (Test-Path -LiteralPath "C:\Program Files\Java") {
        Get-ChildItem -LiteralPath "C:\Program Files\Java" -Directory -ErrorAction SilentlyContinue |
            Sort-Object Name -Descending |
            ForEach-Object { $candidates.Add($_.FullName) }
    }

    foreach ($candidate in $candidates | Select-Object -Unique) {
        if ([string]::IsNullOrWhiteSpace($candidate)) {
            continue
        }
        $javaExe = Join-Path $candidate "bin\java.exe"
        if (-not (Test-Path -LiteralPath $javaExe)) {
            continue
        }
        $version = Get-JavaVersionInfo -JavaExe $javaExe
        if ($version.Major -ge 11) {
            [pscustomobject]@{
                Home = (Resolve-Path -LiteralPath $candidate).Path
                Java = $javaExe
                Major = $version.Major
                VersionLine = $version.Line
            }
            return
        }
    }

    $null
}

if (-not [string]::IsNullOrWhiteSpace($JavaHome)) {
    $java = Use-JavaHome -HomePath $JavaHome
    $javaInfo = Get-JavaVersionInfo -JavaExe $java.Source
} else {
    $readableJfrJava = Find-ReadableJfrJavaHome
    if ($null -ne $readableJfrJava) {
        $java = Use-JavaHome -HomePath $readableJfrJava.Home
        $javaInfo = [pscustomobject]@{
            Major = $readableJfrJava.Major
            Line = $readableJfrJava.VersionLine
        }
    } else {
        $java = Get-Command java -ErrorAction Stop
        $javaInfo = Get-JavaVersionInfo -JavaExe $java.Source
    }
}

$javaVersionLine = $javaInfo.Line

$resolvedOutput = Join-Path $root $OutputDir
New-Item -ItemType Directory -Force -Path $resolvedOutput | Out-Null

$safeWorkload = $Workload -replace "[^A-Za-z0-9_-]", "-"
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$jfrFile = Join-Path $resolvedOutput "adb-$safeWorkload-$timestamp.jfr"
$reportFile = Join-Path $resolvedOutput "adb-$safeWorkload-$timestamp.properties"

if ([string]::IsNullOrWhiteSpace($Url)) {
    $dbPath = (Join-Path $resolvedOutput "db-$safeWorkload-$timestamp") -replace "\\", "/"
    $Url = "jdbc:adb:ldb:$dbPath/adb-benchmark;DB_CLOSE_DELAY=0"
}

$jfrPath = $jfrFile -replace "\\", "/"
if ($javaInfo.Major -eq 8) {
    $jfrArgs = "-XX:+UnlockCommercialFeatures -XX:StartFlightRecording=filename=$jfrPath,settings=profile,dumponexit=true"
} else {
    $jfrArgs = "-XX:StartFlightRecording=filename=$jfrPath,settings=profile,dumponexit=true"
}

if ($javaInfo.Major -gt 0 -and $javaInfo.Major -lt 11) {
    Write-Warning "Java $($javaInfo.Major) may generate an old JFR format that adb-jfr-hotspots.ps1 cannot parse. Pass -JavaHome with JDK 11+ if parsing fails."
}

& .\gradlew.bat :vexra-adb:adbBenchmark `
    "-PadbBenchmarkMode=$Mode" `
    "-PadbBenchmarkUrl=$Url" `
    "-PadbBenchmarkWorkload=$Workload" `
    "-PadbBenchmarkRows=$Rows" `
    "-PadbBenchmarkWarmupOperations=$WarmupOperations" `
    "-PadbBenchmarkOperations=$Operations" `
    "-PadbBenchmarkThreads=$Threads" `
    "-PadbBenchmarkRangeSize=$RangeSize" `
    "-PadbBenchmarkTableEngine=$TableEngine" `
    "-PadbBenchmarkSqlDiagnostics=$SqlDiagnostics" `
    "-PadbBenchmarkOutput=$reportFile" `
    "-PadbBenchmarkJvmArgs=$jfrArgs"

Write-Host "JFR: $jfrFile"
Write-Host "Report: $reportFile"
Write-Host "Java: $($java.Source)"
Write-Host "Java version: $javaVersionLine"
Write-Host "Java major: $($javaInfo.Major)"
