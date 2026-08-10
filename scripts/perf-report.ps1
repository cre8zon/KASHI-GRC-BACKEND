<#
.SYNOPSIS
    perf-report.ps1 — pull the server-side latency + query-count profile.

.DESCRIPTION
    Reads what RequestPerfFilter collected. The key column is AvgQueries:

        hundreds of queries   -> N+1, batch the reads
        slow with few queries -> missing index, or the payload is just large

    Requires kashi.perf.enabled=true in application.properties and a restart,
    and a SIDE_SYSTEM user's JWT — /v1/admin/** is restricted to that authority
    in SecurityConfig, so a vendor or org token gets a 403.

.EXAMPLE
    # Typical run
    $env:KASHI_TOKEN  = 'eyJhbGci...'     # must be a SIDE_SYSTEM user
    $env:KASHI_TENANT = '4'

    .\scripts\perf-report.ps1 -Reset       # clear warm-up noise
    # ... click through the app, or run perf-smoke.ps1 ...
    .\scripts\perf-report.ps1              # table, worst p95 first
    .\scripts\perf-report.ps1 -Csv perf.csv
#>

[CmdletBinding()]
param(
    [string]$BaseUrl  = $(if ($env:KASHI_BASE) { $env:KASHI_BASE } else { 'http://localhost:8080' }),
    [string]$Token    = $env:KASHI_TOKEN,
    [string]$TenantId = $env:KASHI_TENANT,
    [int]$MinMs       = 0,
    [int]$MinQueries  = 0,
    [string]$Csv      = '',
    [switch]$Reset
)

if ([string]::IsNullOrWhiteSpace($Token))    { Write-Error 'Set $env:KASHI_TOKEN (SIDE_SYSTEM user).'; exit 1 }
if ([string]::IsNullOrWhiteSpace($TenantId)) { Write-Error 'Set $env:KASHI_TENANT.'; exit 1 }

$headers = @{ 'Authorization' = "Bearer $Token"; 'X-Tenant-ID' = $TenantId }

if ($Reset) {
    try {
        Invoke-RestMethod -Uri "$BaseUrl/v1/admin/perf/report" -Headers $headers `
                          -Method Delete -ErrorAction Stop | Out-Null
        Write-Host 'Counters reset. Exercise the app, then run this again.' -ForegroundColor Green
    } catch {
        Write-Error "Reset failed: $($_.Exception.Message)"
        Write-Host 'A 403 here means the token is not a SIDE_SYSTEM user.' -ForegroundColor Yellow
        Write-Host 'A 404 means kashi.perf.enabled is still false (the beans are conditional).' -ForegroundColor Yellow
    }
    return
}

# CSV straight from the server — same data, already formatted.
if ($Csv) {
    try {
        Invoke-WebRequest -Uri "$BaseUrl/v1/admin/perf/report.csv" -Headers $headers `
                          -UseBasicParsing -OutFile $Csv -ErrorAction Stop
        Write-Host "Written: $Csv" -ForegroundColor Green
    } catch {
        Write-Error "Download failed: $($_.Exception.Message)"
    }
    return
}

try {
    $url  = "$BaseUrl/v1/admin/perf/report?minMs=$MinMs&minQueries=$MinQueries"
    $resp = Invoke-RestMethod -Uri $url -Headers $headers -Method Get -ErrorAction Stop
} catch {
    Write-Error "Request failed: $($_.Exception.Message)"
    Write-Host 'A 403 means the token is not a SIDE_SYSTEM user.' -ForegroundColor Yellow
    Write-Host 'A 404 means kashi.perf.enabled is still false — set it and restart.' -ForegroundColor Yellow
    return
}

$data = $resp.data
if (-not $data -or -not $data.endpoints -or $data.endpoints.Count -eq 0) {
    Write-Host 'No data collected yet. Exercise the app first, then run again.' -ForegroundColor Yellow
    return
}

Write-Host ''
Write-Host '== All endpoints, worst p95 first ==' -ForegroundColor Cyan
$data.endpoints |
    Select-Object @{n='Endpoint';e={$_.endpoint}},
                  @{n='Calls';e={$_.calls}},
                  @{n='AvgMs';e={$_.avgMs}},
                  @{n='P95Ms';e={$_.p95Ms}},
                  @{n='MaxMs';e={$_.maxMs}},
                  @{n='AvgQueries';e={$_.avgQueries}},
                  @{n='MaxQueries';e={$_.maxQueries}} |
    Format-Table -AutoSize

if ($data.likelyNPlusOne -and $data.likelyNPlusOne.Count -gt 0) {
    Write-Host '== Likely N+1 (30+ queries per call) — fix these first ==' -ForegroundColor Yellow
    $data.likelyNPlusOne |
        Select-Object @{n='Endpoint';e={$_.endpoint}},
                      @{n='AvgQueries';e={$_.avgQueries}},
                      @{n='P95Ms';e={$_.p95Ms}} |
        Format-Table -AutoSize
} else {
    Write-Host 'No endpoint averaged 30+ queries per call.' -ForegroundColor Green
}

Write-Host $data.hint -ForegroundColor DarkGray