param(
    [Parameter(Mandatory = $true)]
    [string]$JfrFile,
    [string]$OutputDir = ""
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path -LiteralPath $JfrFile)) {
    throw "JFR file not found: $JfrFile"
}

$jfr = Get-Command jfr -ErrorAction SilentlyContinue
if ($null -eq $jfr) {
    throw "JDK jfr tool was not found. Install or select a JDK 11+ and retry."
}

$resolvedJfr = Resolve-Path -LiteralPath $JfrFile
if ([string]::IsNullOrWhiteSpace($OutputDir)) {
    $OutputDir = Join-Path (Split-Path -Parent $resolvedJfr.Path) "hotspots"
}
New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null

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
