# 음성 인식·일반 답변 수정 검증 (2026-10-14)

Android 0.21-general-model, 실제 SM-X926N(Android 16 / MT6989 / Mali-G720).
최종 같은 시나리오 5회/30발화를 완료했다(332,997ms). 인식 30/30, 타이머 실행
15/15, 결과 재진입 5/5, 사전 기준 샐러드 답변 5/5였다. 아래 진단/중단 시행은
최종 분모에 합산하지 않는다. 이는 제한된 시나리오의 개선이며 전체 Live 합격은 아니다.
멀티모달 시간 정렬은 [별도 설계](../docs/multimodal-timeline.md)이며 이 음성 시험으로 구현·검증되었다고 주장하지 않는다.

## 최종 결과와 기준선 비교

[수정 후 원본](repeated-voice-fixed-results.json), [답변 판정](repeated-voice-fixed-reviews.json),
[집계](repeated-voice-fixed-summary.json), [실행 조건](repeated-voice-fix-protocol.md).
기준선은 [기존 보고서](android-repeated-voice-evaluation.md)에 그대로 보존한다.

| 항목 | 기준선 0.18 | 최종 0.21 |
|---|---:|---:|
| 확정 인식 | 30/30 | 30/30 |
| 인식 완전 일치(공백/문장부호 정규화) | 25/30 (83.33%) | 30/30 (100%) |
| 십 초 인식/작업 변경 | 0/5 | 5/5 |
| 타이머 명령 실행 | 10/15 | 15/15 |
| 결과 안내·보류·재전달 | 5/5 | 5/5 |
| 재생 중 끼어들기 후 옛 PCM 거부 | 15/15 | 15/15 |
| 샐러드 답변 사전 기준 | 0/5 | 5/5 |
| 샐러드 전체 음성 전달 | 5/5 | 5/5 |
| 전체 시나리오와 답변 기준 | 0/5 | 5/5 |
| CER | 5/255 (1.96%) | 0/255 (0%) |

샐러드 응답은 5회 동일했다: "샐러드 만들 때 소금 대신 레몬즙이나 바닐라 소스를
사용하세요. 채소는 미리 씻어 물기를 제거한 후 드레싱을 뿌려주세요."
씻기·물기 제거·드레싱을 제시하고 소금 추가를 지시하지 않아 사전 기준을 충족한다.
바닐라 소스 제안은 어색하고, 동일 합성 입력에 같은 답이 반복되므로 다양한 질문의
답변 품질이나 조리 지식의 정확도를 보장하지 않는다.

최종 답변 첫 AudioTrack write 지연은 8,068 / 3,868 / 3,890 / 3,871 / 3,879ms,
중앙값 3,879ms, 최대 8,068ms였다. 기준선 중앙값 3,755ms보다 124ms 느리며,
첫 회차 지연 증가도 숨기지 않는다. 이는 앱 응답 시작 기준이고 실제 귀에 들린 순간이나
입력 발화 종료부터의 총 지연이 아니다. p95 또는 제품 보장으로 해석하지 않는다.

입력 모니터는 5회 모두 dropped samples=0, 실제 출력 route type=8(A2DP)을 기록했다.
이는 소프트웨어 전달 측정이며 무선 오디오의 청감상 끊김이 전혀 없다는 보장은 아니다.
최종 제품 소스가 시험 동안 바뀌지 않았음을 해시로 확인했다.
[빌드·CPU 검증·호스트·소스 해시](voice-fix21-build-evidence.json).

## 수정 내용과 근거

- 활성 타이머에서만 ASR 시스템 문맥에 `타이머`라는 작업 종류를 제공한다.
  숫자·정답 문장·인식 후 치환은 없다. JNI와 runtime template에서 기존에 버리던
  system context를 전달하고, turn마다 고정해 취소/다음 발화와 섞이지 않게 했다.
- 같은 모의 음성의 전체 구간은 십 초, VAD 분할 구간은 시초로 인식했다.
  잘린 양끝이 무음인 것을 확인했으며 앞뒤 padding 확대만으로 고쳐지지 않았다.
  동일 분할 PCM에 작업 종류만 제공했을 때 십 초로 인식했다.
  [분할 진단](recognition-window-diagnostic.json), [padding/context 비교](recognition-padding-diagnostic.json).
- 별도 입력 구십/사십/오십 초와 실제 단어 시초를 paced 입력으로 확인했다.
  [held-out 결과](context-voice-validation.json). 제한된 합성 입력 증거이며 범용 인식 보장이 아니다.
- 완료·전달된 타이머 뒤의 새 질문이 현재 목표가 되도록 하고, 최근 대화 주입을
  현재 목표 시작 이후로 제한했다. 이전 대화 이력 자체는 보존한다. 현재 질문과
  배경 상태를 분리하고, 일반 질문에 불필요한 화면 없음 문구를 붙이지 않는다.
- 실제 QAT 파생본은 같은 일반 질문에서 화면 관측이 없어 답할 수 없다고 하거나
  관측 사실을 만들어냈다. 같은 pinned 원본의 기존 기본 INT4 파생본을 같은 CPU
  runner/질문으로 비교한 뒤, 실제 기기 OpenCL과 현재 대화 프롬프트로 확인했다.
  [QAT CPU](voice-qat-cpu-comparison.jsonl), [기본 INT4 CPU](voice-base-cpu-comparison.jsonl),
  [기본 INT4 기기](voice-base-device-comparison.jsonl). 이 비교는 파생본이 일반 대화 실패에
  기여한다는 근거다. 일반 지식·조리 정확도가 검증되었다는 뜻은 아니다.
