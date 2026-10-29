# Holos — offline AI text-to-speech for Android

Holos reads Ukrainian, German and English text aloud with a neural TTS model that runs
entirely on the phone. The model is downloaded once (Hugging Face, with the official
sherpa-onnx GitHub release as fallback); after that no internet is needed.

## Features

- **Local AI voice**: Supertonic 3 (int8 ONNX) via [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx), 10 voices (F1–F5, M1–M5), 44.1 kHz.
- **Starts playing in seconds**: text is split into sentences. Several engines generate them in parallel, in reading order from where you are listening.
- **Progress is never lost**: every sentence is written to disk on its own, atomically. If the app is killed, generation resumes where it stopped.
- **Lock-screen playback**: Media3 `MediaSession` with notification, lock-screen and headset controls (play/pause, seek bar, ±10 s), audio focus and pause-on-unplug.
- **Seeking anywhere**, even into text that is not generated yet. The player waits while the workers jump to that sentence. The seek bar shows which parts already have audio, like YouTube's buffered bar.
- **Lyrics-style reading view**, as in YouTube Music: past, current and upcoming text are styled differently and the current sentence is highlighted word by word. The view auto-scrolls; tap any sentence to jump there.
- **Playback speed** 0.75×–2.5× (pitch-preserving Sonic time-stretch).
- **Library** of saved recordings with generation and listening progress, rename and delete.
- **Import PDF, EPUB, MOBI/AZW/PRC, TXT, Markdown and HTML**, or share/open files and text from other apps.
- **Chapters** come from the EPUB table of contents, PDF bookmarks, MOBI TOC links, or headings like "Chapter 3" / "Розділ 2" / "Kapitel 1". The player shows the current chapter. Its chapter sheet searches chapter titles and the full text, and jumps to any hit.
- Edge-to-edge UI padded for the camera cutout/notch and system bars.
- Built-in **speed test** in Settings: it measures characters per second and estimates the time for 10 pages.

## Model research (Oct 2026)

Goal: 10 pages (~18 000 characters, ~20 min of speech) in under 3 minutes on a
Nothing Phone 3a (Snapdragon 7s Gen 3, 8 cores), or at least faster than real time.

| Model | uk / de / en | Notes |
|---|---|---|
| **Supertonic 3** (Supertone, Apr 2026, 99M params, OpenRAIL-M) | ✅ ✅ ✅ (31 languages) | One model for all three languages. Character-based, so it needs no phonemizer. Flow matching with 2–5 steps. **Chosen.** |
| Piper VITS (`uk_UA-ukrainian_tts`, `de_DE-thorsten`, `en_US-lessac`) | ⚠️ ✅ ✅ | Very fast. Under sherpa-onnx the Ukrainian voice drops phonemes (output was truncated in testing). It needs espeak-ng, which sherpa-onnx 2.x removed. |
| Kokoro-82M / KittenTTS | ❌ ❌ ✅ | No Ukrainian or German. |
| StyleTTS2-UA (ONNX, ~330 MB) | ✅ ❌ ❌ | Ukrainian only, heavy. |
| Higgs Audio v3, VibeVoice, other LLM-style TTS | – | Far too slow for a phone CPU. |

Measured with sherpa-onnx 2.0.1 on 4 x86 vCPUs, Supertonic 3 int8, Ukrainian text:

| Setup | chars/s | Real-time factor |
|---|---|---|
| 1 engine × 4 threads, 5 steps | 71 | 0.17 |
| 1 engine × 4 threads, 3 steps | 106 | 0.12 |
| 1 engine × 4 threads, 2 steps | 128 | 0.10 |
| **4 engines × 1 thread, 3 steps** | **205** | ~0.06 |

Running several single-threaded engines on different sentences scales much better
than giving one engine more threads, so Holos defaults to 4 parallel engines and 3 steps.
At ~200 chars/s, 10 pages take about **1.5 minutes**. Phone speed should be in the same range
(4 Cortex-A720 cores); use **Settings → Speed test** to measure the real number. Even
in the worst case, generation runs many times faster than real time, so playback never
waits after the first sentence.

## Building

The APK is built by GitHub Actions (`.github/workflows/build-apk.yml`) on every push and
published as a GitHub release. To build locally:

```bash
./scripts/fetch-sherpa.sh      # downloads the sherpa-onnx 2.0.1 AAR into app/libs
./gradlew assembleRelease      # needs the Android SDK (platform 35)
```

Output: `app/build/outputs/apk/release/app-release.apk` (arm64-v8a, Android 8.0+).

The release keystore `app/holos-release.jks` is committed on purpose. Every build is signed
with the same key, so new versions install as updates and keep your recordings.
It protects nothing secret: do not reuse it for anything else.

## Architecture

```
tts/ModelManager      download + verify model (HF per-file, GitHub tarball fallback)
tts/EnginePool        pool of sherpa-onnx OfflineTts instances (one per worker)
tts/TextSegmenter     sentence/clause splitting with exact character offsets
importer/             PDF (PdfBox), EPUB, MOBI (PalmDOC + HUFF/CDIC) readers, chapter detection
synth/SynthesisManager  priority-ordered parallel generation, per-sentence atomic saves
synth/SynthesisService  foreground service + wake lock while generating
playback/SegmentPlayer  Media3 SimpleBasePlayer over AudioTrack, one continuous timeline
playback/PlaybackService MediaSessionService (lock screen / notification)
ui/                   Jetpack Compose screens: library, create, player (lyrics), settings
```
