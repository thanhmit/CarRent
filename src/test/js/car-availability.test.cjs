const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const file = path.join(__dirname, '../../main/resources/static/js/car-availability.js');
const source = fs.existsSync(file) ? fs.readFileSync(file, 'utf8') : '';

class Element {
  constructor() {
    this.dataset = {}; this.children = []; this.events = {}; this.attrs = {};
    this.value = ''; this.textContent = ''; this.disabled = false; this.hidden = false;
    this.classList = { toggle() {} };
  }
  addEventListener(name, fn) { (this.events[name] ??= []).push(fn); }
  fire(name) { const e = { prevented: false, preventDefault() { this.prevented = true; } }; (this.events[name] || []).forEach(fn => fn(e)); return e; }
  click() { if (!this.disabled) return this.fire('click'); }
  setAttribute(name, value) { this.attrs[name] = value; }
  removeAttribute(name) { delete this.attrs[name]; }
  replaceChildren(...children) { this.children = children; }
  append(...children) { this.children.push(...children); }
  setCustomValidity(message) { this.validationMessage = message; }
}

function browser({ start = '', end = '', ranges = [['2030-01-10', '2030-01-12']], today = '2030-01-01' } = {}) {
  const root = new Element(); root.dataset.today = today;
  const parts = Object.fromEntries(['start', 'end', 'month', 'days', 'message', 'previous', 'next', 'start-mode', 'end-mode'].map(key => [key, new Element()]));
  parts.start.value = start; parts.end.value = end;
  const link = new Element(); link.href = 'http://localhost/bookings/new?carId=5'; link.dataset.bookingUrl = '/bookings/new?carId=5';
  const busy = ranges.map(([start, end]) => ({ dataset: { start, end } }));
  root.querySelector = selector => parts[selector.match(/data-calendar-([\w-]+)/)?.[1]];
  root.querySelectorAll = () => busy;
  const document = { querySelector: selector => selector === '[data-car-availability]' ? root : selector === '[data-calendar-book]' ? link : null, createElement: () => new Element() };
  vm.runInNewContext(source, { document, URL, Date, Intl, window: { location: { origin: 'http://localhost' } } });
  const day = value => parts.days.children.find(cell => cell.dataset.date === value);
  const change = (from, to) => { parts.start.value = from; parts.end.value = to; parts.end.fire('input'); };
  return { root, parts, link, day, change };
}

test('renders a month and disables busy pickup days but allows the return boundary', () => {
  const b = browser();
  assert.ok(b.parts.days.children.length >= 31, 'calendar must render');
  assert.equal(b.day('2030-01-10').disabled, true);
  assert.equal(b.day('2030-01-11').disabled, true);
  assert.equal(b.day('2030-01-12').disabled, false);
});

for (const [start, end, blocked] of [['09','10',false],['12','13',false],['10','11',true],['11','12',true],['09','13',true]]) {
  test(`validates the entire half-open range ${start} to ${end}`, () => {
    const b = browser({ start: `2030-01-${start}`, end: `2030-01-${end}` });
    assert.equal(b.link.attrs['aria-disabled'], String(blocked));
    assert.equal(b.link.fire('click').prevented, blocked);
    if (!blocked) assert.equal(new URL(b.link.href, 'http://localhost').searchParams.get('endDate'), `2030-01-${end}`);
  });
}

test('return selection permits the next booking start but blocks crossing it', () => {
  const b = browser();
  b.day('2030-01-09').click();
  assert.equal(b.parts.start.value, '2030-01-09');
  assert.equal(b.day('2030-01-10').disabled, false);
  assert.equal(b.day('2030-01-12').disabled, true);
  b.day('2030-01-10').click();
  assert.equal(b.parts.end.value, '2030-01-10');
  assert.equal(b.link.attrs['aria-disabled'], 'false');
});

test('correcting an overlapping typed range restores the booking link', () => {
  const b = browser({ start: '2030-01-09', end: '2030-01-13' });
  b.change('2030-01-12', '2030-01-14');
  assert.equal(b.link.fire('click').prevented, false);
  assert.equal(new URL(b.link.href, 'http://localhost').searchParams.get('startDate'), '2030-01-12');
  assert.equal(b.parts.end.validationMessage, '');
});

test('handles adjacent reservations, past dates, year change and leap February', () => {
  const b = browser({ today: '2029-12-31', ranges: [['2030-01-10','2030-01-12'],['2030-01-12','2030-01-14']] });
  assert.equal(b.day('2029-12-30').disabled, true);
  b.parts.next.click();
  assert.equal(b.day('2030-01-12').disabled, true);
  assert.equal(b.day('2030-01-14').disabled, false);
  const leap = browser({ today: '2032-02-01', ranges: [] });
  assert.ok(leap.day('2032-02-29'));
});

test('rejects reversed, missing and malformed dates without asserting availability', () => {
  for (const [start,end] of [['2030-01-12','2030-01-11'],['2030-01-12',''],['2030-02-31','2030-03-02']]) {
    const b = browser({start,end});
    assert.equal(b.link.fire('click').prevented, true);
  }
});
