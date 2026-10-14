# Android 0.17 mock voice and system audio policy

Date: 2026-10-14 (Asia/Seoul). This report separates software/fixture verification
from human microphone, audible playback and full Live acceptance.

## Delivered behavior

- Debug app top waveform button: enter a short Korean sentence or select a preset,
  then send it as synthetic voice. The text is synthesized locally and only PCM
  reaches production Capture/VAD/ASR. It is not injected as a recognized transcript.
- Source remains explicitly in mock mode between turns, indicated by `모의 ·` and
  an M badge. AudioRecord continues to be drained; ambient microphone bytes are
  excluded from recognition while mock mode is active. `실제 마이크로` restores
  the system microphone path. Response playback can be interrupted by a new mock
  utterance through the real detector.
- One utterance, 160 characters/12 seconds, 400ms lead-in and 1200ms trailing
  silence; no fixture backlog. Offline synthesis has a 30s deadline. No audio
  fixture or model weights were added to git. Temporary synthesis WAVs are deleted.
- Latest user instruction: output follows system media routing and volume; capture
  requests the system default source. Removed app-selected SCO/LE/headset routes,
  communication mode, voice-call volume-key routing and route connection waits.
  Start/stop do not call setMode or change system volume. Input and output devices
  are independently chosen by Android; output to earbuds alone does not prove
  the earbuds microphone is selected.

## Environment and revisions

- Authorized device: Samsung SM-X926N / Galaxy Tab S10 Ultra 5G, Android 16,
  MT6989 / Mali-G720, approximately 15,582,372KiB physical memory reported by
  `/proc/meminfo`. This is not the final Galaxy S 12GB acceptance device.
- external-dev host doctor measured two RTX 3090 24GB GPUs before device runs;
  first measurement: GPU0 free 20,742MiB, GPU1 free 24,108MiB. These host GPUs were
  not used for inference in this change; device inference uses the installed
  MNN bundle. No unrelated GPU process was stopped.
- Installed bundle size: 5,800,727,393 bytes; fixed MNN revision
  `9f097c5fdd48e0cac4468d4cc53a828794d96888`, runtime patch SHA256
  `58142f499dad20791d9df221b776145fc898d58fa97d91d1aa729dc4fe4272c2`.
- Base model provenance from the installed manifest, unchanged by this work:
  Qwen ASR `5eb144179a02acc5e5ba31e748d22b0cf3e303b0`, Qwen3.5 VLM
  `851bf6e806efd8d0a36b00ddf55e13ccb7b8cd0a`, Qwen TTS
  `85e237c12c027371202489a0ec509ded67b5e4b5`. The existing exported/quantized
  bundle is used; these are not claims of newly running raw upstream checkpoints.
- Input synthesis and tested output speech use installed offline Korean Android
  TTS. Production ASR remains Qwen; no model/dependency revision was changed.

## Software checks

- `.venv/bin/python -m pytest`: **51 passed**, including synthetic input bounds,
  resampling, silence isolation, single pending utterance, clear/disable and
  malformed/nonfinite input checks, plus existing protocol/state checks.
- `assembleDebug lintDebug`: **passed**. SDK metadata and existing lint warnings
  remain. A debug-only route-inspection call initially needed an API29 guard;
  the guard was added and lint rerun successfully for minSdk26.
- Static inspection found no production calls to setCommunicationDevice,
  clearCommunicationDevice, setPreferredDevice, setMode, setStreamVolume or
  setVolumeControlStream after the system-policy change.

## Device runs and retained failures

- First run: failed to start capture because the app was not ready/in foreground.
  Second run waited for readiness and exposed a sleeping/locked foreground state.
  No mock conversation acceptance follows from either run.
- Third run, before the system-audio policy update: production VAD accepted paced
  input, partial/final Qwen ASR ran, an actual answer began playback, and a second
  mock voice triggered barge-in. Initial send-to-onset 806ms, first committed
  response text/audio write 2841/4409ms, second send-to-barge-in 1018ms.
  Send metrics include offline synthesis/leading silence; output metrics start
  at the app's final-ASR boundary and are not human end-of-speech latency.
- Preserve the third-run semantic error: input `오이 샐러드 만드는 방법을 자세히 알려 줘.`
  became `오이슬라드 만드는 방법을 자세히 알려줘.` and the VLM described a fictional
  character. A working mock transport does not establish Korean recognition or
  answer correctness.
- Third run did not complete: the device left foreground during the second turn;
  power history recorded a power-button sleep. The app correctly stopped capture
  and cleared session state. Its test failure remains recorded, not counted PASS.
- Final system-policy run: **transport/lifecycle PASS** on the installed 0.17 APK.
  Real input synthesis, paced VAD/partial/final ASR, answer playback, a second mock
  utterance during playback, late PCM rejection, continued recording, physical
  source restoration and pending-synthesis cancellation on stop all completed.
  System audio mode stayed NORMAL (0), input source DEFAULT (0), output usage
  MEDIA (1), and both requested preferred devices were null.
- Actual OS-selected input type 15 was the tablet built-in microphone; output
  type 8 was Bluetooth A2DP. This run confirms system-selected routes, not headset
  microphone selection or user hearing confirmation. No system volume was changed.
- Final timing: initial send-to-onset **841ms**; first response final-ASR-boundary
  to text/audio write **2811/4364ms**; second send-to-barge-in **991ms**; second
  response text/audio write **2763/3596ms**. Capture drained **320,000 samples**,
  with **150,236 synthetic samples** including fixture padding across the two
  completed inputs. These are single-run figures, not p50/p95 or native duplex.
- Semantic acceptance remains **failed/unresolved**. The first ASR error and
  fictional-character answer repeated. The second question was recognized as
  `잠깐만 하나 더 하기 하나는 얼마야`, but the model incorrectly answered `10원입니다`.
  The harness PASS checks input/dispatch/playback/cancellation, not the truth of
  generated answers. These failures must not be hidden by the functional PASS.

## Evidence and remaining acceptance

Local ignored evidence: `.runtime/mock-voice/` contains doctor snapshots, installed
model manifest, build/test logs, earlier run JSON and final instrumentation output.
`MockVoiceInstrumentation` does not manually call ASR, text submit or endpoint
callbacks; it invokes the same synthetic-input UI action.

Human microphone/noise/earbud input accuracy, audible user confirmation, device
route changes while speaking, sustained 5-minute/10-interruption performance,
thermal behavior and final Galaxy S resource acceptance still require separate
evidence. Neither a mock PASS nor a playback-head counter establishes those.
