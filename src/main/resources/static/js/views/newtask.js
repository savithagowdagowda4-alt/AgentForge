import { api, h, mount, toast, speech } from '../core.js';
import { onLeave, app } from '../app.js';
import { evidencePanel } from './evidence.js';
import { getTemplates, openDemoLauncher } from './templates.js';

const CHECKPOINTS = [
  ['on_conflict', 'When agents reach an unresolved conflict', 'You decide which side wins before the recommendation is made.', true],
  ['after_plan', 'After the Coordinator\'s plan', 'Review the plan and assumptions before specialists start.', false],
  ['after_debate', 'After the debate round', 'Review findings and objections before continuing.', false],
  ['before_synthesis', 'Before the final recommendation', 'Add evidence or guidance before the Synthesizer runs.', false],
];

export function newTaskView() {
  let mode = 'text';
  let recognition = null;
  let listening = false;
  let coords = null; // { lat, lon } from the device, only after the user asks
  let task = null;
  let intensity = 'balanced';

  // ---- debate intensity
  const INTENSITY = [['gentle', '🕊', 'Gentle', 'Only serious objections'], ['balanced', '⚖', 'Balanced', 'Challenge what deserves it'], ['ruthless', '🔥', 'Ruthless', 'Question everything']];
  const intensityBox = h('div.intensity');
  const renderIntensity = () => mount(intensityBox, INTENSITY.map(([id, icon, name, hint]) =>
    h('button', { type: 'button', class: id === intensity ? 'active ' + id : id, onclick: () => { intensity = id; renderIntensity(); } },
      h('span.i-ico', icon), h('b', name), h('small', hint))));
  renderIntensity();

  // ---- scenario templates (pre-fill the form)
  const tplBox = h('div.tpl-strip', h('span.small.faint', 'Loading scenarios…'));
  getTemplates().then((list) => mount(tplBox, list.map((t) => h('button.tpl-chip', {
    type: 'button', title: t.blurb,
    onclick: () => {
      title.value = t.title; desc.value = t.description; desc.dispatchEvent(new Event('input'));
      intensity = t.intensity; renderIntensity();
      cpInputs.forEach((l) => { const cb = l.querySelector('input'); cb.checked = t.checkpoints.includes(cb.value); });
      locToggle.checked = !!t.location; locBox.classList.toggle('hidden', !t.location); locText.value = t.location || ''; coords = null;
      toast(`Filled from “${t.name}”. Its sample evidence is attached when you use ⚡ Launch instead.`, 'info', 5000);
    },
  }, h('span', t.icon), t.name)), h('button.tpl-chip.launch', { type: 'button', onclick: openDemoLauncher }, '⚡ Launch a live demo'))).catch(() => mount(tplBox));

  const title = h('input', { placeholder: 'Optional — a short title (we\'ll derive one otherwise)', maxlength: 200 });
  const desc = h('textarea', { placeholder: 'Describe the problem or decision. Include goals, constraints, numbers and deadlines — e.g. "We plan to launch our app in 6 weeks with a $40,000 budget. Should we outsource QA?"', 'aria-label': 'Task description' });
  const interim = h('div.interim', { 'aria-live': 'polite' });
  const counter = h('span.small.faint', '0 / 20000');
  desc.addEventListener('input', () => (counter.textContent = `${desc.value.length} / 20000`));

  // ---- voice input
  const micSupported = speech.recognitionSupported();
  const micBtn = h('button.btn.icon.mic', { type: 'button', title: micSupported ? 'Speak your problem' : 'Speech recognition not supported in this browser', disabled: !micSupported }, '🎤');
  const micHint = h('div.small.muted');
  function startListening() {
    const SR = window.SpeechRecognition || window.webkitSpeechRecognition;
    recognition = new SR();
    recognition.continuous = true; recognition.interimResults = true; recognition.lang = navigator.language || 'en-US';
    recognition.onresult = (e) => {
      let live = '';
      for (let i = e.resultIndex; i < e.results.length; i++) {
        const r = e.results[i];
        if (r.isFinal) { desc.value = (desc.value ? desc.value.replace(/\s*$/, ' ') : '') + r[0].transcript.trim(); desc.dispatchEvent(new Event('input')); }
        else live += r[0].transcript;
      }
      interim.textContent = live;
    };
    recognition.onerror = (e) => {
      const msg = e.error === 'not-allowed' ? 'Microphone permission was denied. You can type instead.' : e.error === 'no-speech' ? 'No speech detected.' : `Speech recognition error: ${e.error}`;
      toast(msg, 'warn'); stopListening();
    };
    recognition.onend = () => { if (listening) stopListening(); };
    try { recognition.start(); } catch (err) { toast(err.message, 'bad'); return; }
    listening = true; mode = 'voice'; renderMode();
    micBtn.classList.add('rec'); micBtn.textContent = '■';
    micHint.textContent = 'Listening… speak naturally. The transcript appears in the box and you can edit it before submitting.';
  }
  function stopListening() {
    listening = false; try { recognition?.stop(); } catch { /* ignore */ }
    micBtn.classList.remove('rec'); micBtn.textContent = '🎤'; interim.textContent = '';
    micHint.textContent = desc.value ? 'Transcription stopped. Review and edit the text before continuing.' : '';
  }
  micBtn.onclick = () => (listening ? stopListening() : startListening());
  onLeave(() => { if (listening) stopListening(); });

  const modeBox = h('div.mode-toggle');
  function renderMode() {
    mount(modeBox,
      h('button', { type: 'button', class: mode === 'text' ? 'active' : '', onclick: () => { if (listening) stopListening(); mode = 'text'; renderMode(); desc.focus(); } }, '⌨ Type'),
      h('button', { type: 'button', class: mode === 'voice' ? 'active' : '', disabled: !micSupported, onclick: () => { if (!listening) startListening(); } }, '🎤 Voice'));
  }
  renderMode();

  // ---- checkpoints
  const cpInputs = CHECKPOINTS.map(([id, label, hint, def]) => {
    const cb = h('input', { type: 'checkbox', value: id, checked: def });
    return h('label.check', cb, h('span', label, h('small', hint)));
  });

  // ---- location (optional, never automatic)
  const locToggle = h('input', { type: 'checkbox' });
  const locText = h('input', { placeholder: 'City, address or area — e.g. "Koramangala, Bengaluru"', maxlength: 300 });
  const locStatus = h('div.small.muted');
  const deviceBtn = h('button.btn.small', { type: 'button' }, '📍 Use my device location (asks permission)');
  deviceBtn.onclick = () => {
    if (!('geolocation' in navigator)) { locStatus.textContent = 'This browser cannot share its location. Type it instead.'; return; }
    locStatus.textContent = 'Waiting for your permission…';
    navigator.geolocation.getCurrentPosition(async (pos) => {
      coords = { lat: +pos.coords.latitude.toFixed(5), lon: +pos.coords.longitude.toFixed(5) };
      locStatus.textContent = `Got a one-time position (±${Math.round(pos.coords.accuracy)} m). Looking up the place name…`;
      try {
        const r = await api(`/geo/reverse?lat=${coords.lat}&lon=${coords.lon}`);
        locText.value = r.available ? r.name : `${coords.lat}, ${coords.lon}`;
        locStatus.textContent = 'Location captured once. It is not tracked and is used only if the task depends on it.';
      } catch { locText.value = `${coords.lat}, ${coords.lon}`; locStatus.textContent = 'Coordinates captured; place name lookup unavailable.'; }
    }, (err) => { locStatus.textContent = err.code === 1 ? 'Permission denied — type a location instead, or leave it empty.' : 'Could not get your location: ' + err.message; },
    { enableHighAccuracy: false, timeout: 15000, maximumAge: 600000 });
  };
  locText.oninput = () => { coords = null; };
  const locBox = h('div.stack.hidden', { style: { gap: '8px' } }, locText, h('div.row', deviceBtn), locStatus,
    h('div.small.faint', 'Location is optional. The Coordinator uses it only if the task genuinely depends on geography; otherwise it is ignored and never appears in reports.'));
  locToggle.onchange = () => locBox.classList.toggle('hidden', !locToggle.checked);

  // ---- steps
  const stepper = h('div.steps');
  const body = h('div');
  let step = 1;
  const renderSteps = () => mount(stepper, ['Describe', 'Evidence', 'Run'].map((s, i) =>
    h('div.step', { class: i + 1 === step ? 'on' : i + 1 < step ? 'done' : '' }, h('b', i + 1 < step ? '✓' : i + 1), s)));

  const errorBox = h('div.error-text');
  const continueBtn = h('button.btn.primary', { type: 'submit' }, 'Continue to evidence →');
  const form = h('form.stack', { onsubmit: (e) => { e.preventDefault(); saveDraft(); } },
    h('div.card.tight.stack', { style: { gap: '10px' } }, h('h4', 'Start from a scenario'), tplBox),
    h('div.card.stack',
      h('div.row.between', h('h2', { style: { margin: 0 } }, 'What should the agents analyse?'), modeBox),
      h('label', 'Title', title),
      h('div.composer', h('label', 'Problem description', desc), micBtn),
      interim, h('div.row.between', micHint, counter),
      !micSupported ? h('div.engine-note', 'Voice input needs the Web Speech API (Chrome or Edge). Typed input works everywhere.') : null),
    h('div.card.stack', h('div.row.between', h('h3', { style: { margin: 0 } }, '⚔ Debate intensity'), h('span.small.muted', "How hard the Devil's Advocate pushes back")), intensityBox),
    h('div.grid.g2',
      h('div.card.stack', h('h3', '⏸ Human-in-the-loop checkpoints'), h('p.small.muted', 'Choose where the workflow pauses for you. You can also pause a running analysis at any time.'), ...cpInputs),
      h('div.card.stack', h('label.check', locToggle, h('span', h('b', '📍 This task depends on a location'), h('small', 'e.g. choosing a site, a route, nearby services'))), locBox)),
    errorBox,
    h('div.row', { style: { justifyContent: 'flex-end' } }, continueBtn));
  desc.addEventListener('keydown', (e) => { if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); saveDraft(); } });

  async function saveDraft() {
    errorBox.textContent = '';
    if (listening) stopListening();
    if (desc.value.trim().length < 15) { errorBox.textContent = 'Please describe the problem in at least a sentence (15+ characters).'; desc.focus(); return; }
    const payload = {
      title: title.value, description: desc.value, inputMode: mode, debateIntensity: intensity,
      checkpoints: cpInputs.map((l) => l.querySelector('input')).filter((c) => c.checked).map((c) => c.value),
      locationText: locToggle.checked ? locText.value : null,
      lat: locToggle.checked && coords ? coords.lat : null, lon: locToggle.checked && coords ? coords.lon : null,
    };
    continueBtn.disabled = true;
    try {
      task = task ? await api(`/tasks/${task.id}`, { method: 'PATCH', body: payload }) : await api('/tasks', { method: 'POST', body: payload });
      step = 2; render();
    } catch (err) { errorBox.textContent = err.message; }
    finally { continueBtn.disabled = false; }
  }

  function evidenceStep() {
    const panel = evidencePanel(task.id);
    const startBtn = h('button.btn.primary', {}, '▶ Start analysis');
    startBtn.onclick = async () => {
      startBtn.disabled = true;
      try { await api(`/tasks/${task.id}/run`, { method: 'POST' }); location.hash = `#/task/${task.id}/radar`; }
      catch (err) { toast(err.message, 'bad'); startBtn.disabled = false; }
    };
    const ai = app.status?.ai?.available;
    return h('div.stack',
      h('div.card.stack', h('div.card-head', h('h2', 'Add evidence (optional)'), h('span.small.muted', `Draft #${task.id}: ${task.title}`)),
        h('p.muted', 'Upload images, screenshots, charts, diagrams or documents. Text is extracted with OCR / PDF parsing',
          ai ? ' and Claude vision' : '', '. Review the extracted text and correct anything that is wrong — agents will use your corrections.'),
        panel.el),
      h('div.card.row.between',
        h('div', h('b', 'Ready?'), h('div.small.muted', ai ? 'Six agents will analyse this with Claude.' : 'No AI service is configured, so agents will use the rule-based Java engine (clearly labelled).')),
        h('div.row', h('button.btn', { onclick: () => { step = 1; render(); } }, '← Edit description'), startBtn)));
  }

  function render() {
    renderSteps();
    mount(body, step === 1 ? form : evidenceStep());
  }
  render();

  return h('div',
    h('div.page-head', h('div', h('h1', 'New Analysis'), h('p.muted', 'Speak or type your problem, attach evidence, and choose where you want to stay in control.'))),
    stepper, body);
}
