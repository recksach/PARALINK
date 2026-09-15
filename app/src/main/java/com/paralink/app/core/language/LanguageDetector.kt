package com.paralink.app.core.language

data class LanguageDetectionResult(
    val language: Language?,
    val confidence: Double,
    val uncertain: Boolean
)

class LanguageDetector {

    private data class Profile(val ngrams: Map<String, Double>, val total: Long)

    private val profiles: Map<Language, Profile> = buildProfiles()

    fun detect(text: String): LanguageDetectionResult {
        val clean = text.trim()
        if (clean.count { it.isLetter() } < 4) {
            return LanguageDetectionResult(null, 0.0, uncertain = true)
        }
        val input = ngrams(clean)
        val scores = profiles.map { (lang, p) ->
            lang to p.ngrams.entries.sumOf { (ng, w) ->
                input[ng]?.let { w * it } ?: 0.0
            } / p.total.toDouble()
        }.sortedByDescending { it.second }

        val top = scores.firstOrNull() ?: return LanguageDetectionResult(null, 0.0, uncertain = true)
        val second = scores.getOrNull(1)?.second ?: 0.0
        val confidence = if (second > 0.0) top.second / second else 1.0
        val uncertain = confidence < 1.6

        return if (uncertain) {
            LanguageDetectionResult(null, confidence, uncertain = true)
        } else {
            LanguageDetectionResult(top.first, confidence, uncertain = false)
        }
    }

    private fun ngrams(text: String): Map<String, Double> {
        val lower = text.lowercase(Locale.ROOT)
        val letters = lower.filter { it.isLetter() }
        val result = HashMap<String, Double>()
        for (n in 2..4) {
            for (i in 0..letters.length - n) {
                val key = letters.substring(i, i + n)
                result[key] = (result[key] ?: 0.0) + 1.0
            }
        }
        val total = result.values.sum()
        return result.mapValues { it.value / total }
    }

