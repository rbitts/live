# Live 합격 기준과 증거

2026-10-14 Android0.21 수정 후 같은 시나리오5회: 인식30/30, 십 초 변경5/5,
타이머 실행15/15, 결과 재진입5/5, 샐러드 사전 답변 기준5/5. 모의 입력은 남성
음성으로 함께 재생하며 시스템 오디오 정책을 따른다. 입력 화자와 VLM 파생본이
변경됐고 일반 답변 품질·사람 Live 한계는 남는다. [수정·비교·실패 기록](../reports/android-voice-fix-validation.md).

2026-10-14 Android0.18 고정 시나리오5회·30발화 측정: 인식25/30(83.3%),
타이머 실행10/15(66.7%), 결과 안내·보류·재전달5/5, 일반 답변0/5.
“십 초→시초”와 요청 재진술 실패가 매번 재현됐다.
[반복 측정·원본·판정 기준](../reports/android-repeated-voice-evaluation.md). 단일 합성 화자 결과이며 전체 Live 합격이 아니다.


Android 0.18은 공통 작업 메모리와 도구 후속 안내를 사용한다. 일반 대화에서 음성으로
타이머를 시작·변경·취소할 수 있고, 완료 시 새 질문 없이 안내한다. “잠깐만”은 안내를
보류하고 “계속해”는 미전달 결과를 다시 읽는다. 공통 코어와 개발 에이전트 어댑터,
부분 ASR 처리 변경 및 범위는 [구현 계약](working-memory.md)를 따른다.


## 모의 음성 입력 (0.17, 2026-10-14)

`MockVoiceInstrumentation` uses the app's offline synthetic-input function and
real paced Capture/NeuralVad/ASR/VLM/AudioTrack path. It checks a first spoken
question, a second synthetic question during actual response playback, old-epoch
PCM rejection, ongoing capture, return to real microphone mode and cancellation
of pending input on stop. It must not use direct text submit or manually signal
speech endpoints. Generated input is labeled mock; it is not a human ASR or
headset/noise-quality result. Model-response quality is inspected separately from
transport PASS. The send-to-barge-in metric includes synthesis and leading input
silence and is not the speech-detection-to-stop metric. Results and failures:
[Android mock voice verification](../reports/android-mock-voice-validation.md).

Android 0.9의 제한적 소형 관찰 모델, 음성 생성/버퍼/재생 분리 측정 및 FT 후보는 [실시간 모델 비교](../reports/android-realtime-model-study.md)를 따른다. 597ms 사물 확인과 일반 대화의 2.148초 재생 시작은 서로 다른 입력·경로다. `CookingInstrumentation`의 PASS에는 잘못된 달걀프라이 계획의 거부가 포함되며, 범용 조리법 합격이 아니다. 물리 카메라 freshness/음성 중 마이크 수집과 데이터셋 fixture 추론을 구분하고 실제 주방 정확도·음향·발열 acceptance는 미완료로 유지한다. `SpeechLatencyInstrumentation`은 생성시간과 first PCM/write/head를 따로 기록하며, `AudioBufferInstrumentation`은 소프트웨어 PCM/취소 검사다.

통과 상태는 reports의 실행 결과를 기준으로 판단한다. 아래 목표값은 제품 보장이나 이미 측정된 값이 아니다.

| 수준 | 필요한 증거 |
|---|---|
| 소프트웨어 테스트 | 끼어들기, 오래된 epoch 폐기, 입력 유지, 버퍼 제한, 세션 정리, 프레임 신선도 테스트 통과 |
| 모델 smoke | 고정 revision의 실제 모델 로드 및 음성/영상 입력→텍스트/음성 출력 성공 |
| paced duplex | 출력 도중 입력을 실제 전달하고 처리하는 동시 타임라인, 음성 응답, 지연 분포, 큐 증가 여부 |
| 사람이 사용하는 Live | 실제 마이크·카메라·스피커 또는 이어폰으로 한국어/영어 시나리오 반복, 에코·중단·재연결·기억 구분 확인 |

## 동일한 시나리오

1. 빨간 컵과 흰 컵을 보여 주고 “내 컵은 어디 있어?”라고 묻는다. MiniCPM에서는 영어로 같은 뜻을 말한다.
2. AI 음성 재생 중 “아니, 빨간 컵”이라고 끼어든다.
3. 재생 중단 및 새 입력 수신을 확인한다. 이전 응답 청크가 다시 재생되면 실패다.
4. 컵을 치우고 “아까 어디 있었어?”라고 묻는다. Qwen 경로는 마지막 관측과 현재 관측을 구분해야 한다.
5. 카메라를 끄고 현재 위치를 묻는다. 관측하지 않은 현재 위치를 단정하면 실패다.
6. 기억을 지우거나 재접속한다. 이전 개인화·프레임이 남으면 실패다.

