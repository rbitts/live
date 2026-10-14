#include "live_engine.hpp"
#include "android_acceleration.hpp"
#include "ujson.hpp"
#include <jni.h>
#include <chrono>
#include <fstream>
#include <cmath>
#include <algorithm>
#include <vector>

using Json = ujson::json;
using ProfileClock = std::chrono::steady_clock;
static double ms(ProfileClock::time_point start) {
    return std::chrono::duration<double,std::milli>(ProfileClock::now()-start).count();
}
static std::string arg(JNIEnv* env,jstring value) {
    const char* bytes=env->GetStringUTFChars(value,nullptr);
    std::string result(bytes);env->ReleaseStringUTFChars(value,bytes);return result;
}
static Json counters(const MNN::Transformer::LlmContext* c) {
    Json j;
    j["prompt_tokens"]=c->prompt_len;j["generated_tokens_or_frames"]=c->gen_seq_len;
    j["vision_ms"]=c->vision_us/1000.;j["audio_ms"]=c->audio_us/1000.;
    j["prefill_ms"]=c->prefill_us/1000.;j["decode_ms"]=c->decode_us/1000.;
    j["sample_ms"]=c->sample_us/1000.;j["image_megapixels"]=c->pixels_mp;
    j["audio_input_seconds"]=c->audio_input_s;
    return j;
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_live_offline_ModelProfileInstrumentation_profileNative(
    JNIEnv* env,jobject,jstring modelRoot,jstring wavPath,jstring imagePath,
    jstring variantValue,jstring roleValue,jint repeats,jstring resultPath) {
    try {
        auto root=arg(env,modelRoot),wav=arg(env,wavPath),image=arg(env,imagePath);
        auto variant=arg(env,variantValue),role=arg(env,roleValue);
        if(repeats<1 || repeats>5) throw std::runtime_error("repeat limit");
        if(role!="all" && role!="asr" && role!="vlm" && role!="tts") throw std::runtime_error("role");
        if(variant!="baseline" && variant!="high4" && variant!="high8"
            && variant!="vision256" && variant!="tts32" && variant!="ttsfull"
            && variant!="opencl-vlm" && variant!="opencl-tts" && variant!="opencl-asr"
            && variant!="opencl-vision" && variant!="production"
            && variant!="opencl-all" && variant!="opencl256") throw std::runtime_error("variant");
        std::ofstream out(arg(env,resultPath));
        if(!out) throw std::runtime_error("profile output unavailable");
        LiveEngine engine;
        auto loadStart=ProfileClock::now();
        engine.load(root,[&](const std::string& currentRole){
            if(variant=="production")return androidAcceleratedConfig(root,currentRole);
            Json config=Json::object();
            if(variant!="baseline") {
                int threads=variant=="high8"?8:4;
                config["thread_num"]=threads;config["power"]="high";
                config["mllm"]=Json::object();
                config["mllm"]["backend_type"]="cpu";config["mllm"]["thread_num"]=threads;
                config["mllm"]["precision"]="normal";config["mllm"]["memory"]="low";
                config["mllm"]["power"]="high";
            }
            if((variant=="vision256" || variant=="opencl256") && currentRole=="vlm") {
                config["image_min_pixels"]=65536;config["image_max_pixels"]=65536;
            }
            if(variant=="tts32" && currentRole=="tts") config["tts_chunk_frames"]=32;
            if(variant=="ttsfull" && currentRole=="tts") config["tts_chunk_frames"]=0;
            const bool gpuDecoder=variant=="opencl-"+currentRole || variant=="opencl-all"
                || (variant=="opencl256" && currentRole=="vlm");
            const bool gpuProcessor=gpuDecoder || (variant=="opencl-vision" && currentRole=="vlm");
            if(gpuDecoder) config["backend_type"]="opencl";
            if(gpuProcessor) {
                config["mllm"]["backend_type"]="opencl";config["mllm"]["thread_num"]=4;
                config["mllm"]["precision"]="low";
                config["tmp_path"]=root+"/"+currentRole+"/gpu-cache";
            }
            return config.dump();
        });
        const double loadMs=ms(loadStart);
        Json loaded=Json::object();loaded["event"]="loaded";loaded["variant"]=variant;loaded["load_ms"]=loadMs;
        loaded["available_gpu"]=androidOpenClDevice();
        out<<loaded.dump()<<std::endl;
        const std::string fixedQuestion="현재 화면에서 컵의 중심은 어느 구역이 있나요?";
        const std::string fixedAnswer="현재 화면에서 컵의 중심은 오른쪽 구역입니다.";
        for(int i=0;i<repeats;++i) {
            Json run;run["variant"]=variant;run["role"]=role;run["iteration"]=i;
            run["load_ms"]=loadMs;run["tts_text"]=fixedAnswer;
            engine.cancel(i+1);
            if(role=="all" || role=="asr") {
                const double previousInput=engine.context("asr")->audio_input_s;
                auto start=ProfileClock::now();auto text=engine.transcribe(wav,i+1);
                run["asr"]=counters(engine.context("asr"));run["asr"]["wall_ms"]=ms(start);
                run["asr"]["audio_input_seconds"]=engine.context("asr")->audio_input_s-previousInput;
                run["asr"]["text"]=text;
            }
            if(role=="all" || role=="vlm") {
                const auto previousVision=engine.context("vlm")->vision_us;
                const double previousPixels=engine.context("vlm")->pixels_mp;
                double first=-1;int updates=0;auto start=ProfileClock::now();
                auto answer=engine.answer("현재 화면: 0.0초 전.<img>"+image+"</img>",fixedQuestion,i+1,
                    [&](const std::string& text){if(!text.empty()){if(first<0)first=ms(start);++updates;}});
                run["vlm"]=counters(engine.context("vlm"));run["vlm"]["wall_ms"]=ms(start);
                run["vlm"]["vision_ms"]=(engine.context("vlm")->vision_us-previousVision)/1000.;
                run["vlm"]["image_megapixels"]=engine.context("vlm")->pixels_mp-previousPixels;
                run["vlm"]["first_text_ms"]=first;run["vlm"]["updates"]=updates;run["vlm"]["text"]=answer;
            }
            if(role=="all" || role=="tts") {
                Json chunks=Json::array();double first=-1;size_t samples=0;
                double squares=0,peak=0;bool finite=true;
                std::vector<int16_t> pcm;pcm.reserve(24*24000);
                auto start=ProfileClock::now();
                bool ok=engine.speak(fixedAnswer,i+1,[&](const float* data,size_t count,bool){
                    if(count){if(first<0)first=ms(start);samples+=count;
                        for(size_t n=0;n<count;++n){
                            finite=finite && std::isfinite(data[n]);
                            if(!finite)return false;
                            squares+=data[n]*data[n];peak=std::max(peak,std::abs(double(data[n])));
                            if(i==0 && pcm.size()<24*24000)pcm.push_back(int16_t(std::clamp(data[n],-1.f,1.f)*32767));
                        }
                        Json chunk=Json::object();chunk["at_ms"]=ms(start);chunk["samples"]=count;
                        chunks.push_back(chunk);}return true;
                });
                run["tts"]=counters(engine.context("tts"));run["tts"]["wall_ms"]=ms(start);
                run["tts"]["first_pcm_ms"]=first;run["tts"]["samples"]=samples;
                run["tts"]["audio_seconds"]=samples/24000.;run["tts"]["rtf"]=ms(start)/(samples/24.);
                run["tts"]["chunks"]=chunks;run["tts"]["success"]=ok;
                run["tts"]["rms"]=std::sqrt(squares/std::max(size_t(1),samples));
                run["tts"]["peak"]=peak;run["tts"]["finite"]=finite;
                // MNN's TTS decode timer includes streaming waveform decode and callbacks.
                run["tts"]["decode_timer_includes_audio"]=variant!="ttsfull";
                if(!ok || !samples || !finite || squares==0) throw std::runtime_error("TTS profile failed");
                if(i==0) {
                    std::ofstream audio(arg(env,resultPath)+".pcm",std::ios::binary);
                    audio.write(reinterpret_cast<const char*>(pcm.data()),pcm.size()*sizeof(int16_t));
                }
            }
            out<<run.dump()<<std::endl;
        }
        return env->NewStringUTF("PASS");
    } catch(const std::exception& e) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),e.what());return nullptr;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_live_offline_CookingProfileInstrumentation_profile(JNIEnv* env,jobject,jstring configValue,
    jstring casesValue,jstring outputValue,jint pixels,jboolean compact,jint repeats) {
    try {
        if((pixels!=0 && pixels!=65536 && pixels!=147456) || repeats<1 || repeats>3)throw std::runtime_error("profile bounds");
        auto config=arg(env,configValue);auto folder=config.substr(0,config.find_last_of('/'));
        std::ifstream file(arg(env,casesValue));std::string source((std::istreambuf_iterator<char>(file)),{});
        auto cases=Json::parse(source);if(cases.size()>24)throw std::runtime_error("case limit");
        std::ofstream out(arg(env,outputValue));if(!out)throw std::runtime_error("output");
        LiveEngine engine;auto settings=Json::parse(androidAcceleratedConfig(folder,"vlm"));
        settings["tmp_path"]=folder+"/gpu-cache";
        settings["jinja"]["context"]["enable_thinking"]=false;
        if(pixels){settings["image_min_pixels"]=pixels;settings["image_max_pixels"]=pixels;}
        auto start=ProfileClock::now();engine.loadVision(config,settings.dump());
        Json loaded;loaded["load_ms"]=ms(start);loaded["gpu"]=androidOpenClDevice();loaded["config"]=config;
        loaded["pixels"]=pixels;loaded["compact"]=bool(compact);out<<loaded.dump()<<std::endl;
        int epoch=0;
        for(int repeat=0;repeat<repeats;repeat++)for(size_t n=0;n<cases.size();n++) {
            auto item=cases[n];Json row;row["case"]=item["id"];row["repeat"]=repeat;
            auto c=engine.context("vlm");auto previousVision=c->vision_us;double previousPixels=c->pixels_mp;
            double first=-1;engine.cancel(++epoch);start=ProfileClock::now();
            try{
                auto progress=[&](const std::string& text){if(first<0&&!text.empty())first=ms(start);};
                auto answer=item["state"].get<std::string>()=="general" ?
                    engine.converse("",item.value("memory",""),item["input"].get<std::string>(),epoch,progress) :
                    item["state"].get<std::string>()=="visual-check" ?
                    engine.visualCheck(item["input"].get<std::string>(),item["image"].get<std::string>(),epoch,progress) :
                    engine.cooking(item["input"].get<std::string>(),item["state"].get<std::string>(),
                    item["image"].get<std::string>(),epoch,item["plan"].get<bool>(),
                    progress,bool(compact));
                row["answer"]=answer;
            }catch(const std::exception& e){row["error"]=e.what();}
            row["wall_ms"]=ms(start);row["first_token_ms"]=first;
            row["metrics"]=counters(engine.context("vlm"));
            row["metrics"]["vision_ms"]=(engine.context("vlm")->vision_us-previousVision)/1000.;
            row["metrics"]["image_megapixels"]=engine.context("vlm")->pixels_mp-previousPixels;
            out<<row.dump()<<std::endl;
        }
        return env->NewStringUTF("PASS");
    }catch(const std::exception& e){env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),e.what());return nullptr;}
}

extern "C" JNIEXPORT jstring JNICALL Java_dev_live_offline_RecognitionFixInstrumentation_transcribeContext(JNIEnv* env,jobject,jlong handle,jstring wav,jlong epoch,jstring context) {
    auto value=reinterpret_cast<LiveEngine*>(handle)->transcribe(arg(env,wav),epoch,arg(env,context));
    auto utf16=utf8ToUtf16(value);return env->NewString(reinterpret_cast<const jchar*>(utf16.data()),utf16.size());
}
