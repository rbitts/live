"""Compare the QAT grid to the pinned MNN packed INT4 exporter on CPU."""
import json
from pathlib import Path
import sys

import torch

ROOT = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(ROOT/'src'), str(ROOT/'.upstream/mnn/transformers/llm/export')]
from live.ojt_quant import StraightThroughGrid, symmetric_grid
from utils.torch_utils import _quant_on_device

torch.manual_seed(42)
torch.set_num_threads(4)
cases = []
for dtype in (torch.float32, torch.bfloat16):
    for shape in ((12,128), (256,256)):
        weight = torch.randn(shape, dtype=dtype)
        packed, alpha = _quant_on_device(weight.float(),4,64,True,False,False)
        codes = torch.stack((packed >> 4, packed & 15), dim=-1).reshape(shape).float()-8
        decoded = (codes.reshape(shape[0],-1,64)*alpha.reshape(shape[0],-1,1)).reshape(shape).to(dtype)
        assert torch.equal(decoded,symmetric_grid(weight)), 'deployment grid differs'
        cases.append({'dtype':str(dtype),'shape':shape,'exact_match':True})
zero = torch.zeros((2,64),requires_grad=True)
StraightThroughGrid.apply(zero).sum().backward()
assert torch.isfinite(symmetric_grid(zero)).all() and torch.equal(zero.grad,torch.ones_like(zero))
print(json.dumps({'cases':cases,'zero_finite':True,'straight_through_gradient':True,'scope':'numeric grid, not runtime model acceptance'},indent=2))
