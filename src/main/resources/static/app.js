/* Sweep frontend. All algorithmic work happens in the Java backend; this file only
   collects input, calls the REST API, and animates what the server returns. */
"use strict";

const $ = (s, r = document) => r.querySelector(s);
const $$ = (s, r = document) => [...r.querySelectorAll(s)];
const NS = "http://www.w3.org/2000/svg";
const REDUCED = matchMedia("(prefers-reduced-motion: reduce)").matches;
const sleep = ms => new Promise(r => setTimeout(r, REDUCED ? 0 : ms));
const PX = 100; // 1 km = 100 svg units
const fmtINR = n => "₹" + Math.round(n).toLocaleString("en-IN");
const esc = s => String(s).replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));

function svg(tag, attrs = {}, parent) {
  const el = document.createElementNS(NS, tag);
  for (const [k, v] of Object.entries(attrs)) el.setAttribute(k, v);
  if (parent) parent.appendChild(el);
  return el;
}

const TYPE_COLOR = {
  "Supermarket": "#E5484D", "Kirana": "#F76B15", "Pharmacy": "#12A594", "Bakery": "#B07A43",
  "Fruit & Veg": "#46A758", "Stationery": "#8E4EC6", "Hardware": "#6E7F86", "Electronics": "#0090FF", "Pet Store": "#D6409F"
};
const CAT_COLOR = {
  grocery: "#F76B15", dairy: "#E8B500", produce: "#46A758", bakery: "#B07A43", snacks: "#E5484D", household: "#6E7F86",
  personal: "#D6409F", pharmacy: "#12A594", stationery: "#8E4EC6", hardware: "#6E7F86", electronics: "#0090FF", pet: "#D6409F"
};
const MODES = {
  walk: { speed: 4.5, kmCost: 0, label: "on foot" },
  cycle: { speed: 14, kmCost: 1, label: "by cycle" },
  auto: { speed: 18, kmCost: 18, label: "by auto-rickshaw" }
};
const PRESETS = {
  "Weekly groceries": [["Milk 1L", 4], ["Eggs 12pk", 1], ["Atta 5kg", 1], ["Toor Dal 1kg", 1], ["Onions 1kg", 2], ["Tomatoes 1kg", 1], ["Bananas 12pc", 1], ["White Bread", 2], ["Dishwash Liquid 500ml", 1], ["Toothpaste 150g", 1]],
  "Sick day": [["Paracetamol 500mg Strip", 2], ["ORS Sachet 4pk", 2], ["Cough Syrup 100ml", 1], ["Oranges 1kg", 1], ["Glucose Powder 500g", 1], ["Tissue Roll 4pk", 1]],
  "Exam week": [["A4 Notebook 200pg", 3], ["Gel Pen Blue", 5], ["Highlighter 4pk", 1], ["Instant Coffee 50g", 1], ["Dark Chocolate 100g", 2], ["Energy Drink 250ml", 2], ["AA Batteries 4pk", 1]],
  "New flat": [["LED Bulb 9W", 4], ["Extension Board 4-socket", 1], ["Garbage Bags 30pc", 2], ["Floor Cleaner 1L", 1], ["Padlock", 1], ["Screwdriver Set", 1], ["Wall Hooks 6pc", 2], ["Salt 1kg", 1], ["Sunflower Oil 1L", 1], ["Matchbox 10pk", 1]],
  "2 AM emergency": [["Paracetamol 500mg Strip", 1], ["Band-Aid 20pc", 1], ["Antiseptic Liquid 120ml", 1], ["Digital Thermometer", 1]],
  "Cat parent": [["Cat Food 1kg", 2], ["Cat Litter 5kg", 1], ["Milk 1L", 2], ["Kitchen Towel 2pk", 1]]
};

const S = {
  stores: [], storeById: {}, items: [], itemByName: {}, list: [],
  home: { x: 4.62, y: 3.38 }, hour: 18, mode: "auto", lambda: 1, stopCost: 40,
  plan: null, running: false, runId: 0, routeView: "final"
};

/* ================================================================ API */
async function api(path, opts) {
  const res = await fetch("/api" + path, opts);
  let body = null;
  try { body = await res.json(); } catch { /* non-JSON */ }
  if (!res.ok) {
    const e = new Error((body && body.error) || ("HTTP " + res.status));
    e.details = (body && body.details) || [];
    throw e;
  }
  return body;
}

/* ================================================================ boot */
async function boot() {
  initTheme();
  initTabs();
  drawTerrain();
  bindBuilder();
  try {
    const [stores, items] = await Promise.all([api("/stores"), api("/items")]);
    S.stores = stores; stores.forEach(s => S.storeById[s.id] = s);
    S.items = items; items.forEach(i => S.itemByName[i.name.toLowerCase()] = i);
    $("#apiDot").classList.add("ok"); $("#apiDot").title = "Java backend connected";
    $("#trieMeter").textContent = `Trie index ready · type to search ${items.length} items`;
    drawStores(); drawLegend(); drawHome();
    loadPreset("Weekly groceries", true);
  } catch (e) {
    $("#apiDot").classList.add("down");
    showError("Can't reach the Java backend.", ["Start it with ./mvnw spring-boot:run, then reload this page."]);
  }
  renderPipeline();
  route();
}

