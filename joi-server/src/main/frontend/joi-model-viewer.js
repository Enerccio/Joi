// <joi-model-viewer>: the 3D preview of a model (ModelViewer.java). It shows a VRM, a glTF / GLB or an FBX, alone or
// from a zip with its textures, lit and framed, to orbit with the mouse. Attributes (set by the server):
//   src    the URL of the file (the model, or the zip)
//   format VRM, GLB, GLTF or FBX: the format of the model (of the main file in a zip)
//   main   the path of the model file in the zip; absent when src is the model itself
// Methods (the dialog tries what the model can do): setExpression(name, weight), setMeshVisible(name, visible),
// setMorph(mesh, key, weight), setToggles(json), setSway(on) (turns her from side to side, to see the spring bones move);
// they act on the model shown, nothing happens before it is.
// takeSnapshots(request) renders the snapshots of a model (a close-up of each part described apart in each of its
// states from the default state, then the whole model bare: the layers of her look off) for the "snapshot-target" URL.
// playSequence(json) plays an animation, steps of VRMA clips at the "animation-src-N" URLs, on the model (now, or once it
// is shown: a new model plays it again): each step loops or plays once, at its speed, in place or not (the hips keep
// their spot on the floor, they only go up and down), forwards or backwards, turned by a quaternion around the hips
// where it starts, and blended in from the step before over some seconds; the animation can start again after its
// last step. setRotationTool(index) shows rings to turn a step (-1 hides them); "step-rotated" ({index, x, y, z, w})
// fires when the mouse lets go of them.
// setMouth({aa, ih, ou, ee, oh}) sets the weights of the five visemes (her mouth while she speaks, from her voice: joi-lipsync.js);
// they are applied each frame after the animation, so a clip does not take them over; null gives the mouth back.
// setFace({eyes, ease, mood}) turns on her face layer (off until it is called: the dialogs that try expressions need none) and says
// how it is: eyes "auto" (open, blinking now and then, looking at the camera), "closed", "half" or "wide" (open, never blinking), reached
// over `ease` seconds; mood {expression, weight, eyes} (her mood's face, from the server: a soft expression and, for some moods, the eyes).
// An animation can say better: the sequence of playSequence may have face: {eyes, expression} (expression: happy, sad, angry, relaxed
// or surprised), which wins over her state and her mood while that animation plays (setFace(null) turns the layer off). An animation
// with a face turns the layer on when nobody did; then the next animation without one turns it off again (the dialogs of the server).
// finishAnimation() lets a looping step end its round and the rest play once; stopAnimation() puts her back at rest;
// setAnimationPaused(paused) pauses; setFrozen(frozen) freezes her altogether (the animation, the sway, the spring bones:
// the node's page does it while the server is gone). It fires "animation-started" ({duration} of one round), "animation-step"
// ({index, repeats}) as a step starts or repeats, "animation-finished" (the animation ended) and "animation-error"
// ({message}).
// renderFrame, when set, is called each frame instead of rendering the scene to the canvas: renderFrame(renderer,
// scene, camera, target), target being the point the camera looks at (the node's Looking Glass page renders her views
// with it).
// It fires "model-info" ({triangles, meshes, materials, vrm, expressions}) once the model is shown and "model-error"
// ({message}) when it can't be.
import * as THREE from 'three';
import {GLTFLoader} from 'three/addons/loaders/GLTFLoader.js';
import {FBXLoader} from 'three/addons/loaders/FBXLoader.js';
import {TGALoader} from 'three/addons/loaders/TGALoader.js';
import {OrbitControls} from 'three/addons/controls/OrbitControls.js';
import {TransformControls} from 'three/addons/controls/TransformControls.js';
import {VRMExpression, VRMLoaderPlugin, VRMUtils} from '@pixiv/three-vrm';
import {createVRMAnimationClip, VRMAnimationLoaderPlugin} from '@pixiv/three-vrm-animation';

// the prefix of the paths of files inside a zip, resolved by the loading manager's URL modifier
const ZIP_BASE = 'joi-zip:/';

// the snapshots of a body (DESIGN.md → Bodies, self-image): angle in degrees around the model (0 = its front)
const SHOTS = [
  {name: 'full-front', angle: 0},
  {name: 'full-three-quarter', angle: 40},
  {name: 'full-profile', angle: 90},
  {name: 'full-back', angle: 180},
  {name: 'face-front', angle: 0, face: true},
  {name: 'face-three-quarter', angle: 35, face: true}
];

const EMOTIONS = ['happy', 'angry', 'sad', 'relaxed', 'surprised'];
// how closed the lids are for each state of the eyes
const LIDS = {closed: 1, half: 0.55};
// the shape keys of the eyes and lids, left out of her mood's expression (the brows stay)
const EYE_SHAPE = /eye|blink|wink|まばたき|目|瞳/i;
const BROW_SHAPE = /brow|眉/i;
const BLINK_SECONDS = 0.2;

class JoiModelViewer extends HTMLElement {

  static get observedAttributes() {
    return ['src'];
  }

  constructor() {
    super();
    const root = this.attachShadow({mode: 'open'});
    root.innerHTML = `
      <style>
        :host { display: block; position: relative; background: var(--lumo-contrast-5pct); }
        canvas { display: block; width: 100%; height: 100%; outline: none; }
        .status { position: absolute; inset: 0; display: flex; align-items: center; justify-content: center;
                  padding: 1em; text-align: center; color: var(--lumo-secondary-text-color);
                  font: var(--lumo-font-size-m) var(--lumo-font-family); pointer-events: none; }
        .status.error { color: var(--lumo-error-text-color); }
      </style>
      <div class="status"></div>`;
    this._status = root.querySelector('.status');
    this._loadId = 0;
  }

  connectedCallback() {
    if (!this._renderer) {
      this._init();
    }
    if (this.getAttribute('src') && !this._model && !this._loading) {
      this._load();
    }
  }

  disconnectedCallback() {
    this._dispose();
  }

  attributeChangedCallback(name, oldValue, newValue) {
    if (name === 'src' && oldValue !== newValue && this.isConnected) {
      if (!this._renderer) {
        this._init();
      }
      this._load();
    }
  }

  _init() {
    const renderer = new THREE.WebGLRenderer({antialias: true, alpha: true});
    renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
    renderer.outputColorSpace = THREE.SRGBColorSpace;
    this.shadowRoot.insertBefore(renderer.domElement, this._status);
    this._renderer = renderer;

    this._scene = new THREE.Scene();
    this._camera = new THREE.PerspectiveCamera(30, 1, 0.01, 1000);
    this._camera.position.set(0, 1.4, 3);
    this._scene.add(new THREE.HemisphereLight(0xffffff, 0x555555, 1.6));
    // the key light follows the camera (see _aimLight), so every side is lit as the front is: an orbit to the back or
    // the back snapshot is not left in the dark
    this._light = new THREE.DirectionalLight(0xffffff, 2.2);
    this._scene.add(this._light);
    this._scene.add(this._light.target);

    this._controls = new OrbitControls(this._camera, renderer.domElement);
    this._controls.enableDamping = true;
    this._clock = new THREE.Clock();

    this._resize = new ResizeObserver(() => this._fit());
    this._resize.observe(this);
    this._fit();
    renderer.setAnimationLoop(() => this._frame());
  }

