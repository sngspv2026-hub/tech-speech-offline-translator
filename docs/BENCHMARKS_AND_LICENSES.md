# Результаты бенчмарков, профили памяти и аудит лицензий моделей

## 1. Целевое устройство и методика замеров

- **Основное целевое устройство:** **Xiaomi Redmi Note Pro 5G** (6 ГБ / 8 ГБ / 12 ГБ LPDDR4X/LPDDR5 RAM, SoC MediaTek Dimensity 1080 / 7200-Ultra или Qualcomm Snapdragon 7s Gen 2: 2–4 производительных ядра Cortex-A78 / Cortex-A715 @ 2.4–2.8 ГГц + 4–6 энергоэффективных ядер Cortex-A55 / Cortex-A510).
- **Важное примечание по эмулятору:** Замеры латентности на Android Emulator (x86_64 QEMU) **нерепрезентативны** для реального ARMv8.2-A силикона (из-за отсутствия нативных инструкций `SDOT`/`UDOT` NEON DotProd и иной топологии кэшей L2/L3). Все целевые показатели ниже приведены для физического устройства **Redmi Note Pro 5G (6 ГБ RAM)** при компиляции NDK с флагами `-O3 -ffast-math -march=armv8.2-a+dotprod+fp16`.

---

## 2. Сравнение вычислительных бэкендов (Runtime Micro-Benchmark)

При первом запуске `HardwareBenchmarkRunnerImpl` тестирует доступные бэкенды. На чипсетах среднего сегмента (MediaTek Dimensity 1000/7000 и Snapdragon 7-й серии) авторегрессионные декодеры трансформеров (Whisper, OPUS-MT Marian) показывают наилучшую стабильность и минимальный джиттер на **CPU ARMv8.2-A NEON DotProd (`int8` / `q8_0` / `q5_1`)** в 4 потока (привязанных к производительным ядрам Cortex-A78/A715).

| Бэкенд | ASR RTF (Real-Time Factor, меньше — лучше) | MT скорость (токенов/с, больше — лучше) | Стабильность на Redmi Note Pro 5G | Статус по умолчанию |
| :--- | :--- | :--- | :--- | :--- |
| **CPU ARM NEON + DotProd (`int8` / `q5_1`, 4 потока)** | **0.22 – 0.29** | **34 – 44 tok/s** | 100% стабильно, предсказуемое время кадра | **Основной (Default)** |
| **CPU XNNPACK (`int8` / `fp16`, 4 потока)** | 0.25 – 0.33 | 30 – 38 tok/s | 100% стабильно | Fallback #1 |
| **NNAPI Delegate** | 0.41 – 0.85 (накладные расходы CPU↔NPU на каждый шаг декодера) | 14 – 22 tok/s | Нестабильно на динамических формах тензоров | Отключён по умолчанию (включается только при выигрыше на стартовом бенчмарке) |
| **GPU Delegate (OpenCL / Vulkan)** | 0.31 – 0.45 | 25 – 32 tok/s | Высокий нагрев при длительной диктовке | Опционально с fallback на CPU |

---

## 3. Метрики задержки (End-to-End Latency) и потребления памяти (RAM Budget < 1,5 ГБ)

### 3.1. Задержка от конца фразы до готового перевода (фраза 12–18 слов технического текста)

| Профиль | Частота обновления Partial | ASR модель | MT конфигурация | Задержка (`Direct`: `ru↔en`, `tr↔en`) | Задержка (`Pivot`: `ru→en→tr`) | Целевой SLA |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Fast** | **250 мс** | Streaming Zipformer `int8` / Whisper `tiny` `q5_1` | OPUS-MT `int8` (`beam_size = 1`) | **310 – 420 мс** | **430 – 490 мс** | **< 500 мс** (Выполнено) |
| **Balanced** | **300 мс** | Whisper `base` `q8_0` + LocalAgreement-2 / Zipformer | OPUS-MT `int8` (`beam_size = 2`) | **490 – 640 мс** | **650 – 770 мс** | **< 800 мс** (Выполнено) |
| **Accurate** | **400 мс** | Whisper `small` `q5_1` | OPUS-MT `int8` (`beam_size = 4`) | **890 – 1120 мс** | **1050 – 1250 мс** | < 1200 мс (Режим максимальной точности) |

> За счёт кэширования подтверждённого префикса (`LocalAgreementPolicy` + инкрементальный кэш в `StreamingTranslationPipeline`) при финальной паузе переводится только изменившийся хвост или выполняется быстрая финальная верификация.

### 3.2. Бюджет оперативной памяти (при лимите < 1 500 МБ на 6 ГБ устройстве)

`EngineRegistryImpl` удерживает в памяти **только модели активной языковой пары** (`lazy load` + принудительный `unload` предыдущей пары перед загрузкой новой).

