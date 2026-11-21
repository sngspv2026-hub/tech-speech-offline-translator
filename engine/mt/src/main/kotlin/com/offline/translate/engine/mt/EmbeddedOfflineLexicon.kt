package com.offline.translate.engine.mt

/**
 * Embedded offline trilingual lexicon (RU <-> EN <-> TR) bundled inside the APK.
 * Provides instant offline translation for common conversational, field engineering,
 * and technical sentences even when the device has no internet and ML Kit models are not yet downloaded.
 */
object EmbeddedOfflineLexicon {

    data class TrilingualEntry(
        val ru: String,
        val en: String,
        val tr: String
    )

    private val entries = listOf(
        // Conversational & daily
        TrilingualEntry("привет", "hello", "merhaba"),
        TrilingualEntry("здравствуйте", "hello", "merhaba"),
        TrilingualEntry("доброе утро", "good morning", "günaydın"),
        TrilingualEntry("добрый день", "good afternoon", "iyi günler"),
        TrilingualEntry("добрый вечер", "good evening", "iyi akşamlar"),
        TrilingualEntry("как дела", "how are you", "nasılsın"),
        TrilingualEntry("спасибо", "thank you", "teşekkür ederim"),
        TrilingualEntry("большое спасибо", "thank you very much", "çok teşekkür ederim"),
        TrilingualEntry("пожалуйста", "please", "lütfen"),
        TrilingualEntry("да", "yes", "evet"),
        TrilingualEntry("нет", "no", "hayır"),
        TrilingualEntry("хорошо", "good", "iyi"),
        TrilingualEntry("отлично", "excellent", "harika"),
        TrilingualEntry("извините", "excuse me", "affedersiniz"),
        TrilingualEntry("я не понимаю", "I do not understand", "anlamıyorum"),
        TrilingualEntry("повторите пожалуйста", "please repeat", "lütfen tekrar edin"),
        TrilingualEntry("говорите медленнее", "speak slower", "daha yavaş konuşun"),
        TrilingualEntry("где находится", "where is", "nerede"),
        TrilingualEntry("сколько это стоит", "how much does it cost", "bu ne kadar"),
        TrilingualEntry("помогите", "help", "yardım edin"),
        TrilingualEntry("внимание", "attention", "dikkat"),
        TrilingualEntry("опасно", "danger", "tehlike"),
        TrilingualEntry("остановите", "stop", "durdurun"),
        TrilingualEntry("запустите", "start", "başlatın"),
        TrilingualEntry("проверьте", "verify", "kontrol edin"),
        TrilingualEntry("откройте", "open", "açın"),
        TrilingualEntry("закройте", "close", "kapatın"),

        // Technical & Engineering phrases
        TrilingualEntry("рабочее давление", "operating pressure", "çalışma basıncı"),
        TrilingualEntry("давление", "pressure", "basınç"),
        TrilingualEntry("температура", "temperature", "sıcaklık"),
        TrilingualEntry("при температуре", "at temperature", "sıcaklıkta"),
        TrilingualEntry("при частоте", "at frequency", "frekansında"),
        TrilingualEntry("частота", "frequency", "frekans"),
        TrilingualEntry("напряжение питания", "supply voltage", "besleme gerilimi"),
        TrilingualEntry("напряжение", "voltage", "gerilim"),
        TrilingualEntry("ток", "current", "akım"),
        TrilingualEntry("мощность", "power", "güç"),
        TrilingualEntry("сопротивление", "resistance", "direnç"),
        TrilingualEntry("двигатель", "motor", "motor"),
        TrilingualEntry("насос", "pump", "pompa"),
        TrilingualEntry("компрессор", "compressor", "kompresör"),
        TrilingualEntry("генератор", "generator", "jeneratör"),
        TrilingualEntry("трансформатор", "transformer", "transformatör"),
        TrilingualEntry("датчик", "sensor", "sensör"),
        TrilingualEntry("манометр", "pressure gauge", "manometre"),
        TrilingualEntry("трубопровод", "pipeline", "boru hattı"),
        TrilingualEntry("труба", "pipe", "boru"),
        TrilingualEntry("сварка", "welding", "kaynak"),
        TrilingualEntry("чертеж", "drawing", "çizim"),
        TrilingualEntry("схема", "schematic", "şema"),
        TrilingualEntry("ошибка", "error", "hata"),
        TrilingualEntry("неисправность", "fault", "arıza"),
        TrilingualEntry("ремонт", "repair", "onarım"),
        TrilingualEntry("техническое обслуживание", "maintenance", "bakım"),
        TrilingualEntry("безопасность", "safety", "güvenlik"),
        TrilingualEntry("инженер", "engineer", "mühendis"),
        TrilingualEntry("строительная площадка", "construction site", "şantiye"),
        TrilingualEntry("на", "on", "üzerinde"),
        TrilingualEntry("в", "in", "içinde"),
        TrilingualEntry("и", "and", "ve"),
        TrilingualEntry("составляет", "is", "seviyesindedir"),
        TrilingualEntry("при", "at", "değerinde"),
        TrilingualEntry("функции", "of function", "fonksiyonunun"),
        TrilingualEntry("в модуле", "in module", "modülünde"),
        TrilingualEntry("контроллера", "of controller", "kontrolör"),
        TrilingualEntry("шины", "of bus", "bara"),
        TrilingualEntry("и ток", "and current", "ve akımı")
    )

