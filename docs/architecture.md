# 두 Live 경로의 구조

음성·영상·텍스트의 원본 시간 구간, 시계 매핑, 근거 선택 및 늦은 사건 처리는
[공통 시간축 설계](multimodal-timeline.md)를 따른다. 기존 timestamp/freshness는
부분 구현이다. Android 0.22는 아래 샘플 영상·발화 끝 anchor 정렬을 구현했으며,
공통 사건 봉투/센서·단어별 정밀 시계 정렬 전체를 구현한 것은 아니다.

Android 0.18은 공통 작업 메모리와 도구 후속 안내를 사용한다. 일반 대화에서 음성으로
타이머를 시작·변경·취소할 수 있고, 완료 시 새 질문 없이 안내한다. “잠깐만”은 안내를
보류하고 “계속해”는 미전달 결과를 다시 읽는다. 공통 코어와 개발 에이전트 어댑터,
부분 ASR 처리 변경 및 범위는 [구현 계약](working-memory.md)를 따른다.


### Android mock voice input (0.17, 2026-10-14)

User-directed audio policy as of 2026-10-14 supersedes the historical 0.10
communication-route overrides below: output uses `USAGE_MEDIA`, input uses
`MediaRecorder.AudioSource.DEFAULT`, and the app does not select a communication
device, set audio mode, force a volume-key stream or change system volume.
Android chooses the actual output and input independently according to system
policy; a Bluetooth output is not evidence of a Bluetooth microphone. Normal
audio focus remains, and actual input route changes still reset incomplete
capture and prime the detector. Mock mode is the explicit test-only input override.

The debug app's top waveform button opens a short text/preset input. A separate
`LocalSpeech` client synthesizes an installed offline Korean voice into a bounded
temporary WAV. `MockVoiceInput` collects at most 12 seconds, converts the resulting
24kHz mono PCM to 16kHz, and deletes the WAV through the existing reader lifecycle.
It never submits the typed text to ASR, VLM, or conversation memory directly.

`Capture` keeps draining `AudioRecord` in 320-sample blocks. Explicit mock mode
replaces each block with paced synthetic PCM (400ms leading and 1200ms trailing
silence), followed by silence between requests. The normal NeuralVad, energy gate,
segmentation, partial/final ASR, speculative reply, and sentence output paths remain
in use. Physical microphone samples are excluded from recognition in this mode;
the source stays in mock mode until the user selects the real microphone or stops
capture. The caption and M badge identify it. A source change resets the detector
and incomplete capture; returning to a physical source retains its route priming.

One input utterance is accepted at a time; there is no unbounded fixture queue.
Input synthesis has a 30s deadline and 160-character/12-second limits. Response
playback may continue while the next mock input is synthesized, then production
VAD onset performs the actual barge-in. Capture/session generations reject late
input synthesis; stop, memory clear and exit cancel pending input. The mock text
is session scoped. This is system duplex software testing, not human microphone,
headset acoustic or native-duplex acceptance. See
[mock voice verification](../reports/android-mock-voice-validation.md).

Android 0.9 변경: [실시간 모델 검토](../reports/android-realtime-model-study.md). 호스트의 두 경로는 유지한다. Android는 선택적 체크섬 검증 `observer/` 역할(LFM2.5-VL-450M, 별도 revision pin)을 로드하여 현재 단계에 명시된 사물의 존재만 짧게 판단한다. 기존 추론 소유 스레드/epoch를 공유하고, 카메라·마이크 수집은 계속 독립적으로 실행한다. 기동 시 관찰 모델을 예열하며 1초 최소 검사 간격·2.5초 결과 freshness·12초 알림 중복 제한을 적용한다. 단계 완료는 사용자 확인만 허용한다. 일반 대화와 proactive 음성도 설치된 오프라인 한국어 TTS를 우선 사용하고 없으면 기존 Qwen TTS를 사용한다. 이 변경은 native duplex가 아니다.

2026-10-12 사용자 요청과 제공된 설계 대화 미리보기를 바탕으로 구현했다. 대화 전문을 읽는 도구는 이 세션에 없었으므로, 전문을 확인했다고 주장하지 않는다.

## 1. MiniCPM-o 4.5: 네이티브 기준선