    private fun buildProfiles(): Map<Language, Profile> {
        val samples = mapOf(
            Language.EN to arrayOf(
                "The quick brown fox jumps over the lazy dog while the villagers watch from the window.",
                "I would like to order a cup of coffee and something to eat this morning.",
                "Hello my friend, how are you doing today? The weather is very nice.",
                "Please remember to charge the phone because the battery is almost empty."
            ),
            Language.UK to arrayOf(
                "Привіт, як справи сьогодні? Погода дуже гарна і тепла.",
                "Я хочу замовити каву та щось перекусити зранку.",
                "Будь ласка, не забудь зарядити телефон, бо батарея майже порожня.",
                "Швидка коричнева лисиця стрибає через ледачого собаку."
            ),
            Language.RU to arrayOf(
                "Привет, как дела сегодня? Погода очень хорошая и тёплая.",
                "Я хочу заказать кофе и что-нибудь перекусить утром.",
                "Пожалуйста, не забудь зарядить телефон, потому что батарея почти пустая.",
                "Быстрая коричневая лиса прыгает через ленивую собаку."
            ),
            Language.PL to arrayOf(
                "Cześć, jak się masz dzisiaj? Pogoda jest bardzo ładna i ciepła.",
                "Chciałbym zamówić kawę i coś do jedzenia rano.",
                "Proszę, nie zapomnij naładować telefonu, bo bateria jest prawie pusta.",
                "Szybki brązowy lis przeskakuje nad leniwym psem."
            ),
            Language.DE to arrayOf(
                "Hallo, wie geht es dir heute? Das Wetter ist sehr schön und warm.",
                "Ich möchte einen Kaffee und etwas zu essen bestellen.",
                "Bitte vergiss nicht, das Telefon aufzuladen, der Akku ist fast leer.",
                "Der schnelle braune Fuchs springt über den faulen Hund."
            ),
            Language.FR to arrayOf(
                "Bonjour, comment ça va aujourd'hui ? Il fait très beau et chaud.",
                "Je voudrais commander un café et quelque chose à manger.",
                "N'oublie pas de charger le téléphone, la batterie est presque vide.",
                "Le rapide renard brun saute par-dessus le chien paresseux."
            ),
            Language.ES to arrayOf(
                "Hola, ¿cómo estás hoy? El tiempo es muy bueno y cálido.",
                "Quisiera pedir un café y algo de comer esta mañana.",
                "Por favor, no olvides cargar el teléfono, la batería está casi vacía.",
                "El rápido zorro marrón salta sobre el perro perezoso."
            ),
            Language.IT to arrayOf(
                "Ciao, come stai oggi? Il tempo è molto bello e caldo.",
                "Vorrei ordinare un caffè e qualcosa da mangiare stamattina.",
                "Per favore, non dimenticare di caricare il telefono, la batteria è quasi vuota.",
                "La rapida volpe marrone salta sopra il cane pigro."
            ),
            Language.PT to arrayOf(
                "Olá, como estás hoje? O tempo está muito bom e quente.",
                "Gostaria de pedir um café e algo para comer de manhã.",
                "Por favor, não te esqueças de carregar o telefone, a bateria está quase vazia.",
                "A rápida raposa castanha salta sobre o cão preguiçoso."
            ),
            Language.TR to arrayOf(
                "Merhaba, bugün nasılsın? Hava çok güzel ve sıcak.",
                "Bu sabah bir kahve ve bir şeyler yemek istiyorum.",
                "Lütfen telefonu şarj etmeyi unutma, pil neredeyse boş.",
                "Hızlı kahverengi tilki tembel köpeğin üzerinden atlıyor."
            ),
            Language.AR to arrayOf(
                "مرحباً، كيف حالك اليوم؟ الطقس جميل جدا ودافئ.",
                "أود أن أطلب قهوة وشيئا للأكل هذا الصباح.",
                "من فضلك لا تنسى شحن الهاتف، البطارية فارغة تقريبا.",
                "الثعلب البني السريع يقفز فوق الكلب الكسول."
            ),
            Language.HI to arrayOf(
                "नमस्ते, आज आप कैसे हैं? मौसम बहुत अच्छा और गर्म है।",
                "मैं सुबह एक कॉफी और कुछ खाना ऑर्डर करना चाहता हूँ।",
                "कृपया फोन चार्ज करना मत भूलिए, बैटरी लगभग खाली है।",
                "तेज़ भूरी लोमड़ी आलसी कुत्ते के ऊपर कूदती है।"
            ),
            Language.ZH to arrayOf(
                "你好，你今天怎么样？天气非常好，也很暖和。",
                "我想点一杯咖啡和一点早餐。",
                "请不要忘记给手机充电，电池几乎没电了。",
                "快速的棕色狐狸跳过懒狗。"
            ),
            Language.JA to arrayOf(
                "こんにちは、今日はどうですか？天気はとても良くて暖かいです。",
                "朝にコーヒーと何か食べるものを注文したいです。",
                "電話を充電するのを忘れないでください。バッテリーがほぼ空です。",
                "素早い茶色のキツネが怠けた犬を飛び越えます。"
            ),
            Language.KO to arrayOf(
                "안녕하세요, 오늘은 어떠십니까? 날씨가 아주 좋고 따뜻합니다.",
                "아침에 커피와 뭔가 먹을 것을 주문하고 싶습니다.",
                "배터리가 거의 없으니 휴대폰을 충전하는 것을 잊지 마세요.",
                "빠른 갈색 여우가 게으른 개를 뛰어넘습니다."
            )
        )
        return samples.mapValues { (_, texts) ->
            val counts = HashMap<String, Double>()
            texts.forEach { text ->
                ngrams(text).forEach { (ng, w) -> counts[ng] = (counts[ng] ?: 0.0) + w }
            }
            val total = counts.values.sum()
            Profile(counts, total.toLong())
        }
    }
}