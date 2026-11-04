# P8.7 — bàn giao đợt5

**Checkpoint P8.7 — 2026-11-04:** hoàn thành **5/5 checklist**: UI Owner xem gói/hạn/quota gồm pending và xe tạm ẩn; gia hạn đúng loại; order/result dẫn từng refund của đúng Owner. Dùng chung Phase4: Admin thiếu tiền giữ WAITING_FUNDS, thông báo Admin và cảnh báo quá hạn; không dùng kênh Owner hoặc báo hoàn thành giả. Full isolated **clean verify 950/950 JUnit, 0 failure/error/skip**, JAR; Chrome **123/123**, 54PNG. **75/86 task**, còn11 (45 task mới xong34). Phụ trách `ducbinhnguyen07ars`, reviewer `thanhmit`; agent review không thay human review. P8.1–P8.7 chưa commit, feature `705f320`/main `6948664` giữ nguyên; phase0/config/seed/uploads và DB ứng dụng giữ nguyên. Không migrate/bật workflow/provider/scheduler/restart8080/chuyển tiền thật/stage/commit/push. Dừng trước **P8.8 riêng**, gate live vẫn chờ.

## Baseline và phạm vi

- Repo `C:\Users\Admin\Documents\hoc\Car`, branch `feat/phase6-rental-gate`.
- HEAD `705f320b51170c322a69ea7c36104313d043f9b7`; main `694866476951551985445fccdf8eb4a0be24ca73`; index giữ nguyên. P8.1–P8.7 chưa commit; `phase-0-init.md` là thay đổi riêng.
- [Spec P8.7](phase-8-owner-subscriptions.md#task-p87--ui-owner-và-các-khoản-hoàn-từ-admin), [SDD2.48 §14.43](../SDD.md#1443-p87--ui-owner-và-hoàn-tiền-từ-admin-2026-11-04).

## Hành vi và kiểm chứng

- Overview current plan/expiry/reserved/pending/temporary hidden; GET chỉ đọc. Catalog chỉ gia hạn cùng loại khi còn hạn, backend recheck; hướng dẫn hạ gói tự chọn xe.
- Order/result liệt kê mọi refund đúng payer/recipient; order APPLIED giữ nguyên. Recipient chỉ yêu cầu khi phát sinh, không bắt liên kết incoming provider. Tái sử dụng Phase4, không viết lại lifecycle.
- Fake Admin104 thiếu tiền giữ WAITING_FUNDS, thông báo Admin và cảnh báo quá hạn dedupe; thiếu Admin channel không fallback Owner. IDOR/current role/CSRF/masking đã kiểm tra.
- Targeted21/21; full clean verify950/950, 0 failure/error/skip, JAR; Chrome123/123, 54PNG ở320/390/1440 và native no-JS forms. No-JS không xác nhận responsive styling vì Tailwind CDN hiện cần JavaScript.

- Evidence `.scratch/phase8/p87/verification-summary.json`, `full-clean-verify.log`, `targeted-green.log`, RED/intermediate logs, `review.md`, source manifests; `browser/browser-evidence.json`, SQL invariants và PNG. Runner `java '@.scratch/phase8/p87/full-suite.args'` dùng random disposable MySQL schema và POM/output riêng, không migrate/seed app DB.
- Provider ký/fake offline, không phải bằng chứng live. Agent review độc lập không thay human reviewer. Giữ config/seed/uploads,14 protected files, DB ứng dụng7bảng và Git baseline.

## Tiếp theo — P8.8 riêng

- 75/86 task, còn11: `ducbinhnguyen07ars`8, `huy69332`2, `thanhmit`1. P8.8 phụ trách `ducbinhnguyen07ars`, reviewer `thanhmit`; review Car/Booking `huy69332` khi liên quan.
- Đọc checklist P8.8: kiểm chứng tích hợp/baseline/SDD và điều kiện demo một Owner. Có thể chuẩn bị evidence local; không tick live receipt/refund, migration review, kênh Owner/Admin hoặc human review khi chưa có bằng chứng.
- Không tự migrate/reset/seed DB ứng dụng, bật flags/workflow/provider/scheduler, restart8080, chuyển tiền thật hoặc stage/commit/push. Chưa có quyền kích hoạt demo. Giữ P8.8 pending nếu thiếu gate thật; không gọi Phase8 hoàn tất từ fixture. Không reset/checkout làm mất dirty code/phase0.