공식 OpenBMB/MiniCPM-o-Demo를 commit 고정하여 사용한다. audio/video 입력을 모델의 duplex 상태에 전달하며, 모델이 listen/speak를 결정한다. 공식 웹 UI와 WebSocket `/v1/realtime?mode=video`를 그대로 사용한다. video 세션과 Omni Full-Duplex 모드를 선택한다. 단순 Chat/Half-Duplex 통과는 이 경로의 합격이 아니다.

- 입력: 16 kHz PCM float32 1초 청크, JPEG 카메라 프레임.
- 출력: text/audio/listen 상태와 단계별 지연 시간.
- 네이티브 실시간 음성의 공식 언어는 영어·중국어. 한국어 기준선으로 오인하지 않는다.
- 녹음 저장은 기본적으로 끈다. 공식 모델 코드도 고정된 upstream commit에서 로드한다.
- 현재 공식 PyTorch 데모의 요구 조건은 GPU VRAM >28GB, 초기화 사용량 참고치는 약 21.5GB다. 24GB GPU에서 로드만 성공해도 장시간 Live가 보장되지는 않는다.

공식 배포 안내의 오래된 직접 worker 실행과 `/ws/duplex/` API는 현재 코드와 맞지 않는다. 최신 공식 Docker entrypoint가 사용하는 `py_backend.server → worker(전달 전용) → gateway` 3프로세스 구조와 Realtime API를 따른다. upstream 파일·추론 코드는 수정하지 않는다.

의존성 확인: ASR/TTS/VLM의 `uv pip check`는 통과한다. native는 minicpmo-utils의 librosa==0.9.0 메타데이터와 공식 데모의 >=0.10.2 요구가 충돌해 0.10.2.post1을 명시적으로 override했다. decord 0.6.0의 플랫폼 태그 경고도 남지만 이 호스트의 import는 성공했다. 실제 native smoke와 별도로 이 두 패키지 메타데이터 예외를 유지한다.

24GB 호스트에서는 기본 무제한 문맥으로 42초 이후 CUDA OOM을 관측했다. `scripts/native_backend.py`가 공식 decoder의 `set_window_config` / `set_window_enabled` API로 2,000 token에서 오래된 단위를 제거해 1,500 token 이하로 줄이는 기본 프로필을 적용한다. 시스템 프롬프트는 보존하지만 오래된 음성·영상 문맥은 잊을 수 있다. 이 설정은 `configs/native.json`에 명시되어 있고, 다른 용량의 GPU에서는 별도 재검증 후 변경한다. allocator는 expandable_segments를 사용한다. upstream 소스/가중치 파일은 수정하지 않는다.

## 2. Qwen: 한국어 시스템 full-duplex

브라우저 AudioWorklet → 20ms PCM/1fps JPEG → 비동기 WebSocket 수신 → VAD → Qwen3-ASR-0.6B → Qwen3.5-4B → 오디오 청크 출력 Qwen3-TTS-12Hz-0.6B-CustomVoice(Sohee) → 브라우저 재생.

입력 수신과 모델 추론은 별도 작업이다. ASR는 GPU 0, VLM과 TTS는 GPU 1에 놓는다. 이 호스트의 GPU 0에는 별도 사용자 작업이 실행 중이므로, 그 작업을 중단하지 않고 출력의 핵심 경로인 TTS를 GPU 1로 분리한다. CPU 연산 thread는 기본 4개로 제한한다. 모델마다 Transformers 요구 버전이 달라 프로세스/가상환경을 분리했다. 현재 ASR는 발화 종료 뒤 공식 Transformers API를 사용하는 endpointed ASR이며, 중간 인식 스트리밍은 아니다. LLM은 문장 단위 텍스트를 TTS에 전달한다. TTS worker는 공식 talker forward 결과에서 완성된 codec frame을 관측하고, 8 frame마다 공식 decoder와 같은 25 frame의 좌측 문맥으로 오디오를 복원한다. 이는 이 저장소에서 구현한 스트리밍 어댑터이며 공식 고수준 generate_custom_voice API 자체가 오디오 스트림을 반환하는 것은 아니다. 최종 전체 디코딩은 원래 API가 수행하며 첫 음성 전송은 이를 기다리지 않는다. 출력된 오디오 청크를 합쳐 한국어 ASR로 재인식하는 GPU smoke로 누락을 점검했다. 이는 사람이 듣는 자연스러움 검사를 대체하지 않는다. 현재 실시간보다 느린 생성 구간이 있어 재생 공백이 발생할 수 있다.

