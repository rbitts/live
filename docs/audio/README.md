# Qwen 한국어 실제 합성 음성 예제

2026-10-12 문서 보완을 위해 고정된 Qwen3-TTS-12Hz-0.6B-CustomVoice 모델의 Sohee 음성으로 새로 생성했다. 마이크 녹음이나 사용자 음성이 아니다.

- `*-continuous.wav`: `/tts_stream`에서 받은 실제 PCM 청크를 무음 삽입 없이 연결한 24kHz mono PCM16 WAV.
- `*-arrival-replay.wav`: 같은 음성 청크에 이번 실행의 도착 간격으로 계산한 재생 공백을 넣은 비교용 WAV. 첫 청크 도착 + 25ms를 재생 시작으로 잡고, 다음 청크가 재생 끝보다 늦게 도착하는 경우에만 무음을 삽입한다. 첫 응답 대기 시간은 제외한다.
- `samples.json`: 정확한 모델 revision, 문장, 시각, 각 청크의 도착 시간과 길이, 삽입한 공백, WAV 해시.
- `verification.json`: 새 음성의 Qwen ASR 재인식 결과. 사람의 청취 품질 검사를 대체하지 않는다.

이전 `reports/tts-stream.json`의 1,337ms 실행에서는 음성 파일을 저장하지 않았다. 이 파일들은 그 실행의 원본이 아니며 TTS만 켠 새 실행이다. VLM과 동시에 추론한 Live 성능이나 실제 스피커 출력 측정으로 해석하면 안 된다. 연속 연결 파일도 실시간 속도 개선을 입증하지 않는다.

일반 질문 샘플의 이번 공백은 약 1.317초, 끼어들기 뒤 답변은 약 1.046초, 과거 장면 답변은 약 2.469초다. 실제 값은 `samples.json`을 따른다.

`docs/qwen-live-interactive.html`은 WAV를 data URI로 포함해 오프라인에서도 재생된다. UI의 시나리오나 오디오 버전을 바꾸면 기존 음성을 멈춘다. 단계 자동 재생은 설명 애니메이션이며 오디오 플레이어와 별개다.

## 재생성

프로젝트 GPU 사전 점검 후 로컬 TTS worker가 준비된 상태에서:

```bash
.venv/bin/python scripts/build_audio_examples.py
```

이미 저장된 WAV를 HTML에 다시 포함할 때는 GPU 없이:

```bash
.venv/bin/python scripts/build_audio_examples.py --embed-only
```

새로 생성하면 도착 시각과 공백도 바뀌므로 재인식 검증과 이 요약을 갱신한다. 검증 파일은 생성 스크립트가 자동 갱신하지 않는다.