  _fit() {
    const width = Math.max(1, this.clientWidth);
    const height = Math.max(1, this.clientHeight);
    this._renderer.setSize(width, height, false);
    this._camera.aspect = width / height;
    this._camera.updateProjectionMatrix();
  }

  _frame() {
    const delta = this._clock.getDelta();
    if (this._frozen) {
      // she stands as she is: only the view goes on (the user can still turn it)
      this._controls.update();
      this._aimLight(this._camera, this._controls.target);
      this._renderScene();
      return;
    }
    if (this._mixer && !this._animationPaused) {
      this._mixer.update(delta);
    }
    if (this._vrm) {
      this._updateFace(delta);
      this._applyMouth();
      if (this._sway) {
        this._swayTime = (this._swayTime || 0) + delta;
        this._swayPose(1);
      }
      this._vrm.update(delta);
    }
    this._controls.update();
    this._aimLight(this._camera, this._controls.target);
    this._renderScene();
  }

  _renderScene() {
    if (this.renderFrame) {
      this.renderFrame(this._renderer, this._scene, this._camera, this._controls.target);
    } else {
      this._renderer.render(this._scene, this._camera);
    }
  }

  _setStatus(text, error) {
    this._status.textContent = text || '';
    this._status.classList.toggle('error', !!error);
  }

  async _load() {
    const id = ++this._loadId;
    const src = this.getAttribute('src');
    this._clearModel();
    if (!src) {
      this._setStatus('');
      return;
    }
    this._loading = true;
    try {
      const data = await this._fetch(src, id);
      if (id !== this._loadId) {
        return;
      }
      this._setStatus('…');
      const format = (this.getAttribute('format') || '').toUpperCase();
      const main = this.getAttribute('main');
      const files = main ? await readZip(data) : null;
      if (id !== this._loadId) {
        return;
      }
      const object = await this._parse(format, main, data, files);
      if (id !== this._loadId) {
        disposeObject(object);
        return;
      }
      this._show(object);
      this._setStatus('');
    } catch (e) {
      if (id === this._loadId) {
        console.error('joi-model-viewer', e);
        this._setStatus(e && e.message ? e.message : String(e), true);
        this.dispatchEvent(new CustomEvent('model-error', {detail: {message: e && e.message ? e.message : String(e)}}));
      }
    } finally {
      if (id === this._loadId) {
        this._loading = false;
      }
    }
  }

  async _fetch(src, id) {
    const response = await fetch(src, {credentials: 'same-origin'});
    if (!response.ok) {
      throw new Error('HTTP ' + response.status);
    }
    const total = Number(response.headers.get('Content-Length')) || 0;
    if (!response.body || !total) {
      this._setStatus('…');
      return await response.arrayBuffer();
    }
    // Content-Length is only for the progress: a compressed response decodes to more bytes, a cut one to fewer
    const reader = response.body.getReader();
    const chunks = [];
    let received = 0;
    for (;;) {
      const {done, value} = await reader.read();
      if (done) {
        break;
      }
      if (id !== this._loadId) {
        reader.cancel();
        return null;
      }
      chunks.push(value);
      received += value.length;
      this._setStatus(Math.min(99, Math.floor(received * 100 / total)) + ' %');
    }
    const data = new Uint8Array(received);
    let at = 0;
    for (const chunk of chunks) {
      data.set(chunk, at);
      at += chunk.length;
    }
    return data.buffer;
  }

  /** The object of the model; in a zip, its other files are found through the manager by their path or name. */
  async _parse(format, main, data, files) {
    const manager = new THREE.LoadingManager();
    manager.addHandler(/\.tga$/i, new TGALoader(manager));
    let mainData = data;
    let path = '';
    const urls = [];
    if (files) {
      const entry = files.get(main);
      if (!entry) {
        throw new Error('The zip has no ' + main);
      }
      mainData = await entry.read();
      const blobs = new Map();
      manager.setURLModifier(url => {
        if (!url.startsWith(ZIP_BASE)) {
          return url;
        }
        const found = findEntry(files, url.substring(ZIP_BASE.length));
        if (!found) {
          return url;
        }
        return blobs.get(found.name) || url;
      });
      // the URL modifier is synchronous: every file is unpacked before the model is parsed
      for (const entry of files.values()) {
        if (entry.name !== main) {
          const blob = new Blob([await entry.read()], {type: mimeOf(entry.name)});
          const blobUrl = URL.createObjectURL(blob);
          urls.push(blobUrl);
          blobs.set(entry.name, blobUrl);
        }
      }
      path = ZIP_BASE + main.substring(0, main.lastIndexOf('/') + 1);
    }
    this._urls = urls;

    if (format === 'FBX') {
      try {
        return new FBXLoader(manager).parse(mainData, path);
      } catch (e) {
        // FBXLoader fails on animation curves of properties it doesn't animate (VRChat's toggles of materials and
        // visibility); a preview needs no animation, so it reads the model again without them
        const stripped = withoutAnimationCurves(mainData);
        if (!stripped) {
          throw e;
        }
        console.warn('joi-model-viewer: the FBX is shown without its animations', e);
        return new FBXLoader(manager).parse(stripped, path);
      }
    }
    const loader = new GLTFLoader(manager);
    loader.register(parser => new VRMLoaderPlugin(parser));
    const gltf = await new Promise((resolve, reject) => loader.parse(mainData, path, resolve, reject));
    const vrm = gltf.userData.vrm;
    if (vrm) {
      VRMUtils.rotateVRM0(vrm);
      vrm.scene.userData.joiVrm = vrm;
      return vrm.scene;
    }
    return gltf.scene;
  }

  /**
   * Turns her face layer on and says how it is: {eyes, ease, mood} (any of them may be left out to keep it). Null turns it off and
   * gives the face back to the model.
   */
  setFace(face) {
    // asked for from outside (the node's page): it stays on whatever plays
    this._faceByAnimation = false;
    if (!face) {
      this._face = null;
      this._clearFace();
      return;
    }
    const was = this._face || {eyes: 'auto', ease: 0.25, mood: null};
    this._face = {
      eyes: face.eyes === undefined ? was.eyes : face.eyes || 'auto',
      ease: face.ease > 0 ? face.ease : face.ease === undefined ? was.ease : 0.05,
      mood: face.mood === undefined ? was.mood : face.mood || null
    };
  }

  /** What decides her face now: the animation's face, else her state's eyes, else her mood's. */
  _faceNow() {
    const face = this._face;
    const animation = this._animationFace || {};
    const mood = face.mood || null;
    const state = face.eyes === 'auto' ? null : face.eyes;
    const animated = animation.eyes === 'auto' ? null : animation.eyes;
    // her mood's expression is held for hours: without its eyes (a model's happy or relaxed often shuts them), which are
    // the state's and the blinking's; an animation's is short and whole
    const expression = animation.expression
      ? {name: animation.expression, weight: animation.weight > 0 ? animation.weight : 0.7}
      : mood && mood.expression ? {name: this._withoutEyes(mood.expression), weight: mood.weight > 0 ? mood.weight : 0.5} : null;
    return {eyes: animated || state || (mood && mood.eyes) || 'auto', expression};
  }

