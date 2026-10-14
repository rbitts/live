#include <jni.h>
#include "webrtc/common_audio/vad/include/webrtc_vad.h"
extern "C" JNIEXPORT jlong JNICALL Java_dev_live_offline_NativeVad_create(JNIEnv* env,jclass) {
    VadInst* vad=WebRtcVad_Create();
    if(!vad || WebRtcVad_Init(vad)!=0 || WebRtcVad_set_mode(vad,3)!=0) {
        if(vad)WebRtcVad_Free(vad);
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),"speech detector init failed");
        return 0;
    }
    return reinterpret_cast<jlong>(vad);
}
extern "C" JNIEXPORT jboolean JNICALL Java_dev_live_offline_NativeVad_process(JNIEnv* env,jclass,jlong handle,jshortArray pcm) {
    if(!handle || env->GetArrayLength(pcm)!=320) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"),"VAD requires 320 samples"); return false;
    }
    int16_t block[320]; env->GetShortArrayRegion(pcm,0,320,block);
    if(env->ExceptionCheck())return false;
    int result=WebRtcVad_Process(reinterpret_cast<VadInst*>(handle),16000,block,320);
    if(result<0)env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),"speech detector failed");
    return result==1;
}
extern "C" JNIEXPORT void JNICALL Java_dev_live_offline_NativeVad_release(JNIEnv*,jclass,jlong handle) {
    if(handle)WebRtcVad_Free(reinterpret_cast<VadInst*>(handle));
}
