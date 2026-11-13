import { h, svg, mount, agentOf, fmtTime } from '../core.js';

const STATE_COLOR = { idle: '#5d6890', processing: '#38bdf8', reviewing: '#f59e0b', waiting: '#a78bfa', completed: '#22c55e', error: '#ef4444' };
const ORDER = ['coordinator', 'analyst', 'inspector', 'synthesizer', 'risk', 'critic'];
const W = 700, H = 560, CX = 350, CY = 280, R = 205;
const POS = Object.fromEntries(ORDER.map((id, i) => {
  const a = (-90 + i * 60) * Math.PI / 180;
  return [id, { x: CX + R * Math.cos(a), y: CY + R * Math.sin(a) }];
}));
POS.user = { x: CX, y: CY };
const FLOW_TYPES = new Set(['message', 'conflict', 'revision', 'defense', 'conflict_unresolved', 'user_resolution', 'decision', 'checkpoint']);

const key = (a, b) => [a, b].sort().join('|');

/**
 * Live Agent Radar. All state shown here is derived from real backend events
 * (replayed from the trace on load, then streamed over SSE).
 */
export function createRadar(initialEvents, { isLive }) {
  const events = [...initialEvents];
  let version = latestVersion(events);
  let state, edges, findings, replayTimer = null;

  const edgeLayer = svg('g'), flowLayer = svg('g'), nodeLayer = svg('g');
  const root = svg('svg', { viewBox: `0 0 ${W} ${H}`, role: 'img', 'aria-label': 'Live network of the six agents' },
    svg('defs', {}, svg('filter', { id: 'glow' }, svg('feGaussianBlur', { stdDeviation: 4, result: 'b' }),
      svg('feMerge', {}, svg('feMergeNode', { in: 'b' }), svg('feMergeNode', { in: 'SourceGraphic' }))),
      svg('linearGradient', { id: 'sweepGrad', x1: 0, y1: 0, x2: 1, y2: 0 },
        svg('stop', { offset: 0, 'stop-color': '#a78bfa', 'stop-opacity': 0 }), svg('stop', { offset: 1, 'stop-color': '#a78bfa', 'stop-opacity': 0.35 }))),
    // Radar scope: concentric rings, cross-hairs and a rotating sweep.
    svg('g', { opacity: 0.9 },
      ...[60, 120, 180, 240].map((r) => svg('circle', { cx: CX, cy: CY, r, fill: 'none', stroke: 'rgba(148,163,255,.10)', 'stroke-dasharray': r % 120 ? '2 6' : null })),
      svg('line', { x1: CX - 260, y1: CY, x2: CX + 260, y2: CY, stroke: 'rgba(148,163,255,.07)' }),
      svg('line', { x1: CX, y1: CY - 260, x2: CX, y2: CY + 260, stroke: 'rgba(148,163,255,.07)' }),
      svg('path', { class: 'sweep', d: `M${CX},${CY} L${CX + 250},${CY} A250,250 0 0,0 ${CX + 250 * Math.cos(-0.6)},${CY + 250 * Math.sin(-0.6)} Z`, fill: 'url(#sweepGrad)' })),
    edgeLayer, flowLayer, nodeLayer);
  const feed = h('div.feed');
  const modeLabel = h('span.badge');
  const replayBtn = h('button.btn.small', { onclick: () => replay() }, '⟲ Replay recorded run');
  const counts = h('div.small.muted');

  const el = h('div.radar-wrap',
    h('div.card.radar',
      h('div.card-head', h('div.row', h('h2', 'Live Agent Radar'), modeLabel), h('div.row', replayBtn)),
      root,
      h('div.legend', Object.entries(STATE_COLOR).map(([s, c]) => h('span', h('span.dot', { style: { color: c } }), s)),
        h('span', h('span', { style: { color: 'var(--bad)' } }, '- - -'), 'conflict'), h('span', h('span', { style: { color: 'var(--ok)' } }, '———'), 'revision')),
      counts),
    h('div.card', h('div.card-head', h('h3', 'Event stream'), h('span.small.faint', 'from the Java backend')), feed));

  function reset() {
    state = Object.fromEntries(ORDER.map((id) => [id, { state: 'idle', summary: '' }]));
    edges = {}; findings = {};
    mount(flowLayer);
    feed.replaceChildren();
  }

  function drawEdges() {
    const els = [];
    const ids = [...ORDER, 'user'];
    for (let i = 0; i < ids.length; i++) for (let j = i + 1; j < ids.length; j++) {
      const a = ids[i], b = ids[j], e = edges[key(a, b)];
      if (!e && (a === 'user' || b === 'user')) continue;
      const cls = e?.unresolved ? 'edge conflict' : e?.conflicts ? 'edge conflict' : e?.revisions ? 'edge revision' : e?.messages ? 'edge hot' : 'edge';
      els.push(svg('line', { class: cls, x1: POS[a].x, y1: POS[a].y, x2: POS[b].x, y2: POS[b].y, opacity: e ? 1 : 0.35 }));
      if (e) {
        const mx = (POS[a].x + POS[b].x) / 2, my = (POS[a].y + POS[b].y) / 2;
        const label = [e.messages ? `${e.messages} msg` : '', e.conflicts ? `${e.conflicts} ⚔` : '', e.revisions ? `${e.revisions} ✎` : ''].filter(Boolean).join(' · ');
        els.push(svg('g', {}, svg('rect', { x: mx - label.length * 3.2 - 6, y: my - 9, width: label.length * 6.4 + 12, height: 18, rx: 9, fill: 'var(--bg2)', stroke: 'var(--line2)' }),
          svg('text', { x: mx, y: my + 4, 'text-anchor': 'middle', 'font-size': 10, fill: 'var(--muted)' }, label)));
      }
    }
    mount(edgeLayer, els);
  }

  function drawNodes() {
    const els = [svg('g', {}, svg('circle', { cx: CX, cy: CY, r: 38, fill: 'rgba(16,20,46,.9)', stroke: '#a78bfa', 'stroke-width': 1.5, filter: 'url(#glow)' }),
      svg('text', { x: CX, y: CY - 2, 'text-anchor': 'middle', 'font-size': 18, fill: 'var(--text)' }, '●'),
      svg('text', { x: CX, y: CY + 16, 'text-anchor': 'middle', 'font-size': 11, fill: 'var(--muted)', 'font-weight': 700 }, 'YOU'))];
    for (const id of ORDER) {
      const a = agentOf(id), p = POS[id], s = state[id];
      const col = STATE_COLOR[s.state] || STATE_COLOR.idle;
      els.push(svg('g', { class: `st-${s.state}` },
        svg('title', {}, `${a.label}: ${s.state}${s.summary ? ' — ' + s.summary : ''}`),
        svg('circle', { class: 'node-ring', cx: p.x, cy: p.y, r: 50, fill: 'none', stroke: col, 'stroke-width': 5, opacity: 0.9, filter: s.state === 'processing' ? 'url(#glow)' : null }),
        svg('circle', { cx: p.x, cy: p.y, r: 44, fill: '#0c0f28', stroke: a.hex, 'stroke-width': 2 }),
        svg('text', { x: p.x, y: p.y - 6, 'text-anchor': 'middle', 'font-size': 20, fill: a.hex }, a.icon),
        svg('text', { x: p.x, y: p.y + 12, 'text-anchor': 'middle', 'font-size': 10.5, 'font-weight': 700, fill: 'var(--text)' }, a.label.replace("Devil's ", '')),
        svg('text', { x: p.x, y: p.y + 26, 'text-anchor': 'middle', 'font-size': 10, fill: col, 'font-weight': 700 }, s.state.toUpperCase()),
        findings[id] ? svg('g', {}, svg('circle', { cx: p.x + 38, cy: p.y - 36, r: 12, fill: a.hex }),
          svg('text', { x: p.x + 38, y: p.y - 32, 'text-anchor': 'middle', 'font-size': 11, 'font-weight': 800, fill: '#fff' }, findings[id])) : null));
    }
    mount(nodeLayer, els);
  }

  function animateFlow(from, to, color) {
    if (!POS[from] || !POS[to] || from === to) return;
    const dot = svg('circle', { r: 6, fill: color, filter: 'url(#glow)' });
    flowLayer.append(dot);
    const t0 = performance.now(), dur = 750;
    const step = (t) => {
      const k = Math.min(1, (t - t0) / dur);
      dot.setAttribute('cx', POS[from].x + (POS[to].x - POS[from].x) * k);
      dot.setAttribute('cy', POS[from].y + (POS[to].y - POS[from].y) * k);
      if (k < 1) requestAnimationFrame(step); else dot.remove();
    };
    requestAnimationFrame(step);
  }

  function apply(ev, animate) {
    if (ev.version_no != null && ev.version_no !== version) return;
    const agent = ev.agent, target = ev.target;
    let payload = {};
    try { payload = ev.payload ? JSON.parse(ev.payload) : {}; } catch { /* ignore */ }
    if (ev.type === 'agent_state' && state[agent]) state[agent] = { state: payload.state, summary: ev.summary };
    if (ev.type === 'finding' && agent) findings[agent] = (findings[agent] || 0) + 1;
    if (FLOW_TYPES.has(ev.type)) {
      const a = agent || 'user', b = target || 'user';
      const e = (edges[key(a, b)] ||= { messages: 0, conflicts: 0, revisions: 0, unresolved: 0 });
      if (ev.type === 'message' || ev.type === 'defense') e.messages++;
      if (ev.type === 'conflict') e.conflicts++;
      if (ev.type === 'conflict_unresolved') e.unresolved++;
      if (ev.type === 'revision') e.revisions++;
      if (ev.type === 'user_resolution') e.unresolved = Math.max(0, e.unresolved - 1);
      if (animate) animateFlow(a, b, ev.type.startsWith('conflict') ? '#ef4444' : ev.type === 'revision' ? '#22c55e' : agentOf(a).hex);
    }
    feed.prepend(h('div.feed-item', { style: { '--agent-color': agentOf(agent || 'user').color } },
      h('div.row.between', h('b', agent ? agentOf(agent).label : 'System', target ? ` → ${agentOf(target).label}` : ''), h('span.faint.mono', fmtTime(ev.ts))),
      h('div', ev.summary), h('div.faint.small', ev.type.replace(/_/g, ' '))));
  }

  function render() {
    drawEdges(); drawNodes();
    const n = events.filter((e) => e.version_no === version).length;
    counts.textContent = version ? `Version ${version} · ${n} recorded events · ${Object.values(findings).reduce((a, b) => a + b, 0)} findings` : 'No run yet';
  }

  function setMode(live) {
    modeLabel.className = 'badge ' + (live ? 'info' : '');
    modeLabel.textContent = live ? '● Live' : 'Recorded state';
  }

  function rebuild() {
    reset();
    events.filter((e) => e.version_no === version || (e.version_no == null && false)).forEach((e) => apply(e, false));
    if (!version) feed.append(h('div.state.small', 'Start the analysis to see agents come online.'));
    render();
  }

  function replay() {
    clearInterval(replayTimer);
    const list = events.filter((e) => e.version_no === version);
    if (!list.length) return;
    reset(); render();
    modeLabel.className = 'badge warn'; modeLabel.textContent = '⟲ Replaying recorded events';
    let i = 0;
    replayTimer = setInterval(() => {
      if (i >= list.length) { clearInterval(replayTimer); setMode(isLive()); return; }
      apply(list[i++], true); render();
    }, 260);
  }

  setMode(isLive());
  rebuild();

  return {
    el,
    onLive(ev) {
      events.push(ev);
      if (ev.version_no != null && ev.version_no > (version || 0)) { version = ev.version_no; rebuild(); }
      if (replayTimer) return;
      apply(ev, true); render(); setMode(isLive());
    },
    setLive(live) { if (!replayTimer) setMode(live); },
    destroy() { clearInterval(replayTimer); },
  };
}

function latestVersion(events) {
  return events.reduce((m, e) => (e.version_no != null && e.version_no > m ? e.version_no : m), 0);
}
