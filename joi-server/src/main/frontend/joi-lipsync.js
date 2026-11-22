// Lip sync from her voice: the pieces of her speech come as audio files, so the mouth is read from the sound, not from
// the text. No TTS server tells where each phoneme is; the sound does. For a piece, analyze() gives a track: one frame
// every HOP seconds with the weights of the five VRM visemes (aa, ih, ou, ee, oh), how far the mouth is open and the
// vowel it makes. MouthPlayer plays the tracks against a clock as the pieces are played by whoever plays the audio
// (the node's player, the app's): it is told when a piece starts and when speech stops, and it never touches the audio.
//
// The reading of a frame: the loudness (RMS) says how far the mouth opens; the first two formants (the peaks of the
// spectral envelope (linear prediction of the pre-emphasized sound; the first two peaks) say which vowel it is, as the distance to the
// centers of the five vowels (F1 high: aa, F2 high: ee / ih, both low: ou / oh). A consonant or a pause (little
// energy, or a spectrum with its weight high up: s, f, t) opens the mouth little and keeps the vowel before it.

/** Seconds between two frames of a track. */
export const HOP = 0.02;
/** The visemes of VRM 1.0, in the order of a frame. */
export const VISEMES = ['aa', 'ih', 'ou', 'ee', 'oh'];

// the centers of the vowels in (F1, F2) Hz, for a voice that is neither a bass nor a child (the distance is on a log scale)
const VOWELS = [
  [800, 1300], // aa  "a" in father
  [330, 2300], // ih  "i" in machine
  [360, 900],  // ou  "u" in rule
  [520, 1950], // ee  "e" in bed
  [520, 1000]  // oh  "o" in more
];

const WINDOW = 0.032;
const FFT_SIZE = 1024;
// the order of the linear prediction: two poles for each formant that fits under the rate's half, and a few over
const ORDER_PER_KHZ = 1;

/**
 * The track of a piece: samples (mono, Float32Array or an array of them to mix) at sampleRate. Returns
 * {hop, duration, frames: Float32Array(count * 5), open: Float32Array(count)}: frame i is at i * hop seconds.
 */
export function analyze(samples, sampleRate) {
  const mono = mix(samples);
  const rate = sampleRate;
  const hop = Math.max(1, Math.round(rate * HOP));
  const window = Math.min(FFT_SIZE, Math.max(64, nextPower(Math.round(rate * WINDOW))));
  const count = Math.max(1, Math.ceil(mono.length / hop));
  const frames = new Float32Array(count * VISEMES.length);
  const open = new Float32Array(count);

  const hann = new Float32Array(window);
  for (let i = 0; i < window; i++) {
    hann[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (window - 1));
  }
  const re = new Float32Array(window);
  const im = new Float32Array(window);

  // the loudness of the whole piece sets what "loud" is: voices are recorded at all levels
  const energies = new Float32Array(count);
  for (let f = 0; f < count; f++) {
    energies[f] = rms(mono, f * hop - (window >> 1), window);
  }
  const loud = percentile(energies, 0.95);
  const floor = Math.max(loud * 0.04, 1e-4);

  // pre-emphasis: the low harmonics of a voice are the strongest, but they say nothing of the vowel
  const emphasized = new Float32Array(mono.length);
  for (let i = 1; i < mono.length; i++) {
    emphasized[i] = mono[i] - 0.97 * mono[i - 1];
  }

  let held = [0, 0, 0, 0, 0];
  for (let f = 0; f < count; f++) {
    const energy = energies[f];
    const level = loud > 1e-4 ? Math.min(1, Math.max(0, (energy - floor) / (loud - floor))) : 0;
    open[f] = Math.pow(level, 0.7);
    if (level <= 0.02) {
      continue;
    }
    const start = f * hop - (window >> 1);
    for (let i = 0; i < window; i++) {
      const at = start + i;
      re[i] = at >= 0 && at < mono.length ? mono[at] * hann[i] : 0;
      im[i] = 0;
    }
    fft(re, im);
    const bins = window >> 1;
    const power = new Float32Array(bins);
    for (let i = 0; i < bins; i++) {
      power[i] = re[i] * re[i] + im[i] * im[i];
    }
    const hz = rate / window;
    // a consonant like s, f or t: the weight is above 3.5 kHz; the mouth is barely open and the vowel stays
    const high = bandSum(power, hz, 3500, Math.min(8000, rate / 2));
    const low = bandSum(power, hz, 80, 3500);
    if (high > low * 0.8) {
      open[f] *= 0.25;
      write(frames, f, held, open[f] > 0.02 ? 0.5 : 0);
      continue;
    }
    const found = formants(emphasized, start, window, hann, rate, re, im);
    if (!found) {
      write(frames, f, held, 0.5);
      continue;
    }
    const [f1, f2] = found;
    held = vowel(f1, f2);
    write(frames, f, held, 1);
  }
  smoothTrack(frames, open, count);
  return {hop: HOP, duration: mono.length / rate, frames, open, count};
}

