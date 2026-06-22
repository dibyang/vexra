param(
    [Parameter(Mandatory = $true)]
    [string]$JfrFile,
    [string]$OutputDir = "",
    [string]$JavaHome = ""
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path -LiteralPath $JfrFile)) {
    throw "JFR file not found: $JfrFile"
}

$jfr = Get-Command jfr -ErrorAction SilentlyContinue

$resolvedJfr = Resolve-Path -LiteralPath $JfrFile
if ([string]::IsNullOrWhiteSpace($OutputDir)) {
    $OutputDir = Join-Path (Split-Path -Parent $resolvedJfr.Path) "hotspots"
}
New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null

if ($null -ne $jfr) {
    $summaryFile = Join-Path $OutputDir "summary.txt"
    $allocationFile = Join-Path $OutputDir "allocation-events.txt"
    $executionFile = Join-Path $OutputDir "execution-samples.txt"
    $focusFile = Join-Path $OutputDir "adb-focus.txt"

    & $jfr.Source summary $resolvedJfr.Path | Out-File -FilePath $summaryFile -Encoding utf8
    & $jfr.Source print --events jdk.ObjectAllocationInNewTLAB,jdk.ObjectAllocationOutsideTLAB $resolvedJfr.Path |
        Out-File -FilePath $allocationFile -Encoding utf8
    & $jfr.Source print --events jdk.ExecutionSample $resolvedJfr.Path |
        Out-File -FilePath $executionFile -Encoding utf8

    $patterns = @(
        "java.lang.reflect.Proxy",
        "AdbSimpleResultSet",
        "AdbPreparedStatementProxy",
        "AdbJdbcProxy",
        "TxnMap2.getVisible",
        "DefaultVisibleRowResolver",
        "RowValue.decodeValue",
        "RowCodec",
        "ADB_COMMIT",
        "WriteBatch",
        "commit"
    )

    Select-String -Path $allocationFile,$executionFile -Pattern $patterns -SimpleMatch |
        ForEach-Object { $_.ToString() } |
        Out-File -FilePath $focusFile -Encoding utf8

    Write-Host "Summary: $summaryFile"
    Write-Host "Allocation events: $allocationFile"
    Write-Host "Execution samples: $executionFile"
    Write-Host "ADB focus matches: $focusFile"
    exit 0
}

function Resolve-Java11 {
    param([string]$PreferredJavaHome)

    $candidates = @()
    if (-not [string]::IsNullOrWhiteSpace($PreferredJavaHome)) {
        $candidates += Join-Path $PreferredJavaHome "bin\java.exe"
    }
    $candidates += "C:\Program Files\Java\jdk-11\bin\java.exe"
    $candidates += "C:\Program Files\Java\latest\bin\java.exe"
    $candidates += Get-ChildItem -LiteralPath "C:\Program Files\Java" -Recurse -Filter java.exe -ErrorAction SilentlyContinue |
        ForEach-Object { $_.FullName }

    foreach ($candidate in $candidates | Select-Object -Unique) {
        if ([string]::IsNullOrWhiteSpace($candidate) -or -not (Test-Path -LiteralPath $candidate)) {
            continue
        }
        $previousErrorActionPreference = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        $versionLine = (& $candidate -version 2>&1 | Select-Object -First 1).ToString()
        $ErrorActionPreference = $previousErrorActionPreference
        if ($versionLine -match '"((1\.)?([0-9]+))') {
            $major = if ($Matches[1] -eq "1.") { [int]$Matches[3] } else { [int]$Matches[3] }
            if ($major -ge 11) {
                return $candidate
            }
        }
    }
    return $null
}

$java11 = Resolve-Java11 -PreferredJavaHome $JavaHome
if ($null -eq $java11) {
    throw "Neither JDK jfr tool nor Java 11+ was found. Install or select a JDK 11+ and retry."
}

$parser = Join-Path $PSScriptRoot "AdbJfrHotspots.java"
if (-not (Test-Path -LiteralPath $parser)) {
    throw "JFR parser source was not found: $parser"
}

& $java11 $parser $resolvedJfr.Path $OutputDir
