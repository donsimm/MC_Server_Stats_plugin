'use strict';
/* Stats-Tafel: liest stats.json und online.json und baut die Seite. Keine Abhaengigkeiten. */

function hash(s) { let h = 2166136261; for (let i = 0; i < s.length; i++) { h ^= s.charCodeAt(i); h = Math.imul(h, 16777619); } return h >>> 0; }
function rng(seed) {
  let a = seed;
  return () => { a |= 0; a = a + 0x6D2B79F5 | 0; let t = Math.imul(a ^ a >>> 15, 1 | a); t = t + Math.imul(t ^ t >>> 7, 61 | t) ^ t; return ((t ^ t >>> 14) >>> 0) / 4294967296; };
}

/* 16x16-Pixelsymbole: '#' voll, '+' halbe Deckkraft (Schattierung), '.' leer */
const blank = () => Array.from({ length: 16 }, () => Array(16).fill('.'));
const rowsOf = g => g.map(r => r.join(''));
const mirror = half => half.map(r => r + [...r].reverse().join(''));
const shiftDown = (rows, top) => { const out = [...Array(top).fill('.'.repeat(16)), ...rows]; while (out.length < 16) out.push('.'.repeat(16)); return out.slice(0, 16); };
const flipX = rows => rows.map(r => [...r].reverse().join(''));
const merge = (a, b) => a.map((r, y) => [...r].map((c, x) => c !== '.' ? c : b[y][x]).join(''));
function outline(rows) {
  rows = shiftDown(rows, 0);
  const solid = (x, y) => x >= 0 && y >= 0 && x < 16 && y < 16 && rows[y][x] !== '.';
  return rows.map((r, y) => [...r].map((c, x) => c === '.' ? '.' : (solid(x - 1, y) && solid(x + 1, y) && solid(x, y - 1) && solid(x, y + 1) ? '+' : '#')).join(''));
}
function seg(g, x0, y0, x1, y1, w, ch) {
  const dx = x1 - x0, dy = y1 - y0;
  for (let y = 0; y < 16; y++) for (let x = 0; x < 16; x++) {
    const px = x + .5, py = y + .5;
    const t = Math.max(0, Math.min(1, ((px - x0) * dx + (py - y0) * dy) / (dx * dx + dy * dy)));
    if (Math.hypot(px - (x0 + t * dx), py - (y0 + t * dy)) <= w / 2) g[y][x] = ch;
  }
}
const ICO = {};
(() => {
  const g = blank();
  for (let y = 0; y < 16; y++) for (let x = 0; x < 16; x++) {
    const d = Math.hypot(x + .5 - 8, y + .5 - 8);
    if (d <= 7.7 && d >= 6.2) g[y][x] = '#'; else if (d < 6.2) g[y][x] = '+';
  }
  seg(g, 8, 8.4, 8, 3.6, 1.7, '#'); seg(g, 8, 8.4, 11.6, 10.4, 1.7, '#');
  ICO.clock = rowsOf(g);
})();
ICO.gem = shiftDown(mirror(['....####', '..##++++', '.#++#+++', '#####+++', '.#+++#++', '..#+++#+', '...#+++#', '....#+++', '.....#++', '......#+', '.......#']), 2);
ICO.skull = shiftDown(mirror(['....####', '..######', '.#######', '.###..##', '.##....#', '.###..##', '.#######', '..######', '...#####', '...#.#.#', '...#.#.#', '....####']), 2);
(() => {
  const g = blank();
  seg(g, 2.4, 13.6, 10, 6, 2, '+');
  for (let y = 0; y < 16; y++) for (let x = 0; x < 16; x++) {
    const px = x + .5, py = y + .5, d = Math.hypot(px - 4, py - 12), a = Math.atan2(12 - py, px - 4);
    if (d >= 7.2 && d <= 9.8 && a >= -0.15 && a <= Math.PI / 2 + 0.15) g[y][x] = '#';
  }
  ICO.pick = rowsOf(g);
})();
(() => {
  const g = blank();
  seg(g, 4.6, 11.4, 14.2, 1.8, 2.2, '#'); seg(g, 2.6, 8.6, 7.4, 13.4, 1.8, '#'); seg(g, 3.4, 12.2, 1.6, 14.4, 1.7, '+');
  ICO.sword = rowsOf(g);
  ICO.swords = merge(ICO.sword, flipX(ICO.sword));
})();
(() => {
  const g = outline(['....#####.......', '....#####.......', '....#####.......', '....#####.......', '....#####.......', '....#####.......', '....######......', '....#########...', '....###########.', '....############', '....############', '...#############', '...#############']).map(r => [...r]);
  for (const y of [11, 12]) for (let x = 3; x < 16; x++) g[y][x] = '#';
  ICO.boot = shiftDown(rowsOf(g), 1);
})();
ICO.star = outline(shiftDown(mirror(['.......#', '......##', '......##', '.....###', '########', '.#######', '..######', '...#####', '...#####', '..######', '..####..', '.###....', '.##.....']), 1));
ICO.heart = outline(shiftDown(mirror(['..#####.', '.#######', '########', '########', '########', '.#######', '..######', '...#####', '....####', '.....###', '......##', '.......#']), 2));
(() => {
  const g = outline(shiftDown(mirror(['.#######', '.#######', '.#######', '.#######', '.#######', '.#######', '..######', '..######', '...#####', '....####', '.....###', '......##', '.......#']), 1)).map(r => [...r]);
  for (let y = 4; y <= 11; y++) { g[y][7] = '#'; g[y][8] = '#'; }
  for (let y = 6; y <= 7; y++) for (let x = 4; x <= 11; x++) g[y][x] = '#';
  ICO.shield = rowsOf(g);
})();
ICO.bolt = outline(shiftDown(['.......#######..', '......#######...', '.....######.....', '....######......', '...#########....', '..#########.....', '........####....', '.......####.....', '......####......', '......###.......', '.....##.........', '.....#..........'], 2));
ICO.scroll = ['................', '...##########...', '...#++++++++#...', '...#+######+#...', '...#++++++++#...', '...#+######+#...', '...#++++++++#...', '...#+######+#...', '...#++++++++#...', '...#+####+++#...', '...#++++++++#...', '...#+######+#...', '...#++++++++#...', '...##########...', '................', '................'];
ICO.cursor = shiftDown(['#...............', '##..............', '#+#.............', '#++#............', '#+++#...........', '#++++#..........', '#+++++#.........', '#++++++#........', '#+++++++#.......', '#++++#####......', '#+#..#++#.......', '##....#++#......', '#.....#++#......', '.......##.......'], 1);
(() => {
  const g = blank();
  for (let y = 0; y < 16; y++) for (let x = 0; x < 16; x++) {
    const px = x + .5, py = y + .5;
    if (Math.hypot(px - 8, py - 8) <= 7.4 && Math.hypot(px - 13.2, py - 2.8) >= 3.4) g[y][x] = '#';
  }
  const o = outline(rowsOf(g)).map(r => [...r]);
  for (const [cx, cy] of [[4, 5], [9, 9], [5, 10], [10, 6], [7, 3], [11, 11]]) for (let dy = 0; dy < 2; dy++) for (let dx = 0; dx < 2; dx++) if (o[cy + dy][cx + dx] !== '.') o[cy + dy][cx + dx] = '#';
  ICO.cookie = rowsOf(o);
})();
(() => {
  const g = blank();
  for (let y = 0; y < 16; y++) for (let x = 0; x < 16; x++) {
    const dx = x + .5 - 8, dy = y + .5 - 8, d = Math.hypot(dx, dy);
    const a = ((Math.atan2(dy, dx) + 2 * Math.PI) % (2 * Math.PI)) / (2 * Math.PI) * 8;
    const tooth = Math.abs(a - Math.round(a)) < 0.28;
    if (d <= 5.8 || (d <= 7.8 && tooth && d > 4)) g[y][x] = '#';
    if (d < 2.4) g[y][x] = '.';
  }
  ICO.gear = outline(rowsOf(g));
})();
function fillTri(g, a, b, c, ch) {
  const side = (p, q, r) => (p[0] - r[0]) * (q[1] - r[1]) - (q[0] - r[0]) * (p[1] - r[1]);
  for (let y = 0; y < 16; y++) for (let x = 0; x < 16; x++) {
    const p = [x + .5, y + .5], d1 = side(p, a, b), d2 = side(p, b, c), d3 = side(p, c, a);
    if (!((d1 < 0 || d2 < 0 || d3 < 0) && (d1 > 0 || d2 > 0 || d3 > 0))) g[y][x] = ch;
  }
}
ICO.arrow = outline(shiftDown(mirror(['.......#', '......##', '.....###', '....####', '...#####', '..######', '.#######', '########', '....####', '....####', '....####', '....####']), 2));
(() => {
  const g = blank();
  const pts = [[3, 13], [12, 11], [4, 9], [12, 7], [4, 5], [12, 3]];
  for (let i = 0; i < pts.length - 1; i++) seg(g, pts[i][0], pts[i][1], pts[i + 1][0], pts[i + 1][1], 2, '#');
  ICO.spring = rowsOf(g);
})();
(() => {
  const g = blank();
  for (const y0 of [4, 8, 12]) for (let x = 0; x < 16; x++) {
    const y = y0 + Math.round(1.6 * Math.sin(x * 0.85 + y0));
    g[Math.max(0, Math.min(15, y))][x] = '#';
    g[Math.max(0, Math.min(15, y + 1))][x] = '#';
  }
  ICO.wave = rowsOf(g);
})();
(() => {
  const g = blank();
  fillTri(g, [1, 14], [14, 1], [14, 11], '#');
  ICO.wing = outline(rowsOf(g));
})();
(() => {
  const g = blank();
  for (let y = 11; y <= 13; y++) for (let x = 2 + (y - 11); x <= 13 - (y - 11); x++) g[y][x] = '#';
  fillTri(g, [9, 2], [9, 10], [14, 10], '#');
  const o = outline(rowsOf(g)).map(r => [...r]);
  for (let y = 2; y <= 10; y++) o[y][8] = '#';
  ICO.boat = rowsOf(o);
})();
(() => {
  const g = blank();
  for (let y = 0; y < 16; y++) for (let x = 0; x < 16; x++) {
    const nx = (x + .5 - 8) / 7.4, ny = (y + .5 - 8) / 4.6;
    if (nx * nx + ny * ny <= 1) g[y][x] = '#';
  }
  const o = outline(rowsOf(g)).map(r => [...r]);
  for (let y = 0; y < 16; y++) for (let x = 0; x < 16; x++) if (Math.hypot(x + .5 - 8, y + .5 - 8) <= 2.6) o[y][x] = '#';
  ICO.eye = rowsOf(o);
})();
(() => {
  const g = blank();
  seg(g, 5, 10.5, 11, 4.5, 4.6, '#');
  seg(g, 10.5, 5, 13.8, 1.7, 2.2, '#');
  seg(g, 3.2, 8.2, 6.5, 11.5, 1.6, '#');
  seg(g, 4.5, 11.5, 1.2, 14.8, 2.6, '+');
  ICO.rocket = rowsOf(g);
})();
(() => {
  const g = blank();
  for (let y = 2; y <= 6; y++) for (let x = 2; x <= 13; x++) g[y][x] = '#';
  const o = outline(rowsOf(g)).map(r => [...r]);
  for (let y = 7; y <= 14; y++) { o[y][7] = '#'; o[y][8] = '#'; }
  ICO.hammer = rowsOf(o);
})();
const icoCache = {};
function icoSVG(name) {
  if (icoCache[name]) return icoCache[name];
  let s = '<svg viewBox="0 0 16 16" shape-rendering="crispEdges" aria-hidden="true">';
  ICO[name].forEach((row, y) => {
    for (let x = 0; x < 16; x++) {
      const c = row[x];
      if (c !== '.') s += `<rect x="${x}" y="${y}" width="1" height="1" fill="currentColor"${c === '+' ? ' fill-opacity=".5"' : ''}/>`;
    }
  });
  return icoCache[name] = s + '</svg>';
}

