// agent UI — vanilla ES module, talks to the Netty HTTP API.

const $ = (sel) => document.querySelector(sel);

const state = {
    token: localStorage.getItem('agent_token') || '',
    app: null,
    config: null,
    sessions: [],
    currentId: null,
    current: null,
    messages: [],
    streaming: null,       // { text, tools: [{id,name,args,result,status}] }
    running: false,
    eventSource: null,
    permQueue: [],
    currentPermission: null,
};

/* ------------------------------- utils ------------------------------- */

function escapeHtml(value) {
    return String(value == null ? '' : value)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

function mdToHtml(text) {
    if (!text) return '';
    let out = escapeHtml(text);
    out = out.replace(/```[\w+-]*\n?([\s\S]*?)```/g,
        (m, code) => '<pre class="code"><code>' + code.replace(/\n$/, '') + '</code></pre>');
    out = out.replace(/`([^`]+)`/g, '<code>$1</code>');
    out = out.replace(/\*\*([^*\n]+)\*\*/g, '<strong>$1</strong>');
    return out;
}

function prettyJson(value) {
    if (value == null || value === '') return '';
    if (typeof value !== 'string') return JSON.stringify(value, null, 2);
    try {
        return JSON.stringify(JSON.parse(value), null, 2);
    } catch (e) {
        return value;
    }
}

function notice(kind, text) {
    const box = $('#notices');
    const div = document.createElement('div');
    div.className = 'notice ' + kind;
    div.textContent = text;
    box.appendChild(div);
    setTimeout(() => div.remove(), 8000);
}

/* -------------------------------- api -------------------------------- */

async function api(path, options) {
    const opts = options || {};
    const headers = Object.assign({ 'content-type': 'application/json' }, opts.headers || {});
    if (state.token) headers['authorization'] = 'Bearer ' + state.token;
    const res = await fetch(path, Object.assign({}, opts, { headers }));
    if (!res.ok) {
        let message = res.status + ' ' + res.statusText;
        try {
            const body = await res.json();
            if (body && body.error) message = body.error;
        } catch (e) { /* not json */ }
        throw new Error(message);
    }
    if (res.status === 204) return null;
    const text = await res.text();
    return text ? JSON.parse(text) : null;
}

/* ------------------------------ health ------------------------------- */

function setHealth(ok, text) {
    const dot = $('#health-dot');
    dot.className = 'dot ' + (ok ? 'dot-on' : 'dot-off');
    $('#health-text').textContent = text || (ok ? 'online' : 'offline');
}

async function checkHealth() {
    try {
        const health = await api('/health');
        setHealth(true, health.status || 'online');
    } catch (e) {
        setHealth(false, 'offline');
    }
}

/* ------------------------------ sessions ----------------------------- */

async function loadSessions() {
    state.sessions = await api('/session');
    renderSessions();
}

function renderSessions() {
    const list = $('#session-list');
    list.innerHTML = '';
    for (const session of state.sessions) {
        const item = document.createElement('div');
        item.className = 'session-item' + (session.id === state.currentId ? ' active' : '');
        item.innerHTML =
            '<span class="title">' + escapeHtml(session.title || 'Untitled') + '</span>' +
            '<button class="session-del" title="Delete">&#10005;</button>';
        item.querySelector('.title').addEventListener('click', () => selectSession(session.id));
        item.querySelector('.session-del').addEventListener('click', (event) => {
            event.stopPropagation();
            deleteSession(session.id);
        });
        list.appendChild(item);
    }
}

async function newSession() {
    try {
        const session = await api('/session', {
            method: 'POST',
            body: JSON.stringify({ title: 'New session' }),
        });
        await loadSessions();
        await selectSession(session.id);
    } catch (e) {
        notice('error', e.message);
    }
}

async function selectSession(id) {
    state.currentId = id;
    state.streaming = null;
    state.running = false;
    state.messages = [];
    renderSessions();
    try {
        state.current = await api('/session/' + id);
        state.messages = await api('/session/' + id + '/message');
    } catch (e) {
        notice('error', e.message);
        return;
    }
    updateTopbar();
    renderAll();
    $('#prompt').focus();
}

async function deleteSession(id) {
    if (!confirm('Delete this session?')) return;
    try {
        await api('/session/' + id, { method: 'DELETE' });
        if (state.currentId === id) {
            state.currentId = null;
            state.current = null;
            state.messages = [];
            state.streaming = null;
            updateTopbar();
            renderAll();
        }
        await loadSessions();
    } catch (e) {
        notice('error', e.message);
    }
}

function updateTopbar() {
    $('#session-title').textContent = state.current ? (state.current.title || 'Untitled') : 'No session';
    const parts = [];
    if (state.app) parts.push(state.app.model || state.app.provider);
    if (state.current && state.current.directory) parts.push(state.current.directory);
    $('#topbar-meta').textContent = parts.join('  ·  ');
    $('#brand-sub').textContent = state.app
        ? 'v' + (state.app.version || '?') + '  ·  ' + (state.app.model || '')
        : 'connecting…';
    updateControls();
}

function updateControls() {
    $('#send-btn').disabled = !state.currentId || state.running;
    $('#abort-btn').classList.toggle('hidden', !state.running);
    $('#compact-btn').disabled = !state.currentId || state.running;
}

/* ------------------------------ messages ----------------------------- */

async function loadMessages() {
    if (!state.currentId) return;
    state.messages = await api('/session/' + state.currentId + '/message');
    renderAll();
}

function renderAll() {
    const box = $('#thread');
    const results = {};
    for (const m of state.messages) {
        if (m.role === 'tool') results[m.toolCallId] = m;
    }
    const parts = [];
    for (const m of state.messages) {
        const html = renderMessage(m, results);
        if (html) parts.push(html);
    }
    if (state.streaming) parts.push(renderStreaming(state.streaming));
    box.innerHTML = parts.length ? parts.join('') : '';
    box.querySelectorAll('.revert-btn').forEach((btn) => {
        btn.addEventListener('click', () => revertMessage(btn.dataset.id));
    });
    const empty = $('#empty-state');
    empty.classList.toggle('hidden', parts.length > 0);
    scrollBottom();
}

function renderMessage(message, results) {
    if (message.role === 'user') {
        const revert = message.id && !String(message.id).startsWith('local-')
            ? '<button class="revert-btn" data-id="' + escapeHtml(message.id) + '">revert to here</button>'
            : '';
        return bubble('user', 'You', mdToHtml(message.content) + revert);
    }
    if (message.role === 'assistant') {
        let html = '';
        if (message.content) html += mdToHtml(message.content);
        const calls = message.toolCalls || [];
        for (const call of calls) {
            const result = results[call.id];
            html += renderTool(call, result);
        }
        if (!html) return '';
        return bubble('assistant', 'agent', html);
    }
    if (message.role === 'summary') {
        return '<div class="summary">Context compacted — ' + escapeHtml(message.content || '') + '</div>';
    }
    return '';
}

function bubble(role, label, html) {
    const initial = role === 'user' ? 'U' : 'A';
    return '<div class="msg ' + role + '">' +
        '<div class="avatar">' + initial + '</div>' +
        '<div class="msg-body">' +
        '<div class="msg-role">' + escapeHtml(label) + '</div>' +
        '<div class="bubble">' + html + '</div>' +
        '</div></div>';
}

function renderTool(call, result) {
    const text = result ? (result.content || '') : '';
    let status = 'run';
    let statusText = 'running…';
    if (result) {
        if (text.indexOf('ERROR') === 0) {
            status = 'err';
            statusText = 'failed';
        } else {
            status = 'ok';
            statusText = 'done';
        }
    }
    return '<div class="tool">' +
        '<div class="tool-head">' +
        '<span class="tool-name">' + escapeHtml(call.name || 'tool') + '</span>' +
        '<span class="tool-status ' + status + '">' + statusText + '</span>' +
        '</div>' +
        '<pre class="tool-args">' + escapeHtml(prettyJson(call.arguments)) + '</pre>' +
        (result ? '<pre class="tool-result">' + escapeHtml(text) + '</pre>' : '') +
        '</div>';
}

function renderStreaming(streaming) {
    let html = mdToHtml(streaming.text);
    for (const tool of streaming.tools) {
        html += renderTool({ name: tool.name, arguments: tool.args }, tool.result == null
            ? null : { content: tool.result });
    }
    if (!html && !streaming.tools.length) html = '<span class="txt-muted">thinking…</span>';
    return bubble('assistant', 'agent', html);
}

function scrollBottom() {
    const box = $('#messages');
    box.scrollTop = box.scrollHeight;
}

/* ------------------------------- events ------------------------------ */

function connectEvents() {
    if (state.eventSource) state.eventSource.close();
    const query = state.token ? '?access_token=' + encodeURIComponent(state.token) : '';
    const source = new EventSource('/event' + query);
    source.onopen = () => setHealth(true, 'online');
    source.onerror = () => setHealth(false, 'offline');
    source.onmessage = (event) => {
        try {
            handleEvent(JSON.parse(event.data));
        } catch (e) { /* ignore malformed frame */ }
    };
    state.eventSource = source;
}

function handleEvent(event) {
    const type = event.type;

    if (type === 'session.created' || type === 'session.deleted') {
        loadSessions();
        return;
    }
    if (event.sessionId && state.currentId && event.sessionId !== state.currentId) return;

    if (type === 'message.start') {
        state.running = true;
        state.streaming = { text: '', tools: [] };
        updateControls();
        renderAll();
    } else if (type === 'message.delta') {
        if (!state.streaming) state.streaming = { text: '', tools: [] };
        state.streaming.text += event.text || '';
        renderAll();
    } else if (type === 'message.end') {
        state.running = false;
        state.streaming = null;
        updateControls();
        loadMessages();
        loadSessions();
    } else if (type === 'tool.start') {
        if (!state.streaming) state.streaming = { text: '', tools: [] };
        state.streaming.tools.push({
            id: event.toolCallId, name: event.name, args: event.arguments, result: null, status: 'run',
        });
        renderAll();
    } else if (type === 'tool.end') {
        if (state.streaming) {
            const tool = state.streaming.tools.find((t) => t.id === event.toolCallId);
            if (tool) {
                tool.result = event.result;
                tool.status = String(event.result || '').indexOf('ERROR') === 0 ? 'err' : 'ok';
            }
        }
        renderAll();
    } else if (type === 'permission.request') {
        showPermission(event);
    } else if (type === 'error') {
        notice('error', event.message || 'agent error');
    } else if (type === 'verify') {
        if (event.status && event.status !== 'ok') {
            notice('error', 'verification ' + event.status + (event.errors ? ': ' + event.errors.length : ''));
        }
    } else if (type === 'checkpoint.created') {
        notice('ok', 'checkpoint saved');
    } else if (type === 'compact.done') {
        notice('ok', event.status === 'nothing-to-compact' ? 'nothing to compact' : 'context compacted');
        loadMessages();
    } else if (type === 'revert.done') {
        notice('ok', 'reverted ' + (event.removedMessages || 0) + ' messages');
        loadMessages();
        loadSessions();
    }
}

/* ------------------------------ composer ----------------------------- */

async function sendMessage() {
    const input = $('#prompt');
    const text = input.value.trim();
    if (!text || !state.currentId || state.running) return;
    input.value = '';
    autoGrow();
    state.messages.push({ id: 'local-' + Date.now(), role: 'user', content: text });
    renderAll();
    try {
        await api('/session/' + state.currentId + '/message', {
            method: 'POST',
            body: JSON.stringify({ text }),
        });
    } catch (e) {
        notice('error', e.message);
    }
}

function autoGrow() {
    const input = $('#prompt');
    input.style.height = 'auto';
    input.style.height = Math.min(input.scrollHeight, 200) + 'px';
}

/* ------------------------------- actions ----------------------------- */

async function abortRun() {
    if (!state.currentId) return;
    try {
        await api('/session/' + state.currentId + '/abort', { method: 'POST' });
    } catch (e) {
        notice('error', e.message);
    }
}

async function compact() {
    if (!state.currentId) return;
    try {
        const result = await api('/session/' + state.currentId + '/compact', { method: 'POST' });
        notice('ok', 'compaction ' + result.status);
    } catch (e) {
        notice('error', e.message);
    }
}

async function revertMessage(messageId) {
    if (!state.currentId || !messageId) return;
    if (!confirm('Revert to this message? Later messages and workspace changes are undone.')) return;
    try {
        const result = await api('/session/' + state.currentId + '/revert', {
            method: 'POST',
            body: JSON.stringify({ messageId }),
        });
        notice('ok', 'reverted ' + (result.removedMessages || 0) + ' messages');
        await selectSession(state.currentId);
    } catch (e) {
        notice('error', e.message);
    }
}

/* ----------------------------- permissions --------------------------- */

function showPermission(request) {
    if (state.currentPermission) {
        state.permQueue.push(request);
        return;
    }
    state.currentPermission = request.permissionId;
    $('#perm-tool').textContent = request.tool || 'tool';
    $('#perm-args').textContent = prettyJson(request.arguments);
    const dialog = $('#permission-dialog');
    if (typeof dialog.showModal === 'function') dialog.showModal();
    else dialog.setAttribute('open', '');
}

async function resolvePermission(id, allow) {
    try {
        await api('/session/' + state.currentId + '/permission/' + id, {
            method: 'POST',
            body: JSON.stringify({ allow }),
        });
    } catch (e) {
        notice('error', e.message);
    }
    if (state.permQueue.length) {
        showPermission(state.permQueue.shift());
    }
}

/* ------------------------------ settings ----------------------------- */

function openSettings() {
    $('#token-input').value = state.token;
    const dialog = $('#settings-dialog');
    if (typeof dialog.showModal === 'function') dialog.showModal();
    else dialog.setAttribute('open', '');
}

function saveToken() {
    state.token = $('#token-input').value.trim();
    localStorage.setItem('agent_token', state.token);
    connectEvents();
    boot();
}

/* -------------------------------- boot ------------------------------- */

async function boot() {
    try {
        state.app = await api('/app');
    } catch (e) {
        notice('error', 'cannot reach agent API: ' + e.message);
        setHealth(false, 'offline');
        return;
    }
    try {
        state.config = await api('/config');
    } catch (e) { /* config is optional */ }
    updateTopbar();
    await loadSessions();
    if (!state.currentId && state.sessions.length) {
        await selectSession(state.sessions[0].id);
    } else if (state.currentId) {
        await selectSession(state.currentId);
    } else {
        renderAll();
    }
}

function wire() {
    $('#new-session').addEventListener('click', newSession);
    $('#composer').addEventListener('submit', (event) => {
        event.preventDefault();
        sendMessage();
    });
    $('#prompt').addEventListener('input', autoGrow);
    $('#prompt').addEventListener('keydown', (event) => {
        if (event.key === 'Enter' && !event.shiftKey) {
            event.preventDefault();
            sendMessage();
        }
    });
    $('#abort-btn').addEventListener('click', abortRun);
    $('#compact-btn').addEventListener('click', compact);
    $('#settings-btn').addEventListener('click', openSettings);
    $('#token-save').addEventListener('click', saveToken);

    $('#permission-dialog').addEventListener('close', () => {
        const id = state.currentPermission;
        state.currentPermission = null;
        if (!id) return;
        resolvePermission(id, $('#permission-dialog').returnValue === 'allow');
    });
}

wire();
connectEvents();
boot();
checkHealth();
setInterval(checkHealth, 20000);
