import * as THREE from "three";
import {GLTFLoader} from "three/addons/loaders/GLTFLoader.js";
import {VRButton} from "three/addons/webxr/VRButton.js";
import {VRMLoaderPlugin, VRMUtils} from "@pixiv/three-vrm";
import {LookingGlassConfig, LookingGlassWebXRPolyfill} from "@lookingglass/webxr";

const params = new URLSearchParams(location.search);
const MODEL = params.get("model") ?? "/@fs/Users/enerccio/src/self/LLM/joi/tmp/ryuu/ryuu.vrm";
const hud = document.getElementById("hud");

const pageId = Math.random().toString(36).slice(2, 7);

function report(data) {
  const body = { page: pageId, browser: navigator.userAgent.match(/(Chrome|Firefox|Safari)\/[\d.]+/)?.[0], ...data };
  fetch("/__report", { method: "POST", body: JSON.stringify(body) }).catch(() => {});
}

window.addEventListener("error", (e) => report({ event: "jsError", message: String(e.message), at: `${e.filename}:${e.lineno}` }));
window.addEventListener("unhandledrejection", (e) => report({ event: "rejection", message: String(e.reason?.stack ?? e.reason) }));
const consoleError = console.error;
console.error = (...args) => {
  report({ event: "consoleError", message: args.map(String).join(" ").slice(0, 800) });
  consoleError(...args);
};

// --- scene -------------------------------------------------------------------------------------------------------

// three.js uses Chrome's native XRWebGLBinding (WebXR layers) when it exists, and Chrome rejects the XRSession of the
// Looking Glass polyfill, a JS object ("parameter 1 is not of type 'XRSession'"). Without it three.js falls back to
// XRWebGLLayer, which the polyfill implements. three.js checks once, when the renderer is created.
delete window.XRWebGLBinding;

// preserveDrawingBuffer so the canvas can be read back for the diagnostic snapshots
const renderer = new THREE.WebGLRenderer({ antialias: true, preserveDrawingBuffer: true });
renderer.setPixelRatio(window.devicePixelRatio);
renderer.setSize(window.innerWidth, window.innerHeight);
renderer.outputColorSpace = THREE.SRGBColorSpace;
renderer.xr.enabled = true;
document.body.appendChild(renderer.domElement);

const scene = new THREE.Scene();
scene.background = new THREE.Color(0x05060a);
// only the background grid fades: the Looking Glass cameras stand about 1.4 m from her, the normal camera 1.15 m
scene.fog = new THREE.Fog(0x05060a, 2.6, 7);

const camera = new THREE.PerspectiveCamera(22, window.innerWidth / window.innerHeight, 0.05, 20);

scene.add(new THREE.AmbientLight(0x8899bb, 0.9));
const key = new THREE.DirectionalLight(0xffffff, 1.6);
key.position.set(0.6, 1.8, 1.4);
scene.add(key);
const rimCyan = new THREE.DirectionalLight(0x22e6ff, 1.4);
rimCyan.position.set(-1.2, 1.6, -1.0);
scene.add(rimCyan);
const rimMagenta = new THREE.DirectionalLight(0xff2bd6, 1.2);
rimMagenta.position.set(1.2, 1.4, -1.0);
scene.add(rimMagenta);

// a receding cyber grid behind her: depth for the light field
const grid = new THREE.GridHelper(6, 36, 0x0fd4ff, 0x123a4a);
grid.rotation.x = Math.PI / 2;
grid.position.set(0, 1.2, -1.2);
scene.add(grid);

// --- avatar ------------------------------------------------------------------------------------------------------

let vrm = null;
const expressionNames = [];

