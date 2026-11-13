import { api, h, mount, toast, empty, fmtTime, speech, readAloudButton, confirmDialog } from '../core.js';
import { onLeave } from '../app.js';

const SUGGESTIONS = ['Why did you recommend this?', 'What are the biggest risks?', 'Which findings were challenged, and how did that end?',
  'What does the evidence say about the numbers in my description?', 'What would change your recommendation?'];

/** "Ask the Agents": grounded follow-up Q&A on a completed analysis, with voice input and read-aloud. */
export function askTab(taskId, detail, { onCite }) {
  const completed = detail.current?.status === 'completed';
  const log = h('div.chat-log');
  const input = h('textarea.chat-input', { placeholder: completed ? 'Ask the team anything about this analysis…  (Enter to send, Shift+Enter for a new line)' : 'Available once the analysis has completed', disabled: !completed, rows: 2 });
  const send = h('button.btn.primary', { disabled: !completed }, 'Send ➤');
  const micOk = speech.recognitionSupported();
  const mic = h('button.btn.icon', { title: micOk ? 'Ask by voice' : 'Voice input not supported in this browser', disabled: !completed || !micOk }, '🎤');
  let rec = null;

  function bubble(m) {
    const isUser = m.role === 'user';
    const parts = String(m.content).split(/(\[(?:finding|evidence) \d+\])/gi).map((p) => {
      const c = p.match(/^\[(finding|evidence) (\d+)\]$/i);
      return c ? h('button.cite', { onclick: () => onCite(c[1].toLowerCase(), +c[2]), title: `Open ${c[1]} #${c[2]}` }, `${c[1] === 'evidence' ? '📎' : '◎'} ${c[2]}`) : p;
    });
    return h('div.chat-msg', { class: isUser ? 'me' : 'them' },
      h('div.chat-meta', isUser ? '👤 You' : '✦ Agent team', h('span.faint', ' · ', fmtTime(m.created_at)),
        !isUser && m.engine === 'rules' ? h('span.badge.warn', { style: { marginLeft: '6px' } }, 'rule-based') : null,
        !isUser && m.engine === 'claude' ? h('span.badge.primary', { style: { marginLeft: '6px' } }, 'Claude') : null),
      h('div.chat-body', parts),
      !isUser ? h('div.row', { style: { marginTop: '6px' } }, readAloudButton(() => String(m.content).replace(/\[(finding|evidence) \d+\]/gi, ''))) : null);
  }

  function render(list) {
    if (!completed) { mount(log, empty('💬', 'Not ready yet', 'You can question the agents once the analysis has completed.')); return; }
    mount(log, list.length ? list.map(bubble) : h('div.state', h('div.big-ico', '💬'), h('h3', 'Ask the agents'),
      h('p', 'Answers come only from this analysis — its findings, evidence and debate — with citations you can click.'),
      h('div.row', { style: { justifyContent: 'center' } }, SUGGESTIONS.map((s) => h('button.btn.small', { onclick: () => ask(s) }, s)))));
    log.scrollTop = log.scrollHeight;
  }

  async function load() {
    try { render(await api(`/tasks/${taskId}/chat`)); } catch (err) { mount(log, h('div.error-text', err.message)); }
  }

  async function ask(q) {
    const question = (q ?? input.value).trim();
    if (!question) return;
    input.value = ''; send.disabled = true;
    log.querySelector('.state')?.remove();
    log.append(bubble({ role: 'user', content: question, created_at: new Date().toISOString() }));
    const thinking = h('div.chat-msg.them', h('div.chat-meta', '✦ Agent team'), h('div.chat-body.typing', h('i'), h('i'), h('i')));
    log.append(thinking); log.scrollTop = log.scrollHeight;
    window.dispatchEvent(new CustomEvent('af:pulse', { detail: { color: '#c084fc' } }));
    try { await api(`/tasks/${taskId}/chat`, { method: 'POST', body: { question } }); await load(); }
    catch (err) { thinking.remove(); toast(err.message, 'bad'); }
    finally { send.disabled = false; input.focus(); }
  }

  send.onclick = () => ask();
  input.onkeydown = (e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); ask(); } };
  mic.onclick = () => {
    if (rec) { rec.stop(); return; }
    const SR = window.SpeechRecognition || window.webkitSpeechRecognition;
    rec = new SR(); rec.lang = navigator.language || 'en-US'; rec.interimResults = true;
    rec.onresult = (e) => { input.value = [...e.results].map((r) => r[0].transcript).join(''); };
    rec.onend = () => { mic.classList.remove('rec'); rec = null; if (input.value.trim()) ask(); };
    rec.onerror = (e) => toast(e.error === 'not-allowed' ? 'Microphone permission denied' : 'Voice error: ' + e.error, 'warn');
    mic.classList.add('rec'); rec.start();
  };
  onLeave(() => { try { rec?.stop(); } catch { /* ignore */ } });

  const clear = h('button.btn.ghost.small', {
    onclick: async () => {
      if (!(await confirmDialog('Clear this conversation?', 'The questions and answers for this task are deleted. The analysis itself is not affected.', 'Clear', true))) return;
      await api(`/tasks/${taskId}/chat`, { method: 'DELETE' }); load();
    },
  }, 'Clear');

  load();
  return h('div.card.stack.chat',
    h('div.card-head', h('h2', '💬 Ask the Agents'), h('div.row', h('span.small.muted', 'Grounded in this analysis · cites findings & evidence'), clear)),
    log,
    h('div.chat-compose', mic, input, send));
}
