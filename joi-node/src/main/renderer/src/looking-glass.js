// Her body for a Looking Glass: each frame the scene is rendered from a row of cameras (her views, left to right,
// all aimed at one plane, the focus) into the tiles of one texture, the quilt; then the quilt is interleaved for the
// display's lenses, each subpixel taking the view its lens shows from there. The calibration is the display's own,
// from Looking Glass Bridge through the node (looking-glass.json: {calibration, quilt: {width, height, columns, rows},
// tuning}); see DESIGN.md → Avatar and Looking Glass.
//
// The tuning (the node's tuning panel, sent again whenever it changes; LookingGlassTuning.java):
//   depthiness how much of the display's view cone her views span (1 is all of it): less is less depth, and sharper
//   focus      where the views meet, in metres from the point the camera looks at (+ is farther): that plane is sharp
//   zoom       how much bigger she is shown than in the window
//   center     added to the calibration's center (where the first lens starts), in views / all views
//   pitch      the calibration's pitch times this (the lenses across the screen)
//   invert     the views the other way round; flipY the lenses read from the top down
//   quilt      the quilt's width and height in pixels (fewer pixels are faster, softer)
//   fps        the most frames a second (her views cost the GPU: less leaves more for the rest)
//   view       hologram, quilt (the quilt as it is) or center (the middle view only), to check the views
import * as THREE from 'three';

export const DEFAULT_TUNING = {
  // tuned by eye on the Go with the test avatar
  depthiness: 0.27, focus: -0.35, zoom: 0.97, center: -0.003, pitch: 1, invert: false, flipY: false, quilt: 4092, fps: 20,
  view: 'hologram'
};

const VIEWS = {hologram: 0, quilt: 1, center: 2};

const VERTEX = `
void main() {
  gl_Position = vec4(position.xy, 0.0, 1.0);
}`;

// for each subpixel, which view its lens shows (along the slanted lenses, shifted by the calibration's center),
// read from that view's tile of the quilt
const FRAGMENT = `
uniform sampler2D quilt;
uniform vec2 resolution;
uniform vec2 tiles;
uniform vec2 portion;
uniform float views;
uniform float pitch;
uniform float tilt;
uniform float center;
uniform float subpixel;
uniform float inverted;
uniform float flipY;
uniform int mode;

vec2 tile(vec2 uv, float view) {
  view = clamp(view, 0.0, views - 1.0);
  float column = mod(view, tiles.x);
  float row = floor(view / tiles.x);
  return vec2((column + uv.x) / tiles.x, (row + uv.y) / tiles.y) * portion;
}

void main() {
  vec2 uv = gl_FragCoord.xy / resolution;
  if (mode == 1) {
    gl_FragColor = vec4(texture2D(quilt, uv * portion).rgb, 1.0);
  } else if (mode == 2) {
    gl_FragColor = vec4(texture2D(quilt, tile(uv, floor(views / 2.0))).rgb, 1.0);
  } else {
    vec2 lens = vec2(uv.x, mix(uv.y, 1.0 - uv.y, flipY));
    vec3 color;
    for (int i = 0; i < 3; i++) {
      float position = fract((lens.x + float(i) * subpixel + lens.y * tilt) * pitch - center);
      position = mix(position, 1.0 - position, inverted);
      color[i] = texture2D(quilt, tile(uv, floor(position * views)))[i];
    }
    gl_FragColor = vec4(color, 1.0);
  }
  #include <colorspace_fragment>
}`;

function value(calibration, name, fallback) {
  const entry = calibration[name];
  if (entry === undefined || entry === null) {
    return fallback;
  }
  return typeof entry === 'object' ? entry.value : entry;
}

