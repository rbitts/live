# Android latency and GPU acceleration — 2026-10-13

Latest Android 0.9 evidence: [small observer, speech generation/playback diagnosis and FT review](android-realtime-model-study.md). Historical results below retain their original measurement scope.


## Decision

The0.6 acceleration baseline on the attached SM-X926N runs the VLM (image encoder and Qwen3.5-4B decoder) with MNN OpenCL. ASR and Qwen TTS retain CPU execution. Model weights, revisions, image resolution and prompts are unchanged. This improves text responsiveness, **not full Live voice acceptance**.

This is MediaTek MT6989 / Dimensity 9300+, not Qualcomm. OpenCL reports `Mali-G720-Immortalis MC12 r0p0`. A Qualcomm QNN/Hexagon model is not a drop-in accelerator for this device. MediaTek NPU deployment is a separate NeuroPilot conversion/runtime integration; it was not implemented or measured here.

## Measurement boundary

- Physical Galaxy Tab S10 Ultra 5G, Android 16/API 36, about 16 GB RAM; connected-device authorization already given. Other devices/processes were not stopped.
- Same installed bundle and fixed GQA image `2316817.jpg`, 4.48-second Korean WAV, fixed question. TTS profiling uses the fixed answer “현재 화면에서 컵의 중심은 오른쪽 구역입니다.” Its sampling is stochastic, so compare real-time factor (RTF), not total duration alone.
- MNN revision `9f097c5fdd48e0cac4468d4cc53a828794d96888` plus existing `mnn-customvoice-sohee.patch`; no final upstream change or model revision change. Models are pinned in `configs/models.json` and existing bundle manifest.
- Two to three repeats per setting. These are engineering smokes, not p95, sustained thermal testing or broad accuracy acceptance. Backend changes run sequentially. A thermal snapshot showed status 0, skin 37.1°C; this is not proof of steady-state thermals.
- Stage times start after a complete input exists. Actual microphone endpointing adds approximately 600 ms of silence at 16 kHz; capture, camera timing and human acoustic latency are not included in the stage table.
- Baseline `vision_us` was cumulative: use successive differences. The harness now emits per-request vision counters. TTS `decode_ms` includes waveform decoding; `audio_ms` must not be added again.

## Results

Warm VLM measurements use iterations 1–2. Full-resolution image input remains about 0.197 MP and 263 prompt tokens.

| Stage | CPU baseline | OpenCL VLM | Interpretation |
|---|---:|---:|---|
| Image encoder | 3.87–4.05 s | 0.775–0.780 s | GPU removes most image-processing delay |
| Prompt prefill | 4.90–4.94 s | 2.20–2.21 s | Still a substantial wait before the first token |
| VLM first visible text | 8.90–9.04 s | 3.05–3.06 s | About 66% less wait at this stage |
| VLM complete short answer | 9.76–9.90 s | 3.74–3.76 s | Same “right region” answer in every measured run |
| ASR, measured separately | 0.77–0.94 s | CPU retained | Not the dominant bottleneck |
| TTS generation/audio duration | 1.67–1.69× | 1.60–1.61× with production profile, still CPU | Production remains slower than playback |

Production-profile all-stage repeats gave VLM first text 3.24–3.32 s; separate stage-only timing is not identical to a complete warmed pipeline.

Real application verification (`workflow-report.json`, GPU flag required):

| User-visible measurement | CPU app, earlier run | Installed GPU app |
|---|---:|---:|
| WAV submission → first text | 8.902 s | 4.166 s |
| WAV submission → first generated PCM | 10.592 s | 5.563 s |
| Silent chat submission → first text | 9.316 s | 3.175 s |
| First AudioTrack write | 11.386 s, two starvation episodes | 10.915 s, zero starvation in this one run |

The playback comparison also changes buffering and sampled speech duration, so it is **not an isolated GPU speedup measurement**. The GPU run generated four seconds of audio, waited for the complete utterance, then played it. First PCM is not first audible sound. Current buffering hides gaps by adding latency; it does not solve slow TTS.

## Rejected or experimental paths

- CPU high-power/four-thread setting: TTS RTF 1.70–1.76, no improvement; not applied.
- TTS chunks 8 → 32 codec frames: RTF improved to 1.287–1.288, but first PCM worsened to 2.93–2.98 s from approximately 0.7–0.9 s. Still slower than real time; not applied.
- Whole TTS on OpenCL: model load finished, generation did not finish or produce a recorded result. Repeat was stopped at 46 seconds of process lifetime. The process was sleeping at 0% CPU in an observed snapshot. This is a stalled-path observation, not a proven driver root cause.
- Waveform-only GPU split: an isolated temporary MNN extension loaded in 4.24 s but still made no completed generation result by 72 seconds of process lifetime; stopped. The extension was removed, and the original patched MNN source was compared byte-for-byte with the pre-experiment copy. No unverified TTS GPU path is enabled in the app.
- Reduced image budget (`opencl256`): first text 1.40–1.44 s warm, same answer on this scene. It changes evidence resolution (about 0.055 MP / 125 tokens), so it is **not the default** without small-object/text accuracy evaluation.
- Early exploratory results used malformed nested diagnostic configuration. Final comparisons use corrected explicit JSON objects and the production shared helper. Those exploratory files are retained locally, not treated as final backend acceptance.
- OpenCL cold shader setup can make the first ever request substantially slower. Persistent per-model shader caches are enabled. The installed app was tested with populated caches; a cache-empty installation is not covered by the final app timing.

