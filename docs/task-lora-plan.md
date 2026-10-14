# 작업 분리·진행 관측·중단 후 회상을 위한 LoRA 학습 계획

작성일:2026-10-14. 상태: **학습 설계이며 학습 실행·가중치 교체는 아직 하지 않았다.**

사용자 결정은 모델의 작업/단계 분리 약점을 별도 규칙으로 보정하지 않고,
학습으로 개선할 문제로 기록하며 현재는 하네스 안정화에 집중하는 것이다.
요리별 객체/동사 규칙, 정답 시각으로 단계 전환, 예상 ASR 덮어쓰기,
모델 답을 정답 문장으로 교체하는 방식은 이 계획의 개선 방법이 아니다.
프로토콜 검사·시간 정렬·epoch 취소·유한 버퍼는 학습과 별개로 유지한다.

## 1. 관측된 약점과 학습 목표

실제 EPIC-KITCHENS `P18_03`의 연속 샐러드 준비를 선정했다.
원본427.435초, 행동 주석113개, 원본/재생본/선택 프레임 해시를 고정했다.
앱 입력은 원래 순서·속도의640×360 무음 MP4와 별도 사용자 PCM이다.
주석은 평가용이며 모델 입력이나 완료/알림의 트리거로 공급하지 않는다.

Android0.27 Qwen3.5-4B의18개 실제 장면 진단에서는 `complete`가0개였다.
채소 담기가 끝난 뒤 봉지를 정리하는108초 장면도 계속 담는 중이라고 답했고,
오이 손질 뒤321초 장면도 진행 중이라고 답했다.
작업 설명, 이전 관측, 현재 영상 근거를 섞은 답변도 있었다.
원본은 [일반 모델 진단](../reports/cooking-task-general-results.json)에 보존한다.
중앙값11.892초, 범위6.566~22.876초는 이 진단의 모델 호출 시간이다.
짧은 객체/동작 질문으로 우회한 후보도 완료를 놓쳤으며 사용자 지시로 제거했다.
그 후보의 속도 개선을 단계 인식 개선으로 보고하지 않는다.

학습할 능력을 다음처럼 구분한다.

| 능력 | 맞는 출력 | 대표 실패/음성·영상 예제 |
|---|---|---|
| 작업/행동 대응 | 현재 보이는 행동을 요청된 task/step에 대응 | 샐러드 중 컵 설거지를 오이 손질 완료로 오인 |
| 단계 경계 | 진행, 완료, 일시 중단, 확인 불가를 구분 | 칼질 중 잠깐 옮긴 것을 전체 손질 완료로 오인 |
| 증거 출처 | 계획·사용자 확인·과거 관측·현재 영상 구분 | 계획의 ‘담기’를 이미 담았다는 관측으로 복사 |
| 진행 기억 | 관측/사용자 확인된 단계와 미확인 단계를 유지 | 다른 대화/타이머가 현재 요리 이력을 지움 |
| 중단 후 복귀 | “뭐 하고 있었더라?”에 마지막 확인한 진행과 이어 확인할 일 | 이전 설명을 현재 상태라고 단정 |
| 개입 내용 | 완료가 관측되면 해당 recipe의 다음 단계 안내 | 무관한 동작마다 말하거나 이미 한 단계 반복 |

중단 감지와 발언권·재생 취소·타이머 전달의 실행은 하네스/상태 코어가 담당한다.
LoRA에 듣지 못한 영상이나 삭제된 기억을 만들어 내도록 가르치지 않는다.
직접 들었다는 확인 없이 AudioTrack drain을 사용자 이해로 라벨링하지 않는다.

## 2. 모델과 실행 범위

| 구분 | 후보/고정 revision | 용도 |
|---|---|---|
| 우선 학습 후보 | LFM2.5-VL-450M, `fc6221ca597f3315e4f82fc2df606783267b34ba` | 작은 관찰 모델의 행동 대응/단계 상태. 현재 사물 존재 smoke만 검증되어 있어 단계 정확도는 새로 평가 |
| 비교 후보 | Qwen3.5-4B, `851bf6e806efd8d0a36b00ddf55e13ccb7b8cd0a` | 동일 데이터의 기본 모델 및 별도 task LoRA 비교. 처음에는 PC 실험 |
| 유지할 경로 | Qwen ASR/TTS와 기본 대화 VLM | 이 실험에서 ASR/TTS를 함께 학습하거나 기본 대화 가중치를 먼저 교체하지 않음 |

