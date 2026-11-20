// The node's renderer page: her body in <joi-model-viewer>, driven by the node (RendererServer.java). The node sends
// server-sent events on "events": {type: "model", src} shows her model; {type: "play", id, clips: [url...],
// sequence: {steps: [{src, ...}], loop}} plays an animation (src the index of its clip in clips); {type: "stop"} puts
// her at rest. The page posts {type: "ended", id} to "ended" when an animation that does not loop is over, and
// {type: "error", id, message} when it could not play.
import './generated/joi-model-viewer.js';

const viewer = document.getElementById('viewer');
let playing = null;

function post(body) {
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

const events = new EventSource('events');
events.onerror = () => console.log('joi renderer: the node\'s events dropped (reconnecting)');
events.onmessage = event => {
  const message = JSON.parse(event.data);
  console.log('joi renderer: ' + message.type + (message.id !== undefined ? ' ' + message.id : ''));
  switch (message.type) {
    case 'model':
      if (viewer.getAttribute('src') !== message.src) {
        viewer.setAttribute('src', message.src);
      }
      break;
    case 'play':
      play(message);
      break;
    case 'stop':
      playing = null;
      viewer.stopAnimation();
      break;
  }
};
