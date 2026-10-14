# Qwen 모바일 Live OJT 실행 계획

**2026-10-12 PC 실행 후속:** [실제 학습·양자화·E2E 결과와 한계](../reports/mobile-pc-validation.md), [재현 가이드](mobile-pc-runbook.md)를 기준으로 현재 상태를 확인한다. 아래 계획/후보 설정과 완료량을 구분한다.

작성: 2026-10-12. 목표는 **QAT 모델, downstream LoRA FT 결과, 모바일 앱 E2E** 세 산출물을 동일 평가셋과 고정 revision으로 연결하는 것이다. 현재는 데이터 준비와 모바일 입력 프로토콜의 CPU 기반을 구현했다. 모바일 모델·학습 결과·APK/IPA 확보는 아직이다.

## 확정된 제품 방향 — 2026-10-12

사용자 결정:

- 기기: **Galaxy S 최신 기종**, Android. 조사 시점의 대상 세대는 S26 계열이며 일반형/플러스/울트라, 지역 SKU와 실제 RAM은 아직 미확인이다.
- 배포: **인터넷 없이 완전 온디바이스 동작**. ASR·VLM·TTS, 관측 기억 조회와 응답 재생까지 기기 내부에서 처리한다. 제품 경로의 서버 fallback은 합격으로 인정하지 않는다.
- downstream: **물체 위치·최근 관측 기억 QA**. 현재 관측과 마지막 관측을 구분하고, 근거가 없으면 현재 위치를 단정하지 않는다.

구현 기본값과 미확정 사항:

- 사용자 후속 결정: **장치 전체 물리 RAM 12GB가 허용 상한**이다. 초기 앱 작업 예산은 약 8GB 이하로 잡고 OS/카메라/그래픽 여유를 남긴다. 앱 예산은 내부 목표이며 실제 가용량/peak/열 상태는 측정해야 한다. 16GB SKU나 RAM Plus로만 성공한 결과는 합격으로 사용하지 않는다.
- 사용자 후속 결정: 현재는 **일반 CPU/GPU 양자화**를 수행한다. INT4/INT8 PTQ 기준선을 먼저 만들고 원래 OJT의 범용 QAT 비교 목표를 유지한다. NPU/Qualcomm 전용 양자화·컴파일·가속은 후속 범위다. [일반 양자화와 RAM 예산](mobile-quantization.md).
- 사용자 후속 결정: 데이터와 모델은 **비상업 연구·학습 데모**에 사용한다. 1차 데이터는 GQA + VISOR + 근거 제한 파생 QA, Ego4D VQ2D는 접근 승인 후 확장한다. [공개 데이터 가공 계획](mobile-datasets.md).
- 기존 정책대로 기억은 세션 범위다. 앱 종료/기억 삭제 시 폐기하며 장기 기억, 상시 객체 추적, 3D 좌표 추정은 초기 OJT 범위에 추가하지 않는다.
- 모델 파일은 검증된 로컬 패키지로 준비할 수 있어야 한다. 사전 설치 후 Wi-Fi/모바일 데이터를 끄고 앱을 완전히 재시작해도 핵심 기능이 동작해야 한다. 온라인 로그인, 모델 다운로드 또는 라이선스 확인이 실행 중 필수이면 실패다.
- Linux 호스트의 학습/export와 기존 Qwen 서버의 비교 측정은 개발 도구다. 최종 앱의 모델 추론에는 서버를 사용하지 않는다.
- 남은 확인: 정확한 device SKU/OS/SoC/RAM, 실제 downstream 데이터와 사용권, 기간/담당자. runtime과 QAT 세부 수치는 변환·추론 실험으로 결정한다.

Samsung 공식 국내 사양(2026-10-12 확인): S26 일반형 12GB, Ultra는 256/512GB 스토리지 모델 12GB RAM 및 1TB 모델 16GB RAM이다. 실제 보유 모델로 간주하지 않는다. https://www.samsung.com/sec/smartphones/galaxy-s26/specs/ · https://news.samsung.com/kr/삼성전자-갤럭시-s26-시리즈-사전-판매-시작

연결 장치 조회(2026-10-12): Galaxy Tab S10 Ultra 5G(Android 16)와 Android 16 에뮬레이터가 확인되었다. Galaxy S 휴대폰은 이 조회에 없었다. 다른 작업에서 사용하는 연결 장치를 조작하거나 대체 타깃으로 사용하지 않았다.

## 범위와 실행 순서

