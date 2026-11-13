import { api, h, svg, mount, agentOf, pct, empty, toast } from '../core.js';

// Fixed palette so exported images look the same as on screen.
const P = { bg: '#11172b', panel: '#1b2340', line: '#3a4670', text: '#e6e9f5', muted: '#8e98b8', ok: '#22c55e', bad: '#ef4444', warn: '#f59e0b', primary: '#6366f1' };
const TYPE = {
  task: { fill: '#312e81', stroke: '#818cf8', icon: '◆' }, assumption: { fill: '#1e293b', stroke: '#94a3b8', icon: 'A' },
  evidence: { fill: '#134e4a', stroke: '#14b8a6', icon: '📎' }, claim: { fill: '#0c4a6e', stroke: '#0ea5e9', icon: '◎' },
  risk: { fill: '#451a03', stroke: '#f59e0b', icon: '▲' }, inconsistency: { fill: '#4c0519', stroke: '#fb7185', icon: '≠' },
  conflict: { fill: '#450a0a', stroke: '#ef4444', icon: '⚔' }, recommendation: { fill: '#3b0764', stroke: '#a855f7', icon: '✦' },
  agent: { fill: '#1b2340', stroke: '#6366f1', icon: '●' }, option: { fill: '#1b2340', stroke: '#6366f1', icon: '?' },
};
const EDGE_COLOR = { supports: P.ok, 'weighs against': P.warn, challenges: P.bad, produced: P.line, assumes: P.line, evidence: P.line, assigned: P.line };

function wrap(text, max) {
  const words = String(text || '').split(/\s+/); const lines = []; let cur = '';
  for (const w of words) { if ((cur + ' ' + w).trim().length > max) { if (cur) lines.push(cur); cur = w; } else cur = (cur + ' ' + w).trim(); }
  if (cur) lines.push(cur);
  return lines;
}

function box(n, x, y, w, hgt, { onClick, selected, subtitle, maxChars = 30, lines = 2 }) {
  const t = TYPE[n.type] || TYPE.claim;
  const stroke = n.agent && n.type !== 'agent' ? agentOf(n.agent).hex : t.stroke;
  const ls = wrap(n.label, maxChars).slice(0, lines);
  const dim = ['rejected', 'withdrawn'].includes(n.status);
  return svg('g', { class: 'board-node' + (selected ? ' selected' : ''), transform: `translate(${x},${y})`, opacity: dim ? 0.5 : 1, onclick: () => onClick(n), tabindex: 0, role: 'button', 'aria-label': n.label },
    svg('rect', { width: w, height: hgt, rx: 10, fill: t.fill, stroke, 'stroke-width': 1.6 }),
    svg('text', { x: 12, y: 20, 'font-size': 13, fill: stroke }, t.icon),
    ...ls.map((l, i) => svg('text', { x: 30, y: 19 + i * 15, 'font-size': 12, 'font-weight': 600, fill: P.text }, l)),
    subtitle ? svg('text', { x: 30, y: hgt - 9, 'font-size': 10, fill: P.muted }, subtitle) : null);
}

function curve(x1, y1, x2, y2, color, label, dashed) {
  const mx = (x1 + x2) / 2;
  return svg('g', {}, svg('path', { d: `M${x1},${y1} C${mx},${y1} ${mx},${y2} ${x2},${y2}`, fill: 'none', stroke: color, 'stroke-width': 1.6, 'stroke-dasharray': dashed ? '5 4' : null, opacity: 0.85 }),
    label ? svg('text', { x: mx, y: (y1 + y2) / 2 - 4, 'font-size': 9.5, fill: color, 'text-anchor': 'middle' }, label) : null);
}

