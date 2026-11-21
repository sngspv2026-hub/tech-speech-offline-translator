# TechSpeech Offline Translator (Android)

Полностью офлайн Android-приложение для синхронного (потокового, near real-time) перевода технической речи с поддержкой загружаемых языковых пакетов, отраслевых глоссариев (CSV / TBX ISO 30042), защиты кода/артикулов и нормализации чисел и единиц измерения СИ.

---

## 1. Ключевые особенности архитектуры

- **100% Офлайн конвейер (ASR → Нормализация → Глоссарий → NMT → TTS):** Ни один байт аудио или текста не покидает устройство.
- **Строгая изоляция разрешений (`INTERNET`):**
  - Разрешение `android.permission.INTERNET` объявлено **только** в манифесте модуля `:engine:packmanager` исключительно для загрузки языковых пакетов по HTTPS.
  - Файл `app/src/main/res/xml/network_security_config.xml` блокирует cleartext-трафик и разрешает HTTPS только к домену репозитория пакетов (`packs.offline-tech-translate.org`).
  - Для полностью изолированных контуров поддерживается **Sideload** (установка пакетов `.tpk`/`.zip` из локального хранилища без интернета).
- **Адаптация под mid-range ARM64 (Xiaomi Redmi Note Pro 5G, 6–12 ГБ RAM):**
  - Пиковое потребление памяти ограничено **< 1,5 ГБ** (фактически ~460–850 МБ) за счёт `EngineRegistryImpl`, удерживающего в RAM только активную языковую пару.
  - Основной путь вычислений — **CPU ARMv8.2-A NEON + DotProd (`int8` / `q5_1` / `q8_0`)** с автоматическим стартовым микробенчмарком (`HardwareBenchmarkRunnerImpl`) и термальной защитой (`ThermalAdaptationMonitor`).
- **6 языковых направлений из коробки (`ru`, `en`, `tr`):**
  - Поддержка как прямых моделей (`RoutingMode.DIRECT`), так и перевода через язык-посредник (`RoutingMode.PIVOT`, например `ru ↔ en ↔ tr`), задаваемых декларативно в `manifest.json` языкового пакета.

---

## 2. Структура модулей Gradle

- `:app` — Точка входа (`MainActivity`), Compose-навигация, онбординг, `TranslationForegroundService` (`foregroundServiceType="microphone"`).
- `:core:model` — Доменные модели, `LanguagePackManifest`, профили скорости (`FAST`, `BALANCED`, `ACCURATE`), технические домены.
- `:core:common` — `SafeLogger` (без PII и без телеметрии), `CryptoVerifier` (SHA-256 и проверка криптографических подписей пакетов).
- `:core:database` — Room БД (история переводов, избранное, пользовательские и встроенные глоссарии, установленные пакеты).
- `:core:datastore` — Preferences DataStore (настройки, профиль бенчмарка, выбранный домен).
- `:core:designsystem` — Material 3 тема, компоненты с разделением `partial` и `final` текста, индикатор уровня звука RMS/VAD, поддержка TalkBack.
- `:engine:api` — Контракты (`AsrEngine`, `TranslationEngine`, `TtsEngine`, `VadEngine`, `TermAdapter`, `NumberUnitNormalizer`, `LanguagePackManager`, `EngineRegistry`) и потоковый координатор `StreamingTranslationPipeline`.
- `:engine:audio` — `AudioCaptureManager` (16 кГц PCM mono), маршрутизация Bluetooth SCO / BLE Audio, аппаратные `NoiseSuppressor` и `AutomaticGainControl`, `CircularPcmRingBuffer` (300 мс pre-roll) и `HybridVadEngine`.
- `:engine:asr` — NDK/JNI мост (`CMakeLists.txt`, `native_asr_bridge.cpp` с ARM NEON конвертацией), алгоритм `LocalAgreementPolicy` (Partial каждые ~300 мс) и `MockAsrEngine` для dev-режима.
- `:engine:mt` — NDK/JNI мост (`native_mt_bridge.cpp`), `OfflineNeuralTranslationEngine` (Direct), `PivotTranslationEngine` (Pivot), `DefaultNumberUnitNormalizer` и `DefaultGlossaryTermAdapter` (CSV/TBX).
- `:engine:tts` — `SystemOfflineTtsEngine` (Android Offline TTS) и `PiperNeuralTtsEngine` (VITS ONNX).
- `:engine:packmanager` — `LanguagePackManagerImpl`, `LanguagePackDownloadWorker` (WorkManager с докачкой HTTP Range), `EngineRegistryImpl`, `ThermalAdaptationMonitor`, `HardwareBenchmarkRunnerImpl`.
- `:feature:live`, `:feature:conversation`, `:feature:text`, `:feature:glossary`, `:feature:history`, `:feature:settings` — UI-экраны приложения.

