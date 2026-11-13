import { api, h, svg, mount, empty, pct, fmtDate } from '../core.js';

// Chart palette: validated for the dark surface (lightness band, chroma, CVD and normal-vision separation all pass).
// Colour follows the agent, never its rank.
const AGENT_CHART = { coordinator: '#6366f1', risk: '#d97706', analyst: '#0284c7', critic: '#f43f5e', inspector: '#0d9488', synthesizer: '#a855f7' };
const FATE = [['active', 'Stood as written', '#6366f1'], ['approved', 'Upheld by you', '#d97706'], ['revised', 'Revised in debate', '#0284c7'],
  ['withdrawn', 'Withdrawn', '#f43f5e'], ['rejected', 'Rejected by you', '#0d9488']];
const INK = { text: '#eef0ff', muted: '#9aa3cc', faint: '#646d99', grid: 'rgba(148,163,255,.12)' };

let tip;
function tooltip() {
  if (!tip) { tip = h('div.chart-tip'); document.body.append(tip); }
  return {
    show(e, content) { mount(tip, content); tip.style.display = 'block'; const x = Math.min(e.clientX + 14, window.innerWidth - tip.offsetWidth - 10); tip.style.left = x + 'px'; tip.style.top = (e.clientY + 14) + 'px'; },
    hide() { tip.style.display = 'none'; },
  };
}

/** Analytics across every analysis: agent performance, debate outcomes and confidence over time. */
export async function insightsView() {
  const d = await api('/insights');
  const t = d.totals;
  const tile = (value, label, hint) => h('div.card.tight.stat', h('b', value), h('span', label), hint ? h('div.small.faint', { style: { marginTop: '4px' } }, hint) : null);

  if (!t.tasks) return h('div', head(), h('div.card', empty('📈', 'No data yet', 'Run a few analyses (try a template from New Analysis) and insights will appear here.')));

  return h('div.stack',
    head(),
    h('div.grid.g4', tile(t.tasks, 'Analyses'), tile(t.versions, 'Versions run', `${t.whatIfs} What-If scenario(s)`),
      tile(d.agents.find((a) => a.id === 'critic').objections, 'Objections raised', "by the Devil's Advocate"),
      tile(t.avgConfidence == null ? '—' : Math.round(t.avgConfidence * 100) + '%', 'Avg. confidence', `${t.followups} follow-up question(s)`)),
    h('div.grid.g2', { style: { alignItems: 'start' } },
      card('Findings by agent', 'How much each specialist contributed, and how confident it was.', findingsBars(d.agents)),
      card('What happened to each finding', 'The fate of every finding after debate and your decisions.', fateBars(d.agents))),
    h('div.grid.g2', { style: { alignItems: 'start' } },
      card('Recommendation confidence over time', 'Each completed version, in the order it ran. Hover for details.', trendLine(d.confidenceTrend)),
      card('How conflicts were resolved', "Outcome of every objection raised by the Devil's Advocate.", hbars(d.conflictOutcomes.map((r) => [label(r.status), r.n]), '#6366f1'))),
    h('div.grid.g2', { style: { alignItems: 'start' } },
      card('Evidence extraction', 'Which extractor read your uploads.', hbars(d.evidenceMethods.map((r) => [r.method || 'unknown', r.n]), '#0284c7')),
      card('Your decisions at checkpoints', 'Human-in-the-loop interventions.', hbars(d.decisions.map((r) => [`${label(r.checkpoint)} · ${r.action}`, r.n]), '#a855f7'))),
    h('div.small.faint', `${t.events} recorded agent events · ${t.memoryReuses} memory reuse(s) · engines: `,
      d.engines.map((e) => `${e.engine} (${e.n})`).join(', ')));
}

function head() {
  return h('div.page-head', h('div', h('h4', 'Analytics'), h('h1', 'Insights'), h('p.muted', 'How your agent team performs across every analysis — computed live by the Java backend.')));
}

function card(title, sub, body) {
  return h('div.card', h('h3', title), h('p.small.muted', sub), body);
}

const label = (s) => String(s || '').replace(/_/g, ' ');

