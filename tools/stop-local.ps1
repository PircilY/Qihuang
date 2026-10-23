#Requires -Version 7.0
param([ValidateRange(1024,65535)][int]$Port=8080)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$record=Join-Path $root "runtime/services-$Port.json"
if(-not (Test-Path -LiteralPath $record)) { Write-Output 'No managed service record'; return }
$services=Get-Content -LiteralPath $record -Raw | ConvertFrom-Json
foreach($entry in @(@{Id=$services.backendPid; Started=$services.backendStartedAt; Name='java'},
                    @{Id=$services.ragPid; Started=$services.ragStartedAt; Name='python'})) {
    if(-not $entry.Id) { continue }
    $process=Get-Process -Id $entry.Id -ErrorAction SilentlyContinue
    if(-not $process) { continue }
    if($process.ProcessName -notlike "$($entry.Name)*" -or -not $entry.Started -or
       $process.StartTime.ToUniversalTime().Ticks -ne ([datetime]$entry.Started).ToUniversalTime().Ticks) {
        throw "PID identity changed; refusing to stop process $($entry.Id)"
    }
    Stop-Process -Id $process.Id -ErrorAction Stop
    Write-Output "Stopped $($entry.Name) PID $($entry.Id)"
}
Remove-Item -LiteralPath $record -Force