const loader = new GLTFLoader();
loader.register((parser) => new VRMLoaderPlugin(parser));
const t0 = performance.now();
loader.load(
  MODEL,
  (gltf) => {
    vrm = gltf.userData.vrm;
    VRMUtils.removeUnnecessaryVertices(gltf.scene);
    VRMUtils.combineSkeletons(gltf.scene);
    VRMUtils.rotateVRM0(vrm);
    vrm.scene.traverse((o) => (o.frustumCulled = false));
    scene.add(vrm.scene);

    relaxArms();
    vrm.update(0);
    frameBust();
    if (vrm.lookAt) vrm.lookAt.target = camera;

    // ?mat=toon|lambert|unlit keeps a cheaper material on, to judge its look on the Go
    if (params.get("mat")) applyBench({ swap: params.get("mat") }, new Map());
    for (const name of Object.keys(vrm.expressionManager?.expressionMap ?? {})) expressionNames.push(name);
    let tris = 0;
    vrm.scene.traverse((o) => {
      if (o.isMesh) tris += (o.geometry.index ? o.geometry.index.count : o.geometry.attributes.position.count) / 3;
    });
    const info = {
      event: "loaded",
      seconds: +((performance.now() - t0) / 1000).toFixed(1),
      triangles: Math.round(tris),
      expressions: expressionNames,
      springJoints: vrm.springBoneManager ? vrm.springBoneManager.joints.size : 0,
      meta: vrm.meta?.name,
    };
    report(info);
    console.log(info);
  },
  (p) => (hud.textContent = `loading ${((p.loaded / (p.total || 1)) * 100).toFixed(0)}%`),
  (e) => {
    hud.textContent = "load failed: " + e;
    report({ event: "error", error: String(e) });
  },
);

function bone(name) {
  return vrm.humanoid.getNormalizedBoneNode(name);
}

/** From the T-pose of the rig to arms hanging down. */
function relaxArms() {
  bone("leftUpperArm").rotation.z = -1.15;
  bone("rightUpperArm").rotation.z = 1.15;
  bone("leftLowerArm").rotation.z = -0.15;
  bone("rightLowerArm").rotation.z = 0.15;
}

const target = new THREE.Vector3();

/** Head and shoulders, the framing of a portrait display. */
function frameBust() {
  bone("head").getWorldPosition(target);
  target.y -= 0.08;
  camera.position.set(0, target.y + 0.02, target.z + 1.15);
  camera.lookAt(target);
  updateLookingGlass();
}

// --- Looking Glass -----------------------------------------------------------------------------------------------

const VIEW_DIAMETER = 0.55; // metres of scene the Go shows: head and shoulders
const cols = params.get("cols");
const rows = params.get("rows");
// ?quilt=3072: a smaller quilt than the Go's default 4092x4092, fewer pixels to shade 66 times
const quilt = params.get("quilt");
new LookingGlassWebXRPolyfill({
  targetY: 1.35,
  targetZ: 0,
  targetDiam: VIEW_DIAMETER,
  // tuned by eye on the Go (2026-11-17): fov 23°, depthiness 0.47, gaussian sigma -0.14, 2-view filtering
  fovy: (23 * Math.PI) / 180,
  depthiness: 0.47,
  gaussianSigma: -0.14,
  // the page's own canvas shows a single centered view while presenting (InlineView.Center); 2 = the raw quilt
  inlineView: 1,
  ...(cols && rows ? { columns: +cols, rows: +rows } : {}),
  ...(quilt ? { quiltResolution: { width: +quilt, height: +quilt } } : {}),
});

function snapshot(name, canvas) {
  if (!canvas) {
    report({ event: "snapshot", name, missing: true });
    return;
  }
  try {
    const small = document.createElement("canvas");
    const scale = Math.min(1, 1024 / Math.max(canvas.width, canvas.height));
    small.width = Math.round(canvas.width * scale);
    small.height = Math.round(canvas.height * scale);
    small.getContext("2d").drawImage(canvas, 0, 0, small.width, small.height);
    fetch(`/__snapshot?name=${name}`, { method: "POST", body: small.toDataURL("image/png") });
    report({ event: "snapshot", name, width: canvas.width, height: canvas.height });
  } catch (e) {
    report({ event: "snapshot", name, error: String(e) });
  }
}

/** The page canvas (the quilt) and the canvas in the Looking Glass popup (the interlaced image on the Go). */
function snapshots() {
  const cfg = LookingGlassConfig;
  const popup = cfg.popup;
  snapshot("page", renderer.domElement);
  const popupCanvas = popup && !popup.closed ? popup.document.querySelector("canvas") : null;
  report({
    event: "popupState",
    popup: !!popup,
    closed: popup?.closed ?? null,
    popupScreen: popup ? { x: popup.screenX, y: popup.screenY, w: popup.innerWidth, h: popup.innerHeight } : null,
    canvases: popup ? popup.document.querySelectorAll("canvas").length : 0,
    target: { x: cfg.targetX, y: cfg.targetY, z: cfg.targetZ, diam: cfg.targetDiam },
  });
  snapshot("popup", popupCanvas);
}
window.addEventListener("keydown", (e) => e.key === "p" && snapshots());

