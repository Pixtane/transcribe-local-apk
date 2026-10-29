package com.pixtane.holos.tts

import com.pixtane.holos.Lang
import com.pixtane.holos.VOICES
import com.pixtane.holos.Voice
import com.pixtane.holos.t
import com.pixtane.holos.uiName

enum class EngineKind { SUPERTONIC, VITS }

/** A downloadable TTS model ("engine") and the languages it can speak. */
data class EnginePack(
    val id: String,
    val family: String,
    val name: String,
    val kind: EngineKind,
    val languages: List<String>,
    val approxBytes: Long,
    val sampleRate: Int,
    val voices: List<Voice>,
    val license: String,
    val blurb: String,
    val dirName: String,
    /** Required files (name → expected size, or -1 when unknown). */
    val files: Map<String, Long>,
    val hfRepos: List<String>,
    val tarballUrl: String,
) {
    val hasQualitySteps get() = kind == EngineKind.SUPERTONIC
}

object EngineCatalog {
    private const val RELEASES = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models"
    private const val SUPERTONIC_ID = "sherpa-onnx-supertonic-3-tts-int8-2026-05-11"

    val SUPERTONIC_LANGS = listOf(
        "uk", "en", "de", "ar", "bg", "cs", "da", "el", "es", "et", "fi", "fr", "hi", "hr", "hu", "id",
        "it", "ja", "ko", "lt", "lv", "nl", "pl", "pt", "ro", "ru", "sk", "sl", "sv", "tr", "vi",
        // Ecclesiastical readings built on the Italian and Russian voices.
        "la", "cu",
    )

    val SUPERTONIC = EnginePack(
        id = "supertonic3",
        family = "Supertonic 3",
        name = "Supertonic 3 · int8",
        kind = EngineKind.SUPERTONIC,
        languages = SUPERTONIC_LANGS,
        approxBytes = 145_296_000L,
        sampleRate = 44_100,
        voices = VOICES,
        license = "Model: OpenRAIL-M (Supertone Inc.). int8 export: Apache-2.0 (k2-fsa/sherpa-onnx).",
        blurb = "Natural, expressive voices. 10 speakers, 31 languages.",
        dirName = "supertonic-3-int8",
        files = linkedMapOf(
            "tts.json" to 8_253L,
            "unicode_indexer.bin" to 262_144L,
            "voice.bin" to 517_168L,
            "duration_predictor.int8.onnx" to 3_700_147L,
            "text_encoder.int8.onnx" to 36_416_150L,
            "vocoder.int8.onnx" to 25_991_073L,
            "vector_estimator.int8.onnx" to 78_400_833L,
        ),
        hfRepos = listOf("csukuangfj2/$SUPERTONIC_ID", "csukuangfj/$SUPERTONIC_ID"),
        tarballUrl = "$RELEASES/$SUPERTONIC_ID.tar.bz2",
    )

    /** Coqui / Neon AI VITS models: character based, single voice, very fast. */
    private val COQUI_MODELS = linkedMapOf(
        "uk" to "vits-coqui-uk-mai", "de" to "vits-coqui-de-css10", "bg" to "vits-coqui-bg-cv",
        "cs" to "vits-coqui-cs-cv", "da" to "vits-coqui-da-cv", "es" to "vits-coqui-es-css10",
        "et" to "vits-coqui-et-cv", "fi" to "vits-coqui-fi-css10", "fr" to "vits-coqui-fr-css10",
        "ga" to "vits-coqui-ga-cv", "hr" to "vits-coqui-hr-cv", "lt" to "vits-coqui-lt-cv",
        "lv" to "vits-coqui-lv-cv", "mt" to "vits-coqui-mt-cv", "nl" to "vits-coqui-nl-css10",
        "pl" to "vits-coqui-pl-mai_female", "pt" to "vits-coqui-pt-cv", "ro" to "vits-coqui-ro-cv",
        "sk" to "vits-coqui-sk-cv", "sl" to "vits-coqui-sl-cv", "sv" to "vits-coqui-sv-cv",
    )

    val COQUI: List<EnginePack> = COQUI_MODELS.map { (lang, model) ->
        EnginePack(
            id = "coqui-$lang",
            family = "Coqui VITS",
            name = "Coqui VITS · ${Lang.of(lang).english}",
            kind = EngineKind.VITS,
            languages = listOf(lang),
            approxBytes = 67_100_000L,
            sampleRate = 22_050,
            voices = listOf(Voice(0, "V", "Default voice")),
            license = "Model: Coqui TTS / Neon AI (BSD-3-Clause); export: Apache-2.0 (k2-fsa/sherpa-onnx).",
            blurb = "Very fast, lightweight single-voice model.",
            dirName = "coqui-$lang",
            files = linkedMapOf("model.onnx" to -1L, "tokens.txt" to -1L),
            hfRepos = emptyList(),
            tarballUrl = "$RELEASES/$model.tar.bz2",
        )
    }

    val ALL: List<EnginePack> = listOf(SUPERTONIC) + COQUI

    fun get(id: String?): EnginePack = ALL.firstOrNull { it.id == id } ?: SUPERTONIC
    fun forLang(code: String): List<EnginePack> = ALL.filter { code in it.languages }
}

/** Engine name in the current UI language. */
fun EnginePack.uiName(): String =
    if (kind == EngineKind.VITS) "Coqui VITS · " + Lang.of(languages.first()).uiName() else name

fun EnginePack.uiBlurb(): String = when (kind) {
    EngineKind.SUPERTONIC -> t("Natural, expressive voices. 10 speakers, 31 languages.", "Природні, виразні голоси. 10 дикторів, 31 мова.")
    EngineKind.VITS -> t("Very fast, lightweight single-voice model.", "Дуже швидка легка модель з одним голосом.")
}
