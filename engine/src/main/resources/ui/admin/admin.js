'use strict';
// RSFA End Forms — local administration area (D-038). Plain JS, no dependencies, no inline handlers (strict CSP).
// All text from files or the server is inserted with textContent, never as HTML.

const API = '/admin/api';
const view = document.getElementById('view');
const S = { schema: null, schemaById: new Map() };

// ---------------------------------------------------------------------------------------------- helpers
function h(tag, attrs, ...children) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v === null || v === undefined || v === false) continue;
    if (k === 'class') el.className = v;
    else if (k === 'text') el.textContent = v;
    else if (k.startsWith('on')) el.addEventListener(k.slice(2), v);
    else if (k === 'dataset') Object.assign(el.dataset, v);
    else el.setAttribute(k, v === true ? '' : v);
  }
  for (const c of children.flat()) if (c !== null && c !== undefined && c !== false) el.append(c instanceof Node ? c : String(c));
  return el;
}
async function api(path, opts = {}) {
  const res = await fetch(API + path, { headers: opts.body && !(opts.body instanceof Blob) ? { 'Content-Type': 'application/json' } : {}, ...opts });
  let body = null;
  try { body = await res.json(); } catch (e) { body = null; }
  return { status: res.status, ok: res.ok, body };
}
function toast(msg, bad) {
  const t = document.getElementById('toast');
  t.textContent = msg; t.className = 'toast' + (bad ? ' bad' : ''); t.hidden = false;
  clearTimeout(toast.t); toast.t = setTimeout(() => { t.hidden = true; }, 4200);
}
const STATUS_TEXT = { deployed: 'Deployed', approved: 'Approved', 'ready-for-review': 'Ready for review', blocked: 'Blocked',
  imported: 'Imported (unreviewed)', draft: 'Draft', uploaded: 'Uploaded' };
const chip = (s) => h('span', { class: 'chip ' + s, text: STATUS_TEXT[s] || s, dataset: { status: s } });
function coverage(mapped, total) {
  const pct = total ? Math.round(100 * mapped / total) : 0;
  const bar = h('span'); bar.style.width = pct + '%';
  return h('div', { class: 'coverage', title: mapped + ' of ' + (total || '?') + ' PDF fields mapped' },
    h('div', { class: 'bar' }, bar), h('span', { class: 'muted', text: total ? `${mapped}/${total}` : `${mapped}/?` }));
}
async function loadSchema() {
  if (S.schema) return S.schema;
  const r = await api('/schema');
  S.schema = r.body.columns || [];
  S.schemaSource = r.body.source;
  S.schema.forEach(c => S.schemaById.set(c.id, c));
  return S.schema;
}
function setNav(name) {
  document.querySelectorAll('[data-nav]').forEach(a => a.classList.toggle('active', a.dataset.nav === name));
}
function modal(title, bodyEls, okText, onOk) {
  const dlg = document.getElementById('modal');
  const form = document.getElementById('modal-form');
  form.replaceChildren(h('h2', { text: title }), ...bodyEls,
    h('div', { class: 'row', style: null }, h('span', { class: 'spacer' }),
      h('button', { class: 'btn', value: 'cancel', text: 'Cancel' }),
      h('button', { class: 'btn primary', value: 'ok', text: okText, dataset: { testid: 'modal-ok' } })));
  form.onsubmit = async (e) => {
    if (e.submitter && e.submitter.value === 'ok') { e.preventDefault(); if (await onOk()) dlg.close(); }
  };
  dlg.showModal();
}

// ---------------------------------------------------------------------------------------------- router
async function route() {
  const hash = location.hash || '#/library';
  const [, page, id] = hash.split('/');
  try {
    if (page === 'upload') return await uploadView();
    if (page === 'draft' && id) return await draftView(id);
    return await libraryView();
  } catch (e) {
    view.replaceChildren(h('div', { class: 'panel banner bad', text: 'Could not load this page: ' + e.message }));
  }
}
window.addEventListener('hashchange', route);
window.addEventListener('DOMContentLoaded', route);