  /**
   * The name of a copy of the model's expression without the shape keys of the eyes and lids (registered with the
   * model's expressions the first time), or the expression itself when it has none of them.
   */
  _withoutEyes(name) {
    const vrm = this._vrm;
    const manager = vrm && vrm.expressionManager;
    const source = manager && manager.getExpression(name);
    if (!source) {
      return name;
    }
    const copies = vrm.scene.userData.joiWithoutEyes || (vrm.scene.userData.joiWithoutEyes = {});
    if (copies[name] === undefined) {
      const kept = source.binds.filter(bind => !EYE_SHAPE.test(this._shapeName(bind)) || BROW_SHAPE.test(this._shapeName(bind)));
      if (kept.length === source.binds.length) {
        copies[name] = name;
      } else {
        const copy = new VRMExpression('joi-without-eyes-' + name);
        kept.forEach(bind => copy.addBind(bind));
        manager.registerExpression(copy);
        copies[name] = copy.expressionName;
      }
    }
    return copies[name];
  }

  /** The name of the shape key a bind of an expression moves, '' for a bind of a material. */
  _shapeName(bind) {
    const mesh = bind.primitives && bind.primitives[0];
    const names = mesh && mesh.morphTargetDictionary;
    if (!names) {
      return '';
    }
    return Object.keys(names).find(key => names[key] === bind.index) || '';
  }

  /** Runs the face layer: the lids (and blinking), the expression, where she looks. */
  _updateFace(delta) {
    const vrm = this._vrm;
    if (!this._face || !vrm) {
      return;
    }
    const manager = vrm.expressionManager;
    const now = this._faceNow();
    const state = this._faceState || (this._faceState = {lid: 0, pulse: -1, next: 2, time: 0, emotions: {}, gaze: null, gazeNext: 0});

    // the lids go to where the eyes say, over the ease
    const tau = Math.max(0.02, this._face.ease / 3);
    const target = LIDS[now.eyes] || 0;
    state.lid += (target - state.lid) * (1 - Math.exp(-delta / tau));

    // a blink now and then while the eyes are free (one in five is a double)
    state.time += delta;
    if (now.eyes === 'auto' && state.pulse < 0 && state.time >= state.next) {
      state.pulse = 0;
      state.next = state.time + (Math.random() < 0.2 ? 0.35 : 2.2 + Math.random() * 3.8);
    }
    let blink = state.lid;
    if (state.pulse >= 0) {
      state.pulse += delta / BLINK_SECONDS;
      const shape = state.pulse < 0.35 ? state.pulse / 0.35 : Math.max(0, 1 - (state.pulse - 0.35) / 0.65);
      blink = Math.max(blink, shape);
      if (state.pulse >= 1) {
        state.pulse = -1;
      }
    }

    // the expression and the ones before it fade over a second or so
    const rate = 1 - Math.exp(-delta / 0.25);
    const names = new Set([...EMOTIONS, ...Object.keys(state.emotions)]);
    if (now.expression) {
      names.add(now.expression.name);
    }
    for (const name of names) {
      const goal = now.expression && now.expression.name === name ? now.expression.weight : 0;
      const current = state.emotions[name] || 0;
      state.emotions[name] = current + (goal - current) * rate;
    }

    if (manager) {
      if (manager.getExpression('blink')) {
        manager.setValue('blink', Math.min(1, blink));
      }
      for (const name of names) {
        if (manager.getExpression(name)) {
          manager.setValue(name, state.emotions[name]);
        }
      }
    }

    // her eyes follow the camera, a little unsteadily; shut, they look at nothing
    if (vrm.lookAt) {
      if (now.eyes === 'closed') {
        if (vrm.lookAt.target) {
          vrm.lookAt.target = null;
          vrm.lookAt.reset();
        }
      } else {
        if (!state.gaze) {
          state.gaze = new THREE.Object3D();
          state.offset = new THREE.Vector3();
          state.goal = new THREE.Vector3();
          this._scene.add(state.gaze);
        }
        if (state.time >= state.gazeNext) {
          state.gazeNext = state.time + 0.6 + Math.random() * 1.9;
          state.goal.set((Math.random() - 0.5) * 0.24, (Math.random() - 0.5) * 0.14 - (now.eyes === 'half' ? 0.12 : 0), 0);
        }
        state.offset.lerp(state.goal, 1 - Math.exp(-delta / 0.08));
        state.gaze.position.copy(this._camera.position).add(state.offset);
        state.gaze.updateMatrixWorld(true);
        vrm.lookAt.target = state.gaze;
      }
    }
  }

  /** Gives the lids, the emotions and the eyes back to the model (the face layer was turned off). */
  _clearFace() {
    const vrm = this._vrm;
    const manager = vrm && vrm.expressionManager;
    if (manager) {
      for (const name of ['blink', ...EMOTIONS, ...Object.keys(this._faceState ? this._faceState.emotions : {})]) {
        if (manager.getExpression(name)) {
          manager.setValue(name, 0);
        }
      }
    }
    if (vrm && vrm.lookAt && vrm.lookAt.target) {
      vrm.lookAt.target = null;
      vrm.lookAt.reset();
    }
    // the point she looked at leaves the scene with the layer (turned on again, it makes a new one)
    if (this._faceState && this._faceState.gaze && this._faceState.gaze.parent) {
      this._faceState.gaze.parent.remove(this._faceState.gaze);
    }
    this._faceState = null;
  }

  /** The weights of the visemes {aa, ih, ou, ee, oh} to show from now on (null: none, the animation's own). */
  setMouth(weights) {
    this._mouth = weights || null;
    if (!weights) {
      this._applyMouth(true);
    }
  }

  /** Writes the mouth into the expressions, after the mixer and before the model updates (a model without a viseme ignores it). */
  _applyMouth(clear) {
    const manager = this._vrm && this._vrm.expressionManager;
    if (!manager || (!this._mouth && !clear)) {
      return;
    }
    for (const name of ['aa', 'ih', 'ou', 'ee', 'oh']) {
      if (manager.getExpression(name)) {
        manager.setValue(name, this._mouth ? Math.min(1, Math.max(0, this._mouth[name] || 0)) : 0);
      }
    }
  }

  /** Sets a VRM expression (others are set back to 0 when exclusive). */
  setExpression(name, weight, exclusive = true) {
    const manager = this._vrm && this._vrm.expressionManager;
    if (!manager) {
      return;
    }
    if (exclusive) {
      manager.expressions.forEach(expression => manager.setValue(expression.expressionName, 0));
    }
    manager.setValue(name, weight);
  }