끼어들기는 20ms 음성 청크 3개 연속 RMS 임계값 초과로 감지한다. 브라우저가 즉시 대기 음성을 비우고, 서버는 epoch을 증가시켜 모든 오래된 결과를 폐기한다. LLM/TTS 생성은 cooperative stopping criteria를 적용한다. ASR 진행 중 추론은 완료될 수 있으나 결과는 사용하지 않는다. RMS VAD는 PoC이며 소음·스피커 에코 오검출은 실제 마이크 검증 대상이다. 브라우저 AEC를 요청하되 장치별 품질을 보장하지 않는다.

Qwen TTS upstream은 `generate_custom_voice(..., stopping_criteria=...)`를 내부 talker에 전달하지 않는다. 격리된 TTS worker의 단일 추론 lock 안에서 talker.generate 호출에 stopping criteria를 직접 전달하고 finally에서 원래 메서드를 복구한다. upstream 파일은 변경하지 않는다.

## 기억 및 개인화

- 프로필은 사용자 입력 500자 이내, 세션에만 저장한다.
- 완료된 대화 최대 8개, 과거 프레임 최대 4개를 저장한다. 한 번의 VLM 요청에는 과거 프레임 최대 2개를 사용한다.
- 웹의 현재 프레임은 서버가 발급한 일회용 capture ticket 이후 새 프레임을 받아 ticket 발급부터 3초 이내인 경우만 사용한다. 요청/캡처/전송 지연을 포함하는 보수적인 시각이며 장치 wall-clock 동기화는 하지 않는다. 레거시 ticket 없는 smoke 입력은 수신 후 3초 기준을 유지하므로 모바일 freshness 검증에는 사용하지 않는다. 과거 관측에는 경과 시간을 붙인다. 상세: [모바일 통신 계약](mobile-protocol.md).
- 프레임은 질문 시점의 최근 장면만 기억한다. 지속적인 객체 추적·3D 공간 기억·장기 기억 DB는 이 PoC의 구현 범위가 아니다.
- 연결 종료 또는 기억 지우기로 세션 기억을 폐기한다. 실제 음성·영상은 git이나 Obsidian에 저장하지 않는다.

## 자원과 접근

기본 external-dev/로컬 구성은 두 RTX 3090이다. native와 Qwen을 동시에 띄우면 GPU 1을 중복 사용하므로 순차 실행한다. 모든 자체 서비스는 127.0.0.1에 바인딩한다. 원격 브라우저는 SSH 포트 전달을 사용해 localhost 보안 컨텍스트에서 접속한다. 임의 인터넷 공개에는 별도의 인증·TLS 설계가 필요하다.

## 공식 근거

- https://github.com/OpenBMB/MiniCPM-o-Demo
- https://github.com/OpenBMB/MiniCPM-o-Demo/blob/main/docs/en/deployment.md
- https://github.com/OpenBMB/MiniCPM-o-Demo/blob/main/docs/en/video-duplex-protocol.md
- https://github.com/OpenBMB/MiniCPM-o-Demo/blob/main/docker/entrypoint-worker-backend.sh
- https://huggingface.co/openbmb/MiniCPM-o-4_5
- https://github.com/QwenLM/Qwen3-ASR
- https://huggingface.co/Qwen/Qwen3.5-4B
- https://github.com/QwenLM/Qwen3-TTS

실행에 쓰는 정확한 코드/모델 revision은 configs의 JSON manifest를 따른다.
## Android 온디바이스 pilot (2026-10-12)

`android/`의 Java 앱과 `native/live_engine.hpp`의 C++ MNN 코어를 추가했다. 기존 서버는 개발 비교 경로로 남으며 앱 추론에는 서버 fallback이 없다. 모델 ZIP은 로컬 URI에서 제한된 임시 폴더로 가져와 경로/크기/SHA256 검증 후 전환한다. INTERNET 권한을 선언하지 않는다.

카메라와 마이크는 모델 작업자와 별도 스레드다. 카메라는 최대 1fps JPEG, 질문 시점 3초 이내 프레임만 현재 근거로 사용한다. 기억은 직전 두 질문의 관측이며 상시 객체 추적/전체 장면 검색이 아니다. 추론 대기열 1개, PCM 재생 대기열 4개를 제한한다. 음성 감지/중지/reset은 epoch를 먼저 바꾸고 재생 큐와 AudioTrack을 즉시 비운다. ASR/VLM은 token 사이, TTS는 codec frame 사이에서 epoch를 확인한다. 취소된 결과는 UI/오디오에 재적용하지 않는다.

