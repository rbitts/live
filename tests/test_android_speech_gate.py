"""Quiet headset speech must survive the capture gate without accepting silence."""
from pathlib import Path
import shutil
import subprocess
import pytest

ROOT = Path(__file__).resolve().parents[1]


def test_headset_speech_gate(tmp_path):
    jdk = ROOT / '.runtime/ojt/toolchains/jdk-17.0.20.1+1/bin'
    javac = str(jdk / 'javac') if (jdk / 'javac').exists() else shutil.which('javac')
    java = str(jdk / 'java') if (jdk / 'java').exists() else shutil.which('java')
    if not javac or not java:
        pytest.skip('JDK required')
    source = tmp_path / 'SpeechGateCheck.java'
    source.write_text('''package dev.live.offline;
public class SpeechGateCheck {
  static void ok(boolean x) { if (!x) throw new AssertionError(); }
  public static void main(String[] args) {
    SpeechGate gate = new SpeechGate();
    for (int i=0; i<500; i++) ok(!gate.voiced(0.001, false));
    // Real headset speech was mostly 0.005–0.020 RMS, below the old 0.025 gate.
    for (int i=0; i<5; i++) ok(gate.voiced(0.009, false));
    for (int i=0; i<150; i++) ok(gate.voiced(0.006, true));
    ok(gate.voiced(0.0025, true)); // Quiet consonant: retain an active sentence.
    ok(!gate.voiced(0.0025, false)); // Same energy does not initiate a new turn.
    for (int i=0; i<40; i++) ok(!gate.voiced(0.0001, true));
    ok(gate.voiced(0.009, false));
    SpeechGate noisy = new SpeechGate();
    for (int i=0; i<300; i++) ok(!noisy.voiced(0.003, false));
    ok(!noisy.voiced(0.004, false));
    ok(noisy.voiced(0.02, false));
  }
}''')
    subprocess.run([javac, '-d', str(tmp_path), str(source), str(ROOT / 'android/app/src/main/java/dev/live/offline/SpeechGate.java')], check=True)
    subprocess.run([java, '-cp', str(tmp_path), 'dev.live.offline.SpeechGateCheck'], check=True)