## 초기 목표

- 클라이언트 음성 감지→실제 재생 중단: p95 ≤250ms.
- 발화 종료→첫 음성: p50 ≤1.5초, p95 ≤2.5초. 문장 TTS/ASR의 현재 구조가 이 값을 넘으면 성능 미달로 기록한다.
- native 1초 청크: steady-state cost_all_ms p95 ≤1000ms, 5분 이상 큐 누적 없음.
- 두 경로 모두 10회 이상 끼어들기, 5분 대화, 장치 AEC 오검출 횟수 기록.
- Qwen 한국어 인식·답변·발음과 장면 기억은 사람이 검수한다. 합성 음성 재인식은 실제 사람의 품질 평가를 대체하지 않는다.

자동 replay는 실제 GPU와 통신 흐름을 점검하는 smoke다. 서버 출력 수신은 실제 스피커 재생과 동일하지 않다. mock/unit test만으로 full-duplex Live 확보를 선언하지 않는다.

## 모바일 OJT 추가 검증

- [작업표](mobile-ojt.md)의 R/L/Q/M/E 단계별 증거를 따로 남긴다. CPU 데이터 준비와 ticket 검사는 학습/모바일 acceptance가 아니다.
- 지연 frame, ticket replay/교체, camera-off/reset 뒤 도착을 검사한다. 실기기에서는 ticket 이후 실제 새 camera callback인지 확인한다.
- 동일 frozen test에서 base/LoRA/merged/PTQ/QAT/exported 모델 품질과 실기기 자원·속도를 비교한다. 모델 세 개의 변환/추론을 각각 검증한다.
- 5분 이상 실제 모바일 대화, 10회 끼어들기, 실제 재생 지연/공백, memory/thermal, 권한·audio focus·Bluetooth·재연결을 기록한다.
- 2026-10-12 확정: 최종 타깃은 최신 Galaxy S의 완전 온디바이스다. [물체 위치·최근 관측 QA 명세](mobile-qa-spec.md)의 QA01~12를 수행하며 로컬 모델 설치 후 네트워크를 차단한 cold start가 필수다. 서버 fallback, 온라인 로그인/추론 또는 필수 런타임 다운로드가 있으면 최종 합격이 아니다.
- 2026-10-12 후속 확정: 물리 RAM 12GB 장치에서 일반 CPU/GPU 양자화를 검증한다. OS/카메라/GPU 공유 메모리를 포함한 자원 여유와 load/prefill/동시 TTS peak를 기록한다. 초기 앱 작업 예산 약 8GB는 실측 전 내부 목표다. 16GB SKU/RAM Plus/NPU 가속으로만 통과한 결과는 현재 타깃 통과로 대체하지 않는다.
- [공개 데이터 계획](mobile-datasets.md)의 근거 제공 QA와 E2E 기억 검색을 구분한다. 비상업 데모 조건/원본 split/한국어 검수/파생 예제 누출을 기록하고 PTQ calibration/QAT에는 train만 사용한다.
- 공개 데이터 준비는 공식 split 역류, source alias 중복, 검수 전 레이블, 검수 이후 응답/이미지 교체를 거부해야 한다. HTTP 범위 다운로드는 전체 ZIP으로 확장하지 않으며 누적 byte 상한과 CRC/이미지 크기를 검사한다. `--allow-unreviewed` 통과는 데이터 품질 합격이 아니다.
## PC 온디바이스 사전 검증 결과 (2026-10-12)

[mobile-pc-validation.md](../reports/mobile-pc-validation.md)에 실제 LoRA/QAT·INT4/INT8·오프라인 C++ E2E·Android 빌드 증거를 기록했다. PC의 314초/10회 생성 취소·재개는 통과했지만 CPU TTS RTF 3.11~3.52와 QA03 모호한 대상 구분은 실패다. 실기기/human Live 합격을 선언하지 않는다. QA09의 모델 단독 추측은 앱의 근거 없음 정책으로 차단한다.