// ---------------------------------------------------------------------------------------------- library
async function libraryView() {
  setNav('library');
  const r = await api('/library');
  const items = r.body.items || [];
  let filter = 'all';
  const tbody = h('tbody');
  const filters = ['all', 'deployed', 'approved', 'ready-for-review', 'draft', 'blocked', 'imported', 'uploaded'];
  const bar = h('div', { class: 'filters' }, filters.map(f => h('button', {
    class: 'btn small' + (f === 'all' ? ' active' : ''), text: f === 'all' ? 'All' : STATUS_TEXT[f], dataset: { filter: f },
    onclick: (e) => { filter = f; bar.querySelectorAll('button').forEach(b => b.classList.toggle('active', b === e.target)); render(); },
  })));
  function render() {
    tbody.replaceChildren(...items.filter(i => filter === 'all' || i.status === filter).map(i => h('tr', { dataset: { kind: i.kind, template: i.templateId || i.name } },
      h('td', {}, h('b', { text: i.bank || '—' })),
      h('td', {}, h('div', { text: i.name || i.templateId }), h('div', { class: 'muted mono', text: i.templateId ? `${i.templateId}@${i.templateVersion}` : (i.templateVersion || '') })),
      h('td', {}, chip(i.status), h('div', { class: 'muted', text: i.statusDetail })),
      h('td', {}, coverage(i.mappedFields || 0, i.templateFields || 0)),
      h('td', { class: 'muted', text: i.kind === 'manifest' ? 'repository manifest' : i.kind === 'draft' ? `draft v${i.version}` : 'uploaded template' }),
      h('td', {}, action(i)))));
    if (!tbody.children.length) tbody.append(h('tr', {}, h('td', { colspan: 6, class: 'empty', text: 'No templates with this status.' })));
  }
  function action(i) {
    if (i.kind === 'draft') return h('a', { class: 'btn small primary', href: '#/draft/' + i.draftId, text: 'Open', dataset: { testid: 'open-draft' } });
    if (i.kind === 'template') return h('button', { class: 'btn small', text: 'Create draft', onclick: () => newDraftModal(i.templateSha256, i) });
    if (i.templateAvailable) return h('button', { class: 'btn small', text: 'Copy to draft', title: 'Creates an editable local draft from this manifest; the manifest itself is not changed',
      onclick: async () => {
        const res = await api('/drafts/from-manifest', { method: 'POST', body: JSON.stringify({ templateId: i.templateId, templateVersion: i.templateVersion }) });
        if (res.ok) location.hash = '#/draft/' + res.body.draftId; else toast(res.body.message || 'Could not copy', true);
      } });
    return h('span', { class: 'muted', text: 'template not available locally' });
  }
  render();
  view.replaceChildren(
    h('div', { class: 'row' }, h('div', {}, h('h1', { text: 'Template library' }),
      h('p', { class: 'lead', text: 'Bank forms, their mapping coverage and review status. "Deployed" and "Approved" come only from recorded RSFA approvals; local drafts are never published from here.' })),
      h('span', { class: 'spacer' }), h('a', { class: 'btn primary', href: '#/upload', text: 'Upload bank PDF' })),
    h('div', { class: 'panel' }, bar, h('table', { dataset: { testid: 'library' } },
      h('thead', {}, h('tr', {}, ['Bank', 'Template', 'Status', 'Coverage', 'Source', ''].map(t => h('th', { text: t })))), tbody)),
    h('p', { class: 'muted', text: `Monday schema: ${r.body.schemaSource}` }));
}

function newDraftModal(sha, meta) {
  const bank = (meta.bank || 'BANK').toUpperCase().replace(/[^A-Z0-9]+/g, '_');
  const name = (meta.name || meta.templateName || 'FORM').toUpperCase().replace(/[^A-Z0-9]+/g, '_').replace(/^_|_$/g, '');
  const idIn = h('input', { value: (bank + '_' + name).slice(0, 60), dataset: { testid: 'draft-template-id' } });
  const verIn = h('input', { value: meta.templateVersion || '1', dataset: { testid: 'draft-template-version' } });
  modal('Create mapping draft', [
    h('p', { class: 'muted', text: 'The draft is stored locally with versions and an audit trail. It is not approved or deployed.' }),
    h('div', { class: 'form-grid' }, h('label', { class: 'field' }, 'Template ID', idIn), h('label', { class: 'field' }, 'Template version', verIn)),
  ], 'Create draft', async () => {
    const res = await api('/drafts', { method: 'POST', body: JSON.stringify({ templateSha256: sha, templateId: idIn.value.trim(), templateVersion: verIn.value.trim() }) });
    if (!res.ok) { toast(res.body.message || 'Could not create the draft', true); return false; }
    location.hash = '#/draft/' + res.body.draftId; return true;
  });
}

// ---------------------------------------------------------------------------------------------- upload
async function uploadView() {
  setNav('upload');
  let file = null;
  const out = h('div', { class: 'grid' });
  const fileIn = h('input', { type: 'file', accept: 'application/pdf,.pdf', hidden: true, dataset: { testid: 'file' } });
  const zone = h('div', { class: 'dropzone', tabindex: 0, dataset: { testid: 'dropzone' } },
    h('b', { text: 'Choose a blank bank PDF' }), h('div', { class: 'muted', text: 'or drop it here · max 15 MB · fillable (AcroForm) forms only' }));
  const chosen = h('div', { class: 'muted' });
  const bank = h('select', { dataset: { testid: 'bank' } }, ['', 'ANZ', 'ASB', 'BNZ', 'GHL', 'KiwiBank', 'WBC', 'Other'].map(b => h('option', { value: b, text: b || 'Select…' })));
  const name = h('input', { placeholder: 'e.g. Boarder Income Declaration', dataset: { testid: 'template-name' } });
  const version = h('input', { placeholder: 'e.g. 02-24', dataset: { testid: 'template-version' } });
  const go = h('button', { class: 'btn primary', text: 'Validate and upload', disabled: true, dataset: { testid: 'upload' } });
  const pick = (f) => { file = f; chosen.textContent = f ? `${f.name} · ${(f.size / 1024).toFixed(0)} KB` : ''; check(); };
  const check = () => { go.disabled = !(file && bank.value && name.value.trim()); };
  zone.addEventListener('click', () => fileIn.click());
  zone.addEventListener('dragover', (e) => { e.preventDefault(); zone.classList.add('over'); });
  zone.addEventListener('dragleave', () => zone.classList.remove('over'));
  zone.addEventListener('drop', (e) => { e.preventDefault(); zone.classList.remove('over'); pick(e.dataTransfer.files[0]); });
  fileIn.addEventListener('change', () => pick(fileIn.files[0]));
  bank.addEventListener('change', check); name.addEventListener('input', check);
  go.addEventListener('click', async () => {
    go.disabled = true; go.textContent = 'Checking…';
    const q = new URLSearchParams({ fileName: file.name, bank: bank.value, templateName: name.value.trim(), templateVersion: version.value.trim() });
    const res = await api('/templates?' + q, { method: 'POST', body: file });
    go.textContent = 'Validate and upload'; check();
    out.replaceChildren(report(res.body.analysis, res.body.stored, res.body.meta));
  });
  view.replaceChildren(h('h1', { text: 'Upload a bank PDF template' }),
    h('p', { class: 'lead', text: 'Only the bank\'s blank form. Files with customer values or signatures are refused. Templates are stored on this computer only.' }),
    h('div', { class: 'panel grid' }, zone, fileIn, chosen,
      h('div', { class: 'form-grid' }, h('label', { class: 'field' }, 'Bank', bank), h('label', { class: 'field' }, 'Template name', name),
        h('label', { class: 'field' }, 'Bank version / date (optional)', version)),
      h('div', { class: 'row' }, go)), out);
}

