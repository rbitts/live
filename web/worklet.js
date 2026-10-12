class Capture extends AudioWorkletProcessor {
  constructor() { super(); this.buffer = []; this.phase = 0; this.sum = 0; this.count = 0; }
  process(inputs) {
    const channel = inputs[0]?.[0];
    if (!channel) return true;
    // Area averaging into 16 kHz, retaining phase across worklet callbacks.
    for (const sample of channel) {
      this.sum += sample; this.count++; this.phase += 16000;
      if (this.phase >= sampleRate) {
        this.phase -= sampleRate;
        this.buffer.push(this.sum / this.count); this.sum = 0; this.count = 0;
        if (this.buffer.length === 320) {
          const pcm = new Float32Array(this.buffer); this.buffer = [];
          this.port.postMessage(pcm, [pcm.buffer]);
        }
      }
    }
    return true;
  }
}
registerProcessor('capture', Capture);