/* Minecraft-Grasblock (isometrisch, Texturen aus 8x8 Pixeln je Fläche) */
function grassSVG() {
  const r = rng(11);
  const G = ['#5d9e30', '#6cb53a', '#7acb44', '#549128'], D = ['#8b5a2f', '#7a4b25', '#9a6a3a', '#6b4020', '#85542b'];
  const pick = a => a[Math.floor(r() * a.length)];
  const cell = (u, v, c) => `<rect x="${u}" y="${v}" width="1.05" height="1.05" fill="${c}"/>`;
  let top = '', side = '';
  for (let v = 0; v < 8; v++) for (let u = 0; u < 8; u++) {
    top += cell(u, v, pick(G));
    const green = v < 2 || (v === 2 && r() < 0.6) || (v === 3 && r() < 0.2);
    side += cell(u, v, green ? pick(G) : pick(D));
  }
  const shade = o => `<rect width="8" height="8" fill="#000" fill-opacity="${o}"/>`;
  return '<svg viewBox="0 0 96 96" shape-rendering="crispEdges" role="img" aria-label="Grasblock">' +
    `<g transform="matrix(6 3 -6 3 48 0)">${top}</g>` +
    `<g transform="matrix(6 3 0 6 0 24)">${side}${shade(.22)}</g>` +
    `<g transform="matrix(6 -3 0 6 48 48)">${side}${shade(.42)}</g></svg>`;
}

/* ---------- Generierte Pixel-Gesichter (Ersatz, falls kein Kopf-Bild vorhanden ist) ---------- */
const SKIN = ['#f1c9a5', '#d9a579', '#b9835a', '#8d5a3b', '#f7d9c4', '#6e4429'];
const HAIR = ['#2b1b12', '#5a3a20', '#c9a227', '#d04a2a', '#1d1d1d', '#9aa0a6', '#7b3fbf', '#2e6bd0'];
const IRIS = ['#3b6fd4', '#2f8f4e', '#6b4a2b', '#7b3fbf'];
function fallbackAvatar() {
  // Neutraler Standardkopf (wie ein Spieler ohne eigenen Skin), solange kein echter Kopf vorhanden ist
  const skin = '#c69c7b', hair = '#4a3222', iris = '#4b4ea8', mouth = '#8d5a47';
  const rects = [];
  const put = (x, y, w, c) => rects.push(`<rect x="${x}" y="${y}" width="${w}" height="1" fill="${c}"/>`);
  for (let y = 0; y < 8; y++) put(0, y, 8, skin);
  put(1, 0, 6, hair); put(0, 1, 8, hair); put(0, 2, 1, hair); put(7, 2, 1, hair);
  put(1, 3, 1, '#fff'); put(2, 3, 1, iris); put(5, 3, 1, iris); put(6, 3, 1, '#fff');
  put(3, 4, 2, 'rgba(0,0,0,.25)'); put(2, 5, 4, mouth);
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 8 8" shape-rendering="crispEdges">${rects.join('')}</svg>`;
  return 'data:image/svg+xml,' + encodeURIComponent(svg);
}
document.addEventListener('error', e => {
  const t = e.target;
  if (t && t.tagName === 'IMG' && t.dataset.n && !t.dataset.fb) { t.dataset.fb = '1'; t.src = fallbackAvatar(); }
}, true);