function report(a, stored, meta) {
  const kinds = {};
  (a.fields || []).forEach(f => { kinds[f.kind] = (kinds[f.kind] || 0) + 1; });
  const panel = h('div', { class: 'panel grid', dataset: { testid: 'report', accepted: String(a.accepted) } },
    h('div', { class: 'banner ' + (a.accepted ? 'ok' : 'bad'), text: a.accepted
      ? 'Accepted: a blank fillable form. Stored locally for mapping.' : 'Refused: this file cannot be used as a template (see below). Nothing was stored.' }),
    h('div', { class: 'stats' }, [['Pages', a.pages], ['Fields', (a.fields || []).length], ...Object.entries(kinds)
      .map(([k, n]) => [k, n]), ['Encrypted', a.encrypted ? 'owner' : 'no'], ['Text chars', a.textChars]].map(([k, v]) => h('div', { class: 'stat' }, h('b', { text: v }), h('span', { class: 'muted', text: k })))),
    a.findings.length ? h('ul', { class: 'findings' }, a.findings.map(f => h('li', { class: f.severity, dataset: { code: f.code } },
      h('b', { text: f.code }), ' ', f.field ? h('span', { class: 'mono', text: f.field + ': ' }) : '', f.message))) : h('div', { class: 'muted', text: 'No findings.' }));
  if (stored) panel.append(h('div', { class: 'row' }, h('span', { class: 'mono muted', text: 'SHA-256 ' + a.sha256.slice(0, 16) + '…' }), h('span', { class: 'spacer' }),
    h('button', { class: 'btn primary', text: 'Create mapping draft', dataset: { testid: 'create-draft' }, onclick: () => newDraftModal(a.sha256, meta) })));
  return panel;
}

// ---------------------------------------------------------------------------------------------- draft workspace
async function draftView(id) {
  setNav('');
  await loadSchema();
  const d = (await api('/drafts/' + id)).body;
  if (!d || !d.state) { view.replaceChildren(h('div', { class: 'panel banner bad', text: 'Draft not found.' })); return; }
  const tpl = (await api('/templates/' + d.state.templateSha256)).body;
  const W = { id, d, tpl, fields: tpl.analysis.fields, mapping: JSON.parse(JSON.stringify(d.mapping)), dirty: false,
    selected: null, page: 0, zoom: 1.0, tab: 'fields', suggestions: null, test: null, synthetic: null };
  W.byName = new Map(W.fields.map(f => [f.name, f]));
  renderDraft(W);
}

function rulesFor(W, name) { return W.mapping.fields.filter(f => f.pdfField === name); }
function markDirty(W) { W.dirty = true; renderDraft(W, true); }

function renderDraft(W, keepScroll) {
  const st = W.d.state;
  const scroll = keepScroll ? document.querySelector('.pane-scroll')?.scrollTop : 0;
  const header = h('div', { class: 'row' },
    h('div', {}, h('h1', {}, (W.d.meta.templateName || st.templateId) + ' ', chip(st.status)),
      h('div', { class: 'muted mono', text: `${st.templateId}@${st.templateVersion} · version ${st.version}${W.dirty ? ' · unsaved changes' : ''} · ${W.d.meta.bank || ''}` })),
    h('span', { class: 'spacer' }),
    h('a', { class: 'btn', href: '#/library', text: '← Library' }),
    h('button', { class: 'btn primary', text: W.dirty ? 'Save new version' : 'Saved', disabled: !W.dirty, dataset: { testid: 'save' }, onclick: () => save(W) }),
    statusActions(W));
  const errors = W.d.problems.filter(p => p.severity === 'error').length;
  const summary = h('div', { class: 'banner ' + (W.dirty ? 'warn' : errors ? 'bad' : 'info'), dataset: { testid: 'summary' }, text: W.dirty
    ? 'Unsaved changes: save to create a new version (any review status returns to draft).'
    : errors ? `${errors} problem(s) block test generation; see the Problems tab.` : 'Mapping compiles. Test it with synthetic data before review.' });
  view.replaceChildren(header, summary, h('div', { class: 'workspace' }, viewer(W), h('div', { class: 'side' }, inspector(W), tabs(W))));
  if (keepScroll) { const p = document.querySelector('.pane-scroll'); if (p) p.scrollTop = scroll; }
}

function statusActions(W) {
  const st = W.d.state;
  const wrap = h('span', { class: 'row' });
  if (st.status === 'draft') wrap.append(h('button', { class: 'btn', text: 'Mark ready for review', disabled: W.dirty, dataset: { testid: 'ready' }, onclick: () => setStatus(W, 'ready-for-review') }));
  if (st.status === 'ready-for-review') {
    wrap.append(h('button', { class: 'btn', text: 'Back to draft', onclick: () => setStatus(W, 'draft') }));
    wrap.append(h('button', { class: 'btn', text: 'Record approval…', dataset: { testid: 'approve' }, onclick: () => approveModal(W) }));
  }
  if (st.status === 'approved') wrap.append(h('button', { class: 'btn', text: 'Revoke approval', onclick: () => setStatus(W, 'draft') }));
  wrap.append(h('button', { class: 'btn', text: 'Publish', disabled: true, title: 'Publishing is not available here: production templates go through the reviewed allowlist and deployment procedure (docs/04, docs/13).' }));
  return wrap;
}