Android APK 검사에는 Java 관측 상태, 모델 ZIP 변조·경로·누락 거부, virtual camera/microphone 입력과 무음 재생 동시성, manifest의 INTERNET 권한 부재, APK 서명/16KB ELF 및 ZIP 정렬을 포함한다. 가상 입력과 실제 사람의 음향 검증을 분리해 보고한다.

## Android 앱 흐름 검증 (2026-10-13)

0.3 아이콘 UI의 최종 APK, 작은 화면·툴팁 및 모델/취소 재검증은 [아이콘 UI 검증 보고서](../reports/android-icon-ui-validation.md)에 기록한다.

`LiveWorkflowInstrumentation`은 온보딩/탭 이동/기억 삭제/늦은 UI·PCM 거부와, 선택적으로 실제 ZIP 설치 → Android JNI ASR/VLM/TTS → 마이크 동시 수신 → 텍스트 전용 응답 → background 정리를 실행한다. 모의 응답으로 실제 모델 합격을 대체하지 않는다. 다른 작업의 공용 에뮬레이터를 점유하지 않고 전용 AVD를 사용한다. 결과·시간·환경·미달 사항은 [Android 검증 보고서](../reports/android-live-validation.md)에 기록한다.


## Android 0.6 GPU/채팅 (2026-10-13)

사용자 지정 SM-X926N에서 VLM OpenCL 실제 추론/채팅 검증을 수행했다. [지연 원인·GPU 실측](../reports/android-latency-diagnosis.md), [채팅·비서 파형](../reports/android-chat-validation.md)을 따른다. 첫 글자 개선과 실제 음성 재생 시작을 별도로 평가하며, 약11초의 음성 시작은 Live acceptance가 아니다. CPU TTS RTF>1, 음향/발열/다양한 카메라 장면 및 사람 대화 검증은 여전히 남아 있다.

### Android0.7 proactive/memory boundary

Record condition-negative silence, independently confirmed positive notice, no duplicate, silent baseline, scene change, recall without a camera, invalid output handling and late-epoch cancellation separately. The first real-device condition/recall smoke passed; a stricter-notice prompt exposed quoted-boolean schema drift. Follow-up results are in [the proactive report](../reports/android-proactive-validation.md); do not infer general Live acceptance from fixture checks.

### Android 0.8 runtime cooking

Run `CookingInstrumentation` on the authorized physical device with the pinned installed bundle. It tests different runtime plans, local/real-ASR completion and recall, installed offline TTS through AudioTrack, a 10s WAV overflow regression, cancellation, microphone continuity and fresh physical camera delivery. Camera inference uses an explicit dataset fixture separately from the camera-freshness check. The final harness mutes its AudioTrack for the user's environment; PCM/playback-head progress is not an audible-quality test. Inspect the generated plans and notice manually: schema success or keyword matching does not establish correct cooking advice. Keep earlier failures and timings in [the cooking report](../reports/android-cooking-validation.md). Python tests cover stale plan revisions, pause/resume, timer one-shot/reset, no visual auto-completion, notice cooldown and fresh/changed-frame scheduling.

### Android 0.12 microphone and visual continuation

`MicrophoneInstrumentation` is an explicitly initiated 15s human-microphone diagnostic. It deletes raw WAVs and reports input route, levels, segmentation and real ASR text; transport PASS is not semantic accuracy. The user confirmed correct live caption after the gate change. `VisionConversationInstrumentation` repeats four real single-image VLM questions with growing stored history and unmuted local voice. Keep the interrupted run separate; the unattended run passed with 5.3–6.3s first text / 7.2–7.8s first audio write. This is completion evidence, not realtime acceptance. See [the report](../reports/android-realtime-model-study.md).


### Android 0.13 voice-first boundary

`VoiceConversationInstrumentation` tests no-image real fixture ASR, generation cancellation, late-result rejection, continued capture and a new answer. `-e textOnly true` disables speech only in the harness and must not be reported as audible playback acceptance. The current PASS has `input_ready=false`: route-transition gating worked, but a connected-headset silent baseline remains unverified. The normal harness requires ready input, actual AudioTrack playback/drain and cancellation. Human voice-triggered barge-in needs a separate check with Buds connected and worn; preserve the failing SCO runs. Build requires the `webrtcvad` checkout at the manifest revision; its license is packaged under assets.


0.14 follow-up: connected/worn Buds baseline15s, input_ready=true, zero false turns; real unmuted ASR→answer audio3264ms from submit, actual playback cancellation70ms, continued capture/late rejection/resumption PASS. User confirmed actual voice interruption and new response, while reporting weak recognition and turn-based cancellation feel. Keep this single human functional confirmation separate from10interruptions/5minutes/noisy ASR acceptance. Details and prior failed startup run remain in the model study report.

