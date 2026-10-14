# Third-party components

- **sherpa-onnx 1.13.8** (k2-fsa), Apache License 2.0 — `app/libs/sherpa-onnx-1.13.8-arm64.aar`, the official
  release AAR (sha256 `633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96`) with only the
  arm64-v8a libraries kept. Includes ONNX Runtime (MIT License).
- **sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01** keyword-spotting model (k2-fsa), Apache License 2.0 —
  `app/src/main/assets/kws/` (the int8 encoder, decoder and joiner, and tokens.txt) from the release archive
  (sha256 `f170013b4716e41b62b9bfd809687c207cef798ef9bc6534d524e17af9b6561a`). `keywords.txt` is ours: "voice bar"
  plus the listen commands, tokenized with the model's bpe.model.
- Material Symbols glyphs (Google), Apache License 2.0.