  /**
   * Plays an animation, now or once a model is shown: {steps: [{src, loop, speed, inPlace, reverse, rotation, blend}], loop}, src the index of
   * its file in the "animation-src-N" attributes. The steps play in order; a looping step repeats until
   * finishAnimation(), then the steps after it play; a looping animation starts again after its last step, until
   * finished. It holds the last frame at the end.
   */
  playSequence(json) {
    const sequence = typeof json === 'string' ? JSON.parse(json) : json;
    const steps = (sequence && sequence.steps || []).map(step => ({
      url: this.getAttribute('animation-src-' + step.src),
      loop: !!step.loop,
      speed: step.speed > 0 ? step.speed : 1,
      inPlace: !!step.inPlace,
      reverse: !!step.reverse,
      rotation: Array.isArray(step.rotation) && step.rotation.length === 4 ? step.rotation.map(Number) : null,
      blend: step.blend > 0 ? step.blend : 0
    })).filter(step => step.url);
    if (!steps.length) {
      this.stopAnimation();
      return;
    }
    this._animation = {steps, loop: !!sequence.loop};
    this._animationFace = sequence.face || null;
    if (sequence.face && !this._face) {
      this.setFace({});
      // on only for this animation (a dialog of the server): an animation without a face turns it off again
      this._faceByAnimation = true;
    } else if (!sequence.face && this._faceByAnimation) {
      this.setFace(null);
    }
    this._animationPaused = false;
    if (this._vrm) {
      this._startAnimation();
    }
  }

  /** Lets a looping step end its round and the animation play to its end, once. */
  finishAnimation() {
    if (!this._animation || this._finishing) {
      return;
    }
    this._finishing = true;
    if (this._action) {
      // a repeating action set to once plays the round it is in to its end, then fires "finished"
      this._action.setLoop(THREE.LoopOnce, Infinity);
    }
  }

  /** Stops the animation and puts her back at rest. */
  stopAnimation() {
    this._animation = null;
    this._animationFace = null;
    this._animationId = (this._animationId || 0) + 1;
    this._stopMixer();
  }

  setAnimationPaused(paused) {
    this._animationPaused = !!paused;
  }

  /** Freezes her where she is, or lets her go on: nothing of her moves while frozen. */
  setFrozen(frozen) {
    this._frozen = !!frozen;
    // the clock does not count the time she stood still
    this._clock.getDelta();
  }

  async _startAnimation() {
    const animation = this._animation;
    const vrm = this._vrm;
    const id = this._animationId = (this._animationId || 0) + 1;
    try {
      const loaded = new Map();
      const clips = [];
      const sources = [];
      for (const step of animation.steps) {
        if (!loaded.has(step.url)) {
          const loader = new GLTFLoader();
          loader.register(parser => new VRMAnimationLoaderPlugin(parser));
          const gltf = await loader.loadAsync(step.url);
          if (id !== this._animationId || vrm !== this._vrm) {
            return;
          }
          const vrmAnimation = gltf.userData.vrmAnimations && gltf.userData.vrmAnimations[0];
          if (!vrmAnimation) {
            throw new Error('the file has no VRM animation');
          }
          // hips that rest at the floor would scale the clip's movement of the hips by a height of 0 (NaN): left out
          const restHips = vrmAnimation.restHipsPosition;
          if (!restHips || !(Math.abs(restHips.y) > 1e-6)) {
            vrmAnimation.humanoidTracks.translation.delete('hips');
          }
          loaded.set(step.url, vrmAnimation);
        }
        // the clip as made, kept to make the step again when it is turned with the rings
        const source = createVRMAnimationClip(loaded.get(step.url), vrm);
        sources.push(source);
        clips.push(this._prepareClip(source.clone(), step, vrm));
      }
      this._stopMixer();
      this._mixer = new THREE.AnimationMixer(vrm.scene);
      this._clips = clips;
      this._sources = sources;
      this._finishing = false;
      this._mixer.addEventListener('finished', () => this._stepEnded(id));
      this._mixer.addEventListener('loop', () => {
        this._repeats++;
        this._stepEvent();
      });
      this._playStep(0);
      this._attachRotationTool();
      const duration = clips.reduce((sum, clip, i) => sum + clip.duration / animation.steps[i].speed, 0);
      this.dispatchEvent(new CustomEvent('animation-started', {detail: {duration: Number(duration.toFixed(3))}}));
    } catch (e) {
      if (id === this._animationId) {
        this.dispatchEvent(new CustomEvent('animation-error', {detail: {message: String(e && e.message || e)}}));
      }
    }
  }

  _playStep(index) {
    const step = this._animation.steps[index];
    const before = this._action;
    const action = this._mixer.clipAction(this._clips[index]);
    if (this._fading && this._fading !== action) {
      this._fading.stop();
    }
    this._fading = null;
    this._mixer.timeScale = step.speed;
    action.reset();
    action.setLoop(step.loop && !this._finishing ? THREE.LoopRepeat : THREE.LoopOnce, Infinity);
    action.clampWhenFinished = true;
    action.play();
    if (before && before !== action) {
      if (step.blend > 0) {
        // the step before holds its last frame and fades out while this one fades in (mixer time runs at the speed)
        before.crossFadeTo(action, step.blend * step.speed, false);
        this._fading = before;
      } else {
        before.stop();
      }
    }
    this._action = action;
    this._stepIndex = index;
    this._repeats = 1;
    this._stepEvent();
  }

  _stepEnded(id) {
    const animation = this._animation;
    if (!animation || id !== this._animationId) {
      return;
    }
    const next = this._stepIndex + 1;
    if (next < animation.steps.length) {
      this._playStep(next);
    } else if (animation.loop && !this._finishing) {
      this._playStep(0);
    } else {
      this._finishing = false;
      this.dispatchEvent(new CustomEvent('animation-finished'));
    }
  }

  _stepEvent() {
    this.dispatchEvent(new CustomEvent('animation-step', {detail: {index: this._stepIndex, repeats: this._repeats}}));
  }

  /** The clip of a step, from a copy of the clip as made: reversed, turned, held in place, as the step says. */
  _prepareClip(clip, step, vrm) {
    if (step.reverse) {
      this._reverseClip(clip);
    }
    if (step.rotation) {
      this._rotateClip(clip, step.rotation, vrm);
    }
    if (step.inPlace) {
      this._keepInPlace(clip, vrm);
    }
    return clip;
  }

  /**
   * Turns the whole clip by the quaternion [x, y, z, w]: the hips' rotation at every key, and their path around where
   * they are at the start (the pivot), so she turns where the step begins.
   */
  _rotateClip(clip, rotation, vrm) {
    const hips = vrm.humanoid && vrm.humanoid.getNormalizedBoneNode('hips');
    if (!hips) {
      return;
    }
    const turn = new THREE.Quaternion().fromArray(rotation).normalize();
    const turned = clip.tracks.find(t => t.name === hips.name + '.quaternion');
    if (turned) {
      const q = new THREE.Quaternion();
      for (let i = 0; i < turned.values.length; i += 4) {
        q.fromArray(turned.values, i).premultiply(turn).toArray(turned.values, i);
      }
    }
    const moved = clip.tracks.find(t => t.name === hips.name + '.position');
    if (moved && moved.values.length >= 3) {
      const pivot = new THREE.Vector3().fromArray(moved.values, 0);
      const p = new THREE.Vector3();
      for (let i = 0; i < moved.values.length; i += 3) {
        p.fromArray(moved.values, i).sub(pivot).applyQuaternion(turn).add(pivot).toArray(moved.values, i);
      }
    }
  }

