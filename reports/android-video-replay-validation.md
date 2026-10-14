# Android 0.26 영상·음성·관측 기억 검증

2026-10-14, SM-X926N / Android 16 / MT6989 / Mali-G720. Qwen **시스템 duplex**
경로다. MiniCPM native 경로는 변경하지 않았다. 최종 계측 결과는 아래에 기록한다.
사람 마이크·음향 및 전체 Live 합격과는 구분한다.

## 구현과 사용

- 라이브 화면의 카메라 버튼을 길게 누르면 로컬 샘플 영상을 선택할 수 있다.
  V 배지는 샘플 영상, M 배지는 모의 음성 입력이다. 영상은 무음으로 재생되고
  기존 마이크 수집과 병행한다. 같은 메뉴에서 다시 재생하거나 영상만 끌 수 있다.
  기기 `다운로드/Live-sample-BigBuckBunny-20261014.mp4`를 준비했다. 최종 앱에서
  실제 메뉴 → 시스템 파일 선택 → 토끼 영상 표시/V 배지까지 확인했다.
  검증 뒤 수집은 일시정지했으며, 선택한 영상은 유지해 라이브 시작으로 재생할 수 있다.
  [UI 확인 기록](video-scenario-ui-validation.json).
- 최대 256MiB/10분/1920픽셀 영상을 2fps 이하, 최대 640픽셀 JPEG로 읽는다.
  인코딩 한 건만 허용하며, 정렬 버퍼는 8장/4MiB/4초로 제한한다.
- 발화의 마지막 유성 PCM 처리 시각 이전 3초 이내 프레임을 확정 시 고정한다.
  ASR·VLM이 늦어져도 새 장면으로 교체하지 않는다. 영상 off 시 현재 근거는 없다.
  이는 센서/decoder PTS 또는 단어 시각 정렬이 아니다. 시간 오차는 측정하지 않았다.
- “이 동물이 어디 있는지 봐 두고 기억해 줘”처럼 명시적으로 요청한 경우,
  실제 선택 프레임에 대한 완료 답변을 관측 시각·영상 위치와 함께 보관한다.
  일반 대화 마지막 몇 턴과 분리해 타이머 목표가 바뀌어도 유지한다. 사용자의
  기억 끄기/기간/최대 2개 설정을 따르고, 세션 종료·초기화 시 삭제한다.
  모델이 작성한 과거 설명이며, 검증된 사실 DB나 현재 화면의 대체물은 아니다.
- 현재 영상 없는 명시적 시각 회상은 이 저장 설명을 그대로 읽는다. 현재 위치
  참조에는 확인 불가를 안내하고, 기억이 없으면 없다고 답한다. 새 VLM 추론으로
  과거 기록을 재작성하지 않는다. 영상 있는 관측·과거/현재 비교는 실제 VLM이다.
  각 답변의 `response_path`에 이 구분을 기록한다.
- “일단 삼십 초 뒤에 알려 줄래”, “아 잠깐 삼십 초 말고 이십 초”,
  “아냐 그건 취소해 줘”를 확정 ASR에서 처리한다. 정정 대상과 이전 시간이
  일치하지 않으면 실행하지 않고 확인한다. 제한된 회화 문법이며 범용 의미 추론은 아니다.

## 고정 시나리오와 증거

[판정 기준](video-scenario-protocol.md)을 측정 전에 고정했다. 토끼/위치 정답을
사용자 발화나 모델 프롬프트에 넣지 않았다. 남성 로컬 TTS를 실제 속도의 PCM으로
입력하여 생산 Capture/VAD/Qwen ASR/VLM/AudioTrack/타이머 경로를 실행한다.
실제 사람의 발화나 음향 회수 입력은 아니다.

