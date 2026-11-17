# Sends a correctly signed fake GitHub push to a local DevPulse server.
# Usage: .\scripts\send-push.ps1 -Author alice -Branch feature-x -Files src/app.js,README.md
param(
    [string]$Author = "alice",
    [string]$Branch = "main",
    [string[]]$Files = @("README.md"),
    [string]$Repo = "acme/app",
    [string]$Url = "http://localhost:8080/webhook"
)

if (-not $env:GITHUB_WEBHOOK_SECRET) { throw "Set GITHUB_WEBHOOK_SECRET first." }

$payload = @{
    ref        = "refs/heads/$Branch"
    repository = @{ full_name = $Repo }
    sender     = @{ login = $Author }
    commits    = @(@{ added = @(); modified = @($Files); removed = @() })
} | ConvertTo-Json -Depth 5 -Compress

$bytes = [Text.Encoding]::UTF8.GetBytes($payload)
$hmac = [Security.Cryptography.HMACSHA256]::new([Text.Encoding]::UTF8.GetBytes($env:GITHUB_WEBHOOK_SECRET))
$signature = "sha256=" + ([BitConverter]::ToString($hmac.ComputeHash($bytes)) -replace '-', '').ToLower()

$response = Invoke-WebRequest -Method Post -Uri $Url -ContentType "application/json" -Body $bytes -Headers @{
    "X-GitHub-Event"      = "push"
    "X-GitHub-Delivery"   = [guid]::NewGuid().ToString()
    "X-Hub-Signature-256" = $signature
}
"$($response.StatusCode) $Author -> $Branch ($($Files -join ', '))"