  /** Shows the rings to turn the step at the index (of the animation playing), or hides them (-1). */
  setRotationTool(index) {
    this._rotationIndex = index >= 0 ? index : -1;
    this._attachRotationTool();
  }

  _attachRotationTool() {
    this._detachRotationTool();
    const index = this._rotationIndex;
    const vrm = this._vrm;
    const hips = vrm && vrm.humanoid && vrm.humanoid.getNormalizedBoneNode('hips');
    if (!(index >= 0) || !hips || !hips.parent || !this._clips || !this._animation || index >= this._clips.length) {
      return;
    }
    const step = this._animation.steps[index];
    // a handle where the hips are as the step starts, in the hips' parent: its rotation is the step's
    const handle = new THREE.Object3D();
    const moved = this._clips[index].tracks.find(t => t.name === hips.name + '.position');
    if (moved && moved.values.length >= 3) {
      handle.position.fromArray(moved.values, 0);
    } else {
      handle.position.copy(hips.position);
    }
    if (step.rotation) {
      handle.quaternion.fromArray(step.rotation).normalize();
    }
    hips.parent.add(handle);
    const tool = new TransformControls(this._camera, this._renderer.domElement);
    tool.setMode('rotate');
    // around the scene's axes: the Y ring always turns her around, whichever way she already faces
    tool.setSpace('world');
    tool.attach(handle);
    tool.addEventListener('dragging-changed', e => {
      this._controls.enabled = !e.value;
    });
    tool.addEventListener('objectChange', () => this._turnStep(index, handle.quaternion));
    tool.addEventListener('mouseUp', () => {
      const q = handle.quaternion;
      this.dispatchEvent(new CustomEvent('step-rotated', {detail: {index, x: q.x, y: q.y, z: q.z, w: q.w}}));
    });
    this._scene.add(tool.getHelper());
    this._rotationTool = {tool, handle};
  }

  _detachRotationTool() {
    const rotation = this._rotationTool;
    if (!rotation) {
      return;
    }
    rotation.tool.detach();
    this._scene.remove(rotation.tool.getHelper());
    rotation.tool.dispose();
    if (rotation.handle.parent) {
      rotation.handle.parent.remove(rotation.handle);
    }
    this._controls.enabled = true;
    this._rotationTool = null;
  }

  /** Makes the step's clip again with the new rotation and plays it on from where it was. */
  _turnStep(index, quaternion) {
    const step = this._animation && this._animation.steps[index];
    if (!step || !this._sources || !this._mixer || !this._vrm) {
      return;
    }
    step.rotation = quaternion.toArray();
    const old = this._clips[index];
    const clip = this._prepareClip(this._sources[index].clone(), step, this._vrm);
    this._clips[index] = clip;
    if (this._fading && this._fading.getClip() === old) {
      this._fading.stop();
      this._fading = null;
    }
    const playing = this._action && this._action.getClip() === old ? this._action : null;
    if (playing) {
      const action = this._mixer.clipAction(clip);
      action.reset();
      action.setLoop(playing.loop, Infinity);
      action.clampWhenFinished = true;
      action.time = playing.time;
      action.paused = playing.paused;
      action.play();
      playing.stop();
      this._action = action;
    }
    this._mixer.uncacheClip(old);
    // the pose shows the turn at once, also while paused
    this._mixer.update(0);
  }

  /** Turns the clip around: its last frame first, each key at the same distance from the other end. */
  _reverseClip(clip) {
    const duration = clip.duration;
    for (const track of clip.tracks) {
      const times = track.times;
      const values = track.values;
      const count = times.length;
      const size = track.getValueSize();
      const reversedTimes = new times.constructor(count);
      const reversedValues = new values.constructor(values.length);
      for (let i = 0; i < count; i++) {
        const from = count - 1 - i;
        reversedTimes[i] = duration - times[from];
        for (let k = 0; k < size; k++) {
          reversedValues[i * size + k] = values[from * size + k];
        }
      }
      track.times = reversedTimes;
      track.values = reversedValues;
    }
  }

  /** Holds the hips of the clip on the floor where they start: only their height moves. */
  _keepInPlace(clip, vrm) {
    const hips = vrm.humanoid && vrm.humanoid.getNormalizedBoneNode('hips');
    const track = hips && clip.tracks.find(t => t.name === hips.name + '.position');
    if (!track || track.values.length < 3) {
      return;
    }
    const values = track.values;
    const x = values[0];
    const z = values[2];
    for (let i = 0; i < values.length; i += 3) {
      values[i] = x;
      values[i + 2] = z;
    }
  }

  /** Drops the mixer of the clip and puts the model back at rest. */
  _stopMixer() {
    this._detachRotationTool();
    this._fading = null;
    this._sources = null;
    if (this._mixer) {
      this._mixer.stopAllAction();
      this._mixer.uncacheRoot(this._mixer.getRoot());
      this._mixer = null;
      this._action = null;
    }
    this._clips = null;
    this._finishing = false;
    const vrm = this._vrm;
    if (vrm) {
      if (vrm.humanoid) {
        vrm.humanoid.resetNormalizedPose();
      }
      if (vrm.expressionManager) {
        vrm.expressionManager.expressions.forEach(expression => vrm.expressionManager.setValue(expression.expressionName, 0));
      }
    }
  }

  /** Turns her hips and body from side to side, so the spring bones (hair, tail, cloth) are seen moving. */
  setSway(on) {
    this._sway = !!on;
    if (!this._sway) {
      this._swayPose(0);
    }
  }

  /** The sway's pose now, at the amount (0 puts her back at rest). */
  _swayPose(amount) {
    const humanoid = this._vrm && this._vrm.humanoid;
    if (!humanoid) {
      return;
    }
    const t = this._swayTime || 0;
    const hips = humanoid.getNormalizedBoneNode('hips');
    const spine = humanoid.getNormalizedBoneNode('spine');
    if (hips) {
      hips.rotation.y = amount * 0.45 * Math.sin(t * 2.2);
    }
    if (spine) {
      spine.rotation.z = amount * 0.12 * Math.sin(t * 2.2 + 0.6);
    }
  }

  /** Shows or hides a mesh by the name of its node. */
  setMeshVisible(name, visible) {
    const node = this._node(name);
    if (node) {
      node.visible = visible;
    }
  }

  /** Sets a shape key of a mesh (a toggle key such as "Hide Halo"). */
  setMorph(mesh, key, weight) {
    const node = this._node(mesh);
    if (!node) {
      return;
    }
    node.traverse(child => {
      if (child.morphTargetDictionary && key in child.morphTargetDictionary) {
        child.morphTargetInfluences[child.morphTargetDictionary[key]] = weight;
      }
    });
  }

  /**
   * Sets toggles of the model shown: a JSON array of {mesh, key (null for the mesh itself), hides, shown, parent (the id
   * of the toggle it is part of, "mesh:Bodysuit" / "key:Bodysuit/Hide Panties"; null for none)}. A toggle's own state is
   * what it is set to; it is seen only while the toggles it is part of are shown (a bodysuit hidden hides its panties).
   */
  setToggles(togglesJson) {
    this._applyToggles(togglesJson ? JSON.parse(togglesJson) : []);
  }

