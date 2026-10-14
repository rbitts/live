# 모바일 OJT PC 실행 가이드

현재 판정과 실패는 [PC 검증 보고서](../reports/mobile-pc-validation.md)가 기준이다. `.runtime/ojt`에는 대용량 데이터·가중치·빌드 산출물을 보관하고 git에 넣지 않는다. 아래는 저장소 루트에서 실행한다. 실제 모바일 설치는 아직 수행하지 않는다.

## 환경

Python 3.11 환경을 각각 만들고 해당 lock을 설치한다. 기존 `.venv-asr/.venv-vlm/.venv-tts/.venv-native`를 바꾸지 않는다. `requirements/export-asr.lock`과 `export-tts.lock`의 editable 경로는 `configs/upstreams.json` revision으로 checkout한 `.upstream/qwen-asr`와 `.upstream/qwen-tts`를 참조한다.

```bash
uv venv --python 3.11 .venv-ft
uv pip sync --python .venv-ft/bin/python requirements/ft.lock
uv venv --python 3.11 .venv-export
uv pip sync --python .venv-export/bin/python requirements/export.lock
uv venv --python 3.11 .venv-export-asr
uv pip sync --python .venv-export-asr/bin/python requirements/export-asr.lock
uv venv --python 3.11 .venv-export-tts
uv pip sync --python .venv-export-tts/bin/python requirements/export-tts.lock
```

MNN은 manifest의 고정 commit을 checkout하고 `git -C .upstream/mnn apply --check ../../patches/mnn-customvoice-sohee.patch` 후 적용한다. 이미 적용한 작업 트리에 중복 적용하지 않는다. 소스 빌드 예:

```bash
.venv-export/bin/cmake -S .upstream/mnn -B .runtime/ojt/mnn-build -G Ninja \
  -DCMAKE_MAKE_PROGRAM="$PWD/.venv-export/bin/ninja" \
  -DCMAKE_BUILD_TYPE=Release -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
  -DMNN_LOW_MEMORY=ON -DMNN_BUILD_LLM=ON -DMNN_BUILD_LLM_OMNI=ON \
  -DMNN_SUPPORT_TRANSFORMER_FUSE=ON -DMNN_BUILD_OPENCV=ON \
  -DMNN_IMGCODECS=ON -DMNN_BUILD_AUDIO=ON -DMNN_BUILD_CONVERTER=ON -DMNN_AVX512=OFF
.venv-export/bin/cmake --build .runtime/ojt/mnn-build -j 6
```

MNN Python wheel의 executable-stack 오류를 해결하기 위해 호스트 보안 설정을 바꾸지 않는다. exporter에 위 C++ `MNNConvert`의 절대 경로를 지정한다.

## 데이터·학습

GQA 가공/선택 다운로드는 [데이터 문서](mobile-datasets.md)를 따른다. `plate/pot`의 다의어를 배제한다. 현재 활성 데이터는 `.runtime/ojt/combined-prepared-001`이고 원본 sidecar는 `combined-audit-001`이다. VISOR adapter는 `scripts/import_visor_ojt.py`; 공식 sparse annotation, `frame_mapping.json`, `EPIC_100_video_info.csv`가 필요하다.

검수 전 실행은 명시적인 실험이며 acceptance가 아니다. 데이터/출력은 덮어쓰지 않고 새로운 폴더를 사용한다. 학습 스크립트는 실행 직전 doctor·GPU 여유·프로젝트 잠금을 확인한다.

```bash
CUDA_VISIBLE_DEVICES=1 HF_HUB_OFFLINE=1 TRANSFORMERS_OFFLINE=1 \
  .venv-ft/bin/python scripts/pc_vlm_experiment.py \
  --data .runtime/ojt/combined-prepared-001 --output .runtime/ojt/lora-new \
  --mode lora --steps 50 --allow-unreviewed-experiment

CUDA_VISIBLE_DEVICES=1 HF_HUB_OFFLINE=1 TRANSFORMERS_OFFLINE=1 \
  .venv-ft/bin/python scripts/pc_vlm_experiment.py \
  --data .runtime/ojt/combined-prepared-001 --output .runtime/ojt/qat-new \
  --mode qat --adapter .runtime/ojt/lora-new/adapter --steps 40 \
  --quantize-shared-head --allow-unreviewed-experiment

CUDA_VISIBLE_DEVICES='' .venv-ft/bin/python scripts/merge_ojt_adapter.py \
  --adapter .runtime/ojt/qat-new/adapter --output .runtime/ojt/merged-new

CUDA_VISIBLE_DEVICES='' .venv-export/bin/python scripts/check_quant_parity.py
```

