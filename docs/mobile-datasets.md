# 공개 데이터 기반 모바일 QA 학습 구성

**2026-10-12 PC 실행 후속:** [실제 학습·양자화·E2E 결과와 한계](../reports/mobile-pc-validation.md), [재현 가이드](mobile-pc-runbook.md)를 기준으로 현재 상태를 확인한다. 아래 계획/후보 설정과 완료량을 구분한다.

2026-10-12 사용자 확정: **비상업 연구·학습 데모**, 최신 Galaxy S, **기기 전체 RAM 12GB 상한**, 인터넷 없는 물체 위치·최근 관측 QA. NPU/Qualcomm 전용 최적화는 후속이다. 이 문서는 데이터 선택·가공 계획이며 아래 목표 수량은 아직 확보하거나 학습한 수량이 아니다.

## 선택

**1차: GQA + EPIC-KITCHENS VISOR + 두 데이터에서 파생한 답변 유보 사례.** Ego4D VQ2D는 접근 승인 후 확장한다. 작은 CLEVR 세트는 학습 코드 진단용이다. 이미 다른 프로젝트의 평가에 소비한 SuperMemory-VQA는 학습/calibration/신선한 test에서 제외한다.

| 소스 | 사용할 부분 | 가르칠 행동 | 범위/제약 |
|---|---|---|---|
| GQA | scene graphs v1.1, questions v1.2의 balanced train, 해당 원본 이미지 | 물체 식별, 색 구분, 화면/물체 기준 위치 관계 | 정지영상이다. 시간 순서를 가진 기억 데이터로 둔갑시키지 않음 |
| EPIC-KITCHENS VISOR | GroundTruth-SparseAnnotations의 train/val JSON + sparse RGB | 관측 순서, 같은 물체의 전후 모습, 마지막 제공 관측의 위치 | 주방 편향. sparse 주석이고 완성된 자연어 QA는 아님 |
| 근거 제한 파생 QA | 위 원본의 현재 frame 제거/만료, history만 제공, reset | 현재 확인 불가, 과거 위치와 현재 위치 구분 | 파생본은 원본과 같은 split. 이미지에 안 보임과 주석에 없음은 다름 |
| Ego4D Episodic Memory VQ2D | 승인 후 visual query와 response track | 마지막으로 본 물체의 시간·2D 위치 | 접근 승인 필요. 이미지 crop query를 텍스트 객체명으로 바꾸면 별도 파생 과제 |
| CLEVR v1.0 | 소량 scene graph/이미지/질문 | 관계·색·대상 구분의 학습 코드 smoke | 합성 도형이므로 컵/주방 실환경 성능 근거가 아님 |

### 공식 근거와 접근

- **GQA:** 공식 다운로드는 scene graph v1.1(42.7MB), 질문 v1.2(1.4GB), 이미지를 분리 제공한다. 객체 bbox/속성/관계와 question/answer/fullAnswer/semantic 필드를 사용할 수 있다. 페이지 HTML 하단에 CC BY 4.0 링크가 있으나, 원본 이미지 권리까지 일괄 허가되었다고 확대 해석하지 않고 실제 배포물의 고지와 이미지 출처를 보존한다. https://cs.stanford.edu/people/dorarad/gqa/download.html
- **VISOR:** 공식 README는 수동 sparse 주석과 프레임, `exhaustive`, `subsequence`, frame mapping을 설명하고 파일에 CC BY-NC 4.0을 명시한다. 사용자가 확정한 비상업 데모 범위로 사용하며 출처·변경 내역을 보존한다. https://data.bris.ac.uk/datasets/2v6cgv1x04ol22qp9rm9x2j6a7/README.txt
- VISOR 포털의 전체 배포 표시 크기는 28.4GiB이다. 먼저 수동 annotation과 선택한 영상별 sparse frame ZIP만 받는다. 포털의 일반 Licence 필드는 다른 non-commercial 명칭을 표시하므로, 데이터별 README/프로젝트의 CC BY-NC 고지를 함께 저장한다. https://data.bris.ac.uk/data/dataset/2v6cgv1x04ol22qp9rm9x2j6a7 · https://epic-kitchens.github.io/VISOR/site
- **Ego4D VQ2D:** 질의 객체의 마지막 관측을 시간 구간과 bbox로 찾는 과제라 도메인 적합도가 높다. 공식 절차상 약관 확인/동의·접근 승인이 선행한다. 별도 계약이므로 단순 CC 비상업 데이터로 분류하지 않는다. 현재 이 프로젝트의 접근 권한은 확인하지 않았다. https://ego4d-data.org/docs/benchmarks/episodic-memory/ · https://ego4d-data.org/docs/start-here/
- Ego4D의 공개 계약 문서에는 허용된 모델 개발과 원본 영상 제공의 범위를 별도로 규정한다. 학습 가능성을 원본 영상의 데모 앱 동봉/재배포 허용으로 해석하지 않는다. 실제 수락 계약에 따라 확인한다. https://ego4d-data.org/pdfs/Ego4D-Licenses-Draft.pdf
- **CLEVR:** 제작자가 전체 데이터 CC BY 4.0을 명시한다. 실영상 준비와 별개로 작은 학습 루프의 정상 동작 확인에 사용 가능하다. https://cs.stanford.edu/people/jcjohns/clevr/
- SuperMemory-VQA는 장기 관측 기반 평가 데이터다. 기존 vault의 소비된 평가 기록을 확인했으므로 이번 학습과 독립 평가에서 제외한다. 그 프로젝트의 점수나 실험 정책을 이번 모델 성능으로 가져오지 않는다. https://supermemory-vqa.github.io/

