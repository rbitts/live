#!/usr/bin/env bash
# Standalone physical-tablet benchmark. Does not change the app's selected ASR.
set -euo pipefail
cd "$(dirname "$0")/.."
task_root="$PWD"
.venv/bin/python - <<'PY'
import json, subprocess
pins=json.load(open('configs/upstreams.json'))
for name in ('whisper-cpp','vulkan-headers','spirv-headers'):
    actual=subprocess.check_output(['git','-C','.upstream/'+name,'rev-parse','HEAD'],text=True).strip()
    if actual != pins[name]['revision']:
        raise SystemExit('Unexpected upstream revision: '+name)
PY
sdk="$task_root/.runtime/ojt/android-sdk"
ndk="$sdk/ndk/27.2.12479018"
cmake="$sdk/cmake/3.22.1/bin/cmake"
export PATH="$sdk/cmake/3.22.1/bin:$PATH"
"$cmake" -S .upstream/spirv-headers -B .runtime/whisper-vulkan-headers \
  -DCMAKE_INSTALL_PREFIX="$task_root/.runtime/whisper-vulkan-sdk"
"$cmake" --install .runtime/whisper-vulkan-headers
"$cmake" -S .upstream/whisper-cpp -B .runtime/whisper-vulkan -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 -DANDROID_STL=c++_static \
  -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF -DGGML_NATIVE=OFF \
  -DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16 -DGGML_OPENMP=OFF \
  -DGGML_VULKAN=ON -DWHISPER_BUILD_TESTS=OFF -DWHISPER_BUILD_EXAMPLES=ON \
  -DVulkan_INCLUDE_DIR="$task_root/.upstream/vulkan-headers/include" \
  -DVulkan_LIBRARY="$ndk/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/28/libvulkan.so" \
  -DVulkan_GLSLC_EXECUTABLE="$ndk/shader-tools/linux-x86_64/glslc" \
  -DSPIRV-Headers_DIR="$task_root/.runtime/whisper-vulkan-sdk/share/cmake/SPIRV-Headers" \
  -DCMAKE_CXX_FLAGS="-I$task_root/.upstream/spirv-headers/include"
"$cmake" --build .runtime/whisper-vulkan --target whisper-cli -j 6