/* ---------- Zustand ---------- */
const $ = id => document.getElementById(id);
const view = $('view');
let DATA = null, ONLINE = { players: [] }, IDX = null;
let period = 'all', mode = 'abs', cmp = '', jmode = 'rel', cmpj = '', cur = (location.hash || '').slice(1) || 'start';
let onlineSig = '';

const PERIOD_NAMES = { all: 'Gesamt', month: 'Monat', week: 'Woche', day: 'Heute' };
const SKILL_LABELS = {
  ACROBATICS: 'Akrobatik', ALCHEMY: 'Alchemie', ARCHERY: 'Bogenschiessen', AXES: 'Äxte', EXCAVATION: 'Graben',
  FISHING: 'Angeln', HERBALISM: 'Kräuterkunde', MINING: 'Bergbau', REPAIR: 'Reparieren', SWORDS: 'Schwerter',
  TAMING: 'Zähmen', TRIDENTS: 'Dreizack', CROSSBOWS: 'Armbrust', UNARMED: 'Faustkampf', WOODCUTTING: 'Holzfällen',
  MACES: 'Streitkolben', SPEARS: 'Speere'
};
const skillLabel = k => SKILL_LABELS[k] || (k.charAt(0) + k.slice(1).toLowerCase());

const nf = new Intl.NumberFormat('de-CH');
const nf1 = new Intl.NumberFormat('de-CH', { minimumFractionDigits: 1, maximumFractionDigits: 1 });
const esc = s => String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

function dur(h) {
  let d = Math.floor(h / 24), hh = Math.round(h - d * 24);
  if (hh === 24) { d++; hh = 0; }
  return d > 0 ? `${d} T ${hh} Std` : `${hh} Std`;
}
function big(v) {
  const units = [[1e24, 'Quadr.'], [1e21, 'Trd.'], [1e18, 'Trio.'], [1e15, 'Brd.'], [1e12, 'Bio.'], [1e9, 'Mrd.'], [1e6, 'Mio.']];
  if (v >= 1e27) return v.toExponential(2).replace('e+', ' × 10^');
  for (const [limit, name] of units) if (v >= limit) return nf1.format(v / limit) + ' ' + name;
  return nf.format(Math.round(v));
}
function fmt(c, v) {
  switch (c.format) {
    case 'dur': return dur(v);
    case 'km': return nf1.format(v) + ' km';
    case 'adv': return `${Math.round(v)} / ${c.max || '?'}`;
    case 'big': return big(v);
    case 'level': return `Level ${Math.floor(v + 1e-9)}`;
    case 'dec': return nf1.format(v);
    default: return nf.format(Math.round(v));
  }
}
function fmtChip(c, v) { return c.format === 'dur' ? `${nf.format(Math.round(v / 24))} T` : fmt(c, v); }
function sessStr(m) { return m >= 60 ? `${Math.floor(m / 60)} Std ${m % 60} Min` : `${m} Min`; }
function dateStr(ms) {
  if (!ms) return 'unbekannt';
  const d = new Date(ms), p = n => String(n).padStart(2, '0');
  return `${p(d.getDate())}.${p(d.getMonth() + 1)}.${d.getFullYear()}`;
}
function agoStr(ms) {
  if (!ms) return 'unbekannt';
  const h = Math.max(0, Math.round((Date.now() - ms) / 3600000));
  return h < 48 ? `vor ${h} Std` : `vor ${Math.round(h / 24)} Tg`;
}

async function getJSON(url) {
  const r = await fetch(url + '?t=' + Date.now(), { cache: 'no-store' });
  if (!r.ok) throw new Error(url + ': HTTP ' + r.status);
  return r.json();
}

function buildIndex() {
  IDX = { cat: {}, val: {}, rank: {} };
  for (const c of DATA.categories) {
    IDX.cat[c.id] = c;
    IDX.val[c.id] = {}; IDX.rank[c.id] = {};
    (c.boards.all || []).forEach(([u, v], i) => { IDX.val[c.id][u] = v; IDX.rank[c.id][u] = i + 1; });
  }
}
const nameOf = u => (DATA.players[u] && DATA.players[u].name) || u.slice(0, 8);
const isOnline = u => ONLINE.players.some(p => p.uuid === u);
const boardOf = c => (period !== 'all' && c.boards[period]) ? c.boards[period] : c.boards.all;
const onlyTotal = c => period !== 'all' && !c.boards[period];

/* ---------- Bausteine ---------- */
function avatar(u) { return `<img class="av" src="heads/${u}.png" alt="" loading="lazy" data-n="${esc(nameOf(u))}">`; }
function cardVars(c) { return `--card:${esc(c.color)};`; }
function rowHTML(u, v, i, c, max) {
  const w = max ? ` style="--w:${(v / max * 100).toFixed(1)}%"` : '';
  return `<li><a class="row" href="#profil-${u}" data-go="profil-${u}"${w}><span class="rank${i < 3 ? ' top' : ''}">${i + 1}</span>${avatar(u)}` +
    `<span class="nm">${isOnline(u) ? '<i class="dot" title="online"></i>' : ''}${esc(nameOf(u))}</span><span class="val">${fmt(c, v)}</span></a></li>`;
}
function headHTML(c) {
  const tag = onlyTotal(c) ? '<span class="tag">nur Gesamt</span>' : '';
  return `<div class="card-h"><div><h3>${esc(c.title)}</h3><p>${esc(c.subtitle)}</p></div>${tag}</div>`;
}
function cardHTML(c) {
  const b = boardOf(c);
  const rows = b.length ? `<ol class="rows">${b.slice(0, 10).map(([u, v], i) => rowHTML(u, v, i, c)).join('')}</ol>` : '<p class="none">Noch keine Daten.</p>';
  return `<article class="card${c.night ? ' night' : ''}" data-title="${esc(c.title.toLowerCase())}" style="${cardVars(c)}">${headHTML(c)}${rows}` +
    `<div class="card-f"><a href="#kat-${esc(c.id)}" data-go="kat-${esc(c.id)}">Alle ${b.length} anzeigen →</a><span class="src">Quelle: ${esc(c.source)}</span></div></article>`;
}
function periodCtl(cats) {
  const periods = new Set(['all']);
  (cats || DATA.categories).forEach(c => c.periods.forEach(p => periods.add(p)));
  if (periods.size < 2) return '';
  if (!periods.has(period)) period = 'all';
  return `<div class="bar-tools"><span class="label">Zeitraum</span><div class="seg" role="group" aria-label="Zeitraum">` +
    Object.entries(PERIOD_NAMES).filter(([k]) => periods.has(k)).map(([k, n]) => `<button type="button" data-period="${k}" aria-pressed="${k === period}">${n}</button>`).join('') +
    `</div></div><p class="hint">Zeitfenster gibt es nur für Ranglisten, die AJLeaderboards mitzählt. Alle anderen zeigen weiter den Gesamtwert («nur Gesamt»).</p>`;
}
function onlineInner() {
  const on = ONLINE.players.slice().sort((a, b) => b.sessionMin - a.sessionMin);
  const total = Object.keys(DATA.players).length;
  const head = `<h2 class="sec">Gerade online<small>${on.length} von ${total} · aktualisiert alle 30 Sekunden</small></h2>`;
  if (!on.length) return head + '<p class="empty">Gerade ist niemand online.</p>';
  return head + `<div class="online">${on.map(p => `<a class="pcard" href="#profil-${p.uuid}" data-go="profil-${p.uuid}">` +
    `<img class="av" src="heads/${p.uuid}.png" alt="" loading="lazy" data-n="${esc(p.name)}"><b><i class="dot"></i>${esc(p.name)}</b>` +
    `<span>${esc(p.dim)} · ${sessStr(p.sessionMin)}${p.afk ? ' · AFK' : ''}</span></a>`).join('')}</div>`;
}

