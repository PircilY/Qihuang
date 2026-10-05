#Requires -Version 7.0
param(
    [ValidateRange(1024, 65535)][int]$Port = 8080,
    [ValidateRange(1024, 65535)][int]$RagPort = 8000,
    [switch]$WithPython,
    [string]$StorePath
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$jar = Join-Path $root 'backend/target/tcm-cvd-rag-prototype-0.1.0-SNAPSHOT.jar'
$runtime = Join-Path $root 'runtime'
if (-not (Test-Path -LiteralPath $jar)) { throw 'Build backend with mvn package first.' }
if (-not $StorePath) { $StorePath = Join-Path $runtime 'knowledge-imports.json' }
if ($WithPython -and $Port -eq $RagPort) { throw 'Java and Python need different ports.' }

function Assert-FreePort([int]$Value) {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $Value)
    try { $listener.Start() }
    catch { throw "Port $Value is unavailable. Choose another port." }
    finally { $listener.Stop() }
}

function Wait-Healthy([string]$Url, [System.Diagnostics.Process]$Process) {
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        if ($Process.HasExited) { throw "Service exited. Check logs in $runtime" }
        try {
            $response = Invoke-RestMethod -Uri $Url -TimeoutSec 2 -NoProxy
            if ($response.status -eq 'UP') { return }
        } catch { Start-Sleep -Seconds 1 }
    }
    throw "Service did not become healthy: $Url"
}

Assert-FreePort $Port
if ($WithPython) { Assert-FreePort $RagPort }
$java = (Get-Command java -ErrorAction Stop).Source
New-Item -ItemType Directory -Path $runtime -Force | Out-Null
$started = @()
$saved = @{}
$names = 'SERVER_ADDRESS','SERVER_PORT','KNOWLEDGE_STORE_PATH','RAG_ENGINE_MODE','RAG_SERVICE_URL','PYTHONPATH'
foreach ($name in $names) { $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
try {
    if ($WithPython) {
        $python = (Get-Command python -ErrorAction Stop).Source
        $deps = Join-Path $root '.rag-deps'
        if (Test-Path -LiteralPath $deps) { $env:PYTHONPATH = $deps }
        $rag = Start-Process -FilePath $python -ArgumentList @(
            '-m', 'uvicorn', 'app.main:app', '--app-dir', 'rag-service',
            '--host', '127.0.0.1', '--port', "$RagPort"
        ) -WorkingDirectory $root -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput (Join-Path $runtime "rag-$RagPort.log") `
            -RedirectStandardError (Join-Path $runtime "rag-$RagPort.err.log")
        $started += $rag
        Wait-Healthy "http://127.0.0.1:$RagPort/health" $rag
    }
    $env:SERVER_ADDRESS = '127.0.0.1'
    $env:SERVER_PORT = "$Port"
    $env:KNOWLEDGE_STORE_PATH = $StorePath
    $env:RAG_ENGINE_MODE = if ($WithPython) { 'auto' } else { 'local' }
    $env:RAG_SERVICE_URL = "http://127.0.0.1:$RagPort"
    $backend = Start-Process -FilePath $java -ArgumentList @('-jar', "`"$jar`"") `
        -WorkingDirectory $root -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $runtime "backend-$Port.log") `
        -RedirectStandardError (Join-Path $runtime "backend-$Port.err.log")
    $started += $backend
    Wait-Healthy "http://127.0.0.1:$Port/actuator/health" $backend
    $result = [ordered]@{
        url = "http://127.0.0.1:$Port"
        backendPid = $backend.Id
        ragPid = if ($WithPython) { $rag.Id } else { $null }
        storePath = $StorePath
    }
    $result | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $runtime "services-$Port.json") -Encoding utf8
    $result | ConvertTo-Json
} catch {
    foreach ($process in $started) {
        if (-not $process.HasExited) { Stop-Process -Id $process.Id }
    }
    throw
} finally {
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $saved[$name], 'Process') }
}