  /** The id of a toggle as the server has it. */
  _toggleId(toggle) {
    return toggle.key ? 'key:' + toggle.mesh + '/' + toggle.key : 'mesh:' + toggle.mesh;
  }

  /** Shows what the toggles say: each seen only while every toggle it is part of (among them) is shown. */
  _applyToggles(toggles) {
    const byId = new Map(toggles.map(toggle => [this._toggleId(toggle), toggle]));
    for (const toggle of toggles) {
      let seen = toggle.shown;
      const visited = new Set([this._toggleId(toggle)]);
      let at = toggle;
      while (seen) {
        const above = at.parent ? byId.get(at.parent) : null;
        if (!above || visited.has(this._toggleId(above))) {
          break;
        }
        visited.add(this._toggleId(above));
        seen = above.shown;
        at = above;
      }
      this._setToggle(toggle, seen);
    }
  }

  /**
   * Renders the snapshots of the model and POSTs each to the URL of the "snapshot-target" attribute, named after its
   * shot: for each part a close-up per state (768 × 768, "part-<index>-<state index>") from the default state, then the
   * model in its bare state (the layers of her look off), the shots of SHOTS (768 × 1024 JPEGs on a plain background).
   * The toggles are put back as they were.
   * Fires "snapshots-taken" ({count}) when the server has them, "snapshots-error" ({message}) when not.
   *
   * @param requestJson {base: [{mesh, key, hides, shown}] (the default state of every toggle), bare: (the same with the
   *                    layers of her look off, for the full shots; base when absent), parts: [{index,
   *                    toggles: [{mesh, key, hides}], states: [[shown of each toggle]]}]}
   */
  async takeSnapshots(requestJson) {
    try {
      if (!this._model || !this._renderer) {
        throw new Error('No model is shown');
      }
      const request = requestJson ? JSON.parse(requestJson) : {};
      const base = request.base || [];
      const bare = request.bare || base;
      const parts = request.parts || [];
      const saved = this._saveToggles(base.concat(...parts.map(part => part.toggles)));
      // the snapshots show her at rest, even while she sways
      this._swayPose(0);
      let images;
      try {
        // the close-ups start from the default state; then the layers of her look go off for the shots of the whole model
        this._applyToggles(base);
        images = this._snapshotSetup(768, 768, camera => this._renderParts(camera, parts, base));
        this._applyToggles(bare);
        images.push(...this._snapshotSetup(768, 1024, camera => this._renderShots(camera)));
      } finally {
        this._restoreToggles(saved);
      }
      // one request per shot, as Vaadin's own uploads do (the raw file, its name in X-Filename)
      for (const image of images) {
        const blob = await (await fetch(image.url)).blob();
        const response = await fetch(this.getAttribute('snapshot-target'), {
          method: 'POST', body: blob,
          headers: {'Content-Type': 'image/jpeg', 'X-Filename': encodeURIComponent(image.name + '.jpg')}
        });
        if (!response.ok) {
          throw new Error('The server refused the snapshots (' + response.status + ')');
        }
      }
      this.dispatchEvent(new CustomEvent('snapshots-taken', {detail: {count: images.length}}));
    } catch (e) {
      this.dispatchEvent(new CustomEvent('snapshots-error', {detail: {message: String(e && e.message || e)}}));
    }
  }

  /**
   * Renders with a camera of the size on a plain background, synchronously (no frame of the loop in between), and puts
   * the view back as it was.
   */
  _snapshotSetup(width, height, render) {
    const renderer = this._renderer;
    const camera = new THREE.PerspectiveCamera(30, width / height, this._camera.near, this._camera.far);
    const before = {size: renderer.getSize(new THREE.Vector2()), ratio: renderer.getPixelRatio(),
      background: this._scene.background, grid: this._grid ? this._grid.visible : false};
    renderer.setPixelRatio(1);
    renderer.setSize(width, height, false);
    this._scene.background = new THREE.Color(0x9ea4ab);
    if (this._grid) {
      this._grid.visible = false;
    }
    try {
      return render(camera);
    } finally {
      renderer.setPixelRatio(before.ratio);
      renderer.setSize(before.size.x, before.size.y, false);
      this._scene.background = before.background;
      if (this._grid) {
        this._grid.visible = before.grid;
      }
    }
  }

  /** Points the camera at the target from the angle (degrees around the model, 0 = its front) and takes the image. */
  _shoot(camera, name, target, distance, angleDegrees) {
    const angle = THREE.MathUtils.degToRad(angleDegrees);
    camera.position.set(target.x + Math.sin(angle) * distance, target.y, target.z + Math.cos(angle) * distance);
    camera.near = distance / 100;
    camera.far = distance * 100;
    camera.updateProjectionMatrix();
    camera.lookAt(target);
    if (this._vrm) {
      this._vrm.update(0);
    }
    this._aimLight(camera, target);
    this._renderer.render(this._scene, camera);
    return {name, url: this._renderer.domElement.toDataURL('image/jpeg', 0.9)};
  }

  _renderShots(camera) {
    const box = new THREE.Box3().setFromObject(this._model);
    const size = box.getSize(new THREE.Vector3());
    const center = box.getCenter(new THREE.Vector3());
    const head = this._headPosition(box, size);
    const fov = THREE.MathUtils.degToRad(camera.fov / 2);
    // the whole model in the frame: its height, and its widest side (a T-pose's arms) across the narrower width
    const across = Math.atan(Math.tan(fov) * camera.aspect);
    const fullDistance = Math.max(size.y * 0.55 / Math.tan(fov), Math.max(size.x, size.z) * 0.55 / Math.tan(across))
        + Math.max(size.x, size.z) / 2;
    const faceDistance = (size.y * 0.09) / Math.tan(fov);
    return SHOTS.map(shot => this._shoot(camera, shot.name, shot.face ? head : center,
        shot.face ? faceDistance : fullDistance, shot.angle));
  }