LFM 제조사의 [모델 카드](https://huggingface.co/LiquidAI/LFM2.5-VL-450M)와
[학습 안내](https://docs.liquid.ai/lfm/fine-tuning/overview)는 vision SFT/LoRA,
실제 입력과 동일한 chat template 및 고정 평가셋을 안내한다.
이는 우리 조리 단계 인식이 개선된다는 증거가 아니다.
[LoRA 원 논문](https://arxiv.org/abs/2106.09685)의 저랭크 적응을 후보 방법으로 쓰고
개선 여부는 아래의 동결된 사건/시퀀스 평가로 판단한다.

한 장의 현재 이미지+최근 관측 텍스트가 **현재 Android 배포 계약**이다.
첫 실험도 이 입력으로 학습한다. 여러 원본 프레임을 쓰는 temporal 후보는 별도 PC
실험으로 표시하며, MNN의 한 이미지 경로가 이를 배포할 수 있다고 가정하지 않는다.
모든 고정 revision·export 설정은 configs가 정본이다. 새 revision은 별도 검토 대상으로 기록한다.

## 3. 데이터 원본, 선정 기준과 분리

### 3.1 우선 원본

| 원본 | 얻는 자료 | 사용 방식/현재 접근 상태 |
|---|---|---|
| EPIC-KITCHENS-100 | uncut MP4, action 시작/종료·verb/noun·narration | 첫 핵심 자료. 원본과 공식 train/validation CSV 접근 확인 |
| EPIC VISOR | 사물/손 마스크와 sparse 관측 | 가능한 원본의 사물 근거 검수 보조. sparse 프레임을 연속 영상으로 바꾸지 않음 |
| IKEA ASM | 연속 조립 영상, atomic action 경계·부품 주석 | 2차 작업 도메인. 공개 배포와 영상 CC-BY-NC-4.0 확인, 개별 압축본 접근/용량은 추가 확인 |
| Breakfast | 전체 breakfast 활동과 temporal 주석 | 예비 후보. 공식 raw mirror 미완성/Google Drive quota 때문에 현재 영상 확보 우선순위 낮음 |

원본 링크와 사용 조건은 [선정 보고서](../reports/cooking-task-selection.md)에 모은다.
EPIC는 개별 영상 README·주석 license의 CC-BY-NC-4.0을 따른다.
IKEA code의 MIT를 영상 license로 해석하지 않는다.
현재 허용 범위는 비상업 연구/학습 데모다. 영상·음성·가중치는 git이나 vault에 넣지 않는다.
Ego4D는 접근 승인 전에는 이번 작업에 넣지 않는다.
SuperMemory-VQA는 다른 프로젝트에서 평가에 썼으므로 새 학습/새 평가 자료에서 제외한다.

### 3.2 영상 선정 절차

1. 공식 CSV에서 actor/video별 행동을 시간순으로 묶고 원본 split을 기록한다.
2. 단일 작업의 준비→손질→담기/조립→정리 흐름이 이어지는 후보를 찾는다.
3. 반복 절단, 부분 이송, 무관한 설거지, 가림, 작업 복귀가 포함된 영상을 우선한다.
4. HEAD로 개별 크기·범위 다운로드·원본 주소를 확인하고 다운로드 byte 상한을 둔다.
5. 원본을 실제로 보고 완전한 task 또는 연속된 하위 task의 범위를 검수한다.
6. 전체 파일 해시와 decode/PTS/길이를 확인한 뒤 raw/derived/annotation manifest를 고정한다.

현재 CSV 기반 **미검수 train 후보**는 `P02_122`, `P02_132`, `P03_120`, `P03_121`,
`P04_116`, `P04_04`, `P07_113`, `P07_04`이다.
계란·토마토, 초콜릿/버터 준비, 팬 조리, paneer/양파, 마늘 등 서로 다른 행동을 포함한다.
이 목록은 metadata 후보이며 아직 확보·화면 검수·학습 라벨 승인된 데이터가 아니다.
3~15분의 주석 구간을 찾았지만 실제 MP4 길이/용량은 별도 측정한다.
10분 넘는 원본은 앱 재생 한도를 이유로 임의 반복/가속하지 않고 검수한 연속 하위 task를 쓴다.

### 3.3 분리 규칙

- **원본 참여자+원본 영상 전체**를 단위로 나눈다. 같은 영상의 전후 프레임,
  서로 다른 카메라, VISOR 파생본, 반복 다운로드/동일 해시를 다른 split으로 보내지 않는다.
- 원본 train/validation/test 값과 실험의 train/dev/frozen-test 역할을 별도 필드로 둔다.
  원본 validation/test를 학습·calibration·QAT로 돌리지 않는다.
- 기존 OJT의 P06 validation, P32 test는 train으로 옮기지 않는다.
- 현재 P18_03 및 주석을 확인한 P18_11은 **개발/실패 재현용**이다.
  이번 영상의 실패를 학습 입력으로 복사해 평가 통과를 만드는 방식은 금지한다.
- 새 frozen-test 후보는 train/dev와 참여자가 다른 P08/P26의 공식 validation 영상이다.
  예: P08_09/P08_16. 원본 split 이름을 test라고 바꾸지 않고 실험 역할만 frozen-test로 기록한다.
  실제 영상 적합성을 검수해 목록/해시를 동결한 후 모델 튜닝 과정에서 열지 않는다.
- 영상 ID/순번/특정 타이머 시각으로 답을 외우지 못하도록 모델 prompt에는
  원본 파일명·정답 사건 시각·future action narration을 넣지 않는다.

## 4. 라벨 정의: task와 atomic action은 같지 않다

원본의 `cut cucumber` 구간이 끝났다고 손질 전체가 끝난 것은 아니다.
P18_03에서는 자르기와 일부 담기가 겹치고 이후 다시 자른다.
주석 종료 timestamp를 `complete` 정답으로 자동 변환하지 않는다.

라벨은 두 층으로 만든다.

| 층 | 필드 | 라벨링 방법 |
|---|---|---|
| 원본 행동 | action_id, verb/noun, start/end, overlapping actions | 공식 주석 보존. 경계 오차·중첩 여부 기록 |
| 작업 진행 | task_id, step_id, prerequisites, repeated/substeps, state, evidence | 목표/recipe에 맞는 사람 검수. 단계의 눈에 보이는 결과와 진행 이력 확인 |

`working`: 해당 단계 행동이 현재 근거에서 보인다.
`complete`: 해당 사용자 목표의 작업 단위가 끝난 결과/전환이 관측되었다.
`unknown`: 작업 공간이 가려짐, 관련 물체만 존재, 일시 멈춤과 완료를 구분할 수 없음,
예상 행동이 사진에 없음, 최신 근거가 없음, 목표량/대상/과거 이력이 불명확함.

반복 작업은 개별 칼질/나사 돌리기와 재료 준비/부품 고정의 완료를 구분한다.
계획 순서가 있다고 관측이 완전하다고 가정하지 않는다.
동시에 진행되는 단계는 주석의 중첩 관계를 보존한다. 단일 정답 단계로 억지로 합치지 않는다.
현재 prototype의 순차 단계 코어 한계도 평가에서 기록한다.
아직 보지 못한 양념, 익힘·온도·위생·안전 상태는 정답 사실로 생성하지 않는다.

각 양성 완료 예제에는 **현재 제공 입력만으로 판정 가능한 근거**가 있어야 한다.
원본 annotation이나 이후 영상을 보면 알 수 있지만 현재 입력에서 판단 불가하면 unknown이다.
ingredient 명칭도 annotation에만 있고 화면/사용자 정보로 식별 불가하면 시각 식별을 강제하지 않는다.

## 5. 데이터 생성 방법

### 5.1 실제 영상 시퀀스 생성

1. uncut 영상과 공식 행동 주석을 받아 source manifest/해시/원본 split을 고정한다.
2. 각 영상의 task/step mapping과 완료 판정 근거를 사람이 작성한다.
3. PyAV로 원본 PTS를 보존하며 일반 구간은 약0.2~0.5fps, 경계 후보 ±4~8초는
  1~2fps로 추출한다. 경계 후보는 샘플링 위치일 뿐 정답 완료 시각이 아니다.
4. 시점t마다 **t 이전 근거만**으로 최근 관측/history/진행 ledger를 만든다.
  미래 프레임·후속 narration·정답 completed 목록을 입력에 소급하지 않는다.
5. 현재 프레임, task 지시, active step, 제한된 과거 관측으로 모델 입력을 구성한다.
6. 독립 라벨러가 해당 입력과 source video를 대조해 phase/evidence/출처를 승인한다.
  원본 전체를 본 라벨러도 입력만으로 판단 불가능한 미래 사실은 target에서 제거한다.
7. 원본 행동 경계와 고수준 완료 경계가 다른 사례·가림/중단 사례를 별도로 검수한다.
8. 동결 manifest와 approved-only JSONL을 생성하고 train/dev/test의 그룹·해시 중복을 검사한다.

원본 자료에서 annotation 시작/끝을 이용해 초안 후보를 만드는 것은 가능하다.
그 초안을 승인된 semantic label로 쓰려면 검수가 필요하다.
일반 모델의 현재 오판을 teacher 정답으로 자기 복제하지 않는다.

### 5.2 대화·메모리 시나리오 생성

실제 영상 위에 **합성 시나리오**로 별도 표시한 자연스러운 사용자 문맥을 만든다.
예: “잠깐 옆 사람이랑 얘기하고 올게” → 다른 대화 →
“돌아왔어. 뭐 하고 있었더라?” 또는 “아 잠깐 삼십 초 말고 이십 초.”

영상은 계속 원래 속도로 진행한다. 대화가 있었다고 영상의 행동 주석을 바꾸지 않는다.
합성 문장 자체가 원본 영상의 실제 발화라고 기록하지 않는다.
ASR 평가용 문장은 현재 모의 음성 입력에서 PCM을 실제 속도로 보내고 **실제 인식 결과**를 저장한다.
typed 원문을 인식값이나 모델 입력의 정답으로 덮어쓰지 않는다.

회상 target은 마지막 확인된 진행, 확인 출처, 미확인 단계, 다음 확인할 일을 담는다.
카메라가 끊긴 동안 진행한 일을 추정해서 채우지 않는다.
다른 사람의 발화를 자동 구분하는 능력은 별도 음향/화자 데이터 없이 학습되었다고 주장하지 않는다.
첫 pilot은 명시적 대화 보류/복귀 신호만 다룬다.

### 5.3 어려운 음성·영상 문맥과 부정 예제

- 같은 사진에 다른 active task/step을 제공해 목표와 관측의 결합을 학습한다.
- 컵 설거지·냉장고 열기 등 동일 episode의 무관한 동작은 해당 요리 단계 완료가 아니다.
- 일부 재료 이송 후 다시 자르는 장면, 손이 멈추지만 재료가 남은 장면을 포함한다.
- 과거 설명에는 완료라고 적혀 있지만 현재는 계속 작업하는 모순 문맥을 별도 합성 표시한다.
  명확한 현재 근거/출처를 우선하거나 확인 불가를 답하게 한다.
- 현재 이미지 없음/오래된 입력/관측 공백/reset을 생성한다. reset은 history/ledger도 제거한다.
- 영상 ID와 절대 시간은 숨기고, 시계 offset 변경은 원본 PTS와 별도 합성 필드에 기록한다.
- task 조건을 바꾸는 예제는 실제 사용자 지시로 표시하고 recipe 지시를 완료 사실로 취급하지 않는다.

확대/약한 밝기 변화/모바일 축소는 train에만 적용할 수 있다.
핵심 사물이나 손이 crop/blur로 보이지 않게 되면 양성 label을 그대로 유지하지 않는다.
시간 역전·반복 몽타주·합성 객체 이동을 실제 작업 진행으로 라벨링하지 않는다.

### 5.4 생성 모델을 쓰는 범위

로컬 모델은 영어 annotation의 한국어 초안, 문장 변형, QA/대화 초안, JSON 초안 생성에 쓸 수 있다.
현재 작업 사실·완료 여부·근거 출처의 최종 판정은 source evidence 검수로 정한다.
생성 provenance에는 teacher repo/revision, 입력 범위, prompt hash, seed와 수정/검수 이력을 둔다.
teacher에게 평가용 raw/정답을 넣고 그 결과를 train으로 가져오지 않는다.
유료/cloud teacher는 현재 실행 범위에 없다.

## 6. 데이터 형식과 검수 규칙

canonical record 필수 필드:

| 구분 | 필드 |
|---|---|
| 출처 | dataset, participant_id, video_id, source_split, experiment_role, URLs/license, 원본/주석 SHA256 |
| 미디어 | 원본 PTS, 현재 image 경로/hash, 과거 근거 경로/hash와 시각, 추출/축소 설정 |
| 지시 | 명시적 goal, task/step plan, active step, user constraints, plan 출처 |
| 기억 | bounded prior observations, confirmed/observed/unknown ledger, attention 상태, memory reset/generation |
| target | phase/evidence 또는 auxiliary action/task binding, 허용 근거 출처 |
| 검수 | draft/approved/rejected, reviewer, 근거 구간, 불확실 사유, 판정 수정 이력 |
| 파생 이력 | parent sample/group, 생성 유형, teacher/seed/prompt hash, synthetic dialogue 여부 |

현재 runtime observer의 기본 target은 아래 두 필드다.

```json
{"phase":"working","evidence":"칼을 잡고 오이를 자르는 모습이 보입니다."}
```

이는 형식 예시이며 P18_03의 승인된 train 예제가 아니다.
작업 대응 보조 예제는 별도 `task_type`으로 step/action ID와 근거를 학습하되,
배포 시 기본 phase/evidence 계약이 유지되는지 검사한다.
여러 active/completed step을 반환하는 새 계약은 후속 후보이며 현재 앱 API와 호환된다고 간주하지 않는다.

검수 gate:

- 승인 전 예제는 train/calibration/QAT에 넣지 않는다.
- image/answer/history가 바뀌면 기존 검수를 무효화하고 다시 승인한다.
- 각 history 시각≤현재 시각, 현재 입력≤sample 시각, raw group/split 일관성을 검증한다.
- task plan의 미래 단계나 원본 완료 시각이 visual evidence target에 들어가면 거부한다.
- 완료 양성과 어려운 부정/중첩/출처 모순 예제는 전수 검수한다.
- 평가용 완료·중단 경계는 독립2인 검수를 목표로 한다. 인력 부재 시 draft로 두고 acceptance에 쓰지 않는다.
- 승인된 수, 영상/참여자 수, unique 사건 수, 중복 프레임 수를 별도로 집계한다.

## 7. 규모와 학습 순서

### 7.1 첫 pilot (목표 수량, 아직 생성되지 않음)

train8~12개 원본 영상/최소4참여자, dev3~4영상/별도2참여자,
frozen-test4~6영상/별도2참여자를 확보·검수한다.
train 승인2,000~5,000개 시점/대화 예제를 목표로 하며 완료 사건 최소100개,
dev/test 합계 완료 사건 최소50개를 확보한다.
같은 완료 장면의 인접 프레임100개를 사건100개로 세지 않는다.
우선 확보 byte 예산은20GiB 내로 계획하고 실제 HEAD/가용 디스크로 재산정한다.

목표 sample mix는 phase/transition50%, action-task binding25%,
중단/복귀·출처 기반 기억15%, 근거 부족/모순10%다.
각 mode 내 working/complete/unknown 및 어려운 부정을 재균형하되
평가셋은 실제 영상의 시간 분포와 별도 어려운 사건 목록을 모두 유지한다.
보조 목표가 기본 phase 성능을 개선하는지는 ablation으로 검증한다.

### 7.2 확대 조건

pilot에서 held-out 사건 개선과 일반 기능 회귀 검사가 통과한 뒤,
30~60영상/15,000~40,000예제로 확대한다.
조리법·공간·참여자·카메라·작업 속도·연속/중첩/중단의 다양성을 먼저 늘린다.
IKEA 조립 등 새 도메인을 넣을 때 별도 task mapping/검수/미관측 부품 기준을 둔다.
단일 샐러드 영상을 많은 prompt로 복제하는 것을 다양성 확대라고 보고하지 않는다.

## 8. LoRA 설정과 재현

다음 값은 **첫 실험 설정**이며 측정된 최적값이 아니다.

| 항목 | LFM 관찰 모델 pilot | Qwen 비교 후보 |
|---|---|---|
| 정밀도 | 원본 BF16으로 학습 | 원본 BF16 우선, 메모리 실측 후 대안 결정 |
| LoRA | r8, alpha16, dropout0.05 | r8, alpha16, dropout0.05 |
| 대상 층 | 실제 named_modules를 덤프해 LM attention/projection linear만 확인 | 기존 script의 q_proj/v_proj/in_proj_qkv/out_proj 대상 확인 |
| vision | 첫 run에서 encoder freeze | 첫 run에서 encoder freeze |
| LR | 5e-5부터, 비교2e-5 | 2e-5부터 |
| batch | micro1, accumulation8 | micro1, accumulation4~8 |
| 길이 | prompt+image+target≤2048tokens, 현재 이미지1장 | 동일 입력 계약 |
| optimizer | AdamW, weight decay0.01, clip1.0 | 동일 |
| schedule | warmup3%, cosine, 최대2epochs | 동일 |
| seeds |42,43 |42,43 |
| validation |100optimizer steps마다 dev 사건/시퀀스 검사 | 동일 |

vision projector/상위 vision block의 적응은 LM-only 결과가 한계를 보이고
검수 데이터가 충분할 때 별도 ablation으로 한다.
LFM에 Qwen module 경로를 그대로 적용하지 않는다.
모델별 실제 trainable 이름·파라미터 수·gradient·VRAM peak를 저장한다.
assistant target만 loss에 포함하고 user/history/image-placeholder token은 마스킹한다.
학습과 inference에서 같은 pinned processor/chat template·image 전처리를 사용한다.

기존 `scripts/pc_vlm_experiment.py`의 masked training/LoRA/merge 구조와
export/quantization 도구를 재사용할 수 있다.
그러나 이 script의 데이터/evaluator는 기존 spatial QA용이다.
task JSONL importer, 시퀀스/사건 metric, LFM 대상 층 검증을 추가한 뒤 사용한다.
기존 도구를 실행하면 바로 조리 단계 학습이 된다고 안내하지 않는다.

실행 profile은 external-dev, 호스트 RTX3090 24GB 두 장이다.
단일 GPU1에서 순차 pilot을 기본으로 하고 매번 doctor/preflight로 실제 가용 VRAM을 확인한다.
native/Qwen 호스트 실행과 학습을 GPU1에서 겹치지 않으며 무관한 프로세스를 중단하지 않는다.
CPU 검증은 `.venv`; 학습/export는 모델별 격리 환경을 쓴다.
현재 ft lock은 torch2.8.0/transformers5.19.0/peft0.21.2이다.
LFM 호환성을 확인하고 다르면 이 프로젝트의 별도 환경/lock으로 기록한다.
다른 프로젝트 환경이나 기존 worker pin을 변경하지 않는다.

실험 단계는 gradient smoke → baseline → LoRA → merged BF16 → PTQ/export →
동일 test → Android 실제 inference 순서다.
PTQ calibration에는 train만 사용한다. QAT는 PTQ 품질 손실이 확인될 때 후속으로 분리한다.
실험 run에는 source/config/data/review hashes, seed, 단계별 loss, 모델/환경 revisions,
VRAM/시간, 오류·중단 원인, adapter/merged/export hashes를 저장한다.

## 9. 평가와 채택 기준

하네스 성공과 모델 정확도를 따로 보고한다.
0개 complete를 내거나 항상 unknown으로 답하는 모델은 오개입이 적어도 합격이 아니다.

| 평가 | 방법 | 초기 채택 목표(보장 아님) |
|---|---|---|
| 상태 분리 | phase macro-F1, task/step binding 정확도, overlapping action 별도 집계 | base 대비 개선, 모든 핵심 class coverage 확보 |
| 완료 사건 | 독립 실제 사건의 precision/recall, 최초 완료 판단의 지연 | precision≥95%, recall≥80%, 완료 사건≥50개 평가 |
| 오개입 | 아직 진행/일시 중단/무관한 작업인데 complete 및 다음 안내 | 실제30분당1회 이하 목표; 중요한 오판은 사례별 검수 |
| 시간 경계 | ground truth 경계±2초/±5초 사건 검출, 미검출 따로 계산 | 지연/조기 판정 모두 공개 |
| 복귀 기억 | 같은 task의 완료/미확인/다음 단계·출처를 독립 채점 | camera-off/reset/다른 대화에서도 사실 보존; 삭제 뒤 회상0 |
| 근거 없음 | 미관측 현재를 단정하는지 | unseen state/출처 바꿔치기0 목표 |
| 회귀 | 기존 사물 존재/위치·일반 질의·음성 시나리오의 동결 평가 | 기준 대비 하락하면 기본 대화 모델 교체 보류 |
| 실기기 | cold/warm model time, 첫 text/PCM/write/drain, 입력 유지, 메모리/thermal | 관찰 p50≤1.5초/p95≤2.5초 목표. 측정 전 주장 금지 |

baseline/LoRA/merged/PTQ는 **같은 테스트 목록·입력·범위·기억·prompt·seed**로 비교한다.
학습 전에 원본 Transformers BF16과 현재 MNN INT4에 같은 frozen 입력을 넣어
전처리·chat template·양자화/export·backend 차이도 확인한다.
원본은 맞고 배포본만 틀리는 경우를 모델의 학습 부족으로 단정하거나 LoRA로 덮지 않는다.
current-image only, image+실제 과거 관측, no-image, noisy history, reset을 ablation한다.
teacher-forced history와 모델이 직접 만든 history로 굴린 closed-loop 성능을 따로 낸다.
frame 수가 아니라 영상/참여자/완료 사건 단위의 분산·신뢰구간을 보고한다.
dev로 checkpoint/LR/rank를 고르고 frozen-test는 최종 한 번 비교한다.
test 실패를 이용해 바꾼 후보는 그 test를 새로운 blind evidence로 재사용하지 않는다.

LoRA는 단계 의미를 개선할 가능성이 있지만 입력에 없는 시간 정보를 복구하거나
큰 모델의 prefill 비용을 없애는 방법은 아니다.
좋은 라벨에도 한 장 입력의 한계가 남으면, 시퀀스 표현·시간 모델·더 적합한 관찰 모델을
별도 비교하며 이를 조건문으로 정답화하지 않는다.
실제 Galaxy S 물리12GB, 사람의 마이크/카메라, 주방 소음·여러 화자와
10회 끼어들기/5분 이상 Live 검사는 현재 태블릿/합성 음성 결과로 대체하지 않는다.

## 10. 실행 전 산출물과 순서

| 단계 | 만들어야 할 산출물 | 종료 조건 |
|---|---|---|
| H0 현재 | 안정된 하네스, 미디어/빌드/입력 해시, 원문 모델 출력과 독립 판정 | 모델 실패를 보정 없이 반복 재현 |
| D1 선정 | candidate index, 다운로드/라이선스/원본 split manifest | 원본 영상 decode·과정 검수 완료 |
| D2 라벨 | task/atomic mapping, 완료/중단/unknown 근거, review manifest | 승인된 양성/어려운 부정, 누출 검사 완료 |
| D3 생성 | causal 시퀀스/합성 대화 JSONL, 파생 그룹/teacher 이력 | approved-only 데이터와 frozen-test hashes 고정 |
| T0 연결 | task importer/evaluator와 모델별 gradient smoke | masking/층 선택/CPU 검사·실제 GPU smoke 완료 |
| T1 학습 | seed별 adapter, dev 보고서와 ablation | 독립 사건 개선 및 오개입·회귀 기준 충족 |
| E1 배포 후보 | merged/PTQ/MNN parity, 새 manifest와 APK | 실제 동일 fixture 및 paced replay 검증 |
| A1 최종 | 실기기/사람 Live 보고서 | docs/verification.md의 최종 acceptance 충족 |

지금 실행하는 것은 H0와 계획 문서 작성이다. D1 이후의 추가 수집·라벨 승인,
LoRA 학습·새 모델 설치가 완료된 상태로 보고하지 않는다.
