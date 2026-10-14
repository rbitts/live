// Local persistent-model verification runner. No device acceptance claim.
#include "llm/llm.hpp"
#include "ujson.hpp"
#include <chrono>
#include <cmath>
#include <fstream>
#include <memory>
#include <stdexcept>
#include <sys/resource.h>

using MNN::Transformer::Llm;
using MNN::Transformer::ChatMessages;
using Clock = std::chrono::steady_clock;
static double elapsed(Clock::time_point start) { return std::chrono::duration<double>(Clock::now()-start).count(); }

int main(int argc, char** argv) {
    if (argc != 5) { std::cerr << "role config.json requests.jsonl results.jsonl\n"; return 2; }
    const std::string role = argv[1];
    if (role != "vlm" && role != "asr" && role != "tts") return 2;
    std::ifstream input(argv[3]);
    if (!input) return 2;
    std::ofstream output(argv[4]);
    auto start = Clock::now();
    std::unique_ptr<Llm> model(Llm::createLLM(argv[2]));
    model->set_config("{\"async\":false,\"reuse_kv\":false,\"thread_num\":4,\"max_new_tokens\":96,\"sampler_type\":\"greedy\"}");
    if (role == "asr") model->set_config("{\"enforce_utf8\":true}");
    if (role == "tts") model->set_config("{\"tts_chunk_frames\":8,\"sampler_type\":\"mixed\",\"mixed_samplers\":[\"penalty\",\"temperature\",\"topK\",\"topP\"],\"temperature\":0.9,\"top_k\":50,\"top_p\":1.0,\"repetition_penalty\":1.05}");
    if (!model->load()) { std::cerr << "model load failed\n"; return 3; }
    double load_seconds = elapsed(start);
    std::string line;
    int count = 0;
    bool failed = false;
    while (std::getline(input, line)) {
        if (line.empty()) continue;
        if (line.size() > 16384 || ++count > 1000) return 2;
        auto request = ujson::json::parse(line);
        auto result = ujson::json::object();
        result["id"] = request.value("id", "");
        result["load_seconds"] = load_seconds;
        start = Clock::now();
        int max_frames = request.value("max_frames", 256);
        if (max_frames < 1 || max_frames > 256) return 2;
        if (role == "tts") {
            std::vector<float> samples;
            double first_audio = -1;
            bool overflow = false;
            int chunks = 0;
            int cancel_after = request.value("cancel_after_chunks", 0);
            auto chunk_events = ujson::json::array();
            model->setWavformCallback([&](const float* data, size_t n, bool last) {
                if (data && n) {
                    if (first_audio < 0) first_audio = elapsed(start);
                    if (samples.size() + n > 24000 * 30) { overflow = true; return false; }
                    samples.insert(samples.end(), data, data+n);
                    ++chunks;
                    auto event = ujson::json::object();
                    event["seconds"] = elapsed(start); event["samples"] = int(n);
                    chunk_events.push_back(event);
                    if (cancel_after && chunks >= cancel_after) return false;
                }
                return true;
            });
            auto text = request.value("text", "");
            if (text.empty() || text.size() > 900) return 2;
            bool ok = model->generateTTS(text, "korean", max_frames, "");
            bool cancelled = int(model->getContext()->status) == 3;
            if ((!ok && !(cancel_after && cancelled)) || overflow || samples.empty()) return 4;
            for (float v : samples) if (!std::isfinite(v)) return 4;
            std::ofstream audio(request.value("pcm_path", ""), std::ios::binary);
            if (!audio) return 4;
            audio.write(reinterpret_cast<const char*>(samples.data()), samples.size()*sizeof(float));
            result["sample_rate"] = 24000;
            result["samples"] = int(samples.size());
            result["first_audio_seconds"] = first_audio;
            result["chunks"] = chunk_events;
            result["expected_cancellation"] = cancel_after > 0;
            if (cancel_after && (!cancelled || chunks != cancel_after)) failed = true;
        } else {
            auto prompt = request.value("prompt", "");
            if (prompt.empty()) return 2;
            std::ostringstream answer;
            ChatMessages messages;
            if (role == "vlm") messages.emplace_back("system", request.value("system", "짧은 한국어로 답하세요. 현재 화면과 과거 관측을 구분하고 관측하지 않은 현재 위치는 추측하지 마세요."));
            messages.emplace_back("user", prompt);
            model->response(messages, &answer, nullptr, 96);
            result["prediction"] = answer.str();
        }
        result["seconds"] = elapsed(start);
        const auto* context = model->getContext();
        result["status"] = int(context->status);
        result["generated_tokens"] = context->gen_seq_len;
        result["hit_generation_cap"] = role == "tts" && context->gen_seq_len >= max_frames;
        if (context->gen_seq_len >= (role == "tts" ? max_frames : 96)) failed = true;
        {
            auto tokens = ujson::json::array();
            for (int token : context->output_tokens) tokens.push_back(token);
            result[role == "tts" ? "codec_tokens" : "output_tokens"] = tokens;
        }
        struct rusage usage{}; getrusage(RUSAGE_SELF, &usage);
        result["peak_rss_kib"] = double(usage.ru_maxrss);
        output << result.dump() << std::endl;
        std::cout << "completed " << count << std::endl;
    }
    return failed ? 4 : 0;
}
