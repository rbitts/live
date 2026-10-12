# Open-weight Live

MiniCPM-o 4.5 네이티브 Live와 한국어 Qwen 시스템 full-duplex를 비교하는 로컬 PoC.

| 경로 | 구성 | 의미 |
|---|---|---|
| Native | MiniCPM-o 4.5 + 공식 OpenBMB 데모 | 모델 자체가 듣기·보기·말하기 상태를 처리 |
| Korean | Qwen3-ASR-0.6B + Qwen3.5-4B + Qwen3-TTS-12Hz-0.6B-CustomVoice | 앱이 동시 입력·끼어들기·재생 취소를 처리 |

**검증 상태는 [검증 기록](reports/status.md)을 따른다.** 코드 준비, GPU 모델 smoke, 실제 사람의 Live 사용 검증은 서로 다른 단계다. Qwen의 ASR는 발화 단위다. TTS는 문장 텍스트를 받아 8개 codec frame(약 640ms) 단위의 오디오로 전달한다.

## 실행

이 호스트의 준비된 환경에서는 저장소 루트에서 실행한다. 두 경로는 GPU 1을 공유하므로 동시에 실행하지 않는다.

```bash
# 환경·GPU·모델 확인
.venv/bin/python scripts/doctor.py

# 1. Native: http://localhost:8006 에서 Omnimodal Full-Duplex 선택
.venv/bin/python scripts/run.py native

# Ctrl+C로 위 경로 종료 후, 2. Korean: http://localhost:8080
.venv/bin/python scripts/run.py qwen
```

한국어 화면에서 대화 시작을 누르고 마이크·카메라를 허용한다. MiniCPM 공식 화면의 `Rec`는 브라우저 내 녹화 옵션이므로 필요하지 않으면 끈다. 서버 자동 녹화는 기본 비활성화다. 원격 호스트에 접속하는 경우 로컬 컴퓨터에서 다음과 같이 포트를 전달한다. `USER@HOST`는 실제 접속 대상으로 바꾼다.

```bash
ssh -L 8080:127.0.0.1:8080 -L 8006:127.0.0.1:8006 USER@HOST
```

브라우저는 로컬 `http://localhost:8080` 또는 `http://localhost:8006`을 연다. 일반 HTTP 원격 IP에서는 카메라·마이크 접근이 제한된다. 외부 네트워크에 모델 포트를 공개하지 않는다.

## 새 호스트 준비

Linux, NVIDIA 드라이버, Python용 uv, git이 필요하다. 이 호스트에서는 RTX 3090 24GB 두 장을 사용하며, native 공식 요구 조건은 VRAM >28GB이므로 장비별 capacity smoke를 수행한다. 설치/모델 다운로드는 수십 GB를 사용할 수 있다.

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
