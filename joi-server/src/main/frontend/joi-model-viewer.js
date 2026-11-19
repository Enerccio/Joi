// <joi-model-viewer>: the 3D preview of a model (ModelViewer.java). It shows a VRM, a glTF / GLB or an FBX, alone or
// from a zip with its textures, lit and framed, to orbit with the mouse. Attributes (set by the server):
//   src    the URL of the file (the model, or the zip)
//   format VRM, GLB, GLTF or FBX: the format of the model (of the main file in a zip)
//   main   the path of the model file in the zip; absent when src is the model itself
// Methods (the dialog tries what the model can do): setExpression(name, weight), setMeshVisible(name, visible),
// setMorph(mesh, key, weight), setToggles(json), setSway(on) (turns her from side to side, to see the spring bones move);
// they act on the model shown, nothing happens before it is.
// takeSnapshots(request) renders the snapshots of a model (the whole model in its default state, and a close-up of
// each part described apart in each of its states) for the "snapshot-target" URL.
// playAnimation(loop, speed) plays the VRMA at the "animation-src" URL on the model (now, or once it is shown: a new
// model plays it again), stopAnimation() puts her back at rest; setAnimationLoop(loop), setAnimationSpeed(speed) and
// setAnimationPaused(paused) change the clip playing. It fires "animation-started" ({duration}), "animation-finished"
// (a clip played once ended) and "animation-error" ({message}).
// It fires "model-info" ({triangles, meshes, materials, vrm, expressions}) once the model is shown and "model-error"
// ({message}) when it can't be.
import * as THREE from 'three';
import {GLTFLoader} from 'three/addons/loaders/GLTFLoader.js';
import {FBXLoader} from 'three/addons/loaders/FBXLoader.js';
import {TGALoader} from 'three/addons/loaders/TGALoader.js';
import {OrbitControls} from 'three/addons/controls/OrbitControls.js';
import {VRMLoaderPlugin, VRMUtils} from '@pixiv/three-vrm';
import {VRMAnimationLoaderPlugin, createVRMAnimationClip} from '@pixiv/three-vrm-animation';

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
    if (this._mixer && !this._animationPaused) {
      this._mixer.update(delta);
    }
    if (this._vrm) {
      if (this._sway) {
        this._swayTime = (this._swayTime || 0) + delta;
        this._swayPose(1);
      }
      this._vrm.update(delta);
    }
    this._controls.update();
    this._aimLight(this._camera, this._controls.target);
    this._renderer.render(this._scene, this._camera);
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
    const reader = response.body.getReader();
    const data = new Uint8Array(total);
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
      data.set(value, received);
      received += value.length;
      this._setStatus(Math.floor(received * 100 / total) + ' %');
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

  /** Plays the clip at the "animation-src" URL, now or once a model is shown. */
  playAnimation(loop, speed) {
    const url = this.getAttribute('animation-src');
    if (!url) {
      return;
    }
    this._animation = {url, loop: !!loop, speed: speed > 0 ? speed : 1};
    this._animationPaused = false;
    if (this._vrm) {
      this._startAnimation();
    }
  }

  /** Stops the clip and puts her back at rest. */
  stopAnimation() {
    this._animation = null;
    this._animationId = (this._animationId || 0) + 1;
    this._stopMixer();
  }

  setAnimationLoop(loop) {
    if (this._animation) {
      this._animation.loop = !!loop;
    }
    if (this._action) {
      this._action.setLoop(loop ? THREE.LoopRepeat : THREE.LoopOnce, Infinity);
      if (loop && !this._action.isRunning()) {
        this._action.reset().play();
      }
    }
  }

  setAnimationSpeed(speed) {
    const value = speed > 0 ? speed : 1;
    if (this._animation) {
      this._animation.speed = value;
    }
    if (this._mixer) {
      this._mixer.timeScale = value;
    }
  }

  setAnimationPaused(paused) {
    this._animationPaused = !!paused;
  }

  async _startAnimation() {
    const animation = this._animation;
    const vrm = this._vrm;
    const id = this._animationId = (this._animationId || 0) + 1;
    try {
      const loader = new GLTFLoader();
      loader.register(parser => new VRMAnimationLoaderPlugin(parser));
      const gltf = await loader.loadAsync(animation.url);
      if (id !== this._animationId || vrm !== this._vrm) {
        return;
      }
      const vrmAnimation = gltf.userData.vrmAnimations && gltf.userData.vrmAnimations[0];
      if (!vrmAnimation) {
        throw new Error('the file has no VRM animation');
      }
      this._stopMixer();
      const clip = createVRMAnimationClip(vrmAnimation, vrm);
      this._mixer = new THREE.AnimationMixer(vrm.scene);
      this._mixer.timeScale = animation.speed;
      this._action = this._mixer.clipAction(clip);
      this._action.setLoop(animation.loop ? THREE.LoopRepeat : THREE.LoopOnce, Infinity);
      this._action.clampWhenFinished = true;
      this._mixer.addEventListener('finished', () => this.dispatchEvent(new CustomEvent('animation-finished')));
      this._action.play();
      this.dispatchEvent(new CustomEvent('animation-started', {detail: {duration: Number(clip.duration.toFixed(3))}}));
    } catch (e) {
      if (id === this._animationId) {
        this.dispatchEvent(new CustomEvent('animation-error', {detail: {message: String(e && e.message || e)}}));
      }
    }
  }

  /** Drops the mixer of the clip and puts the model back at rest. */
  _stopMixer() {
    if (this._mixer) {
      this._mixer.stopAllAction();
      this._mixer.uncacheRoot(this._mixer.getRoot());
      this._mixer = null;
      this._action = null;
    }
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

  /** Sets toggles of the model shown: a JSON array of {mesh, key (null for the mesh itself), hides, shown}. */
  setToggles(togglesJson) {
    for (const toggle of togglesJson ? JSON.parse(togglesJson) : []) {
      this._setToggle(toggle, toggle.shown);
    }
  }

  /**
   * Renders the snapshots of the model and POSTs each to the URL of the "snapshot-target" attribute, named after its
   * shot: the model put in its default state, the shots of SHOTS (768 × 1024 JPEGs on a plain background), then for
   * each part a close-up per state (768 × 768, "part-<index>-<state index>"). The toggles are put back as they were.
   * Fires "snapshots-taken" ({count}) when the server has them, "snapshots-error" ({message}) when not.
   *
   * @param requestJson {base: [{mesh, key, hides, shown}] (the default state of every toggle), parts: [{index,
   *                    toggles: [{mesh, key, hides}], states: [[shown of each toggle]]}]}
   */
  async takeSnapshots(requestJson) {
    try {
      if (!this._model || !this._renderer) {
        throw new Error('No model is shown');
      }
      const request = requestJson ? JSON.parse(requestJson) : {};
      const base = request.base || [];
      const parts = request.parts || [];
      const saved = this._saveToggles(base.concat(...parts.map(part => part.toggles)));
      // the snapshots show her at rest, even while she sways
      this._swayPose(0);
      let images;
      try {
        base.forEach(toggle => this._setToggle(toggle, toggle.shown));
        images = this._snapshotSetup(768, 1024, camera => this._renderShots(camera));
        images.push(...this._snapshotSetup(768, 768, camera => this._renderParts(camera, parts, base)));
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
    const byDefault = new Map(base.map(toggle => [toggle.mesh + '/' + (toggle.key || ''), toggle.shown]));
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
      try {
        part.states.forEach((state, index) => {
          part.toggles.forEach((toggle, i) => this._setToggle(toggle, state[i]));
          images.push(this._shoot(camera, 'part-' + part.index + '-' + index, target, distance, angle));
        });
      } finally {
        part.toggles.forEach(toggle => {
          const shown = byDefault.get(toggle.mesh + '/' + (toggle.key || ''));
          this._setToggle(toggle, shown === undefined ? true : shown);
        });
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
