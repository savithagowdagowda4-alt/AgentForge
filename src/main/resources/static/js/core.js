// Shared frontend helpers: API client, DOM builder, toasts, modal, formatting, state views.

export const AGENTS = {
  coordinator: { label: 'Coordinator', color: 'var(--c-coordinator)', hex: '#6366f1', icon: '◆' },
  analyst: { label: 'Research Analyst', color: 'var(--c-analyst)', hex: '#0ea5e9', icon: '◎' },
  inspector: { label: 'Evidence Inspector', color: 'var(--c-inspector)', hex: '#14b8a6', icon: '◉' },
  risk: { label: 'Risk Assessor', color: 'var(--c-risk)', hex: '#f59e0b', icon: '▲' },
  critic: { label: "Devil's Advocate", color: 'var(--c-critic)', hex: '#ef4444', icon: '✕' },
  synthesizer: { label: 'Synthesizer', color: 'var(--c-synthesizer)', hex: '#a855f7', icon: '✦' },
  user: { label: 'You', color: 'var(--c-user)', hex: '#e2e8f0', icon: '●' },
};
export const agentOf = (id) => AGENTS[id] || { label: id || 'System', color: 'var(--muted)', hex: '#8e98b8', icon: '•' };

export class ApiError extends Error {
  constructor(status, message) { super(message); this.status = status; }
}

export async function api(path, opts = {}) {
  const init = { method: opts.method || 'GET', headers: {}, credentials: 'same-origin' };
  if (opts.body instanceof FormData) init.body = opts.body;
  else if (opts.body !== undefined) { init.body = JSON.stringify(opts.body); init.headers['Content-Type'] = 'application/json'; }
  let res;
  try { res = await fetch('/api' + path, init); }
  catch { throw new ApiError(0, 'Cannot reach the AgentForge server. Is it running?'); }
  const type = res.headers.get('content-type') || '';
  const data = type.includes('json') ? await res.json().catch(() => ({})) : await res.text();
  if (res.status === 401 && !path.startsWith('/auth')) { window.dispatchEvent(new Event('af:unauthorized')); }
  if (!res.ok) throw new ApiError(res.status, (data && data.error) || `Request failed (${res.status})`);
  return data;
}

