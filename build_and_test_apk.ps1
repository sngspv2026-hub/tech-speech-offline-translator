#Requires -Version 5.1
<#
.SYNOPSIS
  Полный цикл тестирования и сборки APK (standard + airgapped) для TechSpeech Offline Translator.
  Автоматически проверяет/настраивает JDK 17 и Android SDK (local.properties),
  прогоняет Python и JUnit тесты и собирает оба варианта APK в папку dist/.
#>

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $ProjectRoot

Write-Host "=== [1/4] Проверка структуры проекта и изоляции разрешений INTERNET ===" -ForegroundColor Cyan
$py = if (Test-Path "C:\Projekt\Camera\.venv\Scripts\python.exe") {
    "C:\Projekt\Camera\.venv\Scripts\python.exe"
} else {
    "python"
}
& $py "$ProjectRoot\scripts\verify_project_integrity.py"
& $py "$ProjectRoot\scripts\evaluate_technical_test_suite.py"

Write-Host "=== [2/4] Сборка офлайн языковых пакетов (.tpk) в dist/ ===" -ForegroundColor Cyan
& $py "$ProjectRoot\scripts\convert_and_package_models.py" --pack-id org.offline.translate.pack.ru_en_core --version 1.2.0 --source-lang ru --target-lang en --routing DIRECT --output "$ProjectRoot\dist\pack_ru_en_v1.2.0.tpk" --dry-run
& $py "$ProjectRoot\scripts\convert_and_package_models.py" --pack-id org.offline.translate.pack.tr_en_pivot_ru --version 1.2.0 --source-lang ru --target-lang tr --routing PIVOT --pivot-lang en --output "$ProjectRoot\dist\pack_tr_en_pivot_ru_v1.2.0.tpk" --dry-run
& $py "$ProjectRoot\scripts\convert_and_package_models.py" --pack-id org.offline.translate.pack.ru_tr_direct --version 1.0.0 --source-lang ru --target-lang tr --routing DIRECT --output "$ProjectRoot\dist\pack_ru_tr_direct_v1.0.0.tpk" --dry-run

Write-Host "=== [3/4] Проверка окружения JDK 17 и Android SDK ===" -ForegroundColor Cyan
if (-not $env:JAVA_HOME) {
    $candidates = @(
        "C:\Program Files\Android\Android Studio\jbr",
        "C:\Android\jdk17",
        "$env:LOCALAPPDATA\Programs\Android Studio\jbr"
    )
    foreach ($c in $candidates) {
        if (Test-Path "$c\bin\java.exe") {
            $env:JAVA_HOME = $c
            $env:PATH = "$c\bin;$env:PATH"
            break
        }
    }
}

if (-not $env:ANDROID_HOME) {
    $sdkCandidates = @(
        "$env:LOCALAPPDATA\Android\Sdk",
        "C:\Android\sdk"
    )
    foreach ($s in $sdkCandidates) {
        if (Test-Path $s) {
            $env:ANDROID_HOME = $s
            break
        }
    }
}

if ($env:ANDROID_HOME -and -not (Test-Path "$ProjectRoot\local.properties")) {
    $escapedSdk = $env:ANDROID_HOME.Replace("\", "\\").Replace(":", "\:")
    Set-Content -Path "$ProjectRoot\local.properties" -Value "sdk.dir=$escapedSdk" -Encoding UTF8
    Write-Host "Создан файл local.properties -> $($env:ANDROID_HOME)" -ForegroundColor Green
}

if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    Write-Warning "JDK 17 не найден в PATH. Установите Android Studio (JBR 17) или выполните: winget install Microsoft.OpenJDK.17"
    exit 1
}

Write-Host "=== [4/4] Запуск Unit-тестов всех 18 модулей и сборка APK (standard + airgapped) ===" -ForegroundColor Cyan
& "$ProjectRoot\gradlew.bat" testStandardDebugUnitTest :app:assembleStandardDebug :app:assembleAirgappedDebug

$stdApk = "$ProjectRoot\app\build\outputs\apk\standard\debug\app-standard-debug.apk"
$airApk = "$ProjectRoot\app\build\outputs\apk\airgapped\debug\app-airgapped-debug.apk"

if (Test-Path $stdApk) {
    Copy-Item $stdApk "$ProjectRoot\dist\TechSpeechTranslator-standard-debug.apk" -Force
    Write-Host "[OK] Standard APK: $ProjectRoot\dist\TechSpeechTranslator-standard-debug.apk" -ForegroundColor Green
}
if (Test-Path $airApk) {
    Copy-Item $airApk "$ProjectRoot\dist\TechSpeechTranslator-airgapped-debug.apk" -Force
    Write-Host "[OK] Air-Gapped APK (без INTERNET): $ProjectRoot\dist\TechSpeechTranslator-airgapped-debug.apk" -ForegroundColor Green
}
