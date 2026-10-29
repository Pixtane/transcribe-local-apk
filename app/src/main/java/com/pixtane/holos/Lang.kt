package com.pixtane.holos

import androidx.compose.ui.graphics.Color
import com.pixtane.holos.tts.ChurchSlavonic
import com.pixtane.holos.tts.Latin

/**
 * A language the user can pick. [ttsCode] is what the engine is asked to speak; for Latin
 * and Church Slavonic the text is first rewritten ([transform]) and read with the Italian or
 * Russian voice, which matches ecclesiastical pronunciation closely.
 */
class Lang(
    val code: String,
    val english: String,
    val native: String,
    val flag: String,
    val sample: String,
    val accent: Color,
    val deep: Color,
    val ttsCode: String = code,
    val transform: ((String) -> String)? = null,
) {
    fun prepare(text: String): String = transform?.invoke(text) ?: text
    override fun toString() = code

    companion object {
        private fun l(code: String, en: String, nat: String, flag: String, sample: String, accent: Long, deep: Long, tts: String = code, tr: ((String) -> String)? = null) =
            Lang(code, en, nat, flag, sample, Color(accent), Color(deep), tts, tr)

        val UK = l("uk", "Ukrainian", "Українська", "🇺🇦", "Привіт! Я прочитаю ваш текст уголос — повністю на телефоні, без інтернету.", 0xFFFACC15, 0xFF1D3B8F)
        val EN = l("en", "English", "English", "🇬🇧", "Hello! I can read any text aloud, entirely on your phone, with no internet needed.", 0xFF5EEAD4, 0xFF134E4A)
        val DE = l("de", "German", "Deutsch", "🇩🇪", "Hallo! Ich lese Ihnen jeden Text vor – direkt auf dem Handy, ganz ohne Internet.", 0xFFFB923C, 0xFF6B1D1D)

        val ALL: List<Lang> = listOf(
            UK, EN, DE,
            l("la", "Latin (ecclesiastical)", "Latina", "🇻🇦", "Pater noster, qui es in caelis, sanctificetur nomen tuum.", 0xFFFDE68A, 0xFF5B3A0E, "it", Latin::toItalianReading),
            l("cu", "Church Slavonic", "Церковнославянскій", "☦️", "Ѻ҆́тче на́шъ, и҆́же є҆сѝ на нб҃сѣ́хъ, да свѧти́тсѧ и҆́мѧ твоѐ.", 0xFFFCD34D, 0xFF4A1D1D, "ru", ChurchSlavonic::toRussianReading),
            l("ar", "Arabic", "العربية", "🇸🇦", "مرحبًا! يمكنني قراءة أي نص بصوت عالٍ على هاتفك مباشرة.", 0xFF6EE7B7, 0xFF064E3B),
            l("bg", "Bulgarian", "Български", "🇧🇬", "Здравейте! Мога да прочета всеки текст на глас, направо на телефона ви.", 0xFF86EFAC, 0xFF14532D),
            l("cs", "Czech", "Čeština", "🇨🇿", "Ahoj! Přečtu nahlas jakýkoli text, přímo ve vašem telefonu.", 0xFF93C5FD, 0xFF1E3A8A),
            l("da", "Danish", "Dansk", "🇩🇰", "Hej! Jeg kan læse enhver tekst højt, direkte på din telefon.", 0xFFFCA5A5, 0xFF7F1D1D),
            l("el", "Greek", "Ελληνικά", "🇬🇷", "Γεια σας! Μπορώ να διαβάσω δυνατά οποιοδήποτε κείμενο στο κινητό σας.", 0xFF7DD3FC, 0xFF0C4A6E),
            l("es", "Spanish", "Español", "🇪🇸", "¡Hola! Puedo leer cualquier texto en voz alta, directamente en tu teléfono.", 0xFFFDBA74, 0xFF7C2D12),
            l("et", "Estonian", "Eesti", "🇪🇪", "Tere! Ma loen iga teksti ette otse sinu telefonis.", 0xFF93C5FD, 0xFF1E293B),
            l("fi", "Finnish", "Suomi", "🇫🇮", "Hei! Voin lukea minkä tahansa tekstin ääneen suoraan puhelimessasi.", 0xFFBFDBFE, 0xFF1E3A8A),
            l("fr", "French", "Français", "🇫🇷", "Bonjour ! Je peux lire n'importe quel texte à voix haute, directement sur votre téléphone.", 0xFFA5B4FC, 0xFF312E81),
            l("ga", "Irish", "Gaeilge", "🇮🇪", "Dia duit! Is féidir liom aon téacs a léamh os ard ar do ghuthán.", 0xFF86EFAC, 0xFF14532D),
            l("hi", "Hindi", "हिन्दी", "🇮🇳", "नमस्ते! मैं आपके फ़ोन पर ही कोई भी पाठ ज़ोर से पढ़ सकता हूँ।", 0xFFFDBA74, 0xFF7C2D12),
            l("hr", "Croatian", "Hrvatski", "🇭🇷", "Bok! Mogu naglas pročitati bilo koji tekst, izravno na vašem telefonu.", 0xFFFCA5A5, 0xFF1E3A8A),
            l("hu", "Hungarian", "Magyar", "🇭🇺", "Szia! Bármilyen szöveget felolvasok, közvetlenül a telefonodon.", 0xFF86EFAC, 0xFF7F1D1D),
            l("id", "Indonesian", "Bahasa Indonesia", "🇮🇩", "Halo! Saya bisa membacakan teks apa pun langsung di ponsel Anda.", 0xFFFCA5A5, 0xFF7F1D1D),
            l("it", "Italian", "Italiano", "🇮🇹", "Ciao! Posso leggere ad alta voce qualsiasi testo, direttamente sul tuo telefono.", 0xFF86EFAC, 0xFF14532D),
            l("ja", "Japanese", "日本語", "🇯🇵", "こんにちは！スマートフォンだけで、どんな文章でも読み上げます。", 0xFFFDA4AF, 0xFF881337),
            l("ko", "Korean", "한국어", "🇰🇷", "안녕하세요! 휴대폰에서 바로 어떤 글이든 소리 내어 읽어 드립니다.", 0xFF93C5FD, 0xFF1E3A8A),
            l("lt", "Lithuanian", "Lietuvių", "🇱🇹", "Labas! Galiu garsiai perskaityti bet kokį tekstą tiesiai jūsų telefone.", 0xFFFDE047, 0xFF14532D),
            l("lv", "Latvian", "Latviešu", "🇱🇻", "Sveiki! Es varu skaļi nolasīt jebkuru tekstu tieši jūsu tālrunī.", 0xFFFCA5A5, 0xFF7F1D1D),
            l("mt", "Maltese", "Malti", "🇲🇹", "Bongu! Nista' naqra kwalunkwe test b'leħen għoli fuq it-telefon tiegħek.", 0xFFFCA5A5, 0xFF7F1D1D),
            l("nl", "Dutch", "Nederlands", "🇳🇱", "Hallo! Ik kan elke tekst hardop voorlezen, rechtstreeks op je telefoon.", 0xFFFDBA74, 0xFF1E3A8A),
            l("pl", "Polish", "Polski", "🇵🇱", "Cześć! Mogę przeczytać na głos dowolny tekst, prosto w twoim telefonie.", 0xFFFCA5A5, 0xFF7F1D1D),
            l("pt", "Portuguese", "Português", "🇵🇹", "Olá! Posso ler qualquer texto em voz alta, diretamente no seu telemóvel.", 0xFF86EFAC, 0xFF7F1D1D),
            l("ro", "Romanian", "Română", "🇷🇴", "Bună! Pot citi cu voce tare orice text, direct pe telefonul tău.", 0xFFFDE047, 0xFF1E3A8A),
            l("ru", "Russian", "Русский", "🇷🇺", "Здравствуйте! Я могу прочитать вслух любой текст прямо на вашем телефоне.", 0xFF93C5FD, 0xFF1E3A8A),
            l("sk", "Slovak", "Slovenčina", "🇸🇰", "Ahoj! Prečítam nahlas akýkoľvek text priamo vo vašom telefóne.", 0xFF93C5FD, 0xFF1E3A8A),
            l("sl", "Slovenian", "Slovenščina", "🇸🇮", "Živijo! Lahko na glas preberem katero koli besedilo, kar na vašem telefonu.", 0xFF93C5FD, 0xFF1E3A8A),
            l("sv", "Swedish", "Svenska", "🇸🇪", "Hej! Jag kan läsa upp vilken text som helst, direkt i din telefon.", 0xFFFDE047, 0xFF1E3A8A),
            l("tr", "Turkish", "Türkçe", "🇹🇷", "Merhaba! Herhangi bir metni doğrudan telefonunuzda sesli okuyabilirim.", 0xFFFCA5A5, 0xFF7F1D1D),
            l("vi", "Vietnamese", "Tiếng Việt", "🇻🇳", "Xin chào! Tôi có thể đọc to bất kỳ văn bản nào ngay trên điện thoại của bạn.", 0xFFFDE047, 0xFF7F1D1D),
        )

        val entries get() = ALL

        fun of(code: String): Lang = ALL.firstOrNull { it.code == code } ?: EN
        fun find(code: String?): Lang? = code?.let { c -> ALL.firstOrNull { it.code == c } }

        /** Cheap heuristic language guess used to pre-select the language. */
        fun detect(text: String): Lang? {
            val sample = text.take(4000)
            if (sample.isBlank()) return null
            var cyr = 0; var lat = 0; var csMarks = 0
            for (c in sample) {
                when {
                    c in 'Ѐ'..'ӿ' -> cyr++
                    c in 'a'..'z' || c in 'A'..'Z' -> lat++
                }
                if (c in "ѣѧѫѡѿѻѯѱѳѵꙋ҃҆҇" || c in 'ⷠ'..'ⷿ') csMarks++
            }
            if (cyr > lat) {
                if (csMarks > cyr / 40) return of("cu")
                val lower = sample.lowercase()
                val ukHits = lower.count { it in "іїєґ" }
                val ruHits = lower.count { it in "ыэъё" }
                return if (ruHits > ukHits * 2 && ruHits > 3) of("ru") else UK
            }
            if (lat == 0) return null
            val lower = " " + sample.lowercase() + " "
            val deHits = listOf(" der ", " die ", " und ", " ist ", " nicht ", " ich ", " das ", " ein ", " mit ", "ä", "ö", "ü", "ß")
                .sumOf { lower.windowedCount(it) }
            val enHits = listOf(" the ", " and ", " is ", " of ", " to ", " you ", " that ", " with ", " this ")
                .sumOf { lower.windowedCount(it) }
            val laHits = listOf(" et ", " est ", " in ", " ad ", " non ", " cum ", " qui ", " quae ", " quod ", " deus ", " dominus ", "que ")
                .sumOf { lower.windowedCount(it) }
            return when {
                laHits > enHits * 2 && laHits > deHits * 2 && laHits > 5 -> of("la")
                deHits > enHits -> DE
                else -> EN
            }
        }

        private fun String.windowedCount(s: String): Int {
            var count = 0; var i = indexOf(s)
            while (i >= 0) { count++; i = indexOf(s, i + s.length) }
            return count
        }
    }
}

/** A voice offered by an engine. [sid] is the speaker id passed to sherpa-onnx. */
data class Voice(val sid: Int, val code: String, val label: String)

/** The ten preset Supertonic 3 voice styles; sid order is alphabetical (F1..F5, M1..M5). */
val VOICES: List<Voice> = (1..5).map { Voice(it - 1, "F$it", "Female $it") } +
    (1..5).map { Voice(it + 4, "M$it", "Male $it") }
