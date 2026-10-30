(() => {
  const checkout = document.getElementById('payment-checkout');
  const root = checkout || document.getElementById('financial-status');
  if (!root || root.dataset.terminal === 'true') return;
  const qr = document.getElementById('payment-qr');
  const cancel = document.getElementById('payment-cancel');
  const message = document.getElementById(checkout ? 'payment-status' : 'polling-message');
  let stopped = false;
  let timer;
  let request;
  const hideExpiredQr = () => {
    if (checkout && Date.now() >= Date.parse(checkout.dataset.expiresAt)) {
      qr.hidden = true;
      cancel.hidden = true;
    }
  };
  let expiryTimer = checkout ? window.setInterval(hideExpiredQr, 1000) : null;
  hideExpiredQr();
  const poll = async () => {
    if (stopped || document.hidden || request) return;
    request = new AbortController();
    const timeout = window.setTimeout(() => request?.abort(), 10000);
    try {
      const response = await fetch(root.dataset.statusUrl, {
        credentials: 'same-origin', cache: 'no-store', signal: request.signal, headers: { Accept: 'application/json' }
      });
      if (stopped || document.hidden) return;
      if (response.status === 401 || response.status === 403 || response.redirected) {
        stopped = true;
        if (checkout) { qr.hidden = true; cancel.hidden = true; }
        message.textContent = 'Phiên đăng nhập đã thay đổi. Vui lòng đăng nhập lại để xem trạng thái.';
      } else {
        if (!response.ok) throw new Error('Status unavailable');
        const payment = await response.json();
        if (stopped || document.hidden) return;
        const state = checkout || root.dataset.intent === 'true' ? payment.status : `${payment.status}:${payment.overdue}`;
        if (state !== (checkout ? 'ACTIVE' : root.dataset.state)) {
          stopped = true;
          if (checkout) { qr.hidden = true; window.location.assign(checkout.dataset.resultUrl); }
          else window.location.reload();
        } else if (!checkout) message.textContent = '';
      }
    } catch (_) {
      if (!stopped && !document.hidden) message.textContent = 'Chưa cập nhật được trạng thái. Hệ thống sẽ kiểm tra lại; vui lòng không chuyển thêm tiền.';
    } finally {
      window.clearTimeout(timeout);
      request = null;
      if (stopped) window.clearInterval(expiryTimer);
      else if (!document.hidden) timer = window.setTimeout(poll, 2000);
    }
  };
  timer = window.setTimeout(poll, 2000);
  document.addEventListener('visibilitychange', () => {
    window.clearTimeout(timer);
    if (document.hidden) request?.abort();
    else if (!stopped && !request) timer = window.setTimeout(poll, 2000);
    hideExpiredQr();
  });
  window.addEventListener('pagehide', () => {
    stopped = true; window.clearTimeout(timer); window.clearInterval(expiryTimer); request?.abort();
  });
  window.addEventListener('pageshow', event => {
    if (event.persisted) {
      stopped = false;
      if (checkout) expiryTimer = window.setInterval(hideExpiredQr, 1000);
      timer = window.setTimeout(poll, 2000);
    }
  });
})();