이미지 근거와 저장된 관측이 모두 없으면 모델의 추측을 허용하지 않고 코어가 유보 응답을 반환한다. 이 방어는 모델 자체의 무관측 QA 학습 성공과 다르다. 같은 C++ 코어를 PC 실제 모델 테스트에서 사용했다. Android 센서/AEC/오디오 focus/열·메모리 수용성은 PC 모델 실행만으로 보장하지 않는다. [검증 결과](../reports/mobile-pc-validation.md).

### 곁 · Live 앱 확장 (2026-10-13)

온보딩 → 모델 준비 → 권한 안내 → 홈/라이브/기억/설정 화면을 구현했다. 현재 화면/과거 관측을 분리하며 기억은 기본 최근 2개·5분, 사용자 선택으로 0/1/2개와 1/5/15분을 적용한다. 앱 내부 탭 이동은 입력과 출력을 중지하되 세션 기억을 유지하고, 명시적 종료·앱 이탈은 기억/답변까지 폐기한다. 영구 저장은 안내 완료와 정책 설정뿐이다.

JNI는 ASR/VLM 결과를 `onModelText`로 TTS 전에 표시한다. 음성 출력 해제 시 TTS 자체를 생략한다. 늦은 텍스트도 PCM과 같은 epoch 검사를 거치며, 이전 Capture 인스턴스의 callback은 새 세션에 접근하지 못한다. 카메라 없이 마이크만 시작할 수 있다. [확정 워크플로우](android-app-workflow.md)와 [Android 검증](../reports/android-live-validation.md)을 따른다.

### 아이콘 UI (0.3, 2026-10-13)

사용자 후속 요청으로 라이브 기본 화면은 아이콘/배지만 표시하고 설명은 native tooltip·상세 dialog로 옮겼다. 온보딩 뒤 바로 라이브로 진입한다. 답변/키보드는 요청할 때 열며 새 답변은 점 배지로 표시한다. 기존 모델 JNI와 기억 정책은 유지한다. [현재 워크플로우](android-app-workflow.md) · [재검증](../reports/android-icon-ui-validation.md).

### 스트리밍 채팅 (0.4, 2026-10-13)

MNN의 기존 1-token 생성 루프에서 완전한 UTF-8 텍스트 snapshot을 JNI `onModelTextDelta`로 전달한다. Android는 최신 snapshot 하나와 최대 한 개의 예약 UI drain으로 32ms 간격 갱신을 합친다. epoch가 다른 partial/final은 버리고 최종 답변 이후 대기 중 partial이 덮어쓰지 못하게 한다. UTF-8/UTF-16 변환은 한글과 보조 평면 문자를 보존한다. 모델 revision/프롬프트/96-token cap은 유지한다.

키보드 진입은 캡처와 음성을 끈 채팅 모드다. 말풍선으로 열면 음성 출력 중에도 생성 중 텍스트를 볼 수 있다. 대화 UI는 최근 8개 질문/답변 쌍만 세션에 유지하며, 앱 이탈과 기억 삭제 시 제거한다. 이 표시 기록을 모델 문맥에 자동 재주입하지 않는다. 상세 검증은 [채팅·비서 UI 실기기 기록](../reports/android-chat-validation.md)을 따른다.

### Android GPU 가속 (0.6, 2026-10-13)

검증된 MT6989 태블릿에서 VLM 이미지 encoder/decoder에 OpenCL을 적용한다. 선택적 vendor library 선언과 실제 GPU 조회를 사용하고 셰이더 캐시는 모델별로 보존한다. 나머지 기기는 CPU 경로를 유지한다. 모델·해상도·프롬프트는 동일하다. ASR/TTS는 CPU이며 TTS GPU 전체/파형 분리 실험은 정지 현상으로 채택하지 않았다. 임시 MNN 수정도 복원했다. 첫 글자는 빨라졌지만 느린 TTS를 완전 버퍼링한 실제 재생은 약11초여서 Live acceptance가 아니다. [단계별 진단·실측·설계 판단](../reports/android-latency-diagnosis.md).

