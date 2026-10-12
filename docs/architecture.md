# 두 Live 경로의 구조

2026-10-12 사용자 요청과 제공된 설계 대화 미리보기를 바탕으로 구현했다. 대화 전문을 읽는 도구는 이 세션에 없었으므로, 전문을 확인했다고 주장하지 않는다.

## 1. MiniCPM-o 4.5: 네이티브 기준선

공식 OpenBMB/MiniCPM-o-Demo를 commit 고정하여 사용한다. audio/video 입력을 모델의 duplex 상태에 전달하며, 모델이 listen/speak를 결정한다. 공식 웹 UI와 WebSocket `/v1/realtime?mode=video`를 그대로 사용한다. video 세션과 Omni Full-Duplex 모드를 선택한다. 단순 Chat/Half-Duplex 통과는 이 경로의 합격이 아니다.

- 입력: 16 kHz PCM float32 1초 청크, JPEG 카메라 프레임.
- 출력: text/audio/listen 상태와 단계별 지연 시간.
- 네이티브 실시간 음성의 공식 언어는 영어·중국어. 한국어 기준선으로 오인하지 않는다.
- 녹음 저장은 기본적으로 끈다. 공식 모델 코드도 고정된 upstream commit에서 로드한다.
- 현재 공식 PyTorch 데모의 요구 조건은 GPU VRAM >28GB, 초기화 사용량 참고치는 약 21.5GB다. 24GB GPU에서 로드만 성공해도 장시간 Live가 보장되지는 않는다.

공식 배포 안내의 오래된 직접 worker 실행과 `/ws/duplex/` API는 현재 코드와 맞지 않는다. 최신 공식 Docker entrypoint가 사용하는 `py_backend.server → worker(전달 전용) → gateway` 3프로세스 구조와 Realtime API를 따른다. upstream 파일·추론 코드는 수정하지 않는다.

의존성 확인: ASR/TTS/VLM의 `uv pip check`는 통과한다. native는 minicpmo-utils의 librosa==0.9.0 메타데이터와 공식 데모의 >=0.10.2 요구가 충돌해 0.10.2.post1을 명시적으로 override했다. decord 0.6.0의 플랫폼 태그 경고도 남지만 이 호스트의 import는 성공했다. 실제 native smoke와 별도로 이 두 패키지 메타데이터 예외를 유지한다.

24GB 호스트에서는 기본 무제한 문맥으로 42초 이후 CUDA OOM을 관측했다. `scripts/native_backend.py`가 공식 decoder의 `set_window_config` / `set_window_enabled` API로 2,000 token에서 오래된 단위를 제거해 1,500 token 이하로 줄이는 기본 프로필을 적용한다. 시스템 프롬프트는 보존하지만 오래된 음성·영상 문맥은 잊을 수 있다. 이 설정은 `configs/native.json`에 명시되어 있고, 다른 용량의 GPU에서는 별도 재검증 후 변경한다. allocator는 expandable_segments를 사용한다. upstream 소스/가중치 파일은 수정하지 않는다.

## 2. Qwen: 한국어 시스템 full-duplex

브라우저 AudioWorklet → 20ms PCM/1fps JPEG → 비동기 WebSocket 수신 → VAD → Qwen3-ASR-0.6B → Qwen3.5-4B → 오디오 청크 출력 Qwen3-TTS-12Hz-0.6B-CustomVoice(Sohee) → 브라우저 재생.

