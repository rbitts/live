"""Bound mobile visual work without relabeling old evidence as current."""
from pathlib import Path
import shutil
import subprocess
import pytest

ROOT = Path(__file__).resolve().parents[1]


def test_live_image_budget_and_freshness(tmp_path):
    jdk = ROOT / '.runtime/ojt/toolchains/jdk-17.0.20.1+1/bin'
    javac = str(jdk / 'javac') if (jdk / 'javac').exists() else shutil.which('javac')
    java = str(jdk / 'java') if (jdk / 'java').exists() else shutil.which('java')
    if not javac or not java:
        pytest.skip('JDK required')
    source = tmp_path / 'MemoryCheck.java'
    source.write_text('''package dev.live.offline;
import java.util.*;
public class MemoryCheck {
  static void ok(boolean x){if(!x)throw new AssertionError();}
  public static void main(String[] args){
    ObservationMemory m=new ObservationMemory();
    for(int i=1;i<=4;i++){
      m.observe(new byte[]{(byte)i},i*1000);
      List<ObservationMemory.Evidence> e=m.liveQuestion(i*1000);
      ok(e.size()==1 && e.get(0).current && e.get(0).frame.at==i*1000);
    }
    ok(m.snapshot(4000).size()==2);
    m.cameraOff();
    List<ObservationMemory.Evidence> past=m.liveQuestion(4500);
    ok(past.size()==1 && !past.get(0).current && past.get(0).frame.at==4000);
    ok(m.liveQuestion(305001).isEmpty());
    m.reset();ok(m.liveQuestion(305002).isEmpty());
    m.configure(0,1000);m.observe(new byte[]{1},306000);
    ok(m.liveQuestion(306000).get(0).current);m.cameraOff();
    ok(m.liveQuestion(306001).isEmpty());
    // ASR completes after a scene cut: preserve the last speech-aligned view.
    m.reset();m.configure(2,300000);
    m.observe(new byte[]{1},1000,"sample-preview-copy",100);
    m.observe(new byte[]{2},2000,"sample-preview-copy",1100);
    m.observe(new byte[]{3},3000,"sample-preview-copy",2100);
    List<ObservationMemory.Evidence> frozen=m.alignedQuestion(2500);
    ok(frozen.size()==1 && frozen.get(0).frame.at==2000);
    ok(frozen.get(0).frame.sourceMs==1100);
    m.observe(new byte[]{9},1800); // out of order cannot replace the view
    ok(m.aligned(2500).at==2000);
    for(int i=4;i<30;i++)m.observe(new byte[]{(byte)i},i*1000);
    ok(frozen.get(0).frame.at==2000); // retained only by bounded active turn
    ok(m.aligned(2500)==null); // old raw ring entry was evicted
    ok(m.alignedQuestion(33000).isEmpty()); // stale is not relabeled current
    m.cameraOff();ok(m.alignedQuestion(29001).isEmpty());
    m.observe(new byte[]{1},40000);ok(m.alignedQuestion(39999).isEmpty());
    m.reset();ok(m.alignedQuestion(40000).isEmpty());
    // Byte limit also bounds high-rate/large inputs, independently of count/time.
    for(int i=0;i<5;i++)m.observe(new byte[2*1024*1024],50000+i);
    ok(m.aligned(50002)==null && m.aligned(50004)!=null);
  }
}''')
    subprocess.run([javac, '-d', str(tmp_path), str(source), str(ROOT / 'android/app/src/main/java/dev/live/offline/ObservationMemory.java')], check=True)
    subprocess.run([java, '-cp', str(tmp_path), 'dev.live.offline.MemoryCheck'], check=True)