중앙 비서는 실제 AudioTrack 재생 위치의 PCM 크기로 원형 파형과 중앙 막대를 움직인다. 생성 중/버퍼링/무음 구간에는 움직이지 않는다. 카메라는 우측 상단 작은 preview로 표시한다. 재생 queue는 취소 가능한 backpressure를 사용하며, 새 epoch 첫 PCM이 이전 epoch poll에서 유실되지 않도록 처리한다.

### Android session assistant (0.7)

The Android JNI path now uses a general assistant prompt with bounded conversation, explicit user-condition and timestamped scene-summary memory. A foreground opt-in15-second observation loop can notify without a question; it prioritizes user capture, checks freshness/epoch and suppresses duplicates. PC spatial evaluation keeps its original evidence policy. [Behavior and acceptance boundaries](../reports/android-proactive-validation.md).

### Android runtime cooking (0.8)

`CookingSession` holds an explicit runtime goal, bounded generated plan, user-confirmed completion, pause state and a foreground timer. Local controls bypass the VLM; spoken controls use real Qwen ASR first. Fresh-image questions still use the existing OpenCL VLM. The plan is model generated, not a fixed dish or prerecorded demo. Replanning replaces the working plan explicitly; camera advice never commits completion.

`SceneGate` retains two 16x12 grayscale signatures only. Pixel change schedules a model check at most every 2.5s; unchanged scenes have a 10s heartbeat. This is a scheduling heuristic, not semantic evidence. Input age is at most 3s, result age at most 6s, no inference backlog, and epoch/revision/camera/user-speech guards reject stale results. Valid short notices are deduplicated with a 12s cooldown. Malformed model output stays silent.

Cooking speech uses `LocalSpeech`: select an installed non-network Korean Android voice, synthesize a bounded temporary WAV on a separate worker, resample to 24k float PCM, and feed the existing AudioTrack/backpressure/playback-head meter. Reader queue is bounded to one; WAV limit 4MiB/20s, text 400 characters, existing audio buffer limits remain. Cancellation stops synthesis, clears playback and rejects late PCM. This is still system duplex, not native duplex; it does not accelerate Qwen TTS. With no suitable installed voice, cooking remains text-only. `MODIFY_AUDIO_SETTINGS` enables the already used communication audio mode; there is no new automatic output-route/mute rule.

### Android headset routing (0.10)

On API 31+, foreground communication focus selects an available Bluetooth SCO/LE headset (or wired/USB headset). PCM waits cancellably for the asynchronous communication route for at most 5 seconds; failure surfaces an error instead of silently playing through another output. Device callbacks refresh selection and cancel pending speech if the selected device disconnects. Session teardown clears the route and restores default volume-button behavior. The app does not change the user's volume. API 26–30 keeps the prior system route. Actual Buds playback and user hearing confirmation are recorded in [the model/latency report](../reports/android-realtime-model-study.md#010-bluetooth-playback-correction-2026-10-13).

### Android input feedback and visual request budget (0.12)

Capture uses a noise-floor-aware energy threshold with a lower continuation threshold for quiet headset syllables. A separate input meter and epoch-checked ASR caption expose microphone input and endpointed recognition on the live screen. Captions clear with the session. General mobile VLM requests use only the freshest selected image while keeping observation history and bounded text memory; past evidence remains labeled past. This avoids the observed multi-image Mali buffer wait in the affected session, but is not a general driver reliability guarantee. Four repeated real image/voice turns completed in the [latest report](../reports/android-realtime-model-study.md); general answer latency still exceeds the Live target.


### Android voice-first stabilization (0.13)

Images are deferred by user request: camera defaults off and off means no image evidence is sent, even when old images remain in observation memory. The capture worker uses separately pinned WebRTC VAD mode 3 plus the energy gate, 160ms onset confirmation, 800ms endpoint, and a roughly 15s bound with silence rearming. Model cancellation and PCM flush still occur as soon as a valid onset is confirmed; capture remains independent. Input must match the selected headset type and have a stable route for 1s; route changes discard partial capture. This initial connection guard is not imposed on every utterance. The classifier is not speaker recognition. See the 0.13 report for failed headset routing and the deliberately narrower text-output smoke.


### Android route priming and turn boundary (0.14)

Reset and feed the per-capture classifier on a route change; arm input after at least3s on the same route and200ms non-speech. The initial1s gate in0.13 did not cover one observed startup false onset. This priming is not repeated per utterance. Human cancellation/resumption passed once, but the architecture remains endpointed ASR → new text generation → completed-answer local speech. An onset discards the old epoch; it does not semantically revise or resume the previous response. Continuous input during output is distinct from natural native duplex reasoning.

