#include "live_engine.hpp"
#include "android_acceleration.hpp"
#include <jni.h>

// JNI's modified UTF-8 cannot represent ordinary four-byte emoji sequences.
static jstring javaText(JNIEnv* env, const std::string& text) {
    auto utf16=utf8ToUtf16(text);
    return env->NewString(reinterpret_cast<const jchar*>(utf16.data()),static_cast<jsize>(utf16.size()));
}

static LiveEngine* engine(jlong value){return reinterpret_cast<LiveEngine*>(value);}
static std::string string(JNIEnv* env,jstring value){
    if(!value)return {};
    auto chars=env->GetStringChars(value,nullptr);
    std::u16string utf16(chars,chars+env->GetStringLength(value));
    env->ReleaseStringChars(value,chars);
    return utf16ToUtf8(utf16);
}
static void failure(JNIEnv* env,const std::exception& e){env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),e.what());}
extern "C" JNIEXPORT jstring JNICALL Java_dev_live_offline_MainActivity_nativeTranscribe(JNIEnv* env,jobject,jlong handle,jstring wav,jlong epoch){
    try{return javaText(env,engine(handle)->transcribe(string(env,wav),epoch));}catch(const std::exception& e){failure(env,e);return nullptr;}
}
extern "C" JNIEXPORT jstring JNICALL Java_dev_live_offline_MainActivity_nativeCooking(JNIEnv* env,jobject,jlong handle,
    jstring input,jstring state,jstring image,jlong epoch,jboolean plan){
    try{return javaText(env,engine(handle)->cooking(string(env,input),string(env,state),string(env,image),epoch,plan));}
    catch(const std::exception& e){failure(env,e);return nullptr;}
}
extern "C" JNIEXPORT jlong JNICALL Java_dev_live_offline_MainActivity_nativeCreate(JNIEnv*,jobject){return reinterpret_cast<jlong>(new LiveEngine());}
extern "C" JNIEXPORT jstring JNICALL Java_dev_live_offline_MainActivity_nativeTaskObserve(JNIEnv* env,jobject,jlong handle,
    jstring input,jstring state,jstring image,jlong epoch){
    try{return javaText(env,engine(handle)->cooking(string(env,input),string(env,state),string(env,image),epoch,false,{},false,true));}
    catch(const std::exception& e){failure(env,e);return nullptr;}
}
extern "C" JNIEXPORT jstring JNICALL Java_dev_live_offline_MainActivity_nativeVisualCheck(JNIEnv* env,jobject,jlong handle,
    jstring question,jstring image,jlong epoch){
    try{return javaText(env,engine(handle)->visualCheck(string(env,question),string(env,image),epoch));}
    catch(const std::exception& e){failure(env,e);return nullptr;}
}
extern "C" JNIEXPORT void JNICALL Java_dev_live_offline_MainActivity_nativeCancel(JNIEnv*,jobject,jlong handle,jlong epoch){engine(handle)->cancel(epoch);}
extern "C" JNIEXPORT void JNICALL Java_dev_live_offline_MainActivity_nativeRelease(JNIEnv*,jobject,jlong handle){delete engine(handle);}
extern "C" JNIEXPORT jboolean JNICALL Java_dev_live_offline_MainActivity_nativeLoad(JNIEnv* env,jobject,jlong handle,jstring root,jboolean validatedDevice){
    try{
        auto path=string(env,root);
        const bool gpu=validatedDevice && !androidOpenClDevice().empty();
        engine(handle)->load(path,[&](const std::string& role){
            return gpu?androidAcceleratedConfig(path,role):"{}";
        });
        return gpu;
    }catch(const std::exception& e){failure(env,e);return false;}
}
extern "C" JNIEXPORT jstring JNICALL Java_dev_live_offline_MainActivity_nativeInfer(JNIEnv* env,jobject owner,jlong handle,
    jstring audio,jstring observation,jstring text,jstring memory,jlong epoch,jboolean speak){
    try{
        auto model=engine(handle);auto question=string(env,text);
        if(question.empty())question=model->transcribe(string(env,audio),epoch);
        if(!model->current(epoch))return env->NewStringUTF("");
        auto cls=env->GetObjectClass(owner);
        auto deltaMethod=env->GetMethodID(cls,"onModelTextDelta","(JLjava/lang/String;Ljava/lang/String;)V");
        auto emit=[&](const std::string& partial) {
            if(!model->current(epoch))return;
            auto transcript=javaText(env,question),reply=javaText(env,partial);
            env->CallVoidMethod(owner,deltaMethod,epoch,transcript,reply);
            env->DeleteLocalRef(transcript);env->DeleteLocalRef(reply);
            if(env->ExceptionCheck())throw std::runtime_error("text callback failed");
        };
        emit("");
        auto answer=model->converse(string(env,observation),string(env,memory),question,epoch,emit);
        if(!model->current(epoch)){env->DeleteLocalRef(cls);return env->NewStringUTF("");}
        auto textMethod=env->GetMethodID(cls,"onModelText","(JLjava/lang/String;Ljava/lang/String;)V");
        auto transcript=javaText(env,question),reply=javaText(env,answer);
        env->CallVoidMethod(owner,textMethod,epoch,transcript,reply);
        env->DeleteLocalRef(transcript);env->DeleteLocalRef(reply);
        if(env->ExceptionCheck()){env->DeleteLocalRef(cls);return nullptr;}
        auto method=env->GetMethodID(cls,"onPcm","(J[F)Z");
        if(speak) model->speak(answer,epoch,[&](const float* data,size_t n,bool final){
            if(!n)return model->current(epoch);
            if(n>24000*3)throw std::runtime_error("native audio chunk limit");
            auto samples=env->NewFloatArray(n);env->SetFloatArrayRegion(samples,0,n,data);
            bool accepted=env->CallBooleanMethod(owner,method,epoch,samples);env->DeleteLocalRef(samples);
            if(env->ExceptionCheck())return false;
            return accepted;
        });
        env->DeleteLocalRef(cls);
        return javaText(env,question+"\n"+answer);
    }catch(const std::exception& e){failure(env,e);return nullptr;}
}
extern "C" JNIEXPORT jstring JNICALL Java_dev_live_offline_MainActivity_nativeObserve(JNIEnv* env,jobject,jlong handle,
    jstring image,jstring memory,jstring task,jlong epoch){
    try { return javaText(env,engine(handle)->observe(string(env,image),string(env,memory),string(env,task),epoch)); }
    catch(const std::exception& e){failure(env,e);return nullptr;}
}
extern "C" JNIEXPORT void JNICALL Java_dev_live_offline_MainActivity_nativeSpeak(JNIEnv* env,jobject owner,jlong handle,
    jstring text,jlong epoch){
    try {
        auto cls=env->GetObjectClass(owner);
        auto method=env->GetMethodID(cls,"onPcm","(J[F)Z");
        engine(handle)->speak(string(env,text),epoch,[&](const float* data,size_t n,bool){
            if (!n) return engine(handle)->current(epoch);
            if(n>24000*3)throw std::runtime_error("native audio chunk limit");
            auto samples=env->NewFloatArray(n); env->SetFloatArrayRegion(samples,0,n,data);
            bool accepted=env->CallBooleanMethod(owner,method,epoch,samples); env->DeleteLocalRef(samples);
            return accepted && !env->ExceptionCheck();
        });
        env->DeleteLocalRef(cls);
    } catch(const std::exception& e){failure(env,e);}
}

extern "C" JNIEXPORT void JNICALL Java_dev_live_offline_MainActivity_nativeCancelAsr(JNIEnv*,jobject,jlong handle,jlong turn) {
    engine(handle)->cancelAsr(turn);
}
extern "C" JNIEXPORT jstring JNICALL Java_dev_live_offline_MainActivity_nativeStreamingAsr(JNIEnv* env,jobject,jlong handle,jstring wav,jlong turn,jstring context) {
    try{return javaText(env,engine(handle)->transcribeStreaming(string(env,wav),turn,string(env,context)));}
    catch(const std::exception& e){failure(env,e);return nullptr;}
}