/* ---------- Ansichten ---------- */
function gridOf(cats) {
  cats = cats.filter(c => (c.boards.all || []).length);
  return cats.length ? `<div class="grid">${cats.map(cardHTML).join('')}</div>` : '<p class="empty">Keine Ranglisten in diesem Bereich.</p>';
}
function startView() {
  const chips = [[ONLINE.players.length + ' online', 'gerade auf dem Server'], [Object.keys(DATA.players).length, 'Spieler erfasst']];
  for (const chip of DATA.chips) {
    const c = IDX.cat[chip.category];
    if (c) chips.push([fmtChip(c, chip.value), chip.label]);
  }
  const main = DATA.categories.filter(c => c.page === MAIN_PAGE);
  return `<div class="chips">${chips.map(([b, s]) => `<div class="chip-stat"><b>${esc(b)}</b><span>${esc(s)}</span></div>`).join('')}</div>` +
    `<section id="online-sec">${onlineInner()}</section>${periodCtl(main)}` +
    `<h2 class="sec">Ranglisten</h2>${gridOf(main)}`;
}
let catFilter = '';
function pageView(title) {
  const cats = DATA.categories.filter(c => c.page === title);
  // Seiten mit vielen Ranglisten bekommen ein Suchfeld
  const filter = cats.length > 20 ? `<div class="filter"><label for="catfilter">Rangliste suchen</label><input id="catfilter" type="search" value="${esc(catFilter)}" placeholder="z. B. Truhen, Fische, Schaden …" autocomplete="off"></div>` : '';
  return `<h2 class="sec" style="margin-top:0">${esc(title)}<small>${cats.length} Ranglisten</small></h2>${filter}${periodCtl(cats)}${gridOf(cats)}`;
}
function applyCatFilter() {
  const q = catFilter.trim().toLowerCase();
  document.querySelectorAll('#view .grid .card[data-title]').forEach(el => { el.hidden = !!q && !el.dataset.title.includes(q); });
}
/* Museum: Baby und ausgewachsen werden zu einem Eintrag, Sammel-Erfolge entfallen, Kategorien werden vereinheitlicht. */
const MUSE = { q: '', cat: '', player: '', st: '' };
let MUSE_ITEMS = null;
const MUSE_GENERIC = ['Feindliche Mobs', 'Friedliche Mobs', 'Vanilla-Mobs', 'Sonstige'];
/** Kategoriename des Datapacks -> Anzeigename; null = Eintrag weglassen. */
function museumCat(raw) {
  const c = (raw || '').toLowerCase().replace(/[_-]+/g, ' ').trim();
  if (/^end line|heads? collection|^baby\b|^babies$/.test(c)) return null;
  if (/^(canidae|end|sea|passives?|weitere|sonstige)$/.test(c) || c === '') return null; // ausgeblendete Kategorien
  if (/shulker/.test(c)) return 'Shulker';
  if (/^(felines?|cats?|kittens?)$/.test(c)) return 'Katzen';
  if (/^(dogs?|wolf|wolves|baby dogs?)$/.test(c)) return 'Hunde';
  if (/^hostiles?$/.test(c)) return 'Feindliche Mobs';
  if (/^passives?$/.test(c)) return 'Friedliche Mobs';
  if (c === 'vanilla') return 'Vanilla-Mobs';
  return raw.replace(/\b\w/g, ch => ch.toUpperCase());
}
function museumItems() {
  if (MUSE_ITEMS && MUSE_ITEMS.src === DATA.museum) return MUSE_ITEMS.list;
  const map = new Map();
  for (const i of DATA.museum.items) {
    if (/^end line/i.test(i.name)) continue;
    const baby = /\bbaby\b/i.test(i.name);
    const cat = museumCat(i.cat);
    const key = i.name.toLowerCase().replace(/^kill\s+/, '').replace(/\bbaby\s+/, '').replace(/\s+/g, ' ').trim();
    let e = map.get(key);
    if (!e) map.set(key, e = { name: '', cat: null, img: '', by: new Set(), adult: false });
    i.by.forEach(u => e.by.add(u));
    if (cat && (!e.cat || MUSE_GENERIC.includes(e.cat) && !MUSE_GENERIC.includes(cat))) e.cat = cat;
    if (!baby && !e.adult) { e.adult = true; e.name = i.name; e.img = i.img || e.img; }
    else if (!e.name) e.name = i.name.replace(/^kill\s+/i, '').replace(/\bbaby\s+/i, '').replace(/^./, c => c.toUpperCase());
    if (!e.img && i.img) e.img = i.img;
  }
  // Zombie-Dorfbewohner in die Kategorie ihres Bioms verschieben (sofern es eine solche Kategorie gibt)
  const cats = [...new Set([...map.values()].map(e => e.cat).filter(Boolean))];
  for (const e of map.values()) {
    if (!e.cat || !/zombie villager/i.test(e.cat + ' ' + e.name) || !/zombie villager/i.test(e.cat)) continue;
    const biome = ['desert', 'plains', 'savanna', 'snow', 'swamp', 'taiga', 'jungle'].find(b => e.name.toLowerCase().includes(b));
    const target = biome && cats.find(c => !/zombie villager/i.test(c) && (c.toLowerCase().includes(biome) || biome.includes(c.toLowerCase())));
    if (target) e.cat = target;
  }
  const list = [...map.values()].filter(e => e.cat).map(e => ({ name: e.name, cat: e.cat, img: e.img, by: [...e.by] }));
  list.sort((a, b) => a.name.localeCompare(b.name, 'de'));
  MUSE_ITEMS = { src: DATA.museum, list };
  return list;
}
function museumList() {
  const all = museumItems(), q = MUSE.q.trim().toLowerCase(), pl = MUSE.player;
  const has = i => pl ? i.by.includes(pl) : i.by.length > 0;
  const shown = all.filter(i => (!MUSE.cat || i.cat === MUSE.cat) && (!q || i.name.toLowerCase().includes(q) || i.cat.toLowerCase().includes(q)) &&
    (!MUSE.st || (MUSE.st === 'have') === has(i)));
  const got = all.filter(has).length;
  const cats = [];
  shown.forEach(i => {
    let c = cats.find(x => x.title === i.cat);
    if (!c) cats.push(c = { title: i.cat, items: [] });
    c.items.push(i);
  });
  cats.sort((a, b) => a.title.localeCompare(b.title, 'de'));
  const card = i => {
    const on = has(i), by = i.by.slice(0, 4), more = i.by.length - by.length;
    const who = by.map(u => `<a href="#profil-${u}" title="${esc(nameOf(u))}">${avatar(u)}</a>`).join('') + (more > 0 ? `<span class="more">+${more}</span>` : '');
    const img = `<img class="mob" src="${i.img ? 'heads/' + esc(i.img) + '.png' : fallbackAvatar()}" alt="" loading="lazy" data-n="${esc(i.name)}">`;
    return `<div class="muse ${on ? 'have' : 'miss'}">${img}<div class="muse-t"><b>${esc(i.name)}</b>` +
      (i.by.length ? `<span class="muse-by">${who}</span><small>${pl ? (on ? 'gesammelt' : nf.format(i.by.length) + ' Spieler') : nf.format(i.by.length) + ' Spieler'}</small>` : '<small>noch nicht gesammelt</small>') + '</div></div>';
  };
  const sections = cats.map(c => {
    const have = c.items.filter(has).length;
    return `<h2 class="sec">${esc(c.title)}<small>${nf.format(have)} / ${nf.format(c.items.length)}</small></h2><div class="muse-grid">${c.items.map(card).join('')}</div>`;
  }).join('') || '<p class="empty">Keine Treffer.</p>';
  const who = pl ? nameOf(pl) + ' hat ' : '';
  return `<p class="muted">${esc(who)}${nf.format(got)} von ${nf.format(all.length)} Köpfen gesammelt${pl ? '' : ' (alle Spieler zusammen)'}</p>` +
    `<div class="muse-bar"><i style="width:${all.length ? Math.round(got * 100 / all.length) : 0}%"></i></div>${sections}`;
}
function museumView() {
  const all = museumItems();
  const cats = [...new Set(all.map(i => i.cat))].sort((a, b) => a.localeCompare(b, 'de'));
  const users = [...new Set(DATA.museum.items.flatMap(i => i.by))].sort((a, b) => nameOf(a).localeCompare(nameOf(b), 'de'));
  const opt = (v, l, cur) => `<option value="${esc(v)}"${v === cur ? ' selected' : ''}>${esc(l)}</option>`;
  return `<h2 class="sec">${esc(DATA.museum.title || 'Museum')}</h2>` +
    `<div class="filter muse-filter"><input id="mq" type="search" value="${esc(MUSE.q)}" placeholder="Kopf suchen …" autocomplete="off">` +
    `<select id="mcat" aria-label="Kategorie">${opt('', 'Alle Kategorien', MUSE.cat)}${cats.map(c => opt(c, c, MUSE.cat)).join('')}</select>` +
    `<select id="mpl" aria-label="Spieler">${opt('', 'Alle Spieler', MUSE.player)}${users.map(u => opt(u, nameOf(u), MUSE.player)).join('')}</select>` +
    `<select id="mst" aria-label="Status">${opt('', 'Gesammelt und fehlend', MUSE.st)}${opt('have', 'Nur gesammelt', MUSE.st)}${opt('miss', 'Nur fehlend', MUSE.st)}</select></div>` +
    `<div id="muse-list">${museumList()}</div>`;
}
function spielerView() {
  const list = Object.entries(DATA.players).sort((a, b) => a[1].name.localeCompare(b[1].name, 'de'));
  const cards = list.map(([u, p]) => {
    const on = ONLINE.players.find(x => x.uuid === u);
    return `<a class="pcard" href="#profil-${u}" data-go="profil-${u}">${avatar(u)}<b>${on ? '<i class="dot"></i>' : ''}${esc(p.name)}</b>` +
      `<span>${on ? 'online · ' + esc(on.dim) : 'zuletzt online ' + agoStr(p.last)}${p.mcmmo ? ' · mcMMO ' + nf.format(p.mcmmo.power) : ''}</span></a>`;
  }).join('');
  return `<h2 class="sec" style="margin-top:0">Spieler<small>${list.length} erfasst, Suche oben rechts</small></h2><div class="online">${cards}</div>`;
}

