# 검증 기록 — 2026-10-12

## 판정

두 경로의 코드·모델·환경과 실제 GPU 기능 smoke를 확보했다. **Gemini Live 수준의 지연/음질이나 사람이 사용하는 full-duplex Live의 최종 합격을 의미하지 않는다.**

| 항목 | 실제 결과 |
|---|---|
| 저장소/환경 | 비공개 rbitts/live, 모델 4개와 upstream 3개 revision 고정, 격리 환경 5개, bootstrap 재실행 성공 |
| CPU/웹 테스트 | 9 passed, Python/JS 문법 검사 통과, 브라우저 UI 확인 |
| MiniCPM 모델 로드 | RTX 3090 24GB의 GPU 1에서 성공, 로드 후 장치 사용량 약 21,579 MiB |
| MiniCPM 30초 smoke | 30/30 입력 처리, 출력 시작 뒤 12개 입력 추가 수신, 실제 PCM 음성 출력 |
| MiniCPM 처리 시간 | 1초 입력 단계의 p95 약 1,030ms. 목표 1,000ms에는 소폭 미달 |
| MiniCPM 연속 실행 | 150초 × 2회 검증 진행 중; 완료 시 아래 개별 보고서로 확정 |
| 한국어 ASR | 합성 질문 “빨간 컵은 어디에 있어요?” 인식 성공 |
| 한국어 시각 응답 | 합성 그림에서 빨간 컵이 왼쪽이라는 답변과 음성 출력 성공 |
| 한국어 응답 중 입력 | 첫 음성 청크 뒤 새 음성 입력, 이전 생성 취소, 새 epoch 응답 성공 |
| 한국어 첫 음성 | 최종 두 응답 2,450ms / 1,874ms (서버 발화 종료 판정→첫 오디오). 사용자 발화 끝 기준으로는 VAD의 약 600ms와 재생 지연이 추가됨 |
| TTS 생성 취소 | 실제 GPU 취소→작업 해제 138.3ms. 실제 스피커 음소거 측정은 아님 |
| 스트리밍 음성 검증 | 8 codec frame(640ms) 청크를 합친 음성을 ASR로 재인식: “빨간 컵은 왼쪽에 있어요.” 일치 |

## 남은 한계

- TTS 생성 속도가 음성 재생 속도보다 느린 구간이 있다. 첫 오디오가 빨라져도 재생 공백이 생길 수 있다. 매끄러운 실시간 음성 목표는 미달이다.
- 더 작은 4-frame 첫 청크 실험에서는 재인식 오류가 발생했다. 기본값으로 채택하지 않았고 실패 기록을 보존했다.
- 한국어 지연은 두 응답의 smoke 값이다. p95, 실제 사람의 인식률, 음성 자연스러움 결과로 일반화할 수 없다.
- `server_barge_ack_ms`는 테스트 음성의 앞부분 무음을 포함한다. 이를 실제 음성 감지→스피커 정지 지연으로 해석하면 안 된다.
- ASR는 발화 종료 후 인식한다. Qwen 조합은 시스템 full-duplex이며 네이티브 full-duplex 모델이 아니다.
- 실제 마이크·카메라·스피커, 소음 환경, 10회 반복 끼어들기와 기억 정확도 평가는 아직 미실시다.
- native의 librosa dependency override 및 decord wheel 메타데이터 예외는 docs/architecture.md에 기록했다. 실제 모델 로드·duplex smoke는 통과했다.

## 증거

- `models.json`, `host.json`: 모델 revision/필수 파일과 호스트 확인.
- `native-smoke.json`: 30초 native paced replay.
- `native-150s-1.json`, `native-150s-2.json`: 생성된 경우에만 연속 실행 증거로 사용.
- `qwen-smoke.json`: 최종 한국어 GPU 통합 replay.
- `qwen-cancel.json`: 실제 TTS 생성 취소.
- `tts-stream.json`: 최종 streaming TTS→ASR 검증.
- `qwen-smoke-initial.json`, `qwen-smoke-sentence-tts.json`: 개선 전 6~7초 첫 음성 기록.
- `tts-stream-4frame-experiment.json`: 채택하지 않은 4-frame 실험 실패.

원시 사용자 음성·영상은 기록하거나 저장소에 넣지 않았다. 합성 입력과 공식 공개 테스트 음성만 사용했다.
