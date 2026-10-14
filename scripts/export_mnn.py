"""Pinned MNN exporter entrypoint with local ASR/TTS registration and CustomVoice support."""
import json
import os
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
exporter = ROOT / '.upstream/mnn/transformers/llm/export'
sys.path.insert(0, str(exporter))
role = sys.argv.pop(1)
speech_decoder_bits = 16
if '--tts-decoder-bit' in sys.argv:
    index = sys.argv.index('--tts-decoder-bit')
    speech_decoder_bits = int(sys.argv[index+1])
    del sys.argv[index:index+2]
    if role != 'tts' or speech_decoder_bits not in (8,16):
        raise ValueError('TTS speech decoder supports only 8 or 16 bits')
original_arguments = list(sys.argv[1:])
destination = Path(sys.argv[sys.argv.index('--dst_path') + 1])
if role == 'asr':
    import qwen_asr  # Registers the pinned official Transformers model classes.
elif role == 'tts':
    import qwen_tts
    import utils.token2wav as token2wav

    def load_custom_voice(self):
        from qwen_tts.core.tokenizer_12hz.configuration_qwen3_tts_tokenizer_v2 import Qwen3TTSTokenizerV2Config
        from qwen_tts.core.tokenizer_12hz.modeling_qwen3_tts_tokenizer_v2 import Qwen3TTSTokenizerV2CausalConvNet, Qwen3TTSTokenizerV2Decoder
        config = json.loads((Path(self.args.path) / 'config.json').read_text())
        if config.get('tts_model_type') != 'custom_voice' or config['talker_config']['spk_id']['sohee'] != 2864:
            raise ValueError('this adapter is restricted to pinned CustomVoice/Sohee')
        tokenizer_config = Qwen3TTSTokenizerV2Config.from_pretrained(os.path.join(self.args.path, 'speech_tokenizer'))
        self.decode_upsample_rate = tokenizer_config.decode_upsample_rate
        decoder = Qwen3TTSTokenizerV2Decoder._from_config(tokenizer_config.decoder_config)
        decoder.load_state_dict(token2wav._load_prefixed_state_dict(
            os.path.join(self.args.path, 'speech_tokenizer', 'model.safetensors'), 'decoder.'), strict=True)
        token2wav._patch_qwen3_tts_dynamic_causal_conv(decoder, Qwen3TTSTokenizerV2CausalConvNet)
        self.speech_decoder = token2wav.Qwen3TTSSpeechDecoderWrapper(decoder).eval()
        self.speaker_encoder = None
        self.quant_bit = speech_decoder_bits

    token2wav.Qwen3TTSToken2Wav.load = load_custom_voice
    token2wav.Qwen3TTSToken2Wav.export = lambda self, path: [self.export_speech_decoder(path)]
elif role != 'vlm':
    raise ValueError('role must be asr, vlm or tts')
elif json.loads((Path(sys.argv[sys.argv.index('--path')+1])/'config.json').read_text()).get('model_type') == 'lfm2_vl':
    # The pinned exporter expects the older nested SigLIP2 wrapper and a callable
    # projector norm. Transformers 5.19 flattened the wrapper; LFM2.5 disables
    # that norm. Preserve parameters/math with a view and Identity, respectively.
    import torch
    import transformers
    from transformers.models.siglip2.modeling_siglip2 import Siglip2VisionModel
    from utils.vision import Lfm2VlVision
    if transformers.__version__ == '5.19.0':
        Siglip2VisionModel.vision_model = property(lambda self: self)
    original_lfm_init = Lfm2VlVision.__init__
    def load_lfm_vision(self, visual, base):
        if base.multi_modal_projector.layer_norm is None:
            base.multi_modal_projector.layer_norm = torch.nn.Identity()
        original_lfm_init(self, visual, base)
    Lfm2VlVision.__init__ = load_lfm_vision
source = exporter / 'llmexport.py'
# The upstream converter replaces sys.argv, which breaks runpy's argv[0] restore.
exec(compile(source.read_bytes(), str(source), 'exec'), {'__name__': '__main__', '__file__': str(source)})
if role == 'tts':
    config_path = destination / 'config.json'
    config = json.loads(config_path.read_text())
    config['custom_voice_speaker_id'] = 2864
    config.update(tts_chunk_frames=8, sampler_type='mixed',
                  mixed_samplers=['penalty','temperature','topK','topP'],
                  temperature=.9, top_k=50, top_p=1.0, repetition_penalty=1.05)
    config_path.write_text(json.dumps(config, indent=2) + '\n')
elif role == 'asr':
    runtime_config = destination / 'config.json'
    runtime = json.loads(runtime_config.read_text())
    runtime['enforce_utf8'] = True
    runtime_config.write_text(json.dumps(runtime, indent=2) + '\n')
    config_path = destination / 'llm_config.json'
    config = json.loads(config_path.read_text())
    config['asr_language'] = 'Korean'
    config['jinja'].setdefault('context', {})['asr_language'] = 'Korean'
    # Match the pinned official processor, including role-separating newlines.
    # The upstream export template omitted them and produced malformed Hangul
    # even in the FP16 control (not a quantization-only error).
    config['jinja']['chat_template'] = "{%- set content = messages[-1].content -%}<|im_start|>system\n<|im_end|>\n<|im_start|>user\n{{ content }}<|im_end|>\n{% if add_generation_prompt %}<|im_start|>assistant\nlanguage {{ asr_language }}<asr_text>{% endif %}"
    config_path.write_text(json.dumps(config, indent=2) + '\n')
else:
    config_path = destination / 'llm_config.json'
    config = json.loads(config_path.read_text())
    config['jinja'].setdefault('context', {})['enable_thinking'] = False
    config['image_min_pixels'] = 65536
    config['image_max_pixels'] = 262144
    config_path.write_text(json.dumps(config, ensure_ascii=False, indent=2) + '\n')
(destination / 'local-export-provenance.json').write_text(json.dumps({
    'role': role, 'upstream_arguments': original_arguments,
    'tts_speech_decoder_bits': speech_decoder_bits if role == 'tts' else None,
    'runtime_revision': json.loads((ROOT/'configs/upstreams.json').read_text())['mnn']['revision'],
    'requires_local_runtime_patch': True,
}, indent=2) + '\n')
