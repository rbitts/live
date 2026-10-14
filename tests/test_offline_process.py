from pathlib import Path
import platform
import subprocess
import sys

import pytest


@pytest.mark.skipif(platform.system()!='Linux' or platform.machine()!='x86_64', reason='PC seccomp profile')
def test_offline_child_can_read_local_data_but_cannot_create_network_socket(tmp_path):
    local=tmp_path/'local.txt';local.write_text('offline')
    script=Path(__file__).resolve().parents[1]/'scripts/run_offline.py'
    child='''import errno,socket,sys
assert open(sys.argv[1]).read() == 'offline'
try: socket.socket()
except PermissionError as e: assert e.errno == errno.EPERM
else: raise AssertionError('network not denied')
'''
    subprocess.run([sys.executable,str(script),sys.executable,'-c',child,str(local)],check=True,timeout=10)
