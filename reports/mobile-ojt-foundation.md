# 모바일 OJT 첫 구현 검증 — 2026-10-12

최신 후속 검증은 [PC 학습·양자화·앱 검증](mobile-pc-validation.md)을 따른다. 아래는 첫 구현 당시의 기록이다.

판정: **소프트웨어 기반만 통과**. 실제 LoRA/QAT 학습, 모바일 모델 export, APK/IPA, 실기기 E2E, human Live acceptance는 아직 실행하지 않았다.

## 구현

- [OJT 작업표](../docs/mobile-ojt.md): 32개 작업, 의존 관계/산출물/완료 기준. 기기·downstream 데이터와 추론 배치 미정.
- `scripts/prepare_ojt_data.py`: 데이터/미디어 검증, 세션 그룹 단위 분할, 동일 이미지/예제의 split 간 누출 감지, 원본·split·모델 provenance. 실제 데이터 대신 합성 텍스트 3개로 CLI 검증.
- [모바일 통신 계약](../docs/mobile-protocol.md): one-use frame ticket, 발급 시각 기준 TTL, 요청 ID로 지연 응답 무효화. 웹은 ticket 이후 새 video callback에서 캡처. 레거시 smoke 경로의 수신 시각 기준은 명시적으로 분리.
- capture 대기와 WebSocket 전송을 제한하고 기존 audio/epoch 취소 경로를 유지. CI에 Node 내장 테스트 추가; 원격 CI는 이번 변경으로 실행하지 않았다.

## 실행 증거

| 검사 | 결과 |
|---|---|
| `.venv/bin/python -m pytest -q` | 24 passed |
| `node --test tests/test_web_capture.cjs` | 3 passed |
| 합성 fixture CLI | train/validation/test 각 1그룹·1행, `fixture=true`, `model_acceptance=false` |
| `git diff --check` | 통과 |
| `scripts/doctor.py` | external-dev, Python 3.11.16, RTX 3090 24GB × 2, 기존 모델 경로 4개 확인 |

호스트 점검 순간 GPU 여유 VRAM: GPU 0 = 21,020 MiB, GPU 1 = 24,108 MiB. 이는 이후 학습 가용량을 보장하지 않는다. GPU 추론/학습을 새로 실행하지 않았으며 다른 프로세스나 모델 환경을 수정하지 않았다.

모델 revision은 그대로다:

| 역할 | 모델 | revision |
|---|---|---|
| ASR | Qwen/Qwen3-ASR-0.6B | `5eb144179a02acc5e5ba31e748d22b0cf3e303b0` |
| VLM | Qwen/Qwen3.5-4B | `851bf6e806efd8d0a36b00ddf55e13ccb7b8cd0a` |
| TTS | Qwen/Qwen3-TTS-12Hz-0.6B-CustomVoice | `85e237c12c027371202489a0ec509ded67b5e4b5` |

## 검사 범위와 남은 실패 가능성

- Python: 기존 duplex/epoch/세션 정리 회귀, ticket TTL/replay/교체/reset/camera-off/close, ticket 없는 경로로의 downgrade 거부, 잘못된 request ID, 데이터 누출/경로/시간 검증.
- Node: 합성 DOM/media/WebSocket으로 ticket→새 프레임 순서, 한 요청만 유지, camera-off/reset 후 지연 ticket 무시, 전송 정체 시 종료를 검사했다. 실제 브라우저 카메라/센서/AEC 검증은 아니다.
- 이번 변경의 실제 모바일 지연·품질·RAM·전력 수치는 없다. 기존 서버 GPU 결과와 재생 공백은 [status.md](status.md)에 보존했다.
- 실제 device/OS/RAM과 downstream 과제/데이터를 확정한 뒤 runtime/export 가능성, 학습 환경, 앱 구현으로 진행한다. QAT 산출물의 런타임 호환성과 ASR/TTS 모바일 지원은 아직 입증하지 않았다.
- 새 프레임 API를 지원하지 않는 브라우저의 카메라 경로는 명시적으로 중단하며 업데이트 또는 카메라 끄기를 안내한다. 실기기에서 이 API와 앱 lifecycle, 스피커 재생 중 마이크 입력을 확인해야 한다.

