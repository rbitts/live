#include "live_engine.hpp"
#include <cassert>
#include <iostream>
int main(){
    assert(validUtf8("현재 화면"));assert(validUtf8("\xF0\x9F\x98\x80"));
    assert(!validUtf8("\xED\x98"));assert(!validUtf8("\xED\x98\xEC\xA0\x9C"));assert(!validUtf8("\xC0\x80"));assert(!validUtf8("\xED\xA0\x80"));
    const std::string unicode="현재 컵 😀";
    assert(utf16ToUtf8(utf8ToUtf16(unicode))==unicode);
    assert(utf16ToUtf8(std::u16string(1,0xD800))=="\xEF\xBF\xBD");
    for(size_t n=1;n<unicode.size();++n)
        if(validUtf8(unicode.substr(0,n))) assert(utf16ToUtf8(utf8ToUtf16(unicode.substr(0,n)))==unicode.substr(0,n));
    bool rejected=false;try{utf8ToUtf16("\xF0\x9F");}catch(const std::runtime_error&){rejected=true;}assert(rejected);
    LiveEngine engine;engine.cancel(2);
    assert(engine.answer("현재 화면은 제공되지 않았습니다.","마지막으로 본 컵은 어디 있었나요?",2)==missingEvidenceAnswer());
    assert(engine.answer("현재 화면은 제공되지 않았습니다.","컵은 어디 있나요?",1).empty());
    int emitted=0;
    engine.answer("", "컵은?",1,[&](const std::string&){++emitted;});assert(emitted==0);
    engine.answer("", "컵은?",2,[&](const std::string& s){++emitted;assert(s==missingEvidenceAnswer());});assert(emitted==1);
    std::cout<<"UTF-8, empty-memory abstention, stale epoch PASS\n";
}
