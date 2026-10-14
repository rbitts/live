package dev.live.offline;

/** Tiny bounded change detector; pixels decide when to ask the model, never what happened. */
final class SceneGate {
  private byte[] latest, checked;
  private long frameAt = -1, checkedAt = -1;
  private final long interval;
  SceneGate(){this(2500);}
  SceneGate(long interval){if(interval<1000)throw new IllegalArgumentException("interval");this.interval=interval;}
  synchronized void frame(byte[] signature, long at) { if(signature==null || signature.length!=192)throw new IllegalArgumentException("192 pixels required"); if(at>frameAt) {latest=signature.clone();frameAt=at;} }
  synchronized boolean due(long now) {
    if(!taskDue(now))return false;
    if(checked==null || now-checkedAt>=10000)return true;
    int change=0;for(int i=0;i<latest.length;i++)change+=Math.abs((latest[i]&255)-(checked[i]&255));
    return change/(float)latest.length>12;
  }
  synchronized boolean taskDue(long now) {
    return latest!=null && now>=frameAt && now-frameAt<=3000 && (checkedAt<0 || now-checkedAt>=interval);
  }
  synchronized void checked(long now){ checked=latest==null?null:latest.clone();checkedAt=now; }
  synchronized void clear(){latest=checked=null;frameAt=checkedAt=-1;}
}