  /**
   * A close-up of each part in each of its states: framed on the part (its toggles' meshes, or the vertices their
   * shape keys move, with some of the model around it), from the side it is on. Between parts the toggles go back to
   * the default state.
   */
  _renderParts(camera, parts, base) {
    const box = new THREE.Box3().setFromObject(this._model);
    const height = box.getSize(new THREE.Vector3()).y;
    const center = box.getCenter(new THREE.Vector3());
    const fov = THREE.MathUtils.degToRad(camera.fov / 2);
    const byId = new Map(base.map(toggle => [this._toggleId(toggle), toggle]));
    const images = [];
    for (const part of parts) {
      const partBox = new THREE.Box3();
      for (const toggle of part.toggles) {
        const node = this._node(toggle.mesh);
        const toggleBox = node && this._partBox(node, toggle.key);
        if (toggleBox && !toggleBox.isEmpty()) {
          partBox.union(toggleBox);
        }
      }
      if (partBox.isEmpty()) {
        continue;
      }
      const target = partBox.getCenter(new THREE.Vector3());
      const extent = Math.max(partBox.getSize(new THREE.Vector3()).length(), height * 0.18);
      const distance = extent * 0.75 / Math.tan(fov);
      const dx = target.x - center.x;
      const dz = target.z - center.z;
      // the side the part is on: in front unless it is clearly behind or beside the body (a tail, a blade)
      const angle = Math.hypot(dx, dz) > height * 0.06 ? THREE.MathUtils.radToDeg(Math.atan2(dx, dz)) : 0;
      // a close-up shows what the part is part of and what is part of it switched on: the panties are seen only while the
      // bodysuit is shown, and the bodysuit is taken with all its options on, not with those the default switched off
      // (only the part's own toggles are set by the shot, the rest stays as by default)
      const own = new Set(part.toggles.map(toggle => this._toggleId(toggle)));
      const related = new Set();
      for (const toggle of base) {
        const id = this._toggleId(toggle);
        const chain = [];
        for (let at = toggle; at && !chain.includes(at); at = at.parent ? byId.get(at.parent) : null) {
          chain.push(at);
        }
        const ids = chain.map(at => this._toggleId(at));
        if (ids.slice(1).some(up => own.has(up))) {
          related.add(id); // below the part
        }
        if (own.has(id)) {
          ids.slice(1).forEach(up => related.add(up)); // above the part
        }
      }
      try {
        part.states.forEach((state, index) => {
          const states = new Map(part.toggles.map((toggle, i) => [this._toggleId(toggle), state[i]]));
          this._applyToggles(base.map(toggle => {
            const id = this._toggleId(toggle);
            if (states.has(id)) {
              return {...toggle, shown: states.get(id)};
            }
            return related.has(id) && !own.has(id) ? {...toggle, shown: true} : toggle;
          }));
          images.push(this._shoot(camera, 'part-' + part.index + '-' + index, target, distance, angle));
        });
      } finally {
        this._applyToggles(base);
      }
    }
    return images;
  }

  /** Where a part is: its mesh, or the vertices of the meshes its shape key moves, in the world. */
  _partBox(node, key) {
    node.updateWorldMatrix(true, true);
    if (!key) {
      return new THREE.Box3().setFromObject(node, true);
    }
    const box = new THREE.Box3();
    const vertex = new THREE.Vector3();
    node.traverse(child => {
      if (!child.isMesh || !child.morphTargetDictionary || !(key in child.morphTargetDictionary)) {
        return;
      }
      const position = child.geometry.attributes.position;
      const morph = child.geometry.morphAttributes.position && child.geometry.morphAttributes.position[child.morphTargetDictionary[key]];
      if (!morph) {
        return;
      }
      const relative = child.geometry.morphTargetsRelative;
      const moved = i => {
        const dx = morph.getX(i) - (relative ? 0 : position.getX(i));
        const dy = morph.getY(i) - (relative ? 0 : position.getY(i));
        const dz = morph.getZ(i) - (relative ? 0 : position.getZ(i));
        return dx * dx + dy * dy + dz * dz;
      };
      // exported keys often move the whole mesh a hair: only what moves by a twentieth of the most is the part
      let most = 0;
      for (let i = 0; i < position.count; i++) {
        most = Math.max(most, moved(i));
      }
      const least = most * 0.05 * 0.05;
      for (let i = 0; i < position.count; i++) {
        if (most === 0 || moved(i) < least) {
          continue;
        }
        vertex.fromBufferAttribute(position, i);
        if (child.isSkinnedMesh && child.applyBoneTransform) {
          child.applyBoneTransform(i, vertex);
        }
        box.expandByPoint(vertex.applyMatrix4(child.matrixWorld));
      }
    });
    return box;
  }

  _partState(node, key) {
    const state = {visible: node.visible, weights: []};
    if (key) {
      node.traverse(child => {
        if (child.morphTargetDictionary && key in child.morphTargetDictionary) {
          state.weights.push([child, child.morphTargetInfluences[child.morphTargetDictionary[key]]]);
        }
      });
    }
    return state;
  }

  /** Shows or hides the part of a toggle: its mesh, or its "Hide X" key (at full weight it hides the part). */
  _setToggle(toggle, shown) {
    const node = this._node(toggle.mesh);
    if (!node) {
      return;
    }
    if (!toggle.key) {
      node.visible = shown;
    } else {
      this.setMorph(toggle.mesh, toggle.key, shown === toggle.hides ? 0 : 1);
    }
  }

  /** How the toggles are now, to put them back. */
  _saveToggles(toggles) {
    const saved = [];
    for (const toggle of toggles) {
      const node = this._node(toggle.mesh);
      if (node) {
        saved.push({node, key: toggle.key, state: this._partState(node, toggle.key)});
      }
    }
    return saved;
  }

  /** Puts the toggles back, the last saved first (a mesh saved twice ends as it was at first). */
  _restoreToggles(saved) {
    for (const {node, key, state} of saved.slice().reverse()) {
      node.visible = state.visible;
      for (const [child, weight] of state.weights) {
        child.morphTargetInfluences[child.morphTargetDictionary[key]] = weight;
      }
    }
  }

  /** Puts the key light above and to the right of the camera, pointed at what it looks at. */
  _aimLight(camera, target) {
    const offset = camera.position.clone().sub(target);
    const distance = offset.length();
    const right = new THREE.Vector3().crossVectors(camera.up, offset).normalize();
    this._light.position.copy(camera.position)
        .addScaledVector(right, distance * 0.35)
        .addScaledVector(camera.up, distance * 0.5);
    this._light.target.position.copy(target);
    this._light.target.updateMatrixWorld();
  }

  /** Where the head is: the VRM's head bone, else near the top of the model. */
  _headPosition(box, size) {
    const bone = this._vrm && this._vrm.humanoid && this._vrm.humanoid.getRawBoneNode('head');
    if (bone) {
      const position = bone.getWorldPosition(new THREE.Vector3());
      position.y += size.y * 0.04;
      return position;
    }
    const center = box.getCenter(new THREE.Vector3());
    return new THREE.Vector3(center.x, box.max.y - size.y * 0.08, center.z);
  }

  /** The node of the model with the name; glTF names are sanitized by the loader ("Body Base" → "Body_Base"). */
  _node(name) {
    if (!this._model) {
      return null;
    }
    return this._model.getObjectByName(name) || this._model.getObjectByName(THREE.PropertyBinding.sanitizeNodeName(name));
  }

