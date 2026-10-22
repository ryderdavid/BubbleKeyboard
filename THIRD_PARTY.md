# Third-party components

- **sherpa-onnx 1.13.8** (k2-fsa), Apache License 2.0 — `app/libs/sherpa-onnx-1.13.8-arm64.aar`, the official
  release AAR (sha256 `633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96`) with only the
  arm64-v8a libraries kept. Includes ONNX Runtime (MIT License).
- **sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01** keyword-spotting model (k2-fsa), Apache License 2.0 —
  `app/src/main/assets/kws/` (the int8 encoder, decoder and joiner, and tokens.txt) from the release archive
  (sha256 `f170013b4716e41b62b9bfd809687c207cef798ef9bc6534d524e17af9b6561a`). `keywords.txt` is ours: "voice bar"
  plus the listen commands, tokenized with the model's bpe.model.
- **Silero VAD** (Silero Team), MIT License — `app/src/main/assets/vad/silero_vad.onnx`, tells speech from silence for
  the voiceprint and "Only my voice". Copyright (c) 2020-present Silero Team.
- Material Symbols glyphs (Google), Apache License 2.0.
- AndroidX and Jetpack Compose (The Android Open Source Project), OkHttp (Square), Kotlin (JetBrains), Apache License 2.0.
- Downloaded on the phone only when chosen, not shipped in the APK:
  - **Parakeet** speech model (NVIDIA NeMo Parakeet, converted to ONNX by the sherpa-onnx project:
    `csukuangfj2/sherpa-onnx-nemo-parakeet-unified-en-0.6b-int8-non-streaming` on Hugging Face), under NVIDIA's license
    for the model. The converted copy doesn't state one; NVIDIA's English Parakeet models are CC BY 4.0.
  - **ERes2Net** speaker model (`3dspeaker_speech_eres2net_sv_en_voxceleb_16k.onnx`, from the
    [3D-Speaker](https://github.com/modelscope/3D-Speaker) project, Apache License 2.0; converted by the sherpa-onnx
    project, `csukuangfj/speaker-embedding-models`).

The app shows these, with the license texts the Apache and MIT licenses ask to travel with it
(`app/src/main/assets/licenses/`), under Settings › Open-source notices.
- **FUTO Swipe** models (FUTO), FUTO Model Weights License 1.0 (`app/src/main/assets/licenses/futo-model-weights-1.0.md`)
  — `app/src/main/assets/swipe/`: the encoder `honorable_sturgeon/model_fp32.pte` (as `encoder.pte`) and the English
  decoder `magic_macaw/model_fp32.pte` (as `decoder.pte`), unmodified, and `scoring.json`, from
  [futo-org/futo-swipe](https://huggingface.co/futo-org/futo-swipe) at revision `18328c3042b066952c0936b3771d492fe2ec289a`.
  The license asks for a visible "Powered by FUTO Swipe": it's on the Swipe to type setting and in the app's notices.
  Voicebar's decoder (`swipe/SwipeDecoder.kt`) is its own, written from the FUTO Swipe technical report
  (arXiv 2606.25247), not FUTO's GPL library. Its tests use 31 swipes from FUTO's
  [swipe.futo.org](https://huggingface.co/datasets/futo-org/swipe.futo.org) test split (MIT).
- **ExecuTorch 1.5.1** (Meta Platforms, Inc. and affiliates), BSD License — runs the swipe models; with **fbjni** and
  **SoLoader** (Meta), Apache License 2.0.
- **Word list** for the keyboard's suggestions and swipe dictionary — `app/src/main/assets/words_en.txt`, the 60,000 most
  common English words (letters and apostrophes only) in order, each with how common it is (0 to 255, in proportion to
  the log of its frequency, as Android's dictionaries have it), taken from the `large_en` data of
  [wordfreq](https://github.com/rspeer/wordfreq) 3.1.1 by Robyn Speer, which is licensed under
  [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/); this list is shared under the same license.
  "I" and its contractions, and first names that aren't also ordinary words, are capitalized.