async function save(W) {
  const res = await api('/drafts/' + W.id, { method: 'PUT', body: JSON.stringify({ baseVersion: W.d.state.version, mapping: W.mapping, note: 'edited in the mapping editor' }) });
  if (res.status === 409) { toast('Someone else saved a newer version. Reload before saving.', true); return; }
  if (!res.ok) { toast(res.body.message || 'Save failed', true); return; }
  W.d = res.body; W.mapping = JSON.parse(JSON.stringify(res.body.mapping)); W.dirty = false; W.test = null;
  toast(`Saved version ${W.d.state.version}`); renderDraft(W, true);
}
async function setStatus(W, status, approval) {
  const res = await api(`/drafts/${W.id}/status`, { method: 'POST', body: JSON.stringify({ baseVersion: W.d.state.version, status, approval }) });
  if (!res.ok) { toast(res.body.message || 'Not allowed', true); return false; }
  W.d = res.body; toast('Status: ' + STATUS_TEXT[status]); renderDraft(W, true); return true;
}
function approveModal(W) {
  const by = h('input', { placeholder: 'Name of the person approving', dataset: { testid: 'approved-by' } });
  const on = h('input', { type: 'date', value: new Date().toISOString().slice(0, 10), dataset: { testid: 'approved-on' } });
  const ref = h('input', { placeholder: 'Ticket, email or decision reference', dataset: { testid: 'approval-ref' } });
  const confirm = h('input', { type: 'checkbox', dataset: { testid: 'approval-confirm' } });
  modal('Record an approval', [
    h('div', { class: 'banner warn', text: 'Record only an approval that the named person actually gave. It applies to this mapping version only. It does not deploy the form or add it to the production allowlist.' }),
    h('div', { class: 'form-grid' }, h('label', { class: 'field' }, 'Approved by', by), h('label', { class: 'field' }, 'Approved on', on), h('label', { class: 'field' }, 'Reference', ref)),
    h('label', { class: 'row' }, confirm, 'I confirm this approval was given by the named person.'),
  ], 'Record approval', async () => {
    if (!confirm.checked) { toast('Confirm the approval first.', true); return false; }
    return setStatus(W, 'approved', { approvedBy: by.value, approvedOn: on.value, reference: ref.value });
  });
}

// ---------------------------------------------------------------------------------------------- viewer
function viewer(W) {
  const pages = W.tpl.analysis.pageSizes;
  const page = pages[W.page];
  const scale = (96 / 72) * W.zoom;
  const img = h('img', { src: `${API}/templates/${W.d.state.templateSha256}/pages/${W.page}.png?dpi=${Math.round(96 * W.zoom)}`, alt: `Page ${W.page + 1}`, draggable: 'false' });
  img.style.width = Math.round(page.width * scale) + 'px'; img.style.height = Math.round(page.height * scale) + 'px';
  const wrap = h('div', { class: 'page-wrap', dataset: { testid: 'page' } }, img);
  for (const f of W.fields) {
    f.widgets.filter(w => w.page === W.page).forEach(w => {
      const mapped = rulesFor(W, f.name).length > 0;
      const box = h('div', { class: 'box' + (f.protectedField ? ' protected' : mapped ? ' mapped' : '') + (W.selected === f.name ? ' selected' : ''),
        title: `${f.name} (${f.kind})${f.label ? ' — ' + f.label : ''}`, dataset: { field: f.name },
        onclick: () => { W.selected = f.name; renderDraft(W, true); } });
      Object.assign(box.style, { left: w.x * scale + 'px', top: w.y * scale + 'px', width: Math.max(w.width * scale, 6) + 'px', height: Math.max(w.height * scale, 6) + 'px' });
      wrap.append(box);
    });
  }
  const nav = h('div', { class: 'viewer-tools' },
    h('button', { class: 'btn small', text: '‹', disabled: W.page === 0, onclick: () => { W.page--; renderDraft(W); } }),
    h('span', { text: `Page ${W.page + 1} of ${pages.length}` }),
    h('button', { class: 'btn small', text: '›', disabled: W.page >= pages.length - 1, onclick: () => { W.page++; renderDraft(W); } }),
    h('span', { class: 'spacer' }),
    h('span', { class: 'chip mapped', text: '■ mapped' }), h('span', { class: 'chip', text: '□ unmapped' }), h('span', { class: 'chip blocked', text: '▧ never filled' }),
    h('button', { class: 'btn small', text: '−', onclick: () => { W.zoom = Math.max(0.6, W.zoom - 0.2); renderDraft(W, true); } }),
    h('button', { class: 'btn small', text: '+', onclick: () => { W.zoom = Math.min(2.0, W.zoom + 0.2); renderDraft(W, true); } }));
  return h('div', { class: 'viewer panel' }, nav, h('div', { class: 'pane-scroll' }, wrap));
}

