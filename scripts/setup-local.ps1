$ErrorActionPreference = 'Stop'
Push-Location (Split-Path -Parent $PSScriptRoot)
try {
    docker compose up -d
    if ($LASTEXITCODE -ne 0) { throw 'Docker Compose failed to start.' }
    $brokerReady = $false
    for ($attempt = 0; $attempt -lt 20; $attempt++) {
        $ErrorActionPreference = 'Continue'
        $cluster = docker compose exec -T broker sh mqadmin clusterList -n namesrv:9876 2>&1
        $ErrorActionPreference = 'Stop'
        if ($LASTEXITCODE -eq 0 -and ($cluster -match 'notify-broker')) { $brokerReady = $true; break }
        Start-Sleep -Seconds 2
    }
    if (-not $brokerReady) { throw 'Broker is not ready. Check: docker compose logs broker' }
    foreach ($topic in @('notify-business-a', 'notify-business-b')) {
        docker compose exec -T broker sh mqadmin updateTopic -n namesrv:9876 -b 127.0.0.1:10911 -t $topic -r 1 -w 1
        if ($LASTEXITCODE -ne 0) { throw "Topic creation failed: $topic" }
    }
    Write-Output 'Infrastructure and both business topics are ready.'
} finally { Pop-Location }
