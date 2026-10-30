const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../../main/resources/static/js/payment-polling.js'), 'utf8');

function browser({ checkout = false, terminal = false, fetcher } = {}) {
  const events = {}, timers = new Map(), intervals = new Map(), requests = [];
  let sequence = 0, reloads = 0, redirect;
  const message = { textContent: '' }, qr = { hidden: false }, cancel = { hidden: false };
  const root = { dataset: { terminal: String(terminal), state: 'READY:false', statusUrl: '/status',
    resultUrl: '/result', expiresAt: '2000-01-01T00:00:00Z' } };
  const document = { hidden: false, addEventListener: (event, callback) => { events[event] = callback; },
    getElementById: id => ({ 'financial-status': checkout ? null : root, 'payment-checkout': checkout ? root : null,
      'payment-status': message, 'polling-message': message, 'payment-qr': qr, 'payment-cancel': cancel })[id] };
  const window = {
    setTimeout: (callback, delay) => { const id = ++sequence; timers.set(id, { callback, delay }); return id; },
    clearTimeout: id => timers.delete(id),
    setInterval: callback => { const id = ++sequence; intervals.set(id, callback); return id; },
    clearInterval: id => intervals.delete(id),
    addEventListener: (event, callback) => { events[event] = callback; },
    location: { reload: () => reloads++, assign: url => { redirect = url; } }
  };
  vm.runInNewContext(source, { document, window, AbortController, Date, fetch: async (url, options) => {
    requests.push({ url, options });
    return fetcher ? fetcher(options) : response('READY', false);
  } });
  return { document, events, requests, message, qr, cancel,
    get reloads() { return reloads; }, get redirect() { return redirect; },
    count: delay => [...timers.values()].filter(t => t.delay === delay).length,
    tick(delay = 2000) {
      const entry = [...timers].find(([, task]) => task.delay === delay);
      assert.ok(entry, `Missing ${delay}ms timer`);
      timers.delete(entry[0]); return entry[1].callback();
    }
  };
}
function response(status, overdue) { return { ok: true, status: 200, json: async () => ({ status, overdue }) }; }

test('one request at a time; success reload follows committed backend state', async () => {
  let finish;
  const b = browser({ fetcher: () => new Promise(resolve => { finish = resolve; }) });
  const pending = b.tick();
  assert.equal(b.requests.length, 1);
  assert.equal(b.count(2000), 0);
  finish(response('READY', false)); await pending;
  assert.equal(b.reloads, 0); assert.equal(b.count(2000), 1);
  const second = b.tick(); finish(response('SUCCEEDED', false)); await second;
  assert.equal(b.reloads, 1); assert.equal(b.count(2000), 0);
  assert.equal(b.requests[0].options.cache, 'no-store');
});

test('hidden tab aborts polling, visible tab resumes, page exit stops requests', async () => {
  const b = browser({ fetcher: ({ signal }) => new Promise((resolve, reject) => {
    signal.addEventListener('abort', () => reject(new Error('Aborted')));
  }) });
  const pending = b.tick();
  b.document.hidden = true; b.events.visibilitychange(); await pending;
  assert.equal(b.requests[0].options.signal.aborted, true); assert.equal(b.count(2000), 0);
  b.document.hidden = false; b.events.visibilitychange(); assert.equal(b.count(2000), 1);
  b.events.pagehide(); assert.equal(b.count(2000), 0);
  b.events.pageshow({ persisted: true }); assert.equal(b.count(2000), 1);
});

test('expired authentication stops polling and tells user to sign in', async () => {
  for (const auth of [{ status: 401 }, { status: 403 }, { redirected: true }]) {
    const b = browser({ fetcher: () => auth }); await b.tick();
    assert.equal(b.count(2000), 0); assert.match(b.message.textContent, /đăng nhập lại/);
  }
});

test('network timeout aborts and retries without treating uncertainty as success', async () => {
  const b = browser({ fetcher: ({ signal }) => new Promise((resolve, reject) => {
    signal.addEventListener('abort', () => reject(new Error('Timeout')));
  }) });
  const pending = b.tick(); b.tick(10000); await pending;
  assert.equal(b.reloads, 0); assert.equal(b.count(2000), 1); assert.match(b.message.textContent, /Chưa cập nhật/);
});

test('terminal refund has no polling; a response completed after page exit cannot reload', async () => {
  assert.equal(browser({ terminal: true }).count(2000), 0);
  let finish;
  const b = browser({ fetcher: () => new Promise(resolve => { finish = resolve; }) });
  const pending = b.tick(); b.events.pagehide(); finish(response('SUCCEEDED', false)); await pending;
  assert.equal(b.reloads, 0); assert.equal(b.count(2000), 0);
});

test('checkout hides expired QR and redirects only from backend status', async () => {
  const b = browser({ checkout: true, fetcher: () => response('RECEIVED') });
  assert.equal(b.qr.hidden, true); assert.equal(b.cancel.hidden, true); assert.equal(b.redirect, undefined);
  await b.tick(); assert.equal(b.redirect, '/result'); assert.equal(b.count(2000), 0);
});