/* ================================================================ theme + tabs */
function initTheme() {
  try { const t = localStorage.getItem("sweep-theme"); if (t) document.documentElement.dataset.theme = t; } catch { }
  $("#themeBtn").addEventListener("click", () => {
    const cur = document.documentElement.dataset.theme || (matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light");
    const next = cur === "dark" ? "light" : "dark";
    const apply = () => { document.documentElement.dataset.theme = next; };
    document.startViewTransition && !REDUCED ? document.startViewTransition(apply) : apply();
    try { localStorage.setItem("sweep-theme", next); } catch { }
  });
}
function initTabs() { addEventListener("hashchange", route); addEventListener("resize", moveInk); }
function route() {
  const v = (location.hash || "#plan").slice(1);
  const view = ["plan", "xray", "lab", "how"].includes(v) ? v : "plan";
  const swap = () => {
    $$(".view").forEach(el => el.hidden = el.dataset.view !== view);
    $$(".tab").forEach(t => t.setAttribute("aria-selected", t.dataset.view === view));
    moveInk();
    if (view === "lab" && !$("#lab").children.length) renderLabShell();
  };
  document.startViewTransition && !REDUCED ? document.startViewTransition(swap) : swap();
}
function moveInk() {
  const t = $(".tab[aria-selected='true']"), ink = $(".tab-ink");
  if (!t) return;
  ink.style.width = t.offsetWidth + "px";
  ink.style.transform = `translateX(${t.offsetLeft}px)`;
}

/* ================================================================ map: terrain */
const ROADS = [
  { name: "JVLR", w: 9, pts: [[0, 5.0], [1.4, 5.15], [2.5, 5.25], [3.7, 5.2], [5, 5.1], [6.4, 5.2], [8, 5.45]] },
  { name: "Saki Vihar Rd", w: 8, pts: [[4.3, 5.1], [4.9, 4.55], [5.3, 4.2], [6.5, 3.6], [8, 3.15]] },
  { name: "Adi Shankaracharya Marg", w: 8, pts: [[0, 2.7], [0.6, 2.45], [1.2, 2.2], [2.2, 2.05], [3.2, 2.3], [4.0, 3.0], [4.5, 3.6], [4.35, 5.1]] },
  { name: "Hiranandani Main", w: 7, pts: [[4.0, 3.0], [5.0, 2.65], [6.0, 2.15], [6.9, 1.6], [8, 1.0]] },
  { name: "Central Ave", w: 6, pts: [[4.55, 2.35], [5.0, 3.0], [5.25, 3.5], [5.75, 4.6], [5.9, 5.15]] },
  { name: "IIT Main Rd", w: 6, pts: [[0.9, 1.55], [1.6, 1.8], [2.4, 2.02], [3.0, 1.5], [3.6, 0.95], [4.6, 0.15]] },
  { name: "LBS Link", w: 6, pts: [[0, 3.3], [0.7, 3.25], [1.3, 3.05], [1.55, 4.0], [2.2, 4.9], [2.5, 5.25]] },
  { name: "Chandivali Farm Rd", w: 6, pts: [[5.85, 1.5], [6.2, 2.1], [6.6, 2.7], [7.05, 3.35]] },
  { name: "", w: 4, pts: [[0.7, 1.1], [0.95, 1.6], [1.2, 2.2]] },
  { name: "", w: 4, pts: [[3.05, 3.95], [3.6, 3.6], [4.1, 3.75], [4.5, 3.6]] },
  { name: "", w: 4, pts: [[5.25, 3.5], [6.0, 3.95], [6.5, 3.6]] },
  { name: "", w: 4, pts: [[7.0, 1.4], [7.4, 1.1], [7.9, 1.05]] },
  { name: "", w: 4, pts: [[4.05, 5.2], [4.1, 5.6], [3.9, 6]] }
];
const LAKE = [[1.78, 2.95], [1.85, 2.6], [2.1, 2.42], [2.45, 2.38], [2.75, 2.5], [2.95, 2.78], [2.92, 3.12], [2.75, 3.4], [2.45, 3.55], [2.12, 3.5], [1.9, 3.3]];
const smooth = pts => { // closed Catmull-Rom -> cubic bezier
  const p = pts.map(([x, y]) => [x * PX, y * PX]); let d = `M${p[0]}`;
  for (let i = 0; i < p.length; i++) {
    const p0 = p[(i - 1 + p.length) % p.length], p1 = p[i], p2 = p[(i + 1) % p.length], p3 = p[(i + 2) % p.length];
    d += `C${p1[0] + (p2[0] - p0[0]) / 6},${p1[1] + (p2[1] - p0[1]) / 6} ${p2[0] - (p3[0] - p1[0]) / 6},${p2[1] - (p3[1] - p1[1]) / 6} ${p2[0]},${p2[1]}`;
  }
  return d + "Z";
};
const polyD = pts => "M" + pts.map(([x, y]) => `${x * PX},${y * PX}`).join("L");

function drawTerrain() {
  const t = $("#terrain"), r = $("#roads"), l = $("#labels");
  svg("path", { d: smooth([[2.0, 0.35], [3.3, 0.2], [4.2, 0.35], [3.9, 1.2], [3.1, 1.75], [2.2, 1.7], [1.7, 1.05]]), class: "park" }, t);
  svg("path", { d: smooth([[0.1, 0.1], [1.2, 0.15], [1.4, 0.8], [0.6, 1.0], [0.1, 0.8]]), class: "park" }, t);
  svg("path", { d: smooth([[5.3, 3.75], [5.7, 3.6], [5.85, 3.85], [5.5, 4.05]]), class: "park" }, t);
  svg("path", { d: smooth(LAKE), class: "lake" }, t);
  if (!REDUCED) for (let i = 0; i < 3; i++) {
    const c = svg("ellipse", { cx: 237, cy: 297, rx: 30, ry: 20, class: "lake-ripple" }, t);
    svg("animate", { attributeName: "rx", values: "20;95", dur: "6s", begin: `${i * 2}s`, repeatCount: "indefinite" }, c);
    svg("animate", { attributeName: "ry", values: "14;70", dur: "6s", begin: `${i * 2}s`, repeatCount: "indefinite" }, c);
    svg("animate", { attributeName: "opacity", values: ".9;0", dur: "6s", begin: `${i * 2}s`, repeatCount: "indefinite" }, c);
  }
  ROADS.forEach((rd, i) => {
    const d = polyD(rd.pts);
    const edge = svg("path", { d, class: "road-edge", "stroke-width": rd.w + 3 }, r);
    const road = svg("path", { d, class: "road", "stroke-width": rd.w, id: "road" + i }, r);
    if (!REDUCED) {
      [edge, road].forEach(p => { const L = 1400; p.style.strokeDasharray = L; p.style.strokeDashoffset = L; p.animate([{ strokeDashoffset: L }, { strokeDashoffset: 0 }], { duration: 1600, delay: 120 * i, easing: "cubic-bezier(.6,0,.2,1)", fill: "forwards" }); });
      if (rd.w >= 6) for (let k = 0; k < 2; k++) { // ambient traffic
        const car = svg("circle", { r: 1.8, fill: k ? "var(--marigold)" : "var(--cobalt)", opacity: .75 }, r);
        const m = svg("animateMotion", { dur: `${14 + i * 2 + k * 5}s`, repeatCount: "indefinite", begin: `${k * 4 + i}s`, keyPoints: k ? "1;0" : "0;1", keyTimes: "0;1", calcMode: "linear" }, car);
        const mp = svg("mpath", {}, m); mp.setAttributeNS("http://www.w3.org/1999/xlink", "href", "#road" + i); mp.setAttribute("href", "#road" + i);
      }
    }
    if (rd.name) {
      const [a, b] = [rd.pts[1], rd.pts[2]];
      const ang = Math.atan2(b[1] - a[1], b[0] - a[0]) * 180 / Math.PI;
      const tx = svg("text", { x: (a[0] + b[0]) / 2 * PX, y: (a[1] + b[1]) / 2 * PX - rd.w, class: "road-name", "text-anchor": "middle", transform: `rotate(${ang} ${(a[0] + b[0]) / 2 * PX} ${(a[1] + b[1]) / 2 * PX - rd.w})` }, l);
      tx.textContent = rd.name;
    }
  });
  [["Powai Lake", 2.36, 3.02, "water"], ["IIT Bombay", 2.95, 0.95], ["Hiranandani", 4.95, 3.28], ["Chandivali", 6.75, 2.05], ["Kanjurmarg W", 0.62, 3.95], ["Raheja Vihar", 7.1, 3.0], ["Saki Vihar", 6.4, 4.6], ["Vihar Woods", 0.6, 0.55]]
    .forEach(([txt, x, y, c]) => { const e = svg("text", { x: x * PX, y: y * PX, class: "map-label " + (c || ""), "text-anchor": "middle" }, l); e.textContent = txt; });
}

/* ================================================================ map: stores, home */
function drawStores() {
  const g = $("#storeLayer"); g.innerHTML = "";
  S.stores.forEach((s, i) => {
    const grp = svg("g", { class: "store store-drop", "data-id": s.id, tabindex: 0, role: "button", "aria-label": `${s.name}, ${s.type}` }, g);
    grp.style.animationDelay = (0.6 + i * 0.03) + "s";
    svg("circle", { cx: s.x * PX, cy: s.y * PX, r: 7, fill: TYPE_COLOR[s.type] || "#888", class: "pin" }, grp);
    grp.addEventListener("pointerenter", e => showStoreCard(s, grp));
    grp.addEventListener("focus", () => showStoreCard(s, grp));
    grp.addEventListener("pointerleave", hideStoreCard);
    grp.addEventListener("blur", hideStoreCard);
  });
  applyHour();
}
function applyHour() {
  $$(".store").forEach(g => {
    const s = S.storeById[g.dataset.id];
    g.classList.toggle("closed", !(S.hour >= s.open && S.hour < s.close));
  });
}
function showStoreCard(s, grp) {
  const card = $("#storeCard"), wrap = $("#mapWrap");
  const open = S.hour >= s.open && S.hour < s.close;
  const hrs = s.close === 24 && s.open === 0 ? "Open 24 hours" : `${String(s.open).padStart(2, "0")}:00 to ${String(s.close).padStart(2, "0")}:00`;
  const stop = S.plan && S.plan.stops.find(x => x.storeId === s.id);
  card.innerHTML = `<h4>${esc(s.name)}</h4><p>${esc(s.type)} · ${esc(s.area)}</p><p>${hrs} · <b style="color:var(${open ? "--good" : "--bad"})">${open ? "open" : "closed"} at ${S.hour}:00</b></p>
    ${stop ? `<p style="margin-top:.35rem"><b>Stop ${S.plan.stops.indexOf(stop) + 1}:</b> ${stop.picks.map(p => esc(p.item)).join(", ")}</p>` : ""}
    <p class="mono" style="margin-top:.35rem">${s.items} items · Bloom sketch ${s.bloomBits} bits, k=${s.bloomK} (${s.bloomBytes} B)</p>`;
  card.hidden = false;
  const r = grp.getBoundingClientRect(), w = wrap.getBoundingClientRect();
  let left = r.left - w.left + 16, top = r.top - w.top - 10;
  if (left + 250 > w.width) left = r.left - w.left - 260;
  if (top + 150 > w.height) top = w.height - 160;
  card.style.left = Math.max(8, left) + "px"; card.style.top = Math.max(56, top) + "px";
  grp.classList.add("focus");
}
function hideStoreCard() { $("#storeCard").hidden = true; $$(".store.focus").forEach(g => g.classList.remove("focus")); }

function drawLegend() {
  $("#legend").innerHTML = Object.entries(TYPE_COLOR).map(([t, c]) => `<span><i style="background:${c}"></i>${t}</span>`).join("") +
    `<span><i style="background:var(--marigold);outline:2px solid var(--ink)"></i>Home</span>`;
}

function drawHome() {
  const g = $("#homeLayer"); g.innerHTML = "";
  const grp = svg("g", { class: "home-pin", tabindex: 0, role: "button", "aria-label": "Your home. Drag to move." }, g);
  svg("circle", { cx: 0, cy: 0, r: 34, fill: "url(#homeHalo)" }, grp);
  svg("circle", { cx: 0, cy: 0, r: 9, class: "pulse" }, grp);
  svg("path", { d: "M0 -16 L12 -4 L8 -4 L8 9 L-8 9 L-8 -4 L-12 -4 Z", class: "core" }, grp);
  placeHome();
  let drag = false;
  grp.addEventListener("pointerdown", e => { drag = true; grp.classList.add("dragging"); grp.setPointerCapture(e.pointerId); e.stopPropagation(); });
  grp.addEventListener("pointermove", e => { if (drag) setHomeFromEvent(e, false); });
  grp.addEventListener("pointerup", e => { if (drag) { drag = false; grp.classList.remove("dragging"); setHomeFromEvent(e, true); } });
  grp.addEventListener("keydown", e => {
    const step = { ArrowLeft: [-.1, 0], ArrowRight: [.1, 0], ArrowUp: [0, -.1], ArrowDown: [0, .1] }[e.key];
    if (step) { e.preventDefault(); S.home.x = clamp(S.home.x + step[0], 0, 8); S.home.y = clamp(S.home.y + step[1], 0, 6); placeHome(); }
  });
}
const clamp = (v, a, b) => Math.min(b, Math.max(a, v));
function placeHome() { $(".home-pin").setAttribute("transform", `translate(${S.home.x * PX} ${S.home.y * PX})`); }
function svgPoint(e) {
  const m = $("#map"), pt = m.createSVGPoint(); pt.x = e.clientX; pt.y = e.clientY;
  return pt.matrixTransform(m.getScreenCTM().inverse());
}
function setHomeFromEvent(e, done) {
  const p = svgPoint(e);
  S.home = { x: +clamp(p.x / PX, 0.05, 7.95).toFixed(2), y: +clamp(p.y / PX, 0.05, 5.95).toFixed(2) };
  placeHome();
  if (done) { clearRoute(); ticker("HOME", `Home set at ${S.home.x.toFixed(2)} km E, ${S.home.y.toFixed(2)} km S. Press Sweep it.`); }
}

/* ================================================================ list builder */
function bindBuilder() {
  const inp = $("#searchInput"), box = $("#suggestList");
  let t = null, active = -1, results = [], seq = 0;
  const close = () => { box.hidden = true; $("#searchBox").setAttribute("aria-expanded", "false"); active = -1; };
  inp.addEventListener("input", () => {
    clearTimeout(t);
    const q = inp.value.trim();
    if (!q) { close(); return; }
    t = setTimeout(async () => {
      const my = ++seq;
      try {
        const r = await api(`/suggest?q=${encodeURIComponent(q)}&k=8`);
        if (my !== seq) return;
        results = r.results; active = results.length ? 0 : -1;
        const qn = q.toLowerCase();
        box.innerHTML = results.length ? results.map((s, i) => {
          const name = esc(s.name), idx = s.name.toLowerCase().split(" ").findIndex(w => w.startsWith(qn.split(" ")[0]));
          const words = name.split(" ");
          if (idx >= 0) words[idx] = `<b>${words[idx].slice(0, qn.length)}</b>${words[idx].slice(qn.length)}`;
          return `<li role="option" id="opt${i}" aria-selected="${i === 0}" data-i="${i}"><span class="cat-dot" style="background:${CAT_COLOR[s.category]}"></span><span>${words.join(" ")}</span><small>${s.stores ? "from " + fmtINR(s.minPrice) + " · " + s.stores + " shops" : "not stocked"}</small></li>`;
        }).join("") : `<li class="none">No item starts with “${esc(q)}”. Try a shorter word.</li>`;
        box.hidden = false; $("#searchBox").setAttribute("aria-expanded", "true");
        const speed = r.scanNanos > 0 && r.trieNanos > 0 ? (r.scanNanos / r.trieNanos) : 0;
        $("#trieMeter").innerHTML = `trie walked <b>${r.nodesVisited}</b> of ${r.trieNodes.toLocaleString()} nodes · ${(r.trieNanos / 1000).toFixed(1)} µs` +
          (speed ? ` · linear scan ${(r.scanNanos / 1000).toFixed(1)} µs` : "");
      } catch (e) { $("#trieMeter").textContent = "Search unavailable: " + e.message; }
    }, 70);
  });
  inp.addEventListener("keydown", e => {
    const lis = $$("li[role=option]", box);
    if (e.key === "ArrowDown" || e.key === "ArrowUp") {
      e.preventDefault(); if (!lis.length) return;
      active = (active + (e.key === "ArrowDown" ? 1 : -1) + lis.length) % lis.length;
      lis.forEach((l, i) => l.setAttribute("aria-selected", i === active));
      lis[active].scrollIntoView({ block: "nearest" });
    } else if (e.key === "Enter") {
      e.preventDefault();
      if (active >= 0 && results[active]) { addItem(results[active].name, 1, inp); inp.value = ""; close(); }
    } else if (e.key === "Escape") close();
  });
  box.addEventListener("pointerdown", e => {
    const li = e.target.closest("li[role=option]"); if (!li) return;
    e.preventDefault(); addItem(results[+li.dataset.i].name, 1, li); inp.value = ""; close(); inp.focus();
  });
  inp.addEventListener("blur", () => setTimeout(close, 120));
  addEventListener("keydown", e => { if (e.key === "/" && document.activeElement !== inp && !e.target.closest("input")) { e.preventDefault(); location.hash = "#plan"; inp.focus(); } });

  $("#presets").innerHTML = Object.keys(PRESETS).map(n => `<button class="chip" data-p="${esc(n)}">${esc(n)}</button>`).join("");
  $("#presets").addEventListener("click", e => { const b = e.target.closest(".chip"); if (b) loadPreset(b.dataset.p); });

  const hour = $("#hour"), lam = $("#lambda"), stop = $("#stopCost");
  const paint = el => el.style.setProperty("--p", ((el.value - el.min) / (el.max - el.min) * 100) + "%");
  hour.addEventListener("input", () => { S.hour = +hour.value; $("#hourOut").textContent = String(S.hour).padStart(2, "0") + ":00"; paint(hour); applyHour(); if (S.hour <= 5) ticker("NIGHT", "Most shops are shut at this hour. Only 24-hour pharmacies stay open."); });
  lam.addEventListener("input", () => { S.lambda = +lam.value; $("#lambdaOut").textContent = S.lambda.toFixed(1); paint(lam); });
  stop.addEventListener("input", () => { S.stopCost = +stop.value; $("#stopOut").textContent = "₹" + S.stopCost; paint(stop); });
  [hour, lam, stop].forEach(paint);
  $("#modeSeg").addEventListener("click", e => {
    const b = e.target.closest("button"); if (!b) return;
    S.mode = b.dataset.mode; $$("#modeSeg button").forEach(x => x.setAttribute("aria-checked", x === b));
  });
  $("#goBtn").addEventListener("click", runPlan);
  $("#map").addEventListener("click", e => { if (e.target.closest(".store, .home-pin")) return; if (S.running) return; setHomeFromEvent(e, true); });
  $("#map").addEventListener("pointermove", e => { const p = svgPoint(e); $("#coord").textContent = `${(p.x / PX).toFixed(2)} km E · ${(p.y / PX).toFixed(2)} km S`; });
  $("#map").addEventListener("dblclick", e => { e.preventDefault(); zoomTo(null); });
  $("#benchAll") && $("#benchAll").addEventListener("click", () => runAllBench(true));
}

function loadPreset(name, quiet) {
  S.list = PRESETS[name].filter(([n]) => S.itemByName[n.toLowerCase()]).map(([n, q]) => ({ name: n, qty: q, category: S.itemByName[n.toLowerCase()].category }));
  if (name === "2 AM emergency") { setHour(2); }
  else if (S.hour <= 5) setHour(18);
  renderList();
  if (!quiet) ticker("LIST", `Loaded “${name}”: ${S.list.length} items. Press Sweep it.`);
}
function setHour(h) { const el = $("#hour"); el.value = h; el.dispatchEvent(new Event("input")); }

function addItem(name, qty, fromEl) {
  const it = S.itemByName[name.toLowerCase()]; if (!it) return;
  const ex = S.list.find(l => l.name === it.name);
  if (ex) { ex.qty = Math.min(50, ex.qty + qty); toast(`${it.name} is already on your list. Quantity is now ${ex.qty}.`); }
  else S.list.push({ name: it.name, qty, category: it.category });
  renderList(it.name);
  if (fromEl && !REDUCED) burst(fromEl.getBoundingClientRect(), CAT_COLOR[it.category], 14);
}
function renderList(flash) {
  const ul = $("#list");
  ul.innerHTML = S.list.map((l, i) => `<li class="line" data-i="${i}" style="animation-delay:${flash ? 0 : i * 30}ms">
    <span class="line-bar" style="background:${CAT_COLOR[l.category]}"></span>
    <span class="line-name">${esc(l.name)}<small>${l.category}</small></span>
    <span class="qty"><button data-a="dec" aria-label="Fewer ${esc(l.name)}">−</button><span>${l.qty}</span><button data-a="inc" aria-label="More ${esc(l.name)}">+</button></span>
    <button class="x" data-a="del" aria-label="Remove ${esc(l.name)}">×</button></li>`).join("");
  if (flash) { const li = $$(".line", ul).find(x => S.list[x.dataset.i].name === flash); if (li) li.scrollIntoView({ block: "nearest" }); }
  $("#listEmpty").hidden = S.list.length > 0;
  $("#goSub").textContent = S.list.length ? `${S.list.length} item${S.list.length > 1 ? "s" : ""} · ${MODES[S.mode].label} · leave ${String(S.hour).padStart(2, "0")}:00` : "add items first";
  ul.onclick = e => {
    const b = e.target.closest("button[data-a]"); if (!b) return;
    const li = b.closest(".line"), i = +li.dataset.i, l = S.list[i];
    if (b.dataset.a === "inc") l.qty = Math.min(50, l.qty + 1);
    if (b.dataset.a === "dec") l.qty = Math.max(1, l.qty - 1);
    if (b.dataset.a === "del") { li.classList.add("leaving"); setTimeout(() => { S.list.splice(i, 1); renderList(); }, REDUCED ? 0 : 220); return; }
    $(".qty span", li).textContent = l.qty;
  };
}

/* ================================================================ plan + choreography */
async function runPlan() {
  if (S.running) return;
  hideError();
  const m = MODES[S.mode];
  const body = {
    home: S.home, hour: S.hour, lambda: S.lambda, stopCost: S.stopCost, kmCost: m.kmCost, speedKmh: m.speed,
    items: S.list.map(l => ({ name: l.name, qty: l.qty }))
  };
  const btn = $("#goBtn"); btn.classList.add("busy"); S.running = true;
  const myRun = ++S.runId;
  try {
    location.hash = "#plan";
    const plan = await api("/plan", { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(body) });
    S.plan = plan;
    await choreograph(plan, myRun);
    renderXray(plan);
  } catch (e) {
    showError(e.message, e.details);
    ticker("ERROR", e.message);
  } finally {
    btn.classList.remove("busy"); S.running = false;
    $("#goSub").textContent = "run again with new settings";
  }
}

function clearRoute() {
  ["#mstLayer", "#routeLayer", "#ringLayer", "#flyLayer", "#runnerLayer"].forEach(s => { $(s).innerHTML = ""; $(s).getAnimations().forEach(a => a.cancel()); });
  $$(".store").forEach(g => g.classList.remove("dim", "chosen"));
  $$(".store-badge-num").forEach(e => e.remove());
}

async function choreograph(plan, runId) {
  clearRoute(); zoomTo(null);
  const alive = () => runId === S.runId;
  const ss = plan.screenStats;
  const openIds = new Set(S.stores.filter(s => S.hour >= s.open && S.hour < s.close).map(s => s.id));

  // ---- 1. Bloom wave: one ring per open store, coloured by its best verdict
  ticker("1/5 BLOOM", `${ss.checks} store × item checks · ${ss.bloomRejected} ruled out by a zero bit · ${ss.falsePositives} false positive${ss.falsePositives === 1 ? "" : "s"} caught · ${(ss.nanos / 1e6).toFixed(2)} ms`);
  const verdict = {};
  plan.screens.forEach(sc => {
    sc.confirmed.forEach(id => verdict[id] = "yes");
    sc.falsePositives.forEach(id => verdict[id] = verdict[id] || "fp");
    sc.lowStock.forEach(id => verdict[id] = verdict[id] || "fp");
  });
  const order = [...openIds].sort((a, b) => dist(S.storeById[a], S.home) - dist(S.storeById[b], S.home));
  order.forEach((id, i) => {
    const s = S.storeById[id], v = verdict[id] || "no";
    const c = svg("circle", { cx: s.x * PX, cy: s.y * PX, r: 8, class: "ring " + v }, $("#ringLayer"));
    c.animate([{ r: 8, opacity: 1 }, { r: v === "yes" ? 30 : 20, opacity: 0 }], { duration: 900, delay: i * 22, easing: "cubic-bezier(.2,.8,.2,1)", fill: "forwards" });
    setTimeout(() => { if (alive() && v !== "yes") $(`.store[data-id="${id}"]`).classList.add("dim"); }, REDUCED ? 0 : i * 22 + 450);
  });
  await sleep(order.length * 22 + 900);
  if (!alive()) return;

  // ---- 2. Set cover picks, items fly from home to the chosen store
  for (const [k, st] of plan.cover.steps.entries()) {
    const s = S.storeById[st.storeId];
    ticker(`2/5 COVER`, `Pick ${k + 1}: ${s.name} covers ${st.newlyCovered.join(", ")} · ₹${st.ratio.toFixed(1)} per item`);
    const g = $(`.store[data-id="${s.id}"]`); g.classList.remove("dim"); g.classList.add("chosen");
    st.newlyCovered.forEach((name, j) => flyLabel(name, S.home, s, j * 90));
    await sleep(Math.max(380, 700 - plan.cover.steps.length * 40));
    if (!alive()) return;
  }
  const visiting = new Set(plan.stops.map(s => s.storeId));
  $$(".store.chosen").forEach(g => { if (!visiting.has(+g.dataset.id)) { g.classList.remove("chosen"); g.classList.add("dim"); } });
  if (plan.stops.length) zoomTo([S.home, ...plan.stops.map(s => S.storeById[s.storeId])]);

  // ---- 3. MST
  const P = id => id === 0 ? S.home : S.storeById[id];
  ticker("3/5 MST", `Kruskal + union-find: spanning tree of ${plan.route.mstEdges.length} edges, ${plan.route.mstKm.toFixed(2)} km. No route can be shorter than this.`);
  plan.route.mstEdges.forEach((e, i) => {
    const a = P(e.a), b = P(e.b);
    const ln = svg("line", { x1: a.x * PX, y1: a.y * PX, x2: b.x * PX, y2: b.y * PX, class: "mst-edge" }, $("#mstLayer"));
    ln.animate([{ opacity: 0 }, { opacity: .7 }], { duration: 300, delay: i * 120, fill: "both" });
  });
  await sleep(plan.route.mstEdges.length * 120 + 500);
  if (!alive()) return;

  // ---- 4. Tour
  const ord = plan.route.order.map(P);
  const pts = [...ord, S.home];
  const d = "M" + pts.map(p => `${p.x * PX},${p.y * PX}`).join("L");
  const rl = $("#routeLayer");
  svg("path", { d, class: "route-casing" }, rl);
  const line = svg("path", { d, class: "route-line" }, rl);
  const len = line.getTotalLength();
  const ratio = plan.route.ratioToOptimal;
  ticker("4/5 ROUTE", `Preorder walk ${plan.route.preorderKm.toFixed(2)} km → 2-opt ${plan.route.km.toFixed(2)} km (${plan.route.twoOptMoves} swap${plan.route.twoOptMoves === 1 ? "" : "s"})` + (ratio != null ? ` · ${ratio <= 1.0001 ? "optimal, confirmed by Held-Karp" : (ratio * 100 - 100).toFixed(1) + "% above optimal"}` : ""));
  line.style.strokeDasharray = len; line.style.strokeDashoffset = len;
  await line.animate([{ strokeDashoffset: len }, { strokeDashoffset: 0 }], { duration: REDUCED ? 1 : 1400, easing: "cubic-bezier(.6,0,.2,1)", fill: "forwards" }).finished;
  line.style.strokeDasharray = "none";
  svg("path", { d, class: "route-flow" }, rl);
  $("#mstLayer").animate([{ opacity: 1 }, { opacity: 0 }], { duration: 600, fill: "forwards" });
  plan.stops.forEach((st, i) => {
    const b = svg("g", { class: "stop-badge", transform: `translate(${st.x * PX} ${st.y * PX - 22})` }, rl);
    b.style.animationDelay = (i * 0.12) + "s";
    svg("circle", { r: 11 }, b); svg("text", {}, b).textContent = i + 1;
  });
  if (!alive()) return;

  // ---- 5. Runner + results
  ticker("5/5 PRICE", `Medians by randomized quickselect · basket ${fmtINR(plan.totals.basket)} · ${plan.totals.savedVsMedian >= 0 ? fmtINR(plan.totals.savedVsMedian) + " under" : fmtINR(-plan.totals.savedVsMedian) + " over"} the city median`);
  renderResults(plan);
  if (plan.stops.length && !REDUCED) {
    const dot = svg("circle", { r: 7, class: "runner" }, $("#runnerLayer"));
    const dur = Math.min(4200, 900 + len * 2.2), t0 = performance.now();
    await new Promise(res => {
      const step = now => {
        if (!alive()) return res();
        const f = Math.min(1, (now - t0) / dur), e = f < .5 ? 2 * f * f : 1 - Math.pow(-2 * f + 2, 2) / 2;
        const p = line.getPointAtLength(e * len); dot.setAttribute("cx", p.x); dot.setAttribute("cy", p.y);
        f < 1 ? requestAnimationFrame(step) : res();
      };
      requestAnimationFrame(step);
    });
    dot.animate([{ opacity: 1 }, { opacity: 0 }], { duration: 500, fill: "forwards" });
  }
  if (!alive()) return;
  const best = Math.min(...plan.alternatives.slice(1).map(a => a.total));
  ticker("DONE", plan.stops.length
    ? `${plan.stops.length} stop${plan.stops.length > 1 ? "s" : ""}, ${plan.totals.km.toFixed(1)} km, about ${plan.totals.minutes} min. ` + (best - plan.totals.total > 1 ? `Saves ${fmtINR(best - plan.totals.total)} against the best simple strategy.` : `Matches the best simple strategy for this list.`)
    : "Nothing on this list can be bought right now. See the notes below.");
  if (plan.stops.length && best - plan.totals.total > 1) { const r = $(".tot-wide").getBoundingClientRect(); burst(r, null, 60); }
}

function dist(a, b) { return Math.hypot(a.x - b.x, a.y - b.y); }

function flyLabel(text, from, to, delay) {
  const t = svg("text", { x: from.x * PX, y: from.y * PX, class: "fly", "text-anchor": "middle" }, $("#flyLayer"));
  t.textContent = text;
  const dx = (to.x - from.x) * PX, dy = (to.y - from.y) * PX;
  t.animate([{ transform: "translate(0,0) scale(.6)", opacity: 0 }, { transform: `translate(${dx * .5}px, ${dy * .5 - 40}px) scale(1)`, opacity: 1, offset: .5 },
  { transform: `translate(${dx}px, ${dy}px) scale(.4)`, opacity: 0 }], { duration: REDUCED ? 1 : 900, delay, easing: "cubic-bezier(.4,0,.2,1)", fill: "forwards" })
    .finished.then(() => t.remove());
}

let vbAnim = null;
function zoomTo(points) {
  const m = $("#map");
  const cur = m.viewBox.baseVal, from = [cur.x, cur.y, cur.width, cur.height];
  let to = [0, 0, 800, 600];
  if (points && points.length) {
    const xs = points.map(p => p.x * PX), ys = points.map(p => p.y * PX);
    let x0 = Math.min(...xs) - 70, x1 = Math.max(...xs) + 70, y0 = Math.min(...ys) - 70, y1 = Math.max(...ys) + 70;
    let w = Math.max(x1 - x0, 480), h = Math.max(y1 - y0, 360);
    if (w / h > 4 / 3) h = w * 3 / 4; else w = h * 4 / 3;
    if (w > 800) { w = 800; h = 600; }
    const cx = (x0 + x1) / 2, cy = (y0 + y1) / 2;
    to = [clamp(cx - w / 2, 0, 800 - w), clamp(cy - h / 2, 0, 600 - h), w, h];
  }
  cancelAnimationFrame(vbAnim);
  const t0 = performance.now(), dur = REDUCED ? 1 : 900;
  const step = now => {
    const f = Math.min(1, (now - t0) / dur), e = 1 - Math.pow(1 - f, 3);
    m.setAttribute("viewBox", from.map((v, i) => v + (to[i] - v) * e).join(" "));
    if (f < 1) vbAnim = requestAnimationFrame(step);
  };
  vbAnim = requestAnimationFrame(step);
}

/* ================================================================ results */
function countUp(el, to, fmt, dur = 1100) {
  const t0 = performance.now();
  const step = now => { const f = Math.min(1, (now - t0) / (REDUCED ? 1 : dur)), e = 1 - Math.pow(1 - f, 4); el.textContent = fmt(to * e); if (f < 1) requestAnimationFrame(step); };
  requestAnimationFrame(step);
}
function renderResults(plan) {
  const T = plan.totals;
  const q = k => $(`[data-count="${k}"]`);
  countUp(q("basket"), T.basket, fmtINR);
  countUp(q("km"), T.km, v => v.toFixed(1) + " km");
  countUp(q("stops"), T.stops, v => String(Math.round(v)));
  countUp(q("minutes"), T.minutes, v => Math.round(v) + " min");
  const sv = q("saved"); sv.classList.toggle("good", T.savedVsMedian >= 0); sv.classList.toggle("bad", T.savedVsMedian < 0);
  countUp(sv, Math.abs(T.savedVsMedian), v => (T.savedVsMedian >= 0 ? "−" : "+") + fmtINR(v) + (T.savedVsMedian >= 0 ? " cheaper" : " dearer"));

  // receipt
  const now = new Date();
  const rc = $("#receipt");
  rc.classList.remove("printing"); void rc.offsetWidth; rc.classList.add("printing");
  rc.innerHTML = `<h3>SWEEP · POWAI</h3><p class="meta">${now.toLocaleDateString("en-IN")} · leave ${String(plan.hour).padStart(2, "0")}:00 · ${MODES[S.mode].label}</p>` +
    plan.stops.map((s, i) => `<div class="r-stop"><div class="r-stop-head"><span>${i + 1}. ${esc(s.name)}</span><span>${fmtINR(s.subtotal)}</span></div>
      <div class="r-stop-sub">${esc(s.type)} · +${s.legKm.toFixed(2)} km · arrive ~${s.etaMin} min</div>
      ${s.picks.map(p => { const cls = p.dealScore <= .34 ? "good" : p.dealScore >= .67 ? "bad" : "meh"; const lab = { good: "steal", meh: "fair", bad: "pricey" }[cls];
      return `<div class="r-line"><span>${p.qty}× ${esc(p.item)}<span class="r-deal ${cls}">${lab}</span></span><span>${fmtINR(p.price * p.qty)}</span></div>`; }).join("")}</div>`).join("") +
    `<hr><div class="r-line" style="padding:0"><span>Basket</span><span>${fmtINR(T.basket)}</span></div>
     <div class="r-line" style="padding:0"><span>Travel + stops</span><span>${fmtINR(T.travelCost)}</span></div>
     <div class="r-line" style="padding:0"><span>${T.km.toFixed(2)} km · ${T.minutes} min</span><span></span></div><hr>
     <div class="r-total"><span>TRIP COST</span><span>${fmtINR(T.total)}</span></div><div class="r-barcode"></div>`;
  if (!plan.stops.length) rc.innerHTML = `<p class="receipt-empty">No stops. Nothing on the list can be bought right now.</p>`;

  // compare
  const max = Math.max(...plan.alternatives.map(a => a.total), 1);
  const sweep = plan.alternatives[0], others = plan.alternatives.slice(1);
  const bestOther = others.reduce((a, b) => a.total < b.total ? a : b);
  const save = bestOther.total - sweep.total;
  $("#compareBars").innerHTML = (save > 1
    ? `<p class="verdict">Sweep saves <em>${fmtINR(save)}</em> against “${bestOther.name.toLowerCase()}”${bestOther.km - sweep.km > .05 ? ` and <em>${(bestOther.km - sweep.km).toFixed(1)} km</em> of travel` : ""}.</p>`
    : `<p class="verdict">For this list Sweep <em>ties</em> with the best simple strategy. Longer lists with overlapping shops show bigger gains.</p>`) +
    plan.alternatives.map((a, i) => `<div class="cmp ${i === 0 ? "win" : ""}"><div class="cmp-head"><b>${esc(a.name)}</b><span class="mono">${fmtINR(a.total)} · ${a.stops} stops · ${a.km.toFixed(1)} km · ${a.minutes} min</span></div>
      <div class="cmp-track"><div class="cmp-fill" style="width:${a.total / max * 100}%;animation-delay:${i * .15}s"></div></div><div class="cmp-desc">${esc(a.description)} · basket ${fmtINR(a.basket)}</div></div>`).join("");
  $("#unmet").innerHTML = plan.unmet.map(u => `<div class="unmet"><b>Can't get ${esc(u.item)}:</b> ${esc(u.reason)}</div>`).join("") +
    plan.notices.map(n => `<p class="notice">Note: ${esc(n)}</p>`).join("");
}

/* ================================================================ X-ray */
function renderXray(plan) {
  $("#xrayEmpty").hidden = true; $("#xray").hidden = false;
  const ss = plan.screenStats;
  $("#bloomKpis").innerHTML = kpi(ss.checks, "checks") + kpi(ss.bloomRejected, "ruled out by a 0 bit", "good") +
    kpi(ss.falsePositives, "false positives caught", "bad") + kpi((ss.observedFpRate * 100).toFixed(2) + "%", "observed FP rate") +
    kpi((ss.expectedFpRate * 100).toFixed(2) + "%", "theoretical FP rate", "accent") + kpi((ss.nanos / 1000).toFixed(0) + " µs", "total screen time");

  const cols = S.stores.map(s => s.id);
  const grid = $("#bloomGrid");
  grid.style.gridTemplateColumns = `auto repeat(${cols.length}, 18px)`;
  let html = `<span></span>` + cols.map(id => `<span class="bg-col" title="${esc(S.storeById[id].name)}">${id}</span>`).join("");
  plan.screens.forEach((sc, r) => {
    const v = {}; sc.definitelyNot.forEach(i => v[i] = "no"); sc.falsePositives.forEach(i => v[i] = "fp"); sc.lowStock.forEach(i => v[i] = "low"); sc.confirmed.forEach(i => v[i] = "yes");
    html += `<span class="bg-label" title="${esc(sc.item)}">${esc(sc.item)}</span>` + cols.map((id, c) =>
      `<button class="bc ${v[id] || "closed"}" style="animation-delay:${(r * 0.02 + c * 0.008).toFixed(3)}s" data-s="${id}" data-i="${sc.itemId}" aria-label="${esc(sc.item)} at ${esc(S.storeById[id].name)}: ${{ no: "definitely not", fp: "false positive", low: "low stock", yes: "confirmed" }[v[id]] || "closed"}"></button>`).join("");
  });
  grid.innerHTML = html;
  grid.onclick = e => { const b = e.target.closest(".bc"); if (!b || b.classList.contains("closed")) return; $$(".bc.sel").forEach(x => x.classList.remove("sel")); b.classList.add("sel"); probe(+b.dataset.s, +b.dataset.i); };
  const firstFp = plan.screens.find(s => s.falsePositives.length);
  const sc0 = firstFp || plan.screens[0];
  if (sc0) { const sid = firstFp ? firstFp.falsePositives[0] : (sc0.confirmed[0] || sc0.definitelyNot[0]); if (sid) probe(sid, sc0.itemId); }

  // cover
  const C = plan.cover;
  $("#coverKpis").innerHTML = kpi(fmtINR(C.greedyCost), "greedy visit cost") + (C.exactCost != null ? kpi(fmtINR(C.exactCost), "exact optimum (DP)", "good") + kpi(C.ratio.toFixed(3) + "×", "greedy ÷ optimal, λ = 0", "accent") : "") +
    kpi(C.harmonicBound.toFixed(2) + "×", "worst-case bound H(n)") + kpi(C.candidates, "candidate stores") + kpi((C.nanos / 1000).toFixed(0) + " µs", "greedy time") +
    `<div class="kpi" style="min-width:220px"><b style="font-size:.85rem">${C.guaranteeApplies ? "H(n) guarantee applies" : "λ > 0: price-aware heuristic"}</b><span>${C.guaranteeApplies ? "pure weighted set cover" : "the bound applies only at λ = 0"}</span></div>`;
  const maxR = Math.max(...C.steps.map(s => s.ratio), 1);
  $("#coverSteps").innerHTML = C.steps.map((st, i) => { const s = S.storeById[st.storeId]; return `<li style="--c:${TYPE_COLOR[s.type]};animation-delay:${i * .08}s"><div><div class="cs-name">${esc(s.name)}</div><div class="cs-items">${st.newlyCovered.map(esc).join(" · ")}</div></div>
    <div class="cs-bar">₹${st.ratio.toFixed(1)} / new item<i style="width:${st.ratio / maxR * 100}%;animation-delay:${i * .08}s"></i></div></li>`; }).join("") || `<li><div class="cs-name">No store needed</div></li>`;

  // route
  drawRouteMini(plan);
  $("#routeSeg").onclick = e => { const b = e.target.closest("button"); if (!b) return; S.routeView = b.dataset.r; $$("#routeSeg button").forEach(x => x.setAttribute("aria-checked", x === b)); drawRouteMini(plan); };
  const R = plan.route, rows = [["MST weight (lower bound)", R.mstKm, "", "OPT ≥ MST"], ["Preorder walk (double-tree)", R.preorderKm, "", "≤ 2 × MST"],
  ["Nearest-neighbour heuristic", R.nearestNeighbourKm, "", "no constant-factor guarantee"], ["Sweep: after 2-opt", R.km, "best", `${R.twoOptMoves} uncrossing move${R.twoOptMoves === 1 ? "" : "s"}`]];
  if (R.optimalKm != null) rows.push(["Held-Karp optimum", R.optimalKm, "opt", `ratio ${R.ratioToOptimal.toFixed(3)}×`]);
  const mx = Math.max(...rows.map(r => r[1]), .01);
  $("#routeBars").innerHTML = rows.map(([n, v, c, note], i) => `<div class="rbar ${c}"><div class="rbar-head"><span>${n}</span><span class="mono">${v.toFixed(2)} km</span></div><div class="rbar-track"><div class="rbar-fill" style="width:${v / mx * 100}%;animation-delay:${i * .1}s"></div></div><div class="rbar-note">${note}</div></div>`).join("");

  // deals
  const picks = plan.stops.flatMap(s => s.picks);
  $("#deals").innerHTML = picks.map(p => {
    const span = Math.max(1, p.cityMax - p.cityMin), pos = v => ((v - p.cityMin) / span * 100).toFixed(1);
    return `<div class="deal"><div class="deal-head"><span>${esc(p.item)}</span><span class="mono">${fmtINR(p.price)}</span></div>
      <div class="deal-track"><span class="deal-med" style="left:${pos(p.median)}%" title="median ${fmtINR(p.median)}"></span><span class="deal-you" style="left:${pos(p.price)}%"></span></div>
      <div class="deal-scale"><span>min ${fmtINR(p.cityMin)}</span><span>median ${fmtINR(p.median)}</span><span>max ${fmtINR(p.cityMax)}</span></div>
      <div class="deal-scale"><span>${p.dealScore === 0 ? "lowest price in Powai" : Math.round(p.dealScore * 100) + "% of listings are cheaper"}</span></div></div>`;
  }).join("") || `<p class="muted">No purchases in this plan.</p>`;
}
const kpi = (v, l, c = "") => `<div class="kpi ${c}"><b>${v}</b><span>${l}</span></div>`;

async function probe(storeId, itemId) {
  const box = $("#bitview");
  try {
    const p = await api(`/bloom?store=${storeId}&item=${itemId}`);
    const cls = !p.mightContain ? "no" : p.actuallyStocked ? "yes" : "fp";
    box.innerHTML = `<h4>${esc(p.item)} at ${esc(p.store)}</h4>
      <div><span class="verdict-chip ${cls}">${esc(p.verdict)}</span></div>
      <p class="mono" style="margin:0;font-size:.72rem;color:var(--ink-3)">m = ${p.m} bits · k = ${p.k} hashes · ${p.bitsOn} bits set (${(p.fillRatio * 100).toFixed(1)}% full) · probe positions ${p.positions.join(", ")}</p>
      <div class="bits" id="bits">${[...p.bits].map((b, i) => `<span class="bit ${b === "1" ? "on" : ""}" data-b="${i}"></span>`).join("")}</div>`;
    const bits = $$("#bits .bit");
    for (const [j, pos] of p.positions.entries()) {
      await sleep(130);
      if (!bits[pos]) continue;
      bits[pos].classList.add("probe"); if (!p.bitsSet[j]) bits[pos].classList.add("zero");
    }
  } catch (e) { box.innerHTML = `<p class="muted">Couldn't load the bit array: ${esc(e.message)}</p>`; }
}

function drawRouteMini(plan) {
  const m = $("#routeMini"); m.innerHTML = "";
  const P = id => id === 0 ? S.home : S.storeById[id];
  const all = [S.home, ...plan.stops.map(s => S.storeById[s.storeId])];
  const xs = all.map(p => p.x), ys = all.map(p => p.y);
  const x0 = Math.min(...xs), x1 = Math.max(...xs), y0 = Math.min(...ys), y1 = Math.max(...ys);
  const sc = Math.min(680 / Math.max(x1 - x0, .5), 480 / Math.max(y1 - y0, .5));
  const tx = p => 60 + (p.x - x0) * sc + (680 - (x1 - x0) * sc) / 2, ty = p => 60 + (p.y - y0) * sc + (480 - (y1 - y0) * sc) / 2;
  const cobalt = "var(--cobalt)";
  if (S.routeView === "mst") plan.route.mstEdges.forEach((e, i) => {
    const l = svg("line", { x1: tx(P(e.a)), y1: ty(P(e.a)), x2: tx(P(e.b)), y2: ty(P(e.b)), stroke: "var(--ink-2)", "stroke-width": 3, "stroke-dasharray": "6 6" }, m);
    l.animate([{ opacity: 0 }, { opacity: 1 }], { duration: 300, delay: i * 120, fill: "both" });
    const lb = svg("text", { x: (tx(P(e.a)) + tx(P(e.b))) / 2, y: (ty(P(e.a)) + ty(P(e.b))) / 2 - 6, "text-anchor": "middle", fill: "var(--ink-3)", "font-size": 13, "font-family": "var(--f-mono)" }, m);
    lb.textContent = e.km.toFixed(2);
  });
  else {
    const ids = S.routeView === "pre" ? plan.route.preorder : plan.route.order;
    const pts = [...ids, 0].map(P);
    const p = svg("path", { d: "M" + pts.map(q => `${tx(q)},${ty(q)}`).join("L"), fill: "none", stroke: S.routeView === "pre" ? "var(--ink-2)" : cobalt, "stroke-width": 5, "stroke-linejoin": "round", "stroke-linecap": "round" }, m);
    const L = p.getTotalLength(); p.style.strokeDasharray = L;
    p.animate([{ strokeDashoffset: L }, { strokeDashoffset: 0 }], { duration: REDUCED ? 1 : 1100, easing: "cubic-bezier(.6,0,.2,1)", fill: "forwards" });
  }
  all.forEach((q, i) => {
    const isHome = i === 0;
    svg("circle", { cx: tx(q), cy: ty(q), r: isHome ? 12 : 10, fill: isHome ? "var(--marigold)" : TYPE_COLOR[q.type], stroke: "var(--ink)", "stroke-width": 2.5 }, m);
    const t = svg("text", { x: tx(q), y: ty(q) + (isHome ? 30 : 28), "text-anchor": "middle", fill: "var(--ink-2)", "font-size": 13, "font-weight": 600, "font-family": "var(--f-body)" }, m);
    t.textContent = isHome ? "Home" : q.name.length > 18 ? q.name.slice(0, 17) + "…" : q.name;
  });
}

/* ================================================================ Lab */
const BENCH = [
  { id: "trie", title: "Autocomplete: trie vs. linear scan", q: "Average time per prefix query, top 8 matches, synthetic two-word names." },
  { id: "bloom", title: "Bloom filter: observed vs. theoretical false positives", q: "100,000 non-member probes per configuration. Memory compared with a HashSet." },
  { id: "select", title: "Randomized quickselect vs. first-pivot vs. sort", q: "Finding the median (random input) or maximum (sorted input). The sorted case is the adversarial one." },
  { id: "setcover", title: "Greedy set cover vs. exact DP", q: "20 random instances per size, 30 candidate stores. Exact = DP over 2ⁿ subsets." },
  { id: "tsp", title: "Route quality: double-tree, 2-opt, nearest-neighbour", q: "15 random Powai-sized instances per n. Small n vs. Held-Karp optimum; large n vs. the MST lower bound." }
];
function renderLabShell() {
  $("#lab").innerHTML = BENCH.map(b => `<article class="bench" id="b-${b.id}"><h3>${b.title}</h3><p class="q">${b.q}</p><div class="bench-body"><div class="skel"></div></div>
    <button class="btn ghost" data-run="${b.id}">Re-run on server</button></article>`).join("");
  $("#lab").onclick = e => { const b = e.target.closest("[data-run]"); if (b) runBench(b.dataset.run, true); };
  runAllBench(false);
}
async function runAllBench(fresh) { if (!$("#lab").children.length) renderLabShell(); for (const b of BENCH) await runBench(b.id, fresh); }
async function runBench(id, fresh) {
  const body = $(`#b-${id} .bench-body`);
  body.innerHTML = `<div class="skel"></div>`;
  try {
    const r = await api(`/bench/${id}${fresh ? "?fresh=true" : ""}`);
    body.innerHTML = BENCH_RENDER[id](r.rows) + `<p class="muted mono" style="font-size:.7rem;margin:0">server time ${r.elapsedMs} ms${fresh ? " · fresh run" : ""}</p>`;
  } catch (e) { body.innerHTML = `<p class="err">Benchmark failed: ${esc(e.message)}</p>`; }
}
const tbl = (head, rows) => `<div class="table-wrap"><table><thead><tr>${head.map(h => `<th>${h}</th>`).join("")}</tr></thead><tbody>${rows.map(r => `<tr>${r.map(c => `<td class="mono">${c}</td>`).join("")}</tr>`).join("")}</tbody></table></div>`;
const C1 = "var(--cobalt)", C2 = "var(--ink-3)", C3 = "var(--marigold)", C4 = "var(--bad)", C5 = "var(--good)";
const BENCH_RENDER = {
  trie: rows => barChart({ groups: rows.map(r => "n = " + r.n.toLocaleString()), series: [{ name: "Trie", color: C1, values: rows.map(r => r.trieNsPerQuery) }, { name: "Linear scan", color: C2, values: rows.map(r => r.scanNsPerQuery) }], unit: "ns / query" }) +
    `<p class="takeaway">At 100k names the trie answers <b>${rows[2].speedup}×</b> faster. Its cost depends on prefix length and matches found, while the scan's cost grows with the catalogue.</p>` +
    tbl(["n", "trie ns", "scan ns", "speed-up", "trie nodes"], rows.map(r => [r.n.toLocaleString(), r.trieNsPerQuery, r.scanNsPerQuery, r.speedup + "×", r.trieNodes.toLocaleString()])),
  bloom: rows => {
    const big = rows.filter(r => r.n === 100000);
    return barChart({ groups: big.map(r => "target " + (r.targetFp * 100) + "%"), series: [{ name: "Observed", color: C1, values: big.map(r => r.observedFp * 100) }, { name: "Theory (1−e^−kn/m)^k", color: C3, values: big.map(r => r.theoreticalFp * 100) }], unit: "% false positives (n = 100k)" }) +
      `<p class="takeaway">Observed rates track theory within sampling noise. At 1% a 100k-item sketch takes <b>${(big[1].bloomBytes / 1024).toFixed(0)} KB</b> against about ${(big[1].hashSetBytesEst / 1048576).toFixed(1)} MB for a HashSet (<b>${Math.round(big[1].hashSetBytesEst / big[1].bloomBytes)}× smaller</b>). Lookups are a little slower, but the sketch is small enough to keep on a phone. Zero false negatives in every run.</p>` +
      tbl(["n", "target", "observed", "theory", "m bits", "k", "bytes", "bloom ns", "set ns", "no FN"], rows.map(r => [r.n.toLocaleString(), (r.targetFp * 100) + "%", (r.observedFp * 100).toFixed(3) + "%", (r.theoreticalFp * 100).toFixed(3) + "%", r.m.toLocaleString(), r.k, r.bloomBytes.toLocaleString(), r.bloomNs, r.hashSetNs, r.noFalseNegatives ? "✓" : "✗"]));
  },
  select: rows => {
    const rnd = rows.filter(r => r.input === "random"), srt = rows.filter(r => r.input !== "random");
    const ser = rs => [{ name: "Randomized select", color: C1, points: rs.map(r => [r.n, r.randomizedSelectMs]) }, { name: "First-pivot select", color: C4, points: rs.map(r => [r.n, r.firstPivotSelectMs]) }, { name: "Sort then pick", color: C2, points: rs.map(r => [r.n, r.sortThenPickMs]) }];
    const last = srt[srt.length - 1];
    return `<p class="q"><b>Random input</b></p>` + lineChart({ series: ser(rnd), logX: true, logY: true, xLabel: "n", yLabel: "ms" }) +
      `<p class="q"><b>Sorted input (adversarial)</b></p>` + lineChart({ series: ser(srt), logX: true, logY: true, xLabel: "n", yLabel: "ms" }) +
      `<p class="takeaway">On sorted data the first-pivot version goes quadratic: ×4 n gives ≈ ×${(srt[2].firstPivotSelectMs / srt[1].firstPivotSelectMs).toFixed(0)} time. The random pivot stays linear and is <b>${Math.round(last.firstPivotSelectMs / last.randomizedSelectMs)}× faster</b> at n = ${last.n.toLocaleString()}. On random data selection beats sorting by about ${Math.round(rnd[2].sortThenPickMs / rnd[2].randomizedSelectMs)}×.</p>` +
      tbl(["input", "n", "randomized ms", "first-pivot ms", "sort ms"], rows.map(r => [r.input, r.n.toLocaleString(), r.randomizedSelectMs, r.firstPivotSelectMs, r.sortThenPickMs]));
  },
  setcover: rows => barChart({ groups: rows.map(r => "n = " + r.n), series: [{ name: "Avg greedy/OPT", color: C1, values: rows.map(r => r.avgRatio) }, { name: "Worst greedy/OPT", color: C3, values: rows.map(r => r.worstRatio) }, { name: "Bound H(n)", color: C2, values: rows.map(r => r.boundHn) }], unit: "cost ratio (1 = optimal)", base: 0 }) +
    `<p class="takeaway">The greedy averages <b>${((rows.reduce((a, r) => a + r.avgRatio, 0) / rows.length - 1) * 100).toFixed(1)}%</b> above optimal, well inside the H(n) bound. Exact time roughly doubles each time n grows by one (2ⁿ), while the greedy stays at a few µs: <b>${Math.round(rows[3].exactUs / rows[3].greedyUs)}× faster</b> at n = ${rows[3].n}.</p>` +
    tbl(["n", "avg ratio", "worst", "H(n)", "greedy µs", "exact µs"], rows.map(r => [r.n, r.avgRatio, r.worstRatio, r.boundHn, r.greedyUs, r.exactUs])),
  tsp: rows => {
    const sm = rows.filter(r => r.heldKarpUs != null), lg = rows.filter(r => r.heldKarpUs == null);
    return barChart({ groups: sm.map(r => "n = " + r.n), series: [{ name: "Double-tree", color: C2, values: sm.map(r => r.doubleTreeRatio) }, { name: "Nearest neighbour", color: C4, values: sm.map(r => r.nearestNeighbourRatio) }, { name: "+ 2-opt (Sweep)", color: C1, values: sm.map(r => r.withTwoOptRatio) }], unit: "tour ÷ Held-Karp optimum", base: .9 }) +
      `<p class="takeaway">With 2-opt, Sweep's routes average <b>${((sm.reduce((a, r) => a + r.withTwoOptRatio, 0) / sm.length - 1) * 100).toFixed(1)}%</b> above the true optimum. The proven worst case is 100%. Held-Karp time grows as n²2ⁿ, so for 800 stops only the bound is usable.</p>` +
      tbl(["n", "double-tree", "+2-opt", "NN", "approx µs", "exact µs", "vs"], rows.map(r => [r.n, r.doubleTreeRatio, r.withTwoOptRatio, r.nearestNeighbourRatio, r.approxUs, r.heldKarpUs ?? "—", r.baseline]));
  }
};

function niceMax(v) { const p = Math.pow(10, Math.floor(Math.log10(v || 1))); return Math.ceil(v / p * 1.05) * p; }
function barChart({ groups, series, unit, base = 0 }) {
  const W = 520, H = 230, L = 52, B = 34, T = 26, R = 8;
  const maxV = niceMax(Math.max(...series.flatMap(s => s.values)));
  const y = v => T + (H - T - B) * (1 - (v - base) / (maxV - base));
  const gw = (W - L - R) / groups.length, bw = Math.min(26, (gw - 16) / series.length);
  let s = `<svg class="chart" viewBox="0 0 ${W} ${H + 22}" role="img" aria-label="${esc(unit)}">`;
  for (let i = 0; i <= 4; i++) { const v = base + (maxV - base) * i / 4; s += `<line class="gridl" x1="${L}" x2="${W - R}" y1="${y(v)}" y2="${y(v)}"/><text x="${L - 6}" y="${y(v) + 3}" text-anchor="end">${fmtNum(v)}</text>`; }
  s += `<text x="${L}" y="12" class="lbl">${esc(unit)}</text>`;
  groups.forEach((g, gi) => {
    const x0 = L + gi * gw + (gw - bw * series.length) / 2;
    series.forEach((se, si) => { const v = se.values[gi], yy = y(v); s += `<rect class="bar-s" style="animation-delay:${(gi * series.length + si) * 60}ms" x="${x0 + si * bw + 1}" y="${yy}" width="${bw - 2}" height="${Math.max(0, H - B - yy)}" rx="3" fill="${se.color}"><title>${esc(se.name)}: ${v}</title></rect>`; });
    s += `<text x="${L + gi * gw + gw / 2}" y="${H - B + 16}" text-anchor="middle">${esc(g)}</text>`;
  });
  s += `<line class="axis" x1="${L}" x2="${W - R}" y1="${H - B}" y2="${H - B}"/>`;
  let lx = L; series.forEach(se => { s += `<rect x="${lx}" y="${H + 6}" width="10" height="10" rx="2" fill="${se.color}"/><text x="${lx + 14}" y="${H + 15}" class="lbl" style="font-size:10px">${esc(se.name)}</text>`; lx += 24 + se.name.length * 6; });
  return s + `</svg>`;
}
function lineChart({ series, logX, logY, xLabel, yLabel }) {
  const W = 520, H = 210, L = 52, B = 30, T = 18, R = 12;
  const xs = series.flatMap(s => s.points.map(p => p[0])), ys = series.flatMap(s => s.points.map(p => Math.max(p[1], 1e-3)));
  const fx = logX ? Math.log10 : v => v, fy = logY ? Math.log10 : v => v;
  const [x0, x1] = [fx(Math.min(...xs)), fx(Math.max(...xs))];
  let y0 = Math.floor(fy(Math.min(...ys))), y1 = Math.ceil(fy(Math.max(...ys)));
  if (y1 === y0) y1 = y0 + 1;
  const X = v => L + (W - L - R) * (fx(v) - x0) / ((x1 - x0) || 1), Y = v => T + (H - T - B) * (1 - (fy(Math.max(v, 1e-3)) - y0) / (y1 - y0));
  let s = `<svg class="chart" viewBox="0 0 ${W} ${H + 22}" role="img" aria-label="${esc(yLabel)} vs ${esc(xLabel)}">`;
  for (let e = y0; e <= y1; e++) { const v = logY ? Math.pow(10, e) : e; s += `<line class="gridl" x1="${L}" x2="${W - R}" y1="${Y(v)}" y2="${Y(v)}"/><text x="${L - 6}" y="${Y(v) + 3}" text-anchor="end">${fmtNum(v)}</text>`; }
  [...new Set(xs)].forEach(v => { s += `<text x="${X(v)}" y="${H - B + 16}" text-anchor="middle">${fmtNum(v)}</text>`; });
  s += `<text x="${L}" y="10" class="lbl">${esc(yLabel)} (log)</text><line class="axis" x1="${L}" x2="${W - R}" y1="${H - B}" y2="${H - B}"/>`;
  series.forEach((se, i) => {
    s += `<path class="line-s" style="animation-delay:${i * 150}ms" stroke="${se.color}" d="M${se.points.map(p => `${X(p[0])},${Y(p[1])}`).join("L")}"/>`;
    se.points.forEach(p => s += `<circle cx="${X(p[0])}" cy="${Y(p[1])}" r="3.5" fill="${se.color}"><title>${esc(se.name)}: ${p[1]} ${esc(yLabel)} at n=${p[0]}</title></circle>`);
  });
  let lx = L; series.forEach(se => { s += `<rect x="${lx}" y="${H + 6}" width="10" height="10" rx="2" fill="${se.color}"/><text x="${lx + 14}" y="${H + 15}" class="lbl" style="font-size:10px">${esc(se.name)}</text>`; lx += 26 + se.name.length * 6; });
  return s + `</svg>`;
}
function fmtNum(v) {
  if (v >= 1e6) return (v / 1e6).toFixed(v % 1e6 ? 1 : 0) + "M";
  if (v >= 1e3) return (v / 1e3).toFixed(v % 1e3 ? 1 : 0) + "k";
  if (v < 0.01 && v > 0) return v.toExponential(0);
  return +v.toFixed(2) + "";
}

/* ================================================================ how-it-works */
function renderPipeline() {
  const steps = [
    ["Type your list", "A trie over every word start finds “dal” inside “Toor Dal 1kg” in microseconds.", "Trie · Unit 4"],
    ["Screen 36 shops", "Each shop's stock is a few-hundred-bit Bloom sketch. A zero bit rules a shop out instantly, and every “maybe” is checked against real inventory.", "Bloom filter · Unit 6"],
    ["Pick the fewest shops", "Greedy weighted set cover chooses the shops with the lowest cost per item still needed, then checks itself against an exact DP.", "Set cover · Unit 5"],
    ["Order the stops", "A minimum spanning tree (Kruskal with union-find) becomes a tour at most 2× optimal, and 2-opt uncrosses it.", "Metric TSP · Units 2 & 5"],
    ["Judge every price", "Randomized quickselect finds each item's median price across town, so you know whether you got a deal.", "Quickselect · Unit 6"]
  ];
  $("#pipeline").innerHTML = steps.map(([h, p, t]) => `<li><h4>${h}</h4><p>${p}</p><span class="tag">${t}</span></li>`).join("");
}

/* ================================================================ feedback */
function ticker(step, text) {
  $("#tickerStep").textContent = step; $("#tickerText").textContent = text; $("#tickerText").title = text;
  const t = $("#ticker"); t.classList.remove("flash"); void t.offsetWidth; t.classList.add("flash");
}
function showError(msg, details = []) {
  const el = $("#err");
  el.innerHTML = `<b>${esc(msg)}</b>` + (details.length ? `<ul>${details.map(d => `<li>${esc(d)}</li>`).join("")}</ul>` : "");
  el.hidden = false;
}
function hideError() { $("#err").hidden = true; }
let toastT;
function toast(msg) { const t = $("#toast"); t.textContent = msg; t.hidden = false; clearTimeout(toastT); toastT = setTimeout(() => t.hidden = true, 2600); }

/* confetti-ish particle burst on the overlay canvas */
const fx = $("#fx"), ctx = fx.getContext("2d");
let parts = [], fxRunning = false;
function burst(rect, color, n) {
  if (REDUCED) return;
  const dpr = devicePixelRatio || 1;
  if (fx.width !== innerWidth * dpr) { fx.width = innerWidth * dpr; fx.height = innerHeight * dpr; }
  const cs = getComputedStyle(document.documentElement);
  const palette = color ? [color] : [cs.getPropertyValue("--cobalt"), cs.getPropertyValue("--marigold"), cs.getPropertyValue("--good")];
  for (let i = 0; i < n; i++) parts.push({
    x: rect.left + rect.width / 2, y: rect.top + rect.height / 2, vx: (Math.random() - .5) * 9, vy: -Math.random() * 8 - 2,
    life: 1, size: 3 + Math.random() * 4, rot: Math.random() * 6, vr: (Math.random() - .5) * .3, c: palette[i % palette.length].trim()
  });
  if (!fxRunning) { fxRunning = true; requestAnimationFrame(tickFx); }
}
function tickFx() {
  const dpr = devicePixelRatio || 1;
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0); ctx.clearRect(0, 0, innerWidth, innerHeight);
  parts = parts.filter(p => p.life > 0);
  for (const p of parts) {
    p.vy += .28; p.x += p.vx; p.y += p.vy; p.rot += p.vr; p.life -= .014;
    ctx.save(); ctx.globalAlpha = Math.max(0, p.life); ctx.translate(p.x, p.y); ctx.rotate(p.rot); ctx.fillStyle = p.c; ctx.fillRect(-p.size / 2, -p.size / 2, p.size, p.size * .6); ctx.restore();
  }
  if (parts.length) requestAnimationFrame(tickFx); else { fxRunning = false; ctx.clearRect(0, 0, innerWidth, innerHeight); }
}

boot();
