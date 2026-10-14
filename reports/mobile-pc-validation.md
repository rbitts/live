# 모바일 OJT PC 검증 — 2026-10-12

**판정: LoRA/QAT 학습, 실제 INT4/INT8 추론, PC 오프라인 E2E와 Android 빌드까지 수행했다. 실기기 투입은 보류한다.** CPU 실시간 성능과 모호한 대상 구분이 미달이며, 공개 데이터의 사람 검수와 실제 Galaxy S 센서·음향·메모리 검증은 남아 있다. 아래 수치는 mock이 아닌 실제 고정 모델 실행 결과다. 입력 유지 테스트의 마이크 생산자는 합성 20ms tick이며 실제 마이크 검증과 구분한다.

## 환경과 고정 대상

- external-dev, AMD Threadripper 3970X 32코어/64스레드, RTX 3090 24GB 두 장. 학습은 GPU 1만 사용했고 실행마다 doctor/가용 VRAM/프로젝트 GPU 잠금을 확인했다. 다른 GPU 프로세스를 종료하지 않았다.
- Qwen ASR → Qwen3.5-4B → Qwen TTS **시스템 duplex** 경로다. MiniCPM native 경로는 변경하지 않았다.
- 모델 revision은 `configs/models.json` 그대로다. MNN은 `9f097c5fdd48e0cac4468d4cc53a828794d96888`로 추가 고정했다. 로컬 호환성 패치는 `patches/mnn-customvoice-sohee.patch`에 보존했다.
- `.venv-ft`, `.venv-export`, `.venv-export-asr`, `.venv-export-tts`를 분리하고 각각 lock을 저장했다. 기존 모델 환경을 변경하지 않았다. MNN Python wheel은 실행 스택 요구 때문에 이 호스트에서 로드되지 않아 보안 설정 변경 없이 공식 C++ runtime/converter를 빌드했다.
- PC MNN은 CPU 4스레드다. GPU BF16과 CPU INT4의 시간을 직접 속도 향상 비율로 비교하지 않는다. Android ARM64는 컴파일만 확인했으며 NPU/Qualcomm 도구를 사용하지 않았다.

## 데이터 감사와 학습

기존 GQA 200개 후보에서 `plate`가 번호판, `pot`가 화분을 뜻할 수 있다는 레이블 오류를 발견했다. 이 두 이름을 제외한 141개를 원본 VLM의 crop 분류로 확인하고 일치한 100개를 남겼다. **모델 교차 확인은 사람 검수나 독립적인 정답 검증이 아니다.** 선택 편향이 있으며 `labels_approved=false`, `model_acceptance=false`를 유지했다. 기존 200개로 실행한 실험에는 `DATA_ISSUE.json`을 남기고 최종 비교에서 제외했다.

VISOR는 실제 영상 3개/촬영자 3명에서 프레임 15개와 QA 36개를 확보했다. 공식 frame mapping과 EPIC-100 FPS로 시점을 계산하고 촬영자를 train/dev/test로 분리했다. 이는 제공된 관측의 비교이며 영구 객체 ID나 실제 물체 이동의 증거로 확대 해석하지 않는다. 학습 쌍 대부분의 화면 구역이 같다는 난도 한계도 있다.

| 구분 | train | validation | test |
|---|---:|---:|---:|
| GQA 원본 장면 | 70 | 16 | 14 |
| VISOR 파생 QA | 12 | 12 | 12 |
| 현재/과거/현재 근거 없음으로 확장한 실제 입력 | 222 | 60 | 54 |

test 54개는 독립 장면 54개가 아니다. GQA 14장과 VISOR 한 촬영자의 네 관측 쌍에서 파생됐다. 한국어 위치 관계, 다중 객체 식별, 장기 검색을 대표하는 대규모 평가셋이 아니다. 당초 2천/2만 학습 목표는 달성량으로 표시하지 않는다.

