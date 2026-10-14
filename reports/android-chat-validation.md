# Android chat and assistant preview — 2026-10-13

These changes are included in 0.6 on the attached SM-X926N. GPU and timing evidence is in [latency diagnosis](android-latency-diagnosis.md).

## Implemented behavior

- User/assistant chat bubbles and bottom composer; actual incremental model text, valid Korean/emoji JNI conversion, coalesced partial updates and late-epoch rejection.
- Keyboard entry stops capture and selects silent chat. Eight display-only conversation pairs are session scoped; memory deletion and exit clear them. No-evidence refusal is preserved.
- Upper-right 112×84 dp camera preview and active dot; the central assistant remains visible.
- Final animation request: a rotating radial waveform surrounds nine independently changing center bars. Actual AudioTrack playback-head position selects the PCM amplitude. Buffering, thinking, listening, silent PCM and silent chat do not drive motion; interruption clears the meter. System-disabled animations remain static.
- A bounded four-item playback queue uses cancellation-aware backpressure. Slow CPU TTS currently triggers complete-utterance buffering, capped at 24 seconds. This can hide gaps but adds latency; it is not a Live performance solution.

## Verified before the final animation adjustment

Real fixed-fixture JNI ASR→VLM→TTS smoke passed with GPU VLM required. First text 4.166 s, first PCM 5.563 s, first AudioTrack write 10.915 s. All 96,000 samples were written with nonzero amplitude and zero starvation episodes in this one run. Silent chat first text 3.175 s with 27 partial updates; partial text was visible before completion and TTS was skipped.

Actual TTS generation/buffering interruption: track stop 6 ms, native completion 66 ms, microphone continued (5,760 subsequent samples). Actual playback interruption after generation: track/native stop 17 ms, microphone continued (5,120 samples). These are individual smokes, not p95 or acoustic latency.

Local evidence: `.runtime/android-profile/workflow-report.json`, cancellation logs; earlier CPU chat/view renders in `.runtime/android-chat/`. View renders are instrumentation renders. ARM64 build/lint, Python 45 and native UTF/evidence/epoch policy tests passed. This revision's x86 build was not rerun.

The first short-EOS buffer test exposed a race: a playback poll holding an old epoch could drop the first chunk of the new response. The loop now preserves that current-epoch chunk. The final checks below cover the affected behavior.

Human camera conversation, visual preference acceptance, varied-scene accuracy, acoustic echo/Bluetooth and sustained thermal behavior remain unverified. This is a system-duplex pipeline, not native duplex or accepted Live performance.

## Final actual-playback validation (0.6)

`workflow-wave-real.log` passed on SM-X926N: first text4.296s, first PCM5.729s, first write13.636s; all136,320 samples both written and consumed by the AudioTrack playback head; observed meter1.0 while playing and0 after completion; zero starvation episodes. Silent chat first text2.957s. `assistant-playing.png` and `assistant-idle.png` were rendered from the real component and visually inspected.

The meter uses480-sample/20ms bins (128 maximum), independent of tiny nonblocking write fragmentation. A previous per-write meter queue evicted entries before playback; it was fixed. Playback drains by actual head position, replacing an estimated-time pause which could truncate output. Synthetic silence→quiet→loud→silence passed: quiet0.14268, loud0.57073, silent tail0;57,600 samples written and played; blocked producer cancellation2ms. Synthetic tests are not model acceptance.

Version0.7 changes the Android prompt and adds actual model-fed session memory/proactive observation; the earlier display-only/no-evidence description above is historical. See [0.7 evidence](android-proactive-validation.md).