// ---------------------------------------------------------------------------------------------- inspector + editor
function inspector(W) {
  const f = W.selected && W.byName.get(W.selected);
  if (!f) return h('div', { class: 'panel', dataset: { testid: 'inspector' } }, h('h2', { text: 'Field inspector' }),
    h('p', { class: 'muted', text: 'Select a field on the page or in the Fields tab.' }));
  const rules = rulesFor(W, f.name);
  const kv = h('dl', { class: 'kv' }, [
    ['PDF field', f.name], ['Type', f.kind], ['Printed label', f.label || '—'], ['Options', f.options.length ? f.options.join(', ') : '—'],
    ['Widgets', f.widgets.map(w => 'p' + (w.page + 1)).join(', ')], ['Flags', [f.required && 'required', f.readOnly && 'read-only', f.comb && 'comb', f.multiline && 'multi-line',
      f.maxLen && 'max ' + f.maxLen].filter(Boolean).join(', ') || '—'], ['Mapping', rules.length ? rules.length + ' rule(s)' : 'not mapped']
  ].map(([k, v]) => [h('dt', { text: k }), h('dd', { class: k === 'PDF field' ? 'mono' : '', text: String(v) })]).flat());
  const panel = h('div', { class: 'panel grid', dataset: { testid: 'inspector', field: f.name } }, h('h2', { text: 'Field inspector' }), kv);
  if (f.protectedField) {
    panel.append(h('div', { class: 'banner bad', text: 'Signature / push button: never filled by the engine and cannot be mapped.' }));
    return panel;
  }
  if (W.byName && f && isDeclaration(f)) panel.append(h('div', { class: 'banner warn', text: 'This reads like a declaration, certification or consent. Do not pre-fill or tick it without an RSFA decision; constant ticks are not available here.' }));
  panel.append(editor(W, f, rules));
  return panel;
}
function isDeclaration(f) { return /\b(declar\w*|certif\w*|consent\w*|acknowledg\w*|sign(ed|ature)?|authori[sz]\w*|confirm\w*|agree\w*|witness\w*)\b/i.test((f.label || '') + ' ' + f.name); }

function editor(W, f, rules) {
  const box = h('div', { class: 'grid', dataset: { testid: 'editor' } }, h('h2', { text: 'Mapping' }));
  const isButton = f.kind === 'checkbox' || f.kind === 'radio';
  rules.forEach((r, i) => box.append(ruleCard(W, f, r, i)));
  const add = h('button', { class: 'btn', text: rules.length ? 'Add another condition' : 'Add mapping', dataset: { testid: 'add-mapping' },
    onclick: () => {
      const rule = { pdfField: f.name, source: isButton ? { type: 'condition', operator: 'equals' } : { type: 'column' } };
      if (isButton && (f.kind === 'radio' || f.options.length > 1)) rule.option = f.options[0];
      W.mapping.fields.push(rule); markDirty(W);
    } });
  if (!isButton && rules.length) add.disabled = true;
  box.append(add);
  if (isButton) box.append(h('p', { class: 'muted', text: 'A box is ticked only when its condition holds. Several conditions may select different options of one radio group; unknown Monday labels can be refused with a closed-option check (Validation tab).' }));
  return box;
}

function ruleCard(W, f, r, idx) {
  const isButton = f.kind === 'checkbox' || f.kind === 'radio';
  const card = h('div', { class: 'card grid', dataset: { testid: 'rule' } });
  const types = isButton ? [['condition', 'Tick when a Monday value matches']] : [['column', 'Monday column value'], ['derived', 'Make-derived key (not a column)'], ['today', "Today's date"], ['constant', 'Fixed text']];
  const typeSel = h('select', { dataset: { testid: 'source-type' } }, types.map(([v, t]) => h('option', { value: v, text: t, selected: r.source.type === v })));
  typeSel.addEventListener('change', () => { r.source = { type: typeSel.value, operator: typeSel.value === 'condition' ? 'equals' : undefined }; markDirty(W); });
  card.append(h('label', { class: 'field' }, 'Source', typeSel));
  if (r.source.type === 'column' || r.source.type === 'condition') card.append(columnPicker(W, r));
  if (r.source.type === 'condition') {
    const col = S.schemaById.get(r.source.columnId);
    const op = h('select', { dataset: { testid: 'operator' } }, [['equals', 'equals'], ['includes', 'includes (multi-select)'], ['present', 'has any value'], ['absent', 'is empty']]
      .map(([v, t]) => h('option', { value: v, text: t, selected: r.source.operator === v })));
    op.addEventListener('change', () => { r.source.operator = op.value; markDirty(W); });
    card.append(h('label', { class: 'field' }, 'Operator', op));
    if (r.source.operator === 'equals' || r.source.operator === 'includes') {
      const labels = col && col.labels.length ? col.labels : null;
      const val = labels ? h('select', { dataset: { testid: 'value' } }, h('option', { value: '', text: 'Select a Monday label…' }),
        labels.map(l => h('option', { value: l, text: l, selected: r.source.value === l })), r.source.value && !labels.includes(r.source.value)
          ? h('option', { value: r.source.value, text: r.source.value + ' (not a current label)', selected: true }) : null)
        : h('input', { value: r.source.value || '', placeholder: 'Monday value', dataset: { testid: 'value' } });
      val.addEventListener('change', () => { r.source.value = val.value; markDirty(W); });
      card.append(h('label', { class: 'field' }, 'Monday value', val));
    }
    if (f.kind === 'radio' || f.options.length > 1) {
      const opt = h('select', { dataset: { testid: 'option' } }, f.options.map(o => h('option', { value: o, text: o, selected: r.option === o })));
      opt.addEventListener('change', () => { r.option = opt.value; markDirty(W); });
      card.append(h('label', { class: 'field' }, 'Selects PDF option', opt));
    }
  }
  if (r.source.type === 'derived') {
    const k = h('input', { value: r.source.key || '', placeholder: 'e.g. 1st Property Address Street' });
    k.addEventListener('change', () => { r.source.key = k.value; markDirty(W); });
    card.append(h('label', { class: 'field' }, 'Payload key', k),
      h('div', { class: 'banner warn', text: 'Not a Monday column: Make 8731317 computes this key (GPT address split, split dates). No schema can check it, so prefer a Monday column where one exists.' }));
  }
  if (r.source.type === 'today') {
    const fmt = h('select', {}, ['dd/MM/yyyy', 'ddMMyyyy', 'ddMMyy', 'd MMMM yyyy'].map(v => h('option', { value: v, text: v, selected: (r.source.format || 'dd/MM/yyyy') === v })));
    fmt.addEventListener('change', () => { r.source.format = fmt.value; markDirty(W); });
    card.append(h('label', { class: 'field' }, 'Date format', fmt));
    if (isDeclaration(f)) card.append(h('div', { class: 'banner warn', text: 'Pre-filled dates next to a declaration need RSFA review.' }));
  }
  if (r.source.type === 'constant') {
    const t = h('input', { value: r.source.text || '', placeholder: 'Fixed text' });
    t.addEventListener('change', () => { r.source.text = t.value; markDirty(W); });
    card.append(h('label', { class: 'field' }, 'Text', t));
  }
  const req = h('input', { type: 'checkbox', checked: !!r.required });
  req.addEventListener('change', () => { r.required = req.checked; markDirty(W); });
  card.append(h('div', { class: 'row' }, h('label', { class: 'row' }, req, 'Required (refuse to generate without it)'), h('span', { class: 'spacer' }),
    h('button', { class: 'btn small danger', text: 'Remove', dataset: { testid: 'remove-rule' }, onclick: () => { W.mapping.fields.splice(W.mapping.fields.indexOf(r), 1); markDirty(W); } })));
  return card;
}

