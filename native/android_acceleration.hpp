#pragma once
#include "ujson.hpp"
#include <CL/cl.h>
#include <dlfcn.h>
#include <string>
#include <vector>

// Verify a vendor GPU exists; registering the MNN backend alone is insufficient.
inline std::string androidOpenClDevice() {
    void* lib=dlopen("libOpenCL.so", RTLD_NOW | RTLD_LOCAL);
    if(!lib)return {};
    auto platforms=reinterpret_cast<decltype(&clGetPlatformIDs)>(dlsym(lib,"clGetPlatformIDs"));
    auto devices=reinterpret_cast<decltype(&clGetDeviceIDs)>(dlsym(lib,"clGetDeviceIDs"));
    auto info=reinterpret_cast<decltype(&clGetDeviceInfo)>(dlsym(lib,"clGetDeviceInfo"));
    std::string name;cl_uint count=0;
    if(platforms && devices && info && platforms(0,nullptr,&count)==CL_SUCCESS && count>0 && count<32) {
        std::vector<cl_platform_id> ids(count);
        if(platforms(count,ids.data(),nullptr)==CL_SUCCESS)for(auto platform:ids){
            cl_device_id device=nullptr;
            if(devices(platform,CL_DEVICE_TYPE_GPU,1,&device,nullptr)!=CL_SUCCESS)continue;
            char value[256]={};
            if(info(device,CL_DEVICE_NAME,sizeof(value),value,nullptr)==CL_SUCCESS){name=value;break;}
        }
    }
    dlclose(lib);return name;
}

// Validated Android profile: retain image resolution and model weights.
// ASR/TTS stay on their existing CPU path; all-GPU TTS stalls on this driver.
inline std::string androidAcceleratedConfig(const std::string& root,const std::string& role) {
    auto config=ujson::json::object();
    if(role!="vlm" && role!="observer")return config.dump();
    config["backend_type"]="opencl";config["thread_num"]=4;
    config["precision"]="low";config["memory"]="low";config["power"]="high";
    config["tmp_path"]=root+"/"+role+"/gpu-cache";
    config["mllm"]=ujson::json::object();
    config["mllm"]["backend_type"]="opencl";config["mllm"]["thread_num"]=4;
    config["mllm"]["precision"]="low";config["mllm"]["memory"]="low";
    config["mllm"]["power"]="high";
    return config.dump();
}