### Android streaming voice (0.15)

Capture now retains 400ms of pre-onset audio and publishes cumulative 16kHz PCM after 1s, then every 600ms. A separate ASR worker holds only the newest pending snapshot; an in-flight decode completes cooperatively and a final snapshot replaces queued partials. Each snapshot is re-decoded by the pinned Qwen ASR model: this is incremental transcript delivery, not an incremental encoder/KV-cache implementation. ASR and answer generation have separate cancellation epochs and model ownership. Capture never waits for either worker.

At 400ms of VAD silence, capture sends an endpoint candidate while preserving the existing 800ms end-of-turn guard. Resumed detected speech discards that candidate. Otherwise final recognition uses the exact same PCM snapshot; the ASR worker reuses its result instead of decoding the extra silence. Cancellation and route loss erase the cached recognition. The buffer and final pending job remain bounded. Very quiet trailing speech that the VAD misses is still an accuracy risk requiring human checks.

Partial transcripts update the existing microphone caption. A hidden answer starts on partial text and is replaced when that text changes. Only a final transcript matching the provisional question (preserving word boundaries, ignoring trailing sentence punctuation) can commit it. Hidden answers never enter chat, memory or speech. Changed final text cancels the draft and generates a fresh answer. This is bounded speculative generation, not a model that revises its spoken reasoning or resumes the interrupted audio.

`SentenceVoice` splits cumulative committed output at sentence endings, preserving decimals and incomplete sentences. Its queue is bounded to 8 sentences/1200 characters, with 400 characters per sentence. Installed offline Korean TTS starts on each completed sentence while the answer model continues; utterance-specific keys reject stale same-response synthesis callbacks. The existing four-chunk PCM queue and backpressure remain. Sentence playback starts after 200ms PCM and does not switch to buffering the entire answer after a sentence gap. The microphone remains active, and valid speech onset cancels ASR, model output, queued sentences and actual playback independently.

Cooking controls remain final-transcript actions and do not execute speculative plans. The native MiniCPM and server Qwen paths are unchanged. Android remains **system duplex**, with device timings and human acceptance recorded separately in the model study.

### Android neural speech detection (0.16)

A user-confirmed quiet-Buds false onset in 0.15 produced the fabricated ASR caption “이미지.”. The streaming answer changes do not solve that input problem. Production capture now uses the pinned Silero ONNX detector from the existing MiniCPM demo checkout via ONNX Runtime Android 1.23.2, on one CPU thread. It consumes continuous 512-sample/32ms windows, retaining recurrent state independently of ASR; onset probability is 0.65 and continuation 0.35, combined with the existing energy gate and sustained-onset/endpoint rules. Route changes reset its state. WebRTC remains available for historical diagnostics, but is no longer the production capture classifier. Runtime errors stop capture with an error rather than silently falling back to the failed classifier.

Artifacts and licenses are hash pinned in `configs/android-vad.json`, prepared outside git by `scripts/prepare_android_vad.py`, and checked during the Android build. The extra runtime is justified by the observed classifier failure, not by ASR model acceleration. It does not identify the wearer or establish noisy-kitchen accuracy; actual silence, human speech and playback interruption checks remain separate acceptance evidence.


### On-device ASR candidate boundary

The production 0.16 recognizer remains Qwen while real-Buds accuracy is unresolved. Debug instrumentation evaluates pinned Korean Zipformer and Whisper runtimes without silently replacing it. `WhisperAsr` loads model files from private app storage and processes PCM wholly on the tablet. Whisper ONNX INT8 is not the original PC numerical precision; the reference PC and candidate device results must be compared on identical PCM. The `fullContext` diagnostic pads waveform input to30s within a fixed buffer; it is not an accepted latency/accuracy improvement.

An explicit debug-only arm captures at most two seconds before the first VAD onset plus five seconds after it, independent of the endpoint gate. Production capture never arms this window. Diagnostic snapshots and WAVs are deleted after comparison or bounded timeout; recognition text/timings may be retained. Stop-only phrases keep the floor with the user, while combined stop+question text remains a new question.


### Android 0.22: sample video with simultaneous speech

