package dev.live.offline;
import java.util.*;
/** Main/UI-thread owner: synthesize each completed sentence once, in bounded order. */
final class SentenceVoice {
  interface Output {void speak(long id,String sentence);void done(long id,String error);}
  private final Output output;
  private final ArrayDeque<String> pending=new ArrayDeque<>();
  private long id=-1;private String seen="";private int offset,queuedChars;
  private boolean busy,finished,terminal;
  SentenceVoice(Output o){output=o;}
  void start(long value){cancel();id=value;}
  boolean owns(long value){return id==value;}
  void cancel(){id=-1;seen="";offset=queuedChars=0;pending.clear();busy=finished=terminal=false;}
  void append(long value,String text,boolean last){
    if(value!=id||terminal)return;
    if(!text.startsWith(seen)){fail("음성 문장이 변경되어 재생을 멈췄어요.");return;}
    seen=text;
    for(int i=offset;i<text.length();i++){
      char c=text.charAt(i);
      boolean decimal=c=='.'&&i>0&&Character.isDigit(text.charAt(i-1))
          &&(i+1==text.length()&&!last || i+1<text.length()&&Character.isDigit(text.charAt(i+1)));
      if(!decimal&&(c=='.'||c=='?'||c=='!'||c=='\n'||c=='。'||c=='？'||c=='！')){
        enqueue(text.substring(offset,i+1));offset=i+1;
      }
    }
    if(last){enqueue(text.substring(offset));offset=text.length();finished=true;}
    dispatch();
  }
  private void enqueue(String text){text=text.trim();if(text.isEmpty()||terminal)return;
    if(pending.size()>=8||queuedChars+text.length()>1200||text.length()>400){fail("음성 답변 길이 제한을 넘었어요.");return;}
    pending.add(text);queuedChars+=text.length();
  }
  void sentenceDone(long value,String error){if(value!=id||terminal)return;
    busy=false;if(error!=null&&!error.isEmpty()){fail(error);return;}dispatch();
  }
  private void dispatch(){if(terminal||busy)return;
    if(!pending.isEmpty()){String text=pending.remove();queuedChars-=text.length();busy=true;output.speak(id,text);}
    else if(finished){terminal=true;output.done(id,"");}
  }
  private void fail(String error){pending.clear();queuedChars=0;terminal=true;output.done(id,error);}
}