위 새 실험은 첫 보고서의 GQA 100-step 선행 LoRA와 동일한 학습 이력이 아니다. 보고서 실험을 정확히 재현할 때에는 `pc-lora-pilot-002`의 설정/adapter를 기준으로 혼합 데이터 50 step을 이어 간다. train만 학습하고 validation으로 선택한다. 평가를 본 뒤 수정한 모델/프롬프트에는 새 독립 test가 필요하다.

## 변환·PC 실행

```bash
CUDA_VISIBLE_DEVICES='' HF_HUB_OFFLINE=1 TRANSFORMERS_OFFLINE=1 OMP_NUM_THREADS=4 \
  .venv-export/bin/python scripts/export_mnn.py vlm \
  --path .runtime/ojt/merged-new --dst_path .runtime/ojt/mnn-new --export mnn \
  --quant_bit 4 --quant_block 64 --sym --scale_bit 32 --visual_quant_bit 16 \
  --disable_fuse_ln_proj --mnnconvert "$PWD/.runtime/ojt/mnn-build/MNNConvert"
```

ASR는 `.venv-export-asr`/`role=asr`/`--path models/asr`/`--quant_bit 8`을 사용한다. TTS는 `.venv-export-tts`/`role=tts`/`--path models/tts`/`--quant_bit 8 --tts-decoder-bit 8`을 사용한다. 기본 TTS decoder 선택은 FP16이므로 현재 INT8 패키지 재현에는 마지막 인자를 생략하지 않는다. wrapper는 한국어 ASR template/UTF-8 제약, CustomVoice/Sohee/sampling/청크 설정, VLM thinking-off/영상 크기 제한을 기록한다.

`.venv/bin/python scripts/build_pc_native.py`는 `.runtime/ojt/bin/`에 실행기를 만들고 policy test를 실행한다. PC 실행기 `native/pc_mnn_runner.cpp`, `pc_live_e2e.cpp`, `pc_cancel_stress.cpp`, `pc_policy_test.cpp`는 C++17로 빌드한다. include는 `.upstream/mnn/include`, `transformers/llm/engine/include`, `transformers/llm/engine/src`, `3rd_party`, `3rd_party/rapidjson/include`; link는 빌드 폴더의 `-lllm -lMNN`이다. 스트레스 실행기는 `-pthread`도 필요하다.

```bash
.venv/bin/python scripts/pc_mnn_evaluate.py \
  --data .runtime/ojt/combined-prepared-001 --config .runtime/ojt/mnn-new/config.json \
  --output .runtime/ojt/native-new-test --split test --runner .runtime/ojt/bin/pc_mnn_runner

.venv/bin/python scripts/run_offline.py .runtime/ojt/bin/pc_live_e2e \
  .runtime/ojt/pc-bundle .runtime/ojt/e2e-delivery-requests.jsonl \
  .runtime/ojt/e2e-new-results.jsonl 2
```

`pc-bundle`는 `asr/config.json`, `vlm/config.json`, `tts/config.json` 구조다. 모델 평가와 E2E 시간을 섞지 않는다. `run_offline.py`는 Linux x86_64 자식 프로세스의 네트워크 syscall을 차단한다. 모델 로드 실패/생성 상한/잘못된 UTF-8/누락 결과를 합격으로 처리하지 않는다.

## Android 빌드·패키지

AGP 8.7.3, Gradle 8.9, JDK 17, SDK 35/build-tools 35.0.0, NDK 27.2.12479018, CMake 3.22.1로 검증했다. 이 세션의 도구는 `.runtime/ojt/toolchains`와 `.runtime/ojt/android-sdk`에 격리했다. SDK 위치는 git 제외된 `android/local.properties`에 지정한다.

```bash
JAVA_HOME="$PWD/.runtime/ojt/toolchains/jdk-17.0.20.1+1" \
GRADLE_USER_HOME="$PWD/.runtime/ojt/gradle-cache" \
  .runtime/ojt/toolchains/gradle-8.9/bin/gradle -p android --offline --no-daemon assembleDebug lintDebug
```

`--offline`은 최초 의존성 확보 후 사용한다. 에뮬레이터 빌드는 `-PliveAbis=x86_64`이며 ARM64와 같은 APK 출력 경로를 사용하므로 각 결과를 별도 보관한다. 앱은 CPU backend만 사용하고 INTERNET 권한이 없다. debug instrumentation은 가상 카메라/마이크/무음 재생과 악성 모델 ZIP 거부를 검사한다. 실제 모델을 mock으로 대체해 모델 합격으로 표시하지 않는다.

