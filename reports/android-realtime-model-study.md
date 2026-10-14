# Cooking perception, model candidates and speech latency — 2026-10-13

## Decision and limits

Android 0.9 adds a **separate 450M visual observer** for named object presence and uses the installed offline Korean voice for general conversation and proactive notices as well as cooking. The original ASR, conversational VLM and Qwen TTS weights are retained. This is a system pipeline, not native duplex.

The observer does not generate recipes, declare steps completed, recognize cooking actions, infer temperature/doneness or verify safety. The app keeps the user's current step, completed-step memory, cancellation epochs and notification cooldown. General recipe correctness remains **unaccepted**: a reproducible fried-egg/scrambled-egg confusion is now rejected after one bounded correction attempt instead of being spoken. This is a narrow regression guard, not a general recipe verifier.

The user's latest instruction supersedes earlier mute requests. Unmuting set media volume to 5/15 and communication volume to 5/15. A later readback showed media 8/15 and communication 5/15; that later setting was preserved. Final handoff uses normal app output. No automatic earbud/mute policy was added. Earlier diagnostics muted their AudioTrack; the final cooking run has sound enabled.

## Hardware and measurement boundary

- Authorized SM-X926N / MT6989 (Dimensity 9300+), Android 16/API 36, Mali-G720-Immortalis MC12 r0p0, approximately 16 GB physical RAM. This is **not Qualcomm hardware**. Galaxy S/12 GB acceptance is still separate.
- MNN `9f097c5fdd48e0cac4468d4cc53a828794d96888` plus the existing runtime patch; no upstream source or environment dependency change. OpenCL image encoder and text decoder on the tablet; original CPU ASR/TTS retained as fallbacks.
- Each device/model run was preceded by `scripts/doctor.py`. Host reference inference used only available GPU 1, RTX 3090 24 GB, sequentially; GPU 0's unrelated workload was left alone. Model-specific environments were preserved. No cloud inference or paid resources.
- Device benchmarks ran sequentially. The first exploratory 4B profile overlapped a candidate-file copy and is excluded from the final comparison. Later matrix runs had no device copying in parallel. One thermal snapshot was status 0, skin 37.7°C; no sustained thermal acceptance is implied.
- Times below start with a complete image/text input. Camera scheduling, microphone endpointing (~600 ms), text generation, synthesis, buffering and playback-head movement are distinct measurements. No p95 claim from two/three repeats.
- Fixtures: GQA image 2316817, EPIC VISOR P01_03 frame 3060, a generated flat gray image, plus held-out VISOR P06_110 frame 319 and P32_07 frame 866. There are only **four real images plus one blank image**. Repeated questions on the same image are not independent general-accuracy evidence.

## Why the original camera decision took 5.8 seconds

The earlier 0.8 app result was 5.804 s. A new isolated warm run of the installed 4B spatial FT/QAT model took **6.512 s** on the mug fixture:

| Stage | Time | Share, approximately |
|---|---:|---:|
| Image encoder | 1.406 s | 22% |
| Prompt/image token prefill | 3.676 s | 56% |
| Answer token decoding | 1.402 s | 22% |
| Other overhead | 0.028 s | <1% |

The input contained 423 tokens and 0.258 MP of image pixels; the notice required 23 output tokens. GPU acceleration was already active. There was no ASR or speech synthesis in this measurement. Replacing Whisper cannot remove this camera bottleneck.

The original notice prompt also failed negative cases: it described missing onions/pans in a nonempty notice despite a contract requiring silence. Shortening the prompt and reducing Qwen image input to about 0.055–0.061 MP reduced latency but did not reliably fix grounding or JSON validity. Merely replacing the model with a smaller one while asking it to compose Korean coaching prose was rejected.

## Actual tablet VLM comparison

All rows use the same short English object-presence questions and bounded YES/NO generation, greedy decoding, OpenCL and persistent per-model shader caches. This is a deliberately narrower task than the old generated Korean notice; the improvement is architectural as well as model-related.

| Candidate | Warm decision range | Median | Five-case result | Decision |
|---|---:|---:|---|---|
| Existing 4B spatial FT/QAT | 1.589–1.977 s | 1.699 s | 5/5 | Too slow for the frequent observation loop |
| Qwen3.5-2B MNN | 0.908–1.075 s | 0.949 s | 5/5 | Viable comparison/control for future FT |
| Qwen3.5-0.8B MNN | 0.373–0.426 s | 0.390 s | 4/5; hallucinated a cup in the blank frame on every repeat | Fast FT challenger; not the installed observer |
| LFM2.5-VL-450M INT4/vision INT8 | 0.680–0.711 s | 0.701 s | 5/5 | Selected for the bounded observer |

LFM additionally answered four held-out pizza/bread/absent-onion/absent-cucumber questions correctly; their warm times were 0.506–0.538 s. These checks are only a smoke. They do not establish accuracy across the app's object vocabulary, occlusion, small ingredients, moving hands or other kitchens.