- LoRA: vision 고정, decoder의 `q_proj/v_proj/in_proj_qkv/out_proj`, rank 8/alpha 16, 학습 파라미터 4,259,840개, assistant-only loss, accumulation 4. 정제 GQA 100 step 후 혼합 데이터 50 step. 전체 optimizer resume 기능은 아직 없다.
- QAT: LoRA 후 추가 40 step, LR 2e-5. `base + LoRA` 합산 가중치에 STE fake quant를 적용하므로 QLoRA와 다르다. W4/group 64/대칭 [-7,7]/FP32 scale/nearest-even, 공유 embedding/head도 W4, vision은 float다. QAT 경로의 LoRA dropout은 0이다.
- 실제 QAT 최대 CUDA allocated 약 10,610MiB/reserved 14,620MiB. adapter 저장→원본 재로드→safe merge→MNN export→실제 CPU 추론까지 수행했다.
- FP32/BF16 두 입력 dtype, 두 행렬 크기에서 MNN packed INT4 복원과 reference grid가 정확히 일치했다. zero/STE gradient도 통과했다. 이것은 **수치 격자 검사**이며 BF16 병합 반올림, 전체 모델 logits, 서로 다른 backend의 bitwise 일치 주장과 다르다.

## 같은 고정 test의 자동 규칙 점수

| 모델/실행 | 현재 위치 (18) | 과거 관측 (18) | 현재 근거 없음 (18) |
|---|---:|---:|---:|
| 원본 BF16 / GPU | 83.3% | 50.0% | 100.0% |
| LoRA BF16 / GPU | 77.8% | 83.3% | 83.3% |
| 원본 INT4 / MNN CPU | 66.7% | 66.7% | 50.0% |
| LoRA 병합 INT4 / MNN CPU | 83.3% | 88.9% | 77.8% |
| **QAT 병합 INT4 / MNN CPU** | **94.4%** | **83.3%** | **94.4%** |

채점기는 방향 단어와 현재·과거/유보 표현을 검사한다. 의미적 정답률, 사람 평가, 통계적 유의성으로 부르지 않는다. 원본 INT4는 96-token 생성 상한 도달 1건도 실패에 포함했다. QAT가 모든 항목에서 이긴 것은 아니며 과거 관측은 LoRA PTQ보다 1/18 낮았다. 시험 데이터로 추가 모델 학습은 하지 않았다.

근거: `.runtime/ojt/pc-base-final-test/`, `pc-lora-final-test/`, `native-{base,lora,qat}-compact-test/`. 데이터 manifest SHA256: `e7f9efea2ef5b3d195f56a0d0be39ce8ee576f6d72328b66dd43eb275f401510`.

## runtime 수정과 실제 음성 검증

- CustomVoice/Sohee는 speaker encoder가 있는 Base 모델과 다르다. 고정 speaker ID 2864의 codec embedding을 사용하도록 연결했다. 타 모델로 교체하지 않았다.
- upstream TTS가 설정을 무시하고 greedy를 강제해 최대 256 frame까지 거의 무음을 생성했다. FP16과 공식 HF greedy에서도 재현돼 단순 INT8 문제로 보지 않았다. 설정한 sampling을 존중하도록 수정한 후 정상 종료했다.
- TTS는 8 codec frame씩, decoder의 왼쪽 문맥 25 frame을 사용해 PCM을 전달한다. 매 codec frame에서 epoch를 확인하고 취소 후 다음 발화를 재개한다. 모든 청크/버퍼는 제한된다.
- ASR의 잘못된 UTF-8 음절은 INT8뿐 아니라 MNN FP16에서도 재현됐다. HF는 같은 음성을 정상 전사했다. 공식 processor와 role 개행을 맞췄고, ASR의 byte-level sampling에 유효 UTF-8 prefix 제약을 적용했다. 제약 후 세 음성 fixture가 정상 한국어로 복원됐고 HF 대조 전사와 공백·문장부호 정규화 후 3/3 일치했다. 의도한 발화의 정답 CER가 0이라는 뜻은 아니며 자연 발화 CER/WER 평가를 대체하지 않는다.
- 네트워크 syscall을 거부하는 `scripts/run_offline.py` 아래에서 모델 로드·실제 ASR/VLM/TTS를 실행했다. 앱 manifest에도 INTERNET 권한이 없다.

## E2E·취소·메모리·지연

