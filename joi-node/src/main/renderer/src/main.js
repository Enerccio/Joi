// The node's renderer page: her body in <joi-model-viewer>, driven by the node (RendererServer.java). The node sends
// server-sent events on "events": {type: "model", src} shows her model; {type: "play", id, clips: [url...],
// sequence: {steps: [{src, ...}], loop}} plays an animation (src the index of its clip in clips); {type: "stop"} puts
// her at rest. The page posts {type: "ended", id} to "ended" when an animation that does not loop is over, and
// {type: "error", id, message} when it could not play.
// {type: "offline", offline: true|false}: the server is gone (or back): she is frozen and shown in gray (body.offline).
// {type: "toggles", toggles: [{mesh, key, hides, shown}]} shows and hides the parts of her model (set again once it loads).
// In the iOS app (WKWebView) there is no node server: the app calls window.joiReceive(message) with the same messages and takes
// what the page says through webkit.messageHandlers.joi ({type: "ready"} when it is loaded, {type: "loaded"} when her model is there, then "ended" and "error" as below).
// {type: "speech", index, data} (a piece of her voice, a wav or mp3 file in Base64: read for her mouth), {type: "speech-start", index}
// (that piece plays now, by the node's own player) and {type: "speech-stop"} (her speech ended or was stopped): her mouth follows her voice (joi-lipsync.js).
// {type: "eyes", eyes: "auto"|"closed", ease} (her state's eyes, reached over `ease` seconds) and {type: "mood", mood} (the key of her
// mood, or none): her face layer (the viewer's setFace); an animation's own face comes in its sequence ({face: {eyes, expression}}).
// With ?looking-glass the page renders her for the Looking Glass whose calibration the node serves (looking-glass.js);
// {type: "tuning", tuning} changes how (the node's tuning panel).
import './generated/joi-model-viewer.js';
import {lookingGlassRenderer} from './looking-glass.js';
import {analyzeFile, MouthPlayer} from './generated/joi-lipsync.js';

const viewer = document.getElementById('viewer');
let playing = null;
let tuning = null;
let toggles = [];
const mouth = new MouthPlayer();
// her face layer is on from the start: she blinks and looks at you (the dialogs of the server leave it off)
viewer.setFace({});
let mouthOpen = false;

if (new URLSearchParams(location.search).has('looking-glass')) {
  document.body.classList.add('looking-glass');
  fetch('looking-glass.json')
    .then(response => response.ok ? response.json() : Promise.reject(new Error('HTTP ' + response.status)))
    .then(config => {
      viewer.renderFrame = lookingGlassRenderer(tuning ? {...config, tuning} : config);
      console.log('joi renderer: on the Looking Glass ' + (config.calibration.serial || ''));
    })
    .catch(error => console.log('joi renderer: no Looking Glass calibration: ' + error.message));
}

const native = window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.joi;

function post(body) {
  if (native) {
    native.postMessage(body);
    return;
  }
  fetch('ended', {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(body)})
    .catch(() => {
      // the node is gone: nothing to tell
    });
}

function play(message) {
  playing = message.id;
  for (const name of viewer.getAttributeNames()) {
    if (name.startsWith('animation-src-')) {
      viewer.removeAttribute(name);
    }
  }
  message.clips.forEach((url, index) => viewer.setAttribute('animation-src-' + index, url));
  viewer.playSequence(message.sequence);
}

// the parts shown and hidden are of the model: set again when one has loaded
viewer.addEventListener('model-info', () => {
  viewer.setToggles(JSON.stringify(toggles));
  if (native) {
    // the app shows a note over the page until her model is drawn
    native.postMessage({type: 'loaded'});
  }
});
viewer.addEventListener('animation-finished', () => {
  if (playing !== null) {
    post({type: 'ended', id: playing});
  }
});
viewer.addEventListener('animation-error', event => {
  if (playing !== null) {
    post({type: 'error', id: playing, message: event.detail.message});
  }
});

/** Her mouth follows the voice: every frame while a piece plays, and once more to close it when it is over. */
function mouthLoop() {
  const weights = mouth.weights();
  const open = weights.aa + weights.ih + weights.ou + weights.ee + weights.oh > 0.001;
  if (open || mouthOpen) {
    viewer.setMouth(open ? weights : null);
    mouthOpen = open;
  }
  requestAnimationFrame(mouthLoop);
}
requestAnimationFrame(mouthLoop);

function speech(message) {
  const text = atob(message.data);
  const bytes = new Uint8Array(text.length);
  for (let i = 0; i < text.length; i++) {
    bytes[i] = text.charCodeAt(i);
  }
  analyzeFile(bytes.buffer)
    .then(track => mouth.add(message.index, track))
    .catch(error => console.log('joi renderer: no mouth for piece ' + message.index + ': ' + (error && error.message || error)));
}

function receive(message) {
  if (message.type !== 'speech') {
    console.log('joi renderer: ' + message.type + (message.id !== undefined ? ' ' + message.id : ''));
  }
  switch (message.type) {
    case 'model':
      if (viewer.getAttribute('src') !== message.src) {
        viewer.setAttribute('src', message.src);
      }
      break;
    case 'play':
      play(message);
      break;
    case 'toggles':
      toggles = message.toggles || [];
      viewer.setToggles(JSON.stringify(toggles));
      break;
    case 'offline':
      document.body.classList.toggle('offline', !!message.offline);
      viewer.setFrozen(!!message.offline);
      break;
    case 'tuning':
      tuning = message.tuning;
      if (viewer.renderFrame && viewer.renderFrame.tune) {
        viewer.renderFrame.tune(tuning);
      }
      break;
    case 'eyes':
      viewer.setFace({eyes: message.eyes, ease: message.ease});
      break;
    case 'mood':
      viewer.setFace({mood: message.mood || null});
      break;
    case 'speech':
      speech(message);
      break;
    case 'speech-start':
      mouth.start(message.index);
      break;
    case 'speech-stop':
      mouth.stop();
      break;
    case 'stop':
      playing = null;
      viewer.stopAnimation();
      break;
  }
}

if (native) {
  // what the page logs and the errors it has go to the app's log (no inspector on a phone)
  for (const level of ['log', 'warn', 'error']) {
    const original = console[level].bind(console);
    console[level] = (...args) => {
      original(...args);
      native.postMessage({type: 'log', message: level + ': ' + args.map(String).join(' ')});
    };
  }
  window.addEventListener('error', event => native.postMessage({type: 'log', message: 'error: ' + event.message}));
  window.addEventListener('unhandledrejection', event => native.postMessage({type: 'log', message: 'rejected: ' + event.reason}));
  window.joiReceive = receive;
  post({type: 'ready'});
} else {
  const events = new EventSource('events');
  events.onerror = () => console.log('joi renderer: the node\'s events dropped (reconnecting)');
  events.onmessage = event => receive(JSON.parse(event.data));
}
