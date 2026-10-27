// RSFA End Forms Engine demo UI. Vanilla JS, same-origin only, no third-party code.
// The UI only collects values and posts them to the existing /generate API; all mapping and PDF filling
// happens in the engine. Relative URLs keep it working behind `gcloud run services proxy`.
'use strict';

(function () {
  const $ = (id) => document.getElementById(id);
  const state = { templates: [], current: null, pdfUrl: null, busy: false };

  const AUTH_HELP =
    'The engine refused the request (HTTP %STATUS%). The Cloud Run service is private: open this page through ' +
    '<code>gcloud run services proxy rsfa-end-forms-engine --region australia-southeast1 --port 8080</code> ' +
    'and browse to <code>localhost:8080</code>. A local server started with ENGINE_AUTH=API_KEY cannot be ' +
    'used from a browser; start it with ENGINE_AUTH=NONE on 127.0.0.1 for a local demo.';

  const CODE_HELP = {
    VALIDATION_FAILED: 'The engine rejected the data. Nothing was generated. Fix the fields listed below and try again.',
    UNKNOWN_TEMPLATE: 'This template is not loaded in the engine.',
    TEMPLATE_UNAVAILABLE: 'The blank lender PDF for this template is not available on this server.',
    INVALID_REQUEST: 'The request was not valid (check the date).',
    INVALID_JSON: 'The request was not valid JSON.',
    PAYLOAD_TOO_LARGE: 'The request is larger than 1 MiB.',
    INTERNAL_ERROR: 'The engine hit an unexpected error. Nothing was generated.'
  };

  const ISSUE_HELP = {
    TEXT_TOO_LONG: 'is longer than the box on the form allows',
    OVERLAY_TEXT_TOO_LONG: 'is too long to fit in the space on the form',
    UNENCODABLE_TEXT: 'contains characters the form font cannot print (for example emoji)',
    MULTILINE_NOT_ALLOWED: 'must be a single line',
    INVALID_DATA: 'has an invalid value',
    MISSING_REQUIRED_VALUE: 'is required'
  };

  function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  }

  function labelForKey(key) {
    if (!state.current || !key) return key;
    for (const s of state.current.sections) {
      for (const f of s.fields) if (f.key === key) return f.label;
    }
    return key;
  }

  function showMessage(kind, html) {
    const m = $('message');
    m.className = 'message ' + kind;
    m.innerHTML = html;
    m.hidden = false;
  }

  function setBusy(busy) {
    state.busy = busy;
    const b = $('generate');
    b.disabled = busy;
    b.classList.toggle('loading', busy);
    b.querySelector('.btn-text').textContent = busy ? 'Generating…' : 'Generate PDF';
  }

  async function checkHealth() {
    const box = $('engine-status');
    try {
      const r = await fetch('health', { cache: 'no-store' });
      const j = await r.json();
      if (r.ok && j.status === 'ok') {
        box.className = 'engine-status ok';
        $('engine-status-text').textContent = 'Engine online · ' + j.templates + ' template' + (j.templates === 1 ? '' : 's') + ' loaded';
        return;
      }
      throw new Error('status ' + r.status);
    } catch (e) {
      box.className = 'engine-status bad';
      $('engine-status-text').textContent = 'Engine not reachable';
    }
  }

  function renderTemplate(t) {
    state.current = t;
    $('template-desc').textContent = t.description || '';
    $('notice').textContent = t.notice || '';
    $('date-label').textContent = (t.date && t.date.label) || 'Form date';
    $('date-hint').textContent = (t.date && t.date.hint) || '';
    const root = $('sections');
    root.textContent = '';
    t.sections.forEach((s, si) => {
      const fs = document.createElement('fieldset');
      fs.className = 'section';
      const lg = document.createElement('legend');
      lg.textContent = s.title;
      fs.appendChild(lg);
      const grid = document.createElement('div');
      grid.className = 'grid';
      s.fields.forEach((f, fi) => {
        const id = 'f-' + si + '-' + fi;
        const lab = document.createElement('label');
        lab.className = 'field' + (f.wide ? ' wide' : '');
        lab.htmlFor = id;
        const span = document.createElement('span');
        span.className = 'label';
        span.textContent = f.label;
        const input = document.createElement('input');
        input.type = 'text';
        input.id = id;
        input.dataset.key = f.key;
        input.autocomplete = 'off';
        input.spellcheck = false;
        if (f.inputmode) input.inputMode = f.inputmode;
        if (f.maxlength) input.maxLength = f.maxlength;
        input.addEventListener('input', () => {
          const sample = (state.current.sample && state.current.sample.data) || {};
          input.classList.toggle('changed', input.dataset.sampled === '1' && input.value !== (sample[f.key] || ''));
        });
        lab.append(span, input);
        if (f.hint) {
          const h = document.createElement('span');
          h.className = 'hint';
          h.textContent = f.hint;
          lab.appendChild(h);
        }
        grid.appendChild(lab);
      });
      fs.appendChild(grid);
      root.appendChild(fs);
    });
  }

  function inputs() {
    return Array.from(document.querySelectorAll('#sections input[data-key]'));
  }

  function loadSample() {
    const sample = state.current && state.current.sample;
    if (!sample) return;
    inputs().forEach((i) => {
      i.value = sample.data[i.dataset.key] || '';
      i.dataset.sampled = '1';
      i.classList.remove('changed');
    });
    $('today').value = sample.today || '';
    showMessage('info', '<strong>Sample data loaded</strong>Fictional applicants only. Edit any field, then select Generate PDF.');
  }

  function clearForm() {
    inputs().forEach((i) => { i.value = ''; i.dataset.sampled = ''; i.classList.remove('changed'); });
    $('today').value = '';
    $('message').hidden = true;
  }

  function buildRequest() {
    const data = {};
    inputs().forEach((i) => {
      const v = i.value.trim();
      if (v !== '') data[i.dataset.key] = v;
    });
    const stamp = new Date().toISOString().replace(/[-:TZ.]/g, '').slice(0, 14);
    const req = {
      requestId: 'demo-' + stamp + '-' + Math.random().toString(36).slice(2, 8),
      templateId: state.current.templateId,
      templateVersion: state.current.templateVersion,
      data: data
    };
    if ($('today').value) req.today = $('today').value;
    return req;
  }

  async function sha256Hex(buf) {
    if (!(window.crypto && crypto.subtle)) return 'unavailable in this browser context';
    const d = await crypto.subtle.digest('SHA-256', buf);
    return Array.from(new Uint8Array(d)).map((b) => b.toString(16).padStart(2, '0')).join('');
  }

  function resetResult() {
    if (state.pdfUrl) URL.revokeObjectURL(state.pdfUrl);
    state.pdfUrl = null;
    $('download').hidden = true;
    $('provenance').hidden = true;
    $('pdf-frame').hidden = true;
    $('pdf-frame').removeAttribute('src');
    $('empty').hidden = false;
  }

  function describeIssue(i) {
    const what = i.sourceKey ? labelForKey(i.sourceKey) : (i.pdfField || 'Form');
    const help = ISSUE_HELP[i.code];
    const text = help ? escapeHtml(what) + ' ' + help : escapeHtml(what) + ': ' + escapeHtml(i.message || '');
    return '<li>' + text + ' <code>' + escapeHtml(i.code) + '</code></li>';
  }

  async function generate(ev) {
    ev.preventDefault();
    if (state.busy || !state.current) return;
    if (inputs().every((i) => i.value.trim() === '')) {
      showMessage('error', '<strong>No data entered</strong>Select Load Sample Data or fill in at least one field.');
      return;
    }
    resetResult();
    setBusy(true);
    showMessage('info', '<strong>Generating…</strong>The engine is filling the blank lender form from the mapping.');
    const req = buildRequest();
    const t0 = performance.now();
    try {
      const r = await fetch('generate', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(req),
        cache: 'no-store'
      });
      const ms = Math.round(performance.now() - t0);
      const type = r.headers.get('Content-Type') || '';
      if (r.ok && type.indexOf('application/pdf') === 0) {
        const buf = await r.arrayBuffer();
        const blob = new Blob([buf], { type: 'application/pdf' });
        state.pdfUrl = URL.createObjectURL(blob);
        const name = (r.headers.get('Content-Disposition') || '').match(/filename="([^"]+)"/);
        const a = $('download');
        a.href = state.pdfUrl;
        a.download = name ? name[1] : 'end-form.pdf';
        a.hidden = false;
        const engine = r.headers.get('X-Engine') || '';
        const deterministic = engine.indexOf('deterministic-mapping') >= 0 && engine.indexOf('no-ai') >= 0;
        $('p-engine').textContent = deterministic ? 'Deterministic mapping engine — not AI' : 'Unknown engine (' + engine + ')';
        $('p-template').textContent = r.headers.get('X-Template') || req.templateId + '@' + req.templateVersion;
        $('p-filled').textContent = r.headers.get('X-Fields-Filled') || '?';
        const w = parseInt(r.headers.get('X-Fill-Warnings') || '0', 10);
        $('p-warnings').textContent = w === 0 ? 'None' : String(w) + ' (blank fields or notes)';
        $('p-time').textContent = ms + ' ms';
        $('p-sha').textContent = await sha256Hex(buf);
        $('provenance').hidden = false;
        $('pdf-frame').src = state.pdfUrl;
        $('pdf-frame').hidden = false;
        $('empty').hidden = true;
        showMessage('ok', '<strong>PDF generated</strong>Filled by the deterministic mapping engine from version-controlled ' +
          'mappings — no AI involved. Same input, same PDF. Signatures and declarations were left blank.');
        return;
      }
      if (r.status === 401 || r.status === 403) {
        showMessage('error', '<strong>Not authorised</strong>' + AUTH_HELP.replace('%STATUS%', r.status));
        return;
      }
      let body = null;
      try { body = await r.json(); } catch (e) { body = null; }
      const code = body && body.code ? body.code : 'HTTP_' + r.status;
      const issues = body && Array.isArray(body.issues) ? body.issues.filter((i) => i.severity === 'ERROR') : [];
      let html = '<strong>Could not generate the PDF (' + escapeHtml(code) + ')</strong>' +
        escapeHtml(CODE_HELP[code] || 'The engine returned HTTP ' + r.status + '.');
      if (issues.length) html += '<ul>' + issues.map(describeIssue).join('') + '</ul>';
      if (body && body.requestId) html += '<div class="hint">Request id: <code>' + escapeHtml(body.requestId) + '</code></div>';
      showMessage('error', html);
    } catch (e) {
      showMessage('error', '<strong>Engine not reachable</strong>The browser could not reach the engine. Check that the ' +
        'server or the <code>gcloud run services proxy</code> command is still running.');
    } finally {
      setBusy(false);
    }
  }

  async function init() {
    $('load-sample').addEventListener('click', loadSample);
    $('clear').addEventListener('click', clearForm);
    $('form').addEventListener('submit', generate);
    checkHealth();
    try {
      const r = await fetch('ui/forms.json', { cache: 'no-store' });
      if (!r.ok) throw new Error('HTTP ' + r.status);
      state.templates = (await r.json()).templates;
      const sel = $('template');
      sel.textContent = '';
      state.templates.forEach((t, idx) => {
        const o = document.createElement('option');
        o.value = String(idx);
        o.textContent = t.label;
        sel.appendChild(o);
      });
      sel.disabled = false;
      sel.addEventListener('change', () => { renderTemplate(state.templates[Number(sel.value)]); resetResult(); });
      renderTemplate(state.templates[0]);
    } catch (e) {
      showMessage('error', '<strong>Could not load the form list</strong>' + escapeHtml(e.message));
    }
  }

  document.addEventListener('DOMContentLoaded', init);
})();
