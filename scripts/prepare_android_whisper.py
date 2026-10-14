"""Prepare pinned on-device Whisper weights outside the APK and git."""
from prepare_android_vad import prepare, ROOT

if __name__ == '__main__':
    prepare(ROOT / 'configs/android-whisper.json', ROOT / '.runtime/android-whisper')
