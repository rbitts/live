# 12GB 모바일 RAM과 일반 양자화 범위

**2026-10-12 PC 실행 후속:** [실제 학습·양자화·E2E 결과와 한계](../reports/mobile-pc-validation.md), [재현 가이드](mobile-pc-runbook.md)를 기준으로 현재 상태를 확인한다. 아래 계획/후보 설정과 완료량을 구분한다.

2026-10-12 사용자 결정: 모바일 **물리 RAM 12GB를 허용 상한**으로 삼고, 현재는 일반 양자화를 수행한다. NPU 가속과 Qualcomm 전용 양자화/컴파일은 후속이다. 최종 앱의 완전 오프라인 요구는 유지한다.

## 현재 실행 경로

1. 기존 FP16/BF16 서버 모델과 모바일 변환 가능성을 기준선으로 확인한다.
2. **CPU 기준, 지원되는 GPU backend를 비교**하는 일반 PTQ를 먼저 만든다. Qualcomm NPU/QNN/전용 quantization 도구를 현재 성공 조건이나 필수 의존성으로 넣지 않는다.
3. VLM LoRA downstream FT를 수행하고 adapter를 병합한 모델을 같은 방식으로 PTQ한다.
4. 원래 OJT의 **QAT 목표는 유지**한다. 일반 CPU/GPU runtime의 실제 quant 수치에 맞춰 QAT를 수행하고, 동일 FT 모델의 PTQ와 비교한다. QAT 완료를 선언하려면 convert/export 및 실제 추론까지 통과해야 한다.
5. 이후 별도 단계에서 Qualcomm/NPU 요구 형식에 맞는 재양자화·컴파일·지원 연산·성능을 검증한다. 현재 산출물이 NPU에 그대로 호환된다고 가정하지 않는다.

## 첫 실험 설정

아래는 측정 전 후보 설정이며 이미 변환된 모델이나 기기 성능이 아니다. 모델 revision은 `configs/models.json`을 유지한다.

| 부분 | 첫 후보 | 비교/예외 |
|---|---|---|
| VLM 언어 decoder Linear | **INT4 weight-only / FP16 activation** | INT8 weight-only 품질 기준과 비교. group size/axis/scale은 runtime kernel 확인 후 고정 |
| vision encoder/projector | 먼저 FP16 보존 | 영상 QA 품질·RAM 측정 후 INT8 후보 평가 |
| norm/민감 연산/반복 상태 | runtime이 요구하는 float 유지 | 모든 연산을 INT4로 바꿨다고 표현하지 않음 |
| ASR | FP16 baseline → 지원 연산 INT8 PTQ | 한국어 CER/WER와 peak RAM 확인 |
| TTS/codec | FP16 baseline → 지원 연산 INT8 PTQ | CustomVoice/Sohee 호환성, 발음/누락/청크 RTF와 청취 검사 |

VLM W4A16은 **가중치 4bit, 활성값 16bit**를 뜻한다. 이미지·KV/DeltaNet 상태·오디오 버퍼·중간 활성값이 모두 4bit가 되는 것은 아니다. MNN 공식 exporter는 4/8bit 가중치 및 block 크기 옵션을 제공하므로 첫 CPU/GPU runtime 후보지만, 현재 고정 모델 전체 경로와 QAT 변환은 실제로 검증해야 한다. https://github.com/alibaba/MNN/blob/master/transformers/README.md

## 메모리 예산

- **12GB는 OS까지 포함한 장치 총량**이다. 모델 가중치에 12GB를 전부 배정하지 않는다. 16GB SKU나 RAM Plus/스왑으로만 성공한 결과는 12GB 타깃 합격으로 사용하지 않는다.
- 엔지니어링 시작 목표: 앱과 모델의 작업 메모리를 약 **8GB 이하**로 관리하고 나머지는 OS/카메라/그래픽·동작 여유로 남긴다. 8GB는 Android의 보장된 한도가 아니라 측정 후 더 줄일 수 있는 내부 예산이다.
- 가중치 파일 합계뿐 아니라 model load/FT adapter merge 후 export/runtime init/prefill/decode/TTS·카메라 동시 동작에서의 peak를 기록한다. APK/파일 크기로 RAM을 대신하지 않는다.
- 앱 PSS/RSS, native heap, GPU/shared allocation, 장치 MemAvailable과 low-memory 종료를 함께 본다. 서로 겹치는 메모리 통계는 단순 합산하지 않는다. 단위와 측정 방법을 report에 고정한다.
- 시작 입력 상한: 현재 1장+history 최대 2장, 카메라 전송 1fps, 짧은 음성 답변. processor가 만드는 image token과 context cache의 실제 크기를 보고 token budget을 고정한다. 기존 bounded queue/epoch 취소 정책을 유지한다.
- 8GB 작업 예산/12GB 장치에서 실패하면 이미지 토큰·문맥·상주 모델/지연 로드 정책을 먼저 조정한다. 그래도 부족하면 별도 명시한 작은 모델 실험으로 분리하며 기존 4B revision을 몰래 교체하지 않는다.

## 합격 보고

동일 frozen test에서 FP/LoRA/merged/PTQ/QAT/exported 품질, RAM peak, 첫 음성/중단 지연, TTS RTF/재생 공백, 5분 열화와 OOM 여부를 비교한다. CPU/GPU backend와 float fallback을 기록한다. NPU가 없다는 이유로 현재 검증을 미루지 않고, NPU 가속 결과로 현재 일반 양자화 성능을 대신하지 않는다.

현재 PC에는 실제 INT4/INT8 artifact와 QAT 비교 결과가 있다. 세 모델 동시 상주 peak RSS는 6.39GiB이며 모바일 RAM 실측은 아직 없다. CPU TTS는 실시간 목표에 미달했다. 자세한 판정은 후속 PC 보고서를 따른다.
