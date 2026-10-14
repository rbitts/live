"""Synthetic microphone input must be bounded, isolated and cancellable."""
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def test_mock_microphone(tmp_path):
    jdk = ROOT / '.runtime/ojt/toolchains/jdk-17.0.20.1+1/bin'
    source = tmp_path / 'MockInputCheck.java'
    source.write_text('''package dev.live.offline;
import java.util.Arrays;
public class MockInputCheck {
  static void ok(boolean v){if(!v)throw new AssertionError();}
  static void rejected(Runnable action){try{action.run();throw new AssertionError("accepted");}catch(IllegalArgumentException|IllegalStateException expected){}}
  public static void main(String[] args) {
    MockAudioInput input=new MockAudioInput(); short[] frame=new short[320];
    Arrays.fill(frame,(short)123);ok(!input.read(frame));ok(frame[0]==123);
    rejected(()->input.offer(new short[10]));input.enable();
    ok(input.read(frame));for(short s:frame)ok(s==0); // No ambient mic mix while waiting.
    float[] tone=new float[24000];Arrays.fill(tone,.25f);
    short[] pcm=MockAudioInput.from24k(tone,tone.length);ok(pcm.length==16000);ok(pcm[0]==8192);
    input.offer(pcm);rejected(()->input.offer(pcm));
    for(int i=0;i<20;i++){ok(input.read(frame));ok(frame[0]==0);}
    for(int i=0;i<50;i++){ok(input.read(frame));for(short s:frame)ok(s==8192);}
    for(int i=0;i<60;i++){ok(input.read(frame));ok(frame[0]==0);}
    ok(!input.feeding());ok(input.enabled());ok(input.deliveredSamples()==41600);
    Arrays.fill(frame,(short)456);ok(input.read(frame));ok(frame[0]==0);
    input.offer(pcm);input.clear();ok(!input.feeding());ok(input.read(frame));ok(frame[0]==0);
    input.offer(pcm);input.disable();ok(!input.read(frame));ok(!input.feeding());
    input.enable();input.offer(new short[]{32767});
    for(int i=0;i<81;i++)input.read(frame);ok(!input.feeding()); // Partial final frame zero padded.
    rejected(()->input.offer(new short[16000*12+1]));
    rejected(()->MockAudioInput.from24k(new float[]{Float.NaN,0,0},3));
    rejected(()->MockAudioInput.from24k(new float[288001],288001));
    short[] clipped=MockAudioInput.from24k(new float[]{2,2,-2},3);ok(clipped[0]==32767);
  }
}''')
    subprocess.run([str(jdk/'javac'), '-d', str(tmp_path), str(source), str(ROOT/'android/app/src/main/java/dev/live/offline/MockAudioInput.java')], check=True)
    subprocess.run([str(jdk/'java'), '-cp', str(tmp_path), 'dev.live.offline.MockInputCheck'], check=True, timeout=15)