/** The weights of the five visemes for a vowel at (f1, f2): the nearest ones, summing to 1. */
export function vowel(f1, f2) {
  const weights = VOWELS.map(([a, b]) => {
    const d = Math.hypot(Math.log(f1 / a) * 1.6, Math.log(f2 / b));
    return Math.exp(-d * d * 14);
  });
  const sum = weights.reduce((x, y) => x + y, 0);
  return sum > 1e-9 ? weights.map(w => w / sum) : [0.4, 0, 0, 0, 0.2];
}

function write(frames, f, weights, factor) {
  for (let v = 0; v < VISEMES.length; v++) {
    frames[f * VISEMES.length + v] = weights[v] * factor;
  }
}

/** The track at a time: {aa, ih, ou, ee, oh} as the weights to give the model (the openness already in them). */
export function at(track, time, out = {aa: 0, ih: 0, ou: 0, ee: 0, oh: 0}) {
  const position = time / track.hop;
  const i = Math.floor(position);
  const k = position - i;
  for (let v = 0; v < VISEMES.length; v++) {
    const a = value(track, i, v);
    const b = value(track, i + 1, v);
    out[VISEMES[v]] = a + (b - a) * k;
  }
  return out;
}

function value(track, i, v) {
  if (i < 0 || i >= track.count) {
    return 0;
  }
  return track.frames[i * VISEMES.length + v] * track.open[i];
}

// the track is smoothed in time: a mouth does not jump from one vowel to another every 20 ms
function smoothTrack(frames, open, count) {
  const n = VISEMES.length;
  const copy = Float32Array.from(frames);
  for (let f = 0; f < count; f++) {
    for (let v = 0; v < n; v++) {
      const a = copy[Math.max(0, f - 1) * n + v];
      const b = copy[f * n + v];
      const c = copy[Math.min(count - 1, f + 1) * n + v];
      frames[f * n + v] = (a + 2 * b + c) / 4;
    }
  }
  const o = Float32Array.from(open);
  for (let f = 0; f < count; f++) {
    open[f] = (o[Math.max(0, f - 1)] + 2 * o[f] + o[Math.min(count - 1, f + 1)]) / 4;
  }
}

function mix(samples) {
  if (!Array.isArray(samples)) {
    return samples;
  }
  if (samples.length === 1) {
    return samples[0];
  }
  const out = new Float32Array(samples[0].length);
  for (const channel of samples) {
    for (let i = 0; i < out.length; i++) {
      out[i] += channel[i] / samples.length;
    }
  }
  return out;
}

function rms(samples, start, length) {
  let sum = 0;
  let n = 0;
  for (let i = Math.max(0, start); i < Math.min(samples.length, start + length); i++) {
    sum += samples[i] * samples[i];
    n++;
  }
  return n ? Math.sqrt(sum / n) : 0;
}

function percentile(values, p) {
  const sorted = Float32Array.from(values).sort();
  return sorted[Math.min(sorted.length - 1, Math.floor(sorted.length * p))] || 0;
}

function bandSum(power, hz, from, to) {
  let sum = 0;
  for (let i = Math.max(1, Math.floor(from / hz)); i < Math.min(power.length, Math.ceil(to / hz)); i++) {
    sum += power[i];
  }
  return sum;
}

/**
 * The first two formants (Hz) of the frame at start: linear prediction (autocorrelation, Levinson-Durbin) of the
 * windowed frame, its envelope 1 / |A| on a grid by an FFT of the coefficients, and the first two peaks of that
 * between 200 Hz and 3.5 kHz. Null when the envelope has no two peaks (noise).
 */
function formants(signal, start, window, hann, rate, re, im) {
  const frame = new Float32Array(window);
  for (let i = 0; i < window; i++) {
    const at = start + i;
    frame[i] = at >= 0 && at < signal.length ? signal[at] * hann[i] : 0;
  }
  const order = Math.min(24, Math.max(8, Math.round(2 + rate / 1000 * ORDER_PER_KHZ)));
  const r = new Float64Array(order + 1);
  for (let lag = 0; lag <= order; lag++) {
    let sum = 0;
    for (let i = lag; i < window; i++) {
      sum += frame[i] * frame[i - lag];
    }
    r[lag] = sum;
  }
  if (r[0] < 1e-9) {
    return null;
  }
  r[0] *= 1.0001; // a little white noise keeps it stable
  const a = new Float64Array(order + 1);
  a[0] = 1;
  let error = r[0];
  for (let i = 1; i <= order; i++) {
    let acc = r[i];
    for (let j = 1; j < i; j++) {
      acc += a[j] * r[i - j];
    }
    const k = -acc / error;
    const next = Float64Array.from(a);
    for (let j = 1; j < i; j++) {
      next[j] = a[j] + k * a[i - j];
    }
    next[i] = k;
    a.set(next);
    error *= 1 - k * k;
    if (error <= 0) {
      return null;
    }
  }
  re.fill(0);
  im.fill(0);
  for (let i = 0; i <= order; i++) {
    re[i] = a[i];
  }
  fft(re, im);
  const bins = window >> 1;
  const hz = rate / window;
  const envelope = new Float32Array(bins);
  for (let i = 0; i < bins; i++) {
    envelope[i] = 1 / (re[i] * re[i] + im[i] * im[i] + 1e-12);
  }
  const peaks = [];
  const from = Math.max(2, Math.floor(200 / hz));
  const to = Math.min(bins - 2, Math.ceil(3500 / hz));
  for (let i = from; i <= to; i++) {
    if (envelope[i] > envelope[i - 1] && envelope[i] >= envelope[i + 1]) {
      // the top of the parabola through the three bins
      const left = Math.log(envelope[i - 1]);
      const middle = Math.log(envelope[i]);
      const right = Math.log(envelope[i + 1]);
      const shift = 0.5 * (left - right) / (left - 2 * middle + right || 1);
      peaks.push((i + Math.max(-0.5, Math.min(0.5, shift))) * hz);
    }
  }
  return peaks.length >= 2 ? [peaks[0], peaks[1]] : peaks.length === 1 ? [peaks[0], Math.min(3000, peaks[0] * 2)] : null;
}

