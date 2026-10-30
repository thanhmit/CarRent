(() => {
  const checkout = document.getElementById('payment-checkout');
  if (!checkout) return;
  const qr = document.getElementById('payment-qr');
  const cancel = document.getElementById('payment-cancel');
  const message = document.getElementById('payment-status');
  let stopped = false;
  const hideExpiredQr = () => {
    if (Date.now() >= Date.parse(checkout.dataset.expiresAt)) {
      qr.hidden = true;
      cancel.hidden = true;
    }
  };
  const timer = window.setInterval(hideExpiredQr, 1000);
  hideExpiredQr();
  const poll = async () => {
    if (stopped) return;
    try {
      const response = await fetch(checkout.dataset.statusUrl, {
        credentials: 'same-origin', cache: 'no-store', headers: { Accept: 'application/json' }
      });
      if (response.status === 401 || response.status === 403 || response.redirected) {
        stopped = true;
        qr.hidden = true;
        cancel.hidden = true;
        message.textContent = 'Phiên đăng nhập đã thay đổi. Vui lòng đăng nhập lại để xem trạng thái.';
      } else {
        if (!response.ok) throw new Error('Status unavailable');
        const payment = await response.json();
        if (payment.status !== 'ACTIVE') {
          stopped = true;
          qr.hidden = true;
          window.location.assign(checkout.dataset.resultUrl);
        }
      }
    } catch (_) {
      message.textContent = 'Chưa cập nhật được trạng thái. Hệ thống sẽ kiểm tra lại; vui lòng không chuyển thêm tiền.';
    }
    if (stopped) window.clearInterval(timer);
    else window.setTimeout(poll, 2000);
  };
  window.setTimeout(poll, 2000);
  window.addEventListener('pagehide', () => { stopped = true; window.clearInterval(timer); });
})();
