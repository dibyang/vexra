param(
    [string]$Workload = "mixed",
    [int]$Rows = 5000,
    [int]$WarmupOperations = 300,
    [int]$Operations = 3000,
    [int]$Threads = 8,
    [string]$Mode = "jdbc",
    [string]$OutputDir = "vexra-adb/build/adb-benchmark/jfr",
    [string]$Url = ""
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

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
$jfrArgs = "-XX:+UnlockCommercialFeatures -XX:StartFlightRecording=filename=$jfrPath,settings=profile,dumponexit=true"

& .\gradlew.bat :vexra-adb:adbBenchmark `
    "-PadbBenchmarkMode=$Mode" `
    "-PadbBenchmarkUrl=$Url" `
    "-PadbBenchmarkWorkload=$Workload" `
    "-PadbBenchmarkRows=$Rows" `
    "-PadbBenchmarkWarmupOperations=$WarmupOperations" `
    "-PadbBenchmarkOperations=$Operations" `
    "-PadbBenchmarkThreads=$Threads" `
    "-PadbBenchmarkOutput=$reportFile" `
    "-PadbBenchmarkJvmArgs=$jfrArgs"

Write-Host "JFR: $jfrFile"
Write-Host "Report: $reportFile"
