# 모바일 Live transport v1

`/ws`의 UTF-8 JSON WebSocket 계약이다. 웹과 네이티브 앱이 같은 Qwen 시스템 duplex 상태 머신을 사용하기 위한 기반이며 온디바이스 모델/모바일 앱 구현 완료를 의미하지 않는다.

| 방향 | type | 필드와 동작 |
|---|---|---|
| 서버→앱 | ready | `protocol_version:1`, `track:system-duplex`, `frame_capture:ticket-v1` |
| 앱→서버 | audio | `pcm`: mono 16kHz little-endian float32, 320 sample/20ms, base64. 1280 raw bytes, finite 값 |
| 앱→서버 | frame_request | 새 캡처 ticket 요청, 최대 하나만 진행. `request_id`: 선택적 0 이상 safe integer |
| 서버→앱 | frame_ticket | `ticket`: opaque 문자열, `max_age_ms:3000`, 전달된 `request_id` echo(생략 시 null) |
| 앱→서버 | frame | `ticket`, `jpeg`: ticket 수신 뒤 새 JPEG, base64 최대 400,000문자/최대 변 1280px |
| 앱→서버 | camera_off | 최신 프레임/진행 중 ticket 삭제, 과거 관측 기억 유지 |
| 앱→서버 | interrupt | 앱이 즉시 재생/대기 큐를 비운 뒤 전송 |
| 앱→서버 | text/profile/reset | 질문/세션 선호/기억 삭제. text 2,000자, profile 500자 제한 |
| 서버→앱 | playback_stop | 증가한 `epoch`, 앱은 현재 재생/대기 큐 삭제 |
| 서버→앱 | speech_started/speech_ended | 발화 구간 상태와 `epoch` |
| 서버→앱 | transcript/response_start/text | `epoch`, 해당 메시지의 `text` |
| 서버→앱 | audio | `epoch`, base64 float32 PCM, `sample_rate`, 첫 청크 `first_audio_ms`(이후 null) |
| 서버→앱 | response_done | `epoch`, `elapsed_ms` |
| 서버→앱 | reset_done/error | 초기화 완료 또는 `message` 오류 |

장치 기본 48kHz 입력은 먼저 16kHz mono로 리샘플링한다. 재연결은 새 세션이며 이전 입력/기억을 복원하지 않는다.

## 카메라 freshness

1. 앱이 `frame_request`를 보내면 서버는 ticket/서버 monotonic 발급 시간을 하나만 보관한다.
2. 앱은 ticket 수신 **뒤의 새 프레임**을 획득한다. 캐시 이미지 재전송은 계약 위반이다. 웹은 `requestVideoFrameCallback`으로 다음 프레임을 기다리고, 네이티브 앱은 capture callback/센서 timestamp로 확인해야 한다.
3. 같은 ticket으로 JPEG를 보내면 서버는 ticket을 한 번 소비한다. 발급 후 3초를 넘긴 프레임은 폐기한다.
4. 현재 장면 나이는 수신 시각이 아닌 ticket 발급 시각부터 센다. 올바른 클라이언트의 실제 capture보다 앞선 보수적인 시각으로 요청·촬영·전송 지연을 포함한다. 장치/서버 wall-clock 동기화는 필요 없다.
5. ticket 교체·camera_off·reset·disconnect 후 도착한 frame/replay는 반영하지 않는다. 만료 frame에는 별도 ack를 보내지 않는다. 앱은 request_id를 증가시키고 종료/reset 시 무효화하여 늦은 ticket 응답 자체도 무시한다.

레거시 GPU smoke를 위해 ticket을 한 번도 요청하지 않은 연결은 ticket 없는 frame을 수신 시각으로 처리한다. 이 경로는 네트워크 지연을 포함한 freshness 보장이 없어 **모바일 합격 시험에 사용하지 않는다**. `frame_request` 이후에는 ticket 없는 frame을 거부한다. 센서 노출 시각이나 카메라 드라이버 정체까지 서버가 증명하지는 않는다.

## duplex와 제한

- 재생 중 캡처/전송을 유지한다. AEC/스피커 에코/audio focus는 실기기 검증 대상이다.
- 클라이언트 음성 감지 시 즉시 재생을 중단한다. 서버 VAD 또는 interrupt가 epoch을 증가시킨다. 현재 epoch의 `response_start` 이전에는 재생을 재개하지 않는다.
- text/audio는 현재 epoch만 표시/재생한다. 취소 뒤 늦은 결과는 폐기한다. on-device worker도 같은 epoch 검사/cooperative cancel이 필요하다.
- 웹은 전송 전 대기 bytes가 64,000을 넘으면 연결을 정리한다. 한 frame 추가 크기는 별도 제한으로 bounded다. ticket 1개, 1fps 요청, 재생 대기 약 8초 상한이다. 정체 frame을 모아서 따라잡지 않는다.
- 화면 종료/앱 background에서는 캡처·재생·연결을 정리한다. 네이티브 lifecycle 구현은 남아 있다.
- 서버는 loopback 단일 사용자 PoC다. 개발 터널/인증된 TLS proxy를 사용하고 공개 다중 사용자 endpoint로 취급하지 않는다.
