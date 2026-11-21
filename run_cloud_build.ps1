#Requires -Version 5.1
$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$EnvFile = Join-Path $ProjectRoot ".env"

$hasToken = $false
if (Test-Path $EnvFile) {
    if (Select-String -Path $EnvFile -Pattern "^GITHUB_TOKEN=.+" -Quiet) {
        $hasToken = $true
    }
}

if (-not $hasToken) {
    Write-Host "Для облачной сборки APK через GitHub Actions нужен Personal Access Token (с правами repo и workflow)." -ForegroundColor Cyan
    Write-Host "Создать токен можно здесь: https://github.com/settings/tokens/new" -ForegroundColor Yellow
    $sec = Read-Host "Введите GITHUB_TOKEN (символы скрыты)" -AsSecureString
    $val = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($sec))
    if ([string]::IsNullOrWhiteSpace($val)) {
        Write-Error "Токен не введён."
        exit 1
    }
    Set-Content -Path $EnvFile -Value "GITHUB_TOKEN=$($val.Trim())" -Encoding UTF8
    Write-Host "Токен сохранён в $EnvFile" -ForegroundColor Green
}

$py = if (Test-Path "C:\Projekt\Camera\.venv\Scripts\python.exe") {
    "C:\Projekt\Camera\.venv\Scripts\python.exe"
} else {
    "python"
}

& $py (Join-Path $ProjectRoot "scripts\cloud_build_github_actions.py")