```bash
.venv/bin/python scripts/package_mobile_models.py \
  --asr .runtime/ojt/mnn-asr-int8 --vlm .runtime/ojt/mnn-qat-compact \
  --tts .runtime/ojt/mnn-tts-int8 --output .runtime/ojt/artifacts/models-new.zip
```

기기에서 사용할 때에는 검증된 ZIP을 로컬에 준비하고 앱의 “모델 가져오기”로 가져온다. 임시 설치 폴더에서 검증 후 전환하며 다운로드 URL/로그인/서버 fallback은 없다. SM-X926N 태블릿의 설치 및 고정 입력 모델 smoke은 통과했다. PC 단계의 실패와 실제 Galaxy S/12GB의 별도 acceptance는 계속 구분한다.

## 곁 · Live 앱 사용 (2026-10-13)

앱 흐름/테마/기억 정책은 [워크플로우](android-app-workflow.md), 실제 Android 모델 결과는 [검증 보고서](../reports/android-live-validation.md)를 따른다.

1. `.runtime/android-icons/artifacts/gyut-live-arm64-debug.apk`를 대상 Galaxy S에 설치한다. 연구용 debug 서명 APK다.
2. `.runtime/ojt/artifacts/live-qat-models.zip`을 기기의 로컬 저장소에 복사한다. ZIP은 약 5.5GB이며 설치용 여유 공간은 별도다.
3. 첫 화면의 **화살표 → 다운로드 아이콘**으로 모델을 가져온다. 상단 상태 아이콘이 체크로 바뀔 때까지 기다린다.
4. 카메라 사용 여부를 정하고 **체크 → 큰 마이크 아이콘**을 누른다. 각 아이콘은 길게 누르면 설명이 나온다.
5. 마이크 권한과 선택한 카메라 권한을 허용하고 질문한다. 텍스트 질문도 사용할 수 있다.
6. 하단 **겹친 사진 아이콘**에서 관측 개수·시간을 바꾸거나 휴지통으로 삭제한다. 다른 앱으로 나가면 이번 대화의 기억은 지워진다.

검증용 x86_64 APK는 같은 폴더의 `gyut-live-x86_64-debug.apk`다. 최초 검증은 전용 `emulator-5580`에서 수행했다. 이후 사용자 요청으로 SM-X926N 실기기에 임시 설치했으며 결과는 [실기기 설치 기록](../reports/android-device-validation.md)을 따른다.

소유권을 확인한 테스트 에뮬레이터에서 다음 instrumentation을 실행할 수 있다. `SERIAL`은 본인 테스트 기기로 바꾼다. 공용 에뮬레이터와 실제 사용자 장치에 무심코 실행하지 않는다.

```bash
adb -s SERIAL shell am instrument -w dev.live.offline/.LiveWorkflowInstrumentation
adb -s SERIAL shell pm grant dev.live.offline android.permission.RECORD_AUDIO
adb -s SERIAL shell pm grant dev.live.offline android.permission.CAMERA
adb -s SERIAL shell am instrument -w dev.live.offline/.PcSmokeInstrumentation
```

실제 모델 경로는 앱 전용 외부 파일 폴더에 `models.zip`, `scene.jpg`, PCM16 mono 16kHz의 44-byte header `question.wav`를 준비한 뒤 실행한다. 소프트웨어 UI만 검사하는 첫 명령과 구분한다.

```bash
adb -s SERIAL shell am instrument -w \
  -e bundle /sdcard/Android/data/dev.live.offline/files/models.zip \
  dev.live.offline/.LiveWorkflowInstrumentation
# 이미 설치한 모델을 cold load해 검사할 때에는 -e reuse true 추가
adb -s SERIAL shell am instrument -w dev.live.offline/.LiveCancelInstrumentation
```

모델 검사는 실제 입력 자료를 앱 요청 경로에 주입한다. 가상 카메라/마이크의 실제 음향이나 물리 기기의 발열을 재현하지 않는다. 최종 사람 사용 검수에는 [Live 합격 기준](verification.md)을 그대로 적용한다.

