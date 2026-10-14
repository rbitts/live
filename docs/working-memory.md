# 공통 작업 메모리와 도구 후속 안내 (0.18)

진행 중 작업에 주입할 음성·영상·텍스트 근거의 시간 정렬은
[공통 시간축 설계](multimodal-timeline.md)에 정의한다. 현재 코어의 사건 시각만으로
원본 미디어 정렬을 보장하지 않는다. 원본 구간·시계 오차·인과 참조는 후속 구현이다.

Android Live와 개발 에이전트 연결부는 `shared/src/main/java/dev/live/memory/`
의 같은 순수 Java 상태 코어를 사용한다. 새 검색 DB·모델·네트워크 서비스는 없다.
이 구현은 저장된 전체 설계의 첫 실행 범위이며, 임의의 자연어에서 계획과 권한을
자동 추출하거나 Codex 앱 내부에 훅을 설치하는 기능은 포함하지 않는다.

## 공통 계약

`WorkingMemory`가 동기화된 상태 소유자다. 스냅샷은 불변 복사본이며
generation(기억 삭제), version(상태 변화), revision(행동 조건), output(전달 취소)를
분리한다. 시간은 어댑터가 같은 프로세스의 단조 시계로 제공한다.

| 입력/API | 반영 및 보장 |
|---|---|
| `input` | 확정 입력과 출처 보존, 결정 revision 갱신. 부분 ASR는 호출 금지 |
| `goal` / `replaceGoal` | 명시적 작업 변경, 기존 안내 무효화. 실행 중 외부 작업은 취소 요청 상태 |
| `constraint` | 입력에 실제 존재하는 근거 구간 필수, 같은 key의 조건 교체 |
| `resourceChanged` | 개발 호스트의 파일·환경 revision 변경, 옛 실행 후보 거부 및 재검증 필요 |
| `lease` / `valid` / `admit` | 생성·실행·출력 직전 최신성 검사. 호스트가 제공한 권한만 사용 |
| `outcome` | 실제 도구 receipt, 중복 제거. 이전 작업의 늦은 사실은 이력에만 남김 |
| `next` | 사용자 발화·안내 보류·기존 출력 중에는 재진입 금지 |
| `delivered` | 실제 전달 완료 확인. 생성·큐 등록만으로 완료되지 않음 |
| `interrupt` / `hold` | 음성 취소와 작업 취소 분리. 미전달 안내는 남김 |
| `reset` | 세션 generation 변경, 이전 callback으로 기억 복원 금지 |

최대 도구 8개, 안내 16개, 조건 4개, 사건 64개다. 도구를 시작하기 전에 후속 안내
용량을 확인하며 미전달 결과를 버려 공간을 만들지 않는다. 입력·결과·목표·조건은
각자 길이 제한이 있다. 과거 사건은 제한된 진단 이력이지 영구 감사 로그가 아니다.
현재 구현은 단일 세션 소유자이며, 여러 호스트의 사건 병합·재시작 복원은 후속 범위다.

## Live 연결

`LiveWorkingMemory`는 확정된 실제 ASR를 처리한다. `MainActivity`는 다음 응답의
모델 문맥에 현재 목표·조건·도구 상태·미전달 안내를 먼저 넣고 최근 대화를 보충한다.
답변의 생성, 실행 직전, 텍스트/PCM 공개에 lease와 기존 epoch 검사를 사용한다.
부분 인식은 자막에만 사용한다. 이전 숨겨진 추측 답변 재사용은 현재 끈 상태로,
변경 전 상태로 계산한 답변이 확정 입력 이후 재사용되는 일을 막는다.

사용할 수 있는 명시적 발화 예:

