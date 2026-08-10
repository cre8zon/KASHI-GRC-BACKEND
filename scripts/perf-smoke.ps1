<#
.SYNOPSIS
    perf-smoke.ps1 — hit every GET endpoint once and rank them by wall time.

.DESCRIPTION
    Client-side half of the profiler. Gives total round-trip time as the browser
    experiences it (network + server). The server-side report at
    /v1/admin/perf/report gives the query count that explains WHY. Run both.

    Only GETs are listed, so this is safe against a live tenant.

.EXAMPLE
    $env:KASHI_TOKEN  = 'eyJhbGci...'      # JWT from devtools, any request header
    $env:KASHI_TENANT = '4'
    $env:ASSESSMENT_ID = '71'
    .\scripts\perf-smoke.ps1

.NOTES
    If PowerShell blocks the script with "running scripts is disabled",
    either run it once as:
        powershell -ExecutionPolicy Bypass -File .\scripts\perf-smoke.ps1
    or allow local scripts permanently for your user:
        Set-ExecutionPolicy -Scope CurrentUser RemoteSigned
#>

[CmdletBinding()]
param(
    [string]$BaseUrl      = $(if ($env:KASHI_BASE)    { $env:KASHI_BASE }    else { 'http://localhost:8080' }),
    [string]$Token        = $env:KASHI_TOKEN,
    [string]$TenantId     = $env:KASHI_TENANT,
    [string]$AssessmentId = $env:ASSESSMENT_ID,
    [string]$VendorId     = $env:VENDOR_ID,
    [string]$InstanceId   = $env:INSTANCE_ID,
    [int]$SlowThresholdMs = 2000,
    [string]$CsvOut       = ''
)

if ([string]::IsNullOrWhiteSpace($Token)) {
    Write-Error "Set `$env:KASHI_TOKEN to a valid JWT (copy from devtools -> any request -> Authorization header, minus 'Bearer ')."
    exit 1
}
if ([string]::IsNullOrWhiteSpace($TenantId)) {
    Write-Error "Set `$env:KASHI_TENANT to your tenant id (e.g. 4)."
    exit 1
}

# ── Endpoints to exercise ───────────────────────────────────────────────────
$endpoints = [System.Collections.Generic.List[string]]::new()
@(
    '/v1/assessments'
    '/v1/workflows/my-tasks'
    '/v1/action-items/my'
    '/v1/action-items/my/count'
    '/v1/notifications'
    '/v1/ui-config/navigation'
) | ForEach-Object { $endpoints.Add($_) }

if ($AssessmentId) {
    @(
        "/v1/assessments/$AssessmentId"
        "/v1/assessments/$AssessmentId/review"
        "/v1/assessments/$AssessmentId/sections/status"
        "/v1/assessments/$AssessmentId/my-sections"
        "/v1/assessments/$AssessmentId/my-questions"
    ) | ForEach-Object { $endpoints.Add($_) }
}
if ($VendorId)   { $endpoints.Add("/v1/vendors/$VendorId/assessments") }
if ($InstanceId) {
    $endpoints.Add("/v1/workflows/instances/$InstanceId/status")
    $endpoints.Add("/v1/workflow-instances/$InstanceId/progress")
}

$headers = @{
    'Authorization' = "Bearer $Token"
    'X-Tenant-ID'   = $TenantId
}

$results = [System.Collections.Generic.List[psobject]]::new()

Write-Host ''
Write-Host ('{0,-56} {1,8} {2,10}' -f 'ENDPOINT', 'HTTP', 'ms')
Write-Host ('-' * 78)

foreach ($ep in $endpoints) {
    $url  = "$BaseUrl$ep"
    $sw   = [System.Diagnostics.Stopwatch]::StartNew()
    $code = 0
    $bytes = 0

    try {
        # -UseBasicParsing avoids the IE engine on older hosts.
        # Body is fully downloaded, so the timing includes transfer — which is the
        # point: a 4MB assessment payload is part of why the page feels slow.
        $resp  = Invoke-WebRequest -Uri $url -Headers $headers -Method Get `
                                   -UseBasicParsing -TimeoutSec 120 -ErrorAction Stop
        $code  = [int]$resp.StatusCode
        $bytes = $resp.RawContentLength
        if ($bytes -le 0 -and $resp.Content) { $bytes = $resp.Content.Length }
    }
    catch [System.Net.WebException], [Microsoft.PowerShell.Commands.HttpResponseException] {
        if ($_.Exception.Response) { $code = [int]$_.Exception.Response.StatusCode }
        else { $code = -1 }
    }
    catch {
        $code = -1
    }
    finally {
        $sw.Stop()
    }

    $ms = [math]::Round($sw.Elapsed.TotalMilliseconds)
    Write-Host ('{0,-56} {1,8} {2,10}' -f $ep, $code, $ms)

    $results.Add([pscustomobject]@{
        Endpoint = $ep
        Http     = $code
        Ms       = $ms
        Kb       = [math]::Round($bytes / 1024, 1)
    })
}

Write-Host ''
Write-Host '-- Slowest first ------------------------------------------------------------'

$sorted = $results | Sort-Object -Property Ms -Descending
foreach ($r in $sorted) {
    $flag = if ($r.Ms -gt $SlowThresholdMs) { "  <-- over $([math]::Round($SlowThresholdMs/1000,1))s target" } else { '' }
    $line = '{0,8} ms  {1,8} KB  {2,-46}{3}' -f $r.Ms, $r.Kb, $r.Endpoint, $flag
    if ($r.Ms -gt $SlowThresholdMs) { Write-Host $line -ForegroundColor Yellow }
    elseif ($r.Http -lt 0 -or $r.Http -ge 400) { Write-Host $line -ForegroundColor Red }
    else { Write-Host $line }
}

if ($CsvOut) {
    $sorted | Export-Csv -Path $CsvOut -NoTypeInformation -Encoding UTF8
    Write-Host ''
    Write-Host "Written: $CsvOut"
}

Write-Host ''
Write-Host 'Now pull the server-side view for query counts:' -ForegroundColor Cyan
Write-Host "  .\scripts\perf-report.ps1" -ForegroundColor Cyan