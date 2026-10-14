#pragma once
#include <string>
#include <stdexcept>
inline bool validUtf8(const std::string& text){
    for(size_t i=0;i<text.size();){
        unsigned char a=text[i++];if(a<0x80)continue;
        int count=a>=0xC2 && a<=0xDF?1:a>=0xE0 && a<=0xEF?2:a>=0xF0 && a<=0xF4?3:-1;
        if(count<0 || i+count>text.size())return false;
        unsigned char b=text[i];
        if((a==0xE0 && b<0xA0)||(a==0xED && b>=0xA0)||(a==0xF0 && b<0x90)||(a==0xF4 && b>=0x90))return false;
        while(count--){unsigned char value=text[i++];if(value<0x80 || value>0xBF)return false;}
    }
    return true;
}

inline std::u16string utf8ToUtf16(const std::string& text) {
    if (!validUtf8(text)) throw std::runtime_error("invalid UTF-8 response");
    std::u16string result;
    for (size_t i=0;i<text.size();) {
        unsigned int cp=static_cast<unsigned char>(text[i++]);
        int extra=cp<0x80?0:cp<0xE0?1:cp<0xF0?2:3;
        if(extra) cp &= (1u << (6-extra))-1;
        while(extra--) cp=(cp<<6)|(static_cast<unsigned char>(text[i++])&0x3F);
        if(cp>0xFFFF) {cp-=0x10000;result.push_back(0xD800+(cp>>10));result.push_back(0xDC00+(cp&0x3FF));}
        else result.push_back(static_cast<char16_t>(cp));
    }
    return result;
}

inline std::string utf16ToUtf8(const std::u16string& text) {
    std::string result;
    for (size_t i=0;i<text.size();++i) {
        unsigned int cp=text[i];
        if(cp>=0xD800 && cp<=0xDBFF && i+1<text.size() && text[i+1]>=0xDC00 && text[i+1]<=0xDFFF) {
            cp=0x10000+((cp-0xD800)<<10)+(text[++i]-0xDC00);
        } else if(cp>=0xD800 && cp<=0xDFFF) cp=0xFFFD;
        if(cp<0x80) result.push_back(cp);
        else if(cp<0x800) {result.push_back(0xC0|(cp>>6));result.push_back(0x80|(cp&63));}
        else if(cp<0x10000) {result.push_back(0xE0|(cp>>12));result.push_back(0x80|((cp>>6)&63));result.push_back(0x80|(cp&63));}
        else {result.push_back(0xF0|(cp>>18));result.push_back(0x80|((cp>>12)&63));result.push_back(0x80|((cp>>6)&63));result.push_back(0x80|(cp&63));}
    }
    return result;
}
