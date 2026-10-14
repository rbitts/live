#include "live_engine.hpp"
#include "ujson.hpp"
#include <chrono>
#include <fstream>
#include <thread>
#include <sys/resource.h>
using Clock=std::chrono::steady_clock;
static double sec(Clock::time_point t){return std::chrono::duration<double>(Clock::now()-t).count();}
int main(int argc,char** argv){
    if(argc!=4)return 2;
    try{
        std::ifstream in(argv[2]);std::string line;std::getline(in,line);auto fixture=ujson::json::parse(line);
        LiveEngine engine;engine.load(argv[1]);std::ofstream out(argv[3]);
        std::atomic<bool> capturing{true};std::atomic<int> ticks{0};std::atomic<long long> maxGapUs{0};
        std::thread input([&]{auto previous=Clock::now();while(capturing){std::this_thread::sleep_for(std::chrono::milliseconds(20));auto now=Clock::now();auto gap=std::chrono::duration_cast<std::chrono::microseconds>(now-previous).count();previous=now;long long old=maxGapUs;while(gap>old && !maxGapUs.compare_exchange_weak(old,gap)){}++ticks;}});
        auto session=Clock::now();bool failed=false;
        try{
            for(int i=0;i<10;++i){
                auto cycle=Clock::now();unsigned long long id=3*i+1;engine.cancel(id);
                auto question=engine.transcribe(fixture.value("audio_path",""),id);
                auto answer=engine.answer(fixture.value("observation",""),question,id);
                auto start=Clock::now();Clock::time_point cancelled;
                std::thread interrupt([&]{std::this_thread::sleep_for(std::chrono::milliseconds(350));cancelled=Clock::now();engine.cancel(id+1);});
                size_t late=0;bool completed=false;
                try{completed=engine.speak(answer,id,[&](const float*,size_t n,bool){if(!engine.current(id))late+=n;return engine.current(id);});}
                catch(...){interrupt.join();throw;}
                auto returned=Clock::now();interrupt.join();
                double cancelMs=std::chrono::duration<double,std::milli>(returned-cancelled).count();
                engine.cancel(id+2);size_t samples=0;double first=-1;auto recovery=Clock::now();
                bool resumed=engine.speak("현재 화면이 없어 지금 위치는 확인할 수 없습니다.",id+2,[&](const float*,size_t n,bool){if(n){if(first<0)first=sec(recovery);samples+=n;}return samples<=24000*30;});
                struct rusage usage{};getrusage(RUSAGE_SELF,&usage);auto row=ujson::json::object();
                row["cycle"]=i+1;row["cancel_ms"]=cancelMs;row["late_samples"]=int(late);row["unexpected_completion"]=completed;row["resumed"]=resumed;
                row["recovery_first_audio_seconds"]=first;row["recovery_seconds"]=sec(recovery);row["samples"]=int(samples);
                row["cycle_seconds"]=sec(cycle);row["elapsed_seconds"]=sec(session);row["peak_rss_kib"]=double(usage.ru_maxrss);
                row["synthetic_capture_ticks"]=ticks.load();row["capture_max_gap_ms"]=maxGapUs.load()/1000.0;
                out<<row.dump()<<std::endl;std::cout<<"cycle "<<i+1<<std::endl;
                failed|=completed || !resumed || late || cancelMs<0 || cancelMs>300;
                std::this_thread::sleep_until(session+std::chrono::seconds((i+1)*30));
            }
        }catch(...){capturing=false;input.join();throw;}
        capturing=false;input.join();return failed?4:0;
    }catch(const std::exception& e){std::cerr<<e.what()<<std::endl;return 4;}
}
