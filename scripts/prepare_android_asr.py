"""Prepare the pinned Korean streaming ASR candidate with its self-contained runtime."""
from prepare_android_vad import ROOT, prepare

if __name__ == '__main__':
    destination = ROOT / '.runtime/android-asr'
    prepare(ROOT / 'configs/android-asr.json', destination)
