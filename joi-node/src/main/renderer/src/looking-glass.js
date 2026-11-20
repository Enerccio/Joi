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
//   rendered   how many of her views are rendered in full (0 for all of them): the others are made from the two
//              nearest of those by their depth (MADE_FRAGMENT); fewer is faster, but what the rendered views don't
//              see is missing (black), transparent parts that write no depth are lost where nothing is behind them,
//              and the light doesn't change between them
//   view       hologram, quilt (the quilt as it is) or center (the middle view only), to check the views
import * as THREE from 'three';

export const DEFAULT_TUNING = {
  // tuned by eye on the Go with the test avatar
  depthiness: 0.27, focus: -0.35, zoom: 0.97, center: -0.003, pitch: 1, invert: false, flipY: false, quilt: 4092, fps: 20,
  rendered: 5, view: 'hologram'
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

// a rendered view's depth: each texel's distance along the camera (r) and whether it is her (g), not the background
const POINTS_FRAGMENT = `
uniform sampler2D depth;
uniform mat4 unproject;
varying vec2 vUv;

void main() {
  float d = texture2D(depth, vUv).r;
  vec4 p = unproject * vec4(vUv * 2.0 - 1.0, d * 2.0 - 1.0, 1.0);
  gl_FragColor = vec4(-p.z / p.w, d < 1.0 ? 1.0 : 0.0, 0.0, 1.0);
}`;

// how deep each row of a rendered view goes: the nearest (r) and farthest (g) distance of her texels in it; r is 1e20
// in a row without her
const ROWS_FRAGMENT = `
uniform sampler2D points;
uniform float width;

void main() {
  int row = int(gl_FragCoord.y);
  float nearest = 1e20;
  float farthest = 0.0;
  for (int x = 0; x < int(width); x++) {
    vec2 p = texelFetch(points, ivec2(x, row), 0).rg;
    if (p.g > 0.5) {
      nearest = min(nearest, p.r);
      farthest = max(farthest, p.r);
    }
  }
  gl_FragColor = vec4(nearest, farthest, 0.0, 1.0);
}`;

// a view made from the rendered ones. The views differ only by moving along the camera's right, each turned back to
// the focus: a texel at distance z of a rendered view is seen in a made one shift * (1 - focus / z) texels further
// along its row (shift: how far apart the two views are). So each pixel looks along its row, over the texels that
// the depth of that row can move onto it, for the span between two neighbouring texels that lands on it (two texels
// at about the same depth are one surface; a jump in depth between them is an edge, not filled), and keeps the
// nearest; where the rendered views saw nothing, it stays black
const MADE_FRAGMENT = `
uniform sampler2D pointsA;
uniform sampler2D colorA;
uniform sampler2D rowsA;
uniform float shiftA;
uniform sampler2D pointsB;
uniform sampler2D colorB;
uniform sampler2D rowsB;
uniform float shiftB;
uniform float useB;
uniform float focus;
uniform float tear;
uniform vec2 size;
varying vec2 vUv;

void look(sampler2D points, sampler2D color, sampler2D rows, float shift, inout float nearest, inout vec3 found) {
  float x = vUv.x * size.x - 0.5;
  int row = int(vUv.y * size.y);
  vec2 depth = texelFetch(rows, ivec2(0, row), 0).rg;
  if (depth.r > 1e19) {
    return;
  }
  // the texels of this row that move onto x: from those moved the most to those moved the least
  float near = shift * (1.0 - focus / depth.r);
  float far = shift * (1.0 - focus / depth.g);
  int last = int(size.x) - 1;
  int start = clamp(int(floor(x - max(near, far))) - 1, 0, last);
  int end = clamp(int(floor(x - min(near, far))) + 1, 0, last);
  vec2 a = texelFetch(points, ivec2(start, row), 0).rg;
  float at = float(start) + shift * (1.0 - focus / a.r);
  for (int i = start; i <= end; i++) {
    vec2 b = texelFetch(points, ivec2(min(i + 1, last), row), 0).rg;
    float bt = float(i + 1) + shift * (1.0 - focus / b.r);
    if (a.g > 0.5) {
      float z = a.r;
      float t = -1.0;
      if (abs(x - at) <= 0.5) {
        t = 0.0;
      }
      if (b.g > 0.5 && abs(a.r - b.r) < tear * a.r && x >= min(at, bt) && x <= max(at, bt) && abs(bt - at) > 1e-4) {
        t = (x - at) / (bt - at);
        z = mix(a.r, b.r, t);
      }
      if (t >= 0.0 && z < nearest) {
        nearest = z;
        found = mix(texelFetch(color, ivec2(i, row), 0).rgb, texelFetch(color, ivec2(min(i + 1, last), row), 0).rgb, t);
      }
    }
    a = b;
    at = bt;
  }
}

void main() {
  float nearest = 1e20;
  vec3 found = vec3(0.0);
  look(pointsA, colorA, rowsA, shiftA, nearest, found);
  if (useB > 0.5) {
    look(pointsB, colorB, rowsB, shiftB, nearest, found);
  }
  gl_FragColor = vec4(found, 1.0);
}`;

// a rendered view copied into its tile as it is
const COPY_VERTEX = `
varying vec2 vUv;

void main() {
  vUv = position.xy * 0.5 + 0.5;
  gl_Position = vec4(position.xy, 0.0, 1.0);
}`;

const COPY_FRAGMENT = `
uniform sampler2D color;
varying vec2 vUv;

void main() {
  gl_FragColor = vec4(texture2D(color, vUv).rgb, 1.0);
}`;

// the jump in depth between neighbouring texels (a part of their distance) that is an edge, not a surface;
// tuning.tear overrides it, to try
const TEAR = 0.03;

/** The views rendered in full when only some are: spread from the first to the last, so the others lie between two. */
export function renderedViews(views, rendered) {
  if (!rendered || rendered >= views) {
    return null;
  }
  if (rendered === 1) {
    return [Math.floor(views / 2)];
  }
  const chosen = [];
  for (let k = 0; k < rendered; k++) {
    chosen.push(Math.round(k * (views - 1) / (rendered - 1)));
  }
  return chosen;
}

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
    prepare();
  }

  // the views rendered in full when the others are made from them: each with its own color and depth
  let sources = [];
  let sourceKey = '';
  const copy = new THREE.ShaderMaterial({
    vertexShader: COPY_VERTEX,
    fragmentShader: COPY_FRAGMENT,
    depthTest: false,
    depthWrite: false,
    uniforms: {color: {value: null}}
  });
  const pointsMaterial = new THREE.ShaderMaterial({
    vertexShader: COPY_VERTEX,
    fragmentShader: POINTS_FRAGMENT,
    depthTest: false,
    depthWrite: false,
    uniforms: {depth: {value: null}, unproject: {value: new THREE.Matrix4()}}
  });
  const made = new THREE.ShaderMaterial({
    vertexShader: COPY_VERTEX,
    fragmentShader: MADE_FRAGMENT,
    depthTest: false,
    depthWrite: false,
    uniforms: {
      pointsA: {value: null},
      colorA: {value: null},
      rowsA: {value: null},
      shiftA: {value: 0},
      pointsB: {value: null},
      colorB: {value: null},
      rowsB: {value: null},
      shiftB: {value: 0},
      useB: {value: 0},
      focus: {value: 1},
      tear: {value: TEAR},
      size: {value: new THREE.Vector2()}
    }
  });
  const rowsMaterial = new THREE.ShaderMaterial({
    vertexShader: COPY_VERTEX,
    fragmentShader: ROWS_FRAGMENT,
    depthTest: false,
    depthWrite: false,
    uniforms: {points: {value: null}, width: {value: 1}}
  });
  const quadScene = new THREE.Scene();
  const quad = new THREE.Mesh(new THREE.PlaneGeometry(2, 2), copy);
  quadScene.add(quad);

  function prepare() {
    const chosen = renderedViews(views, tuning.rendered) || [];
    made.uniforms.size.value.set(tileWidth, tileHeight);
    made.uniforms.tear.value = tuning.tear || TEAR;
    rowsMaterial.uniforms.width.value = tileWidth;
    const key = chosen.join(',') + '@' + tileWidth + 'x' + tileHeight;
    if (key === sourceKey) {
      return;
    }
    sourceKey = key;
    sources.forEach(source => {
      source.target.depthTexture.dispose();
      source.target.dispose();
      source.points.dispose();
      source.rows.dispose();
    });
    sources = chosen.map(index => {
      const target = new THREE.WebGLRenderTarget(tileWidth, tileHeight, {
        colorSpace: THREE.SRGBColorSpace,
        minFilter: THREE.NearestFilter,
        magFilter: THREE.NearestFilter,
        generateMipmaps: false,
        depthTexture: new THREE.DepthTexture(tileWidth, tileHeight, THREE.FloatType)
      });
      const points = new THREE.WebGLRenderTarget(tileWidth, tileHeight, {
        type: THREE.FloatType,
        minFilter: THREE.NearestFilter,
        magFilter: THREE.NearestFilter,
        generateMipmaps: false,
        depthBuffer: false
      });
      const rows = new THREE.WebGLRenderTarget(1, tileHeight, {
        type: THREE.FloatType,
        minFilter: THREE.NearestFilter,
        magFilter: THREE.NearestFilter,
        generateMipmaps: false,
        depthBuffer: false
      });
      return {index, target, points, rows, camera: new THREE.PerspectiveCamera()};
    });
  }

  /** Draws the quad into the target set, with the material. */
  function drawQuad(renderer, with_) {
    quad.material = with_;
    renderer.render(quadScene, flat);
  }

  const view = new THREE.PerspectiveCamera();
  const size = new THREE.Vector2();
  const background = new THREE.Color();
  let last = 0;
  tune(tuning);

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
    // view i: moved along the camera's right, its frustum turned back, so all of them meet at the focus
    const angleOf = i => Math.tan(cone * ((i + 0.5) / views - 0.5));
    const place = (camera_, i) => {
      const angle = angleOf(i);
      camera_.position.copy(camera.position);
      camera_.quaternion.copy(camera.quaternion);
      camera_.translateX(distance * angle);
      camera_.updateMatrixWorld();
      const shift = -near * angle;
      camera_.projectionMatrix.makePerspective(shift - right, shift + right, top, -top, near, camera.far);
      camera_.projectionMatrixInverse.copy(camera_.projectionMatrix).invert();
    };
    const tileOf = i => {
      const x = (i % columns) * tileWidth;
      const y = Math.floor(i / columns) * tileHeight;
      quilt.viewport.set(x, y, tileWidth, tileHeight);
      quilt.scissor.set(x, y, tileWidth, tileHeight);
      renderer.setRenderTarget(quilt);
    };
    if (tuning.view === 'center') {
      // the center view alone needs only its own tile
      const i = Math.floor(views / 2);
      place(view, i);
      tileOf(i);
      renderer.render(scene, view);
    } else if (!sources.length) {
      for (let i = 0; i < views; i++) {
        place(view, i);
        tileOf(i);
        renderer.render(scene, view);
      }
    } else {
      for (const source of sources) {
        place(source.camera, source.index);
        renderer.setRenderTarget(source.target);
        renderer.render(scene, source.camera);
        pointsMaterial.uniforms.depth.value = source.target.depthTexture;
        pointsMaterial.uniforms.unproject.value.copy(source.camera.projectionMatrixInverse);
        renderer.setRenderTarget(source.points);
        drawQuad(renderer, pointsMaterial);
        rowsMaterial.uniforms.points.value = source.points.texture;
        renderer.setRenderTarget(source.rows);
        drawQuad(renderer, rowsMaterial);
      }
      const autoClear = renderer.autoClear;
      renderer.autoClear = false;
      // a texel moves shift * (1 - focus / z) texels between views i and j
      const shift = (i, j) => near / (2 * right) * (angleOf(i) - angleOf(j)) * tileWidth;
      const u = made.uniforms;
      u.focus.value = distance;
      let next = 0;
      for (let i = 0; i < views; i++) {
        while (next < sources.length && sources[next].index < i) {
          next++;
        }
        tileOf(i);
        const exact = sources[next] && sources[next].index === i ? sources[next] : null;
        if (exact) {
          copy.uniforms.color.value = exact.target.texture;
          drawQuad(renderer, copy);
          continue;
        }
        // from the nearest rendered view on each side; the nearer surface of the two is kept
        const from = [sources[next - 1], sources[next]].filter(Boolean);
        u.pointsA.value = from[0].points.texture;
        u.colorA.value = from[0].target.texture;
        u.rowsA.value = from[0].rows.texture;
        u.shiftA.value = shift(i, from[0].index);
        u.useB.value = from.length > 1 ? 1 : 0;
        if (from.length > 1) {
          u.pointsB.value = from[1].points.texture;
          u.colorB.value = from[1].target.texture;
          u.rowsB.value = from[1].rows.texture;
          u.shiftB.value = shift(i, from[1].index);
        }
        drawQuad(renderer, made);
      }
      renderer.autoClear = autoClear;
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
