# Open-weight Live

2026-10-14 Android0.26 샘플 영상+회화형 음성 3회/39발화: 제어 시나리오3/3,
관측 기억의 저장·작업 뒤 유지·현재 비교·영상 없이 회상·삭제 대조15/15.
“아 잠깐 삼십 초 말고 이십 초” 정정과 결과 보류/재개를 실기기에서 검증했다.
부가 장면 세부 설명은0/3, 영상 첫 write는 첫21.8초/이후 중앙값10.3초로
일반 시각 품질·실시간 Live 합격은 아니다. [전체 결과·실패·사용법](reports/android-video-replay-validation.md).

2026-10-14 Android0.21 수정 후 같은 시나리오5회: 인식30/30, 십 초 변경5/5,
타이머 실행15/15, 결과 재진입5/5, 샐러드 사전 답변 기준5/5. 모의 입력은 남성
음성으로 함께 재생하며 시스템 오디오 정책을 따른다. 입력 화자와 VLM 파생본이
변경됐고 일반 답변 품질·사람 Live 한계는 남는다. [수정·비교·실패 기록](reports/android-voice-fix-validation.md).

2026-10-14 Android0.18 고정 시나리오5회·30발화 측정: 인식25/30(83.3%),
타이머 실행10/15(66.7%), 결과 안내·보류·재전달5/5, 일반 답변0/5.
“십 초→시초”와 요청 재진술 실패가 매번 재현됐다.
[반복 측정·원본·판정 기준](reports/android-repeated-voice-evaluation.md). 단일 합성 화자 결과이며 전체 Live 합격이 아니다.


Android 0.18은 공통 작업 메모리와 도구 후속 안내를 사용한다. 일반 대화에서 음성으로
타이머를 시작·변경·취소할 수 있고, 완료 시 새 질문 없이 안내한다. “잠깐만”은 안내를
보류하고 “계속해”는 미전달 결과를 다시 읽는다. 공통 코어와 개발 에이전트 어댑터,
부분 ASR 처리 변경 및 범위는 [구현 계약](docs/working-memory.md)를 따른다.


Android 0.18 개발 앱의 상단 파형 버튼에서 **모의 음성 입력**을 사용할 수 있다.
문장 입력/예문 선택 → `음성 보내기`로 실제 음성 감지·인식·응답 경로를 실행하며,
AI 답변 중에도 보내 끼어들기를 확인할 수 있다. `모의 사용자 ·` 자막과 M 배지로 구분하며,
같은 메뉴의 `실제 마이크로`를 누르면 돌아간다.
앱 출력은 시스템 미디어 경로·음량을, 입력은 시스템 기본 마이크를 따른다.
이어폰/통화 모드/음량을 앱이 강제로 바꾸지 않는다.
[검증 범위와 결과](reports/android-mock-voice-validation.md).

MiniCPM-o 4.5 네이티브 Live와 한국어 Qwen 시스템 full-duplex를 비교하는 로컬 PoC.

**Android 앱: 곁 · Live** — 온보딩, 로컬 모델 준비, 연속 음성·선택적 카메라, 텍스트/음성 응답, 세션 기억 설정을 제공한다. [앱 흐름·디자인](docs/android-app-workflow.md) · [아이콘 UI·실제 모델 검증](reports/android-icon-ui-validation.md). 완전 온디바이스 Qwen 경로이며 Galaxy S 실기기 acceptance는 별도다.

Android 0.16은 이미지 통합을 보류하고 음성 대화 안정화를 우선한다. 연속 마이크, 부분 ASR 자막, 발화 중 숨겨진 답변 계산, 문장 단위 음성 출력을 구현했다. 실기기 고정 음성에서 답변 생성 완료 전 음성 출력과 취소·늦은 결과 거부를 확인했지만, 실제 Buds 한국어 인식은 사용자 검증에 실패했다. 태블릿 내부 Whisper와 가속 후보를 비교 중이며, 전체 full Live 합격은 아니다. [측정·실패·수용성 범위](reports/android-realtime-model-study.md).

| 경로 | 구성 | 의미 |
|---|---|---|
| Native | MiniCPM-o 4.5 + 공식 OpenBMB 데모 | 모델 자체가 듣기·보기·말하기 상태를 처리 |
| Korean | Qwen3-ASR-0.6B + Qwen3.5-4B + Qwen3-TTS-12Hz-0.6B-CustomVoice | 앱이 동시 입력·끼어들기·재생 취소를 처리 |

**검증 상태는 [검증 기록](reports/status.md)을 따른다.** 코드 준비, GPU 모델 smoke, 실제 사람의 Live 사용 검증은 서로 다른 단계다. 서버 Qwen의 ASR는 발화 단위다. Android의 부분 ASR는 별도의 누적 입력 재인식 구조다. TTS는 문장 텍스트를 받아 8개 codec frame(약 640ms) 단위의 오디오로 전달한다.

[한국어 Live 인터랙티브 설명서](docs/qwen-live-interactive.html): 파일을 내려받아 브라우저로 열면 일반 질문·끼어들기·장면 기억을 단계별로 볼 수 있다. 실제 Qwen 합성 음성 3개를 포함하며, 공백 없이 연결한 버전과 청크 도착 간격을 재현한 버전을 비교해 들을 수 있다. 인터넷이나 모델 실행 없이 동작한다. [음성 출처와 재생성](docs/audio/README.md).

