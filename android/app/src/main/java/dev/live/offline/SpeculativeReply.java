package dev.live.offline;
/** Hide provisional reasoning until the final transcript agrees, preserving word boundaries. */
final class SpeculativeReply {
  static final class Snapshot {
    final long id; final String question,answer; final boolean complete;
    Snapshot(long i,String q,String a,boolean c){id=i;question=q;answer=a;complete=c;}
  }
  private long id=-1; private String question="",answer="";private boolean committed,complete;
  static String key(String text){return text.trim().replaceAll("\\s+", " ").replaceAll("[.!?。！？]+$", "");}
  synchronized void start(long value,String text){id=value;question=text;answer="";committed=false;complete=false;}
  synchronized void clear(){id=-1;question=answer="";committed=complete=false;}
  synchronized boolean hidden(long value){return id==value&&!committed;}
  synchronized boolean matches(String text){return id>=0&&key(question).equals(key(text));}
  synchronized long id(){return id;}
  synchronized boolean offer(long value,String text,boolean done){
    if(value!=id)return true;
    // A commit snapshot must not overwrite a newer native callback.
    if(text.length()<answer.length())return false;
    answer=text;complete|=done;return committed;
  }
  synchronized Snapshot commit(String text){
    if(!matches(text))return null;
    committed=true;question=text;return new Snapshot(id,question,answer,complete);
  }
}