- 기존 Qwen ASR → Qwen3.5-4B → Qwen TTS 서버를 기준선으로 보존한다. revision은 `configs/models.json`을 참조한다. 모바일 입력·재생을 포함하는 **시스템 duplex**이며 native duplex라고 부르지 않는다.
- **모바일 runtime feasibility → 원본/PTQ baseline → downstream LoRA → merge 검증 → QAT → export → 오프라인 모바일 통합** 순으로 진행한다. 모바일 capture/playback과 session 상태 머신은 학습과 독립적으로 구현한다. 서버 E2E는 선택적인 개발 비교 시험이다.
- QLoRA는 학습 메모리를 줄이는 방법이며 QAT 완료 증거가 아니다. fake quantization loss 감소도 실제 INT4/INT8 모바일 모델 확보를 의미하지 않는다.
- 첫 FT/QAT 대상으로 VLM을 사용한다. 언어 decoder INT4 weight-only를 첫 후보로, vision/민감 연산은 float 기준선을 보존한다. ASR/TTS는 FP16 기준선과 지원 연산 INT8 PTQ를 비교해 압축 범위를 결정한다. 실제 kernel/fallback은 report에 기록한다.
- 확정한 Android/완전 온디바이스/물체 위치·최근 관측 QA를 기준으로 진행한다. 학습 데이터와 실제 device 세부 사양, runtime 호환성은 아직 검증이 필요하다. [QA 명세와 평가 시나리오](mobile-qa-spec.md)를 따른다.

## 작업 분해

완료는 이 변경에서 구현·CPU 검증한 범위다. 기간은 기기·데이터 확인 후 산정한다.

| ID | 작업 | 산출물 / 완료 기준 | 선행 | 상태 |
|---|---|---|---|---|
| S01 | 기기·추론 위치 확정 | Galaxy S/Android/완전 on-device/물리 RAM 12GB 상한 확정; SKU/SoC/가용량 실측 남음 | 사용자 결정 | 일부 완료 |
| S02 | downstream 과제 정의 | 위치·최근 관측 QA, 비상업 데모, GQA/VISOR 가공 계획; 실제 데이터/품질 검증 남음 | 사용자 결정 | pilot 정의·실험 완료; 다중 객체/관측 검색 확장 필요 |
| S03 | 서버 기준선 정리 | revision/지연/공백/미검증 항목 연결 | 기존 reports | 완료 |
| S04 | 모바일 자원 예산 측정 | 모델별 크기/RSS/peak RAM, prefill/decode, 열·배터리 예산 | S01 | PC 동시 상주 RSS 6.39GiB; 모바일 미측정 |
| R01 | runtime feasibility | VLM·vision·tokenizer·KV/DeltaNet·ASR·TTS/codec 지원표 | S01 | MNN 세 모델 PC 실행/ARM64 빌드; 로컬 패치 필요 |
| R02 | 원본/PTQ export | 고정 exporter/runtime commit, text+image 실제 추론/원본 비교 | R01 | PTQ/QAT export 및 실제 PC 추론 완료 |
| R03 | 실기기 baseline | cold/warm start, RAM, 품질, backend/연산 fallback | R02 | 대기 |
| D01 | 데이터·미디어 검증 | 현재/과거 구분, 파일/해시, 상대 경로, JPEG 제약 | 없음 | 완료 |
| D02 | 세션 단위 분할 | 결정적 train/validation/test, 중복 누출 검사, manifest | D01 | 완료 |
| D03 | 공개 데이터 확보·가공 | VISOR sparse/GQA QA, split-map/provenance, 한국어 200개 감사→2천 pilot→2만 목표 | S02 | GQA 100 + VISOR 36 QA; 사람 검수/2천 pilot 대기 |
| D04 | 고정 평가셋/채점기 | task 성공률, 한국어 CER/WER, grounding, 현재/과거 혼동률 | D03 | 고정 54 QA·규칙 채점 완료; 의미적 검수 남음 |
| L01 | 격리 학습 환경 | `.venv-ft`, 검증된 버전 lock, smoke/메모리 보고서 | S02/R01 | 완료: 별도 ft/export 환경과 lock |
| L02 | LoRA 대상 조사 | 실제 module 이름/shape, vision freeze, rank/alpha 후보 | L01 | 완료: 4,259,840개 파라미터 LoRA |
| L03 | trainer/collator | image processor 정렬, assistant-only loss, image/padding mask, resume | L02/D03 | pilot 완료; 전체 optimizer resume 미구현 |
| L04 | 소량 overfit smoke | loss/gradient/저장·재로드·실제 영상 답변 | L03 | 실제 학습·저장·재로드 smoke 완료 |
| L05 | 본 학습/ablation | base vs LoRA, seed/config/data hash, 회귀/비용 기록 | L04/D04 | 소규모 pilot 완료; 본 학습/통계적 검증 남음 |
| L06 | adapter merge | 미병합/병합 logits 또는 출력 오차, processor 보존 | L05 | safe merge/export 완료; 이전 pilot 출력 일치 확인 |
| Q01 | 일반 quantization 규격 | CPU/GPU INT4/INT8 PTQ→범용 QAT, group/axis/scale/예외층 실측; NPU 후속 | R02/L06 | W4/group64/FP32 scale + 공유 head/embedding W4 확정 |
| Q02 | PTQ 비교 기준 | 같은 FT 모델·split·runtime의 품질/RAM/속도 | Q01 | 동일 test의 원본/LoRA PTQ 완료 |
| Q03 | QAT prepare/smoke | 배포 가능한 연산의 fake quant, gradient/VRAM/재로드 | Q01/L06 | 실제 gradient/40-step QAT·격자 parity 통과 |
| Q04 | QAT train/convert/export | 배포 quant 수치 일치, checkpoint/변환본 hash, parity | Q03 | adapter/merge/MNN export/실제 추론 완료 |
| Q05 | QAT 효과 판정 | 동일 test에서 PTQ 대비 품질·실기기 자원/지연 비교 | Q02/Q04 | PC 54 QA 비교 완료; 실기기 자원 대기 |
| M01 | 모바일 transport 계약 | PCM/JPEG, epoch/취소/큐 제한/freshness 문서 | 기존 서버 | 완료 |
| M02 | 새 프레임 ticket | 전송 지연 포함 TTL, replay/camera-off/reset CPU 검사 | M01 | 완료 |
| M03 | 모바일 앱 shell | 권한, preview, 마이크/스피커, 안전한 개발 연결 | S01/M01 | Android ARM64/x86 APK·에뮬레이터 시작 검증 |
| M04 | 선택적 서버 비교 시험 | 개발 지연/품질 비교용, 최종 on-device 합격 증거에 포함하지 않음 | M03 | 선택 사항 |
| M05 | on-device adapter | 각 모델 load/run/cancel/release, epoch 기반 늦은 출력 폐기 | R03/Q04 | 공통 C++ 코어/JNI 구현·PC 실제 추론; 실기기 대기 |
| M06 | 앱 lifecycle/오디오 | pause/resume, 전화/audio focus, Bluetooth, offline cold start/열화 | M03/M05 | pause/focus/취소 구현; 센서·음향·Bluetooth 실기기 대기 |
| E01 | 동일셋 모델 평가 | base/LoRA/merged/PTQ/QAT/exported 결과 비교 | D04/L06/Q05 | 소규모 고정셋 비교 완료; 사람 검수 미완료 |
| E02 | 모바일 paced duplex | 5분 이상, 10회 barge-in, p50/p95, 재생 공백/큐 증가 | M06 | PC 314초/10회 취소 통과; 모바일 미실행·CPU RTF 미달 |
| E03 | 오프라인 human Live | 통신 차단 후 cold start, 실제 센서/재생·끼어들기·기억 삭제/관측 부재 | E02 | 대기 |
| E04 | 재현 패키지 | 앱 빌드, 모델 manifest, 실험표, 실행법, 실패·한계 | E01/E03 | PC/Android 빌드·모델 ZIP·hash·실패 보고서 확보 |

