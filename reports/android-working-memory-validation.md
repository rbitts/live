# 공통 작업 메모리·도구 재진입 검증 — 2026-10-14

Android **0.18-working-memory**를 SM-X926N에 설치했다. 공통 Java 상태 코어,
Live 연결부, 개발 에이전트 연결부를 구현했다. 모의 음성을 통한 작업 변경과
도구 결과 재진입은 통과했다. 사용자는 별도 비중단 출력 검사에서
“이번에는 끝까지 잘 들림”이라고 확인했다. 일반 ASR·답변 품질 합격은 아니다.

## 구현 범위

- 목표·최신 확정 입력·근거가 있는 조건·도구 상태·미전달 안내를 세션에 유지한다.
- 생성/실행/출력의 최신성을 generation/revision/output lease로 검사한다.
- 부분 ASR는 자막 전용이다. 변경 전 상태의 숨겨진 답변 재사용을 중단했다.
- 도구 결과는 안내 의무로 등록한다. 사용자 발화와 다른 출력이 끝난 뒤 재진입한다.
- 중단된 안내는 미전달 상태로 남는다. 실제 PCM 전체 쓰기와 AudioTrack 재생 위치를
  확인한 뒤에만 완료하며, 보류/재개·중복 결과·세션 종료를 처리한다.
- 실제 연결 도구는 일반 대화의 로컬 타이머다. 개발 에이전트 어댑터는 같은 코어로
  별도 스레드의 실제 임시 파일 검사·결과 게시 계약을 검증했다. Codex 제품 내부,
  Python 서버, native MiniCPM 경로에 자동 연결한 것은 아니다.
- [구현 계약과 남은 범위](../docs/working-memory.md). 기존 요리/관찰 기능 전체를
  공통 상태로 옮기거나 범용 자연어 계획 추출을 구현했다고 주장하지 않는다.

## 환경과 고정 revision

실기기 Samsung Galaxy Tab S10 Ultra 5G SM-X926N, Android 16,
MT6989 / Mali-G720, 약 15,582,372KiB RAM. 최종 Galaxy S 12GB 타깃과 다르다.
external-dev doctor에서 RTX3090 24GB 두 장을 확인했고 마지막 확인 여유는
20,977MiB / 24,108MiB였다. 이번 추론은 태블릿에서 수행했으며 타 작업을 중단하지 않았다.

| 구성 | 설치 manifest의 revision |
|---|---|
| Qwen3-ASR-0.6B | `5eb144179a02acc5e5ba31e748d22b0cf3e303b0` |
| Qwen3.5-4B | `851bf6e806efd8d0a36b00ddf55e13ccb7b8cd0a` |
| Qwen3-TTS | `85e237c12c027371202489a0ec509ded67b5e4b5` |
| MiniCPM-o (manifest reference, 이번 경로에서 실행 안 함) | `503e754207c94da6bb26850b4469f367c9ea3582` |
| MNN | `9f097c5fdd48e0cac4468d4cc53a828794d96888` |

런타임 patch SHA256 `58142f499dad20791d9df221b776145fc898d58fa97d91d1aa729dc4fe4272c2`,
기존 파생 모델 bundle 5,800,727,393 bytes. 모델/의존성 revision은 변경하지 않았다.
이번 모의 입력과 응답 음성은 설치된 오프라인 한국어 TTS `ko-KR-language`다.
Qwen TTS 품질 검증으로 대체할 수 없다. 출력은 시스템 미디어 경로, 입력은 DEFAULT다.

최종 APK SHA256: `4cd94f7a3b558d24472e3a700d79da04f8be0006c027305777224934973a20bc`.

## 소프트웨어 검사

`.venv/bin/python -m pytest -q`: **52 passed** (5.03s).
`assembleDebug`, `lintDebug`, `git diff --check`: 통과.
공통 코어 검사는 조건 교체/근거 검증, stale 실행 후보, 발화 중 완료,
진행 안내보다 결과 우선, 끼어들기/보류/재개, 중복 receipt, 게시 실패 재시도,
자원·조건 변경 후 늦은 사실, 같은 길이 타이머 재설정, 사건 이력 제한과 reset을 포함한다.

## 실제 모의 음성 E2E

`WorkingMemoryInstrumentation`은 입력 문장을 인식 결과로 주입하지 않는다.
오프라인 합성 → 실제 속도 PCM → Capture/NeuralVad → 실제 Qwen ASR →
작업 상태/도구 또는 실제 VLM → AudioTrack을 거친다.

1. “삼십 초 타이머 시작해 줘”를 실제 ASR로 인식하고 타이머 시작.
2. 시작 안내 재생 중 “이십 초 타이머로 바꿔 줘”로 끼어들어 이전 타이머 취소.
3. 새 사용자 입력 없이 20초 타이머 완료 안내가 먼저 발화.
4. 안내 중 “잠깐만”으로 끼어들고, 미전달 1개·완료 0개·자동 재진입 보류 확인.
5. “계속해” 후 안내 재전달, 실제 재생 뒤 완료 1개·미전달 0개·중복 없음 확인.
6. “소금은 빼고 샐러드 만드는 방법 알려 줘”의 조건 저장과 실제 VLM 응답 확인.
7. 연속 캡처와 세션 종료 후 작업/안내 제거 확인.

