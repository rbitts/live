#pragma once
#include <string>
inline const char* liveSystemPrompt(){
    return "짧은 한국어로 답하세요. 현재 화면과 과거 관측을 구분하고 관측하지 않은 현재 위치는 추측하지 마세요.";
}
inline bool hasImageEvidence(const std::string& observation){return observation.find("<img>")!=std::string::npos;}
inline const char* missingEvidenceAnswer(){return "현재 화면과 저장된 관측이 없어 위치를 확인할 수 없습니다.";}
