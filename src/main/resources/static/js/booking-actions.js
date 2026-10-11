(() => {
  document.querySelectorAll('form[data-confirm]').forEach(form => {
    form.addEventListener('submit', event => {
      if (!window.confirm(form.dataset.confirm)) event.preventDefault();
    });
  });
  const dialog = document.getElementById('reject-dialog');
  const opener = document.getElementById('open-reject');
  if (!dialog || !opener) return;
  // With JavaScript disabled, the open dialog still exposes the ordinary rejection form.
  dialog.close();
  opener.hidden = false;
  opener.addEventListener('click', () => dialog.showModal());
  dialog.querySelector('[data-close-dialog]').addEventListener('click', () => dialog.close());
})();