// --- benchmark: B while presenting runs the steps below, 5 s each, and reports the fps of each ----------------------

let frameCounter = 0;
// first run (2026-11-17, 66 views): default 15, quilt 3072 17.5, quilt 2048 22, basic materials 46, no avatar 58-60.
// Column / row changes are ignored by the library at runtime, so view counts are not stepped here.
// Second run: 3 lights + normal maps 15.5, 1 light 16, no normal maps 17, both 18, both + quilt 3072 21, basic 46:
// the MToon shader itself is the cost. This round tries cheaper materials that keep the texture.
const BENCH_STEPS = [
  { name: "MToon (as is)" },
  { name: "toon material + texture", swap: "toon" },
  { name: "lambert + texture", swap: "lambert" },
  { name: "unlit texture", swap: "unlit" },
  { name: "toon material + texture, 1 light", swap: "toon", oneLight: true },
  { name: "MToon again" },
];

const toonGradient = (() => {
  // three bands: shadow, mid, lit
  const t = new THREE.DataTexture(new Uint8Array([90, 90, 90, 255, 170, 170, 170, 255, 255, 255, 255, 255]), 3, 1);
  t.minFilter = t.magFilter = THREE.NearestFilter;
  t.needsUpdate = true;
  return t;
})();
const swapCache = new Map();

/** A cheaper material with the same texture, alpha and sides as the MToon one. */
function cheapMaterial(m, kind) {
  const key = m.uuid + kind;
  if (swapCache.has(key)) return swapCache.get(key);
  const common = {
    map: m.map ?? null,
    color: m.color ?? new THREE.Color(1, 1, 1),
    transparent: m.transparent,
    alphaTest: m.alphaTest,
    side: m.side,
    depthWrite: m.depthWrite,
  };
  const made =
    kind === "toon" ? new THREE.MeshToonMaterial({ ...common, gradientMap: toonGradient })
    : kind === "lambert" ? new THREE.MeshLambertMaterial(common)
    : new THREE.MeshBasicMaterial(common);
  swapCache.set(key, made);
  return made;
}

const normalMaps = new Map();

function applyBench(step, originals) {
  const q = step.quilt ?? 4092;
  LookingGlassConfig.quiltResolution = { width: q, height: q };
  vrm.scene.visible = !step.hide;
  rimCyan.visible = rimMagenta.visible = !step.oneLight;
  vrm.scene.traverse((o) => {
    if (!o.isMesh) return;
    if (!originals.has(o)) originals.set(o, o.material);
    const original = originals.get(o);
    for (const m of Array.isArray(original) ? original : [original]) {
      if (!normalMaps.has(m)) normalMaps.set(m, m.normalMap ?? null);
      const want = step.noNormals ? null : normalMaps.get(m);
      if (m.normalMap !== want) {
        m.normalMap = want;
        m.needsUpdate = true;
      }
    }
    if (step.basic) o.material = new THREE.MeshBasicMaterial({ color: 0x9999aa });
    else if (step.swap) o.material = Array.isArray(original) ? original.map((m) => cheapMaterial(m, step.swap)) : cheapMaterial(original, step.swap);
    else o.material = original;
  });
}

let benchRunning = false;
let benchStatus = ""; // shown in the HUD from the start of a run until the next one

