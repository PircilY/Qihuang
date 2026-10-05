#Requires -Version 7.0
param([ValidateRange(1024, 65535)][int]$Port = 8082)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$temporary = Join-Path $root '.tmp'
New-Item -ItemType Directory -Path $temporary -Force | Out-Null
$store = Join-Path $temporary ("import-smoke-" + [guid]::NewGuid().ToString('N') + '.json')
$service = $null
try {
    $service = (& "$PSScriptRoot/start-local.ps1" -Port $Port -StorePath $store) | ConvertFrom-Json
    $source = Get-Content -LiteralPath (Join-Path $root 'data/sources.json') -Raw |
        ConvertFrom-Json | Where-Object id -eq 'NCCIH-LICORICE'
    $payload = @{
        title = $source.title
        sourceType = $source.sourceType
        publisher = $source.publisher
        publicationYear = $source.publicationYear
        sourceUrl = $source.sourceUrl
        licenseNote = $source.licenseNote
        disease = $source.excerpts[0].disease
        keywords = $source.excerpts[0].keywords
        content = $source.excerpts[0].content
    } | ConvertTo-Json -Depth 5
    $uri = "$($service.url)/api/v1/knowledge/import"
    $response = Invoke-RestMethod -Uri $uri -Method Post -ContentType 'application/json; charset=utf-8' `
        -Body ([Text.Encoding]::UTF8.GetBytes($payload)) -NoProxy
    if ($response.data.status -ne 'LOCAL_READY') { throw 'Import failed' }
    $documentId = $response.data.document.id
    if (-not (Test-Path -LiteralPath $store)) { throw 'Snapshot was not saved' }
    $duplicate = Invoke-WebRequest -Uri $uri -Method Post -ContentType 'application/json; charset=utf-8' `
        -Body ([Text.Encoding]::UTF8.GetBytes($payload)) -NoProxy -SkipHttpErrorCheck
    if ($duplicate.StatusCode -ne 409) { throw 'Duplicate was not rejected' }
    Stop-Process -Id $service.backendPid
    Wait-Process -Id $service.backendPid -ErrorAction SilentlyContinue
    $service = $null
    $service = (& "$PSScriptRoot/start-local.ps1" -Port $Port -StorePath $store) | ConvertFrom-Json
    $restored = Invoke-RestMethod -Uri "$($service.url)/api/v1/knowledge/documents/$documentId" -NoProxy
    if ($restored.data.checksum -ne $response.data.document.checksum) { throw 'Restored checksum mismatch' }
    $chunks = Invoke-RestMethod -Uri "$($service.url)/api/v1/knowledge/chunks" -NoProxy
    $imported = @($chunks.data | Where-Object documentId -eq $documentId)
    if ($imported.Count -ne 1 -or $imported[0].content -ne $source.excerpts[0].content) {
        throw 'Restored text mismatch'
    }
    Write-Output 'PASS: import, duplicate rejection, snapshot and restart restoration'
} finally {
    if ($service) { Stop-Process -Id $service.backendPid -ErrorAction SilentlyContinue }
}
