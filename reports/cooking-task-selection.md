# 실제 작업 데이터 선정과 하네스 — 2026-10-14

사용자 지시에 따라 모델의 단계 분리 약점을 규칙으로 보정하는 후보를 제거했다.
실제 영상으로 입력/출력/기억/취소를 재현하는 하네스와
[상세 LoRA 학습 계획](../docs/task-lora-plan.md)을 분리했다. 아직 학습을 실행하지 않았다.

## 확보한 자료

첫 실제 과제는 EPIC-KITCHENS `P18_03`의 샐러드 준비다.
채소 담기, 건포도 추가, 오이 씻기·손질·담기, 양념, 섞기가 이어지고
중간에 설거지·냉장고·다른 음식의 오븐 작업이 있다.
원본427.435초/1,611,750,783bytes와 공식 행동 주석113개를 확보했다.
처음부터 끝까지 decode한640×360/~15fps 무음 재생본은41,192,324bytes/427.467초/6,412frames다.
원본 순서와 속도를 유지했다. 반복 장면이나 과정을 건너뛴 편집으로 단계 완료를 만들지 않았다.

| 항목 | 기록 |
|---|---|
| 원본 SHA256 | `46485f3de703d921284e84aea05b52a1a6b8e1655811ebe0a1d78713506132eb` |
| 재생본 SHA256 | `d34a8e194e82c5d2cbc3160cb01bb970bdc7f55106267f352c5e3c96760a69a4` |
| 원본 split | EPIC-55 test / EPIC-100 action recognition validation |
| 실험 역할 | 개발용 재현·진단, fresh blind test/train/calibration 아님 |
| 기존 OJT 중복 | 기존 P01/P06/P32와 참여자 다름. P18은 이번 개발 평가로 별도 보존 |
| 라이선스 | 원본 README 및 annotation license의 CC-BY-NC-4.0, 비상업 연구 조건 |
| 위치 | ignored `.runtime/task-video/`; 기기 Download 및 앱 진단 폴더 |

[공식 raw video README](https://data.bris.ac.uk/datasets/3h91syskeag572hl6tvuovwv4d/readme.txt),
[원본 영상](https://data.bris.ac.uk/datasets/3h91syskeag572hl6tvuovwv4d/videos/test/P18/P18_03.MP4),
[EPIC-100 주석](https://github.com/epic-kitchens/epic-kitchens-100-annotations),
[개별 주석 license](https://raw.githubusercontent.com/epic-kitchens/epic-kitchens-100-annotations/master/license.txt).
포털의 일반 license 필드와 개별 파일 notice가 다르므로 개별 notice를 보존했다.
전체 출처·변환·해시는 [미디어 manifest](cooking-task-media-manifest.json)에 있다.

## 비교한 후보

| 후보 | 적합성 | 선정/보류 이유 |
|---|---|---|
| EPIC-KITCHENS | 실제 주방의 uncut 작업과 행동 시각 주석 | raw 개별 다운로드/주석 확보. 첫 샐러드 과제 선정 |
| Breakfast | 전체 breakfast 준비와 단계 구간 | [공식 배포](https://huggingface.co/datasets/Serrelab/breakfast-actions)는 raw mirror 미완성 및 Google Drive quota를 명시. features archive를 영상으로 취급하지 않음 |
| IKEA ASM | 조립 과정, 부품·동작·경계 | [공식 프로젝트](https://ikeaasm.github.io/)의 비상업 영상 license와 공개 배포 확인. 큰 분할 압축본의 실제 개별 접근/검수는 후속 |
| VISOR 기존 자료 | 사물/손 및 과거 근거 검수 | 이미 가진 sparse JPEG를 연속 과정으로 재구성하지 않음. 원본과의 검수 보조 |

## 모델 약점과 제거한 우회 후보

Android0.27의18장 Qwen 실제 진단은 모두 원문 JSON을 보존했다.
완료된 장면에서도 working을 반복하고 현재 장면과 작업 지시/과거 설명을 혼동했다.
complete0/18, 자동 단계 전환0/2진단 그룹. 모델 호출 중앙값11.892초,
범위6.566~22.876초로 단계 판단과 속도 모두 미달이다.
이는 전체 7분 video replay가 아닌 시점별 실제 모델 진단이다.
일부 이전 단계는 fixture에서 사용자 확인으로 초기화했으며 시각 기억 합격으로 세지 않는다.
[원문](cooking-task-general-results.json) · [사전 기준](cooking-task-expected.json).

작은 관찰 모델에 사물/행동별 질문을 보내는 우회 후보는 중앙값1.033초였지만
완료를 놓쳤다. 속도가 빨라졌다고 과정 이해 개선으로 보지 않는다.
[후보 원문](cooking-task-bounded-v1-results.json)은 실패 이력으로 남기고
새 progressQuestions/observeTask 규칙 경로와 씻기 완료 대체 문법을 소스에서 제거했다.
이미 있던 수동 cooking의 사물 존재 알림은 기존 기능이며 새 단계 판정에 쓰지 않는다.

첫 fixture 접근 오류는 adb가 만든 하위 폴더의 접근 권한이었다.
모델 호출 전 오류이며 [오류 기록](cooking-task-checkpoints-access-failure.json)을 보존했다.
공개 테스트 사진 폴더의 읽기 권한을 수정한 뒤 실제 진단을 실행했다.
우회 후보를 사용하는 전체 replay는 사용자 방향 수정 후 중단했다. 합격 근거로 쓰지 않는다.

## 안정화한 하네스

- 연속 MP4와 별도 모의 PCM을 독립적으로 입력한다. 입력은 추론을 기다리지 않는다.
- 모델 observer는 native phase/evidence를 그대로 기록한다. annotation 시각으로 완료를 실행하지 않는다.
- 거부된 오래된 결과/잘못된 JSON도 해시가 고정된 실제 선택 프레임과 함께 기록한다.
- 발화 당시 프레임을 고정해 ASR 뒤의 미래 프레임으로 바뀌지 않는지 검사한다.
- 자연스러운30→20초 정정, AI 재생 중 새 입력, 다른 대화 동안 침묵,
  복귀 뒤 저장된 진행 확인을 실제 ASR/TTS 경로로 검사한다.
- 직접 회상/도구 상태/모델 응답 경로와 사실·미확인 진행을 구분한다.
  모델이 완료를 놓친 결과는 품질 실패로 남기며 transport 성공에 포함하지 않는다.

CPU 상태/프로토콜 검사는53개이며 Android ARM64 빌드/lint를 수행한다.
마지막 전체 replay의 측정·APK·기기 상태는 실행이 끝난 뒤 이 보고서에 추가한다.
이는 Qwen system duplex이며 native duplex, 사람/주방/물리12GB Galaxy S acceptance가 아니다.
