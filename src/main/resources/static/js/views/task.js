import {
  api, h, mount, toast, stream, statusBadge, engineBadge, agentChip, confBar, pct, fmtDate, fmtTime, agentOf,
  empty, loading, confirmDialog, readAloudButton, download, speech,
} from '../core.js';
import { onLeave, app } from '../app.js';
import { createRadar } from './radar.js';
import { evidencePanel } from './evidence.js';
import { boardTab } from './board.js';
import { whatIfTab, compareInto } from './whatif.js';
import { askTab } from './ask.js';

const TABS = [['overview', 'Overview'], ['ask', 'Ask the Agents'], ['radar', 'Agent Radar'], ['debate', 'Debate Room'], ['evidence', 'Evidence'],
  ['whatif', 'What-If'], ['board', 'Thinking Board'], ['timeline', 'Decision Trace'], ['map', 'Map']];

export async function taskView(taskId, initialTab) {
  let detail = await api(`/tasks/${taskId}`);
  let events = await api(`/tasks/${taskId}/events`);
  const S = { tab: TABS.some(([t]) => t === initialTab) ? initialTab : (detail.task.status === 'running' ? 'radar' : 'overview'), version: detail.task.current_version || 0, versionData: detail.current };
  let radar = null;

  const head = h('div'), tabsBar = h('div.tabs', { role: 'tablist' }), content = h('div');
  const isLive = () => ['running', 'awaiting_approval'].includes(detail.task.status);

  // ---- live events
  let reloadTimer = null;
  const close = stream(`/tasks/${taskId}/stream`, {
    'agent-event': (ev) => {
      events.push(ev);
      radar?.onLive(ev);
      if (['finding', 'conflict', 'revision', 'recommendation', 'decision', 'plan'].includes(ev.type))
        window.dispatchEvent(new CustomEvent('af:pulse', { detail: { color: ev.type === 'conflict' ? '#fb7185' : agentOf(ev.agent || 'user').hex } }));
      if (S.tab === 'timeline') renderTab();
      if (['status', 'checkpoint', 'recommendation', 'decision', 'run_started', 'error', 'evidence_added', 'evidence_rejected', 'evidence_restored', 'conflict_unresolved', 'user_resolution'].includes(ev.type)) {
        clearTimeout(reloadTimer); reloadTimer = setTimeout(reload, 350);
      }
    },
  });
  const onKey = (e) => {
    const k = e.detail.key;
    if (/^[1-9]$/.test(k)) switchTab(visibleTabs()[+k - 1]?.[0] || S.tab);
    if (k === 'r') {
      const rec = S.versionData?.recommendation;
      if (!rec) return;
      if (speech.speaking()) speech.stop(); else speech.speak(spokenReport());
    }
  };
  window.addEventListener('af:key', onKey);
  onLeave(() => { close(); radar?.destroy(); window.removeEventListener('af:key', onKey); clearTimeout(reloadTimer); });

  async function reload() {
    try {
      const prevVersion = detail.task.current_version;
      detail = await api(`/tasks/${taskId}`);
      if (S.version === prevVersion || !S.version) { S.version = detail.task.current_version; S.versionData = detail.current; }
      else if (S.version === detail.task.current_version) S.versionData = detail.current;
      radar?.setLive(isLive());
      renderHead();
      if (!['radar', 'evidence', 'ask'].includes(S.tab)) renderTab();
    } catch (err) { toast(err.message, 'bad'); }
  }

  async function selectVersion(v) {
    S.version = v;
    S.versionData = v === detail.task.current_version ? detail.current : await api(`/tasks/${taskId}/versions/${v}`);
    renderHead(); renderTab();
  }

  const visibleTabs = () => TABS.filter(([t]) => t !== 'map' || detail.task.location_text || detail.task.lat != null);

  function onCite(kind, id) {
    if (kind === 'evidence') { switchTab('evidence'); return; }
    switchTab('debate');
    setTimeout(() => {
      const el = document.getElementById('f-' + id);
      if (!el) { toast(`Finding #${id} is not in the version shown`, 'warn'); return; }
      el.scrollIntoView({ behavior: 'smooth', block: 'center' });
      el.classList.add('flash'); setTimeout(() => el.classList.remove('flash'), 1600);
    }, 250);
  }

  function switchTab(t) {
    S.tab = t;
    history.replaceState(null, '', `#/task/${taskId}/${t}`);
    renderTabs(); renderTab();
  }

  function spokenReport() {
    const v = S.versionData; const syn = v?.result?.synthesis;
    if (!syn) return 'There is no recommendation yet.';
    return `Recommendation for ${detail.task.title}. ${syn.recommendation} Confidence ${Math.round(syn.confidence * 100)} percent. `
      + (syn.reasoning || []).map((r, i) => `Reason ${i + 1}: ${r.replace(/\[finding \d+\]/g, '')}`).join(' ');
  }

  // ---- header
  function renderHead() {
    const t = detail.task;
    const actions = [];
    if (t.status === 'running') actions.push(h('button.btn', { onclick: act('pause', 'Pause requested — the run will stop at the next checkpoint') }, '⏸ Pause at next checkpoint'));
    if (['running', 'awaiting_approval'].includes(t.status)) actions.push(h('button.btn.danger', { onclick: async () => { if (await confirmDialog('Stop this analysis?', 'The current run ends and is marked stopped. Completed versions are kept.', 'Stop run', true)) act('cancel', 'Run stopped')(); } }, '■ Stop'));
    if (['draft', 'completed', 'failed', 'stopped', 'interrupted'].includes(t.status))
      actions.push(h('button.btn', { class: t.status === 'draft' ? 'primary' : '', onclick: act('run', 'Analysis started', () => switchTab('radar')) }, t.status === 'draft' ? '▶ Start analysis' : '⟳ Re-run analysis'));
    if (S.versionData?.status === 'completed') {
      const menu = h('div.dropdown.hidden', { style: { minWidth: '180px' } },
        ...[['md', 'Markdown (.md)'], ['html', 'Printable HTML'], ['json', 'Raw data (.json)']].map(([f, l]) =>
          h('button.menu-item', { onclick: () => { menu.classList.add('hidden'); download(`/api/tasks/${taskId}/report?format=${f}&version=${S.version}`); } }, l)));
      actions.push(h('div', { style: { position: 'relative' } }, h('button.btn', { onclick: (e) => { e.stopPropagation(); menu.classList.toggle('hidden'); } }, '⇩ Report'), menu));
    }
    if (!['running', 'awaiting_approval'].includes(t.status))
      actions.push(h('button.btn.ghost.danger', { title: 'Delete task', onclick: async () => {
        if (await confirmDialog('Delete this analysis?', 'This permanently deletes the task, its versions, evidence and history. Memories it created stay in your vault. This cannot be undone.', 'Delete permanently', true)) {
          try { await api(`/tasks/${taskId}`, { method: 'DELETE' }); toast('Task deleted', 'ok'); location.hash = '#/dashboard'; } catch (err) { toast(err.message, 'bad'); }
        }
      } }, '🗑'));

    const vSel = detail.versions.length > 1 ? h('select', { style: { width: 'auto' }, 'aria-label': 'Version', onchange: (e) => selectVersion(+e.target.value) },
      detail.versions.map((v) => h('option', { value: v.version_no, selected: v.version_no === S.version }, `v${v.version_no} · ${v.change_note || ''} (${v.status})`))) : null;

    mount(head, h('div.page-head',
      h('div', { style: { minWidth: 0, flex: 1 } },
        h('div.row', h('a.small', { href: '#/dashboard' }, '← Dashboard'), statusBadge(t.status), engineBadge(t.engine), t.debate_intensity ? h('span.badge', { class: t.debate_intensity === 'ruthless' ? 'bad' : t.debate_intensity === 'gentle' ? 'ok' : '' }, '⚔ ' + t.debate_intensity) : null, t.input_mode === 'voice' ? h('span.badge', '🎤 voice') : null),
        h('h1', { style: { marginTop: '8px' } }, t.title),
        h('div.row.small.muted', `Task #${t.id} · created ${fmtDate(t.created_at)}`, vSel)),
      h('div.row', actions)));
    renderTabs();
  }

  function act(path, msg, after) {
    return async () => {
      try { await api(`/tasks/${taskId}/${path}`, { method: 'POST' }); toast(msg, 'ok'); after?.(); await reload(); }
      catch (err) { toast(err.message, 'bad'); }
    };
  }

  function renderTabs() {
    const v = S.versionData;
    const openConflicts = (v?.conflicts || []).filter((c) => c.status === 'unresolved').length;
    mount(tabsBar, visibleTabs().map(([id, label], i) => h('button.tab', { class: id === S.tab ? 'active' : '', role: 'tab', title: `${label} (${i + 1})`, onclick: () => switchTab(id) },
      label, id === 'debate' && openConflicts ? h('span.badge.bad', openConflicts) : null,
      id === 'evidence' && detail.evidence.length ? h('span.badge', detail.evidence.length) : null,
      id === 'overview' && detail.gate ? h('span.badge.warn', '!') : null)));
  }

  async function renderTab() {
    const renderers = { overview, ask: () => askTab(taskId, detail, { onCite }), radar: radarTab, debate, evidence: evidenceTab, whatif: whatif, board, timeline, map: mapTab };
    try {
      const node = await renderers[S.tab]();
      if (node) mount(content, node);
    } catch (err) { mount(content, empty('⚠', 'Could not render this tab', err.message)); }
  }

  // ---- overview
  function overview() {
    const t = detail.task, v = S.versionData, syn = v?.result?.synthesis;
    const parts = [];
    if (detail.gate && S.version === detail.gate.version) parts.push(gatePanel(detail.gate));
    if (t.status === 'draft') parts.push(h('div.card.stack', h('h2', 'Draft'), h('p.muted', 'This analysis has not started yet. Add evidence if you like, then start it.'),
      h('div.row', h('button.btn', { onclick: () => switchTab('evidence') }, '📎 Add evidence'), h('button.btn.primary', { onclick: act('run', 'Analysis started', () => switchTab('radar')) }, '▶ Start analysis'))));
    if (t.status === 'failed') parts.push(h('div.card.state.error', h('h3', 'The analysis failed'), h('p', t.error || 'Unknown error'), h('p.small.muted', 'Re-run it, or check the Services page.')));
    if (t.status === 'interrupted') parts.push(h('div.card.gate', h('b', 'This run was interrupted by a server restart.'), ' Use “Re-run analysis” to start it again.'));
    if (t.status === 'running' && S.version === t.current_version) {
      const last = events.filter((e) => e.version_no === S.version).slice(-1)[0];
      parts.push(h('div.card.row', h('div.spinner'), h('div.grow', h('b', 'Agents are working…'), h('div.small.muted', last ? last.summary : 'Starting')), h('button.btn.small', { onclick: () => switchTab('radar') }, 'Watch on radar →')));
    }
    if (syn) {
      parts.push(h('div.card.rec-card.stack',
        h('div.card-head', h('h2', '✦ Recommendation'), h('div.row', h('span.small', 'Confidence'), confBar(syn.confidence), readAloudButton(spokenReport))),
        h('div.rec-text', syn.recommendation),
        syn.reasoning?.length ? h('div', h('h4', 'Why'), h('ol', syn.reasoning.map((r) => h('li', linkify(r))))) : null,
        syn.openQuestions?.length ? h('div', h('h4', 'Open questions'), h('ul', syn.openQuestions.map((q) => h('li', q)))) : null,
        (v.result.engineNotes || []).map((n) => h('div.engine-note', n)),
        v.result.engine === 'rules' ? h('div.engine-note', 'Produced by the deterministic rule-based Java engine because no AI service is configured. Set ANTHROPIC_API_KEY for model reasoning.') : null));
    } else if (v && v.status !== 'running' && t.status !== 'draft') {
      parts.push(h('div.card', empty('◇', 'No recommendation in this version', `Version status: ${v.status}`)));
    }
    if (v) {
      const findings = (v.findings || []).filter((f) => f.status !== 'superseded');
      const by = (a) => findings.filter((f) => f.agent === a);
      parts.push(h('div.grid.g3',
        ...['analyst', 'inspector', 'risk'].map((a) => h('div.card.tight', h('div.row.between', agentChip(a), h('span.badge', by(a).length)),
          h('ul.small', { style: { paddingLeft: '18px', margin: '8px 0 0' } }, by(a).slice(0, 4).map((f) => h('li', f.title, f.status !== 'active' ? h('span.faint', ` (${f.status})`) : null)))))));
      const assumptions = Array.isArray(v.assumptions) ? v.assumptions : [];
      parts.push(h('div.grid.g2',
        h('div.card', h('div.card-head', h('h3', 'Assumptions'), t.status === 'completed' ? h('button.btn.small', { onclick: () => switchTab('whatif') }, '⚗ What if…') : null),
          assumptions.length ? h('ol.small', assumptions.map((a) => h('li', a))) : h('p.muted', 'Not available yet.')),
        h('div.card', h('h3', '◈ Memory reused'), v.memoryUses?.length ? h('ul.small', v.memoryUses.map((m) => h('li',
          h('b', `#${m.memory_id} `), m.snapshot.length > 160 ? m.snapshot.slice(0, 160) + '…' : m.snapshot,
          h('span.faint', ` — from ${m.source_title ? '“' + m.source_title + '”' : 'a note'}, match ${pct(m.score)}${m.deleted ? ', since deleted' : ''}`))))
          : h('p.muted.small', 'No previous findings were relevant to this task.'))));
    }
    parts.push(h('div.card', h('h3', 'Task description'), h('p', { style: { whiteSpace: 'pre-wrap' } }, t.description),
      t.location_text ? h('p.small.muted', '📍 ', t.location_text, t.location_relevant ? ' (used: task is location-dependent)' : ' (not used: task judged not location-dependent)') : null));
    return h('div.stack', parts);
  }

  function linkify(text) {
    return String(text).split(/(\[finding \d+\])/g).map((p) => /^\[finding \d+\]$/.test(p)
      ? h('a', { href: 'javascript:void 0', onclick: () => { switchTab('debate'); setTimeout(() => document.getElementById('f-' + p.match(/\d+/)[0])?.scrollIntoView({ behavior: 'smooth', block: 'center' }), 200); } }, p) : p);
  }

  // ---- human-in-the-loop gate
  function gatePanel(g) {
    const choices = {};
    const note = h('textarea', { placeholder: 'Optional guidance for the agents (e.g. "The budget figure in the screenshot is the current one")', style: { minHeight: '70px' } });
    const conflicts = g.checkpoint === 'on_conflict' ? g.payload.conflicts : [];
    const conflictUi = conflicts.map((c) => {
      const name = 'c' + c.id;
      const opt = (val, label, hint) => h('label.check', h('input', { type: 'radio', name, value: val, onchange: () => (choices[c.id] = val) }), h('span', label, h('small', hint)));
      return h('div.card.tight.stack', { style: { gap: '6px' } },
        h('div.row', agentChip('critic'), h('span.muted', 'vs'), agentChip(c.agent), h('span.badge.bad', c.severity)),
        h('div', h('b', 'Finding: '), c.findingTitle), h('div.muted', h('b', 'Objection: '), c.objection),
        h('div.row', opt('uphold', 'Uphold the finding', 'The agent is right; keep it'), opt('reject', 'Accept the objection', 'Reject the finding')));
    });
    const approve = h('button.btn.ok', {}, '✓ Approve & resume');
    approve.onclick = async () => {
      if (conflicts.some((c) => !choices[c.id])) { toast('Decide every conflict first', 'warn'); return; }
      approve.disabled = true;
      try { await api(`/tasks/${taskId}/decision`, { method: 'POST', body: { action: 'approve', note: note.value, conflictChoices: choices } }); toast('Decision recorded — resuming', 'ok'); }
      catch (err) { toast(err.message, 'bad'); approve.disabled = false; }
    };
    const stop = h('button.btn.danger', { onclick: async () => { try { await api(`/tasks/${taskId}/decision`, { method: 'POST', body: { action: 'stop', note: note.value } }); } catch (err) { toast(err.message, 'bad'); } } }, 'Stop run');
    return h('div.card.gate.stack',
      h('div.card-head', h('h2', '⏸ Your approval is needed'), h('span.badge.warn', g.checkpoint.replace(/_/g, ' '))),
      h('p', g.message),
      g.payload?.plan ? h('div.small', h('b', 'Plan: '), g.payload.plan.summary, h('ol', (g.payload.plan.assumptions || []).map((a) => h('li', a)))) : null,
      ...conflictUi,
      h('label', 'Guidance for the agents', note),
      h('div.row.between', h('button.btn.small', { onclick: () => switchTab('evidence') }, '📎 Add or reject evidence first'), h('div.row', stop, approve)));
  }

  // ---- radar
  function radarTab() {
    radar?.destroy();
    radar = createRadar(events, { isLive });
    return radar.el;
  }

  // ---- debate room
  function debate() {
    const v = S.versionData;
    if (!v) return empty('⚔', 'No debate yet', 'Start the analysis to see agents argue.');
    const conflictsBy = {};
    (v.conflicts || []).forEach((c) => (conflictsBy[c.finding_id] ||= []).push(c));
    const evs = events.filter((e) => e.version_no === S.version);
    const findings = v.findings || [];
    const columns = ['analyst', 'inspector', 'risk'].map((a) => {
      const list = findings.filter((f) => f.agent === a);
      return h('div.stack', h('div.row.between', agentChip(a), h('span.small.muted', `${list.length} findings`)),
        list.length ? list.map((f) => findingCard(f, conflictsBy[f.id] || [], evs)) : h('p.muted.small', 'No findings.'));
    });
    const unresolved = (v.conflicts || []).filter((c) => c.status === 'unresolved');
    return h('div.stack',
      h('div.card.tight.row.between', h('div', h('b', `${(v.conflicts || []).length} objection(s)`), ' raised by the Devil\'s Advocate · ',
        h('span', { style: { color: unresolved.length ? 'var(--bad)' : 'var(--ok)' } }, `${unresolved.length} unresolved`)),
        detail.gate ? h('button.btn.small', { onclick: () => switchTab('overview') }, 'Decide now →') : null),
      h('div.grid.g3', { style: { alignItems: 'start' } }, columns));
  }

  function findingCard(f, conflicts, evs) {
    const save = h('button.btn.ghost.small', { title: 'Save this finding to your memory vault' }, '◈ Remember');
    save.onclick = async () => { try { await api(`/tasks/${taskId}/findings/${f.id}/memory`, { method: 'POST' }); toast('Saved to memory vault', 'ok'); save.disabled = true; } catch (err) { toast(err.message, 'warn'); } };
    const revised = f.original_detail && f.original_detail !== f.detail;
    return h('div.finding', { id: 'f-' + f.id, class: f.status, style: { '--agent-color': agentOf(f.agent).color } },
      h('div.meta', h('span.badge', f.kind), f.status !== 'active' ? h('span.badge', { class: f.status === 'approved' ? 'ok' : f.status === 'rejected' ? 'bad' : 'warn' }, f.status) : null, confBar(f.confidence), h('span.grow'), save),
      h('div.ftitle', `#${f.id} `, f.title),
      h('div.small', { style: { marginTop: '4px' } }, f.detail),
      revised ? h('details.small', h('summary.faint', 'Original version'), h('div.before', f.original_detail)) : null,
      f.evidence_refs ? h('div.small.faint', '📎 evidence ', f.evidence_refs.split(',').map((id) => '#' + id).join(', ')) : null,
      conflicts.length ? h('div.thread', conflicts.map((c) => {
        const userEv = evs.find((e) => e.type === 'user_resolution' && e.payload && JSON.parse(e.payload).conflictId === c.id);
        return [
          h('div.bubble.critic', h('div.row.between', agentChip('critic'), h('span.badge', { class: c.severity === 'high' ? 'bad' : 'warn' }, c.severity)), h('div', c.objection)),
          c.response_action ? h('div.bubble', h('div.row.between', agentChip(f.agent), h('span.badge', c.response_action)), h('div', c.resolution?.split(' | ')[0] || '')) : null,
          userEv ? h('div.bubble.user', h('b', '👤 You: '), userEv.summary) : null,
          h('div.small', h('span.badge', { class: c.status === 'unresolved' ? 'bad' : c.status === 'resolved' ? 'ok' : 'primary' }, c.status.replace('_', ' '))),
        ];
      })) : null);
  }

  // ---- evidence
  function evidenceTab() {
    const panel = evidencePanel(taskId, { canUpload: () => detail.task.status !== 'running', uploadBlockedReason: 'Pause the analysis (or wait for a checkpoint) to add evidence.' });
    return h('div.card.stack',
      h('div.card-head', h('h2', 'Evidence'), h('span.small.muted', detail.task.status === 'awaiting_approval' ? 'Paused — evidence added now will be re-inspected when you resume.' : '')),
      panel.el);
  }

  // ---- what-if
  function whatif() {
    return whatIfTab({ ...detail, current: S.versionData }, { onStarted: () => { S.version = 0; reload().then(() => switchTab('radar')); } });
  }

  // ---- board
  async function board() {
    if (!S.versionData || S.versionData.status !== 'completed') return h('div.card', empty('⬡', 'Board not ready', 'The Thinking Board is generated from a completed version.'));
    mount(content, loading('Drawing the board…'));
    return boardTab(taskId, S.version);
  }

  // ---- decision trace timeline
  let tlFilter = 'all';
  function timeline() {
    const groups = { all: () => true, agents: (e) => ['agent_state', 'plan', 'message'].includes(e.type), findings: (e) => ['finding', 'revision', 'defense', 'recommendation'].includes(e.type),
      conflicts: (e) => e.type.startsWith('conflict') || e.type === 'user_resolution', evidence: (e) => e.type.startsWith('evidence'), human: (e) => e.agent === 'user' || e.type === 'checkpoint',
      memory: (e) => e.type.startsWith('memory') };
    const filterBar = h('div.row', Object.keys(groups).map((g) => h('button.btn.small', { class: g === tlFilter ? 'primary' : '', onclick: () => { tlFilter = g; renderTab(); } }, g)));
    const list = events.filter(groups[tlFilter]);
    const items = []; let lastV;
    for (const e of list) {
      if (e.version_no !== lastV) {
        lastV = e.version_no;
        const v = detail.versions.find((x) => x.version_no === e.version_no);
        items.push(h('div.tl-version', e.version_no ? `Version ${e.version_no}${v ? ' — ' + (v.change_note || '') : ''}` : 'Before analysis'));
      }
      items.push(h('div.tl-item', { style: { '--agent-color': agentOf(e.agent || 'user').color } },
        h('div.when', fmtTime(e.ts), ' · ', e.type.replace(/_/g, ' ')),
        h('div', h('b', agentOf(e.agent || 'user').label), e.target ? ` → ${agentOf(e.target).label}` : '', ': ', e.summary)));
    }
    const v = S.versionData?.result?.synthesis;
    const cmpBox = h('div');
    const done = detail.versions.filter((x) => x.status === 'completed');
    const selA = h('select', done.map((x) => h('option', { value: x.version_no }, `v${x.version_no}`)));
    const selB = h('select', done.map((x) => h('option', { value: x.version_no }, `v${x.version_no}`)));
    if (done.length >= 2) { selA.value = done[done.length - 2].version_no; selB.value = done[done.length - 1].version_no; }
    return h('div.grid', { style: { gridTemplateColumns: 'minmax(0,1fr) minmax(0, 380px)', alignItems: 'start' }, class: 'tl-grid' },
      h('div.card.stack', h('div.card-head', h('h2', 'Decision Trace'), h('span.small.muted', `${list.length} of ${events.length} events`)), filterBar,
        items.length ? h('div.timeline', items) : empty('⏱', 'No events', 'Nothing matches this filter.')),
      h('div.stack',
        v ? h('div.card', h('h3', 'Reasoning behind the recommendation'), h('p.small', v.recommendation), h('ol.small', (v.reasoning || []).map((r) => h('li', linkify(r))))) : null,
        detail.decisions.length ? h('div.card', h('h3', '👤 Your decisions'), h('ul.small', detail.decisions.map((d) => h('li', h('b', d.checkpoint.replace(/_/g, ' ')), ` (v${d.version_no}): ${d.action}`, d.note ? ` — “${d.note}”` : '', h('div.faint', fmtDate(d.created_at)))))) : null,
        h('div.card.stack', h('h3', 'Compare versions'), done.length < 2 ? h('p.muted.small', 'Needs two completed versions (run a What-If scenario).')
          : h('div.row', selA, '→', selB, h('button.btn.small', { onclick: () => compareInto(cmpBox, taskId, +selA.value, +selB.value) }, 'Compare')), cmpBox)));
  }

  // ---- map
  function mapTab() {
    const geo = S.versionData?.result?.geo;
    const t = detail.task;
    if (!t.location_relevant && !geo) return h('div.card', empty('📍', 'Location not used',
      t.status === 'completed' ? 'The Coordinator judged this task not to depend on geography, so the location was ignored.' : 'The map appears once the analysis decides the location matters.'));
    if (typeof window.L === 'undefined') return h('div.card', empty('🗺', 'Map library unavailable', 'Leaflet could not load (offline?). Places are listed below.'), placesList(geo));
    if (!geo?.available) return h('div.card', empty('📍', 'No coordinates', geo?.note || 'The location could not be geocoded.'));
    const mapEl = h('div.map');
    setTimeout(() => {
      const m = L.map(mapEl).setView([geo.lat, geo.lon], 14);
      L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', { maxZoom: 19, attribution: '© OpenStreetMap contributors' }).addTo(m);
      L.circleMarker([geo.lat, geo.lon], { radius: 10, color: '#6366f1', fillOpacity: 0.7 }).addTo(m).bindPopup(`<b>Your location</b><br>${escapeHtml(geo.location)}`);
      const pts = [[geo.lat, geo.lon]];
      (geo.places || []).forEach((p) => { L.marker([p.lat, p.lon]).addTo(m).bindPopup(`<b>${escapeHtml(p.name)}</b><br>${escapeHtml(p.category)} · search: ${escapeHtml(p.query)}`); pts.push([p.lat, p.lon]); });
      if (pts.length > 1) m.fitBounds(pts, { padding: [30, 30] });
      onLeave(() => m.remove());
    }, 0);
    return h('div.stack', h('div.card', h('div.card-head', h('h2', '📍 Context map'), h('span.small.muted', 'Location used once for this task; not tracked')), mapEl), placesList(geo));
  }
  function placesList(geo) {
    const places = geo?.places || [];
    return h('div.card', h('h3', `Nearby results (${places.length})`), places.length ? h('div.list', places.map((p) => h('div.list-item', '📍', h('div.grow', h('div.title', p.name), h('div.small.muted', `${p.category} · “${p.query}”`)))))
      : h('p.muted.small', 'No place searches were needed or none matched near this location.'));
  }

  renderHead();
  renderTab();
  return h('div', head, tabsBar, content);
}

function escapeHtml(s) { return String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c])); }
export { app };
