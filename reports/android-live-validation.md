# 곁 · Android Live 검증 — 2026-10-13

**판정: 앱 구현과 Android 실제 모델 연결 통과. 실시간 성능 및 Galaxy S human Live acceptance는 미달/미검증.** 기존 시험 앱을 제품 흐름으로 확장했고 MiniCPM 경로와 고정 모델 revision은 변경하지 않았다. 이 보고서는 기준 HEAD `4a24036` 이후의 로컬 작업 트리를 다룬다. 이전부터 있던 모바일 OJT 변경을 포함하며 별도 commit/push하지 않았다.

## 구현 결과

- 곁 · Live: 짙은 남색·민트 테마, 온보딩 → 모델 준비 → 권한 안내 → 홈/라이브/기억/설정.
- 마이크 연속 캡처, 선택적 카메라, 텍스트 입력, TTS 이전 텍스트 표시, TTS를 건너뛰는 텍스트 전용 모드.
- 화면 하단의 고정 시작/즉시 중단 버튼, 센서 표시, 경과 시간, 첫 PCM 도착 시간. 카메라 off는 마지막 미리보기를 숨긴다.
- 관측 기억: 기본 최근 2개·5분, 0/1/2개와 1/5/15분 선택, 썸네일/경과 시간, 개별·전체 삭제. 실제 추론 근거에 정책을 적용한다.
- 앱 내부 탭 이동은 입력과 생성/출력을 멈추고 기억은 유지한다. 명시적 종료·뒤로 가기·앱 pause는 세션을 지운다. 설정만 영구 저장한다.
- epoch가 다른 텍스트/PCM과 오래된 capture callback 거부, 추론 대기열 1·PCM 큐 4, 강제 종료 후 다음 실행의 임시 요청 파일 정리.

전체 결정과 예외 흐름: [워크플로우](../docs/android-app-workflow.md). 설치/재현: [실행 가이드](../docs/mobile-pc-runbook.md).

## 환경과 모델

- `external-dev`, doctor 측정 당시 RTX 3090 24GB 두 장, GPU 여유 20,766/24,108 MiB. 이번 Android 추론/에뮬레이터는 **CPU** 사용으로 GPU 모델 실행은 하지 않았다. 다른 GPU 작업은 중단하지 않았다.
- 전용 `gyut` AVD / `emulator-5580`, Android 16 API 36 x86_64, 가상 RAM 12,288MiB, vCPU 4, software rendering, 가상 카메라/마이크. 기존 `emulator-5554`와 연결된 Galaxy Tab은 조작하지 않았다.
- AGP 8.7.3 / Gradle 8.9 / JDK 17 / compile·target SDK 35 / NDK 27.2.12479018. 앱 minSdk 26; Android 26~35와 실제 ARM64 장치 런타임은 이번 검증 대상이 아니다.
- Android 프로세스에 INTERNET 권한 없음. 테스트 AVD Wi-Fi/mobile data를 끄고 실제 모델을 cold load했다. 호스트와의 ADB 파일 전달은 설치/검사 용도다.
- 고정 ASR: `Qwen/Qwen3-ASR-0.6B`, revision `5eb144179a02acc5e5ba31e748d22b0cf3e303b0`, MNN INT8.
- 고정 VLM 원본: `Qwen/Qwen3.5-4B`, revision `851bf6e806efd8d0a36b00ddf55e13ccb7b8cd0a`. 기존 OJT LoRA/QAT 파생 `mnn-qat-compact` INT4/vision float를 그대로 사용. 이번에 재학습하지 않았다.
- 고정 TTS: `Qwen/Qwen3-TTS-12Hz-0.6B-CustomVoice`, revision `85e237c12c027371202489a0ec509ded67b5e4b5`, 기존 MNN INT8/Sohee.
- MNN `9f097c5fdd48e0cac4468d4cc53a828794d96888`, 기존 CustomVoice 패치 사용. 패치 SHA256 `58142f499dad20791d9df221b776145fc898d58fa97d91d1aa729dc4fe4272c2`.
- 모델 ZIP `.runtime/ojt/artifacts/live-qat-models.zip`: 28개 모델 파일, 압축 해제 모델 총 5,497,259,526 bytes, ZIP 자체 5,497,268,505 bytes. 가중치/입출력/스크린샷은 git 밖에 둔다.

## 단계별 결과

| 검사 | 결과 | 의미/한계 |
|---|---|---|
| Python protocol/state | 45 passed | 기존 서버/모바일 계약 회귀 |
| Node capture | 3 passed | ticket·새 프레임·camera-off/reset·전송 상한 |
| Java 관측 정책 | PASS | 불변 복사, stale/future frame, 개수 제한, opt-out, 삭제 후 현재 재사용 거부, 만료, clock regression |
| ARM64/x86_64 assemble + lint | PASS | 연구용 debug APK, Android 권장사항 lint 경고는 남음 |
| 서명/정렬/권한 | PASS | 두 APK v2 서명, 16KB ZIP 정렬, 각각 native library 6개 ELF LOAD 0x4000 이상, CAMERA/RECORD_AUDIO만 선언 |
| 앱 흐름 instrumentation | PASS | 온보딩, 탭, 기억 삭제/설정, 오래된 텍스트/PCM 거부 |
| 입력/패키지 instrumentation | PASS | 가상 camera 4 frames, 무음 재생 중 46,080 microphone samples, 경로/체크섬/누락/예상 외 파일 4종 거부 |
| 실제 Android 모델 경로 | PASS | 앱의 ZIP installer와 JNI ASR → VLM → TTS, 실제 PCM callback; mock 없음 |
| 실제 TTS 중 입력 유지 | PASS, 단일 시나리오 | 최종 실행에서 첫 PCM 이후 330,240 microphone samples(16kHz, 약 20.64초) 수신 |
| 실제 생성 중 중단 | PASS, 단일 시나리오 | 버튼→AudioTrack 정지 54ms, native 취소 완료 205ms, 이후 마이크 8,320 samples 유지 |
| Galaxy S/사람의 Live | **미실행** | 실기기 설치, 실제 스피커·마이크·Bluetooth·발열·5분/10회 기준을 대체하지 않음 |

