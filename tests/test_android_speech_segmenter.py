"""Barge-in needs sustained speech; long input cannot repeatedly cancel itself."""
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]

def test_turn_boundaries(tmp_path):
    jdk = ROOT / '.runtime/ojt/toolchains/jdk-17.0.20.1+1/bin'
    check = tmp_path / 'SegmentCheck.java'
    check.write_text('''package dev.live.offline;
public class SegmentCheck {
  static void ok(boolean b) { if (!b) throw new AssertionError(); }
  public static void main(String[] args) {
    SpeechSegmenter g = new SpeechSegmenter();
    // Repeated 100 ms transients cannot interrupt an answer.
    for(int j=0;j<20;j++) { for(int i=0;i<5;i++)ok(g.accept(true)==0); ok(g.accept(false)==0); }
    for(int i=0;i<7;i++)ok(g.accept(true)==0);
    ok(g.accept(true)==1); // 160 ms speech onset.
    for(int i=0;i<20;i++)ok(g.accept(false)==0);
    ok(g.quietFrames()==20 && g.speaking()); // Candidate is not a final turn.
    for(int i=0;i<19;i++)ok(g.accept(false)==0);
    ok(g.accept(true)==0); ok(g.quietFrames()==0); // Resumed speech invalidates candidate.
    for(int i=0;i<39;i++)ok(g.accept(false)==0);
    ok(g.accept(false)==2); ok(!g.speaking());
    for(int i=0;i<7;i++)ok(g.accept(true)==0);ok(g.accept(true)==1);
    for(int i=0;i<741;i++)ok(g.accept(true)==0);
    ok(g.accept(true)==2); // Bound at 15 seconds.
    for(int i=0;i<1000;i++)ok(g.accept(true)==0); // No repeat of one long sound.
    for(int i=0;i<40;i++)ok(g.accept(false)==0);
    for(int i=0;i<7;i++)ok(g.accept(true)==0);ok(g.accept(true)==1);
  }
}''')
    subprocess.run([str(jdk/'javac'),'-d',str(tmp_path),str(check),str(ROOT/'android/app/src/main/java/dev/live/offline/SpeechSegmenter.java')],check=True)
    subprocess.run([str(jdk/'java'),'-cp',str(tmp_path),'dev.live.offline.SegmentCheck'],check=True)