**Cold compilation matters:** the first LFM visual request took 11.507 s (9.171 s in vision); first Qwen 0.8B and 2B requests also exceeded 11 s. The application now warms the observer with a synthetic blank frame before announcing model readiness. This moves necessary setup into startup; it does not make a cache-empty installation instant. The LFM export uses a fixed 512×512 image tile / 256 image tokens; Qwen `image_max_pixels` settings do not change that. Do not label its result a 256-pixel LFM benchmark.

The app adds 303.5 MB of observer files to the checksummed bundle. It maps a supported object explicitly named in the current Korean step to an English presence question. Unknown objects abstain. Compound-word boundaries prevent `칼국수`/`팬케이크` from being misread as `칼`/`팬`; `컵` and `잔` share a target. This language bridge is a bounded interim policy, not an FT result or a fixed menu. A YES can only produce a conditional reminder of the existing step. Free-form model explanations, malformed output and NO produce no notice. The observation loop has a 1 s minimum interval, retains change gating/10 s heartbeat, rejects results older than 2.5 s and keeps the 12 s notice cooldown. No visual output advances a step.

### Revisions and export

- Installed observer: `LiquidAI/LFM2.5-VL-450M` at `fc6221ca597f3315e4f82fc2df606783267b34ba`; pinned separately in `configs/cooking-observer.json`.
- Qwen 0.8B public MNN candidate: `taobao-mnn/Qwen3.5-0.8B-MNN` at `3208b55b2e3c948016c9d380a697be881a1f12f8`.
- Qwen 2B public MNN candidate: `taobao-mnn/Qwen3.5-2B-MNN` at `35781816d7b6a9dcb273a6765ac9563401951c3c`.
- Existing original 4B and speech model pins remain in `configs/models.json`. An existing un-finetuned 4B compact export was also tested; it did not solve recipe generation.

Downloaded candidate files were size-checked and LFS files SHA-256-checked against the pinned metadata. LFM export required two compatibility adaptations in the local exporter entry point: Transformers 5.19 flattened SigLIP2's wrapper, and LFM2.5's disabled projector layer norm must be Identity rather than calling None. No parameters or upstream source were changed. The resulting export passed real tablet inference. This is not broad numerical parity or fine-tuning acceptance.

## Cooking guidance accuracy

Korean JSON planning failed across smaller candidates: omitted useful steps, invented ingredients/techniques, malformed output and generation-cap loops. English object classification worked substantially better than Korean recipe/notice generation. LFM's stated Korean support is not evidence that this specific constrained planning task works.

A BF16 original Qwen3.5-4B reference run on RTX 3090 also confused fried eggs with stirring and emitted stray formatting. This reproduces a model/prompt problem before mobile quantization; it does not prove quantization has no additional effect. Enabling thinking reached a 1,024-token bound after 42.2 s without a completed recipe, so it is not enabled in the app.

Changes applied:

- Initial planning uses the user's ingredients/constraints without an incidental camera frame. Observed objects no longer contaminate the initial recipe request.
- Fried-egg plans containing stirring/mixing/stir-frying are challenged once with an explicit correction. If the model repeats the error, no plan or spoken instruction is accepted. The final device tests record that rejection explicitly.
- Fast observation uses a strict presence answer and the app's existing conditional instruction, eliminating generated claims about completed work or an irrelevant scene.

Runtime salad/water plans and control/memory can still work, while the fried-egg example remains blocked. Do not describe this as generally accurate cooking guidance. Food/action-specific evaluation and targeted FT remain necessary.

## Speech generation versus playback

The same fixed Korean sentences went through the actual production player. AudioTrack was muted for these comparison runs; playback-head/PCM evidence is not a human acoustic-quality test.

| Metric | Qwen, short sentence | Qwen, longer sentence | Offline Korean voice, short | Offline Korean voice, longer |
|---|---:|---:|---:|---:|
| Generated audio duration | 2.24 s | 8.32 s | 1.63 s | 5.57 s |
| Synthesis time | 3.583 s | 15.105 s | 0.170 s | 0.165 s |
| Generation RTF | 1.60 | 1.82 | 0.104 | 0.030 |
| First PCM at player | 0.835 s | 0.723 s | 0.184 s | 0.184 s |
| Buffer wait after first PCM | 2.775 s | 14.387 s | 0.002 s | 0.001 s |
| First AudioTrack write | 3.610 s | 15.110 s | 0.186 s | 0.185 s |
| First playback-head movement | 4.179 s | 15.250 s | 0.307 s | 0.270 s |
| First nonzero playback meter | 4.474 s | 15.951 s | 0.307 s | 0.270 s |
| Observed starvation count | 0 | 0 | 0 | 0 |

Qwen's 640 ms chunks arrived roughly 0.85–1.26 s apart. The production player correctly recognized insufficient supply and waited for the whole bounded utterance. This avoids gaps by **adding latency**. At RTF 1.82, sustained playback would have to be about 0.55× speed to keep up; a 0.9× adjustment cannot fix it. Sentence splitting or shorter preroll alone cannot remove the sustained throughput deficit.