## 1차 학습 규모

실제 레코드 수는 필터링·권리/파일 확인 후 확정한다. 아래는 **중복을 제외한 학습 QA 목표**이며 같은 자료를 반복해 수량만 채우지 않는다.

| 구성 | pilot train | 본 실험 train | 주요 시나리오 |
|---|---:|---:|---|
| GQA 현재 장면/관계 | 800 | 8,000 | QA01~03, QA11 |
| VISOR 현재·과거 제공 관측 비교 | 800 | 8,000 | QA04~06 |
| 근거 제한·답변 유보 파생본 | 400 | 4,000 | QA06~09 |
| 합계 | **2,000** | **20,000** | 자연어 한국어 QA |

- 시작은 class별 20~50개를 사람이 점검하는 **200 QA 이내의 변환 감사**다. 그다음 32~64개 overfit smoke, 2,000개 pilot, 결과가 유효할 때 20,000개로 확장한다.
- 검증 목표는 별도 validation 1,000개 / frozen OJT test 1,000개다. 원본 공식 test의 숨겨진 정답을 만들지 않으며 이 수치는 공식 벤치마크 전체 점수가 아니다.
- PTQ calibration은 train에서 256개부터 512개까지 균형 추출한다. text-only/현재 1장/history 포함 요청을 모두 넣는다. 실제 방법이 calibration을 요구할 때만 사용하고 validation/test는 제외한다.
- QAT도 같은 train만 사용한다. PTQ와 QAT에 서로 다른 유리한 test를 고르지 않는다.
- 첫 물체 후보: 컵/머그, 병, 그릇, 접시, 냄비, 수저, 책, 휴대폰. 해당 클래스가 실제 주석에 있는 경우만 사용한다. GQA와 VISOR 이름을 한국어 소형 사전으로 정규화하되 cup/mug처럼 식별에 필요한 차이는 남긴다.
- 숫자는 데이터 확보량이나 권장 보편 학습량을 주장하는 값이 아니라 이 OJT의 첫 실험 예산이다.

## QA 만드는 방법

### GQA: 현재 장면

1. 주방/실내 소품 중 bbox가 유효하고 대상이 식별 가능한 장면을 고른다. 글자만 보고 답할 수 있는 언어 편향/단순 yes-no 사례가 대부분이 되지 않게 한다.
2. 객체/관계 annotation으로 뒷받침되는 위치 질문을 우선한다. bbox 중심은 **화면 왼쪽/가운데/오른쪽**의 근거이고, bbox 겹침만으로 “책상 위”라는 지지 관계를 만들지 않는다.
3. 같은 종류가 여러 개일 때 색/속성으로 대상이 유일하게 정해지는 사례와, 모호해서 되묻는 사례를 구분한다.
4. 기존 영문 question/fullAnswer를 한국어로 옮기되 관계·부정·색·대상 ID를 검증한다. 변환 모델을 쓰더라도 주석에 없는 위치를 정답으로 만들지 않고, 첫 감사 세트는 전량 사람 검수한다.

### VISOR: 제공된 관측의 시간 구분

1. 처음에는 수동 sparse annotation만 사용한다. 자동 보간 dense mask는 실제 정답과 분리하고 필요할 때 확장한다.
2. 같은 영상/하위 시퀀스의 프레임을 시간순으로 배열한다. mask `id`가 영상 전체의 영구 객체 ID라고 가정하지 않는다. 이름/클래스가 같다는 이유만으로 다른 컵을 동일 물체로 연결하지 않으며 불명확한 쌍은 제외/검수한다.
3. `frame_mapping.json`과 원본 FPS/시각 정보를 검증해 timestamp를 계산한다. 임의의 30fps 또는 filename 숫자만으로 밀리초를 만들지 않는다.
4. **현재 1장 + 과거 최대 2장**을 한 예제로 구성한다. 과거 frame의 시각과 `history` 표시는 모델 prompt에도 포함해야 한다. JSON metadata만 두고 processor에서 버리면 시간 구분 학습이 되지 않는다.
5. 화면 위치 변화는 카메라 움직임일 수 있다. 물체가 물리적으로 이동했다는 답변은 추가 관측/검수로 확인한 사례에만 붙인다.
6. sparse frame에서 마지막으로 보였다는 것은 **제공된 관측 중 마지막**이라는 뜻이다. 연속 영상 전체에서의 진짜 마지막 관측이라고 주장하지 않는다. 이 기능의 retrieval 평가는 Ego4D VQ2D 확장/실기기 replay로 따로 한다.