명시적으로 허용된 실기기에서 다른 화면 자동화 서비스가 이미 연결되어 있다면 `-e skipScreenshots true`로 화면 수집만 생략할 수 있다. 고정 입력 모델 검증과 주변 마이크 입력을 분리하려면 `-e fixtureOnly true`를 추가한다. 이 경우 실제 마이크 동시성은 검사하지 않으며 보고서에도 `live_microphone_test: false`로 남는다. `-e scope`로 실제 검사 환경을 기록한다. 이 검사는 앱의 설정·세션을 초기화하므로 사용 중인 앱 데이터가 있는 기기에서 무심코 실행하지 않는다.


## Android GPU 단계 진단 (0.6)

검증된 MT6989 실기기에서 VLM OpenCL을 사용한다. `libOpenCL.so` vendor 접근은 optional이며 다른 기기에는 CPU를 유지한다. 고정 모델 revision/해상도는 바꾸지 않는다. 설치 APK와 단계 계측은 [실측 보고서](../reports/android-latency-diagnosis.md)를 따른다. `ModelProfileInstrumentation`의 `production` 프로필은 실제 앱과 설정 함수를 공유한다. `LiveWorkflowInstrumentation`에는 `-e requireGpu true`로 가속 활성화를 검증할 수 있다. `LiveCancelInstrumentation -e phase playback`은 생성 완료 후 실제 재생 중단도 구분한다. GPU TTS 실험은 정지하여 기본 경로에서 제외했다.


Android 0.13 추가 소스: `.upstream/webrtcvad`를 `configs/upstreams.json`의 URL/revision으로 checkout한다. `native/CMakeLists.txt`가 해당 원본의 VAD/signal-processing 소스를 정적으로 빌드하며 Python wrapper 설치는 필요 없다. APK에 WebRTC 라이선스를 포함한다. 기존 모델/변환 환경의 의존성은 변경하지 않는다.
# Android streaming voice detector preparation

Before the offline Android build, run `.venv/bin/python scripts/prepare_android_vad.py` once. It fetches and SHA-256 checks the Silero ONNX model already pinned by the MiniCPM demo revision and ONNX Runtime Android 1.23.2, plus their licenses, using `configs/android-vad.json`. All binaries stay under ignored `.runtime/android-vad`; Gradle verifies the hashes and packages only the selected ABI. Existing ASR/VLM/TTS model revisions are unchanged. This additional runtime is used only for the small voice detector, since the WebRTC detector produced a user-confirmed quiet-Buds false onset. It adds approximately 19.4MB of uncompressed ARM64 native libraries plus the 1.8MB detector model. Its real-device acceptance belongs in the streaming voice report; successful download/build alone is not detector acceptance.



### Whisper tablet comparison (debug only,2026-10-13)

Run `.venv/bin/python scripts/prepare_android_asr.py` for pinned Sherpa candidate runtime assets and `.venv/bin/python scripts/prepare_android_whisper.py` for large external Whisper weights. Never add these files to git. Debug builds verify candidate assets; release builds do not bundle them. No live recognizer selection changes merely because these files exist.

`WhisperInstrumentation` reads `turbo-encoder.int8.onnx`, `turbo-decoder.int8.onnx`, `turbo-tokens.txt` from the app-private `files/whisper/` directory. Transfer using the debug package's `run-as` identity, verify each SHA against `configs/android-whisper.json`, then use the existing public `question.wav` fixture. The first external-storage attempt failed native file validation; private app storage succeeded. `-e full_context true` is a diagnostic30s-padding comparison, not the accepted default. Reports are external `whisper-profile-cpu.json` / `whisper-profile-nnapi.json`; copy each variant before the next run overwrites it. A requested NNAPI provider may fall back: inspect runtime logs.

The optional standalone GPU benchmark uses pinned `.upstream/whisper-cpp`, `vulkan-headers`, `spirv-headers`. Run `bash scripts/build_android_whisper.sh`; it verifies source revisions and builds an ARM64 CLI using this project's NDK/CMake. It does not alter upstream sources or app ASR. Deploy the CLI and a16kHz mono fixture to `/data/local/tmp`, deploy pinned GGML weights, and run a bounded `timeout` comparison. Record context,beam,precision,actual device selection and encoder/decoder timing. Only enable ARM FP16/DOTPROD on verified hardware. The measured Mali Vulkan route was slower; see the model-study report.

`AsrReferenceInstrumentation` is an explicit one-utterance debug diagnostic. It records a bounded wider window plus the selected segment, compares local engines, waits at most120s for comparison acknowledgment, and deletes the recording. Do not arm it without a user diagnostic request; never preserve private WAVs in reports or notes.
