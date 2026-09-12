# Drives fail-open.js and kills Redis underneath it.
#
# The kill has to happen while load is in flight: an outage discovered between
# runs proves nothing about what a request in progress does. Timings here must
# match KILL_AT / RESTORE_AT in fail-open.js.

$ErrorActionPreference = "Stop"

$KillAt = 20
$RestoreAt = 40

Write-Host "starting load; redis dies at t+${KillAt}s, returns at t+${RestoreAt}s"

$k6 = Start-Job -ScriptBlock {
    docker compose --profile load run --rm k6 run fail-open.js
}

Start-Sleep -Seconds $KillAt
Write-Host "t+${KillAt}s: stopping redis"
docker compose stop redis | Out-Null

Start-Sleep -Seconds ($RestoreAt - $KillAt)
Write-Host "t+${RestoreAt}s: starting redis"
docker compose start redis | Out-Null

Wait-Job $k6 | Out-Null
Receive-Job $k6
Remove-Job $k6

Write-Host ""
Write-Host "summary written to k6/fail-open-summary.txt"
