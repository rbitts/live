"""Stop only the registered Live supervisor; reject stale/reused process IDs."""
import json
import os
from pathlib import Path
import signal
import time

root=Path(__file__).resolve().parents[1]
registry=root/'.runtime/supervisor.json'
if not registry.exists():
    raise SystemExit('No registered Live server is running.')
record=json.loads(registry.read_text())
proc=Path(f"/proc/{record['pid']}")
def same_process():
    try:
        fields=(proc/'stat').read_text().rsplit(') ',1)[1].split()
        return fields[0]!='Z' and fields[19]==record['start_ticks']
    except FileNotFoundError:
        return False
if not same_process():
    raise SystemExit('Stale process record; no signal sent.')
os.kill(record['pid'],signal.SIGTERM)
for _ in range(60):
    if not same_process():
        print('Live server stopped; owned workers released.')
        break
    time.sleep(.25)
else:
    raise SystemExit('Shutdown is still in progress. Check .runtime logs; no unrelated process was stopped.')