- “삼십 초 타이머 시작해 줘.”
- “이십 초 타이머로 바꿔 줘.”
- “타이머 취소해 줘.”
- “잠깐만.” — 출력과 자동 안내를 보류하며 타이머는 계속 실행한다.
- “계속해.” — 보류한 결과를 다시 안내한다.
- “지금 작업은 뭐야?”
- “작업 변경. …” — 기존 작업·도구 안내를 대체한다.
- “소금은 빼고 다시 알려 줘.” — 명시적 제외 조건을 현재 작업에 추가한다.

도구 명령은 제한된 문법으로만 실행한다. 불명확한 숫자를 추측해 타이머를 바꾸지
않는다. 도구 없는 일반 입력은 최신 확정 발화로 문맥에 포함한다. 이것은 범용 의미
분석기나 모든 조리·관찰 상태의 자동 통합이 아니다. 기존 조리/관찰 도우미는 별도
기능이며, 이번 공통 스케줄러를 통한 실제 도구 연결은 일반 대화의 로컬 타이머다.

타이머의 단조 시계 만료가 실제 성공 receipt를 만든다. callback은 바로 말하지
않고 결과 안내를 대기시킨다. VAD 시작은 즉시 재생을 비우고, 최종 ASR 처리 전까지
발언권을 열지 않는다. 최종 입력 후 최소 500ms와 기존 출력 종료를 확인해 재진입한다.
250ms 앱 tick 때문에 이 값은 정확한 재생 시작 지연 보장이 아니다.

공통 코어는 2초 이상 진행 중이고 아직 시작 안내를 하지 않은 도구에 한 번만
진행 안내를 만든다. 결과가 도착하면 대기 중 진행 안내를 대체한다. 실제 타이머는
설정 직후 안내하므로 반복해서 “작업 중”이라고 말하지 않는다. 결과 전달은 이와
별개다. 실패한 전달은 5초 뒤 재시도하고, 3회 실패 후 미전달 상태로 남긴다.

음성 생성 완료, 전체 PCM 쓰기 완료, 실제 AudioTrack 재생 위치 도달을 모두 확인해
전달 완료를 기록한다. 중단된 짧은 결과 문장은 다음 허용 시점에 전체를 다시 읽는다.
세부 문장 내 재생 지점부터 이어 읽는 기능은 아직 없다. 소프트웨어 재생 완료는
실제 사용자가 들었거나 이해했다는 확인이 아니다. 일반 응답 기억도 끝까지 재생한
뒤 저장한다. 텍스트 모드에서는 앱의 표시 완료를 전달로 취급한다.

앱 이탈·세션 종료·기억 삭제는 작업과 안내를 지운다. 백그라운드 알람이나 OS 알림이
아니며, 다른 채널로 전송하지 않는다. 마이크 연속 수집, 시스템 기본 입력/미디어
출력 정책은 유지한다. 설치된 오프라인 한국어 음성 중 엔진의 현재 음성을 우선하고,
없으면 품질 등급·지연 순으로 선택한다. 이름 사전순으로 음성을 선택하지 않는다. 이 경로는 Qwen **시스템 duplex**다.

## 개발 에이전트 연결

`AgentMemorySession`은 Android 의존성이 없는 삽입형 어댑터다. 호스트는 사용자
입력을 `message`, 실행 직전 후보를 `beforeTool`, 실제 종료를 `toolFinished`로
전달한다. 새 사용자 메시지와 독립된 idle/timer callback에서 `next`를 호출하고,
출력 채널 게시가 실제 완료된 뒤 `published`를 호출한다. 게시 실패는 공통 코어의
`deliveryFailed`로 되돌린다. 실제 도구 실행·취소·권한과 파일 revision 계산은
호스트 소유다. 이전 프로세스의 실행 여부를 추정하거나 외부 작업을 자동 재실행하지 않는다.

저장소의 JVM 검증은 같은 코어로 실제 임시 파일 검사 작업을 별도 스레드에서
실행하고, 새 사용자 입력 없이 결과 후보 생성·게시 확인을 검증한다. 이는 개발
에이전트 호스트가 연결할 수 있는 실행 가능한 경계이며 **Codex 제품 자체에 연결한
것은 아니다**. Python 서버·MiniCPM native 경로에도 자동 적용되지 않는다.

