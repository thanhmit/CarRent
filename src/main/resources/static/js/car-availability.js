(() => {
  const root = document.querySelector('[data-car-availability]');
  if (!root) return;
  const part = name => root.querySelector(`[data-calendar-${name}]`);
  const start = part('start'), end = part('end'), days = part('days'), message = part('message');
  const link = document.querySelector('[data-calendar-book]');
  const today = root.dataset.today;
  const validDate = value => {
    if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
    const time = Date.parse(`${value}T00:00:00Z`);
    return Number.isFinite(time) && new Date(time).toISOString().slice(0, 10) === value;
  };
  const ranges = Array.from(root.querySelectorAll('[data-busy-range]'), row => row.dataset);
  const dataValid = validDate(today) && ranges.every(row => validDate(row.start) && validDate(row.end) && row.start < row.end);
  const busy = date => ranges.some(row => row.start <= date && date < row.end);
  const overlaps = (from, to) => ranges.some(row => from < row.end && to > row.start);
  let mode = 'start';
  let month = new Date(`${validDate(start.value) && start.value >= today ? start.value : today}T00:00:00Z`);
  month.setUTCDate(1);
  const iso = date => date.toISOString().slice(0, 10);
  const label = value => value.split('-').reverse().join('/');
  const selectable = date => dataValid && date >= today && (mode === 'start'
    ? !busy(date)
    : validDate(start.value) && start.value >= today && date > start.value && !overlaps(start.value, date));

  const updateLink = () => {
    let error = '';
    if (!dataValid) error = 'Chưa thể tải lịch bận. Vui lòng tải lại trang; hệ thống sẽ kiểm tra lịch khi gửi đơn.';
    else if (!validDate(start.value) || !validDate(end.value)) error = 'Chọn ngày nhận và ngày trả xe.';
    else if (start.value < today || end.value <= start.value) error = 'Ngày nhận không được ở quá khứ; ngày trả phải sau ngày nhận.';
    else if (overlaps(start.value, end.value)) error = 'Xe đã có đơn trong khoảng ngày này. Vui lòng chọn lịch khác.';
    end.setCustomValidity(error);
    message.textContent = error || 'Khoảng ngày đã chọn chưa giữ chỗ. Hệ thống kiểm tra lại khi gửi đơn.';
    if (link) {
      link.setAttribute('aria-disabled', String(Boolean(error)));
      link.classList.toggle('opacity-50', Boolean(error));
      if (error) link.removeAttribute('href');
      else {
        const url = new URL(link.dataset.bookingUrl, window.location.origin);
        url.searchParams.set('startDate', start.value);
        url.searchParams.set('endDate', end.value);
        link.href = url.pathname + url.search;
      }
    }
    return !error;
  };

  const render = () => {
    part('month').textContent = new Intl.DateTimeFormat('vi-VN', { month: 'long', year: 'numeric', timeZone: 'UTC' }).format(month);
    part('start-mode').setAttribute('aria-pressed', String(mode === 'start'));
    part('end-mode').setAttribute('aria-pressed', String(mode === 'end'));
    part('previous').disabled = iso(month).slice(0, 7) <= today.slice(0, 7);
    part('next').disabled = month.getUTCFullYear() === 9999 && month.getUTCMonth() === 11;
    const cells = [];
    for (let i = 0; i < (month.getUTCDay() + 6) % 7; i++) cells.push(document.createElement('span'));
    for (let date = new Date(month); date.getUTCMonth() === month.getUTCMonth(); date.setUTCDate(date.getUTCDate() + 1)) {
      const value = iso(date), button = document.createElement('button');
      const occupied = busy(value), selected = value === start.value || value === end.value;
      button.type = 'button';
      button.dataset.date = value;
      button.disabled = !selectable(value);
      button.textContent = String(date.getUTCDate());
      button.setAttribute('aria-label', `${label(value)}${occupied ? ' — Đã có đơn giữ lịch' : ''}${selected ? ' — Đang chọn' : ''}`);
      button.setAttribute('aria-pressed', String(selected));
      button.className = 'h-10 rounded-lg text-label-md focus-visible:outline focus-visible:outline-2 focus-visible:outline-secondary '
        + (button.disabled ? 'bg-surface-container text-on-surface-variant opacity-40 cursor-not-allowed ' : 'hover:bg-secondary-fixed cursor-pointer ')
        + (selected ? 'ring-2 ring-secondary font-semibold ' : '')
        + (start.value && end.value && start.value < value && value < end.value && !overlaps(start.value, end.value) ? 'bg-secondary-fixed ' : '');
      button.addEventListener('click', () => {
        if (button.disabled) return;
        if (mode === 'start') {
          start.value = value;
          if (!validDate(end.value) || end.value <= value || overlaps(value, end.value)) end.value = '';
          mode = 'end';
        } else { end.value = value; mode = 'start'; }
        updateLink(); render(); part(`${mode}-mode`).focus?.();
      });
      cells.push(button);
    }
    days.replaceChildren(...cells);
  };

  ['start', 'end'].forEach(name => {
    part(name).addEventListener('focus', () => { mode = name; render(); });
    part(name).addEventListener('input', () => { updateLink(); render(); });
    part(`${name}-mode`).addEventListener('click', () => { mode = name; render(); });
  });
  ['previous', 'next'].forEach(name => part(name).addEventListener('click', () => {
    month.setUTCMonth(month.getUTCMonth() + (name === 'next' ? 1 : -1)); render();
  }));
  if (link) link.addEventListener('click', event => { if (!updateLink()) event.preventDefault(); });
  updateLink(); render(); root.hidden = false;
  const fallback = document.querySelector('[data-calendar-fallback]');
  if (fallback) fallback.hidden = true;
})();
