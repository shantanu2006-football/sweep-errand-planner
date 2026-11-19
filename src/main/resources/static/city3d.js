/* Sweep 3D city. Pure presentation: draws the server's plan in a procedural Powai.
   1 world unit = 1 km. Grid (x east, y south) maps to world (X = x - 4, Z = y - 3). */
import * as THREE from "./vendor/three.module.min.js";

const W = (x, y, h = 0) => new THREE.Vector3(x - 4, h, y - 3);

function rng(seed) { let s = seed >>> 0; return () => ((s = (s * 1664525 + 1013904223) >>> 0) / 4294967296); }
function inPoly(x, y, poly) {
  let inside = false;
  for (let i = 0, j = poly.length - 1; i < poly.length; j = i++) {
    const [xi, yi] = poly[i], [xj, yj] = poly[j];
    if ((yi > y) !== (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside;
  }
  return inside;
}
function segDist(px, py, [ax, ay], [bx, by]) {
  const dx = bx - ax, dy = by - ay, L = dx * dx + dy * dy;
  const t = L ? Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / L)) : 0;
  return Math.hypot(px - ax - t * dx, py - ay - t * dy);
}
function glowTexture() {
  const c = document.createElement("canvas"); c.width = c.height = 128;
  const g = c.getContext("2d"), grd = g.createRadialGradient(64, 64, 0, 64, 64, 64);
  grd.addColorStop(0, "rgba(255,255,255,1)"); grd.addColorStop(.25, "rgba(255,255,255,.55)"); grd.addColorStop(1, "rgba(255,255,255,0)");
  g.fillStyle = grd; g.fillRect(0, 0, 128, 128);
  return new THREE.CanvasTexture(c);
}
function windowTexture() {
  const c = document.createElement("canvas"); c.width = 64; c.height = 128;
  const g = c.getContext("2d"); g.fillStyle = "#000"; g.fillRect(0, 0, 64, 128);
  const r = rng(7);
  for (let y = 6; y < 124; y += 9) for (let x = 5; x < 60; x += 10) {
    const on = r() < .32; g.fillStyle = on ? `rgba(255,${190 + r() * 50 | 0},${110 + r() * 60 | 0},${.7 + r() * .3})` : "rgba(40,60,80,.4)";
    g.fillRect(x, y, 6, 5);
  }
  const t = new THREE.CanvasTexture(c); t.colorSpace = THREE.SRGBColorSpace; return t;
}
function labelSprite(text, { color = "#ffffff", bg = "rgba(18,34,43,.88)", size = 46, bold = 800 } = {}) {
  const c = document.createElement("canvas"), g = c.getContext("2d");
  g.font = `${bold} ${size}px "Bricolage Grotesque", system-ui, sans-serif`;
  const w = Math.ceil(g.measureText(text).width) + size * .9, h = size * 1.5;
  c.width = w; c.height = h;
  g.font = `${bold} ${size}px "Bricolage Grotesque", system-ui, sans-serif`;
  if (bg) { g.fillStyle = bg; g.beginPath(); g.roundRect(0, 0, w, h, h / 2); g.fill(); }
  g.fillStyle = color; g.textAlign = "center"; g.textBaseline = "middle"; g.fillText(text, w / 2, h / 2 + 2);
  const tex = new THREE.CanvasTexture(c); tex.colorSpace = THREE.SRGBColorSpace;
  const s = new THREE.Sprite(new THREE.SpriteMaterial({ map: tex, depthTest: false, transparent: true }));
  s.userData.aspect = w / h; s.renderOrder = 10;
  return s;
}

const PALETTE = {
  light: { sky: 0xE4ECE7, ground: 0xEBF0EC, road: 0xFFFFFF, water: 0x9CC7DA, park: 0xC9DEC4, bldA: [0.93, 0.95, 0.94], bldB: [0.78, 0.83, 0.84],
    hemi: 1.15, sun: 1.6, windows: 0, fog: [12, 26], route: 0x1F4BFF, beam: .35 },
  dark: { sky: 0x08121A, ground: 0x0E1B22, road: 0x243A44, water: 0x10324A, park: 0x12302A, bldA: [0.16, 0.21, 0.25], bldB: [0.09, 0.12, 0.15],
    hemi: .45, sun: .3, windows: .7, fog: [7, 18], route: 0x6D8BFF, beam: .7 }
};

