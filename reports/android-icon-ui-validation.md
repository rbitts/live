# 곁 · 아이콘 중심 Android UI 검증 — 2026-10-13

**판정: 0.3 아이콘 UI 빌드·소프트웨어 검사·실제 모델 연결 통과. 실시간 성능 미달과 실기기 검증 과제는 남아 있다.** 사용자가 요청한 최소 텍스트, 아이콘·배지 중심 상태, 툴팁 설명을 적용했다. 기존 남색·민트 테마와 모델/기억 정책을 유지했다.

## 화면과 동작

- 온보딩 완료 후 라이브 화면으로 바로 진입한다. 시작/일시정지, 응답 중단, 세션 종료를 고정 아이콘으로 제공한다.
- 상태는 준비·로딩·생성·재생·오류 아이콘으로 표시한다. 기억 개수와 읽지 않은 답변은 배지로 표시한다. 카메라 대기는 실제 캡처 중 상태와 구분한다.
- 라이브 상단에는 앱 이름 텍스트 없이 왼쪽 상태 아이콘과 오른쪽 도움말 아이콘만 표시한다.
- 아이콘은 접근성 이름과 길게 누르기 툴팁을 제공한다. 주요 터치 영역은 최소 48dp다.
- 답변은 말풍선을 눌러 연다. 입력창도 키보드 아이콘으로 연다. 모델/개인정보 상세 설명은 정보 대화상자에서 확인한다.
- 기억 설정은 개수·기간 칩, 관측 썸네일, 삭제 아이콘으로 구성한다. 현재 화면과 과거 관측의 구분 및 세션 종료 시 삭제 정책은 유지한다.
- 재생 상태는 실제 AudioTrack 쓰기와 예상 재생 완료 시각을 반영한다. 이는 음향 측정값은 아니다.

최종 흐름: [Android 워크플로우](../docs/android-app-workflow.md). 설치: [실행 가이드](../docs/mobile-pc-runbook.md).

## 환경과 범위

`external-dev`의 전용 API 36 x86_64 에뮬레이터(12GB RAM, 4 cores, CPU 추론, emulator-5580)를 사용했다. GPU 실행이나 클라우드 자원 사용은 없었다. 기존 공용 에뮬레이터와 연결된 Galaxy Tab은 사용하지 않았다. 고정 모델·MNN revision 및 native 추론 소스는 이번 UI 변경에서 바꾸지 않았다. 정확한 모델/상위 소스 목록은 [기존 통합 보고서](android-live-validation.md#환경과-모델), `configs/models.json`, `configs/upstreams.json`을 따른다. Qwen ASR → VLM → TTS는 system duplex 경로이며 native duplex가 아니다.

## 검증 결과

| 검사 | 결과 |
|---|---|
| ARM64/x86_64 assembleDebug + lintDebug | 통과, 연구용 debug APK 0.3-icon-live-preview |
| APK 서명·정렬 | 두 ABI v2 서명, 16KB ZIP 정렬, 각 6개 native library의 ELF LOAD 정렬 ≥ 0x4000 |
| Python protocol/state | 45 passed |
| Java ObservationMemory | 통과 |
| 최종 APK UI instrumentation | 흐름·기억·이전 epoch 결과 거부·아이콘 이름/툴팁·입력창·답변 접기 통과 |
| 작은 화면 | 360dp 너비, 글꼴 1.3배에서 주요 5개 동작 영역이 화면 내부이며 ≥ 48dp |
| 툴팁 | 실제 길게 누르기 표시 및 스크린샷 확인 |
| 실제 Android JNI 모델 | ASR → VLM → TTS PCM callback 통과, mock 없음 |
| 실제 TTS 취소 | 최종 APK에서 버튼→트랙 정지 3ms, native 취소 33ms, 이후 마이크 5,440 samples |

실제 모델 검사 질문은 고정 WAV와 GQA 이미지 입력이다. 실제 사람/카메라 대화 시험이 아니다. ASR은 “현재 화면에서 컵의 중심은 어느 구역이 있나요?”, VLM은 “현재 화면에서 컵의 중심은 오른쪽 구역입니다.”로 응답했다. 관측 삭제 후 텍스트 질문에는 현재 화면과 저장 관측이 없어 위치를 확인할 수 없다고 응답했다. 답변은 기본 라이브 화면에서 접힌 상태를 유지했다.

첫 PCM **31.241초**, 전체 요청 **55.691초**, TTS 중 마이크 **390,400 samples**를 측정했다. 모델 준비 대기 29.824초는 검사 시작 후 남은 대기이며 전체 cold load 시간이 아니다. 실제 모델 smoke 이후 카메라 대기 표시·툴팁 문구와 입력창 중단 버튼/대기 표시를 다듬고 소프트웨어 및 실제 취소 검사를 다시 통과했다. 마지막 상단 이름 제거는 표시 배치만 변경했으며 빌드와 화면을 다시 확인했다. 추론 경로는 바뀌지 않았다. 모든 시간은 단일 smoke 관측으로 p95나 실제 스피커 지연을 의미하지 않는다.

## 산출물과 증거

모든 로컬 산출물은 `.runtime/android-icons/` 아래에 있다.

- `artifacts/gyut-live-arm64-debug.apk`, `artifacts/gyut-live-x86_64-debug.apk`, `artifacts/verification-manifest.json`.
- `live-final.png`, `tooltip.png`, `live-small.png`, `03-memory.png` — 실제 앱 화면.
- `model-report.json`, `workflow-model.log`, `workflow-software.log`, `cancel-real.log`.
- `build-*.log`, `signature-*.log`, `zipalign-*.log`, `elf-alignment.json`, `pytest.log`, `java-tests.log`, `small-layout-check.json`.

기존 약 5.5GB 모델 ZIP은 그대로 재사용한다. APK는 모델 가중치를 포함하지 않는다. 이번 실행에서 실제 카메라 smoke를 재실행하지 않았으며 이전 입력 검사는 기존 보고서의 이력으로만 유지한다.

## 남은 확인

첫 음성 지연은 실시간 목표에 미달한다. 실제 Galaxy S 설치·음향 끼어들기·발열·Bluetooth·권한 거절/복귀·TalkBack 및 5분 대화/10회 중단 기준은 여전히 미검증이다. UI/모델 통합 통과를 사람의 Live 사용 또는 full-duplex 확보로 해석하지 않는다.