function findingsBars(agents) {
  const rows = agents.filter((a) => a.findings !== undefined);
  const max = Math.max(1, ...rows.map((r) => r.findings));
  const W = 460, H = 220, pad = { l: 34, r: 10, t: 16, b: 44 }, bw = 64;
  const gap = (W - pad.l - pad.r - rows.length * bw) / (rows.length + 1);
  const y = (v) => pad.t + (H - pad.t - pad.b) * (1 - v / max);
  const tt = tooltip();
  const ticks = [0, Math.ceil(max / 2), max];
  return svg('svg', { viewBox: `0 0 ${W} ${H}`, class: 'chart', role: 'img', 'aria-label': 'Findings by agent' },
    ...ticks.map((v) => svg('g', {}, svg('line', { x1: pad.l, x2: W - pad.r, y1: y(v), y2: y(v), stroke: INK.grid }),
      svg('text', { x: pad.l - 8, y: y(v) + 4, 'text-anchor': 'end', 'font-size': 10, fill: INK.faint }, v))),
    ...rows.map((r, i) => {
      const x = pad.l + gap + i * (bw + gap), top = y(r.findings), h0 = H - pad.b - top;
      const c = AGENT_CHART[r.id];
      const show = (e) => tt.show(e, h('div', h('b', r.label), h('div', `${r.findings} findings`), h('div', `avg confidence ${pct(r.avgConfidence)}`),
        h('div', `challenged ${r.challenged}×`)));
      return svg('g', { onmousemove: show, onmouseleave: () => tt.hide(), style: 'cursor:default' },
        svg('rect', { x: x - 8, y: pad.t, width: bw + 16, height: H - pad.t - pad.b, fill: 'transparent' }),
        svg('path', { d: roundedTop(x, top, bw, h0, 4), fill: c }),
        svg('text', { x: x + bw / 2, y: top - 6, 'text-anchor': 'middle', 'font-size': 12, 'font-weight': 700, fill: INK.text }, r.findings),
        svg('text', { x: x + bw / 2, y: H - pad.b + 16, 'text-anchor': 'middle', 'font-size': 11, fill: INK.muted }, r.label.replace('Research ', '').replace('Evidence ', '').replace(' Assessor', '')),
        svg('text', { x: x + bw / 2, y: H - pad.b + 30, 'text-anchor': 'middle', 'font-size': 10, fill: INK.faint }, `conf ${pct(r.avgConfidence)}`));
    }),
    svg('line', { x1: pad.l, x2: W - pad.r, y1: H - pad.b, y2: H - pad.b, stroke: INK.muted, 'stroke-width': 1 }));
}

function fateBars(agents) {
  const rows = agents.filter((a) => a.findings !== undefined).map((a) => {
    const active = Math.max(0, a.findings - a.approved - a.revised - a.withdrawn - a.rejected);
    return { ...a, active };
  });
  const W = 460, rowH = 34, pad = { l: 92, r: 40, t: 8 }, H = pad.t + rows.length * rowH + 64;
  const max = Math.max(1, ...rows.map((r) => r.findings));
  const scale = (W - pad.l - pad.r) / max;
  const tt = tooltip();
  const els = [];
  rows.forEach((r, i) => {
    const y = pad.t + i * rowH;
    els.push(svg('text', { x: pad.l - 10, y: y + 18, 'text-anchor': 'end', 'font-size': 11, fill: INK.muted }, r.label.replace('Research ', '').replace('Evidence ', '').replace(' Assessor', '')));
    let x = pad.l;
    for (const [key, name, color] of FATE) {
      const v = r[key]; if (!v) continue;
      const w = v * scale;
      els.push(svg('rect', { x: x + 1, y: y + 6, width: Math.max(1, w - 2), height: 18, rx: 3, fill: color,
        onmousemove: (e) => tt.show(e, h('div', h('b', r.label), h('div', `${name}: ${v}`))), onmouseleave: () => tt.hide() }));
      x += w;
    }
    els.push(svg('text', { x: x + 6, y: y + 19, 'font-size': 11, 'font-weight': 700, fill: INK.text }, r.findings));
  });
  const ly = pad.t + rows.length * rowH + 14;
  FATE.forEach(([, name, color], i) => {
    const lx = 8 + (i % 3) * 152, yy = ly + Math.floor(i / 3) * 20;
    els.push(svg('rect', { x: lx, y: yy, width: 10, height: 10, rx: 2, fill: color }), svg('text', { x: lx + 16, y: yy + 9, 'font-size': 10.5, fill: INK.muted }, name));
  });
  return svg('svg', { viewBox: `0 0 ${W} ${H}`, class: 'chart', role: 'img', 'aria-label': 'Finding outcomes by agent' }, ...els);
}