function columnPicker(W, r) {
  const current = S.schemaById.get(r.source.columnId);
  const search = h('input', { placeholder: 'Search Monday columns (title or ID)…', value: '', dataset: { testid: 'column-search' } });
  const list = h('div', { class: 'colpick', dataset: { testid: 'column-list' } });
  const fill = () => {
    const q = search.value.toLowerCase();
    const cols = S.schema.filter(c => !q || c.title.toLowerCase().includes(q) || c.id.toLowerCase().includes(q)).slice(0, 60);
    list.replaceChildren(...cols.map(c => h('div', { class: c.id === r.source.columnId ? 'active' : '', dataset: { column: c.id },
      onclick: () => { r.source.columnId = c.id; r.source.columnTitle = c.title; markDirty(W); } },
      c.title, h('small', { text: `${c.id} · ${c.type}${c.labels.length ? ' · ' + c.labels.length + ' labels' : ''}${c.duplicateTitle ? ' · duplicate title!' : ''}` }))));
    if (!cols.length) list.append(h('div', { class: 'muted', text: 'No column matches.' }));
  };
  search.addEventListener('input', fill); fill();
  const chosen = current ? h('div', { class: 'banner info' }, h('b', { text: current.title }), ' ', h('span', { class: 'mono', text: `(${current.id})` }))
    : r.source.columnId ? h('div', { class: 'banner bad', text: `Column ${r.source.columnId} is not in the current Monday schema (last title: ${r.source.columnTitle || '?'})` })
      : h('div', { class: 'banner warn', text: 'No Monday column selected yet.' });
  return h('div', { class: 'grid' }, h('label', { class: 'field' }, 'Monday column', chosen), search, list);
}

// ---------------------------------------------------------------------------------------------- tabs
function tabs(W) {
  const counts = { problems: W.d.problems.length, suggestions: W.suggestions ? W.suggestions.proposals.length : null };
  const names = [['fields', 'Fields'], ['problems', 'Problems'], ['suggestions', 'Suggestions'], ['test', 'Test'], ['validation', 'Validation'], ['history', 'History']];
  const bar = h('div', { class: 'tabs' }, names.map(([k, t]) => h('button', { class: W.tab === k ? 'active' : '', dataset: { tab: k },
    onclick: () => { W.tab = k; renderDraft(W, true); } }, t, counts[k] ? h('span', { class: 'count', text: counts[k] }) : null)));
  const body = { fields: fieldsTab, problems: problemsTab, suggestions: suggestionsTab, test: testTab, validation: validationTab, history: historyTab }[W.tab](W);
  return h('div', { class: 'panel' }, bar, body);
}

function fieldsTab(W) {
  const mappedCount = new Set(W.mapping.fields.map(f => f.pdfField)).size;
  const rows = W.fields.map(f => {
    const n = rulesFor(W, f.name).length;
    return h('tr', { class: W.selected === f.name ? 'sel' : '', dataset: { field: f.name } },
      h('td', {}, h('button', { class: 'btn small mono', text: f.name, dataset: { testid: 'field-link' }, onclick: () => { W.selected = f.name; W.page = f.widgets[0] ? f.widgets[0].page : W.page; renderDraft(W, true); } })),
      h('td', { class: 'muted', text: f.kind }), h('td', { class: 'muted', text: (f.label || '').slice(0, 48) }),
      h('td', {}, f.protectedField ? h('span', { class: 'chip blocked', text: 'never' }) : n ? h('span', { class: 'chip approved', text: n + ' rule' + (n > 1 ? 's' : '') }) : h('span', { class: 'chip draft', text: 'unmapped' })));
  });
  return h('div', {}, h('p', { class: 'muted', text: `${mappedCount} of ${W.fields.length} fields mapped. Unmapped fields stay blank.` }),
    h('div', { class: 'pane-scroll' }, h('table', {}, h('thead', {}, h('tr', {}, ['Field', 'Type', 'Label', 'Mapping'].map(t => h('th', { text: t })))), h('tbody', {}, rows))));
}