최종 코어 실행: **PASS**, 캡처 876,800 samples. 결과 안내는 중단 후 재생되었지만
완료 기록은 한 번이다. 이전 epoch의 PCM은 거부됐다.

| 단일 실행 측정 | 값 |
|---|---:|
| 변경 입력 보내기→VAD 끼어들기 | 669ms |
| 30초 시작 안내 첫 PCM write | 473ms |
| 20초 시작 안내 첫 PCM write | 516ms |
| 재개 확인 안내 첫 PCM write | 65ms |
| 변경 확인 후 결과 안내 관측까지 | 20,489ms |

send-to-onset에는 입력 합성·선행 무음이 포함된다. 첫 write는 앱 응답 시작 기준이며
실제 첫 소리나 모델 생성 속도, p95가 아니다. 시작/결과 문장은 실제 타이머 상태를
사용하는 결정적 안내다. 모델의 일반 답변 성능 수치로 해석하지 않는다.

## 보존한 실패와 답변 품질

첫 run은 “십 초”가 **“시초”**로 인식돼 변경 단계가 실패했다. 이를 10초라고
추측해 실행하지 않았다. 해당 실행에서는 기존 30초 타이머가 끝났다.
이후 불명확한 타이머 변경은 정확한 시간을 다시 묻고 기존 타이머를 유지하도록
명시적 안내를 추가했다. 20초 변경의 PASS로 10초 음성 인식 실패를 지우지 않는다.

조건 주입 후 실제 VLM 답변은 “현재 요청은 소금을 빼고 샐러드 만드는 방법
제공입니다.”였다. 조건은 반영했지만 조리법을 제공하지 못했으므로 **일반 답변 품질은
실패/미완료**다. 범용 의미 이해·조리 정확도·한국어 ASR acceptance는 남아 있다.

## 사용자 출력 문제 제보와 비중단 재생 검사

사용자는 검증 중 음성이 갑자기 끊기고 품질이 좋지 않다고 제보했다.
로그에서 해당 E2E는 시작 안내 및 완료 안내 중 모의 발화를 의도적으로 넣었고,
VAD 시작→epoch 무효화 시점이 급격한 중단과 일치했다.
음성 선택의 사전순 정책도 제거했다. 설치된 오프라인 한국어 엔진 현재 음성을
우선하고, 없으면 품질 등급·지연 순으로 고른다. 최종 선택은 기존과 같은
`ko-KR-language`였으므로 더 좋은 목소리로 교체했다고 주장하지 않는다.

최종 APK의 `OutputContinuityInstrumentation`은 일반 **텍스트** 요청으로 실제
10초/20초 타이머를 설정하고, mock-silence 캡처로 ASR/끼어들기를 배제했다.
따라서 이 검사는 10초 음성 인식의 재합격이 아니라 출력 경로 분리 검사다.

| 안내 | 생성=쓰기=재생 head samples | producer starvation | 첫 write |
|---|---:|---:|---:|
| 10초 시작 | 99,795 | 0 | 244ms |
| 10초 완료 | 94,299 | 0 | 123ms |
| 20초 시작 | 101,248 | 0 | 125ms |
| 20초 완료 | 95,266 | 0 | 124ms |

**PASS**, 의도하지 않은 epoch 변경 없음, 캡처 635,520 samples.
재생 head 갱신 간격 최대 81–92ms. AudioTrack underrun counter는 종료 구간을
포함해 각 1 증가했으며 발생 시점을 분리하지 않았으므로 “underrun 0” 결과가 아니다.
사용자는 이 비중단 검사 중 **“이번에는 끝까지 잘 들림”**이라고 확인했다.
이 청취 확인은 네 안내의 연속성에 해당하며 모든 목소리·발음·잡음 환경의 품질 합격은 아니다.

코어 E2E PASS는 최종 음성 선택 정책 조정 직전의 동일 코어에서, 비중단 출력 PASS는
정책 조정 후 최종 APK에서 얻었다. 실제 선택 음성은 동일했다.

## 로컬 근거와 남은 수용성

`.runtime/working-memory-device-result1.json` (실패),
`working-memory-device-result2.json` (중간 PASS), `working-memory-device-final.json`,
`working-memory-logcat-final.txt`, `working-memory-pytest.log`, `working-memory-build.log`,
`working-memory-doctor-final.json`, `working-memory-model-manifest.json`,
`output-continuity-result.json`에 원본을 보존했다. 실제 음성 파일은 git/vault에 넣지 않았다.

실제 사람의 마이크 입력·소음·헤드셋 재연결, 5분/10회 장기 duplex,
Galaxy S 12GB와 발열, 일반 답변 품질은 미완료다. 이 결과는 **시스템 duplex**의
제한된 검증이며 native duplex 또는 전체 Live 합격 선언이 아니다.