같은 `native/live_engine.hpp`를 PC 실행기와 Android JNI가 사용한다. PC에서는 실제 WAV 입력→ASR→현재/과거 이미지 VLM→TTS, 취소→재개를 실행했다. 결과에 물체 오인도 보존했다. 예를 들어 머그컵과 두 사람이 든 잔이 함께 있는 이미지에서 일반적인 “컵” 질문에 다른 잔을 골랐다. 실행 성공을 QA 정답으로 간주하지 않는다.

| 검사 | 결과 / 한계 |
|---|---|
| 5분 반복 | 실제 모델 314.26초, 10회 생성 취소·재개 모두 성공 |
| 취소 후 늦은 음성 | 0 sample |
| 생성 중단 | median 52.2ms, max 79.9ms; 실제 스피커 중단 지연과 다름 |
| 합성 입력 생산자 | 20ms tick 15,657회, 최대 간격 29.6ms; 실제 음향/AEC 검증 아님 |
| 세 모델 동시 상주 peak RSS | 6,699,760KiB = **6.39GiB**; Android PSS/OS/카메라·GPU 공유 메모리는 미측정 |
| TTS 첫 청크 | 반복 시험 약 1.07~1.35초 |
| TTS 생성 RTF | **3.11~3.52, 실시간 미달**. RTF 1보다 커 지속 재생 공백 가능 |
| 발화 종료→첫 음성 | PC 전체 경로는 초 단위 목표를 초과. p50 1.5초/p95 2.5초 합격으로 보고하지 않음 |

품질 평가와 일부 빌드/실험은 CPU에서 겹쳐 실행됐다. 단일 모델 측정과 전체 파이프라인/반복 시험 시간을 구분하며, 이 수치를 모바일 성능이나 열·배터리 수치로 외삽하지 않는다. PC RSS가 8GB 예산 아래여도 물리 12GB Galaxy S 합격 증거는 아니다.

## QA01~12의 PC 대체 검사

- QA01/02: 공개 이미지의 파란 머그컵, 남자가 든 잔을 특정한 질문은 위치를 맞혔다. 원래 빨간/흰 컵 실물 시나리오의 완전한 대체는 아니다.
- **QA03 실패:** 모호한 잔 질문에 어느 대상을 뜻하는지 되묻지 않았다. 추가 system instruction도 개선하지 못하고 QA11을 악화시켜 채택하지 않았다. 원래 비교 프롬프트를 유지한다.
- QA04/11: 실제 VISOR 관측 쌍의 현재 화면 구역 변화는 원래 프롬프트에서 맞혔다. 실제 물체 이동과 카메라 자세 추적을 증명한 것은 아니다.
- QA05/06/07: 과거 근거와 현재 화면 부재 응답 통과. 신선도/카메라 off는 Java 및 기존 Python/브라우저 상태 테스트로 별도 검사했다.
- QA08: 화면에 없는 키보드에 대해 유보했다.
- **QA09 모델 단독 실패:** 이미지/기억이 없어도 과거 컵 위치를 만들었다. 앱 공통 코어는 이미지 근거가 전혀 없으면 모델 호출 전에 명시적으로 유보한다. 이 방어 정책의 reset/오래된 epoch 테스트를 통과했으며 모델 자체가 학습으로 문제를 해결한 것은 아니다.
- QA10: 실제 모델 생성 취소/재개와 합성 연속 입력은 통과했다. 사람 말소리·스피커·AEC/에코·실제 재생 중단은 미검증이다.
- QA12: PC process-level 네트워크 차단 cold load와 APK 권한 검사는 통과했다. Galaxy S의 모델 설치 후 비행기 모드 cold start는 수행하지 않았다.

추가 시나리오는 실패 탐색용이다. 이를 보고 수정한 앱 정책의 독립적인 최종 test라고 부르지 않는다.

## Android 산출물과 남은 단계

Android 앱은 카메라 1fps JPEG, 연속 16kHz 마이크, 현재 프레임 3초 제한, 직전 두 질문의 관측 기억, 제한된 입력·재생 큐, epoch 폐기, pause/권한·audio focus 처리, 로컬 ZIP 가져오기/크기·경로·SHA256 검사를 구현했다. 입력 파일은 임시 폴더에서 사용 후 삭제하며 앱 종료/기억 지우기로 세션 기억을 없앤다. **상시 객체 추적이나 질문 없이 본 모든 장면의 검색은 구현하지 않았다.**