## Applied implementation

`MNN_OPENCL=ON`; Android vendor-library access is declared optional. A real OpenCL GPU is probed through the vendor API. The production profile enables GPU only on the validated `MT6989` device family; other SoCs and unavailable drivers retain CPU behavior. Image processing and the VLM use OpenCL low precision, four-thread/GPU mode and persistent cache. ASR/TTS weights and CPU settings remain unchanged.

`native/android_acceleration.hpp` is shared by app JNI and the `production` profiler, avoiding separate benchmark-only optimization. The debug-only instrumentation exposes stage measurements, finite/nonzero PCM checks, sample counts and RTF. It does not access camera/microphone or play audio.

Build/lint, 45 Python tests, native UTF-8/evidence/epoch policy tests and real GPU app workflow passed. Model correctness is established only for these fixed smokes. Human microphone/camera conversation, varied-scene accuracy, prolonged thermal/battery behavior, Bluetooth/acoustic AEC and full-duplex acceptance remain open.

## Architecture consequence

Keep the working VLM GPU acceleration. TTS is now the primary obstacle to spoken Live behavior. More playback buffering is not the answer. A follow-up design needs a verified speech path that consistently generates faster than real time (proposed target RTF ≤ 0.7 with margin), then bounded short preroll and sentence-level overlap. If retaining this Qwen TTS, resolve the stalled GPU path or port the waveform/autoregressive components to a validated backend. Otherwise evaluate an offline platform/smaller Korean TTS model with measured quality and latency. These are proposed next experiments, not completed implementations or approved model substitutions.

NeuroPilot/NPU or Qualcomm hardware would require a separate operator/quantization/runtime validation. Changing the model's label to “QC” alone would not fix the serial ASR → complete VLM answer → slow TTS architecture. Even with GPU VLM, the observed approximately11–17-second speaker start must not be described as Live-ready.

## Evidence and reproduction

Local ignored evidence: `.runtime/android-profile/` (JSONL profiles, build logs, device workflow/cancel logs, APK). Main artifact: `live-0.6-arm64.apk`. No recordings, weights or shader caches are committed.

After building/installing the debug app with the existing model bundle and `question.wav` / `scene.jpg` in the app's external files directory:

```sh
adb -s R54X902CZEK shell am instrument -w -e variant production -e role all -e repeats 2 dev.live.offline/.ModelProfileInstrumentation
```

Use `baseline` for unchanged CPU settings; `opencl-vlm` / `opencl256` for stage experiments. Runs are sequential; preserve first-run versus warm results. The app has no Internet permission; no paid/cloud resources or workstation inference GPU was used.

Primary references checked 2026-10-13:

- [Samsung: Tab S10 hardware](https://news.samsung.com/sg/galaxy-tab-s10-series-samsungs-first-ai-ready-tablet)
- [MNN official GPU compilation/configuration](https://mnn-docs.readthedocs.io/en/stable/transformers/llm.html)
- [Android vendor native-library access](https://developer.android.com/guide/topics/manifest/uses-native-library-element)
- [Qualcomm QNN device support](https://docs.qualcomm.com/nav/home/QNN_general_overview.html?product=924033590759186372)
- [MediaTek NeuroPilot access and editions](https://neuropilot-developer.mediatek.com/resources/public/latest/en/docs/readme)

## Proactive assistant follow-up

The user selected a combination of remembered user conditions and scene-change awareness. Version0.7 changes prompts and adds bounded session text memory/periodic observation; its times must not be mixed into the unchanged-prompt GPU comparison above. See [real-device proactive validation](android-proactive-validation.md).

At TTS RTF1.6, uninterrupted sustained output without sufficient buffering would require playback speed at most1/1.6≈0.625×. Small adjustments such as0.9× cannot close that deficit, and slowing playback cannot advance the first generated sample. Playback-rate changes were not applied. Short first answers are now requested; measuring their duration-dependent benefit is separate from fixing synthesis throughput.

A credible Live design needs independently scheduled perception/memory updates, user-priority cancellation, salience/deduplication for proactive notices, and a faster speech path. This release implements the control and memory loop with the existing pinned model; it does not demonstrate low-latency continuous semantic perception.