async function benchmark() {
  if (!renderer.xr.isPresenting || !vrm || benchRunning) return;
  benchRunning = true;
  const originals = new Map();
  const results = [];
  for (const [i, step] of BENCH_STEPS.entries()) {
    benchStatus = `BENCHMARK ${i + 1}/${BENCH_STEPS.length}: ${step.name}`;
    applyBench(step, originals);
    await new Promise((r) => setTimeout(r, 1500)); // settle (framebuffers are re-created)
    const f0 = frameCounter;
    const t0 = performance.now();
    await new Promise((r) => setTimeout(r, 4000));
    const fps = +(((frameCounter - f0) * 1000) / (performance.now() - t0)).toFixed(1);
    results.push({ step: step.name, fps });
    report({ event: "bench", step: step.name, fps, quilt: `${LookingGlassConfig.quiltWidth}x${LookingGlassConfig.quiltHeight}`, views: LookingGlassConfig.numViews });
  }
  applyBench({}, originals);
  report({ event: "benchDone", results });
  benchStatus = "BENCHMARK DONE: " + results.map((r) => `${r.step} ${r.fps}`).join(" · ");
  benchRunning = false;
}
window.addEventListener("keydown", (e) => e.key === "b" && benchmark());
const vrButton = VRButton.createButton(renderer);
document.body.appendChild(vrButton);

// the library's button is tiny; a big one and the L key do the same (a key press counts as a user gesture)
const bigButton = document.createElement("button");
bigButton.textContent = "ENTER LOOKING GLASS (L)";
bigButton.style.cssText =
  "position:fixed;left:50%;top:50%;transform:translate(-50%,-50%);z-index:3;font:bold 28px ui-monospace,monospace;" +
  "padding:24px 36px;background:#0b1d26;color:#7ff;border:2px solid #2ef;cursor:pointer";
bigButton.onclick = enterLookingGlass;
document.body.appendChild(bigButton);
window.addEventListener("keydown", (e) => e.key === "l" && enterLookingGlass());

async function enterLookingGlass() {
  report({ event: "enterClicked", xr: !!navigator.xr, buttonText: vrButton.textContent });
  try {
    const supported = await navigator.xr?.isSessionSupported("immersive-vr");
    report({ event: "sessionSupported", supported });
    vrButton.click();
  } catch (e) {
    report({ event: "enterFailed", message: String(e?.stack ?? e) });
  }
  setTimeout(() => report({ event: "afterEnter", presenting: renderer.xr.isPresenting, popup: !!LookingGlassConfig.popup, popupClosed: LookingGlassConfig.popup?.closed ?? null }), 1500);
}

renderer.xr.addEventListener("sessionstart", () => {
  bigButton.style.display = "none";
  report({ event: "sessionstart" });
  // the popup can open late (the display permission prompt), so look twice
  setTimeout(snapshots, 4000);
  setTimeout(snapshots, 12000);
  // ?bench=1 runs the benchmark by itself (the B key goes to whichever window has focus, often the popup)
  if (params.has("bench")) setTimeout(benchmark, 5000);
});
renderer.xr.addEventListener("sessionend", () => {
  bigButton.style.display = "";
  report({ event: "sessionend" });
});

function updateLookingGlass() {
  LookingGlassConfig.targetX = target.x;
  LookingGlassConfig.targetY = target.y;
  LookingGlassConfig.targetZ = target.z;
}

/**
 * The library's mouse controls (wheel zooms, drag rotates, on the popup and on the page) move the cameras; a stray
 * scroll sent them 15-80 m away, into the fog. Joi decides the framing, so it is put back every frame.
 */
function lockLookingGlassView() {
  updateLookingGlass();
  LookingGlassConfig.targetDiam = VIEW_DIAMETER;
  LookingGlassConfig.trackballX = 0;
  LookingGlassConfig.trackballY = 0;
}

// --- moods and speech (the state the server would drive) ---------------------------------------------------------

let mood = "neutral";
const extras = new Set();
const moodKeys = { 1: "happy", 2: "angry", 3: "sad", 4: "relaxed", 5: "surprised", 0: "neutral" };
const extraKeys = { b: "blush", s: "smirk", h: "heartEyes", w: "worry", k: "think" };
let talkingUntil = 0;

function setMood(name) {
  mood = name;
}

function toggle(name) {
  extras.has(name) ? extras.delete(name) : extras.add(name);
}

function talk(seconds = 3) {
  talkingUntil = performance.now() + seconds * 1000;
}

window.joi = { setMood, toggle, talk, report, THREE, scene, renderer, get vrm() { return vrm; } };

window.addEventListener("keydown", (e) => {
  if (e.key in moodKeys) setMood(moodKeys[e.key]);
  else if (e.key in extraKeys) toggle(extraKeys[e.key]);
  else if (e.key === "t") talk(3);
});