/** A renderFrame for <joi-model-viewer> that renders for the Looking Glass of the config; tune(tuning) changes it. */
export function lookingGlassRenderer(config) {
  const calibration = config.calibration;
  const screenWidth = value(calibration, 'screenW', 1440);
  const screenHeight = value(calibration, 'screenH', 2560);
  const slope = value(calibration, 'slope', -5);
  const flipped = value(calibration, 'flipImageX', 0) ? -1 : 1;
  const columns = config.quilt.columns;
  const rows = config.quilt.rows;
  const views = columns * rows;
  const aspect = screenWidth / screenHeight;
  const viewCone = THREE.MathUtils.degToRad(value(calibration, 'viewCone', 40));
  const basePitch = value(calibration, 'pitch', 50) * screenWidth / value(calibration, 'DPI', 300) * Math.cos(Math.atan(1 / slope));
  const baseCenter = value(calibration, 'center', 0);
  const baseInverted = value(calibration, 'invView', 1) ? 1 : 0;
  // the quilt's shape is the display's (as Bridge suggests it); its size is tuned
  const quiltAspect = config.quilt.height / config.quilt.width;

  let tuning = {...DEFAULT_TUNING, ...(config.tuning || {})};
  let tileWidth = 0;
  let tileHeight = 0;

  // sRGB storage: her colors keep their precision in the dark, and are read back linear
  const quilt = new THREE.WebGLRenderTarget(1, 1, {
    colorSpace: THREE.SRGBColorSpace,
    minFilter: THREE.LinearFilter,
    magFilter: THREE.LinearFilter,
    generateMipmaps: false
  });
  quilt.scissorTest = true;

  const material = new THREE.ShaderMaterial({
    vertexShader: VERTEX,
    fragmentShader: FRAGMENT,
    depthTest: false,
    depthWrite: false,
    uniforms: {
      quilt: {value: quilt.texture},
      resolution: {value: new THREE.Vector2(screenWidth, screenHeight)},
      tiles: {value: new THREE.Vector2(columns, rows)},
      portion: {value: new THREE.Vector2(1, 1)},
      views: {value: views},
      // the lenses: how many lie across the screen, how slanted they are, and where the first starts
      pitch: {value: basePitch},
      tilt: {value: screenHeight / (screenWidth * slope) * flipped},
      center: {value: baseCenter},
      subpixel: {value: 1 / (3 * screenWidth) * flipped},
      inverted: {value: baseInverted},
      flipY: {value: 0},
      mode: {value: 0}
    }
  });
  const screen = new THREE.Scene();
  screen.add(new THREE.Mesh(new THREE.PlaneGeometry(2, 2), material));
  const flat = new THREE.OrthographicCamera(-1, 1, 1, -1, 0, 1);

  function tune(next) {
    tuning = {...DEFAULT_TUNING, ...next};
    const width = Math.max(256, Math.round(tuning.quilt));
    const height = Math.max(256, Math.round(width * quiltAspect));
    tileWidth = Math.floor(width / columns);
    tileHeight = Math.floor(height / rows);
    if (quilt.width !== width || quilt.height !== height) {
      quilt.setSize(width, height);
    }
    const u = material.uniforms;
    u.portion.value.set(columns * tileWidth / width, rows * tileHeight / height);
    u.pitch.value = basePitch * tuning.pitch;
    u.center.value = baseCenter + tuning.center;
    u.inverted.value = tuning.invert ? 1 - baseInverted : baseInverted;
    u.flipY.value = tuning.flipY ? 1 : 0;
    u.mode.value = VIEWS[tuning.view] ?? 0;
  }

  tune(tuning);

  const view = new THREE.PerspectiveCamera();
  const size = new THREE.Vector2();
  const background = new THREE.Color();
  let last = 0;

  const render = (renderer, scene, camera, target) => {
    const now = performance.now();
    if (now - last < 1000 / Math.max(1, tuning.fps) - 2) {
      // the frame shown stays
      return;
    }
    last = now;
    camera.updateMatrixWorld();
    const distance = Math.max(camera.position.distanceTo(target) + tuning.focus, 0.01);
    const near = camera.near;
    const top = near * Math.tan(THREE.MathUtils.degToRad(camera.fov) / 2) / Math.max(0.05, tuning.zoom);
    const right = top * aspect;
    const cone = viewCone * tuning.depthiness;
    const clearColor = renderer.getClearColor(background).clone();
    const clearAlpha = renderer.getClearAlpha();
    renderer.setClearColor(0x000000, 1);
    // the center view alone needs only its own tile
    const only = tuning.view === 'center' ? Math.floor(views / 2) : -1;
    for (let i = 0; i < views; i++) {
      if (only >= 0 && i !== only) {
        continue;
      }
      // the view moves along the camera's right and turns its frustum back, so all of them meet at the focus
      const angle = Math.tan(cone * ((i + 0.5) / views - 0.5));
      view.position.copy(camera.position);
      view.quaternion.copy(camera.quaternion);
      view.translateX(distance * angle);
      view.updateMatrixWorld();
      const shift = -near * angle;
      view.projectionMatrix.makePerspective(shift - right, shift + right, top, -top, near, camera.far);
      view.projectionMatrixInverse.copy(view.projectionMatrix).invert();
      const x = (i % columns) * tileWidth;
      const y = Math.floor(i / columns) * tileHeight;
      quilt.viewport.set(x, y, tileWidth, tileHeight);
      quilt.scissor.set(x, y, tileWidth, tileHeight);
      renderer.setRenderTarget(quilt);
      renderer.render(scene, view);
    }
    renderer.setRenderTarget(null);
    renderer.setClearColor(clearColor, clearAlpha);
    renderer.getDrawingBufferSize(size);
    material.uniforms.resolution.value.copy(size);
    renderer.render(screen, flat);
  };
  render.tune = tune;
  return render;
}