The local voice `ko-KR-language` synthesizes to a WAV before handing PCM to the same metered AudioTrack. Its first engine audio callback was 61–152 ms; file completion and resampling account for the remaining wait. It is not streaming model synthesis. Long-utterance PCM backpressure in the reader (135 waits in this run) occurred **after** the entire file was synthesized, so reader completion time must not be mistaken for synthesis time/RTF.

0.9 now prefers that installed, offline-only voice for ordinary replies and non-cooking proactive notices too. Cooking keeps the same voice path; Qwen remains the general-mode fallback when no offline Korean voice is ready. The local choice is pinned to the response epoch before inference, preventing two speech engines from speaking one reply. Completion is owned by the chosen voice; cancellation clears both. Text remains streamed independently and silent mode remains available.

A real general-conversation smoke (`한 문장으로 인사해 줘.` → `안녕하세요.`) observed first text **1.595 s**, first PCM **2.009 s**, write **2.010 s**, head/nonzero meter **2.148 s**, 1 ms buffering and zero starvation. This includes conversational VLM time and verifies the new routing; it is not a matched-text before/after VLM benchmark.

The first combined speech harness timed out in its local section; the two completed Qwen measurements are retained. A separate local run with screen-awake protection passed both sentences. The final general routing run also passed. No inference is made about the incomplete local section.

## Candidate review and FT direction

Primary references checked 2026-10-13; publisher/device-independent capabilities are not tablet benchmarks:

| Area | Candidate / evidence | Assessment |
|---|---|---|
| Visual FT, first | [LFM2.5-VL-450M](https://huggingface.co/LiquidAI/LFM2.5-VL-450M) | Official original checkpoint and FT guidance; adjustable image-token budget in its native processor. Actual tablet binary-task evidence above. Start with compact object/action state output rather than Korean prose. |
| Visual FT, challenger | [Qwen3.5-0.8B](https://huggingface.co/Qwen/Qwen3.5-0.8B) | Same MNN family already runs fast. Blank-scene hallucination requires explicit negative/uncertain training and validation. |
| Visual alternate | [SmolVLM-500M](https://huggingface.co/HuggingFaceTB/SmolVLM-500M-Instruct) | Small, English-focused candidate; not measured here. English event labels are feasible, direct Korean guidance is not presumed. |
| Alternate mobile runtime | [Gemma 3n](https://ai.google.dev/gemma/docs/gemma-3n), [LiteRT NPU](https://developers.google.com/edge/litert/next/npu) | Requires a separate runtime/model/operator validation. MediaTek support exists in LiteRT; it does not establish this VLM's MT6989 NPU compatibility or speed. QNN/Hexagon artifacts are not a drop-in for this tablet. |
| ASR | [Whisper / whisper.cpp](https://github.com/ggml-org/whisper.cpp) | Compare multilingual tiny/base, not `.en`, with Korean kitchen noise and command WER. Android/Vulkan support exists; this Mali path is unmeasured. It cannot accelerate visual inference. |
| Streaming ASR | [Korean streaming Zipformer](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/online-transducer/zipformer-transducer-models.html#sherpa-onnx-streaming-zipformer-korean-2024-06-16-korean) | A genuinely streaming recognition candidate for reducing endpoint wait. Current Qwen ASR is endpointed; the existing short synthetic command was ~0.23–0.25 s after input completion, excluding silence detection. |
| Independent TTS | [Supertonic 3](https://huggingface.co/Supertone/supertonic-3) | Korean-capable on-device candidate for a consistent bundled voice. Not installed or benchmarked; do not copy publisher speed figures onto this device. The verified immediate route is the installed offline voice. |

Proposed FT evaluation contract (not trained or accepted yet):

1. Train the **original** small checkpoint on current-frame object/action states, with explicit absent/uncertain labels. Keep recipe explanation outside the frequent observer path.
2. Split by kitchen/video sequence before sampling frames. Do not train on these benchmark fixtures or leak adjacent frames across train/test.
3. Include irrelevant objects, similar utensils, empty/blurred/occluded frames, small objects, hand motion, changed camera angles and user requests that mention objects absent from the image. A spoken request is never image evidence.
4. Evaluate FP/BF16 and exported INT4 separately with the exact mobile preprocessing/prompt/output contract. Use short English/enum targets first; verify Korean instruction generation separately if later required.
5. Measure capture-to-decision, warm/cold latency, false notices, missed events, temperature and battery over sustained sessions. Suggested observer gate: warm p95 ≤1 s with separately agreed false-positive/recall targets on a substantially larger held-out set. Current smokes cannot establish those thresholds.

## Reproduction and evidence

`CookingProfileInstrumentation` loads only the requested explicit candidate, records image/prefill/decode counters and has no sensors/speaker. Its external `cooking-cases.json` contains bounded case IDs, user input, state, image path and plan flag. `state: "visual-check"` selects the bounded classifier. Example:

```sh
.venv/bin/python scripts/doctor.py
adb -s R54X902CZEK shell am instrument -w \
  -e model LFM2.5-VL-450M-MNN -e pixels 0 -e compact true -e repeats 3 \
  dev.live.offline/.CookingProfileInstrumentation
```

Ignored local evidence is under `.runtime/cooking-study/`: pinned download metadata/checksums, exported artifacts, matrix JSONL, warm/held-out cases, reference-model outputs, speech reports, device E2E reports and build logs. Source changes are in `native/live_engine.hpp`, `native/android_profile.cpp`, the Android policies/player routing, and the bounded optional observer importer/packager. No media, weights, caches or credentials are added to git.

Final APK: `.runtime/cooking-study/live-0.9-arm64.apk`, SHA-256 `0865f043cdbe9ccfa1fcdfd7f27f53196517f56438acea44341193315735b779`. Build/lint, 46 CPU tests, native UTF-8/epoch/evidence checks, v2 signature and seven 16 KB-aligned ELF libraries passed. The real-player amplitude/silence/backpressure/cancel test passed; blocked producer cancellation took 1 ms. These checks establish software behavior, not full human Live acceptance.

The final **unmuted** cooking device run passed its functional checks. The fixture-driven app observation completed in **597 ms** and produced `준비되면 물 한 잔을 준비해 주세요.`; it did not advance the step. Offline instruction PCM/write were 178/179 ms, the synthetic spoken completion command reached text in 193 ms, physical camera frames were fresh and microphone samples continued during output. The earlier run rejected the bad egg plan but failed to observe because `잔` was not yet recognized as a cup target; that omission was fixed and regression-tested. Salad/water planning passed, fried-egg planning was explicitly rejected. The harness PASS is **not** a recipe-quality or human kitchen acceptance label. The app was reopened with ordinary output enabled and capture idle.

## 0.10 Bluetooth playback correction (2026-10-13)

The user could not hear 0.9 despite Bluetooth media volume 8/15. This was a routing failure: AudioTrack uses VOICE_COMMUNICATION, and actual playback device was the tablet speaker; its call volume was 1/15. The prior media-volume readback did not establish the app output route.

0.10 explicitly selects an available Bluetooth communication headset with AudioManager.setCommunicationDevice on API 31+, waits cancellably up to 5 seconds for asynchronous connection before PCM writes, and releases that selection when capture/session ends. Device changes reselect the route; removal of the selected device cancels pending playback. Volume buttons control the call stream while the app owns communication focus. No system volume is automatically raised. API 26–30 retains existing system routing and is not validated by this fix.

Official API basis: https://developer.android.com/develop/connectivity/bluetooth/ble-audio/audio-manager

Physical SM-X926N / Android 16, connected Buds FE: unmuted SpeechLatencyInstrumentation with require_headset=true passed both local Korean sentences. Actual AudioTrack output type 7 (BLUETOOTH_SCO), device ID 111, call volume 8; zero playback starvation. First cold headset connection: first PCM 185ms, write 1211ms, head 1331ms. Next utterance with connection retained: PCM 194ms, write 196ms, head 297ms. This initial connection overhead is separate from speech generation. The user explicitly confirmed hearing both sentences in Buds. This verifies these two audible utterances, not long-duration microphone/echo/reconnect acceptance.

Evidence: `.runtime/cooking-study/speech-headset.json`, `headset-local.log`, `build-headset.log`, `pytest-headset.log`. Build/lint and 46 CPU tests passed. Installed version 0.10-headset-audio; existing model revisions and weights unchanged. App reopened after diagnostic completion.

## 0.11 microphone/caption and 0.12 VLM stall correction (2026-10-13)

Human Buds input reached AudioRecord as Bluetooth SCO, 16 kHz, not silenced. The old fixed RMS onset threshold 0.025 missed most of the first spoken test: only 11/749 20ms frames passed it (the window also includes silence), yielding two 0.82s segments. Full-window ASR was also inaccurate; segmentation alone was not the only observed quality limitation. `SpeechGate` now uses a 0.004 minimum onset with a bounded estimated noise floor and lower continuation threshold. The 600ms endpoint and bounded 6s utterance remain. This is still an energy gate, not a learned speech/noise classifier.

The live screen now shows a microphone input meter and up to two lines of recognized text before answer generation finishes. Longer recognized text is available by tapping the microphone icon. Caption callbacks check response epoch, stale results are dropped, and session/chat clearing erases the transcript. ASR remains endpointed: this is not streaming ASR while the user speaks. The original assistant orb still reacts only to actual output audio; input uses its separate meter.

`MicrophoneInstrumentation` compares the complete 15s window and production gate segments; its raw WAV is transient and deleted. Baseline and fixed diagnostics returned incorrect text in several cases, so their transport PASS must not be described as ASR accuracy PASS. After returning to unrestricted actual Live use, the user confirmed that the input meter moved and the recognized sentence was correct. The fixed diagnostic also verified caption rendering and stale-epoch rejection. Broader noisy-kitchen and Korean ASR acceptance remain incomplete.

The user then reported that ASR text appeared but VLM answers did not. On the affected live process, Java worker was inside `nativeInfer`; the native backtrace showed `clEnqueueMapBuffer` waiting in Mali `osup_sync_object_wait`/pthread_cond_wait. That request carried three image evidence items (two history + current). This is an observed GPU wait/stall, not proof of a permanent driver deadlock or a proven sole cause. New microphone turns continued to invalidate/queue requests while the native worker was occupied.

0.12 bounds the general mobile VLM request to one image: the current image when fresh, otherwise the latest retained historical image still explicitly labeled old. The two stored observation memories and session text memory remain; simultaneous comparison of several stored photos is no longer performed by this path. The model revisions, weights, resolution and OpenCL profile are unchanged. Metadata-only begin/end logs expose image count and response cancellation without recording speech text.

A first repeated-image test was interrupted by new microphone requests and failed; retained as `vision-interrupted.json`. After the user agreed to leave the tablet idle, four consecutive real VLM image questions and offline voice responses passed with history growing to two entries. First text: 5327, 5459, 6209, 6293ms. First audio write: 7393, 7169, 7736, 7797ms. The image was a controlled fixture, not an assertion of current physical camera semantics; these results demonstrate completion, not object-description accuracy or full human Live acceptance. The earlier 597ms measurement is the narrow 450M presence observer, not this 4B answer route.

Validation: build/lint passed; 48 CPU tests passed, including quiet-speech continuation/noise-floor cases and the one-image/current-vs-past/expiry memory bound. Installed 0.12-live-inference on SM-X926N Android 16. Evidence: `.runtime/cooking-study/mic-baseline.json`, `mic-fixed.json`, `stalled-lldb-symbols.txt`, `vision-conversation-final.json`, `build-live-inference.log`, `pytest-live-inference.log`. Raw diagnostic WAV files were confirmed absent after completion.


## 0.13 voice-first cancellation work (2026-10-13)

The user rejected the actual Live retest after 0.12: recognition appeared, but no answer was heard, and they confirmed that they had waited without speaking. Logs showed repeated epoch cancellation. Therefore the controlled four-image PASS above did **not** resolve human Live acceptance. The user then explicitly prioritized microphone → ASR → text reasoning → speech → interruption and deferred images.

0.13 starts with the camera off and passes no image evidence when it is off (including retained photographs). Qwen ASR and Qwen3.5-4B pins/configuration remain unchanged. The existing installed Korean offline voice and Qwen fallback remain; this is still system duplex.

Speech detection now combines the quiet-headset energy gate with WebRTC VAD mode 3 on complete 16 kHz/20ms frames. It requires 160ms sustained detection before invalidating a response, retains 800ms endpoint silence, and bounds an utterance to approximately 15s. After hitting the length bound it waits for silence rather than repeatedly interrupting itself. Capture never waits for model generation. The classifier is compiled locally from https://github.com/wiseman/py-webrtcvad at `e283ca41df3a84b0e87fb1f5cb9b21580a286b09`, pinned in `configs/upstreams.json`; original upstream source is unmodified and its license is included in APK assets. This small dependency is justified because RMS cannot distinguish speech from non-speech noise. It is not speaker identification and cannot reject all nearby voices.

Two initial headset-baseline runs failed. On the second, route logging showed built-in input (type 15) → SCO (7) → built-in within roughly 1.4s; Android audio logs showed SCO connected then disconnected about 0.9s apart. A spurious 1.16s segment recognized “어”; its text answer completed, but the headset output wait expired and invalidated it. This is distinct from a VLM hang. The reason for the physical SCO disconnection is not established; a user question about wearing/connection state remains pending.

Capture now waits for one second of stable input routing, rejects input whose type does not match the requested headset, and discards a partial utterance on a microphone change. The caption shows connection pending, and the input meter does not present an unintended tablet microphone as Buds input. This prevents the observed route-transition input from creating a turn; it does not repair an externally disconnected headset. A late microphone UI update is also guarded with its captured turn ID.

Final real-device **text-output** smoke on SM-X926N / Android 16:

- No images; prerecorded 24kHz PCM fixture explicitly resampled to 16kHz and passed through real ASR → 4B response. First answer token 2524ms from submit, excluding the 800ms endpoint. Completed answer correctly stated that there was no current image. This fixture happens to ask about a cup; no image was supplied.
- A second text question was cancelled after actual generation began. Epoch invalidation took 3ms; the model worker became idle after 59ms. Late PCM and text were rejected, and physical AudioRecord continued capturing.
- A new question then completed correctly: “하나 더하기 하나는 2입니다.” First token 1793ms; no ASR in this third request.
- Real JNI VAD classified 164/224 fixture frames as speech and confirmed one onset. CPU segmentation checks cover short transients, pauses, the long-input cap, and rearming after silence.
- The 15s baseline had zero epoch changes and 238400 captured samples **but `input_ready=false`** because SCO did not stay connected. This is evidence that unready input was gated, NOT proof of quiet/noisy headset detection acceptance. Speech output was disabled only in this explicitly scoped harness. No audible playback/voice-triggered barge-in PASS is claimed.

Build/lint and 49 CPU tests passed; whitespace check passed. Installed APK SHA256: `7a71076b16ad3cdcfcc2992b22ef13aa925b8f17137c07c0ada8488421ece683`. Source evidence: `.runtime/cooking-study/voice-text-conversation.json`, `voice-conversation-quiet-fail.json`, `audio-voice-route.txt`, `build-voice-turns.log`, `pytest-voice-turns.log`. Host doctor measured both RTX3090s before the smoke; inference ran on the tablet's existing OpenCL/CPU paths, not the host GPUs. No model revision, weights, cloud endpoint or user volume changed.

Remaining acceptance: reconnect/wear Buds; validate quiet input and a real Korean sentence with the new classifier; confirm audible answer; interrupt actual playback by voice and confirm immediate cut, fresh ASR/answer and no stale output. Repeat generation/playback interruption and sustained quiet/noisy use before claiming stable Live. The earlier 0.11 human ASR confirmation does not automatically validate the changed 0.13 onset classifier.


## 0.14 connected-headset startup and human interruption (2026-10-13)

The user confirmed quiet surroundings and that Buds were connected to the tablet and worn. A fresh 0.13 run kept SCO connected, but a false onset about 1.9s after SCO routing produced “그냥.” and “알겠습니다.” during the quiet baseline (`voice-headset-confirmed-fail.json`). Therefore the earlier disconnect is not a sufficient explanation of all failures.

0.14 resets the speech classifier when the input route changes and feeds it during startup rather than leaving it cold. Input is armed only after at least 3s on the same route and 200ms of non-speech. This affects connection startup, not every utterance; the existing 160ms confirmed onset still performs cancellation during conversation. Transition/initial classifier behavior is a supported working explanation of this case, not proof that all noise false positives are solved. No model, inference configuration, volume or output route policy changed.

The full unmuted `VoiceConversationInstrumentation` passed on SM-X926N, Android16, with actual input ready:

- 15s baseline: zero epoch changes, 239040 captured samples, `input_ready=true`; physical capture remained on SCO. Audio system snapshot showed SCO active and an unmuted VOICE_COMMUNICATION AudioTrack started on device111.
- Real fixture ASR plus no-image answer: first text2483ms, first audio write3264ms from submit (endpoint excluded).
- Second text question: first text1756ms, first audio2490ms; cancellation of actual playback took70ms. Continued capture and rejection of late PCM/text passed.
- Resumed text question: first text1821ms, first audio2304ms, completed “하나 더하기 하나는 두입니다.”
- Build/lint,49CPUtests,whitespace checks passed. APK SHA256 `fa6b0c3a4d5081448d43ba6b7563763df1b083a855be6459430071119aa04a3b`. Existing model/upstream revisions remain unchanged. Doctor measured host GPUs before the device model run; inference stayed on the tablet.

At 2026-10-13T19:09:20+09:00, after restoring the normal app and enabling voice-only capture, the user explicitly confirmed that speaking over the answer stopped the old voice and produced a new answer. They then clarified that recognition quality is weak and the interaction feels like cancelling and answering again, not natural full duplex. This is one functional human barge-in confirmation, not the 10-interruption/5-minute/ASR-quality acceptance required for full Live.

That perception matches the architecture: continuous AudioRecord runs during playback, but the onset invalidates the entire answer epoch; ASR waits for the 800ms endpoint, then the same model worker performs ASR → full new text reasoning. Local voice starts from the completed answer. The recorded3264ms submit-to-audio plus endpoint is roughly4s after end of speech in this fixture scenario, not a directly measured human end-to-end latency. The transcript is endpointed, not streaming. A pure resume of the interrupted answer is not implemented. Automated number enumeration also produced a promise to enumerate rather than the requested list, so audio transport PASS is not general instruction-following quality PASS.

## 0.15–0.16 incremental voice implementation (2026-10-13)

The user requested incremental ASR, reasoning while speaking, sentence-level voice output, improved recognition and real-device checks. Android now has separate bounded ASR/answer workers, cumulative partial transcripts, hidden speculative answers committed only on matching final text, and an ordered sentence TTS queue. The 800ms endpoint guard remains; a 400ms-silence candidate can reuse the identical PCM recognition when speech does not resume. Pre-onset audio retention increased from 200 to 400ms to preserve quiet initial syllables. This is incremental delivery using repeated bounded Qwen ASR decoding, not a native streaming encoder/KV implementation or native duplex. All existing ASR/VLM/TTS weight revisions and CPU/OpenCL assignments remain unchanged.

Controlled runs on the same SM-X926N, Buds route, camera off:

| Run | Partial updates before endpoint | Draft reuse | Endpoint signal → first text / first audio write |
|---|---:|---:|---:|
| Initial streaming implementation | 5 | 0 | 3272 / 4028ms |
| Replace stale drafts | 5 | 1 | 2480 / 3293ms |
| Shorter assistant prompt | 5 | 1 | 2403 / 3248ms |
| Endpoint candidate/cache | 6 | 1 | 2255 / 3100ms |

The last run pads the spoken fixture with 400ms silence and submits its final signal 400ms later, matching the candidate/cache design. Earlier harness runs used a 650ms pause and repeated the same PCM, so these are engineering observations, not a controlled latency distribution. The endpoint metric excludes the preceding silence guard, and AudioTrack write is not acoustic onset at the ear. Do not compare it directly to human speech-end latency or claim the 1.5s/2.5s Live target was met. ASR snapshot delays reached approximately1.4s while CPU recognition and GPU speculative prefill overlapped; cancelled prefill is still cooperative only after the initial model response call. These remain performance bottlenecks.

All four runs emitted three sentences; actual first audio preceded completion of the answer generation. No speculative audio was emitted before commitment, and a cancelled final ASR did not revive output. The generated cooking text was inspected: an early run repeated “양파와 양파”, so sentence-delivery PASS is not cooking-accuracy PASS. Later shortened-prompt responses avoided that particular duplication; broader reasoning quality is unverified.

The subsequent **real-microphone quiet test failed**: a spontaneous onset produced “이미지.”, and the user explicitly confirmed that they had not spoken. Preserve `.runtime/streaming-study/voice-quiet-failed.json`; the earlier streaming fixture PASS did not resolve that acoustic problem.

Version0.16 replaces production WebRTC classification with the small Silero model bundled by the already pinned MiniCPM demo revision, SHA-256 `591f853590d11ddde2f2a54f9e7ccecb2533a8af7716330e8adfa6f3849787a9`. The unmodified ONNX model runs on one CPU thread through ONNX Runtime Android1.23.2; runtime, model and license hashes are in `configs/android-vad.json`. No weights enter git. The extra dependency is justified by the observed quiet-headset misclassification. The unused trial MNN conversion was not deployed. Official model/runtime references: https://github.com/snakers4/silero-vad and https://onnxruntime.ai/docs/get-started/with-java.html.

The reference model classified 0/312 digital-silence windows as speech and96/140 Korean-fixture windows as speech at probability0.65. On the physical tablet, the same fixture produced one sustained onset, with detector inference averaging429–431µs per32ms window (max2932–5269µs in two runs). This is a real model/latency smoke, not noisy-headset or word-error-rate acceptance. Two microphone runs were blocked when the screen entered Dozing; preserve those failures separately from classifier failures. The user then explicitly reopened the screen for a rerun.

CPU/state tests:50 passed. Android assemble/lint passed, with hash-verified model/runtime assets. ARM64 ONNX Runtime LOAD segments use16KB alignment. Raw ASR WAVs remain transient and are deleted after each recognition. Detailed private device outputs remain under ignored `.runtime/streaming-study/`.

### 0.16 connected-headset rerun

After the user kept the screen awake, `.runtime/streaming-study/voice-neural-pass.json` passed: input_ready=true,238720 microphone samples during the15s quiet window, zero epoch changes/false turns, real ASR fixture → first text2174ms / first audio write3036ms. A separate text enumeration returned the requested numbers instead of a promise. Actual playback pause took64ms, the inference worker was idle by65ms, late PCM/text were rejected, capture continued, and the new “하나 더하기 하나” response started audio after2174ms. These timings cover controlled submissions/button cancellation, not human voice onset or a statistical latency distribution. The detector's real fixture mean in this run was241µs per32ms window. The broader5-minute/10-interruption and human Korean recognition-quality checks remain separate.

Historical 0.14 evidence: `.runtime/cooking-study/voice-headset-startup.json`, `voice-headset-startup.log`, `audio-startup-verify.txt`, `build-voice-startup.log`. The later 0.15–0.16 implementation and acceptance limits above supersede that version’s implementation status.


### Human Korean recognition failure and matched-audio diagnosis

The user rejected the live 0.16 transcript: only “잠깐만” appeared and the following question was missed. Exact stop-only phrases now stop the answer without a spoken acknowledgment and keep listening; a combined stop+question is not swallowed. This logic passes a JVM test; a new human interruption check is still required.

Two explicit Buds diagnostics were performed, with raw audio deleted from tablet and host immediately after comparison. First utterance target was “하나 더하기 하나는 얼마야”. The 1.84s selected segment produced Android “아나도 알게 안오면” and original pinned Qwen3-ASR-0.6B “아나도 알게 한 거야.” on RTX 3090 GPU0. Peak0.905/RMS0.123 excludes near-zero input but does not establish acoustic quality.

A second diagnosis retained two seconds before detected onset plus five seconds after it, independently of the endpoint gate (bounded seven seconds). The selected2.16s segment produced “하나도 하기 하나는 얼마나”; the wider7s window produced Android “하나도 여기 하나는 얼마야.” and original Qwen “하나도 하기 하나는 얼마예요.”. Thus neither Android quantization nor VAD clipping alone explains the error. The wider window does not prove all future segmentation correct. Original reference inference took988ms, Android selected/wide412/799ms. These are single examples, not a CER benchmark.

The initial Korean streaming Zipformer candidate (k2-fsa 2024-06-16, revision ba6078bca4daf3f0dd37f79d0ab505af71df14a6) decoded the4.48s fixed fixture in215–219ms with update calls up to20ms, but had particle/spacing errors and returned empty on both human windows. A batch-vs-incremental fixed-fixture check returned the same text, so the empty human result is not explained by the batch diagnostic alone. It is not promoted to production.

Runtime investigation: Sherpa1.12.15 JNI could not link against the existing ONNX Runtime1.23.2 (versioned OrtGetApiBase symbol). Use the pinned official Sherpa1.13.8 static-runtime ARM AAR instead; no unsafe shared-library replacement. `configs/android-asr.json` records candidate and runtime hashes. The newer [Korean72M Zipformer](https://huggingface.co/kangkyu/icefall-asr-ko-streaming-zipformer-72m) chunk16 candidate is under evaluation at revision db24b58d22736349eaeb34cc181ad0f3debf9903; its published CER is author-reported, not our device or Buds acceptance.


### Whisper installed on the tablet; not promoted to Live ASR

The user explicitly preferred transferring the local model to the tablet over a PC-dependent microphone path. This was performed: Whisper large-v3-turbo ONNX INT8 weights (1,036,613,791 bytes including tokens) were hash-verified in private app storage; GGML Q5_0 (574,041,195 bytes) and unquantized FP16 (1,624,555,275 bytes) were also deployed for real tablet inference. No audio went to a cloud service.

Pins: original PC `openai/whisper-large-v3-turbo` revision `41f01f3fe87f28c78e2fbf8b568835947dd65ed9`; [Sherpa ONNX export](https://huggingface.co/csukuangfj/sherpa-onnx-whisper-turbo) revision `2ca6ff69fc878651b770880507669577ac41c2ff`; [GGML weights](https://huggingface.co/ggerganov/whisper.cpp) revision `5359861c739e955e79d9a303bcbc70fb988958b1`; whisper.cpp1.9.5 commit `d1be6fde11ac6e0407606b4e42fe72d34add8037`. Artifact hashes and source/header pins are in `configs/android-whisper.json` and `configs/upstreams.json`. Existing host Qwen/MiniCPM model revisions are unchanged.

Hardware: Samsung SM-X926N, MT6989, Mali-G720-Immortalis MC12, Android16; PC reference RTX3090 GPU0. Availability was measured before model runs. Buds input was confirmed in AudioFlinger as16kHz mono PCM16, Bluetooth SCO, VOICE_COMMUNICATION.

Fixed4.48s Korean fixture measurements (single runs unless noted; not p95 or an acoustic end-to-end measure):

| Runtime / configuration | Inference / compute time | Observed result |
|---|---:|---|
| PC original Whisper, FP16, GPU0 | 689ms | Exact fixture text |
| Tablet ONNX INT8,4 CPU threads | 2340ms | Missing syllables/words |
| Same, requested NNAPI | 2326ms | Same errors; runtime explicitly fell back to CPU |
| Same,30s waveform context | 6179ms | Same errors; padding alone did not fix it |
| Tablet GGML Q5,Vulkan, full context,beam5 | encoder40602ms; total42166ms incl load | Exact fixture |
| Q5,Vulkan,context384,greedy,2 inputs | encoder11353ms mean; total23840ms for2 incl load | Particle error remained |
| Q5,CPU,context384,generic ARM | encoder10406ms; total10861ms incl load | Particle error remained |
| Q5,CPU,context384,FP16/DOTPROD ARM | encoder7283ms; total7689ms incl load | Particle error remained |
| Q5,Vulkan,context256,flash attention off | total14672ms incl load | Repeated sentence; rejected |
| FP16 original-precision GGML,Vulkan,full context,greedy | encoder26027ms; total27736ms incl load | Exact fixture |

The Vulkan log actually selected `Mali-G720-Immortalis MC12`; NNAPI requested-provider text alone was misleading. Its prebuilt runtime logged “Current API level21 Fallback to cpu!”, a build restriction despite Android16 hardware. Shader compiler lacked several optional cooperative-matrix/integer-dot extensions; no NPU performance claim is made. Vulkan and CPU configurations differ in precision/context/beam count, so this table is not a controlled backend-only speed ratio.

Human comparisons are more important than the fixture. On one exact7s wider Buds recording, tablet Qwen gave “잠깐만 하나 더 하게 하는 얼마요”; tablet ONNX Whisper gave “잠깐만 하나 더 하기에는 얼마야” in2743ms (selected2.48s segment1876ms, worse missing words). Original PC Whisper on the identical PCM gave the requested “잠깐만 하나 더하기 하나는 얼마야” in606ms. Q5 CPU/context448 gave “잠깐만 하나 더 하게 하는 얼마야”, total7223ms. Thus uploading and successful execution are established, but Android conversion/quantization/context fidelity and Korean accuracy remain unresolved. Original-precision Vulkan was checked on the fixed fixture only, not this later human recording. All diagnostic audio copies were deleted after comparison, including temporary CLI input and host copies.

The72M Korean Zipformer candidate also failed the human comparison (“그러면어.” / “잠깐만오케이말해.” on selected/wide input), despite20–23ms fixture update calls. Neither it nor Whisper replaces the production recognizer. Candidate Java classes, libraries and bundled Zipformer assets are debug-only; large Whisper weights remain outside the APK. The0.16 Qwen streaming prototype and stop-only handling remain installed.

Conclusion: tablet deployment is possible and was demonstrated. The large original Whisper does not meet Live latency on this measured device/runtime; the faster INT8 route did not preserve the needed human transcription quality. Streaming UX/cancellation correctness does not compensate for that accuracy gap, and full Live acceptance remains failed/pending.