## 첫 구현: 데이터 준비

GPU/학습 라이브러리 없이 `.venv`에서 실행한다. 데이터와 산출물은 git에서 제외된 `.runtime/ojt/` 아래 둔다. 합성 텍스트 3개는 도구 검증용이며 모델 품질 평가/실제 학습셋이 아니다.

```bash
.venv/bin/python scripts/prepare_ojt_data.py tests/fixtures/ojt-text.jsonl \
  --media-root tests/fixtures --output .runtime/ojt/format-check-001 --fixture
```

출력: `train.jsonl`, `validation.jsonl`, `test.jsonl`, `manifest.json`. 기존 디렉터리는 덮어쓰지 않는다. manifest는 모델 revision, split별 레코드/그룹 수·SHA256, 원본 SHA256/seed와 `data_prepared_only`, `model_acceptance=false`를 보존한다.

레코드는 trainer 중립적인 **단일 질문/응답** 형식이다. 아직 Qwen processor 입력 변환이나 loss masking 구현은 아니다.

```json
{"id":"turn-001","group_id":"capture-session-001","prompt":"지금 컵은 어디 있나요?","response":"책상 왼쪽에 있습니다.","question_at_ms":42000,"images":[{"path":"session-001/frame.jpg","view":"current","captured_at_ms":41000}]}
```

