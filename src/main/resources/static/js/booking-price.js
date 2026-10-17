(() => {
  const form = document.querySelector('[data-booking-price]');
  if (!form) return;
  const start = form.querySelector('[name="startDate"]');
  const end = form.querySelector('[name="endDate"]');
  const daysOutput = document.getElementById('rental-days');
  const totalOutput = document.getElementById('estimated-total');
  const detailLinks = document.querySelectorAll('[data-car-detail]');
  const price = BigInt(form.dataset.price);
  const update = () => {
    const days = (end.valueAsNumber - start.valueAsNumber) / 86400000;
    end.setCustomValidity('');
    detailLinks.forEach(link => {
      const url = new URL(link.dataset.carDetail, window.location.origin);
      if (Number.isInteger(days) && days > 0 && start.validity.valid && end.validity.valid) {
        url.searchParams.set('startDate', start.value);
        url.searchParams.set('endDate', end.value);
      }
      link.href = url.pathname + url.search;
    });
    if (!Number.isInteger(days) || days <= 0) {
      daysOutput.textContent = '—';
      totalOutput.textContent = 'Chọn ngày thuê hợp lệ';
      if (Number.isFinite(days)) end.setCustomValidity('Ngày trả xe phải sau ngày nhận xe.');
      return;
    }
    const total = price * BigInt(days);
    daysOutput.textContent = `${days} ngày`;
    if (total > 9223372036854775807n) {
      totalOutput.textContent = 'Tổng tiền vượt giới hạn';
      end.setCustomValidity('Vui lòng chọn khoảng ngày ngắn hơn.');
      return;
    }
    totalOutput.textContent = `${total.toLocaleString('vi-VN')} VNĐ`;
  };
  start.addEventListener('input', update);
  end.addEventListener('input', update);
  update();
})();