## 공개 데이터 구현 후속: 2026-10-12

확정 범위는 최신 Galaxy S, 전체 RAM 12GB, 완전 오프라인, 비상업 연구 데모, 일반 PTQ/범용 QAT 우선이다. 위 첫 구현 시점의 미정 항목과 구분한다.

- 공식 GQA sceneGraphs v1.1 원본 ZIP 44,824,862 bytes 확보. SHA256: `59f6a3f6ac5227bac6cc615508e542dd546e309c7dbbdb666de05e42d7c51989`. 원본 train/val JSON의 실제 schema를 확인했다. 출처: https://cs.stanford.edu/people/dorarad/gqa/download.html
- 한국어 화면 위치 QA 200개를 `.runtime/ojt/gqa-audit-002/`에 생성했다. 원본 train에서 OJT train 140/dev 30, 원본 val에서 test 30. 후보 분포: 전화기 44, 병 30, 그릇 29, 책 13, 접시 46, 냄비 13, 컵 25. 기존 GQA 질문의 번역이 아닌 bbox 중심 삼등분 파생 QA다.
- `.runtime/ojt/gqa-images-002/`에 대응하는 실제 JPEG 200장 확보, 합계 27,565,890 bytes. 공식 이미지 ZIP 전체 크기 21,817,965,542 bytes 중 HTTP 범위 요청으로 57,007,025 bytes만 전송했다. 각 파일 CRC/SHA256/이미지 크기 검증 완료. 첫 시도 `gqa-images-001`은 HTTP 206 응답 미확인으로 중단했으며 전체 ZIP으로 전환하지 않았다. 제한적 일시 오류 재시도 후 새 출력 디렉터리에서 성공했다.
- `.runtime/ojt/gqa-prepared-audit-001/`의 명시 분할 준비 성공. 원본 split 역류/이미지 ID alias·bytes 중복/검수 후 레코드·이미지 교체를 검사한다. 전 레이블 `pending`, `labels_approved=false`, `model_acceptance=false`다. 사람이 검수한 학습셋으로 보고하지 않는다.
- `.runtime/ojt/gqa-review-001.html`: 원본 이미지/대상 bbox/한국어 질문·답변/판정·메모/검수 provenance 저장을 포함한 독립 오프라인 검수 화면. 200개 이미지 포함, JavaScript 구문 검사 및 headless Chrome 첫 화면 시각 확인. 이 확인은 200개 QA 전량 사람 검수나 판정 파일 다운로드 동작 검증이 아니다.
- 검증: `.venv/bin/python -m pytest -q` **40 passed**, 실제 공개 데이터 200건 준비 CLI 통과, `git diff --check` 통과. 기존 웹/모델 동작은 이번 데이터 후속에서 변경하지 않았다. 모델 revision·환경 의존성·GPU 사용 변경 없음.
- 남은 작업: 이미지/한국어 전량 검수와 오류 후보 수정, VISOR 실제 주석/시간 QA adapter, 기억/답변 유보 사례, LoRA 학습/일반 양자화/모바일 실행. 현재 200개는 변환 감사용이며 2천 pilot 또는 2만 본 학습셋 완료가 아니다.

## PC 검증 후 정정

기존 200개 후보의 plate/pot 다의어 오류를 확인했다. 활성 실험 데이터는 GQA 100개 + VISOR 36 QA이며 검수 승인된 데이터로 바꾸어 부르지 않는다. 과거 200개 실험은 최종 비교에서 제외했다. 실제 LoRA/QAT/export/PC E2E/Android 빌드 결과와 성능·QA 실패는 [후속 보고서](mobile-pc-validation.md)에 기록했다.