### 답변 유보: 거짓 부정 레이블 방지

- 주석에 컵이 없다는 이유만으로 “컵이 없다”를 만들지 않는다. 특히 VISOR `exhaustive=n/inconclusive`는 부정 예제 근거가 아니다.
- 첫 파생본은 확실한 입력 조건을 쓴다: 현재 frame을 제공하지 않고 history만 남긴 뒤 “지금?”을 묻거나, reset 뒤 관측을 비우고 과거를 묻는다.
- 기대 답변은 “현재 위치는 확인할 수 없습니다. 마지막 관측에서는 …”처럼 근거 범위를 제한한다. 실제로 화면에서 사라진 물체의 부정 QA는 원본 프레임 검수를 거친다.
- 가림·미관측·삭제와 무관한 질문에 무조건 “모르겠습니다”만 답하는 편향도 별도 측정한다.

예시 구조(실제 데이터에서 생성한 결과가 아닌 가공 규칙 예시): `history(t=10초, 컵 왼쪽)` + `current(t=20초, 컵 오른쪽)`에 “지금?”과 “아까?”를 각각 묻는다. 두 예제는 같은 source group에 둔다. **LoRA로 물건의 실제 위치를 외우게 하지 않고 제공된 증거를 읽는 방법을 학습한다.**

## 분할과 평가 계약

- 원본 공식 train에서 학습/개발 세트를 만들고, 공개 정답이 있는 공식 val의 독립 그룹 일부를 OJT test로 고정한다. 공식 숨김 test는 임의 레이블을 생성하지 않는다.
- GQA는 image ID로 묶고 파생 QA 전부를 같은 split에 둔다. 다른 소스와 섞을 때 원본 COCO/VG ID와 이미지 해시 중복도 제거한다.
- VISOR는 participant/kitchen 식별 정보로 상위 그룹을 만든다. 선택한 test 참여자의 train 영상은 학습에서 제외한다. clip만 나누어 같은 사람/주방이 새 test인 것처럼 보고하지 않는다. 공식 분할 전체의 성능과 이 OJT subset 성능을 구분한다.
- 추가 Ego4D는 canonical video/participant 단위로 분할한다. 잘라낸 clip끼리 다른 split에 들어가지 않게 한다. 질의 시점보다 미래인 crop/frame은 기억 입력으로 주지 않는다.
- evidence frame을 정답 주석으로 골라 주는 **근거 제공 QA 평가**와, 앱이 스스로 최근 관측을 저장/검색하는 **E2E 기억 평가**를 구분한다. 전자의 좋은 점수만으로 검색/기억 동작을 확보했다고 판단하지 않는다.
- 한국어 텍스트 QA 성능과 ASR→QA→TTS 음성 E2E를 분리한다. 이 데이터 조합은 VLM FT용이며 ASR/TTS 학습 데이터를 확보한 것이 아니다.

## 저장/가공 작업

- `.runtime/ojt/datasets/<source>/` 아래 원본·다운로드 URL·해시·라이선스 고지를 보관한다. 이미지/영상/데이터 원본은 git과 vault에 넣지 않는다. 승인용 인증정보는 기록하지 않는다.
- source manifest에는 release/URL/hash/원본 split/image 또는 video ID/participant/한국어 변환 버전/검수 상태를 기록한다. 개별 레코드 provenance는 `id`로 연결한 sidecar에 보존한다.
- `prepare_ojt_data.py`는 `--split-map`/`--provenance`를 함께 받아 공식 분할을 보존한다. 공식 train은 OJT train/validation, 공식 val은 OJT test에만 배정한다. 자동 80/10/10 분할은 공식 train/val을 합친 데이터에 사용하지 않는다.
- GQA 정지 화면 위치 adapter와 출처 검사를 구현했다. 후속: GQA 이미지/한국어 검수 → VISOR 소량 annotation schema 감사 및 시간 QA adapter → pilot LoRA → PTQ/범용 QAT 비교.
- 원본 다운로드/형식 확인, 실제 이미지 QA 생성, LoRA 학습, 모바일 실행은 각각 별도 완료 상태로 기록한다. 이 계획을 쓴 것만으로 데이터 확보/모델 학습을 완료 처리하지 않는다.