function trendLine(points) {
  if (!points.length) return empty('📉', 'No completed versions yet', '');
  const W = 460, H = 220, pad = { l: 40, r: 16, t: 14, b: 30 };
  const n = points.length;
  const x = (i) => pad.l + (n === 1 ? (W - pad.l - pad.r) / 2 : i * (W - pad.l - pad.r) / (n - 1));
  const y = (v) => pad.t + (H - pad.t - pad.b) * (1 - v);
  const d = points.map((p, i) => `${i ? 'L' : 'M'}${x(i)},${y(p.confidence)}`).join(' ');
  const cross = svg('line', { y1: pad.t, y2: H - pad.b, stroke: INK.muted, 'stroke-dasharray': '3 3', opacity: 0 });
  const tt = tooltip();
  const el = svg('svg', { viewBox: `0 0 ${W} ${H}`, class: 'chart', role: 'img', 'aria-label': 'Recommendation confidence over time' },
    ...[0, 0.5, 1].map((v) => svg('g', {}, svg('line', { x1: pad.l, x2: W - pad.r, y1: y(v), y2: y(v), stroke: INK.grid }),
      svg('text', { x: pad.l - 8, y: y(v) + 4, 'text-anchor': 'end', 'font-size': 10, fill: INK.faint }, `${v * 100}%`))),
    svg('path', { d: `${d} L${x(n - 1)},${y(0)} L${x(0)},${y(0)} Z`, fill: 'url(#trendFill)' }),
    svg('defs', {}, svg('linearGradient', { id: 'trendFill', x1: 0, y1: 0, x2: 0, y2: 1 },
      svg('stop', { offset: 0, 'stop-color': '#a855f7', 'stop-opacity': 0.25 }), svg('stop', { offset: 1, 'stop-color': '#a855f7', 'stop-opacity': 0 }))),
    svg('path', { d, fill: 'none', stroke: '#a855f7', 'stroke-width': 2, 'stroke-linejoin': 'round' }),
    cross,
    ...points.map((p, i) => svg('circle', { cx: x(i), cy: y(p.confidence), r: 4.5, fill: '#a855f7', stroke: '#0c0f28', 'stroke-width': 2 })),
    svg('text', { x: pad.l, y: H - 8, 'font-size': 10, fill: INK.faint }, fmtDate(points[0].created_at)),
    n > 1 ? svg('text', { x: W - pad.r, y: H - 8, 'text-anchor': 'end', 'font-size': 10, fill: INK.faint }, fmtDate(points[n - 1].created_at)) : null);
  el.addEventListener('mousemove', (e) => {
    const r = el.getBoundingClientRect();
    const px = (e.clientX - r.left) * (W / r.width);
    let best = 0; points.forEach((_, i) => { if (Math.abs(x(i) - px) < Math.abs(x(best) - px)) best = i; });
    const p = points[best];
    cross.setAttribute('x1', x(best)); cross.setAttribute('x2', x(best)); cross.setAttribute('opacity', 1);
    tt.show(e, h('div', h('b', p.title), h('div', `v${p.version_no} · ${p.change_note || ''}`), h('div', `confidence ${pct(p.confidence)}`), h('div.faint', fmtDate(p.created_at))));
  });
  el.addEventListener('mouseleave', () => { cross.setAttribute('opacity', 0); tt.hide(); });
  return el;
}

function hbars(rows, color) {
  if (!rows.length) return empty('·', 'Nothing yet', '');
  const sorted = [...rows].sort((a, b) => b[1] - a[1]);
  const W = 460, rowH = 30, pad = { l: 170, r: 40 }, H = sorted.length * rowH + 8;
  const max = Math.max(1, ...sorted.map((r) => r[1]));
  const tt = tooltip();
  return svg('svg', { viewBox: `0 0 ${W} ${H}`, class: 'chart', role: 'img' },
    ...sorted.map(([name, v], i) => {
      const y = 4 + i * rowH, w = Math.max(2, (W - pad.l - pad.r) * v / max);
      return svg('g', { onmousemove: (e) => tt.show(e, h('div', h('b', name), h('div', `${v}`))), onmouseleave: () => tt.hide() },
        svg('rect', { x: 0, y, width: W, height: rowH - 2, fill: 'transparent' }),
        svg('text', { x: pad.l - 10, y: y + 18, 'text-anchor': 'end', 'font-size': 11, fill: INK.muted }, name.length > 26 ? name.slice(0, 25) + '…' : name),
        svg('path', { d: roundedRight(pad.l, y + 6, w, 16, 4), fill: color }),
        svg('text', { x: pad.l + w + 6, y: y + 18, 'font-size': 11, 'font-weight': 700, fill: INK.text }, v));
    }));
}

function roundedTop(x, y, w, hgt, r) {
  if (hgt <= 0) return '';
  r = Math.min(r, hgt, w / 2);
  return `M${x},${y + hgt} V${y + r} Q${x},${y} ${x + r},${y} H${x + w - r} Q${x + w},${y} ${x + w},${y + r} V${y + hgt} Z`;
}
function roundedRight(x, y, w, hgt, r) {
  r = Math.min(r, w / 2, hgt / 2);
  return `M${x},${y} H${x + w - r} Q${x + w},${y} ${x + w},${y + r} V${y + hgt - r} Q${x + w},${y + hgt} ${x + w - r},${y + hgt} H${x} Z`;
}