function problemsTab(W) {
  if (W.dirty) return h('div', { class: 'banner warn', text: 'Save to re-check the mapping.' });
  if (!W.d.problems.length) return h('div', { class: 'banner ok', text: 'No problems: every rule targets a real field and a current Monday column.' });
  return h('ul', { class: 'findings', dataset: { testid: 'problems' } }, W.d.problems.map(p => h('li', { class: p.severity, dataset: { code: p.code } },
    h('b', { text: p.code }), ' ', p.pdfField ? h('span', { class: 'mono', text: p.pdfField + ': ' }) : '', p.message)));
}

function suggestionsTab(W) {
  const out = h('div', { class: 'grid', dataset: { testid: 'suggestions' } });
  const run = h('button', { class: 'btn primary', text: W.suggestions ? 'Refresh suggestions' : 'Suggest mappings', dataset: { testid: 'suggest' },
    onclick: async () => { const r = await api(`/drafts/${W.id}/suggestions`, { method: 'POST', body: '{}' }); W.suggestions = r.body; renderDraft(W, true); } });
  out.append(h('div', { class: 'row' }, run, h('span', { class: 'muted', text: 'Uses template structure and the Monday schema only, never data values.' })));
  const s = W.suggestions;
  if (!s) return out;
  out.append(h('div', { class: 'banner warn', dataset: { testid: 'mock-banner' } }, h('span', { class: 'chip mock', text: s.provider.isMock ? 'MOCK' : s.provider.id }), ' ', s.provider.notice));
  for (const p of s.proposals) {
    const pct = Math.round(p.confidence * 100);
    const barEl = h('span'); barEl.style.width = pct + '%';
    const done = rulesFor(W, p.pdfField).length > 0;
    out.append(h('div', { class: 'card', dataset: { testid: 'proposal', field: p.pdfField } },
      h('div', { class: 'row' }, h('h3', {}, h('span', { class: 'mono', text: p.pdfField }), ' → ', p.columnTitle), h('span', { class: 'spacer' }),
        h('span', { class: 'confidence' }, h('div', { class: 'bar' }, barEl), pct + '%')),
      p.sourceType === 'condition' ? h('div', { class: 'muted', text: `tick when "${p.value}"${p.option ? ' → option ' + p.option : ''}` }) : null,
      h('ul', {}, p.evidence.map(e => h('li', { class: 'muted', text: e }))),
      p.ambiguities.length ? h('div', { class: 'banner warn' }, p.ambiguities.join(' · ')) : null,
      p.alternatives.length ? h('div', { class: 'muted', text: 'Alternatives: ' + p.alternatives.map(a => `${a.columnTitle} (${Math.round(a.confidence * 100)}%)`).join(', ') }) : null,
      h('div', { class: 'row' }, h('span', { class: 'spacer' }),
        h('button', { class: 'btn small', text: 'Show field', onclick: () => { W.selected = p.pdfField; const f = W.byName.get(p.pdfField); if (f && f.widgets[0]) W.page = f.widgets[0].page; renderDraft(W, true); } }),
        h('button', { class: 'btn small primary', text: done ? 'Already mapped' : 'Accept', disabled: done, dataset: { testid: 'accept' }, onclick: () => {
          const rule = { pdfField: p.pdfField, source: p.sourceType === 'condition' ? { type: 'condition', columnId: p.columnId, columnTitle: p.columnTitle, operator: p.operator, value: p.value }
            : { type: 'column', columnId: p.columnId, columnTitle: p.columnTitle }, suggestedBy: s.provider.id };
          if (p.option) rule.option = p.option;
          W.mapping.fields.push(rule); W.selected = p.pdfField; markDirty(W);
        } }))));
  }
  if (s.refused.length) out.append(h('div', { class: 'card' }, h('h3', { text: 'Never suggested' }), h('ul', {}, s.refused.map(r => h('li', {}, h('span', { class: 'mono', text: r.pdfField }), ' — ', r.reason)))));
  if (s.unmatched.length) out.append(h('p', { class: 'muted', text: `${s.unmatched.length} field(s) without a suggestion; map them manually or leave them blank.` }));
  return out;
}