/** Hyperscript-style element builder. h('div.card#x', {onclick}, children...) */
export function h(sel, attrs, ...kids) {
  if (attrs == null || typeof attrs !== 'object' || attrs instanceof Node || Array.isArray(attrs)) { kids.unshift(attrs); attrs = {}; }
  const [, tag = 'div', rest = ''] = sel.match(/^([a-z0-9-]*)(.*)$/i);
  const el = document.createElement(tag || 'div');
  rest.replace(/([.#])([\w-]+)/g, (_, t, v) => { if (t === '.') el.classList.add(v); else el.id = v; });
  for (const [k, v] of Object.entries(attrs)) {
    if (v == null || v === false) continue;
    if (k.startsWith('on')) el.addEventListener(k.slice(2).toLowerCase(), v);
    else if (k === 'style' && typeof v === 'object') {
      for (const [p, val] of Object.entries(v)) {
        if (p.startsWith('--')) el.style.setProperty(p, val); else el.style[p] = val;
      }
    }
    else if (k === 'html') el.innerHTML = v;
    else if (k === 'class') el.className += ' ' + v;
    else if (k === 'value') el.value = v;
    else if (k in el && k !== 'list' && typeof v !== 'string') el[k] = v;
    else el.setAttribute(k, v === true ? '' : v);
  }
  append(el, kids);
  return el;
}
function append(el, kids) {
  for (const k of kids.flat(Infinity)) {
    if (k == null || k === false) continue;
    el.append(k instanceof Node ? k : document.createTextNode(String(k)));
  }
}
export const svg = (tag, attrs = {}, ...kids) => {
  const el = document.createElementNS('http://www.w3.org/2000/svg', tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (v == null) continue;
    if (k.startsWith('on')) el.addEventListener(k.slice(2).toLowerCase(), v); else el.setAttribute(k, v);
  }
  append(el, kids);
  return el;
};

export function mount(container, ...nodes) { container.replaceChildren(...nodes.flat().filter(Boolean)); }

export function toast(msg, kind = 'info', ms = 4500) {
  const t = h('div.toast', { class: kind }, msg);
  document.getElementById('toasts').append(t);
  setTimeout(() => t.remove(), ms);
}

export function modal(content, { wide = false } = {}) {
  const m = document.getElementById('modal');
  const card = document.getElementById('modalCard');
  card.style.width = wide ? 'min(1000px, 100%)' : '';
  mount(card, content);
  m.classList.remove('hidden');
  const close = () => { m.classList.add('hidden'); card.replaceChildren(); };
  m.onclick = (e) => { if (e.target === m) close(); };
  return close;
}
export function closeModal() { document.getElementById('modal').classList.add('hidden'); }

export function confirmDialog(title, text, okLabel = 'Confirm', danger = false) {
  return new Promise((resolve) => {
    const close = modal(h('div.stack',
      h('h2', title), h('p.muted', text),
      h('div.row', { style: { justifyContent: 'flex-end' } },
        h('button.btn', { onclick: () => { close(); resolve(false); } }, 'Cancel'),
        h('button.btn', { class: danger ? 'danger' : 'primary', onclick: () => { close(); resolve(true); } }, okLabel))));
  });
}

export const loading = (label = 'Loading…') => h('div.state', h('div.spinner'), h('p', { style: { marginTop: '10px' } }, label));
export const empty = (icon, title, text, action) => h('div.state', h('div.big-ico', icon), h('h3', title), text ? h('p', text) : null, action || null);
export const errorState = (err, retry) => h('div.state.error', h('div.big-ico', '⚠'), h('h3', 'Something went wrong'),
  h('p', err?.message || String(err)), retry ? h('button.btn', { onclick: retry }, 'Retry') : null);

export function fmtDate(s) {
  if (!s) return '';
  const d = new Date(String(s).replace(' ', 'T'));
  return isNaN(d) ? String(s) : d.toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' });
}
export function fmtTime(s) {
  const d = new Date(String(s).replace(' ', 'T'));
  return isNaN(d) ? '' : d.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit', second: '2-digit' });
}
export const pct = (x) => x == null ? '—' : Math.round(x * 100) + '%';

const STATUS = {
  draft: ['Draft', ''], running: ['Running', 'info'], awaiting_approval: ['Needs approval', 'warn'], completed: ['Completed', 'ok'],
  failed: ['Failed', 'bad'], stopped: ['Stopped', ''], interrupted: ['Interrupted', 'warn'],
};
export function statusBadge(s) {
  const [label, cls] = STATUS[s] || [s, ''];
  return h('span.badge', { class: cls }, s === 'running' ? h('span.spinner', { style: { width: '10px', height: '10px', borderWidth: '2px' } }) : null, label);
}
export function engineBadge(engine) {
  if (!engine) return null;
  return engine === 'claude' ? h('span.badge.primary', { title: 'Agents reasoned with Claude' }, '✦ Claude AI')
    : h('span.badge.warn', { title: 'No AI service configured: agents used deterministic Java rules' }, '⚙ Rule-based');
}
export function agentChip(id) {
  const a = agentOf(id);
  return h('span.agent-chip', { style: { '--agent-color': a.color } }, h('span.dot'), a.label);
}
export function confBar(c) {
  return h('span.row', { style: { gap: '6px' } }, h('span.conf', h('i', { style: { width: pct(c) } })), h('span.small.muted', pct(c)));
}

export function download(url) {
  const a = h('a', { href: url, download: '' });
  document.body.append(a); a.click(); a.remove();
}

/** Opens an EventSource and returns a closer. Reconnects automatically (browser default). */
export function stream(path, handlers) {
  const es = new EventSource('/api' + path);
  for (const [name, fn] of Object.entries(handlers)) es.addEventListener(name, (e) => { try { fn(JSON.parse(e.data)); } catch { /* ignore */ } });
  return () => es.close();
}

// ---- speech helpers (browser APIs; all reasoning stays in the Java backend)
export const speech = {
  recognitionSupported: () => !!(window.SpeechRecognition || window.webkitSpeechRecognition),
  synthesisSupported: () => 'speechSynthesis' in window,
  speak(text, onEnd) {
    if (!this.synthesisSupported()) { toast('Read-aloud is not supported in this browser', 'warn'); return; }
    speechSynthesis.cancel();
    const chunks = text.match(/[^.!?\n]+[.!?]?/g) || [text];
    chunks.forEach((c, i) => {
      const u = new SpeechSynthesisUtterance(c.trim());
      u.rate = 1.02;
      if (i === chunks.length - 1 && onEnd) u.onend = onEnd;
      speechSynthesis.speak(u);
    });
  },
  stop() { if (this.synthesisSupported()) speechSynthesis.cancel(); },
  speaking() { return this.synthesisSupported() && speechSynthesis.speaking; },
};

export function readAloudButton(getText) {
  const btn = h('button.btn.small', { title: 'Read aloud' }, '🔊 Read aloud');
  btn.onclick = () => {
    if (speech.speaking()) { speech.stop(); btn.textContent = '🔊 Read aloud'; return; }
    btn.textContent = '■ Stop reading';
    speech.speak(getText(), () => { btn.textContent = '🔊 Read aloud'; });
  };
  return btn;
}