- `group_id`: 분할 불가 단위. 같은 사람/장소가 여러 촬영에 반복되면 상위 그룹으로 묶는다. 랜덤 turn 분할을 피한다.
- 두 timestamp는 동일 세션의 같은 시계 기준이다. 현재 이미지는 질문보다 미래일 수 없고 최대 3초 전, 과거는 `history`로 표시한다. 현재 1장+과거 2장 이내다. 실시간 ticket과 별개인 오프라인 레이블 시각이다.
- `path`: `--media-root` 내부 상대 경로 JPEG, 최대 변 1280px. root 밖 symlink, 누락/손상 파일을 거부한다. 준비 시 파일 SHA256을 추가한다.
- 그룹 수 기준 약 80/10/10, 최소 3개 그룹, validation/test 각각 최소 1그룹이다. 전체 입력/seed가 달라지면 split이 달라질 수 있으므로 첫 평가 전에 고정한다.
- 공개 데이터에는 `--split-map`/`--provenance`로 공식 분할과 검수 상태를 보존한다. GQA 감사 생성/선택 이미지 다운로드 실행법은 [공개 데이터 파이프라인](mobile-datasets.md#첫-실행-가능한-gqa-감사-파이프라인)을 따른다.
- 동일 이미지 bytes/동일 예제의 split 간 유입은 실패 처리한다. 근접 프레임/같은 피사체/정답 사실성은 수동 검수가 필요하다.
- PTQ calibration/QAT 학습은 train만, 선택/튜닝은 validation, 최종 비교는 test를 사용한다. 실제 촬영 데이터 수집 동의는 별도로 확보한다. 앱은 기본 녹화하지 않는다.

## 기술 선택 근거와 미확인 사항

2026-10-12 공식 MNN 자료는 Qwen3.5 export/inference와 hybrid linear attention 지원을 안내한다. 첫 VLM runtime 후보로 조사할 근거이며 고정 4B checkpoint의 vision 경로, ASR/TTS 전체, QAT 호환성을 실기기에서 검증한 결과는 아니다. https://github.com/alibaba/MNN/discussions/4354

TorchAO QAT는 prepare → 학습 → convert 흐름을 제공한다. exporter/kernel과 quant 수치가 맞아야 하므로 TorchAO 결과가 MNN으로 그대로 연결된다고 가정하지 않는다. ExecuTorch도 후보지만 해당 checkpoint 전체 연산과 영상 입력 export 통과가 선행 조건이다. https://docs.pytorch.org/ao/stable/workflows/qat.html · https://github.com/pytorch/executorch/blob/main/docs/source/llm/export-llm.md

초기 실험은 FP/BF16 LoRA → merge → PTQ/QAT 비교를 제안한다. VRAM이 부족하면 runtime 호환성을 유지하는 QAT+LoRA나 작은 모델을 별도 실험하며 기존 revision을 몰래 바꾸지 않는다. QAT와 QLoRA의 목적 차이: https://docs.pytorch.org/ao/stable/eager_tutorials/finetuning.html

4B 가중치의 4bit 산술 하한은 약 2GB지만 scale·예외층·vision·KV/반복 상태·ASR/TTS·활성값이 추가된다. 실제 모바일 파일/RAM이나 동작 보장으로 사용하지 않는다.

2026-10-12 추가 조사: MNN 공식 transformers 문서에는 Qwen3-TTS 실행 예제가 있지만 reference audio를 쓰는 경로다. 기존 고정 모델은 **CustomVoice/Sohee**이므로 다른 Base 모델 예제의 존재를 현재 모델 호환 증거로 사용하지 않는다. ASR/VLM/TTS별 exporter와 한국어 오프라인 출력을 확인한 뒤 runtime을 채택한다. 이후 MNN을 추가 고정하고 CustomVoice 패치와 실제 PC 추론을 검증했다. 기존 모델/upstream revision은 변경하지 않았다. https://github.com/alibaba/MNN/blob/master/transformers/README.md

## 검증과 보고

- external-dev: GPU 실행 직전마다 `scripts/doctor.py`. 학습과 Live 서버는 순차 실행하며 타 프로젝트 프로세스를 종료하지 않는다. `.venv-ft`와 필요한 QAT/export 환경은 별도 lock으로 격리한다.
- report에 device/OS/SoC/RAM/thermal 상태, model/adapter/QAT/exporter/runtime revision, data/split hash, dtype/quant 규격, 문맥/영상 해상도, cold/warm을 기록한다.
- 발화 끝→첫 실제 재생, 감지→스피커 중단을 측정하고 서버 첫 전송과 분리한다. 모델별 시간, 재생 공백, 전력/열화, 실패/OOM도 남긴다.
- 초기 목표는 [verification.md](verification.md). RAM은 물리 12GB 상한/초기 앱 예산 약 8GB이며 downstream 품질/배터리 목표는 baseline 후 확정한다. 서버도 재생 공백/human acceptance가 남아 있다: [status.md](../reports/status.md).
- 개발 연결은 localhost/SSH tunnel 또는 인증된 TLS proxy. Android USB reverse는 타깃 결정 후 검토한다. 모델 포트를 외부에 공개하지 않는다.
- CPU 통과는 학습·export·실기기 E2E 통과가 아니다. 실제 재현 기록은 [첫 구현 검증](../reports/mobile-ojt-foundation.md)에 남긴다.