- 최종 앱에는 기본 INT4 VLM을 설치했다. 원본 모델 revision과 MNN/runtime patch는
  동일하다. 패키지 파일 비교에서 VLM의 config/decoder/weight/visual/tokenizer만
  달랐고 ASR/TTS/observer 파일은 동일했다. 검증된 앱 ZIP importer로 설치했다.
- 사용자 요청으로 모의 입력도 별도 bounded/nonblocking AudioTrack으로 재생한다.
  실제 인식 PCM은 그대로 사용한다. 높은 톤 실험은 사용자 거부로 즉시 중단했다.
  최종 입력은 설치된 오프라인 `ko-kr-x-koc-local`, AI 출력은 `ko-KR-language`다.
  pitch/speed 변조는 없고 시스템 입력/미디어 출력/음량 정책을 유지한다.
  남성 분류 근거: https://crosstales.com/media/data/assets/rtvoice/RTVoice-doc.pdf .
  Android voice metadata 자체에 성별 필드가 있다고 주장하지 않는다.

## 실패와 중간 시행 보존

| 시행 | 결과와 용도 |
|---|---|
| 기존 0.18, 같은 시나리오 5회 | 인식 25/30, 십 초 0/5, 타이머 10/15, 샐러드 0/5. 기준선 원본 보존 |
| 첫 답변 지침 진단 | 샐러드에 부적절한 볶기/끓이기 제안. 거부: `answer-fix-diagnostic.json` |
| 단순 지침 직접 텍스트 진단 | 제한된 샐러드 응답 개선, 다른 답변의 부정확성 남음: `answer-fix-confirmation.json` |
| 0.19 동일 기존 화자 5회 | 인식 30/30, 타이머 15/15. 샐러드는 화면 없음/옛 타이머 혼동으로 0/5. `repeated-voice-candidate-*.json` |
| 0.20 문맥 범위 수정 1회 | 샐러드 기준 통과. 모의 재생 1,600 samples drop을 관측해 bounded buffer 조정. `repeated-voice-fix-smoke.json` |
| 높은 톤 시행 | 사용자가 불쾌하다고 하여 중단. 부분 결과 `repeated-voice-high-tone-interrupted.json` |
| 첫 남성 음성 시행 | capture가 사라져 2회차 중 중단. 원인 미확정. 첫 샐러드에 생선소금 제안으로 실패. `repeated-voice-male-aborted.json` |
| 기본 INT4 기기 직접 텍스트 | 질문에 답하지만 바닐라 소스 등 품질상 어색함 존재. 일반 답변 정확도 합격으로 확대하지 않음 |

모든 최종 응답은 원문을 사람이 읽는 방식으로 사전 기준에 맞춰 검토하고, 답변 SHA256에
판정을 연결한다. 같은 문장·순서·5회와 기준을 사용하지만 최종 입력 화자와 VLM 파생본이
기준선과 다르므로 개선 전체를 단일 코드 변경 효과로 단정하지 않는다. 동일 화자의
0.19 인식 결과는 ASR 변경의 별도 증거이며, 중단 시행은 최종 분모에 합산하지 않는다.

## 환경·검증 범위

- CPU `.venv` pytest: 52 passed (4.68s). assembleDebug/lintDebug 성공.
- 호스트 doctor: external-dev, RTX 3090 24GB 두 장; 실행 직전 free 20,458/24,109 MiB.
  최종 추론은 태블릿에서 수행했고 다른 프로젝트 GPU 작업은 건드리지 않았다.
- 앱 APK SHA256: `97a46fe22cacbaf06a90f87b135b814a58dd2fc254feec7487da52aaeeb95ae0`.
- ASR Qwen3-ASR-0.6B: `5eb144179a02acc5e5ba31e748d22b0cf3e303b0`.
- VLM Qwen3.5-4B: `851bf6e806efd8d0a36b00ddf55e13ccb7b8cd0a`.
- TTS Qwen3-TTS-0.6B: `85e237c12c027371202489a0ec509ded67b5e4b5`.
  최종 음성 출력은 설치된 로컬 Android TTS이며 이 Qwen TTS를 실행한 결과가 아니다.
- 기본 INT4 VLM weight SHA256: `845153f3e0b13fe0c7cf0a6753314dd8b4616c4ba761de03ee99aa09502d2ee3`.
  이전 QAT: `d77134f46e3a5eec97a3c47d7b1b8da1f8e60b233b5c37749f41b968880b6656`.
  전체 파일/ZIP 해시는 [모델 manifest](voice-fix21-model-manifest.json)에 보존한다.
- MNN: `9f097c5fdd48e0cac4468d4cc53a828794d96888`.
- runtime patch SHA256: `58142f499dad20791d9df221b776145fc898d58fa97d91d1aa729dc4fe4272c2`.
- 영상은 이번 시나리오에서 꺼져 있다. 음성 인식/재생/도구 재진입의 소프트웨어 및
  실제 모델 시험이며 사람 마이크, 소음·에코·기기 변경, 장시간 열·배터리,
  카메라 정렬, 전체 Live 또는 native duplex acceptance를 뜻하지 않는다.
- 사용자는 기존 비중단 10/20초 안내가 끝까지 들린다고 확인했다. 이후 모의 입력과
  답변 모두 들리지만 구분이 어렵다고 했고 높은 톤을 거부했다. 최종 남성 입력의
  구분감/음색에 대한 사람 확인은 아직 없다.