`SampleVideoInput` plays an explicitly selected local video through `MediaPlayer` on
its own preview surface, silently, while the existing `Capture` continuously drains
microphone PCM. Long-press the video button to select/restart a sample, return to a
physical camera, or stop only the sample video. The V badge distinguishes sample
input; mock speech retains its independent M badge. No network permission or model
revision changes are introduced. File size is bounded at 256 MiB, duration at ten
minutes, maximum width/height at 1920 pixels. End/error clears current visual input
and keeps speech capture alive. Session stop/source change closes the player and
invalidates callbacks; physical and sample camera are mutually exclusive.

The app samples the rendered video at up to 2fps and at most 640 pixels per axis.
Only one bitmap encoding may be in flight; late work is dropped after one second.
JPEGs enter a separate alignment ring (8 frames, 4 MiB, 4 seconds), apart from the
existing two-question memory. `Capture` records the monotonic processing time of
the last voiced PCM block. At the speech endpoint it chooses the newest frame
at/before that anchor, at most three seconds old, and retains this one image for
that ASR turn. Slow ASR or a scene cut cannot silently substitute a newer image.
Partial transcripts never mutate this bundle or run tools. Video-off/reset rejects
the bundle. Final voice input still uses the existing epoch/lease cancellation.

The timestamps are conservative preview-copy request and audio-block processing
times. The player's approximate media position is diagnostic provenance; neither
is a sensor exposure or exact decoder presentation timestamp. Clock uncertainty
is unknown, there are no word timestamps, and rapid scene cuts within a spoken
utterance are not resolved as deictic references. The first scope of the common
timeline is endpoint anchoring, not full implementation of `multimodal-timeline.md`.

The VLM receives a label identifying the attached frame as the question-time view.
With video off, ordinary questions remain image-independent and explicit visual
questions are told current evidence is unavailable. Current input never silently
falls back to a historical JPEG. Existing bounded textual dialogue and explicitly
labeled past observations remain distinct. Tool completion may update working
memory during a spoken turn; the pending notice waits for the user and current
answer, uses actual AudioTrack drain to mark delivery, and remains pending after
interruption. `lastDeliveredEpoch` exposes that delivery fact to the scenario
harness even when a waiting timer immediately starts a new output epoch.

See [scenario and source](../reports/video-scenario-protocol.md) and
[device validation](../reports/android-video-replay-validation.md). The replay is
system duplex with synthetic speech; it does not establish human acoustics or
native duplex acceptance.

### Android 0.23 explicit visual recall and conversational correction

An explicit spoken request such as “봐 두고 기억해 줘” stores the completed
image-grounded model description separately from the last four dialogue turns.
The caller checks the response epoch/working-memory lease and associates the
actual frozen image's observation time, source, and approximate video position.
These session-only notes obey the user's memory on/off, retention and 0–2 visual
item settings; reset clears them. A timer goal change does not erase them. They
are labeled past model descriptions, not verified scene facts or current images.
No automatic object tracker or persistent personal memory is introduced.

The final-ASR timer adapter accepts bounded conversational forms, including
“일단 삼십 초 뒤에 알려 줄래”, “아 잠깐 삼십 초 말고 이십 초” and
“아냐 그건 취소해 줘”. A correction needs an active timer and a matching prior
duration; an ambiguous/missing reference requests clarification without running
a tool. This is a tested grammar, not unrestricted semantic tool planning. Raw
ASR text is retained without scenario-specific spelling or numeric correction.
The replay protocol includes off-camera recall and the identical query after
reset, in addition to scene comparison after intervening timer work.

Android 0.24 adds a bounded no-current-image policy for explicit visual recall
and current-location references. It reads saved model descriptions verbatim with
a past-observation label, or reports no saved observation; unseen current state
is explicitly unavailable. It does not ask the model to rewrite the record into
new present-tense claims. The ordinary image-present past/current comparison still
runs the real VLM. A repeated-device 0.23 recall contradiction motivated this
change; the failed response is preserved in the validation report. This policy
does not validate the original model description or cover unrestricted questions.

In 0.25–0.26 the MNN conversation wrapper conditions image instructions on the
actual attachment marker rather than always including the missing-image warning.
Image-denial text is not saved as an observation. Past/current questions request
both the remembered target/location and the attached view explicitly. Multi-sentence
memory readback uses the same bounded `SentenceVoice` queue as streamed model
answers, preserving epoch cancellation and actual playback-drain accounting.
