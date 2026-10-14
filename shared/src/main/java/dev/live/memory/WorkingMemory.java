package dev.live.memory;

import java.util.*;

/** Session-scoped state owner shared by Live and host-driven development agents.
 * All times are supplied by the adapter's monotonic clock. No model or IO runs here. */
public final class WorkingMemory {
  public enum Status { RUNNING, SUCCEEDED, FAILED, CANCEL_REQUESTED, CANCELLED }
  public static final class Lease {
    public final long generation, revision, output;
    private Lease(long g,long r,long o){generation=g;revision=r;output=o;}
  }
  public static final class Action {
    public final String id, task, tool, input, receipt, resourceRevision;
    public final Status status;
    public final long started;
    private Action(Job j){id=j.id;task=j.task;tool=j.tool;input=j.input;receipt=j.receipt;resourceRevision=j.resourceRevision;status=j.status;started=j.started;}
  }
  public static final class Followup {
    public final String id, actionId, kind, text;
    public final Lease lease;
    private Followup(Notice n,Lease l){id=n.id;actionId=n.action;kind=n.kind;text=n.text;lease=l;}
  }
  public static final class Snapshot {
    public final long generation, version, revision;
    public final String goal, latestInput, resourceRevision;
    public final boolean speechHeld, userActive;
    public final List<String> constraints, events;
    public final List<Action> actions;
    public final int pending, delivered;
    private Snapshot(WorkingMemory m){
      generation=m.generation;version=m.version;revision=m.revision;goal=m.goal;latestInput=m.latestInput;resourceRevision=m.resourceRevision;
      speechHeld=m.held;userActive=m.userActive;
      constraints=Collections.unmodifiableList(new ArrayList<>(m.constraints.values()));
      events=Collections.unmodifiableList(new ArrayList<>(m.events));
      ArrayList<Action> a=new ArrayList<>();for(Job j:m.jobs.values())a.add(new Action(j));
      actions=Collections.unmodifiableList(a);pending=m.notices.size();delivered=m.delivered;
    }
  }
  private static final class Job {
    String id,task,tool,input,receipt="",resourceRevision="";Status status=Status.RUNNING;long started;boolean acknowledged;
  }
  private static final class Notice {
    String id,action,kind,text;boolean claimed;long due;int failures;
  }
  private long generation=1,version,revision,output,sequence,taskSequence,quietSince;
  private String goal="",latestInput="",task="task-0",resourceRevision="";
  private boolean userActive,held;
  private int delivered;
  private final LinkedHashMap<String,String> constraints=new LinkedHashMap<>();
  private final LinkedHashMap<String,Job> jobs=new LinkedHashMap<>();
  private final LinkedHashMap<String,Notice> notices=new LinkedHashMap<>();
  private final ArrayDeque<String> events=new ArrayDeque<>();