function nextPower(n) {
  let p = 1;
  while (p < n) {
    p <<= 1;
  }
  return p;
}

// an in-place radix-2 FFT
function fft(re, im) {
  const n = re.length;
  for (let i = 1, j = 0; i < n; i++) {
    let bit = n >> 1;
    for (; j & bit; bit >>= 1) {
      j ^= bit;
    }
    j ^= bit;
    if (i < j) {
      [re[i], re[j]] = [re[j], re[i]];
      [im[i], im[j]] = [im[j], im[i]];
    }
  }
  for (let size = 2; size <= n; size <<= 1) {
    const angle = -2 * Math.PI / size;
    const wr = Math.cos(angle);
    const wi = Math.sin(angle);
    for (let start = 0; start < n; start += size) {
      let cr = 1;
      let ci = 0;
      for (let k = 0; k < size / 2; k++) {
        const a = start + k;
        const b = a + size / 2;
        const tr = re[b] * cr - im[b] * ci;
        const ti = re[b] * ci + im[b] * cr;
        re[b] = re[a] - tr;
        im[b] = im[a] - ti;
        re[a] += tr;
        im[a] += ti;
        const next = cr * wr - ci * wi;
        ci = cr * wi + ci * wr;
        cr = next;
      }
    }
  }
}

/** Decodes a piece (the bytes of a wav or mp3 file) to samples and analyzes it. */
export async function analyzeFile(bytes) {
  const Context = window.OfflineAudioContext || window.webkitOfflineAudioContext;
  const context = new Context(1, 1, 44100);
  const buffer = await context.decodeAudioData(bytes);
  const channels = [];
  for (let c = 0; c < buffer.numberOfChannels; c++) {
    channels.push(buffer.getChannelData(c));
  }
  return analyze(channels, buffer.sampleRate);
}

/**
 * Plays the tracks against a clock. add(index, track) queues a piece's track (they come before the audio does);
 * start(index) says that the audio of that piece plays now; stop() ends it all. weights() is the mouth right now
 * ({aa, ih, ou, ee, oh}, all 0 when she is not speaking). `delay` is seconds the sound is late after start() (a
 * player that takes a moment to start): the mouth is that much late too.
 */
export class MouthPlayer {
  constructor(delay = 0.06, clock = () => performance.now() / 1000, wall = () => Date.now() / 1000) {
    this.delay = delay;
    this._clock = clock;
    this._wall = wall;
    this._tracks = new Map();
    this._current = null;
    this._began = 0;
    this._out = {aa: 0, ih: 0, ou: 0, ee: 0, oh: 0};
  }

  /** A piece's track; one whose piece started already (the analysis took longer than the sound's start) joins in late. */
  add(index, track) {
    this._tracks.set(index, track);
    if (this._starting === index && !this._current) {
      this._current = track;
    }
  }

  /**
   * The audio of the piece plays now. A piece whose track is not ready yet starts when it is. `at` is when the sound began, as wall-clock
   * milliseconds (the node's own clock, the same machine): a page busy drawing or reading another piece gets to this call late, and the
   * mouth must not be late with it. Without `at`, or when it is not credible (more than 3 s from now: another machine's clock), now counts.
   */
  start(index, at) {
    this._starting = index;
    for (const key of [...this._tracks.keys()]) {
      if (key < index) {
        this._tracks.delete(key);
      }
    }
    let ahead = 0;
    if (typeof at === 'number' && Number.isFinite(at)) {
      const offset = at / 1000 - this._wall();
      if (Math.abs(offset) <= 3) {
        ahead = offset;
      }
    }
    this._began = this._clock() + ahead + this.delay;
    this._current = this._tracks.get(index) || null;
  }

  stop() {
    this._tracks.clear();
    this._current = null;
    this._starting = undefined;
  }

  weights() {
    const out = this._out;
    const track = this._current;
    const time = this._clock() - this._began;
    if (!track || time < 0 || time > track.duration + track.hop) {
      for (const name of VISEMES) {
        out[name] = 0;
      }
      return out;
    }
    return at(track, time, out);
  }
}
