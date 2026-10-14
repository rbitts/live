#include "live_engine.hpp"
#include "ujson.hpp"
#include <chrono>
#include <fstream>
#include <sys/resource.h>

using Clock=std::chrono::steady_clock;
static double seconds(Clock::time_point t){return std::chrono::duration<double>(Clock::now()-t).count();}
int main(int argc,char** argv) {
    if(argc!=5){std::cerr<<"bundle requests.jsonl results.jsonl max_requests\n";return 2;}
    try {
        LiveEngine engine; auto start=Clock::now();engine.load(argv[1]);double load=seconds(start);
        std::ifstream requests(argv[2]);std::ofstream output(argv[3]);std::string line;unsigned long long epoch=0;
        int limit=std::stoi(argv[4]);if(limit<1 || limit>100)return 2;
        while(std::getline(requests,line)) {
            if(line.size()>16384 || ++epoch>static_cast<unsigned>(limit))return 2;
            auto row=ujson::json::parse(line);engine.cancel(epoch);start=Clock::now();
            auto question=row.value("question","");
            if(question.empty())question=engine.transcribe(row.value("audio_path",""),epoch);
            double asr=seconds(start);auto answer=engine.answer(row.value("observation",""),question,epoch);
            double vlm=seconds(start)-asr;double first=-1;size_t samples=0;auto chunks=ujson::json::array();
            std::ofstream pcm(row.value("pcm_path",""),std::ios::binary);if(!pcm)return 2;
            int cancelAfter=row.value("cancel_after_chunks",0),count=0;
            bool ok=engine.speak(answer,epoch,[&](const float* data,size_t n,bool final){
                if(n){
                    if(first<0)first=seconds(start);
                    if(samples+n>24000*30)throw std::runtime_error("audio buffer limit");
                    pcm.write(reinterpret_cast<const char*>(data),n*sizeof(float));samples+=n;++count;
                    auto chunk=ujson::json::object();chunk["seconds"]=seconds(start);chunk["samples"]=int(n);chunks.push_back(chunk);
                    if(cancelAfter && count>=cancelAfter){engine.cancel(epoch+1);return false;}
                }
                return true;
            });
            if(cancelAfter ? ok || count!=cancelAfter : !ok)return 4;
            auto result=ujson::json::object();result["id"]=row.value("id","");result["question"]=question;result["answer"]=answer;
            result["load_seconds"]=load;result["asr_seconds"]=asr;result["vlm_seconds"]=vlm;result["total_seconds"]=seconds(start);
            result["first_audio_seconds"]=first;result["samples"]=int(samples);result["chunks"]=chunks;result["cancelled"]=!ok;
            struct rusage usage{};getrusage(RUSAGE_SELF,&usage);result["peak_rss_kib"]=double(usage.ru_maxrss);
            output<<result.dump()<<std::endl;std::cout<<"completed "<<epoch<<std::endl;
        }
    }catch(const std::exception& e){std::cerr<<e.what()<<std::endl;return 4;}
    return 0;
}