function katView(c) {
  const b = boardOf(c), max = b.length ? Math.max(1, b[0][1]) : 1;
  const rows = b.length ? `<ol class="rows">${b.map(([u, v], i) => rowHTML(u, v, i, c, max)).join('')}</ol>` : '<p class="none">Noch keine Daten.</p>';
  return `<a class="back" href="#${pageRoute(c.page)}" data-go="${pageRoute(c.page)}">← ${esc(c.page)}</a>${periodCtl([c])}` +
    `<article class="card narrow bars${c.night ? ' night' : ''}" style="${cardVars(c)}">${headHTML(c)}${rows}` +
    `<div class="card-f"><span>${b.length} Spieler</span><span class="src">Quelle: ${esc(c.source)}</span></div></article>`;
}

function skillKeys() {
  const keys = new Set();
  Object.values(DATA.players).forEach(p => p.mcmmo && Object.keys(p.mcmmo.skills).forEach(k => keys.add(k)));
  return [...keys].sort((a, b) => skillLabel(a).localeCompare(skillLabel(b), 'de'));
}
const mcmmoValue = (pl, k) => (pl.mcmmo && pl.mcmmo.skills[k]) || 0;
function skillMax(keys) {
  return keys.map(k => mode === 'abs' ? DATA.mcmmoRadarMax : Math.max(1, ...Object.values(DATA.players).map(p => mcmmoValue(p, k))));
}

/**
 * Radar als SVG. Die Beschriftungen werden gegeneinander verschoben, damit sie sich nicht überlagern.
 * valueOf(spieler, schluessel) liefert den Wert, maxes die Skala je Achse.
 */
function radarSvg(aria, keys, labelOf, valueOf, maxes, me, other) {
  const n = keys.length, R = 118, cx = 200, cy = 200;
  const pt = (i, f, r = R) => { const a = i * 2 * Math.PI / n - Math.PI / 2; return [cx + Math.cos(a) * r * f, cy + Math.sin(a) * r * f]; };
  const fix = a => a.map(v => v.toFixed(1)).join(',');
  const poly = (pl, dx = 0, dy = 0) => keys.map((k, i) => { const [x, y] = pt(i, Math.min(1, valueOf(pl, k) / maxes[i])); return (x + dx).toFixed(1) + ',' + (y + dy).toFixed(1); }).join(' ');
  let s = `<svg viewBox="-60 -4 520 420" role="img" aria-label="${esc(aria)}">`;
  [.25, .5, .75, 1].forEach(f => { s += `<polygon class="ring${f === 1 ? ' out' : ''}" points="${keys.map((_, i) => fix(pt(i, f))).join(' ')}"/>`; });
  keys.forEach((_, i) => { const [x, y] = pt(i, 1); s += `<line class="axis" x1="${cx}" y1="${cy}" x2="${x.toFixed(1)}" y2="${y.toFixed(1)}"/>`; });
  s += `<polygon points="${poly(me, 5, 5)}" fill="#0c0a12"/>`;
  if (other) {
    s += `<polygon points="${poly(other, 5, 5)}" fill="#0c0a12" opacity=".5"/>`;
    s += `<polygon points="${poly(other)}" fill="#4de3d5" fill-opacity=".6" stroke="#0c0a12" stroke-width="3" stroke-dasharray="7 4"/>`;
  }
  s += `<polygon points="${poly(me)}" fill="#ffd23f" fill-opacity=".88" stroke="#0c0a12" stroke-width="3" stroke-linejoin="miter"/>`;
  keys.forEach((k, i) => { const [x, y] = pt(i, Math.min(1, valueOf(me, k) / maxes[i])); s += `<rect x="${(x - 3.5).toFixed(1)}" y="${(y - 3.5).toFixed(1)}" width="7" height="7" fill="#0c0a12"/>`; });

  // Beschriftungen: Position und grobe Breite berechnen, dann in der oberen und unteren Hälfte entzerren
  const labels = keys.map((k, i) => {
    const a = i * 2 * Math.PI / n - Math.PI / 2, c = Math.cos(a), sn = Math.sin(a);
    const [x, y] = pt(i, 1, R + 14);
    const anchor = c > 0.2 ? 'start' : c < -0.2 ? 'end' : 'middle';
    const text = labelOf(k), w = text.length * 8;
    const x0 = anchor === 'start' ? x : anchor === 'end' ? x - w : x - w / 2;
    return { text, x, by: y + (sn > 0.3 ? 12 : sn < -0.3 ? -2 : 5), x0, x1: x0 + w, anchor, bottom: sn >= 0 };
  });
  for (const bottom of [true, false]) {
    const half = labels.filter(l => l.bottom === bottom).sort((p, q) => p.x0 - q.x0);
    half.forEach((l, i) => {
      for (let guard = 0; guard < 6; guard++) {
        const clash = half.slice(0, i).some(e => e.x0 < l.x1 && l.x0 < e.x1 && Math.abs(l.by - e.by) < 15);
        if (!clash) break;
        l.by += bottom ? 15 : -15;
      }
    });
  }
  labels.forEach(l => { s += `<text class="rl" x="${l.x.toFixed(1)}" y="${l.by.toFixed(1)}" text-anchor="${l.anchor}">${esc(l.text)}</text>`; });
  return s + '</svg>';
}

