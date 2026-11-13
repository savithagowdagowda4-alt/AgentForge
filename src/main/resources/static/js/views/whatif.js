import { api, h, mount, toast, pct, agentChip, empty, loading } from '../core.js';

/** What-If Simulator: edit assumptions, re-run as a new version, then compare. */
export function whatIfTab(detail, { onStarted }) {
  const t = detail.task;
  const current = detail.current;
  const assumptions = (current?.assumptions && Array.isArray(current.assumptions) ? current.assumptions : []).slice();
  const completed = t.status === 'completed';

  const list = h('div.stack', { style: { gap: '8px' } });
  const renderList = () => mount(list, assumptions.map((a, i) => h('div.row', { style: { flexWrap: 'nowrap' } },
    h('b.small', { style: { width: '28px' } }, `A${i + 1}`),
    h('input', { value: a, oninput: (e) => (assumptions[i] = e.target.value), 'aria-label': `Assumption ${i + 1}` }),
    h('button.btn.ghost.small', { title: 'Remove', onclick: () => { assumptions.splice(i, 1); renderList(); } }, '✕'))));
  renderList();
  const note = h('input', { placeholder: 'Name this scenario, e.g. "Budget cut by 30%"', maxlength: 200 });
  const runBtn = h('button.btn.primary', { disabled: !completed }, '▶ Run scenario');
  runBtn.onclick = async () => {
    runBtn.disabled = true;
    try {
      const r = await api(`/tasks/${t.id}/whatif`, { method: 'POST', body: { assumptions, note: note.value } });
      toast(`Scenario started as version ${r.version}`, 'ok');
      onStarted(r.version);
    } catch (err) { toast(err.message, 'bad'); runBtn.disabled = false; }
  };

  const versions = detail.versions.filter((v) => v.status === 'completed');
  const cmpBox = h('div');
  const selA = h('select'), selB = h('select');
  versions.forEach((v) => {
    const label = `v${v.version_no} — ${v.change_note || ''}`;
    selA.append(h('option', { value: v.version_no }, label)); selB.append(h('option', { value: v.version_no }, label));
  });
  if (versions.length >= 2) {
    const last = versions[versions.length - 1];
    selB.value = last.version_no;
    selA.value = last.parent_version && versions.some((v) => v.version_no === last.parent_version) ? last.parent_version : versions[versions.length - 2].version_no;
  }
  const cmpBtn = h('button.btn', { onclick: () => compareInto(cmpBox, t.id, +selA.value, +selB.value) }, 'Compare');

  const editor = h('div.card.stack',
    h('div.card-head', h('h2', '⚗ What-If Decision Simulator'), h('span.small.muted', current ? `Starting from v${current.version_no}` : '')),
    completed ? h('p.muted', 'Change, add or remove the assumptions the analysis depends on. The agents re-run the analysis on the new assumptions as a new version, and you can compare it with the original.')
      : h('div.engine-note', 'What-If runs on a completed analysis. Wait for the current run to finish.'),
    list,
    h('div.row', h('button.btn.small', { onclick: () => { assumptions.push(''); renderList(); list.lastChild?.querySelector('input')?.focus(); } }, '＋ Add assumption')),
    h('label', 'Scenario name', note),
    h('div.row', { style: { justifyContent: 'flex-end' } }, runBtn));

  const compare = h('div.card.stack',
    h('div.card-head', h('h2', '⇄ Compare versions')),
    versions.length < 2 ? empty('⇄', 'Need two completed versions', 'Run a What-If scenario to compare it with the original recommendation.')
      : h('div.row', h('label', 'Original', selA), h('label', 'Scenario', selB), h('div', { style: { alignSelf: 'flex-end' } }, cmpBtn)),
    cmpBox);
  if (versions.length >= 2) setTimeout(() => cmpBtn.click(), 0);
  return h('div.stack', editor, compare);
}

export async function compareInto(box, taskId, a, b) {
  if (a === b) { mount(box, h('div.muted', 'Pick two different versions.')); return; }
  mount(box, loading('Comparing…'));
  try {
    const c = await api(`/tasks/${taskId}/compare?a=${a}&b=${b}`);
    const explainBox = h('div');
    const explainBtn = c.aiExplanationAvailable ? h('button.btn.small', {
      onclick: async () => {
        explainBtn.disabled = true; mount(explainBox, loading('Asking Claude to explain the differences…'));
        try {
          const r = await api(`/tasks/${taskId}/compare/explain?a=${a}&b=${b}`, { method: 'POST' });
          mount(explainBox, r.available ? h('div.card.tight', h('h4', 'AI explanation'), h('p', r.impact),
            Array.isArray(r.drivers) && r.drivers.length ? h('ul.small', r.drivers.map((d) => h('li', h('b', d.assumption), ' — ', d.effect))) : null) : h('div.engine-note', r.message));
        } catch (err) { mount(explainBox, h('div.error-text', err.message)); explainBtn.disabled = false; }
      },
    }, '✦ Explain with AI') : h('span.small.faint', 'AI explanation unavailable (no API key).');
    const rec = (side, label) => h('div.card.tight', { class: label === 'Scenario' && c.recommendationChanged ? 'diff-changed' : '' },
      h('div.row.between', h('h4', `${label} · v${side.version}`), h('span.small', 'confidence ', pct(side.confidence))),
      h('div.small.faint', side.note || ''), h('p', { style: { marginTop: '6px' } }, side.recommendation || '—'));
    const fc = c.findingChanges;
    mount(box, h('div.stack',
      h('div.card.tight.rec-card', h('b', c.recommendationChanged ? '⚠ Recommendation changed. ' : '✓ Recommendation held. '), c.impact, h('div.row', { style: { marginTop: '8px' } }, explainBtn)),
      explainBox,
      h('div.grid.g2', rec(c.a, 'Original'), rec(c.b, 'Scenario')),
      h('div', h('h3', 'Assumption changes'), c.assumptionChanges.length ? h('div.stack', { style: { gap: '6px' } }, c.assumptionChanges.map((x) =>
        h('div.diff', { class: `diff-${x.type === 'modified' ? 'changed' : x.type}` }, h('span.badge', x.type), ' ',
          x.before ? h('span.before', x.before) : null, x.before && x.after ? ' → ' : null, x.after || ''))) : h('p.muted', 'No assumption changes.')),
      h('div', h('h3', `Finding changes (${fc.length})`), fc.length ? h('div.stack', { style: { gap: '6px' } }, fc.map((x) =>
        h('div.diff', { class: `diff-${x.type}` },
          h('div.row', h('span.badge', x.type), agentChip(x.agent), x.confidenceDelta ? h('span.small', `confidence ${x.confidenceDelta > 0 ? '+' : ''}${Math.round(x.confidenceDelta * 100)} pts`) : null),
          x.before && x.type !== 'added' ? h('div.small', { class: x.type === 'removed' ? '' : 'before' }, x.before.title) : null,
          x.after ? h('div', h('b', x.after.title)) : null,
          h('div.small.muted', '↳ ', x.why)))) : h('p.muted', 'Findings did not change materially.'))));
  } catch (err) { mount(box, h('div.error-text', err.message)); }
}