영상은 Big Buck Bunny 예고편에서 움직이는 짧은 구간을 반복 편집했다.
0–60초 토끼·풀밭 → 60–90초 작은 동물들 → 90–300초 토끼 없는 나무·풀밭이다.
원본의 연속 사건이나 이동 추적 데이터가 아니다.
[출처·해시·편집](video-scenario-media-manifest.json),
[원본](https://media.w3.org/2010/05/bunny/trailer.mp4),
[Blender 출처 표시·CC BY 3.0](https://peach.blender.org/about/).
(c) copyright 2008, Blender Foundation / www.bigbuckbunny.org.

13개 발화는 기억 요청 → 영상 설명 중 끼어들기 → 30초 설정/20초 정정 →
말하는 중 만료/답변 뒤 안내 → 안내 중 보류/재개/1회 전달 → 새 장면과 과거 비교 →
영상 없이 회상/현재 상태 질문 → 새 타이머 취소/만료 후 무알림 →
기억 삭제 후 **동일 회상 질문**이다. 이 마지막 대조로 기억 의존 여부를 확인한다.

## 측정 결과

최종 **3회·39발화·670,561ms(약 11분 11초)** 완료. 실제 기기 GPU 활성 상태이며,
CPU 52 tests와 assembleDebug/lintDebug가 통과했다. 실행 전후 검증 대상 소스 해시가
같다. [원본](video-scenario-results.json), [계측 요약](video-scenario-summary.json),
[전문·선택 프레임 SHA256에 연결한 판정](video-scenario-reviews.json).

| 판정 대상 | 결과 |
|---|---|
| 동시 영상/실제 AudioRecord 수집·제어 시나리오 | 3/3 |
| 30초 설정 → “아 잠깐 삼십 초 말고 이십 초” 정정 | 3/3 |
| 발화 중 만료·답변 뒤 자발 안내·보류·재개·1회 전달 | 각각 3/3 |
| 새 10초 타이머 대명사 취소·만료 뒤 무알림 | 3/3 |
| 옛 epoch PCM 거부 | 39/39, 이 중 재생 중 보낸 입력 12건 |
| 타이머 작업 뒤 관측 유지·과거/현재 비교 | 3/3 |
| 영상 없이 회상·현재 확인 불가·reset 후 같은 질문에 기억 없음 | 각각 3/3 |
| 사전 고정한 관측 기억 의미 기준 | 15/15 (5항목 × 3회) |
| 부가 장면 설명의 세부 정확도 | **0/3**; 작은 동물을 쥐/붉은 새로 잘못 분류하거나 위치·이전 움직임을 추측 |

완료된 답변 전문을 선택 JPEG 12장과 대조했다. 설명 중 끼어들기로 중단한 3개
답변은 완성 답변 채점에서 제외했다. 비교 답변 2회차에는 같은 문단을 두 번
반복하는 문제도 있었다. 따라서 **관측 기억/제어 기능 통과이며 일반 시각 답변
품질 합격은 아니다.** 영상 off의 세 가지 경우는 메모리 정책 응답이며 새 VLM
추론이 아니다. 첫 관측과 과거/현재 비교는 실제 VLM이다.

ASR의 공백·구두점 제외 문자 일치는 26/39다. “봐 두고→과두고”, “껐어→꺼서”,
한 회차의 “아까→가” 오류를 보존했고, “아냐→아니야” 같은 동의 표현도 문자 불일치로
집계했다. 시간/정정/취소 요청 동작은 3회 모두 실행됐지만 **인식 정확도 100%가 아니다.**
기대 문장으로 ASR를 치환하지 않았다.

| 마지막 유성 PCM 처리 시각 → 첫 AudioTrack write | 실측 |
|---|---|
| 첫 영상 응답 | 21,829ms |
| 이후 영상 응답 11건 | 중앙값 10,285ms, 7,142–15,436ms |
| 영상 없는 기억 정책 9건 | 중앙값 1,556ms, 951–2,235ms |
| 도구 직접 안내 15건 | 중앙값 1,224ms, 939–2,018ms |

재생 중 보낸 12입력의 감지→중단 호출은 1–6ms(중앙값 3ms)였다. 이는 VAD 감지
전 시간이나 실제 귀에 들리는 중단 시간이 아니다. 선택 프레임은 anchor 이전
57–488ms였고, 12건 모두 응답 준비 중 새 프레임이 들어와도 선택 근거가 유지됐다.
AudioRecord 수집은 회차별 3,421,440 / 3,285,440 / 3,040,320 samples,
모의 입력 모니터 drop은 모두 0이었다. 첫 영상 지연과 따뜻해진 상태의 지연 모두
실시간 Live 수용성 근거로 쓰지 않는다.

## 환경·재현

최종 APK와 59개 소스 해시, 빌드/CPU 테스트, host doctor 결과는
[빌드 증거](video-scenario-build-evidence.json)에 있다. 모델은 Android 0.21의
base INT4 bundle을 그대로 사용했다. [모델 파일 manifest](voice-fix21-model-manifest.json).
ASR revision `5eb144179a02acc5e5ba31e748d22b0cf3e303b0`, VLM revision
`851bf6e806efd8d0a36b00ddf55e13ccb7b8cd0a`, MNN revision
`9f097c5fdd48e0cac4468d4cc53a828794d96888`.
VLM weight SHA256 `845153f3e0b13fe0c7cf0a6753314dd8b4616c4ba761de03ee99aa09502d2ee3`.
출력은 Qwen TTS가 아닌 설치된 Android 오프라인 `ko-KR-language`, 입력 합성은
`ko-kr-x-koc-local`이다. 시스템 미디어 음량/경로를 유지한다.

각 기기 실행 전 external-dev doctor로 host RTX3090 두 장의 여유를 확인했다.
최종 시행 전 각각 20,647/24,109MiB였으며, 실제 추론은 태블릿 GPU에서만 수행했다.
다른 프로세스나 프로젝트 환경/원본 revision을 변경하지 않았다.

```sh
.venv/bin/python -m pytest
# 지정 JDK17/Gradle8.9/기존 캐시로 assembleDebug lintDebug
.runtime/video-tools/bin/python scripts/prepare_video_scenario.py
.runtime/ojt/android-sdk/platform-tools/adb -s R54X902CZEK push \
  .runtime/video-replay/scenario.mp4 /sdcard/Android/data/dev.live.offline/files/scenario.mp4
.venv/bin/python scripts/doctor.py
.runtime/ojt/android-sdk/platform-tools/adb -s R54X902CZEK shell am instrument -w \
  -e rounds 3 dev.live.offline/.VideoScenarioInstrumentation
```

영상 생성 전 manifest의 원본을 `.runtime/video-replay/sample.mp4`에 준비한다.
생성 도구만 별도 PyAV19.0.1/Pillow12.3.0 환경을 사용한다. 앱 의존성 추가는 없다.
기기 `files/video-scenario.json`과 `evidence-*.jpg`를 수집해 응답과 실제 프레임을
대조한다. 영상·JPEG·오디오·모델은 git/vault에 저장하지 않는다.

## 실패 보존과 한계

- [초기 0.22 smoke](video-scenario-initial-smoke.json)는 제어 동작 중심이었다.
  제목 프레임과 반복 편집 때문에 기억 비교가 부적절했던
  [시행](video-scenario-repeated-clip-interrupted.json), 사용자 지적에 따라 중단한
  [정형 발화 시행](video-scenario-formal-speech-interrupted.json)을 보존했다.
  이들을 자연 발화·기억 검증 성공 분모에 넣지 않았다.
- [첫 0.23 중단](video-scenario-natural-foreground-interrupted.json)은 28.9초에
  앱의 foreground 상실로 실패했다. 기기 로그에 잠금화면 전환과 DeX 창 숨김이
  있었으며, 시험의 화면 유지 설정을 모델 로드 전으로 옮긴 뒤 재실행했다.
  외부 사용자 조작 여부는 확정하지 않는다. 앱은 background에서 세션을 종료한다.
- [0.23 의미 실패](video-scenario-natural-semantic-failure.json)의 2회차 영상 off
  회상은 “현재를 확인할 수 없다” 뒤 “지금도 그 위치에 있다고 기억하겠다”고
  모순되게 답했다. [실패 판정·답변 해시](video-scenario-natural-failure-review.json).
  실행을 중단해 raw JSON은 마지막 running snapshot이다. 0.24에서 기억 조회와
  현재 불확실성 안내를 분리했다. 수정 전 성공 1회차도 최종 분모에 합치지 않았다.
- [0.24 이미지 부정 실패](video-scenario-image-denial-failure.json)는 실제 토끼
  JPEG 및 images=1 로그에도 이미지를 볼 수 없다고 답했다.
  [답변·프레임 해시](video-scenario-image-denial-review.json). 이미지 디코더 오류는
  발견하지 못했고 정확한 원인은 확정하지 않는다. 0.25에서는 이미지 첨부 여부로
  모델 지시를 분기하고, 이런 부정 답변을 관측으로 저장하지 않도록 했다.
- [0.25 긴 되읽기 실패](video-scenario-readback-length-failure.json)는 단일 TTS의
  길이 제한으로 음성이 나오지 않았다(`last_status=음성 길이 확인 필요`).
  비교 답변도 과거 대상의 위치를 누락해 의미 기준에 미달했다.
  0.26은 기억 조회를 기존 문장별 출력 경로로 연결하고, 비교 질문의 과거 위치와
  현재 모습을 모두 답하도록 지시했다. [수정 전 빌드](video-scenario-readback-failed-build.json).
- 첫 write는 마지막 유성 PCM **처리 시각**부터 AudioTrack 최초 쓰기까지다.
  800ms 종료 가드·ASR·모델·TTS가 포함되지만 하드웨어 음성 종료/귀에 들린 시각은 아니다.
  감지→중단은 invalidation/AudioTrack stop 호출 시간이며, VAD 확인 시간과
  Bluetooth 잔여 재생 지연을 포함하지 않는다. 두 수치를 섞지 않는다.
- 사람 마이크/소음/에코/기기 연결 변경, 장기 발열, Galaxy S12GB, 정밀 시청각
  정렬, 여러 객체·장면의 일반 기억 품질은 검증되지 않았다. 일정은 앱이 foreground인
  동안의 로컬 타이머이며 OS 예약 알림/캘린더가 아니다.