function testTab(W) {
  const out = h('div', { class: 'grid', dataset: { testid: 'test' } });
  if (W.dirty) return h('div', { class: 'banner warn', text: 'Save first: tests always run on a saved version.' });
  out.append(h('div', { class: 'banner info', text: 'Synthetic data only. Never type a real client\'s details here.' }));
  const load = h('button', { class: 'btn', text: 'Fill with fictional values', dataset: { testid: 'synthetic' },
    onclick: async () => { const r = await api(`/drafts/${W.id}/synthetic`, { method: 'POST', body: '{}' }); W.synthetic = r.body; renderDraft(W, true); } });
  out.append(h('div', { class: 'row' }, load));
  if (W.synthetic) {
    const inputs = [];
    const rows = Object.entries(W.synthetic.data).map(([colId, v]) => {
      const c = S.schemaById.get(colId);
      const inp = h('input', { value: v, dataset: { column: colId } });
      inputs.push(inp);
      return h('tr', {}, h('td', {}, c ? c.title : colId, h('div', { class: 'muted mono', text: colId })), h('td', {}, inp));
    });
    const today = h('input', { type: 'date', value: W.synthetic.today });
    out.append(h('table', { class: 'data-table' }, h('tbody', {}, rows)), h('label', { class: 'field' }, "Today's date for the test", today));
    out.append(h('div', { class: 'row' }, h('button', { class: 'btn primary', text: 'Generate test PDF', dataset: { testid: 'generate' }, onclick: async () => {
      const data = {}; inputs.forEach(i => { data[i.dataset.column] = i.value; });
      W.synthetic.data = data; W.synthetic.today = today.value;
      const r = await api(`/drafts/${W.id}/test`, { method: 'POST', body: JSON.stringify({ data, today: today.value }) });
      W.test = r.body; const d = await api('/drafts/' + W.id); W.d = d.body; renderDraft(W, true);
    } })));
  }
  const t = W.test;
  if (t) {
    out.append(h('div', { class: 'banner ' + (t.ok ? 'ok' : 'bad'), dataset: { testid: 'test-result', ok: String(t.ok) }, text: t.ok
      ? `Generated: ${t.filled.length} field(s) filled, ${(t.skipped || []).length} mapped field(s) left blank, ${t.unmapped.length} unmapped. Signatures and declarations untouched.`
      : t.stage === 'compile' ? 'Not generated: the mapping has problems (Problems tab).' : 'Not generated: the data was refused (see issues).' }));
    const issues = (t.issues || []).concat((t.problems || []).filter(p => p.severity === 'error').map(p => ({ severity: 'ERROR', code: p.code, pdfField: p.pdfField, message: p.message })));
    if (issues.length) out.append(h('ul', { class: 'findings' }, issues.map(i => h('li', { class: i.severity === 'ERROR' ? 'error' : 'warning' },
      h('b', { text: i.code }), ' ', i.pdfField ? h('span', { class: 'mono', text: i.pdfField + ': ' }) : '', i.message || ''))));
    if (t.ok) {
      out.append(h('div', { class: 'row' }, h('a', { class: 'btn', href: `${API}/results/${t.resultId}.pdf`, target: '_blank', rel: 'noopener', text: 'Open PDF', dataset: { testid: 'open-pdf' } }),
        h('a', { class: 'btn', href: `${API}/results/${t.resultId}.pdf`, download: `${W.d.state.templateId}-test.pdf`, text: 'Download' }),
        h('span', { class: 'muted mono', text: 'SHA-256 ' + t.pdfSha256.slice(0, 16) + '…' })));
      for (let p = 0; p < t.pages; p++) {
        out.append(h('div', { class: 'compare', dataset: { testid: 'compare' } },
          h('figure', {}, h('figcaption', { text: `Blank template · page ${p + 1}` }), h('img', { src: `${API}/results/${t.resultId}/template/pages/${p}.png?dpi=72`, alt: 'template page' })),
          h('figure', {}, h('figcaption', { text: `Generated (synthetic) · page ${p + 1}` }), h('img', { src: `${API}/results/${t.resultId}/pages/${p}.png?dpi=72`, alt: 'generated page' }))));
      }
      if (t.unmapped.length) out.append(h('details', {}, h('summary', { text: `${t.unmapped.length} unmapped field(s) left blank` }), h('p', { class: 'mono muted', text: t.unmapped.join(', ') })));
    }
  }
  return out;
}

function validationTab(W) {
  const v = W.mapping.validation || (W.mapping.validation = {});
  v.closedOptions = v.closedOptions || [];
  const condCols = [...new Set(W.mapping.fields.filter(f => f.source.type === 'condition' && f.source.columnId).map(f => f.source.columnId))];
  const out = h('div', { class: 'grid' }, h('p', { class: 'muted', text: 'Closed options refuse a Monday value that matches no box (for example a renamed label) instead of silently leaving every box blank. Multi-select columns may tick several boxes; a single-select column with two values is refused.' }));
  if (!condCols.length) out.append(h('div', { class: 'muted', text: 'No checkbox conditions yet.' }));
  for (const id of condCols) {
    const c = S.schemaById.get(id);
    const existing = v.closedOptions.find(o => o.columnId === id);
    const on = h('input', { type: 'checkbox', checked: !!existing });
    const multi = h('input', { type: 'checkbox', checked: !!(existing && existing.multiSelect), disabled: !existing });
    on.addEventListener('change', () => { if (on.checked) v.closedOptions.push({ columnId: id, multiSelect: false }); else v.closedOptions = v.closedOptions.filter(o => o.columnId !== id); markDirty(W); });
    multi.addEventListener('change', () => { existing.multiSelect = multi.checked; markDirty(W); });
    out.append(h('div', { class: 'card row' }, h('b', { text: c ? c.title : id }), h('span', { class: 'spacer' }),
      h('label', { class: 'row' }, on, 'Refuse unknown values'), h('label', { class: 'row' }, multi, 'Multi-select')));
  }
  return out;
}

function historyTab(W) {
  const st = W.d.state;
  return h('div', { class: 'grid' },
    st.approval ? h('div', { class: 'banner ok', text: `Approved by ${st.approval.approvedBy} on ${st.approval.approvedOn} (${st.approval.reference}) for version ${st.approval.version}. ${st.approval.scope}.` }) : null,
    st.lastTest ? h('div', { class: 'muted', text: `Last test: version ${st.lastTest.version}, ${st.lastTest.ok ? 'generated' : 'refused'}, ${st.lastTest.at}` }) : null,
    h('ul', { class: 'timeline', dataset: { testid: 'audit' } }, W.d.audit.slice().reverse().map(e => h('li', {},
      h('b', { text: `v${e.version} · ${e.action}` }), ' ', h('span', { class: 'muted', text: `${e.at.replace('T', ' ').slice(0, 19)} · ${e.actor}` }),
      e.note ? h('div', { class: 'muted', text: e.note }) : null,
      e.changes && e.changes.length ? h('div', { class: 'mono muted', text: e.changes.map(c => `${c.change} ${c.pdfField}`).join(' · ') }) : null))));
}