---

## 3. Сборка и запуск тестов

### Требования
- **JDK 17**
- **Android SDK** (`compileSdk = 35`, `minSdk = 28`) и **Android NDK 26+ / CMake 3.22.1+**
- **Python 3.10+** (для скриптов конвертации моделей и автоматического прогона корпуса технических фраз)

### Команды сборки Gradle
```bash
# Сборка Debug APK
./gradlew :app:assembleDebug

# Запуск всех JVM Unit-тестов по всем модулям
./gradlew testDebugUnitTest

# Запуск инструментальных тестов и бенчмарка на подключённом Xiaomi Redmi Note Pro 5G
./gradlew :app:connectedDebugAndroidTest
```

### Запуск Python-тестов качества технического перевода и сборщика пакетов
```bash
python scripts/evaluate_technical_test_suite.py
```

---

## 4. Как подготовить, квантовать и опубликовать языковой пакет (`.tpk`)

Используйте скрипт [scripts/convert_and_package_models.py](file:///c:/Projekt/translate/scripts/convert_and_package_models.py):

```bash
# Установка зависимостей конвертера
pip install -r scripts/requirements.txt

# 1. Конвертация модели OPUS-MT из HuggingFace в CTranslate2 int8 и упаковка в .tpk:
python scripts/convert_and_package_models.py \
  --pack-id org.offline.translate.pack.ru_en_core \
  --version 1.2.0 \
  --source-lang ru \
  --target-lang en \
  --routing DIRECT \
  --hf-mt-model Helsinki-NLP/opus-mt-ru-en \
  --quantization int8 \
  --output dist/pack_ru_en_v1.2.0.tpk

# 2. Сборка пакета в тестовом режиме (dry-run без скачивания тяжелых весов):
python scripts/convert_and_package_models.py \
  --pack-id org.offline.translate.pack.ru_tr_pivot \
  --version 1.0.0 \
  --source-lang ru \
  --target-lang tr \
  --routing PIVOT \
  --pivot-lang en \
  --output dist/pack_ru_tr_pivot.tpk \
  --dry-run
```

---

## 5. Как добавить новый язык без изменения кода приложения

Архитектура полностью управляется данными из `manifest.json`:
1. Соберите `.tpk` архив для нового языка (например, немецкого `de`):
   ```bash
   python scripts/convert_and_package_models.py \
     --pack-id org.offline.translate.pack.de_en \
     --source-lang de \
     --target-lang en \
     --routing DIRECT \
     --hf-mt-model Helsinki-NLP/opus-mt-de-en \
     --output dist/pack_de_en.tpk
   ```
2. Опубликуйте пакет в JSON-каталоге сервера или скопируйте файл `pack_de_en.tpk` на смартфон и выберите установку через **Sideload**.
3. `LanguagePackManagerImpl` автоматически прочитает массив `languages` и `directions` из `manifest.json`, зарегистрирует язык `DE` в `availableLanguages`, и он мгновенно появится в выпадающем списке языков на всех экранах (`Синхрон`, `Разговор`, `Текст`).

---

## 6. Как добавить и импортировать технический глоссарий (CSV / TBX)

### Формат CSV
```csv
src_lang,tgt_lang,domain,src_term,tgt_term,case_sensitive,priority
ru,en,oil_gas_energy,"превентор","blowout preventer",false,100
ru,tr,mechanical,"клапан","vana",false,100
```

### Формат TBX (ISO 30042 XML)
```xml
<?xml version="1.0" encoding="UTF-8"?>
<martif type="TBX" xml:lang="en">
  <text><body>
    <termEntry id="term_1">
      <descrip type="subjectField">oil_gas_energy</descrip>
      <langSet xml:lang="ru"><tig><term>превентор</term></tig></langSet>
      <langSet xml:lang="en"><tig><term>blowout preventer</term></tig></langSet>
      <langSet xml:lang="tr"><tig><term>patlama önleyici</term></tig></langSet>
    </termEntry>
  </body></text>
</martif>
```
Пользовательские термины получают приоритет `100` и всегда переопределяют встроенные отраслевые глоссарии (приоритет `50`).