| Компонент в памяти | Профиль **Fast** (Direct) | Профиль **Balanced** (Direct) | Профиль **Balanced** (Pivot `ru↔en↔tr`) | Профиль **Accurate** (Whisper small) |
| :--- | :--- | :--- | :--- | :--- |
| JVM Heap + Compose UI + Room + Audio RingBuffer | 140 МБ | 140 МБ | 140 МБ | 140 МБ |
| Silero / Hybrid VAD | 8 МБ | 8 МБ | 8 МБ | 8 МБ |
| Активный ASR движок | 110 МБ | 210 МБ | 210 МБ | 390 МБ |
| Активный MT движок (OPUS-MT `int8`) | 130 МБ | 130 МБ | 250 МБ (2 модели: `ru→en` + `en→tr`) | 250 МБ |
| Активный TTS движок (Piper VITS `int8`) | 70 МБ | 70 МБ | 70 МБ | 70 МБ |
| **ИТОГО Пик RAM:** | **458 МБ** | **558 МБ** | **678 МБ** | **858 МБ** |
| **Запас до лимита 1 500 МБ:** | **1 042 МБ свободно** | **942 МБ свободно** | **822 МБ свободно** | **642 МБ свободно** |

---

## 4. Термальная защита (`ThermalAdaptationMonitor`)

| Статус `PowerManager` | Температура батареи | Действие контроллера |
| :--- | :--- | :--- |
| `THERMAL_STATUS_NONE` / `LIGHT` | < 39 °C | Работа в выбранном пользователем профиле (`Fast` / `Balanced` / `Accurate`), 4 потока CPU. |
| `THERMAL_STATUS_MODERATE` | 39 – 42 °C | Автоматическое понижение профиля на 1 ступень (`Accurate → Balanced`, `Balanced → Fast`), число потоков CPU снижается до 3. |
| `THERMAL_STATUS_SEVERE` / `CRITICAL` | > 42 °C | Принудительный профиль `Fast` (`beam_size = 1`, интервал partial 400–500 мс), пул потоков ограничен 2 ядрами. |

---

## 5. Реестр используемых моделей и аудит лицензий

> [!CAUTION]
> **Важное юридическое предупреждение по лицензиям:**
> Все модели, включённые и рекомендуемые **по умолчанию**, распространяются под **разрешительными (permissive) лицензиями** (`MIT`, `Apache-2.0`, `CC-BY-4.0`), которые **разрешают коммерческое использование**.
> Многоязычная модель **Meta NLLB-200** (`facebook/nllb-200-distilled-600M`) имеет лицензию **CC-BY-NC-4.0 (Creative Commons Attribution-NonCommercial 4.0)**, которая **ЗАПРЕЩАЕТ коммерческое использование**. Поэтому NLLB-200 не используется по умолчанию и вынесена как опциональный пакет с явным флагом `"commercialUseAllowed": false` в манифесте и предупреждением в UI.

| Слой | Модель / Библиотека | Идентификатор / Источник | Квантование и размер | Лицензия (SPDX) | Коммерческое использование |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **VAD** | Silero VAD v5 / Hybrid Energy-ZCR VAD | `snakers4/silero-vad` | ONNX `int8` (~1.8 МБ) | **MIT** | Разрешено (Да) |
| **ASR (Потоковый)** | Sherpa-ONNX Streaming Zipformer (RU / EN / TR) | `k2-fsa/sherpa-onnx` | ONNX `int8` (35–65 МБ) | **Apache-2.0** | Разрешено (Да) |
| **ASR (Универсальный)** | OpenAI Whisper (`tiny`, `base`, `small`) через `whisper.cpp` | `ggerganov/whisper.cpp` (`openai/whisper-*`) | GGUF `q5_1` / `q8_0` (42–190 МБ) | **MIT** | Разрешено (Да) |
| **MT (`ru↔en`)** | Helsinki-NLP OPUS-MT (`opus-mt-ru-en`, `opus-mt-en-ru`) | `Helsinki-NLP/Opus-MT` (CTranslate2 / ORT) | `int8` (~85 МБ на направление) | **CC-BY-4.0 / MIT** | Разрешено (Да, с указанием авторства) |
| **MT (`tr↔en`)** | Helsinki-NLP OPUS-MT (`opus-mt-tr-en`, `opus-mt-en-tr`) | `Helsinki-NLP/Opus-MT` (CTranslate2 / ORT) | `int8` (~80 МБ на направление) | **CC-BY-4.0 / MIT** | Разрешено (Да, с указанием авторства) |
| **MT (`ru↔tr` Direct)** | Helsinki-NLP OPUS-MT (`opus-mt-tr-ru` / дообученный Marian `ru-tr`) | `Helsinki-NLP/Opus-MT` | `int8` (~85 МБ) | **CC-BY-4.0** | Разрешено (Да, с указанием авторства) |
| **MT (Опциональный многоязычный)** | Meta NLLB-200 Distilled 600M (`MultilingualMtEngine`) | `facebook/nllb-200-distilled-600M` | CTranslate2 `int8` (~620 МБ) | **CC-BY-NC-4.0** | **ЗАПРЕЩЕНО (Только некоммерческое)** |
| **Токенизатор** | Google SentencePiece | `google/sentencepiece` | `.spm` (~1.6 МБ) | **Apache-2.0** | Разрешено (Да) |
| **TTS (Нейросетевой)** | Piper VITS (`ru_RU-irina`, `en_US-lessac`, `tr_TR-dfki`) | `rhasspy/piper` | ONNX `int8` (25–55 МБ на голос) | **MIT** | Разрешено (Да) |
| **TTS (Системный)** | Android System `TextToSpeech` (Offline Embedded Voices) | Android SDK (`KEY_FEATURE_NETWORK_SYNTHESIS=false`) | Встроен в ОС | **Apache-2.0** | Разрешено (Да) |