### Android 0.15–0.16 streaming voice

`StreamingConversationInstrumentation` paces cumulative real PCM through the production ASR worker, verifies partial captions and hidden inference before the endpoint, forbids pre-commit audio, checks draft reuse and actual sentence output before answer generation finishes, and rejects a cancelled ASR final. It uses a fixture and controlled endpoint, not the human microphone. Its first-audio metric starts at the endpoint signal, excluding the preceding 800ms silence guard; do not present it as measured human end-of-speech latency. Existing voice instrumentation separately requires foreground capture, connected input, quiet baseline, actual AudioTrack drain/cancel/resumption and late-result rejection.

The 0.15 controlled streaming PASS did not establish acoustic acceptance: a subsequent quiet-Buds onset was explicitly confirmed false by the user. Keep it in the report. Version 0.16 introduces a pinned Silero detector and must pass real headset silence and speech checks independently. CPU tests cover final-ASR coalescing/cache/cancellation, no speculative audio, question mismatch including word boundaries, ordered sentence dispatch, decimals, queue lifecycle and interruption. Build/lint, detector fixture inference and a user test must remain distinct. No native-duplex or full Live acceptance follows from these unit tests.


### ASR acceptance after human failure (2026-10-13)

The human0.16 report “잠깐만” followed by missed speech overrides the earlier fixture/quiet PASS as a Korean ASR acceptance result. Matched segmented and wider PCM comparisons show errors on Android Qwen, original PC Qwen, Korean Zipformer candidates, and quantized Android Whisper; original PC Whisper recognized a later identical utterance correctly. Do not call deployment or a nonempty transcript accuracy acceptance. Preserve the device/host, quantization, context length and requested/actual backend for each result.

Sherpa's `provider=nnapi` run explicitly logged an API21 build restriction and CPU fallback on this Android16 tablet: it is **not NPU acceleration**. The separate whisper.cpp Vulkan binary actually selected Mali-G720 but was slower in measured runs; shortened-context output and repeated-output failures are separate from full-context results. See the model-study report and ignored `.runtime/streaming-study/` artifacts. No current result establishes full-duplex Live acceptance.


### Android 0.22 sample video and spoken scheduling

`VideoScenarioInstrumentation` must use an actual local MP4 decoder/preview and
paced synthetic PCM through production VAD/ASR; text injection and frame labels
cannot replace model inference. Freeze the sample SHA256 and semantic rubric before
measurement. The scenario must cover voice during video/output, frame advancement
while ASR/VLM is busy, last-voiced-time evidence (no future replacement), real timer
start/change/expiry during user speech, deferred notice after answer playback,
barge-in/old PCM rejection, hold/resume/exactly-once delivery, changed scene, video
off with continued microphone, and reset. Compare each visual answer with the
exact saved sample frame, independently of transport success.

Keep first visual initialization delay separate from warmed requests. In this
path first-write timing follows the final voice anchor (last processed voiced
block), including endpoint guard and subsequent inference, except direct text
requests which use their submit clock. It is still an AudioTrack write metric,
not acoustic playback or hardware capture timing. The interrupt metric is the
synchronous production invalidation/AudioTrack-stop call, excluding VAD onset
confirmation and Bluetooth output latency. No human-microphone or general Live
acceptance follows from the replay. [Protocol](../reports/video-scenario-protocol.md)
· [measured results and failures](../reports/android-video-replay-validation.md).

Android 0.23 extends that protocol with natural corrections without repeating
“타이머” or a command verb, referential cancellation, and explicit image memory
that survives unrelated tool-goal changes. Require the remembered animal and
location, a past/current distinction after a scene cut, recall with video off,
uncertainty about the unseen current state, and no fabricated recall after reset
using the identical question. Preserve full responses and selected JPEG hashes;
judge semantic quality independently of instrumentation completion. CPU checks
cover reference mismatch/no active timer, non-command negatives, note capacity,
expiry, opt-out and reset. The memory contains model-derived descriptions and
does not establish object tracking or general visual recall quality.

The final 0.26 replay also verifies full AudioTrack drain for multi-sentence
memory readback. No-image recall/current uncertainty uses the explicit memory
policy; image-present descriptions and comparison use real VLM inference. These
paths are recorded separately. Preserve the earlier image-denial, contradictory
recall and single-TTS length failures rather than treating nonempty text as a pass.
