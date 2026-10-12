# Live open-weight PoC

- Reply in the user's language. Complete authorized implementation and verification.
- Read README.md, docs/architecture.md and docs/verification.md before changing behavior.
- Two distinct tracks: MiniCPM-o 4.5 native duplex; Qwen ASR → Qwen3.5-4B → Qwen TTS system duplex. Never call the Qwen pipeline native duplex.
- Use the external-dev profile. CPU tests use .venv; each model worker has its own .venv-* because Transformers pins conflict. Never mutate another project's environments.
- Host: verify with scripts/doctor.py; two RTX 3090 24GB GPUs are expected but availability must be measured before every GPU run. Do not terminate unrelated GPU processes.
- Default allocation: native GPU 1; Qwen ASR GPU 0, VLM/TTS GPU 1. Run the two tracks sequentially on this host. No paid/cloud resources without authorization.
- Model and upstream revisions live in configs/models.json and configs/upstreams.json. Do not silently move revisions. Weights/cache/audio/video/secrets must not enter git.
- Native upstream is reused without local source edits. scripts/native_backend.py applies the official context-window API from configs/native.json; never disable it on this 24GB host without a bounded capacity test. Dependency overrides require an explanation and a real inference smoke.
- Preserve continuous microphone capture during playback. Barge-in clears client playback immediately, invalidates the response epoch, cancels model generation cooperatively, and drops late results. Network input must not wait on inference.
- Bound all media queues and buffers. Timestamp camera frames; do not use a stale frame as current evidence. Keep observed history separate from current view. Personalization is explicit and session scoped by default.
- Server binds loopback; use SSH tunnels or authenticated TLS reverse proxy for remote access. Browser capture needs localhost or HTTPS. Do not expose unauthenticated model ports.
- Run .venv/bin/python -m pytest for protocol/state changes; run model-specific GPU and duplex smokes for model changes. A mock pass is never model acceptance. Report hardware, revisions, latency, failures, and human microphone/camera checks still needed.
- Never report full-duplex secured without the acceptance evidence in docs/verification.md. Separate software correctness, model smoke, paced duplex performance, and human Live acceptance.
- Search the canonical OBSIDIAN_VAULT_PATH when prior context matters and save durable session outcomes once. No credentials in notes.