## 첫 실행 가능한 GQA 감사 파이프라인

`import_gqa_ojt.py`는 공식 sceneGraphs v1.1 ZIP의 train/val을 읽는다. 기본 200개 후보는 train 140, validation 30, test 30의 서로 다른 이미지다. 이 수량은 pilot 2천 개와 별개인 변환 감사용이다.

```bash
.venv/bin/python scripts/import_gqa_ojt.py .runtime/ojt/datasets/gqa/sceneGraphs.zip \
  --output .runtime/ojt/gqa-audit-002
.venv/bin/python scripts/fetch_gqa_images.py .runtime/ojt/gqa-audit-002/media-requests.jsonl \
  --output .runtime/ojt/gqa-images-002
.venv/bin/python scripts/prepare_ojt_data.py .runtime/ojt/gqa-audit-002/records.jsonl \
  --media-root .runtime/ojt/gqa-images-002 --output .runtime/ojt/gqa-prepared-audit-001 \
  --split-map .runtime/ojt/gqa-audit-002/split-map.json \
  --provenance .runtime/ojt/gqa-audit-002/provenance.jsonl --allow-unreviewed
.venv/bin/python scripts/review_ojt_data.py .runtime/ojt/gqa-audit-002 \
  --media-root .runtime/ojt/gqa-images-002 --output .runtime/ojt/gqa-review-001.html
```

출력 디렉터리는 덮어쓰지 않으므로 재실행 시 새 이름을 쓴다. 원본 ZIP은 공식 [다운로드 페이지](https://cs.stanford.edu/people/dorarad/gqa/download.html)의 scene graphs 링크에서 받는다. 이미지 도구는 공식 ZIP의 필요한 바이트만 요청하며 서버가 범위 요청을 무시하면 전체 다운로드를 거부한다. 최대 200장/HTTP 누적 128MiB, 이미지당 8MiB, JPEG 최대 1280px이며 원본 JPEG/CRC/SHA256을 보존한다. 압축 파일 전체를 임의 경로에 풀지 않는다. 다운로드 실패는 `incomplete` manifest로 남긴다.

- 첫 adapter는 기존 GQA 질문 번역이 아니라 **bbox 중심의 화면 삼등분**으로 만든 한국어 QA다. 지지 관계/세계 좌표/물체 이동을 생성하지 않는다. 경계 근처 bbox와 동일 종류 중복 주석을 제외한다. 주석 누락에 의한 모호함은 이미지 검수로 확인한다.
- `0ms`는 정지 화면의 합성 시각 기준이다. 영상 촬영 시각이나 기억 QA로 해석하지 않는다. 화면상 위치 데이터만으로 최근 관측 기억 학습을 완료한 것이 아니다.
- `records.jsonl`, `provenance.jsonl`, `split-map.json`, `media-requests.jsonl`, `manifest.json`을 출력한다. 레코드와 출처는 ID로 연결하며 원본 ZIP/멤버 SHA256, 객체 ID/bbox, 공식 split, 변환 버전, 검수 상태를 보존한다.
- 기본 `pending` 레이블은 준비 도구가 거부한다. `--allow-unreviewed`는 형식 감사용으로만 통과시키며 `labels_approved=false`, `model_acceptance=false`다. 이미지를 전량 검수한 뒤 별도 검수본의 상태를 `approved`로 바꾸고 `media_sha256`에 검수한 이미지 경로→SHA256을 기록한다. 답변을 수정하면 `record_sha256`도 검수본 내용으로 갱신한다. 원본 감사본은 보존한다.
- 분할표는 그룹 전체와 세 split을 정확히 포함해야 한다. 원본 이미지 ID alias/동일 예제/동일 이미지 해시의 split 간 중복, 누락·중복 provenance, 검수 이후 레코드·이미지 교체를 거부한다. 근접 이미지와 잘못된 사람 검수를 자동 판별하는 기능은 아니다.
- 승인은 학습 전 데이터 확인 상태이며, 학습/모바일 acceptance를 의미하지 않는다. 원본 이미지 권리 고지와 비상업 데모 범위는 위의 소스 정책을 유지한다.

검수 HTML에는 이미지가 포함되어 인터넷 없이 열린다. 대상 상자를 숨겨 답변의 자연스러움도 확인하고 `적합`/`수정 필요`를 선택한다. `판정 파일 저장`은 검수한 이미지 해시가 포함된 provenance JSONL을 내려받는다. 자동 승인은 없고 페이지를 닫으면 저장하지 않은 판정은 사라진다. 수정 필요 항목의 응답 수정/제외는 별도 검수본에서 처리한 뒤 다시 준비 도구에 넣는다. 공개 공유용 페이지가 아니라 로컬 데이터 검수 산출물이다.