function legendHTML(P, otherP) {
  return `<div class="legend"><span><i style="background:#ffd23f"></i>${esc(P.name)}</span>${otherP ? `<span><i style="background:#4de3d5"></i>${esc(otherP.name)}</span>` : ''}</div>`;
}
function scaleCtl(attr, current) {
  return `<div class="seg" role="group" aria-label="Skala"><button type="button" ${attr}="abs" aria-pressed="${current === 'abs'}">Absolut</button><button type="button" ${attr}="rel" aria-pressed="${current === 'rel'}">Relativ</button></div>`;
}

function mcmmoCard(u) {
  const P = DATA.players[u];
  if (!P.mcmmo) return '';
  const keys = skillKeys(), maxes = skillMax(keys);
  const otherP = cmp && cmp !== u && DATA.players[cmp] && DATA.players[cmp].mcmmo ? DATA.players[cmp] : null;
  const order = keys.map((k, i) => i).sort((a, b) => mcmmoValue(P, keys[b]) - mcmmoValue(P, keys[a]));
  const list = order.map(i => { const k = keys[i], v = mcmmoValue(P, k); return `<li><span>${esc(skillLabel(k))}</span><span class="meter"><i style="width:${Math.min(100, v / maxes[i] * 100).toFixed(1)}%"></i></span>` +
    `<span class="num">${nf.format(v)}${otherP ? ` <small>${nf.format(mcmmoValue(otherP, k))}</small>` : ''}</span></li>`; }).join('');
  const candidates = Object.entries(DATA.players).filter(([id, p]) => id !== u && p.mcmmo).sort((a, b) => a[1].name.localeCompare(b[1].name, 'de'));
  const opts = '<option value="">– keiner –</option>' + candidates.map(([id, p]) => `<option value="${id}"${id === cmp ? ' selected' : ''}>${esc(p.name)}</option>`).join('');
  return `<h2 class="sec">Skills</h2><article class="card" style="--card:#b9a4ff"><div class="card-h"><div><h3>mcMMO-Skills</h3><p>Skillradar und Level je Fertigkeit</p></div></div>` +
    `<div class="mm"><div>${radarSvg('mcMMO-Skillradar von ' + P.name, keys, skillLabel, mcmmoValue, maxes, P, otherP)}${legendHTML(P, otherP)}</div>` +
    `<div><div class="power"><b>${nf.format(P.mcmmo.power)}</b><span>Power-Level</span></div>` +
    `<div class="ctrl">${scaleCtl('data-mode', mode)}<label for="cmp">Vergleichen mit</label><select id="cmp">${opts}</select></div>` +
    `<ul class="skills">${list}</ul>` +
    `<p class="note">${mode === 'abs' ? 'Absolut: Skala bis Level ' + nf.format(DATA.mcmmoRadarMax) + ' (in der Config einstellbar).' : 'Relativ: 100 % entspricht dem besten Spieler des Servers im jeweiligen Skill.'} Verwerten und Schmelzen fehlen, weil mcMMO sie als Child-Skills ohne eigene Erfahrung führt.</p></div></div>` +
    `<div class="card-f"><span></span><span class="src">Quelle: mcMMO</span></div></article>`;
}

const JOB_LABELS = { Miner: 'Bergarbeiter', Farmer: 'Farmer', Hunter: 'Jäger', Fisher: 'Fischer', Woodcutter: 'Holzfäller' };
const jobLabel = k => JOB_LABELS[k] || k;
const JOB_ABS_MAX = 100;
const jobValue = (pl, k) => (pl.jobs && pl.jobs[k] ? pl.jobs[k].level : 0);
const jobTotal = pl => Object.values(pl.jobs || {}).reduce((a, j) => a + j.level, 0);
function jobKeys() {
  const keys = new Set();
  Object.values(DATA.players).forEach(p => p.jobs && Object.keys(p.jobs).forEach(k => keys.add(k)));
  return [...keys].sort((a, b) => jobLabel(a).localeCompare(jobLabel(b), 'de'));
}
function jobMax(keys) {
  return keys.map(k => jmode === 'abs' ? JOB_ABS_MAX : Math.max(1, ...Object.values(DATA.players).map(p => jobValue(p, k))));
}
function jobsCard(u) {
  const P = DATA.players[u];
  if (!P.jobs) return '';
  const keys = jobKeys(), maxes = jobMax(keys);
  const otherP = cmpj && cmpj !== u && DATA.players[cmpj] && DATA.players[cmpj].jobs ? DATA.players[cmpj] : null;
  const order = keys.map((k, i) => i).sort((a, b) => jobValue(P, keys[b]) - jobValue(P, keys[a]) || ((P.jobs[keys[b]] || {}).xp || 0) - ((P.jobs[keys[a]] || {}).xp || 0));
  const list = order.map(i => {
    const k = keys[i], j = P.jobs[k];
    return `<li><span>${esc(jobLabel(k))}</span><span class="meter"><i style="width:${Math.min(100, jobValue(P, k) / maxes[i] * 100).toFixed(1)}%"></i></span>` +
      `<span class="num">Level ${jobValue(P, k)}${j ? ` <small>${nf.format(Math.round(j.xp))} XP</small>` : ''}${otherP ? ` <small>· ${jobValue(otherP, k)}</small>` : ''}</span></li>`;
  }).join('');
  const candidates = Object.entries(DATA.players).filter(([id, p]) => id !== u && p.jobs).sort((a, b) => a[1].name.localeCompare(b[1].name, 'de'));
  const opts = '<option value="">– keiner –</option>' + candidates.map(([id, p]) => `<option value="${id}"${id === cmpj ? ' selected' : ''}>${esc(p.name)}</option>`).join('');
  return `<h2 class="sec">Jobs</h2><article class="card" style="--card:#ffe08a"><div class="card-h"><div><h3>AethelJobs</h3><p>Jobradar und Level je Beruf</p></div></div>` +
    `<div class="mm"><div>${radarSvg('Jobradar von ' + P.name, keys, jobLabel, jobValue, maxes, P, otherP)}${legendHTML(P, otherP)}</div>` +
    `<div><div class="power"><b>${nf.format(jobTotal(P))}</b><span>Gesamtlevel</span></div>` +
    `<div class="ctrl">${scaleCtl('data-jmode', jmode)}<label for="cmpj">Vergleichen mit</label><select id="cmpj">${opts}</select></div>` +
    `<ul class="skills jobs">${list}</ul>` +
    `<p class="note">${jmode === 'abs' ? 'Absolut: Skala bis Level ' + JOB_ABS_MAX + '.' : 'Relativ: 100 % entspricht dem besten Spieler des Servers im jeweiligen Job.'} Das Gesamtlevel ist die Summe aller Job-Level.</p></div></div>` +
    `<div class="card-f"><span></span><span class="src">Quelle: AethelJobs</span></div></article>`;
}

