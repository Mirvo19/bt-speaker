class PcmPacker extends AudioWorkletProcessor {
  constructor() {
    super();
    this.packet = new ArrayBuffer(4096);
    this.view = new DataView(this.packet);
    this.offset = 0;
  }

  process(inputs, outputs) {
    const input = inputs[0];
    const output = outputs[0];
    for (const channel of output) channel.fill(0);
    if (!input || input.length === 0) return true;

    const left = input[0];
    const right = input[1] || left;
    for (let frame = 0; frame < left.length; frame += 1) {
      this.view.setInt16(this.offset, this.toPcm(left[frame]), true);
      this.view.setInt16(this.offset + 2, this.toPcm(right[frame]), true);
      this.offset += 4;
      if (this.offset === this.packet.byteLength) {
        this.port.postMessage(this.packet, [this.packet]);
        this.packet = new ArrayBuffer(4096);
        this.view = new DataView(this.packet);
        this.offset = 0;
      }
    }
    return true;
  }

  toPcm(sample) {
    const clipped = Math.max(-1, Math.min(1, sample));
    return clipped < 0 ? clipped * 32768 : clipped * 32767;
  }
}

registerProcessor("pcm-packer", PcmPacker);