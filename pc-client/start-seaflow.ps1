# seaflow-pc launcher.
#
# Auto-detects the local default gateway, reads the connection key from
# `connection.key`, optionally reads bypass routes/domains from `bypass.txt`,
# and runs seaflow-client.exe with full-tunnel routing.
#
# Cleans up Wintun adapter and routing on Ctrl+C / window close / crash.
# Run as Administrator (the .bat wrapper requests elevation automatically).

$ErrorActionPreference = 'Continue'
$here = $PSScriptRoot
$clientExe = "$here\seaflow-client.exe"
$logFile = "$here\seaflow.log"
$clientLog = "$here\client.log"
$keyFile = "$here\connection.key"
$bypassFile = "$here\bypass.txt"

# ─────────── Admin elevation ───────────
$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
    Write-Host "Need Administrator rights. Restarting elevated..." -ForegroundColor Yellow
    Start-Process powershell -Verb RunAs -ArgumentList "-NoExit","-ExecutionPolicy","Bypass","-File","`"$PSCommandPath`""
    exit
}

function Log($msg) {
    $ts = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
    $line = "$ts $msg"
    [System.IO.File]::AppendAllText($logFile, $line + "`r`n", (New-Object System.Text.UTF8Encoding($false)))
    Write-Host $msg
}

# ─────────── Read connection key ───────────
if (-not (Test-Path $keyFile)) {
    Log "ERROR: connection.key not found in $here"
    Log "       Create connection.key, paste your aivpn://... key inside, save."
    Read-Host "Press Enter to exit"
    exit 1
}
$connectionKey = (Get-Content -Raw $keyFile).Trim()
if (-not $connectionKey -or ($connectionKey -notmatch '^aivpn(-wrtc)?://')) {
    Log "ERROR: connection.key does not contain a valid aivpn:// or aivpn-wrtc:// key."
    Read-Host "Press Enter to exit"
    exit 1
}
Log "Loaded connection key ($($connectionKey.Length) chars)"

# ─────────── Auto-detect default gateway ───────────
$script:gateway = $null
try {
    # Pick the active default route with the lowest metric (real internet path,
    # not our own VPN tun if previous run left one). Filter out 0.0.0.0 next-hops.
    $script:gateway = (Get-NetRoute -DestinationPrefix '0.0.0.0/0' -ErrorAction SilentlyContinue |
        Where-Object { $_.NextHop -ne '0.0.0.0' -and $_.NextHop -notmatch '^10\.0\.0\.' } |
        Sort-Object -Property RouteMetric, ifMetric |
        Select-Object -First 1 -ExpandProperty NextHop)
} catch {}

if (-not $script:gateway) {
    Log "ERROR: cannot detect default gateway. Are you connected to the internet?"
    Read-Host "Press Enter to exit"
    exit 1
}
Log "Auto-detected default gateway: $script:gateway"

# ─────────── Bypass list ───────────
$script:bypassRoutes  = @()
$script:bypassDomains = @()

if (Test-Path $bypassFile) {
    foreach ($raw in Get-Content $bypassFile) {
        $line = $raw.Trim()
        if (-not $line) { continue }
        if ($line.StartsWith('#')) { continue }
        if ($line -match '^(\d{1,3}\.){3}\d{1,3}$') {
            $script:bypassRoutes += $line
        } elseif ($line -match '^[A-Za-z0-9\.\-]+\.[A-Za-z]+$') {
            $script:bypassDomains += $line
        } else {
            Log "WARN: bypass.txt: unrecognized entry '$line'"
        }
    }
    Log "Loaded $($script:bypassRoutes.Count) IP(s) and $($script:bypassDomains.Count) domain(s) from bypass.txt"
}

$script:resolvedBypassIps = @()

# ─────────── Helpers ───────────
function Move-ClientLog {
    foreach ($suffix in '','.err') {
        $src = "$clientLog$suffix"
        if (Test-Path -LiteralPath $src) {
            $dst = "$clientLog$suffix.prev"
            try {
                if (Test-Path -LiteralPath $dst) { Remove-Item -LiteralPath $dst -Force -ErrorAction SilentlyContinue }
                Move-Item -LiteralPath $src -Destination $dst -Force -ErrorAction SilentlyContinue
            } catch {}
        }
    }
}

function Remove-StuckWintun {
    $devs = pnputil /enum-devices /class net 2>&1 |
            Select-String -Pattern 'SWD\\Wintun\\\{[0-9a-fA-F-]+\}' -AllMatches |
            ForEach-Object { $_.Matches.Value } |
            Select-Object -Unique
    foreach ($d in $devs) {
        Log "Removing Wintun adapter: $d"
        pnputil /remove-device $d 2>&1 | Out-Null
    }
    if ($devs.Count -gt 0) { Start-Sleep -Milliseconds 800 }
}

function Stop-SeaflowProcesses {
    Get-Process -Name 'seaflow-client','aivpn-client','aivpn-client-new' -ErrorAction SilentlyContinue | ForEach-Object {
        Log "Killing $($_.ProcessName) (PID $($_.Id))"
        $_ | Stop-Process -Force
    }
}

function Resolve-BypassDomains {
    $ips = @()
    foreach ($domain in $bypassDomains) {
        try {
            $records = Resolve-DnsName -Name $domain -Type A -ErrorAction Stop -DnsOnly 2>$null
            foreach ($r in $records) {
                if ($r.IPAddress -and $r.IPAddress -notmatch '^0\.' -and $r.IPAddress -notmatch '^127\.') {
                    $ips += $r.IPAddress
                }
            }
        } catch {
            Log "WARN: failed to resolve $domain -- $($_.Exception.Message)"
        }
    }
    $ips = $ips | Select-Object -Unique
    if ($bypassDomains.Count -gt 0) {
        Log "Resolved $($bypassDomains.Count) domain(s) to $($ips.Count) IP(s)"
    }
    return $ips
}

function Remove-VpnRoutes {
    route delete 0.0.0.0 mask 128.0.0.0 2>$null | Out-Null
    route delete 128.0.0.0 mask 128.0.0.0 2>$null | Out-Null
    foreach ($ip in $bypassRoutes) { route delete $ip 2>$null | Out-Null }
    foreach ($ip in $script:resolvedBypassIps) { route delete $ip 2>$null | Out-Null }
}

function Add-BypassRoutes {
    foreach ($ip in $bypassRoutes) {
        route add $ip mask 255.255.255.255 $gateway metric 5 2>$null | Out-Null
    }
    $script:resolvedBypassIps = Resolve-BypassDomains
    foreach ($ip in $script:resolvedBypassIps) {
        route add $ip mask 255.255.255.255 $gateway metric 5 2>$null | Out-Null
    }
    if (($bypassRoutes.Count + $script:resolvedBypassIps.Count) -gt 0) {
        Log "Added bypass routes for $($bypassRoutes.Count) static IP(s) and $($script:resolvedBypassIps.Count) resolved IP(s)"
    }
}

function Get-WintunInterface {
    for ($i = 0; $i -lt 30; $i++) {
        $line = netsh interface ipv4 show interfaces 2>$null | Select-String 'wintun' | Select-Object -First 1
        if ($line) {
            $idx = ($line.ToString() -split '\s+' | Where-Object { $_ -ne '' })[0]
            return $idx
        }
        Start-Sleep -Seconds 1
    }
    return $null
}

function Update-Routes($ifIndex) {
    Log "Routing all traffic through wintun (IF=$ifIndex)"
    route delete 0.0.0.0 mask 128.0.0.0 2>$null | Out-Null
    route delete 128.0.0.0 mask 128.0.0.0 2>$null | Out-Null
    route add 0.0.0.0 mask 128.0.0.0 10.0.0.1 IF $ifIndex metric 5 2>$null | Out-Null
    route add 128.0.0.0 mask 128.0.0.0 10.0.0.1 IF $ifIndex metric 5 2>$null | Out-Null
}

$script:cleaned = $false
function Invoke-Cleanup {
    if ($script:cleaned) { return }
    $script:cleaned = $true
    Log "--- Cleanup ---"
    Stop-SeaflowProcesses
    Start-Sleep -Milliseconds 500
    Remove-VpnRoutes
    Remove-StuckWintun
    Log "Disconnected."
}

[Console]::TreatControlCAsInput = $false
Register-EngineEvent PowerShell.Exiting -Action { Invoke-Cleanup } | Out-Null

# ─────────── Main ───────────
try {
    Log "=== seaflow-pc start ==="
    Stop-SeaflowProcesses
    Remove-VpnRoutes
    Remove-StuckWintun
    Add-BypassRoutes

    Move-ClientLog
    Log "Launching $clientExe"
    $proc = Start-Process -FilePath $clientExe `
        -ArgumentList '-k', "`"$connectionKey`"", '--full-tunnel' `
        -RedirectStandardOutput $clientLog `
        -RedirectStandardError "$clientLog.err" `
        -PassThru -NoNewWindow

    Log "Client started (PID $($proc.Id))"

    $ifIdx = Get-WintunInterface
    if (-not $ifIdx) {
        Log "ERROR: wintun interface not found within 30s"
        throw "Wintun setup failed"
    }
    Log "Wintun IF=$ifIdx"
    Start-Sleep -Seconds 3
    Update-Routes $ifIdx

    $ip = (curl.exe -s --max-time 15 ifconfig.me) 2>$null
    if ($ip) { Log "External IP via VPN: $ip" }

    Log "VPN is running. Press Ctrl+C to disconnect."
    while (-not $proc.HasExited) {
        Start-Sleep -Seconds 1
    }
    Log "Client exited with code $($proc.ExitCode)"
}
finally {
    Invoke-Cleanup
}