export async function boardTab(taskId, version) {
  let data;
  try { data = await api(`/tasks/${taskId}/board?version=${version}`); }
  catch (err) { return empty('⚠', 'Board unavailable', err.message); }
  const byId = Object.fromEntries(data.nodes.map((n) => [n.id, n]));
  let mode = 'evidence', selected = null;
  const canvas = h('div.board-canvas');
  const panel = h('div.card.node-panel');
  const modes = [['evidence', 'Evidence map'], ['mind', 'Mind map'], ['tree', 'Decision tree'], ['flow', 'Agent workflow']];
  const bar = h('div.row');

  const select = (n) => { selected = n?.id; renderPanel(n); draw(); };

  function renderPanel(n) {
    if (!n) { mount(panel, h('h3', 'Explore'), h('p.muted.small', 'Click any node to see its explanation and how it connects to the rest of the analysis.')); return; }
    const out = data.edges.filter((e) => e.from === n.id), inc = data.edges.filter((e) => e.to === n.id);
    const link = (id, rel) => byId[id] ? h('li', h('a', { href: 'javascript:void 0', onclick: () => select(byId[id]) }, byId[id].label), h('span.faint.small', ` (${rel})`)) : null;
    mount(panel,
      h('div.row', h('span.badge', n.kind || n.type), n.status ? h('span.badge', n.status) : null),
      h('h3', { style: { marginTop: '8px' } }, n.label),
      n.agent ? h('div.small.muted', 'By ', agentOf(n.agent).label) : null,
      n.confidence != null ? h('div.small', 'Confidence ', pct(n.confidence)) : null,
      h('p', { style: { whiteSpace: 'pre-wrap', marginTop: '8px' } }, n.detail || ''),
      n.reasoning?.length ? h('div', h('h4', 'Reasoning'), h('ol.small', n.reasoning.map((r) => h('li', r)))) : null,
      inc.length ? h('div', h('h4', 'Incoming'), h('ul.small', inc.map((e) => link(e.from, e.label)))) : null,
      out.length ? h('div', h('h4', 'Outgoing'), h('ul.small', out.map((e) => link(e.to, e.label)))) : null);
  }

  function draw() {
    mount(bar, modes.map(([m, l]) => h('button.btn.small', { class: m === mode ? 'primary' : '', onclick: () => { mode = m; draw(); } }, l)),
      h('span.grow'), h('button.btn.small', { onclick: () => exportImage('png') }, '⇩ PNG'), h('button.btn.small', { onclick: () => exportImage('svg') }, '⇩ SVG'));
    const s = mode === 'evidence' ? evidenceMap() : mode === 'mind' ? mindMap() : mode === 'tree' ? decisionTree() : workflow();
    mount(canvas, s);
  }

  function frame(w, hgt, ...kids) {
    return svg('svg', { xmlns: 'http://www.w3.org/2000/svg', width: w, height: hgt, viewBox: `0 0 ${w} ${hgt}`, style: `min-width:${w}px;font-family:Segoe UI,system-ui,sans-serif` },
      svg('rect', { width: w, height: hgt, fill: P.bg }), ...kids);
  }

  function evidenceMap() {
    const left = data.nodes.filter((n) => n.type === 'evidence' || n.type === 'assumption');
    const mid = data.nodes.filter((n) => ['claim', 'risk', 'inconsistency'].includes(n.type));
    const conf = data.nodes.filter((n) => n.type === 'conflict');
    const rec = byId.rec;
    const W = 1180, rowH = 66, colX = [30, 330, 640, 920], bw = [250, 270, 230, 230];
    const hgt = Math.max(left.length, mid.length, conf.length, 3) * rowH + 90;
    const pos = {};
    const place = (list, col) => list.forEach((n, i) => (pos[n.id] = { x: colX[col], y: 60 + i * rowH, w: bw[col] }));
    place(left, 0); place(mid, 1);
    conf.forEach((n, i) => {
      const target = data.edges.find((e) => e.from === n.id)?.to;
      const ty = pos[target]?.y ?? 60 + i * rowH;
      let y = ty; while (Object.values(pos).some((p) => p.x === colX[2] && Math.abs(p.y - y) < 54)) y += 56;
      pos[n.id] = { x: colX[2], y, w: bw[2] };
    });
    if (rec) pos.rec = { x: colX[3], y: Math.max(60, hgt / 2 - 40), w: bw[3] };
    const H2 = Math.max(hgt, ...Object.values(pos).map((p) => p.y + 80));
    const edges = data.edges.filter((e) => pos[e.from] && pos[e.to]).map((e) => {
      const a = pos[e.from], b = pos[e.to];
      if (e.label === 'challenges') return curve(b.x + b.w, b.y + 27, a.x, a.y + 27, EDGE_COLOR.challenges, '', true);
      return curve(a.x + a.w, a.y + 27, b.x, b.y + 27, EDGE_COLOR[e.label] || P.line, e.label === 'weighs against' ? 'against' : '');
    });
    const heads = ['Assumptions & evidence', 'Claims, findings & risks', 'Conflicts', 'Recommendation']
      .map((t, i) => svg('text', { x: colX[i], y: 32, 'font-size': 12, 'font-weight': 700, fill: P.muted }, t.toUpperCase()));
    const nodes = Object.entries(pos).map(([id, p]) => {
      const n = byId[id];
      return box(n, p.x, p.y, p.w, id === 'rec' ? 80 : 54, { onClick: select, selected: selected === id, maxChars: Math.floor(p.w / 7.6), lines: id === 'rec' ? 3 : 2,
        subtitle: n.confidence != null ? `${n.agent ? agentOf(n.agent).label + ' · ' : ''}${pct(n.confidence)}${n.status && n.status !== 'active' ? ' · ' + n.status : ''}` : n.status || '' });
    });
    if (!mid.length) return empty('◇', 'Nothing to draw yet', 'This version has no findings.');
    return frame(W, H2, ...heads, ...edges, ...nodes);
  }

  function mindMap() {
    const groups = [
      ['Assumptions', data.nodes.filter((n) => n.type === 'assumption')],
      ['Evidence', data.nodes.filter((n) => n.type === 'evidence')],
      ...['analyst', 'inspector', 'risk'].map((a) => [agentOf(a).label, data.nodes.filter((n) => n.agent === a && n.type !== 'agent')]),
      ['Conflicts', data.nodes.filter((n) => n.type === 'conflict')],
      ['Recommendation', byId.rec ? [byId.rec] : []],
    ].filter(([, l]) => l.length);
    const total = groups.reduce((s, [, l]) => s + l.length, 0);
    const R1 = 200, R2 = Math.max(380, total * 15);
    const S = (R2 + 160) * 2, C = S / 2;
    const els = [];
    let angle = -Math.PI / 2;
    for (const [name, list] of groups) {
      const span = (2 * Math.PI) * (list.length / total);
      const mid = angle + span / 2;
      const gx = C + R1 * Math.cos(mid), gy = C + R1 * Math.sin(mid);
      els.push(svg('line', { x1: C, y1: C, x2: gx, y2: gy, stroke: P.primary, 'stroke-width': 3, opacity: 0.6 }));
      list.forEach((n, i) => {
        const a = angle + span * (i + 0.5) / list.length;
        const x = C + R2 * Math.cos(a), y = C + R2 * Math.sin(a);
        els.push(curve(gx, gy, x, y, TYPE[n.type]?.stroke || P.line));
      });
      els.push(svg('g', {}, svg('rect', { x: gx - 70, y: gy - 16, width: 140, height: 32, rx: 16, fill: P.panel, stroke: P.primary }),
        svg('text', { x: gx, y: gy + 5, 'text-anchor': 'middle', 'font-size': 12, 'font-weight': 700, fill: P.text }, `${name} (${list.length})`)));
      list.forEach((n, i) => {
        const a = angle + span * (i + 0.5) / list.length;
        const x = C + R2 * Math.cos(a), y = C + R2 * Math.sin(a);
        els.push(box(n, x - 85, y - 22, 170, 44, { onClick: select, selected: selected === n.id, maxChars: 21, lines: 2 }));
      });
      angle += span;
    }
    els.push(box(byId.task, C - 110, C - 30, 220, 60, { onClick: select, selected: selected === 'task', maxChars: 28, lines: 3 }));
    return frame(S, S, ...els);
  }

  function decisionTree() {
    const t = data.decisionTree;
    if (!t || !t.options || !t.options.length) return empty('⑂', 'No decision tree', 'This version has no completed recommendation.');
    const opts = t.options;
    const colW = 280, W = Math.max(900, opts.length * colW + 80), C = W / 2;
    const els = [];
    const q = { id: 'q', type: 'option', label: t.question || 'Decision', detail: t.question };
    byId.q = q;
    els.push(box(q, C - 200, 30, 400, 60, { onClick: select, selected: selected === 'q', maxChars: 50, lines: 3 }));
    opts.forEach((o, i) => {
      const x = 40 + i * ((W - 80) / opts.length) + ((W - 80) / opts.length - 240) / 2;
      const color = o.recommended ? P.ok : P.line;
      const ids = [`o${i}`, `w${i}`, `r${i}`];
      byId[ids[0]] = { id: ids[0], type: o.recommended ? 'recommendation' : 'option', label: (o.recommended ? '★ ' : '') + o.label, detail: `${o.label}\n\nChoose when: ${o.when}\n\nExpected outcome: ${o.outcome}`, status: o.recommended ? 'recommended' : '' };
      byId[ids[1]] = { id: ids[1], type: 'assumption', label: 'When: ' + (o.when || '—'), detail: o.when };
      byId[ids[2]] = { id: ids[2], type: 'claim', label: '→ ' + (o.outcome || '—'), detail: o.outcome };
      els.push(curve(C, 90, x + 120, 150, color, o.recommended ? 'recommended' : ''));
      els.push(svg('line', { x1: x + 120, y1: 204, x2: x + 120, y2: 240, stroke: color, 'stroke-width': 1.6 }));
      els.push(svg('line', { x1: x + 120, y1: 310, x2: x + 120, y2: 340, stroke: color, 'stroke-width': 1.6 }));
      els.push(box(byId[ids[0]], x, 150, 240, 54, { onClick: select, selected: selected === ids[0], maxChars: 28 }));
      els.push(box(byId[ids[1]], x, 240, 240, 70, { onClick: select, selected: selected === ids[1], maxChars: 28, lines: 3 }));
      els.push(box(byId[ids[2]], x, 340, 240, 70, { onClick: select, selected: selected === ids[2], maxChars: 28, lines: 3 }));
    });
    return frame(W, 440, ...els);
  }

  function workflow() {
    const lay = { coordinator: [60, 200], analyst: [330, 70], inspector: [330, 200], risk: [330, 330], critic: [600, 200], synthesizer: [870, 200], user: [600, 380] };
    const els = [];
    const counts = {};
    for (const l of data.workflow.links) counts[`${l.source}>${l.target}`] = l.count;
    const pairs = Object.entries(counts).map(([k, c]) => [...k.split('>'), c]).filter(([a, b]) => lay[a] && lay[b]);
    for (const [a, b, c] of pairs) {
      const [x1, y1] = lay[a], [x2, y2] = lay[b];
      els.push(curve(x1 + 200, y1 + 28, x2, y2 + 28, b === 'critic' || a === 'critic' ? P.bad : P.primary, `${c}`));
    }
    for (const [id, [x, y]] of Object.entries(lay)) {
      const a = agentOf(id);
      const n = byId[id] || { id, type: 'agent', label: a.label, detail: (data.workflow.agents.find((g) => g.id === id) || {}).mission || 'The human reviewer', agent: id };
      byId[id] = n;
      els.push(box({ ...n, label: a.label }, x, y, 200, 56, { onClick: select, selected: selected === id, subtitle: id === 'user' ? 'human-in-the-loop' : '' }));
    }
    els.push(svg('text', { x: 30, y: 460, 'font-size': 11, fill: P.muted }, 'Numbers are real message / conflict / revision counts from this version\'s event log.'));
    return frame(1100, 480, ...els);
  }

  async function exportImage(kind) {
    const el = canvas.querySelector('svg');
    if (!el) { toast('Nothing to export', 'warn'); return; }
    const xml = new XMLSerializer().serializeToString(el);
    const name = `agentforge-task-${taskId}-v${version}-${mode}`;
    if (kind === 'svg') { saveBlob(new Blob([xml], { type: 'image/svg+xml' }), name + '.svg'); return; }
    const img = new Image();
    img.onload = () => {
      const scale = 2, c = document.createElement('canvas');
      c.width = el.width.baseVal.value * scale; c.height = el.height.baseVal.value * scale;
      const ctx = c.getContext('2d'); ctx.scale(scale, scale); ctx.drawImage(img, 0, 0);
      c.toBlob((b) => saveBlob(b, name + '.png'), 'image/png');
    };
    img.onerror = () => toast('Could not render PNG; try SVG export', 'bad');
    img.src = 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(xml);
  }
  function saveBlob(blob, name) {
    const url = URL.createObjectURL(blob);
    const a = h('a', { href: url, download: name }); document.body.append(a); a.click(); a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 2000);
  }

  renderPanel(null); draw();
  return h('div.stack', h('div.card.tight', bar),
    h('div.grid', { style: { gridTemplateColumns: 'minmax(0,1fr) 320px', alignItems: 'start' }, class: 'board-grid' }, canvas, panel));
}
