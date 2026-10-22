package com.ryderdavid.voicebar

/**
 * What Voicebar is built with that others made, for Settings › Open-source notices (and
 * THIRD_PARTY.md, which says the same). [licenseAsset] is the full text in assets/licenses/, when
 * the license asks for its text to travel with the app; otherwise [licenseUrl] points to it.
 */
object Notices {
    data class Notice(
        val name: String,
        /** What it does in Voicebar. */
        val role: String,
        val license: String,
        val copyright: String,
        val source: String,
        val licenseAsset: String? = null,
        val licenseUrl: String? = null,
    )

    val all = listOf(
        Notice("sherpa-onnx 1.13.8", "Speech recognition on the phone (Parakeet), the voice-bar wake word, and the voiceprint",
            "Apache License 2.0", "k2-fsa", "https://github.com/k2-fsa/sherpa-onnx", licenseAsset = "apache-2.0.txt"),
        Notice("ONNX Runtime", "Runs the speech models (inside sherpa-onnx)",
            "MIT License", "Copyright (c) Microsoft Corporation", "https://github.com/microsoft/onnxruntime", licenseAsset = "mit-onnxruntime.txt"),
        Notice("sherpa-onnx-kws-zipformer-gigaspeech-3.3M", "The \"voice bar\" wake-word model",
            "Apache License 2.0", "k2-fsa", "https://github.com/k2-fsa/sherpa-onnx", licenseAsset = "apache-2.0.txt"),
        Notice("Silero VAD", "Tells speech from silence (the voiceprint and \"Only my voice\")",
            "MIT License", "Copyright (c) 2020-present Silero Team", "https://github.com/snakers4/silero-vad", licenseAsset = "mit-silero-vad.txt"),
        Notice("FUTO Swipe models (Powered by FUTO Swipe)", "Swipe typing: the encoder (honorable_sturgeon) and English decoder (magic_macaw), with their tuned scoring. Voicebar's own decoder runs them",
            "FUTO Model Weights License 1.0", "FUTO", "https://huggingface.co/futo-org/futo-swipe", licenseAsset = "futo-model-weights-1.0.md"),
        Notice("ExecuTorch 1.5.1", "Runs the swipe models", "BSD License", "Meta Platforms, Inc. and affiliates, and contributors",
            "https://github.com/pytorch/executorch", licenseAsset = "bsd-executorch.txt"),
        Notice("fbjni and SoLoader", "ExecuTorch's bridge to Android", "Apache License 2.0", "Meta Platforms, Inc. and affiliates",
            "https://github.com/facebookincubator/fbjni", licenseAsset = "apache-2.0.txt"),
        Notice("Word list from wordfreq 3.1.1", "The keyboard's suggestions and swipe dictionary: the 60,000 most common English words, with how common each is",
            "CC BY-SA 4.0 (this list is shared under the same license)", "Robyn Speer", "https://github.com/rspeer/wordfreq",
            licenseUrl = "https://creativecommons.org/licenses/by-sa/4.0/"),
        Notice("Material Symbols", "Icons on the bar", "Apache License 2.0", "Google",
            "https://github.com/google/material-design-icons", licenseAsset = "apache-2.0.txt"),
        Notice("AndroidX and Jetpack Compose", "The settings screens and Android support libraries", "Apache License 2.0",
            "The Android Open Source Project", "https://developer.android.com/jetpack/androidx", licenseAsset = "apache-2.0.txt"),
        Notice("OkHttp", "Talking to ElevenLabs, Claude and OpenAI", "Apache License 2.0", "Square, Inc.",
            "https://github.com/square/okhttp", licenseAsset = "apache-2.0.txt"),
        Notice("Kotlin", "The language and its standard library", "Apache License 2.0", "JetBrains s.r.o. and Kotlin contributors",
            "https://github.com/JetBrains/kotlin", licenseAsset = "apache-2.0.txt"),
        Notice("Parakeet speech model (downloaded when you choose it)", "Speech recognition on the phone: NVIDIA's NeMo Parakeet, converted to ONNX by the sherpa-onnx project",
            "NVIDIA's license for the model", "NVIDIA", "https://huggingface.co/csukuangfj2/sherpa-onnx-nemo-parakeet-unified-en-0.6b-int8-non-streaming",
            licenseUrl = "https://huggingface.co/nvidia"),
        Notice("ERes2Net speaker model (downloaded when you set up your voiceprint)", "Recognizes your voice for \"Only my voice\": from the 3D-Speaker project, converted to ONNX by the sherpa-onnx project",
            "Apache License 2.0 (the 3D-Speaker project)", "The 3D-Speaker authors (Alibaba Tongyi Lab)", "https://github.com/modelscope/3D-Speaker",
            licenseAsset = "apache-2.0.txt"),
    )
}
