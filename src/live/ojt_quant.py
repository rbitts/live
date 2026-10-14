"""Reference symmetric W4 group-64 fake quantization for MNN-compatible QAT experiments.

This module alone is not an INT4 runtime. Acceptance requires the exported MNN model.
"""
import types

import torch
from torch.nn import functional as F


def symmetric_grid(weight, group_size=64):
    if weight.ndim != 2 or weight.shape[-1] % group_size:
        raise ValueError('expected a matrix with input channels divisible by group size')
    original_dtype = weight.dtype
    blocks = weight.float().reshape(weight.shape[0], -1, group_size)
    scale = blocks.abs().amax(dim=-1, keepdim=True) / 7
    scale = torch.where(scale == 0, torch.ones_like(scale), scale)
    codes = torch.round(blocks / scale).clamp(-7, 7)
    return (codes * scale).reshape_as(weight).to(original_dtype)


class StraightThroughGrid(torch.autograd.Function):
    @staticmethod
    def forward(ctx, weight):
        return symmetric_grid(weight)

    @staticmethod
    def backward(ctx, gradient):
        return gradient


def qat_forward(module, inputs, *args, **kwargs):
    if args or kwargs:
        raise ValueError('unsupported LoRA forward arguments in reference QAT')
    if module.disable_adapters or module.merged or module.active_adapters != ['default']:
        raise ValueError('reference QAT expects one active unmerged default adapter')
    # Quantize the combined base + live adapter weight, so the gradient sees the
    # deployment grid. Quantizing only a frozen base then adding LoRA is QLoRA.
    weight = module.base_layer.weight.float() + module.get_delta_weight('default').float()
    quantized = StraightThroughGrid.apply(weight).to(inputs.dtype)
    bias = module.base_layer.bias
    return F.linear(inputs, quantized, bias.to(inputs.dtype) if bias is not None else None)


def enable_reference_qat(model, quantize_shared_head=False):
    from peft.tuners.lora.layer import Linear as LoraLinear
    adapted, frozen = [], []
    for name, module in list(model.named_modules()):
        if '.language_model.layers.' not in name:
            continue
        if isinstance(module, LoraLinear):
            module.forward = types.MethodType(qat_forward, module)
            adapted.append(name)
        elif isinstance(module, torch.nn.Linear) and '.base_layer' not in name and '.lora_' not in name:
            with torch.no_grad():
                module.weight.copy_(symmetric_grid(module.weight))
            frozen.append(name)
    if not adapted or not frozen:
        raise ValueError('no decoder layers selected for QAT')
    if quantize_shared_head:
        head = model.get_output_embeddings().weight
        if head.data_ptr() != model.get_input_embeddings().weight.data_ptr():
            raise ValueError('compact QAT expects the pinned tied embedding/head')
        # Bound temporary FP32 tensors for the large shared vocabulary matrix.
        with torch.no_grad():
            for start in range(0, head.shape[0], 256):
                head[start:start+256].copy_(symmetric_grid(head[start:start+256]))
        frozen.append('shared_embeddings_lm_head')
    return {'kind': 'fake-quant training; requires native export validation', 'bits': 4, 'group_size': 64,
            'symmetric_range': [-7, 7], 'scale_dtype': 'float32', 'rounding': 'nearest-even',
            'vision': 'float', 'embeddings_lm_head': 'W4 tied' if quantize_shared_head else 'float',
            'lora_dropout_during_qat': 0,
            'adapted_layers': adapted, 'frozen_quantized_layers': frozen}
