"""Apply the official context-window API for this host's 24GB GPU profile."""
import json
import runpy
import subprocess
import sys
from pathlib import Path

root=Path(__file__).resolve().parents[1]
upstream=root/'.upstream/minicpm-demo'
expected=json.loads((root/'configs/upstreams.json').read_text())['minicpm-demo']['revision']
actual=subprocess.check_output(['git','-C',str(upstream),'rev-parse','HEAD'],text=True).strip()
if actual!=expected:
    raise RuntimeError('Native profile adapter requires the pinned upstream revision')
sys.path.insert(0,str(upstream))
from core.processors import backend_factory
from MiniCPMO45.utils import DuplexWindowConfig

create_backend=backend_factory.create_backend
profile=json.loads((root/'configs/native.json').read_text())

def create_configured_backend(config):
    backend=create_backend(config)
    original_load=backend.load_model
    def load():
        original_load()
        decoder=backend.processor.model.duplex.decoder
        decoder.set_window_config(DuplexWindowConfig(**profile))
        decoder.set_window_enabled(True)
        print('Applied native 24GB context profile:',profile,flush=True)
    backend.load_model=load
    return backend

backend_factory.create_backend=create_configured_backend
runpy.run_module('py_backend.server',run_name='__main__')