  _show(object) {
    this._model = object;
    this._vrm = object.userData.joiVrm || null;
    this._scene.add(object);

    const box = new THREE.Box3().setFromObject(object);
    const size = box.getSize(new THREE.Vector3());
    const center = box.getCenter(new THREE.Vector3());
    const height = Math.max(size.y, 1e-3);
    // an FBX from Unity is often in centimetres: the camera follows whatever the size is
    const radius = Math.max(size.x, size.y, size.z) / 2;
    const distance = radius / Math.sin(THREE.MathUtils.degToRad(this._camera.fov / 2)) * 1.1;
    this._camera.near = distance / 100;
    this._camera.far = distance * 100;
    this._camera.position.set(center.x, center.y + height * 0.05, center.z + distance);
    this._camera.updateProjectionMatrix();
    this._controls.target.copy(center);
    this._controls.update();

    this._grid = new THREE.GridHelper(radius * 4, 20, 0x888888, 0x444444);
    this._grid.position.set(center.x, box.min.y, center.z);
    this._grid.material.transparent = true;
    this._grid.material.opacity = 0.35;
    this._scene.add(this._grid);

    let triangles = 0;
    let meshes = 0;
    const materials = new Set();
    object.traverse(node => {
      if (node.isMesh) {
        meshes++;
        const geometry = node.geometry;
        triangles += (geometry.index ? geometry.index.count : geometry.attributes.position.count) / 3;
        (Array.isArray(node.material) ? node.material : [node.material]).forEach(m => materials.add(m));
      }
    });
    const vrm = this._vrm;
    if (vrm && this._animation) {
      this._startAnimation();
    } else if (!vrm && this._animation) {
      this.dispatchEvent(new CustomEvent('animation-error', {detail: {message: 'the model is no VRM: clips play on VRM models'}}));
    }
    this.dispatchEvent(new CustomEvent('model-info', {
      detail: {
        triangles: Math.round(triangles), meshes, materials: materials.size,
        vrm: vrm ? (vrm.meta && vrm.meta.metaVersion === '0' ? '0.x' : '1.0') : null,
        expressions: vrm && vrm.expressionManager ? vrm.expressionManager.expressions.length : 0,
        height: Number(size.y.toFixed(3))
      }
    }));
  }

  _clearModel() {
    this._detachRotationTool();
    if (this._mixer) {
      this._mixer.stopAllAction();
      this._mixer = null;
      this._action = null;
    }
    if (this._model) {
      this._scene.remove(this._model);
      disposeObject(this._model);
      this._model = null;
      this._vrm = null;
    }
    if (this._grid) {
      this._scene.remove(this._grid);
      this._grid.geometry.dispose();
      this._grid.material.dispose();
      this._grid = null;
    }
    (this._urls || []).forEach(url => URL.revokeObjectURL(url));
    this._urls = [];
  }

  _dispose() {
    this._loadId++;
    this._loading = false;
    if (!this._renderer) {
      return;
    }
    this._clearModel();
    this._resize.disconnect();
    this._controls.dispose();
    this._renderer.setAnimationLoop(null);
    this._renderer.dispose();
    this._renderer.domElement.remove();
    this._renderer = null;
  }
}

function disposeObject(object) {
  object.traverse(node => {
    if (node.geometry) {
      node.geometry.dispose();
    }
    if (node.material) {
      (Array.isArray(node.material) ? node.material : [node.material]).forEach(material => {
        Object.values(material).forEach(value => value && value.isTexture && value.dispose());
        material.dispose();
      });
    }
  });
}

/** A file of the zip for a path a model asks for: the same path, else the same path in any case, else the same name. */
function findEntry(files, wanted) {
  let path = wanted.replace(/\\/g, '/');
  try {
    path = decodeURIComponent(path);
  } catch (e) {
    // a name with a lone % is taken as it is
  }
  const parts = [];
  for (const part of path.split('/')) {
    if (part === '..') {
      parts.pop();
    } else if (part !== '.' && part !== '') {
      parts.push(part);
    }
  }
  path = parts.join('/');
  if (files.has(path)) {
    return files.get(path);
  }
  const lower = path.toLowerCase();
  const name = lower.substring(lower.lastIndexOf('/') + 1);
  let byName = null;
  for (const entry of files.values()) {
    const entryLower = entry.name.toLowerCase();
    if (entryLower === lower) {
      return entry;
    }
    if (!byName && entryLower.substring(entryLower.lastIndexOf('/') + 1) === name) {
      byName = entry;
    }
  }
  return byName;
}

/**
 * A copy of the FBX whose AnimationCurve nodes have another name, so FBXLoader finds no animation; null when it has
 * none. Binary FBX stores a node name after its length byte, the text FBX as "AnimationCurve:".
 */
function withoutAnimationCurves(buffer) {
  const bytes = new Uint8Array(buffer.slice(0));
  const name = new TextEncoder().encode('AnimationCurve');
  const binary = new TextDecoder().decode(bytes.subarray(0, 18)) === 'Kaydara FBX Binary';
  let found = false;
  outer:
  for (let i = 1; i + name.length <= bytes.length; i++) {
    for (let j = 0; j < name.length; j++) {
      if (bytes[i + j] !== name[j]) {
        continue outer;
      }
    }
    const after = bytes[i + name.length];
    if (binary ? bytes[i - 1] === name.length : after === 0x3a /* : */) {
      bytes[i + name.length - 1] = 0x5f; // AnimationCurv_
      found = true;
    }
  }
  return found ? bytes.buffer : null;
}

function mimeOf(name) {
  const lower = name.toLowerCase();
  if (lower.endsWith('.png')) return 'image/png';
  if (lower.endsWith('.jpg') || lower.endsWith('.jpeg')) return 'image/jpeg';
  if (lower.endsWith('.webp')) return 'image/webp';
  return 'application/octet-stream';
}

/**
 * The files of a zip by their path: {name, read()} where read() gives an ArrayBuffer. Stored and deflated files, read
 * from the central directory; the browser inflates them (DecompressionStream). No ZIP64: a zip over 4 GB is refused.
 */
async function readZip(buffer) {
  const view = new DataView(buffer);
  const bytes = new Uint8Array(buffer);
  let end = -1;
  for (let i = buffer.byteLength - 22; i >= Math.max(0, buffer.byteLength - 65557); i--) {
    if (view.getUint32(i, true) === 0x06054b50) {
      end = i;
      break;
    }
  }
  if (end < 0) {
    throw new Error('Not a zip');
  }
  const count = view.getUint16(end + 10, true);
  let offset = view.getUint32(end + 16, true);
  if (offset === 0xffffffff) {
    throw new Error('ZIP64 is not supported');
  }
  const utf8 = new TextDecoder('utf-8');
  const files = new Map();
  for (let n = 0; n < count; n++) {
    if (view.getUint32(offset, true) !== 0x02014b50) {
      throw new Error('Broken zip');
    }
    const method = view.getUint16(offset + 10, true);
    const compressed = view.getUint32(offset + 20, true);
    const nameLength = view.getUint16(offset + 28, true);
    const extraLength = view.getUint16(offset + 30, true);
    const commentLength = view.getUint16(offset + 32, true);
    const local = view.getUint32(offset + 42, true);
    const name = utf8.decode(bytes.subarray(offset + 46, offset + 46 + nameLength));
    offset += 46 + nameLength + extraLength + commentLength;
    if (name.endsWith('/') || name.startsWith('__MACOSX/') || name.includes('/__MACOSX/')) {
      continue;
    }
    const start = local + 30 + view.getUint16(local + 26, true) + view.getUint16(local + 28, true);
    const data = bytes.subarray(start, start + compressed);
    files.set(name, {
      name,
      read: async () => {
        if (method === 0) {
          return data.slice().buffer;
        }
        if (method !== 8) {
          throw new Error('Unsupported compression in ' + name);
        }
        const stream = new Blob([data]).stream().pipeThrough(new DecompressionStream('deflate-raw'));
        return await new Response(stream).arrayBuffer();
      }
    });
  }
  return files;
}

customElements.define('joi-model-viewer', JoiModelViewer);