export function createCity(container, opts) {
  const { stores, home: home0, typeColor, roads, lake, parks, labels, dark: dark0, reduced } = opts;
  let dark = !!dark0, home = { ...home0 };
  const P = () => PALETTE[dark ? "dark" : "light"];

  const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: false, powerPreference: "high-performance" });
  renderer.setPixelRatio(Math.min(devicePixelRatio, 2));
  renderer.shadowMap.enabled = true; renderer.shadowMap.type = THREE.PCFSoftShadowMap;
  renderer.toneMapping = THREE.ACESFilmicToneMapping; renderer.outputColorSpace = THREE.SRGBColorSpace;
  container.appendChild(renderer.domElement);
  renderer.domElement.className = "city3d";

  const scene = new THREE.Scene();
  const camera = new THREE.PerspectiveCamera(42, 4 / 3, 0.05, 100);
  const hemi = new THREE.HemisphereLight(0xffffff, 0x88a0a8, 1);
  const sun = new THREE.DirectionalLight(0xfff2dd, 1.5);
  sun.position.set(-5, 9, -3); sun.castShadow = true;
  sun.shadow.mapSize.set(2048, 2048);
  Object.assign(sun.shadow.camera, { left: -6, right: 6, top: 5, bottom: -5, near: 1, far: 25 });
  sun.shadow.bias = -0.0005;
  scene.add(hemi, sun);

  /* ---------- ground, water, parks, roads */
  const groundMat = new THREE.MeshStandardMaterial({ roughness: 1 });
  const ground = new THREE.Mesh(new THREE.PlaneGeometry(60, 60), groundMat);
  ground.rotation.x = -Math.PI / 2; ground.receiveShadow = true; scene.add(ground);
  const shapeMesh = (pts, mat, h) => {
    const v = pts.map(([x, y]) => new THREE.Vector2(x - 4, -(y - 3)));
    const sh = new THREE.Shape(); sh.moveTo(v[0].x, v[0].y); sh.splineThru([...v.slice(1), v[0]]);
    const m = new THREE.Mesh(new THREE.ShapeGeometry(sh, 24), mat);
    m.rotation.x = -Math.PI / 2; m.position.y = h; m.receiveShadow = true; scene.add(m); return m;
  };
  const waterMat = new THREE.MeshStandardMaterial({ roughness: .15, metalness: .2 });
  const parkMat = new THREE.MeshStandardMaterial({ roughness: 1 });
  shapeMesh(lake, waterMat, 0.003);
  parks.forEach(p => shapeMesh(p, parkMat, 0.002));
  const ripples = [];
  for (let i = 0; i < 3; i++) {
    const r = new THREE.Mesh(new THREE.RingGeometry(.2, .215, 64), new THREE.MeshBasicMaterial({ color: 0xffffff, transparent: true, opacity: .3 }));
    r.rotation.x = -Math.PI / 2; r.position.copy(W(2.36, 2.97, .006)); r.scale.set(1, .72, 1); r.userData.phase = i / 3; scene.add(r); ripples.push(r);
  }
  const roadMat = new THREE.MeshStandardMaterial({ roughness: .9, side: THREE.DoubleSide });
  const roadCurves = [];
  roads.forEach(rd => {
    const w = rd.w * 0.0045;
    const pos = [], idx = [];
    rd.pts.forEach(([x, y], i) => {
      const a = rd.pts[Math.max(0, i - 1)], b = rd.pts[Math.min(rd.pts.length - 1, i + 1)];
      let dx = b[0] - a[0], dy = b[1] - a[1]; const L = Math.hypot(dx, dy) || 1; dx /= L; dy /= L;
      pos.push(x - 4 - dy * w, .004, y - 3 + dx * w, x - 4 + dy * w, .004, y - 3 - dx * w);
      if (i) { const k = i * 2; idx.push(k - 2, k - 1, k, k - 1, k + 1, k); }
    });
    const g = new THREE.BufferGeometry(); g.setAttribute("position", new THREE.Float32BufferAttribute(pos, 3)); g.setIndex(idx); g.computeVertexNormals();
    const m = new THREE.Mesh(g, roadMat); m.receiveShadow = true; scene.add(m);
    if (rd.w >= 6) roadCurves.push(new THREE.CatmullRomCurve3(rd.pts.map(([x, y]) => W(x, y, .02)), false, "catmullrom", 0));
  });

  /* ---------- procedural buildings (instanced) */
  const r = rng(2026), N = 1700, dummy = new THREE.Object3D();
  const winTex = windowTexture();
  const bldMat = new THREE.MeshStandardMaterial({ roughness: .78, metalness: .05, emissiveMap: winTex, emissive: 0xffd9a0, emissiveIntensity: 0 });
  const roofMat = new THREE.MeshStandardMaterial({ roughness: .85, metalness: .05 });   // no windows on roofs
  const bld = new THREE.InstancedMesh(new THREE.BoxGeometry(1, 1, 1), [bldMat, bldMat, roofMat, roofMat, bldMat, bldMat], N);
  bld.castShadow = true; bld.receiveShadow = true;
  const towers = [[4.8, 3.2, 1], [6.3, 2.2, .9], [5.5, 4.3, .7], [1.4, 4.8, .45], [7.3, 1.3, .55]];
  let placed = 0; const heights = [];
  for (let tries = 0; placed < N && tries < N * 12; tries++) {
    const x = .15 + r() * 7.7, y = .15 + r() * 5.7;
    if (inPoly(x, y, lake) || parks.some(p => inPoly(x, y, p))) continue;
    if (roads.some(rd => rd.pts.some((p, i) => i && segDist(x, y, rd.pts[i - 1], p) < rd.w * .0045 + .035))) continue;
    if (stores.some(s => Math.hypot(s.x - x, s.y - y) < .09) || Math.hypot(home.x - x, home.y - y) < .12) continue;
    let h = .02 + r() * .06;
    for (const [tx, ty, k] of towers) { const d = Math.hypot(x - tx, y - ty); if (d < 1.1) h += k * Math.pow(1 - d / 1.1, 2) * (.15 + r() * .4); }
    const fw = .035 + r() * .055, fd = .035 + r() * .055;
    dummy.position.set(x - 4, h / 2, y - 3); dummy.scale.set(fw, h, fd); dummy.rotation.y = (r() - .5) * .25; dummy.updateMatrix();
    bld.setMatrixAt(placed, dummy.matrix); heights.push(r()); placed++;
  }
  bld.count = placed; scene.add(bld);
  const paintBuildings = () => {
    const p = P(), c = new THREE.Color();
    for (let i = 0; i < placed; i++) { const t = heights[i]; c.setRGB(p.bldA[0] + (p.bldB[0] - p.bldA[0]) * t, p.bldA[1] + (p.bldB[1] - p.bldA[1]) * t, p.bldA[2] + (p.bldB[2] - p.bldA[2]) * t); bld.setColorAt(i, c); }
    bld.instanceColor.needsUpdate = true;
  };

  /* ---------- stores: beacons */
  const glow = glowTexture();
  const storeObjs = new Map();
  const maxItems = Math.max(...stores.map(s => s.items));
  stores.forEach(s => {
    const col = new THREE.Color(typeColor[s.type] || "#888");
    const h = .12 + .32 * s.items / maxItems;
    const g = new THREE.Group(); g.position.copy(W(s.x, s.y));
    const pillar = new THREE.Mesh(new THREE.CylinderGeometry(.018, .026, h, 16), new THREE.MeshStandardMaterial({ color: col, emissive: col, emissiveIntensity: .35, roughness: .4 }));
    pillar.position.y = h / 2; pillar.castShadow = true;
    const cap = new THREE.Mesh(new THREE.SphereGeometry(.034, 20, 14), new THREE.MeshStandardMaterial({ color: col, emissive: col, emissiveIntensity: .9 }));
    cap.position.y = h + .03;
    const halo = new THREE.Sprite(new THREE.SpriteMaterial({ map: glow, color: col, transparent: true, opacity: .55, depthWrite: false, blending: THREE.AdditiveBlending }));
    halo.position.y = h + .03; halo.scale.setScalar(.22);
    const base = new THREE.Mesh(new THREE.RingGeometry(.035, .05, 32), new THREE.MeshBasicMaterial({ color: col, transparent: true, opacity: .8 }));
    base.rotation.x = -Math.PI / 2; base.position.y = .008;
    g.add(pillar, cap, halo, base); g.userData = { s, h, cap, halo, pillar, base, col };
    scene.add(g); storeObjs.set(s.id, g);
  });

  /* ---------- home beacon */
  const homeG = new THREE.Group();
  const marigold = new THREE.Color(0xFFB000);
  const house = new THREE.Mesh(new THREE.ConeGeometry(.06, .09, 4), new THREE.MeshStandardMaterial({ color: marigold, emissive: marigold, emissiveIntensity: .6 }));
  house.position.y = .13; house.rotation.y = Math.PI / 4; house.castShadow = true;
  const body = new THREE.Mesh(new THREE.BoxGeometry(.07, .07, .07), new THREE.MeshStandardMaterial({ color: marigold, emissive: marigold, emissiveIntensity: .35 }));
  body.position.y = .045; body.castShadow = true;
  const beam = new THREE.Mesh(new THREE.CylinderGeometry(.03, .03, 2.2, 16, 1, true), new THREE.MeshBasicMaterial({ color: marigold, transparent: true, opacity: .25, depthWrite: false, blending: THREE.AdditiveBlending, side: THREE.DoubleSide }));
  beam.position.y = 1.1;
  const pulse = new THREE.Mesh(new THREE.RingGeometry(.08, .1, 48), new THREE.MeshBasicMaterial({ color: marigold, transparent: true, opacity: .9, side: THREE.DoubleSide }));
  pulse.rotation.x = -Math.PI / 2; pulse.position.y = .01;
  homeG.add(house, body, beam, pulse);
  const homeLabel = labelSprite("HOME", { color: "#12222B", bg: "rgba(255,176,0,.95)", size: 40 });
  homeLabel.position.y = .3; homeLabel.scale.set(.09 * homeLabel.userData.aspect, .09, 1); homeG.add(homeLabel);
  scene.add(homeG);
  const placeHome = () => homeG.position.copy(W(home.x, home.y));
  placeHome();

  /* ---------- area labels */
  labels.forEach(([t, x, y]) => {
    const s = labelSprite(t.toUpperCase(), { color: "rgba(124,141,146,.95)", bg: null, size: 38, bold: 700 });
    s.position.copy(W(x, y, .05)); s.scale.set(.085 * s.userData.aspect, .085, 1); s.material.depthTest = true; scene.add(s);
  });

  /* ---------- traffic particles along main roads */
  const cars = [];
  roadCurves.forEach((c, i) => {
    for (let k = 0; k < 6; k++) {
      const sp = new THREE.Sprite(new THREE.SpriteMaterial({ map: glow, color: k % 2 ? 0xFFB000 : 0xff4d4d, transparent: true, depthWrite: false, blending: THREE.AdditiveBlending }));
      sp.scale.setScalar(.05); sp.userData = { c, t: Math.random(), v: (.012 + Math.random() * .02) * (k % 2 ? 1 : -1) };
      scene.add(sp); cars.push(sp);
    }
  });

  /* ---------- route */
  let route = null;
  const routeGroup = new THREE.Group(); scene.add(routeGroup);
  function setPlan(plan) {
    routeGroup.clear();
    route = null;
    const visiting = new Set(plan ? plan.stops.map(s => s.storeId) : []);
    storeObjs.forEach((g, id) => {
      const on = !plan || visiting.has(id);
      g.scale.setScalar(plan && visiting.has(id) ? 1.5 : 1);
      g.userData.pillar.material.opacity = on ? 1 : .25; g.userData.pillar.material.transparent = !on;
      g.userData.cap.material.opacity = on ? 1 : .25; g.userData.cap.material.transparent = !on;
      g.userData.halo.material.opacity = on ? .55 : .08; g.userData.base.material.opacity = on ? .8 : .15;
    });
    if (!plan || !plan.stops.length) return;
    const ptsGrid = [home, ...plan.stops.map(s => ({ x: s.x, y: s.y })), home];
    const path = new THREE.CurvePath();
    for (let i = 1; i < ptsGrid.length; i++) {
      const a = W(ptsGrid[i - 1].x, ptsGrid[i - 1].y, .06), b = W(ptsGrid[i].x, ptsGrid[i].y, .06);
      const mid = a.clone().lerp(b, .5); mid.y += Math.min(.5, a.distanceTo(b) * .22); // gentle arc between stops
      path.add(new THREE.QuadraticBezierCurve3(a, mid, b));
    }
    const col = new THREE.Color(P().route);
    const tube = new THREE.Mesh(new THREE.TubeGeometry(path, 400, .014, 10, false), new THREE.MeshStandardMaterial({ color: col, emissive: col, emissiveIntensity: 1.1, roughness: .3 }));
    const outer = new THREE.Mesh(new THREE.TubeGeometry(path, 400, .04, 10, false), new THREE.MeshBasicMaterial({ color: col, transparent: true, opacity: .18, depthWrite: false, blending: THREE.AdditiveBlending }));
    tube.geometry.setDrawRange(0, 0); outer.geometry.setDrawRange(0, 0);
    routeGroup.add(tube, outer);
    const shadowLine = new THREE.Line(new THREE.BufferGeometry().setFromPoints(ptsGrid.map(p => W(p.x, p.y, .007))), new THREE.LineDashedMaterial({ color: col, dashSize: .05, gapSize: .04, transparent: true, opacity: .6 }));
    shadowLine.computeLineDistances(); routeGroup.add(shadowLine);
    const comets = [];
    for (let k = 0; k < 5; k++) {
      const sp = new THREE.Sprite(new THREE.SpriteMaterial({ map: glow, color: 0xffffff, transparent: true, depthWrite: false, blending: THREE.AdditiveBlending }));
      sp.scale.setScalar(.12); sp.userData.off = k / 5; routeGroup.add(sp); comets.push(sp);
    }
    plan.stops.forEach((s, i) => {
      const g = storeObjs.get(s.storeId);
      const tag = labelSprite(String(i + 1), { color: "#FFB000", bg: "rgba(18,34,43,.95)", size: 56 });
      tag.position.copy(W(s.x, s.y, g.userData.h * 1.5 + .2)); tag.scale.set(.1 * tag.userData.aspect, .1, 1);
      tag.userData.baseY = tag.position.y; tag.userData.i = i; routeGroup.add(tag);
      const nm = labelSprite(s.name, { color: "#ffffff", bg: "rgba(18,34,43,.78)", size: 30, bold: 600 });
      nm.position.copy(W(s.x, s.y, g.userData.h * 1.5 + .31)); nm.scale.set(.055 * nm.userData.aspect, .055, 1); routeGroup.add(nm);
    });
    route = { path, tube, outer, comets, total: tube.geometry.index.count, t0: performance.now(), plan };
    frameRoute(ptsGrid);
  }

  /* ---------- camera: orbit with damping, plus fly-through */
  const target = new THREE.Vector3(0, 0, 0);
  const sph = { r: 7.2, theta: -0.35, phi: 0.95 };
  const goal = { r: 7.2, theta: -0.35, phi: 0.95, tx: 0, tz: 0 };
  const flyLook = new THREE.Vector3();
  let dragging = false, lastX = 0, lastY = 0, idleT = performance.now(), fly = null;
  const el = renderer.domElement;
  el.addEventListener("pointerdown", e => { dragging = true; lastX = e.clientX; lastY = e.clientY; el.setPointerCapture(e.pointerId); fly = null; idleT = performance.now(); });
  el.addEventListener("pointermove", e => {
    if (!dragging) return;
    const dx = e.clientX - lastX, dy = e.clientY - lastY; lastX = e.clientX; lastY = e.clientY;
    if (e.shiftKey || e.buttons === 2) { const s = goal.r * .0016; goal.tx -= (dx * Math.cos(goal.theta) + dy * Math.sin(goal.theta)) * s; goal.tz -= (-dx * Math.sin(goal.theta) + dy * Math.cos(goal.theta)) * s; }
    else { goal.theta -= dx * .006; goal.phi = Math.max(.25, Math.min(1.45, goal.phi - dy * .005)); }
    idleT = performance.now();
  });
  el.addEventListener("pointerup", () => { dragging = false; });
  el.addEventListener("contextmenu", e => e.preventDefault());
  el.addEventListener("wheel", e => { e.preventDefault(); goal.r = Math.max(1.4, Math.min(12, goal.r * (1 + Math.sign(e.deltaY) * .1))); idleT = performance.now(); fly = null; }, { passive: false });

  function frameRoute(pts) {
    const xs = pts.map(p => p.x), ys = pts.map(p => p.y);
    goal.tx = (Math.min(...xs) + Math.max(...xs)) / 2 - 4; goal.tz = (Math.min(...ys) + Math.max(...ys)) / 2 - 3;
    goal.r = Math.max(2.6, Math.min(7.5, Math.max(Math.max(...xs) - Math.min(...xs), Math.max(...ys) - Math.min(...ys)) * 1.6 + 1.6));
    goal.phi = .85;
  }
  function flyThrough() {
    if (!route) return false;
    fly = { t0: performance.now(), dur: Math.min(24000, 7000 + route.path.getLength() * 2000) };
    flyLook.copy(target);
    return true;
  }
  function resetView() { Object.assign(goal, { r: 7.2, theta: -.35, phi: .95, tx: 0, tz: 0 }); fly = null; }

  /* ---------- theme */
  function setTheme(d) {
    dark = d; const p = P();
    scene.background = new THREE.Color(p.sky);
    scene.fog = new THREE.Fog(p.sky, p.fog[0], p.fog[1]);
    groundMat.color.set(p.ground); roadMat.color.set(p.road); waterMat.color.set(p.water); parkMat.color.set(p.park);
    hemi.intensity = p.hemi; sun.intensity = p.sun; bldMat.emissiveIntensity = p.windows;
    beam.material.opacity = p.beam * .45;
    storeObjs.forEach(g => { g.userData.pillar.material.emissiveIntensity = dark ? .9 : .35; g.userData.cap.material.emissiveIntensity = dark ? 2 : .9; });
    if (route) { const c = new THREE.Color(p.route); route.tube.material.color.copy(c); route.tube.material.emissive.copy(c); route.outer.material.color.copy(c); }
    renderer.toneMappingExposure = dark ? 1.15 : 1.0;
    paintBuildings();
  }
  setTheme(dark);

  /* ---------- loop */
  let raf = 0, visible = true, w = 0, h = 0;
  const ro = new ResizeObserver(() => {
    w = container.clientWidth; h = container.clientHeight;
    if (!w || !h) return;
    renderer.setSize(w, h, false); camera.aspect = w / h; camera.updateProjectionMatrix();
  });
  ro.observe(container);
  const io = new IntersectionObserver(es => { visible = es[0].isIntersecting; if (visible && !raf) loop(); });
  io.observe(container);
  let onFlyEnd = null;
  function loop() {
    raf = 0;
    if (!visible || !container.isConnected) return;
    raf = requestAnimationFrame(loop);
    const now = performance.now(), t = now / 1000;
    // idle auto-rotate
    if (!dragging && !fly && now - idleT > 4000 && !reduced) goal.theta += .0012;
    if (fly) {
      const f = Math.min(1, (now - fly.t0) / fly.dur), e = f < .5 ? 2 * f * f : 1 - Math.pow(-2 * f + 2, 2) / 2;
      // drone chase cam: behind and above the route, looking a little ahead
      const p = route.path.getPointAt(Math.min(.999, e)), q = route.path.getPointAt(Math.min(1, e + .06));
      const dir = q.clone().sub(p).setY(0); if (dir.lengthSq() < 1e-6) dir.set(0, 0, 1); dir.normalize();
      camera.position.lerp(p.clone().add(new THREE.Vector3(-dir.x * 1.25, 1.05, -dir.z * 1.25)), .05);
      flyLook.lerp(new THREE.Vector3(q.x, 0, q.z), .08);
      camera.lookAt(flyLook);
      if (f >= 1) {  // hand back to the orbit camera from wherever the flight ended
        fly = null;
        const off = camera.position.clone().sub(target);
        sph.r = off.length(); sph.phi = Math.acos(Math.max(-1, Math.min(1, off.y / sph.r))); sph.theta = Math.atan2(off.x, off.z);
        goal.theta = sph.theta; idleT = performance.now();
        if (onFlyEnd) onFlyEnd();
      }
    } else {
      sph.r += (goal.r - sph.r) * .08; sph.theta += (goal.theta - sph.theta) * .1; sph.phi += (goal.phi - sph.phi) * .1;
      target.x += (goal.tx - target.x) * .08; target.z += (goal.tz - target.z) * .08;
      camera.position.set(target.x + sph.r * Math.sin(sph.phi) * Math.sin(sph.theta), sph.r * Math.cos(sph.phi), target.z + sph.r * Math.sin(sph.phi) * Math.cos(sph.theta));
      camera.lookAt(target);
    }
    // ambient motion
    ripples.forEach(rp => { const k = ((t * .18 + rp.userData.phase) % 1); rp.scale.set(.6 + k * 2.4, (.6 + k * 2.4) * .72, 1); rp.material.opacity = .35 * (1 - k); });
    pulse.scale.setScalar(1 + (t * 1.2 % 1) * 2.2); pulse.material.opacity = .9 * (1 - (t * 1.2 % 1));
    storeObjs.forEach((g, id) => { const u = g.userData; u.cap.position.y = u.h + .03 + Math.sin(t * 2 + id) * .008; u.halo.position.y = u.cap.position.y; u.halo.scale.setScalar(.2 + Math.sin(t * 3 + id) * .03); });
    cars.forEach(c => { const u = c.userData; u.t = (u.t + u.v * .016 + 1) % 1; c.position.copy(u.c.getPointAt(u.t)); });
    if (route) {
      const grow = reduced ? 1 : Math.min(1, (now - route.t0) / 2200), eased = 1 - Math.pow(1 - grow, 3);
      const n = Math.floor(route.total * eased / 3) * 3;
      route.tube.geometry.setDrawRange(0, n); route.outer.geometry.setDrawRange(0, n);
      route.comets.forEach(c => { const u = ((t * .09 + c.userData.off) % 1) * eased; c.position.copy(route.path.getPointAt(u)); c.visible = grow > .05; });
      routeGroup.children.forEach(o => { if (o.userData.baseY != null) o.position.y = o.userData.baseY + Math.sin(t * 2.2 + o.userData.i) * .015; });
    }
    renderer.render(scene, camera);
  }
  loop();

  return {
    setPlan, flyThrough, resetView, setTheme,
    setHome(hm) { home = { ...hm }; placeHome(); },
    onFlyEnd(fn) { onFlyEnd = fn; },
    dispose() { cancelAnimationFrame(raf); ro.disconnect(); io.disconnect(); renderer.dispose(); el.remove(); }
  };
}