function statsDetails(u) {
  const rows = DATA.categories.filter(c => c.auto && IDX.val[c.id][u] !== undefined).map(c => {
    const rk = IDX.rank[c.id][u];
    return `<tr><td><a href="#kat-${esc(c.id)}" data-go="kat-${esc(c.id)}">${esc(c.title)}</a></td><td class="r">${fmt(c, IDX.val[c.id][u])}</td><td class="r">Platz ${rk}</td></tr>`;
  }).join('');
  if (!rows) return '';
  return `<h2 class="sec">Alle Statistiken</h2><details class="allstats"><summary>${DATA.categories.filter(c => c.auto && IDX.val[c.id][u] !== undefined).length} Werte anzeigen</summary><div class="tablewrap"><table><tbody>${rows}</tbody></table></div></details>`;
}

function profilView(u) {
  const P = DATA.players[u], on = ONLINE.players.find(p => p.uuid === u);
  const state = on ? `online seit ${sessStr(on.sessionMin)} · ${esc(on.dim)} · Ping ${on.ping} ms${on.afk ? ' · AFK' : ''}` : `Zuletzt online ${agoStr(P.last)}`;
  const hero = `<div class="hero"><article class="card" style="--card:#8fd0ff"><img class="av" src="heads/${u}.png" alt="" data-n="${esc(P.name)}">` +
    `<div><h1>${esc(P.name)}${on ? '<span class="pill">online</span>' : ''}</h1><p>Erstbeitritt ${dateStr(P.first)} · ${state}</p></div></article></div>`;
  const tiles = DATA.categories.filter(c => !c.synthetic && !c.auto && IDX.val[c.id][u] !== undefined).map(c => {
    const rk = IDX.rank[c.id][u], total = (c.boards.all || []).length;
    return `<a class="card tile${c.night ? ' night' : ''}" href="#kat-${esc(c.id)}" data-go="kat-${esc(c.id)}" style="${cardVars(c)}">` +
      `<div class="card-h"><div><h3>${esc(c.title)}</h3></div></div>` +
      `<div class="big">${fmt(c, IDX.val[c.id][u])}</div>` +
      `<div class="pl"><span class="rank${rk <= 3 ? ' top' : ''}">Platz ${rk}</span><span>von ${total}</span></div></a>`;
  }).join('');
  return `<a class="back" href="#start" data-go="start">← Übersicht</a>${hero}` +
    (tiles ? `<h2 class="sec">Platzierungen</h2><div class="tiles">${tiles}</div>` : '') + jobsCard(u) + mcmmoCard(u) + statsDetails(u);
}

const slug = t => String(t).toLowerCase().replace(/ä/g, 'ae').replace(/ö/g, 'oe').replace(/ü/g, 'ue').replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '');
const SKILL_ICONS = { ACROBATICS: 'spring', ALCHEMY: 'gem', ARCHERY: 'arrow', AXES: 'hammer', CROSSBOWS: 'arrow', EXCAVATION: 'pick', FISHING: 'wave', HERBALISM: 'heart', MACES: 'hammer', MINING: 'pick', REPAIR: 'gear', SPEARS: 'sword', SWORDS: 'sword', TAMING: 'heart', TRIDENTS: 'wave', UNARMED: 'shield', WOODCUTTING: 'hammer' };
const SKILL_COLORS = ['#b9a4ff', '#a8c8ff', '#b8f5c8', '#ffd36e', '#ff9fc0', '#7fd4ff', '#f7c8a0', '#d6c2ff', '#b5e07a'];
let PAGES = [];
const HOME_SVG = '<svg viewBox="0 0 24 24" width="22" height="22" fill="currentColor" aria-hidden="true"><path d="M12 2.5 1.5 12h3v9h6v-6h3v6h6v-9h3L12 2.5z"/></svg>';
const DISCORD_SVG = '<svg viewBox="0 0 24 24" width="22" height="22" fill="currentColor" aria-hidden="true"><path d="M20.317 4.3698a19.7913 19.7913 0 00-4.8851-1.5152.0741.0741 0 00-.0785.0371c-.211.3753-.4447.8648-.6083 1.2495-1.8447-.2762-3.68-.2762-5.4868 0-.1636-.3933-.4058-.8742-.6177-1.2495a.077.077 0 00-.0785-.037 19.7363 19.7363 0 00-4.8852 1.515.0699.0699 0 00-.0321.0277C.5334 9.0458-.319 13.5799.0992 18.0578a.0824.0824 0 00.0312.0561c2.0528 1.5076 4.0413 2.4228 5.9929 3.0294a.0777.0777 0 00.0842-.0276c.4616-.6304.8731-1.2952 1.226-1.9942a.076.076 0 00-.0416-.1057c-.6528-.2476-1.2743-.5495-1.8722-.8923a.077.077 0 01-.0076-.1277c.1258-.0943.2517-.1923.3718-.2914a.0743.0743 0 01.0776-.0105c3.9278 1.7933 8.18 1.7933 12.0614 0a.0739.0739 0 01.0785.0095c.1202.099.246.1981.3728.2924a.077.077 0 01-.0066.1276 12.2986 12.2986 0 01-1.873.8914.0766.0766 0 00-.0407.1067c.3604.698.7719 1.3628 1.225 1.9932a.076.076 0 00.0842.0286c1.961-.6067 3.9495-1.5219 6.0023-3.0294a.077.077 0 00.0313-.0552c.5004-5.177-.8382-9.6739-3.5485-13.6604a.061.061 0 00-.0312-.0286zM8.02 15.3312c-1.1825 0-2.1569-1.0857-2.1569-2.419 0-1.3332.9555-2.4189 2.157-2.4189 1.2108 0 2.1757 1.0952 2.1568 2.419 0 1.3332-.9555 2.4189-2.1569 2.4189zm7.9748 0c-1.1825 0-2.1569-1.0857-2.1569-2.419 0-1.3332.9554-2.4189 2.1569-2.4189 1.2108 0 2.1757 1.0952 2.1568 2.419 0 1.3332-.946 2.4189-2.1568 2.4189Z"/></svg>';
const MAIN_PAGE = 'Übersicht';
const pageRoute = p => p === MAIN_PAGE ? 'start' : 'seite-' + slug(p);

/** Pro mcMMO-Skill eine eigene Rangliste (aus den Spielerdaten), fuer den Reiter "mcMMO". */
function addSkillCategories() {
  DATA.categories = DATA.categories.filter(c => !c.synthetic);
  skillKeys().forEach((k, i) => {
    const board = Object.entries(DATA.players).filter(([, p]) => p.mcmmo && p.mcmmo.skills[k] > 0)
      .map(([u, p]) => [u, p.mcmmo.skills[k]]).sort((a, b) => b[1] - a[1]);
    DATA.categories.push({ id: 'mcmmo-' + k.toLowerCase(), page: 'mcMMO', highlight: false, synthetic: true, title: skillLabel(k), subtitle: 'mcMMO-Level',
      icon: SKILL_ICONS[k] || 'star', color: SKILL_COLORS[i % SKILL_COLORS.length], night: false, format: 'int', source: 'mcMMO', periods: ['all'], boards: { all: board } });
  });
}
function buildPages() {
  PAGES = [];
  DATA.categories.forEach(c => { if (!PAGES.includes(c.page)) PAGES.push(c.page); });
}
function currentTab() {
  if (cur === 'start') return 'start';
  if (cur === 'museum') return 'museum';
  if (cur === 'spieler' || cur.startsWith('profil-')) return 'spieler';
  if (cur.startsWith('seite-')) return cur;
  if (cur.startsWith('kat-') && IDX.cat[cur.slice(4)]) return pageRoute(IDX.cat[cur.slice(4)].page);
  return 'start';
}
function renderNav() {
  const tab = currentTab();
  const items = [['start', MAIN_PAGE], ...PAGES.filter(p => p !== MAIN_PAGE).map(p => [pageRoute(p), p]),
    ...(DATA.museum ? [['museum', DATA.museum.title || 'Museum']] : []), ['spieler', 'Spieler']];
  const links = DATA.links || {};
  const tabs = items.map(([id, label]) => `<a href="#${id}" data-go="${id}"${id === tab ? ' aria-current="page"' : ''}>${esc(label)}</a>`).join('');
  // Externe Symbole rechts: Homepage und Discord (Adressen kommen aus der config.yml)
  let ext = '';
  if (links.homepageUrl) ext += `<a class="icon home" href="${esc(links.homepageUrl)}" target="_blank" rel="noopener noreferrer" aria-label="${esc(links.homepageLabel || 'Homepage')}" title="${esc(links.homepageLabel || 'Homepage')}">${HOME_SVG}</a>`;
  if (links.discordUrl) ext += `<a class="icon discord" href="${esc(links.discordUrl)}" target="_blank" rel="noopener noreferrer" aria-label="Discord" title="Discord">${DISCORD_SVG}</a>`;
  $('nav').innerHTML = `<div class="nav-tabs">${tabs}</div>` + (ext ? `<div class="nav-ext">${ext}</div>` : '');
}