## 실제 모델 출력과 지연

고정 질문 WAV와 GQA `2316817.jpg`를 앱의 동일 `submit` 경로에 주입했다. 실제 카메라 사진을 캡처한 human test가 아니다. 모델 출력 중 마이크 유지 검사는 카메라를 끈 가상 마이크로 진행했다. 화면 미리보기에 이 사진을 라이브 장면처럼 합성하지 않았다. 실제 camera callback은 별도 입력 smoke에서 확인했다.

- 실제 ASR: `현재 화면에서 컵의 중심은 어느 구역이 있나요?`
- 실제 VLM: `현재 화면에서 컵의 중심은 오른쪽 구역입니다.`
- 실제 TTS: 한국어 PCM 생성 및 Android 재생 큐 전달.
- 관측/기억을 지운 텍스트 전용 후속 질문: `현재 화면과 저장된 관측이 없어 위치를 확인할 수 없습니다.`. TTS callback은 발생하지 않았다.
- background 전환 후 답변/기억 삭제 확인. 모델 오류·위치 판단 품질 전체가 통과한 것은 아니다. 기존 QA03 모호한 대상 실패를 해결했다고 주장하지 않는다.

| 실행 | 준비 단계 | 첫 PCM | 전체 요청 | TTS 중 마이크 samples |
|---|---:|---:|---:|---:|
| 최초 ZIP 설치·모델 연결 | 설치+로드 48.649초 | 30.569초 | 57.279초 | 이 실행은 미측정 |
| 동시 입력 검증 | 모델 준비 대기 31.432초 | 31.811초 | 62.095초 | 484,160 |
| 최종 UI/모델 검증 | 모델 준비 대기 29.431초 | **31.192초** | **51.858초** | **330,240** |

준비 대기 시간은 해당 instrumentation 단계에서 남은 로드 대기를 잰 값이며 정확한 cold load 전체 시간과 구분한다. 첫 PCM은 앱 요청 제출부터 Java PCM callback까지이고 실제 스피커 첫 소리와 다르다. 짧은 몇 차례의 smoke이므로 p50/p95 통계가 아니다. 발화 종료→첫 음성 목표 1.5/2.5초와 비교하면 **성능 미달**이다.

5초 간격 `dumpsys meminfo` 표본 최대치는 최종 실행 PSS **6.374GiB**, RSS **6.505GiB**. 이전 동시 실행 표본 최대 RSS는 6.519GiB였다. 에뮬레이터 프로세스 표본이며 순간 peak를 놓칠 수 있고 실제 Galaxy S의 OS/카메라/GPU 공유 메모리·열 예산을 보장하지 않는다.

실제 중단 검사는 첫 PCM을 받은 뒤 아직 진행 중인 TTS를 중단했다. epoch 증가, 재생 큐 비우기, AudioTrack 정지, 늦은 PCM 거부, native 작업 종료와 마이크 지속을 검사했다. 54ms는 UI 버튼 처리/트랙 상태 관측이며 사람 음성 감지→스피커 무음의 p95가 아니다.

## 로컬 산출물

- `.runtime/android-live/artifacts/gyut-live-arm64-debug.apk` — Galaxy S용 설치 파일.
- `.runtime/android-live/artifacts/gyut-live-x86_64-debug.apk` — 에뮬레이터용.
- `.runtime/android-live/artifacts/verification-manifest.json` — 최종 APK/모델/코드·증거 SHA256.
- `.runtime/android-live/model-{initial,final}-report.json`, `workflow-model-{final,concurrent-previous}.log`, `cancel-real.log`, `capture-smoke.log`, `memory-samples*.json`.
- `.runtime/android-live/{build-arm64,build-x86,signature-arm64,signature-x86,zipalign-arm64,zipalign-x86}.log`, `elf-alignment.json`.
- `.runtime/android-live/01-welcome.png`, `02-live-answer.png`, `03-memory.png`, `04-home.png` — 실제 앱 렌더링. 모델 테스트 화면은 주입한 고정 테스트 이미지의 결과다.

## 남은 합격 조건

1. 실제 Galaxy S 12GB에서 로컬 ZIP 설치 후 비행기 모드 cold start.
2. 실제 마이크·카메라·스피커/이어폰 5분 대화, 10회 끼어들기와 실제 재생 중단 p95.
3. 권한 최초 요청·거절/영구 거절, 전화·audio focus, 잠금/복귀, Bluetooth 전환, 큰 글씨/TalkBack 기기 점검.
4. thermal/memory pressure 및 12GB 총 기기 예산 검증.
5. TTS 속도/재생 공백 개선과 모호한 물체 QA 품질 재평가. 기존 검수 대기 학습 데이터 상태도 그대로 유지.

앱/모델 통합 완료와 전체 full-duplex Live 확보를 구분한다. 연구용 APK를 일반 배포용 release 또는 사람 사용 합격품으로 표시하지 않는다.
