# Android 0.8: runtime cooking companion — 2026-10-13

Latest Android 0.9 evidence: [small observer, speech generation/playback diagnosis and FT review](android-realtime-model-study.md). Historical results below retain their original measurement scope.


## Result and boundary

The authorized SM-X926N now has `0.8-cooking`. No dish is built in: a user supplies the goal, ingredients and exclusions at runtime. Current-step controls, explicit completion memory, pause/resume, recall, a foreground timer and camera-initiated notices are implemented. Cooking uses the existing on-device Qwen ASR/OpenCL VLM with an installed offline Korean Android voice. Other modes retain Qwen TTS. This is system duplex, not native duplex.

The final real-device engineering smoke passed. **Human kitchen Live acceptance and general cooking correctness have not passed.** Camera judgment still takes about 5.8 seconds. The model sometimes gives awkward or incomplete cooking directions. Faster playback cannot resolve those model-quality issues.

## Hardware and unchanged models

- Galaxy Tab S10 Ultra 5G / SM-X926N, Android 16/API36, MT6989 / Dimensity9300+, about16GB RAM. VLM uses Mali-G720-Immortalis MC12 OpenCL; ASR remains CPU. No Qualcomm/QNN/NPU change.
- Same installed model bundle, image resolution and upstream revisions. MNN `9f097c5fdd48e0cac4468d4cc53a828794d96888` with the existing local patch; no upstream source edits this session.
- Qwen ASR revision `5eb144179a02acc5e5ba31e748d22b0cf3e303b0`; Qwen3.5-4B `851bf6e806efd8d0a36b00ddf55e13ccb7b8cd0a`; installed Qwen TTS `85e237c12c027371202489a0ec509ded67b5e4b5` retained outside cooking. Configs and installed manifest remain the revision authority.
- Android voice reported `ko-KR-language`, non-network/not-missing voice flags; device logs identify Google's embedded Korean synthesizer. No voice download or cloud-model call was added. Voice readiness is device dependent; missing offline Korean voice falls back to text.
- `scripts/doctor.py` ran before each physical-model run. Final host snapshot: two RTX3090 24GB,21717/24108MiB free; host GPUs were not used for this inference. Unrelated device/emulator/GPU work was not stopped.

## Final measured run

Evidence: ignored `.runtime/cooking-report-accept.json` and `.runtime/cooking-device-accept.log`, `CookingInstrumentation` on the actual installed APK. These are single-run engineering timings, not p95. Final output was deliberately muted in the debug harness after the user's volume request. AudioTrack playback-head and PCM-meter progress verify the output path, not audibility or acoustic echo cancellation.

| Measurement | Result | Scope |
|---|---:|---|
| Runtime salad plan, no salt | 4.409s | Real VLM, complete JSON |
| Runtime egg plan, no cheese | 4.225s | Real VLM, complete JSON; quality caveat below |
| Completion command → text | <1ms timer resolution | Local stored step, no generation |
| Completion command → first PCM / first AudioTrack write | 263 /264ms | Installed Korean TTS, no speech slowing |
| Spoken “다 했어” → next-step text |250ms | Real ASR on a synthesized fixture; excludes speech duration and600ms microphone endpoint silence |
| Recall during actual microphone capture → first write |65ms | Warm voice;3200 microphone samples received while output progressed |
| Current-image check → camera-initiated notice |5.804s | Real VLM on EPIC VISOR fixture |

Different runtime goals were used, and previous plans were replaced. The fixture-only goals are test inputs, not app defaults. The current-step recall matched the stored step. The final camera notice was “컵이 손에 있어 물 담기 준비가 보입니다.” The fixture `P01_03_frame_0000003060.jpg` visibly contains a hand holding a mug, so that narrow observation is supported. The notice appeared in chat without a new question, and did **not** advance the step. Physical camera freshness was separately verified; this is not evidence that a person cooked in front of the camera.

## Correctness and failure history

- Python46 tests pass. Runtime-state tests cover stale plan revisions, explicit completion, pause/resume/back/recall, timer one-shot/reset, bounds, notice cooldown/deduplication and fresh/changed-frame scheduling.
- Final ARM64 build and lint pass. Native UTF/evidence/stale-epoch policy tests pass. APK v2 signing,16KB ZIP alignment and all7 ELF PT_LOAD alignments pass.
- Real long-WAV regression, exact/once-quoted notice parsing, cancellation-to-zero meter, live microphone continuity and camera frame freshness pass in the final Android harness.
- Initial device run had no output; after waking/dismissing the unlocked keyguard, the short-voice run succeeded. Initial foreground/focus state was not captured, so a definitive root cause for that first run is not claimed.
- A later, reproducible long-voice failure was integer overflow in `(i * sampleRate)` during resampling, before conversion to double. Fixed by promoting before multiplication;10-second WAV and longer real device synthesis now pass.
- An early `NOTICE:` protocol was ignored by the model. JSON is now required. The model sometimes returns exactly one JSON-encoded string containing the object; only that wrapper is normalized, then key/type/length checks apply. Arbitrary text, booleans, extra keys, arrays and nested wrappers are rejected. No invented positive result replaces a parse failure.
- An English planning prompt produced malformed JSON and unnecessary ingredients; it was rejected and not shipped. The final Korean planner produced a usable salad sequence. The egg example still says “볶아 주세요” and has awkward phrasing. Recipe completeness, heat/timing guidance, exclusions across many dishes and safe clarification remain unaccepted.
- An earlier plan mentioned absent photos, which the camera model incorrectly reused as current evidence. Planning no longer injects a “no photo” observation; the observation prompt labels task instructions separately from current-image evidence. The final narrow fixture passed, but broad provenance accuracy is not established. Prior0.7 memory attribution failures remain relevant.

## Runtime safeguards and remaining work

Camera checks are opt-in with cooking, live capture and camera enabled. A tiny pixel-change gate schedules at a minimum2.5s interval, with a10s unchanged-scene heartbeat; inference duration still limits actual cadence. Input older than3s and results older than6s are rejected. User speech, epoch/revision changes, camera stop and invalid output suppress late notices. Notice cooldown is12s, and the model cannot mark steps complete. Working state is bounded and cleared on opt-out/end/background; timers only operate in the foreground session.

No automated doneness/temperature/food-safety determination or physical action is implemented. Remaining acceptance: diverse real cooking scenes, irrelevant-scene silence, noisy-kitchen ASR, interrupting with the user's own voice, audible long speech/echo behavior, thermal endurance, and substantially faster visual intervention with equal grounding accuracy. Do not describe the entire interaction as fully realtime from the fast local-control result.

## Installed artifact and device handoff

- APK: `.runtime/android-profile/live-0.8-arm64.apk`
- SHA256: `d598eea5226b489cb6ae026d1d31c9cb5ad2c4ace540ec01c195c0311a0b4e95`
- User requested device volume only; application auto-mute/earbud detection was explicitly not added. Device media volume is0. The communication stream refuses index0 on this tablet (minimum1), so subsequent debug output was additionally muted at its AudioTrack for verification. Ringer/alarm settings were not changed.
- Session cleaned after instrumentation; final handoff is the installed app idle with capture stopped. Screen timeout remains300000ms. Models stay installed; no commit/push or cloud deployment was performed.