- ARM64 APK 및 x86_64 에뮬레이터 APK를 빌드했다. AGP 8.7.3/Gradle 8.9/JDK 17/SDK 35/NDK 27.2, 16KB ELF 및 APK 정렬 검사. 실제 Galaxy S/연결된 Galaxy Tab에는 설치하지 않았다.
- 모델 패키지: `.runtime/ojt/artifacts/live-qat-models.zip`, 28개 파일, **5,497,259,526 bytes**. 전체 파일 SHA256, ZIP CRC와 archive SHA256을 확인했다. MNN patch가 필요한 로컬 연구 산출물이다.
- 아티팩트/검사 상세는 `.runtime/ojt/artifacts/`와 `.runtime/ojt/apk-*.{log,json}`에 보존한다. 빌드·재현 명령은 [PC 실행 가이드](../docs/mobile-pc-runbook.md)를 따른다.
- 45 Python tests, 3 Node tests, Java 관측 기억 검사, C++ UTF-8/근거 없음/epoch 검사 통과. 에뮬레이터 시작 화면을 확인하고 Android 15+ 화면 여백 문제도 수정했다. 입력·패키지 instrumentation 결과는 아래 최종 확인에 기록한다.

실기기 전 주요 수정 과제는 (1) 모호한 대상/무관측 예제를 추가하고 검수한 데이터로 재학습·새 평가, (2) TTS 생성 속도 및 재생 공백 개선, (3) 앱의 최근 기억 범위 확장 필요성 확인이다. 실제 센서·음향·열·OS 메모리와 사람이 사용하는 Live 합격은 실기기 단계에서만 판정한다.

공식 근거: [MNN 고정 소스](https://github.com/alibaba/MNN/tree/9f097c5fdd48e0cac4468d4cc53a828794d96888), [Android 빌드 버전 호환성](https://developer.android.com/build/releases/about-agp), [GQA](https://cs.stanford.edu/people/dorarad/gqa/download.html), [VISOR 원본](https://data.bris.ac.uk/datasets/2v6cgv1x04ol22qp9rm9x2j6a7/), [EPIC-100 FPS 자료](https://github.com/epic-kitchens/epic-kitchens-100-annotations).

## 최종 확인

- ARM64 debug APK 6,943,004 bytes, x86_64 debug APK 7,345,886 bytes. 최종 APK의 v2 서명, 16KB ZIP 정렬, 포함된 native library 6개의 ELF LOAD alignment 0x4000을 확인했다.
- x86_64 에뮬레이터 instrumentation: **PASS**, 가상 카메라 4 frame, 무음 재생 중 마이크 46,720 sample(16kHz), 모델 패키지 경로 이탈/체크섬 변조/누락/예상 외 파일 4종 거부. 실제 모델은 에뮬레이터에 설치하지 않았으며 음향 품질/실제 센서/Android 모델 메모리 합격이 아니다.
- 최종 C++ 코어 재실행: 실제 질문 WAV 전사→이미지 응답→PCM 완료, 이미지/기억이 없는 후속 질문은 명시적 유보→PCM 완료. 현재 질문의 첫 오디오는 load 제외 13.08초, cold model load 19.86초로 성능 목표 미달이다. 모호한 “컵”의 오인도 그대로 남아 있다.
- 최종 SHA256과 근거 파일 목록은 `.runtime/ojt/artifacts/verification-manifest.json`에 기록한다. 모델 ZIP SHA256은 `686e0002f7cf3da019716cb760397d249890652143ec5daa03fc5240ce5cc03c`이다.
- 공용 `emulator-5554`를 다른 프로젝트가 사용 중인 사실을 검사 후 확인했다. `dev.live.offline`만 종료했고 원래 `com.plaium.tmm` 화면으로 복귀한 것을 확인했다. 다른 앱/실기기에는 추가 조작을 하지 않았다. 향후 에뮬레이터 실행 전 소유/전면 작업을 확인해야 한다.