function render() {
  if (!DATA) return;
  if (cur.startsWith('kat-') && IDX.cat[cur.slice(4)]) view.innerHTML = katView(IDX.cat[cur.slice(4)]);
  else if (cur.startsWith('profil-') && DATA.players[cur.slice(7)]) view.innerHTML = profilView(cur.slice(7));
  else if (cur === 'spieler') view.innerHTML = spielerView();
  else if (cur === 'museum' && DATA.museum) view.innerHTML = museumView();
  else if (cur.startsWith('seite-') && PAGES.find(p => 'seite-' + slug(p) === cur)) view.innerHTML = pageView(PAGES.find(p => 'seite-' + slug(p) === cur));
  else { cur = 'start'; view.innerHTML = startView(); }
  renderNav();
  applyCatFilter();
}
function renderFooter() {
  const sources = [...new Set(DATA.categories.map(c => c.source))];
  const cookie = DATA.categories.some(c => c.source.indexOf('CookieClicker') >= 0);
  const when = new Date(DATA.generated * 1000);
  const p = n => String(n).padStart(2, '0');
  $('foot').innerHTML = `<div><b>Datenquellen:</b> ${sources.map(esc).join(' · ')}</div>` +
    `<div>Stand: ${p(when.getDate())}.${p(when.getMonth() + 1)}.${when.getFullYear()} ${p(when.getHours())}:${p(when.getMinutes())}${cookie ? ' · Cookie-Clicker-Daten: Plugin CookieClickerZ von KartoffelChipss' : ''}</div>`;
}
function go(t) {
  cur = t; render(); window.scrollTo(0, 0);
  try { history.replaceState(null, '', '#' + t); } catch (e) { /* Adressleiste nicht aenderbar */ }
}

/* ---------- Laden ---------- */
function showError(msg) {
  if (!DATA) view.innerHTML = `<p class="empty err">${esc(msg)}</p>`;
}
async function loadStats() {
  try {
    const next = await getJSON('stats.json');
    const changed = !DATA || next.generated !== DATA.generated;
    next.categories.forEach(c => { if (!c.page) c.page = c.section || 'Ranglisten'; });
    DATA = next; addSkillCategories(); buildIndex(); buildPages();
    document.title = DATA.title;
    if ($('site-title')) $('site-title').textContent = DATA.title;
    if ($('site-sub')) $('site-sub').textContent = DATA.subtitle || '';
    if (changed) { render(); renderFooter(); }
  } catch (e) {
    showError('Noch keine Daten. Der Server erzeugt stats.json kurz nach dem Start (oder mit /serverstats refresh). ' + e.message);
  }
}
async function loadOnline() {
  try { ONLINE = await getJSON('online.json'); } catch (e) { ONLINE = { players: [] }; }
  if (!DATA) return;
  const sig = ONLINE.players.map(p => p.uuid).sort().join(',');
  if (sig !== onlineSig) { onlineSig = sig; render(); return; }
  const sec = $('online-sec');
  if (sec) sec.innerHTML = onlineInner();
}

/* ---------- Ereignisse ---------- */
document.addEventListener('click', e => {
  const g = e.target.closest('[data-go]');
  if (g) { e.preventDefault(); go(g.dataset.go); hideSug(); return; }
  const pb = e.target.closest('[data-period]');
  if (pb) { period = pb.dataset.period; render(); return; }
  const mb = e.target.closest('[data-mode]');
  if (mb) { mode = mb.dataset.mode; render(); return; }
  const jb = e.target.closest('[data-jmode]');
  if (jb) { jmode = jb.dataset.jmode; render(); return; }
  if (!e.target.closest('.search')) hideSug();
});
document.addEventListener('input', e => {
  if (e.target.id === 'catfilter') { catFilter = e.target.value; applyCatFilter(); }
  if (e.target.id === 'mq') { MUSE.q = e.target.value; $('muse-list').innerHTML = museumList(); }
});
document.addEventListener('change', e => {
  if (e.target.id === 'cmp') { cmp = e.target.value; render(); }
  if (e.target.id === 'cmpj') { cmpj = e.target.value; render(); }
  const mk = { mcat: 'cat', mpl: 'player', mst: 'st' }[e.target.id];
  if (mk) { MUSE[mk] = e.target.value; $('muse-list').innerHTML = museumList(); }
});
window.addEventListener('hashchange', () => { const t = location.hash.slice(1) || 'start'; if (t !== cur) { cur = t; render(); } });

const q = $('q'), sug = $('sug');
function hideSug() { sug.hidden = true; }
function matches() {
  const s = q.value.trim().toLowerCase();
  if (!s || !DATA) return [];
  return Object.entries(DATA.players).filter(([, p]) => p.name.toLowerCase().includes(s)).sort((a, b) => a[1].name.localeCompare(b[1].name, 'de')).slice(0, 6);
}
q.addEventListener('input', () => {
  const m = matches();
  sug.innerHTML = m.map(([u, p]) => `<li><button type="button" data-go="profil-${u}">${avatar(u)}<span>${esc(p.name)}</span></button></li>`).join('');
  sug.hidden = !m.length;
});
q.addEventListener('keydown', e => {
  if (e.key === 'Enter') { const m = matches(); if (m.length) { go('profil-' + m[0][0]); q.value = ''; hideSug(); } }
  if (e.key === 'Escape') hideSug();
});
sug.addEventListener('click', () => { q.value = ''; });

$('logo').innerHTML = grassSVG();
// Eigenes Logo: liegt eine Datei logo.png im Ordner der Webseite, ersetzt sie Grasblock, Titel und Untertitel
(function tryLogo(names) {
  if (!names.length) return;
  const file = names[0], probe = new Image();
  probe.onload = () => {
    const brand = document.querySelector('.brand');
    brand.classList.add('has-logo');
    brand.innerHTML = `<img class="logo-img" src="${file}" alt="Startseite">`;
  };
  probe.onerror = () => tryLogo(names.slice(1));
  probe.src = file;
})(['logo.png', 'logo.jpg', 'logo.jpeg', 'logo.webp', 'logo.gif']);
(async () => {
  await Promise.all([loadStats(), loadOnline()]);
  render();
  setInterval(loadStats, 60000);
  setInterval(loadOnline, 15000);
})();