음성·영상·텍스트의 원본 시간 구간과 공통 정렬은 [시간축 설계](docs/multimodal-timeline.md)에 정의한다.
현재 timestamp/freshness는 부분 구현이며 공통 정렬 전체는 후속 구현이다.

## 실행

모바일 온디바이스·학습 확장은 [OJT 작업표](docs/mobile-ojt.md)와 [모바일 통신 계약](docs/mobile-protocol.md)을 따른다. 데이터 준비와 프레임 freshness 기반을 구현했으며 실제 LoRA/QAT 학습과 모바일 앱 검증은 별도 진행 단계다.

모바일 목표는 Galaxy S의 완전 오프라인 동작, 물리 RAM 12GB 상한, 비상업 연구 데모다. [공개 학습 데이터 구성](docs/mobile-datasets.md)과 [일반 양자화 계획](docs/mobile-quantization.md)을 따르며 NPU/Qualcomm 전용 가속은 후속이다.

이 호스트의 준비된 환경에서는 저장소 루트에서 실행한다. 두 경로는 GPU 1을 공유하므로 동시에 실행하지 않는다.

```bash
# 환경·GPU·모델 확인
.venv/bin/python scripts/doctor.py

# 1. Native: http://localhost:8006 에서 Omnimodal Full-Duplex 선택
.venv/bin/python scripts/run.py native

# Ctrl+C 또는 python3 scripts/stop.py로 종료 후, 2. Korean: http://localhost:8080
.venv/bin/python scripts/run.py qwen
```

한국어 화면에서 대화 시작을 누르고 마이크·카메라를 허용한다. MiniCPM 공식 화면의 `Rec`는 브라우저 내 녹화 옵션이므로 필요하지 않으면 끈다. 서버 자동 녹화는 기본 비활성화다. 원격 호스트에 접속하는 경우 로컬 컴퓨터에서 다음과 같이 포트를 전달한다. `USER@HOST`는 실제 접속 대상으로 바꾼다.

```bash
ssh -L 8080:127.0.0.1:8080 -L 8006:127.0.0.1:8006 USER@HOST
```

브라우저는 로컬 `http://localhost:8080` 또는 `http://localhost:8006`을 연다. 일반 HTTP 원격 IP에서는 카메라·마이크 접근이 제한된다. 외부 네트워크에 모델 포트를 공개하지 않는다.

Codex가 실행해 둔 서버도 `python3 scripts/stop.py`로 종료할 수 있다. 이 도구는 등록된 Live 프로세스만 중단하며 PID 재사용을 검사한다.

## 새 호스트 준비

Linux, NVIDIA 드라이버, Python용 uv, git이 필요하다. 이 호스트에서는 RTX 3090 24GB 두 장을 사용하며, native 공식 요구 조건은 VRAM >28GB이다. 이 24GB 호스트에서는 configs/native.json의 문맥 제한 프로필을 적용하며, 장비별 capacity smoke를 수행한다. 기본 무제한 문맥은 42초 이후 OOM이 발생했다. 설치/모델 다운로드는 수십 GB를 사용할 수 있다.

```bash
python3 scripts/bootstrap.py --models
.venv/bin/python scripts/doctor.py
.venv/bin/python -m pytest -q
```

- `.venv`: CPU 게이트웨이/테스트. `.venv-asr`, `.venv-vlm`, `.venv-tts`, `.venv-native`: 격리된 모델 환경.
- `configs/models.json`: 모델 revision. `configs/upstreams.json`: 공식 코드 commit.
- `requirements/*.lock`: 실제 설치 환경에서 고정한 버전 목록.
- `.upstream/`, `models/`, `.runtime/`: 로컬 전용, git 제외. 모델은 Hugging Face 캐시에 다운로드 후 링크한다.
- `.runtime/*.log`: 실행 로그. native 자동 음성·영상 저장은 꺼져 있다.
- 서버는 단일 사용자 PoC다. 인증된 다중 사용자 서비스 배포는 별도 범위다.

## 검증

```bash
.venv/bin/python -m pytest -q
# 해당 경로 서버를 실행한 다음 다른 터미널에서 실행
.venv/bin/python scripts/smoke_qwen.py
.venv-native/bin/python scripts/smoke_native.py
```

[구조와 제약](docs/architecture.md) · [합격 기준](docs/verification.md) · [에이전트 지침](AGENTS.md)

가중치와 upstream 소스는 재배포하지 않는다. 각 upstream 및 모델의 라이선스를 따르며 모델별 라이선스는 해당 Hugging Face 저장소에서 확인한다. 개인화는 세션 프로필과 질문 시점의 장면 기억이며, 파인튜닝·지속 객체 추적·장기 기억 DB를 의미하지 않는다.

## 모바일 OJT PC 검증

LoRA/QAT 학습, MNN INT4/INT8 모델, 네트워크 차단 PC E2E와 Android APK를 구현했다. 실시간 속도와 모호한 대상 QA는 아직 미달이며 실기기 합격이 아니다. [측정 결과·실패](reports/mobile-pc-validation.md) · [빌드·실행 가이드](docs/mobile-pc-runbook.md). 모델/데이터/APK는 `.runtime/ojt/`에만 보관한다.

실기기 요리 도우미0.8은 메뉴를 실행 중 정하고 진행·기억·카메라 알림을 연결한다. 빠른 단계 조작과 약5.8초의 화면 판단을 구분하며, 전체 Live/조리 정확도 합격은 아니다. [요리 검증과 사용 흐름](reports/android-cooking-validation.md).