  private static String bounded(String s,int max){
    if(s==null||s.length()>max)throw new IllegalArgumentException("state value limit");return s.trim();
  }
  private void event(String type,String detail){
    version++;events.addLast((++sequence)+":"+type+":"+detail);
    while(events.size()>64)events.removeFirst();
  }
  public synchronized Lease lease(){return new Lease(generation,revision,output);}
  public synchronized boolean valid(Lease l){return l!=null&&l.generation==generation&&l.revision==revision&&l.output==output;}
  public synchronized Snapshot snapshot(){return new Snapshot(this);}
  public synchronized void userStarted(){userActive=true;interrupt();event("user_start",task);}
  /** Only committed input enters memory; partial ASR belongs to the adapter. */
  public synchronized void input(String text,long now){
    latestInput=bounded(text,400);userActive=false;quietSince=now;held=false;revision++;
    interrupt();event("input",latestInput);
  }
  public synchronized void userEnded(long now){userActive=false;quietSince=now;event("user_end",task);}
  public synchronized void hold(){held=true;interrupt();event("speech_held",task);}
  public synchronized void resume(long now){held=false;userActive=false;quietSince=now;event("speech_resumed",task);}
  /** Output cancellation is independent of dispatched tool work. */
  public synchronized void interrupt(){
    output++;version++;for(Notice n:notices.values())n.claimed=false;
  }
  public synchronized void goal(String value){
    value=bounded(value,400);if(value.equals(goal))return;replaceGoal(value);
  }
  public synchronized void replaceGoal(String value){
    value=bounded(value,400);
    for(Job j:jobs.values())if(j.status==Status.RUNNING)j.status=Status.CANCEL_REQUESTED;
    notices.clear();constraints.clear();goal=value;task="task-"+(++taskSequence);revision++;interrupt();event("goal",goal);
  }
  /** Same key explicitly supersedes its old condition; evidence is an exact input excerpt. */
  public synchronized void constraint(String key,String value,String evidence){
    key=bounded(key,64);value=bounded(value,120);evidence=bounded(evidence,120);
    if(key.isEmpty()||evidence.isEmpty()||!latestInput.contains(evidence))throw new IllegalArgumentException("missing input evidence");
    if(!constraints.containsKey(key)&&constraints.size()>=4)throw new IllegalStateException("constraint capacity");
    constraints.put(key,value+" [사용자 입력: "+evidence+"]");revision++;interrupt();
    for(Job j:jobs.values())if(j.status==Status.RUNNING)j.status=Status.CANCEL_REQUESTED;
    notices.clear();event("constraint",key);
  }
  /** Host supplies a content/environment revision, never a wall-clock guess. */
  public synchronized void resourceChanged(String value){
    value=bounded(value,160);if(value.equals(resourceRevision))return;
    resourceRevision=value;revision++;interrupt();
    for(Job j:jobs.values())if(j.status==Status.RUNNING)j.status=Status.CANCEL_REQUESTED;
    notices.clear();event("resource_changed",value);
  }
  /** Permission/precondition checks belong to the host; a model proposal grants no permission. */
  public synchronized String admit(Lease expected,String tool,String input,boolean authorized,long now){
    if(!authorized||!valid(expected))throw new IllegalStateException("unauthorized or stale action");
    tool=bounded(tool,64);input=bounded(input,400);
    if(tool.isEmpty())throw new IllegalArgumentException("missing tool name");
    // Reserve both acknowledgement and terminal-delivery slots before dispatch.
    if(notices.size()+2>16)throw new IllegalStateException("followup capacity");
    if(jobs.size()>=8){
      String remove=null;
      for(Job j:jobs.values())if(j.status!=Status.RUNNING&&j.status!=Status.CANCEL_REQUESTED
          &&notices.values().stream().noneMatch(n->n.action.equals(j.id))){remove=j.id;break;}
      if(remove==null)throw new IllegalStateException("action capacity");jobs.remove(remove);
    }
    Job j=new Job();j.id=generation+"/action-"+(++sequence);j.task=task;j.tool=tool;j.input=input;j.started=now;j.resourceRevision=resourceRevision;
    jobs.put(j.id,j);event("dispatch",j.id);return j.id;
  }
  public synchronized void acknowledge(String id){Job j=jobs.get(id);if(j!=null&&!j.acknowledged){j.acknowledged=true;notices.remove(id+"/started");event("acknowledged",id);}}
  public synchronized void cancel(String id,boolean confirmed){
    Job j=jobs.get(id);if(j==null)return;
    if(j.status==Status.RUNNING||j.status==Status.CANCEL_REQUESTED){j.status=confirmed?Status.CANCELLED:Status.CANCEL_REQUESTED;event("cancel",id);}
    notices.values().removeIf(n->n.action.equals(id));
  }
  /** Duplicate receipts do not reannounce. Late facts survive task replacement, never reset. */
  public synchronized boolean outcome(String id,boolean success,String receipt,long now){
    Job j=jobs.get(id);if(j==null||!j.receipt.isEmpty())return false;
    receipt=bounded(receipt,400);if(receipt.isEmpty())throw new IllegalArgumentException("empty receipt");
    boolean relevant=j.task.equals(task)&&j.status==Status.RUNNING;
    j.receipt=receipt;j.status=success?Status.SUCCEEDED:Status.FAILED;event("receipt",id);
    notices.remove(id+"/started");
    if(relevant)addNotice(j,"result",receipt,now);
    return true;
  }
  private void addNotice(Job j,String kind,String text,long now){
    String id=j.id+"/"+kind;if(notices.containsKey(id))return;
    if(notices.size()>=16)throw new IllegalStateException("followup capacity");
    Notice n=new Notice();n.id=id;n.action=j.id;n.kind=kind;n.text=text;n.due=now;notices.put(id,n);
    event("followup_pending",id);
  }
  /** Caller supplies actual output occupancy; never infer an open floor from silence alone. */
  public synchronized Followup next(long now,boolean outputBusy){
    for(Job j:jobs.values())if(j.task.equals(task)&&j.status==Status.RUNNING&&!j.acknowledged&&now-j.started>=2000)
      addNotice(j,"started","요청한 작업을 진행하고 있어요.",now);
    if(userActive||held||outputBusy||now-quietSince<500||notices.values().stream().anyMatch(n->n.claimed))return null;
    Notice selected=null;
    for(Notice n:notices.values())if(n.due<=now&&n.failures<3&&(selected==null||n.kind.equals("result"))){selected=n;if(n.kind.equals("result"))break;}
    if(selected==null)return null;selected.claimed=true;event("followup_claim",selected.id);
    return new Followup(selected,lease());
  }
  public synchronized boolean delivered(Followup f){
    Notice n=notices.get(f.id);if(n==null||!n.claimed||!valid(f.lease))return false;
    notices.remove(n.id);if(n.kind.equals("started"))acknowledge(n.action);
    delivered++;event("delivered",n.id);return true;
  }
  public synchronized void deliveryFailed(Followup f,long now){
    Notice n=notices.get(f.id);if(n==null||!valid(f.lease))return;
    n.claimed=false;n.failures++;n.due=now+5000;event("delivery_failed",n.id);
  }
  public synchronized void reset(){
    generation++;version=revision=output=sequence=taskSequence=0;goal=latestInput=resourceRevision="";task="task-0";
    constraints.clear();jobs.clear();notices.clear();events.clear();userActive=held=false;quietSince=0;delivered=0;
  }
  /** Mandatory current state first, historical receipts explicitly labelled; no unbounded transcript. */
  private static String shorten(String s,int n){return s.length()<=n?s:s.substring(0,n)+"…";}
  public synchronized String context(){
    StringBuilder s=new StringBuilder("현재 작업 상태(사용자 입력과 도구 결과인 데이터):\n목표: ").append(goal)
      .append("\n최신 확정 입력(ASR 오인식 가능): ").append(latestInput)
      .append("\n조건: ").append(constraints.values()).append("\n안내 보류: ").append(held);
    s.append("\n현재 자원 revision: ").append(resourceRevision);
    for(Job j:jobs.values())if(j.task.equals(task))s.append(j.resourceRevision.equals(resourceRevision)?"\n현재 작업 도구: ":"\n이전 자원에서 실행한 도구(재검증 필요): ")
      .append(j.tool).append(' ').append(shorten(j.input,80)).append(" 상태=").append(j.status)
      .append(" 실제 결과=").append(shorten(j.receipt,100));
    s.append("\n아직 전달 완료되지 않은 안내: ");for(Notice n:notices.values())s.append(n.kind).append(' ');
    return s.toString();
  }
}