## 검증

`.venv/bin/python -m pytest`는 공통 코어 및 두 어댑터를 컴파일해 stale lease,
조건 교체, 출처 없는 변경 거부, 발화 도중 결과, 결과 우선순위, 중단·보류·재개,
중복 receipt, 실패 재시도, 자원 revision 변경, reset, 타이머 교체를 검증한다.

`WorkingMemoryInstrumentation`은 모의 입력 TTS → 실제 속도 PCM → Capture/VAD →
실제 Qwen ASR를 사용한다. 타이머 완료나 사용자 발화 사건을 직접 주입하지 않는다.
작업 변경, 응답 도중 끼어들기, 새 질문 없는 결과 안내, 결과 중단·재전달,
조건 주입 후 실제 VLM 응답, 연속 수집, 세션 정리를 검사한다.
실기기 결과와 ASR 실패는 [검증 보고서](../reports/android-working-memory-validation.md)에 기록한다.


`OutputContinuityInstrumentation`은 별도로 음성 인식과 의도적 끼어들기를 제외하고
일반 텍스트 요청으로 실제 10초·20초 타이머를 실행한다. 시작·완료 안내 네 개의
PCM 생성/쓰기/재생 위치, producer starvation, AudioTrack underrun과 재생 위치
갱신 간격을 기록한다. 이 검사는 사용자에게 들리는 발음·음색의 자연스러움을 대신하지 않는다.


## Android 0.20: recognition context and current question

An active local timer supplies only the task category `타이머` to Qwen ASR's
system context. No duration, expected transcript or numeric replacement rule is
provided. `StreamingAsr.begin(context)` freezes at most 128 characters for a turn;
each queued snapshot owns that context. Cancellation clears it, and stale decode
results remain rejected by the existing generation guard. Timer completion or
cancellation removes this recognition hint. The runtime chat template now carries
the system context instead of discarding it; the pinned Korean language and ASR
model artifacts are unchanged. This is recognition bias, not a guarantee of accuracy.

The VLM receives the current question as its own user message, with quoted working
state/history labeled as background. The generic answer policy requests concrete,
subject-appropriate help rather than a restatement; output remains bounded at 192
tokens. A terminal timer ceases to be the goal when a new ordinary question arrives
and its result has already been delivered. An active timer or undelivered result
is retained. Follow-up questions retain explicit exclusions.

Diagnosis, rejected candidate answers, held-out checks and the same five-round
comparison are recorded in `reports/android-voice-fix-validation.md`. Synthetic
speech checks do not establish human microphone or general-answer acceptance.

Android 0.21 replaces the installed narrow QAT VLM derivative with the existing
base INT4 derivative of the same pinned upstream revision, after CPU and device
comparisons exposed general-question failures. ASR/TTS/observer artifacts remain
identical. The validation report records hashes and the changed comparison conditions.

Recent dialogue is selected from the start of the current goal; the full bounded
history remains stored and available to the unscoped memory API. Scene history is
still labeled past. An empty camera input does not add a misleading no-image
preface to ordinary questions. Explicit visual questions still require evidence.

Mock input monitoring copies the same paced synthetic samples into a separate,
bounded, nonblocking media AudioTrack. It never feeds speaker audio back into ASR
or blocks capture on playback; assistant barge-in cancellation leaves user input
playback running. Stop/real-microphone mode closes the monitor. A dialog checkbox
controls monitoring. On the verified Google engine the mock synthesizer prefers
the installed offline Korean male voice `ko-kr-x-koc-local`, then `ko-kr-x-kod-local`;
otherwise it falls back to the existing installed voice. Assistant speech retains
the engine's default Korean voice. No pitch or speed transformation remains.
