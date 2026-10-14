package dev.live.memory;

/** Embeddable development-agent adapter. Host owns authorization, IO, cancellation
 * and an idle/timer callback calling next(). This does not hook the Codex product. */
public final class AgentMemorySession {
  public final WorkingMemory memory = new WorkingMemory();
  public void message(String text,long now){memory.input(text,now);}
  public String beforeTool(WorkingMemory.Lease lease,String name,String input,boolean authorized,long now){
    return memory.admit(lease,name,input,authorized,now);
  }
  public boolean toolFinished(String id,boolean success,String receipt,long now){return memory.outcome(id,success,receipt,now);}
  public WorkingMemory.Followup next(long now,boolean publishing){return memory.next(now,publishing);}
  public boolean published(WorkingMemory.Followup f){return memory.delivered(f);}
}