입력 수신과 모델 추론은 별도 작업이다. ASR는 GPU 0, VLM과 TTS는 GPU 1에 놓는다. 이 호스트의 GPU 0에는 별도 사용자 작업이 실행 중이므로, 그 작업을 중단하지 않고 출력의 핵심 경로인 TTS를 GPU 1로 분리한다. CPU 연산 thread는 기본 4개로 제한한다. 모델마다 Transformers 요구 버전이 달라 프로세스/가상환경을 분리했다. 현재 ASR는 발화 종료 뒤 공식 Transformers API를 사용하는 endpointed ASR이며, 중간 인식 스트리밍은 아니다. LLM은 문장 단위 텍스트를 TTS에 전달한다. TTS worker는 공식 talker forward 결과에서 완성된 codec frame을 관측하고, 8 frame마다 공식 decoder와 같은 25 frame의 좌측 문맥으로 오디오를 복원한다. 이는 이 저장소에서 구현한 스트리밍 어댑터이며 공식 고수준 generate_custom_voice API 자체가 오디오 스트림을 반환하는 것은 아니다. 최종 전체 디코딩은 원래 API가 수행하며 첫 음성 전송은 이를 기다리지 않는다. 출력된 오디오 청크를 합쳐 한국어 ASR로 재인식하는 GPU smoke로 누락을 점검했다. 이는 사람이 듣는 자연스러움 검사를 대체하지 않는다. 현재 실시간보다 느린 생성 구간이 있어 재생 공백이 발생할 수 있다.

끼어들기는 20ms 음성 청크 3개 연속 RMS 임계값 초과로 감지한다. 브라우저가 즉시 대기 음성을 비우고, 서버는 epoch을 증가시켜 모든 오래된 결과를 폐기한다. LLM/TTS 생성은 cooperative stopping criteria를 적용한다. ASR 진행 중 추론은 완료될 수 있으나 결과는 사용하지 않는다. RMS VAD는 PoC이며 소음·스피커 에코 오검출은 실제 마이크 검증 대상이다. 브라우저 AEC를 요청하되 장치별 품질을 보장하지 않는다.

Qwen TTS upstream은 `generate_custom_voice(..., stopping_criteria=...)`를 내부 talker에 전달하지 않는다. 격리된 TTS worker의 단일 추론 lock 안에서 talker.generate 호출에 stopping criteria를 직접 전달하고 finally에서 원래 메서드를 복구한다. upstream 파일은 변경하지 않는다.

## 기억 및 개인화

- 프로필은 사용자 입력 500자 이내, 세션에만 저장한다.
- 완료된 대화 최대 8개, 과거 프레임 최대 4개를 저장한다. 한 번의 VLM 요청에는 과거 프레임 최대 2개를 사용한다.
- 현재 프레임은 서버 수신 후 3초 이내인 경우만 사용한다. 과거 관측은 경과 시간을 붙인다. 촬영 장치 시각과 서버 시각 동기화는 아직 하지 않는다.
- 프레임은 질문 시점의 최근 장면만 기억한다. 지속적인 객체 추적·3D 공간 기억·장기 기억 DB는 이 PoC의 구현 범위가 아니다.
- 연결 종료 또는 기억 지우기로 세션 기억을 폐기한다. 실제 음성·영상은 git이나 Obsidian에 저장하지 않는다.

## 자원과 접근

기본 external-dev/로컬 구성은 두 RTX 3090이다. native와 Qwen을 동시에 띄우면 GPU 1을 중복 사용하므로 순차 실행한다. 모든 자체 서비스는 127.0.0.1에 바인딩한다. 원격 브라우저는 SSH 포트 전달을 사용해 localhost 보안 컨텍스트에서 접속한다. 임의 인터넷 공개에는 별도의 인증·TLS 설계가 필요하다.

## 공식 근거

- https://github.com/OpenBMB/MiniCPM-o-Demo
- https://github.com/OpenBMB/MiniCPM-o-Demo/blob/main/docs/en/deployment.md
- https://github.com/OpenBMB/MiniCPM-o-Demo/blob/main/docs/en/video-duplex-protocol.md
- https://github.com/OpenBMB/MiniCPM-o-Demo/blob/main/docker/entrypoint-worker-backend.sh
- https://huggingface.co/openbmb/MiniCPM-o-4_5
- https://github.com/QwenLM/Qwen3-ASR
- https://huggingface.co/Qwen/Qwen3.5-4B
- https://github.com/QwenLM/Qwen3-TTS

실행에 쓰는 정확한 코드/모델 revision은 configs의 JSON manifest를 따른다.