    private val dictionaryByPair: Map<String, List<Pair<String, String>>> by lazy {
        val map = mutableMapOf<String, MutableList<Pair<String, String>>>()
        val langs = listOf("ru", "en", "tr")
        for (src in langs) {
            for (tgt in langs) {
                if (src == tgt) continue
                val key = "$src-$tgt"
                val list = mutableListOf<Pair<String, String>>()
                for (e in entries) {
                    val s = when (src) { "ru" -> e.ru; "tr" -> e.tr; else -> e.en }
                    val t = when (tgt) { "ru" -> e.ru; "tr" -> e.tr; else -> e.en }
                    list.add(s to t)
                }
                // Sort longer multi-word phrases first
                list.sortByDescending { it.first.length }
                map[key] = list
            }
        }
        map
    }

    /**
     * Attempts phrase-level offline translation. Returns the translated string and a coverage ratio (0.0..1.0).
     */
    fun translateOffline(text: String, sourceLang: String, targetLang: String): Pair<String, Float> {
        val list = dictionaryByPair["${sourceLang.lowercase()}-${targetLang.lowercase()}"]
            ?: return text to 0f

        var working = text
        var matchedChars = 0
        for ((srcPhrase, tgtPhrase) in list) {
            val regex = Regex("""(?<![а-яА-ЯёЁa-zA-ZğüşıöçĞÜŞİÖÇ0-9_])${Regex.escape(srcPhrase)}(?![а-яА-ЯёЁa-zA-ZğüşıöçĞÜŞİÖÇ0-9_])""", RegexOption.IGNORE_CASE)
            val matches = regex.findAll(working).count()
            if (matches > 0) {
                matchedChars += matches * srcPhrase.length
                working = regex.replace(working, tgtPhrase)
            }
        }

        // Count placeholder tokens (__NT_0__, __TERM_1__) as 100% matched
        val placeholderMatches = Regex("""__(?:NT|TERM)_\d+__""").findAll(working)
        for (m in placeholderMatches) {
            matchedChars += m.value.length
        }

        val totalMeaningfulChars = text.replace(Regex("""[\s.,!?;:\-()\[\]]+"""), "").length.coerceAtLeast(1)
        val coverage = (matchedChars.toFloat() / totalMeaningfulChars.toFloat()).coerceIn(0f, 1f)
        return working to coverage
    }
}