const VISEMES = ["aa", "ih", "ou", "ee", "oh"];
let viseme = "aa";
let nextViseme = 0;
let nextBlink = 2;
let blinkPhase = -1;
const moodWeights = {};

function animate(time, delta) {
  const em = vrm.expressionManager;
  const t = time / 1000;

  // moods ease in and out instead of snapping
  for (const name of ["happy", "angry", "sad", "relaxed", "surprised", ...Object.values(extraKeys)]) {
    const goal = name === mood || extras.has(name) ? 1 : 0;
    moodWeights[name] = THREE.MathUtils.damp(moodWeights[name] ?? 0, goal, 6, delta);
    if (expressionNames.includes(name)) em.setValue(name, moodWeights[name]);
  }

  // blinking, a quick close and a slower open
  nextBlink -= delta;
  if (nextBlink <= 0 && blinkPhase < 0) blinkPhase = 0;
  if (blinkPhase >= 0) {
    blinkPhase += delta / 0.18;
    em.setValue("blink", Math.sin(Math.min(blinkPhase, 1) * Math.PI) * (1 - (moodWeights.happy ?? 0)));
    if (blinkPhase >= 1) {
      blinkPhase = -1;
      nextBlink = 2 + Math.random() * 4;
    }
  }

  // a fake lip sync: random visemes, what the TTS stream would drive
  const talking = performance.now() < talkingUntil;
  if (talking && time > nextViseme) {
    viseme = VISEMES[Math.floor(Math.random() * VISEMES.length)];
    nextViseme = time + 70 + Math.random() * 90;
  }
  for (const v of VISEMES) {
    const goal = talking && v === viseme ? 0.8 : 0;
    em.setValue(v, THREE.MathUtils.damp(em.getValue(v) ?? 0, goal, 18, delta));
  }

  // breathing and a slight sway
  bone("chest").rotation.x = Math.sin(t * 1.6) * 0.015;
  bone("spine").rotation.z = Math.sin(t * 0.5) * 0.012;
  bone("neck").rotation.y = Math.sin(t * 0.37) * 0.05;
  bone("head").rotation.z = Math.sin(t * 0.29) * 0.03;
}

// --- loop and measurements ---------------------------------------------------------------------------------------

const clock = new THREE.Clock();
let frames = 0;
let lastReport = performance.now();

renderer.setAnimationLoop((time) => {
  const delta = Math.min(clock.getDelta(), 0.1);
  if (vrm) {
    animate(time, delta);
    vrm.update(delta);
  }
  if (renderer.xr.isPresenting) {
    if (vrm) lockLookingGlassView();
    // The Looking Glass polyfill binds its own textures with raw WebGL calls between frames. three.js's state cache
    // does not see that, so the bone texture update (texSubImage2D) landed on the polyfill's texture ("invalid
    // combination of format, type and internalFormat"), the bones stayed zero and every skinned mesh vanished while
    // unskinned ones rendered. Forget the cached state each frame, but keep the XR render target three.js has bound.
    const xrTarget = renderer.getRenderTarget();
    renderer.resetState();
    renderer.setRenderTarget(xrTarget);
  }
  renderer.render(scene, camera);

  frameCounter++;
  frames++;
  const now = performance.now();
  if (now - lastReport >= 2000) {
    const fps = (frames * 1000) / (now - lastReport);
    const presenting = renderer.xr.isPresenting;
    const quilt = presenting ? `${LookingGlassConfig.quiltWidth}x${LookingGlassConfig.quiltHeight} ${LookingGlassConfig.numViews} views` : "-";
    hud.textContent = `fps ${fps.toFixed(1)}\nlooking glass ${presenting ? "ON" : "off"}  quilt ${quilt}\nmood ${mood}  ${[...extras].join(" ")}`
      + (benchStatus ? `\n${benchStatus.replaceAll(" · ", "\n")}` : "");
    report({ event: "fps", fps: +fps.toFixed(1), presenting, quilt });
    frames = 0;
    lastReport = now;
  }
});

window.addEventListener("resize", () => {
  camera.aspect = window.innerWidth / window.innerHeight;
  camera.updateProjectionMatrix();
  renderer.setSize(window.innerWidth, window.innerHeight);
});
