# Phase5 đợt2 — P5.3 quản lý tài khoản

**Checkpoint P5.3 — 2026-11-04:** hoàn thành **4/4 checklist** Admin quản lý tài khoản: q/role/status/page20, hồ sơ và số liệu scoped target, POST status ACTIVE/INACTIVE/BANNED dưới khóa và kiểm tra Admin hiện tại; chống self-change/role-email tamper/CSRF, banned login và stale request trong queue. Full isolated **clean verify 986/986 JUnit, 0 failure/error/skip**, JAR; Chrome **107/107**, 33 PNG. **78/86 task**, còn **8** (`ducbinhnguyen07ars`5, `huy69332`2, `thanhmit`1). Phụ trách `ducbinhnguyen07ars`, reviewer `thanhmit`; agent review không thay human review. P8.8/P4.2-A gate thật vẫn pending. Tiếp theo **P5.4 riêng**. Bảo toàn dirty P8/P5,phase0/config/seed/uploads,DB ứng dụng,HEAD/main/index; không migrate/bật flags/provider/scheduler/restart8080/chuyển tiền thật/stage/commit/push.

## Baseline và quyền thao tác

- Branch `feat/phase6-rental-gate`; HEAD `705f320b51170c322a69ea7c36104313d043f9b7`; main `694866476951551985445fccdf8eb4a0be24ca73`; index giữ nguyên. P8/P5dirty chưa commit, phase0 riêng phải giữ.
- Phụ trách `ducbinhnguyen07ars`, reviewer `thanhmit`; agent review không thay human review. [Checklist](phase-5-admin-polish.md), [SDD2.51 §14.46](../SDD.md#1446-p53--admin-quản-lý-tài-khoản-2026-11-04).

## Hoàn tất P5.3 — 4/4

- List q/role/status,page20/idDESC; search literal không coi %/_ là wildcard, pager giữ filter. Detail profile narrow DTO không password; counts thuộc đúng target, legacy missing payment schema không cản quản lý user.
- POSTstatus ACTIVE/INACTIVE/BANNED kiểm tra current ACTIVEAdmin dưới khóaID tăng dần, cấm self và role/email tamper, CSRF. Reciprocal bans chỉ một bên commit. Browser bắt nhãn option trống, sửa bằng map entries và assert nonblank; test thiếu status tái hiện500, sửa optional binding để service trả400. Test stale managed actor bắt bug find+refresh; helper đổi sang detach/find locking read và test cuối pass.
- Banned user không login mới; HTTP queuedOwner capture principal trước ban bị403 khi worker chạy sau admin statusPOST, xe giữACTIVE/không success flash. Không tự ban từ nợ hoặc refund, không thay role/email/password/booking/money khi đổi status.
- Focused13/13 (service6+HTTP7); full clean986/986,0failure/error/skip,JAR. Giữ logs RED stale actor/missing status; lượt full trước fix input đã dừng có chủ đích, không tính là full pass. Browser107/107, 33PNG, responsive320/390/1440 và native no-JS chức năng. Browser SQL phases tách GETs và intentionalPOSTs: GET/denied giữ nguyên mọi field, POST chỉ đổi user target status/updated_at; final snapshot còn admin1.updated_at do fixture đổi trạng thái/quyền rồi restore. Provider calls0; schema/listener tạm đã dọn. Native computer-use pipe không khả dụng; dùng Chrome Playwright và visual PNG, no-JS không claim responsiveCSS.
- Evidence `.scratch/phase5/p53/verification-summary.json`, `full-clean-verify.log`, `targeted-green.log`, `review.md`, manifests và `browser/`. Full runner `java '@.scratch/phase5/p53/full-suite.args'` dùng schema/POM/build tạm, không DB ứng dụng.
- Đã đối chiếu 357 file baseline: chỉ sửa 6 tài liệu được phép và liên kết trên dashboard; các thay đổi có sẵn còn lại giữ nguyên. Giữ nguyên 14 file bảo vệ, 7 bảng DB ứng dụng và HEAD/main/index; không migrate/activation/restart8080/provider/money/Git writes.

## Đợt tiếp theo

**P5.4 riêng — Admin booking, biên bản và khoản còn nợ**: phụ trách `ducbinhnguyen07ars`, reviewer `thanhmit`; list/detail tiến độ30/70/receipts/refunds/BEFORE/AFTER private, nợ quá hạn và quyền ảnh, legacy có nhãn, không thêm Admin ký/giao nhận thay Owner. Chưa triển khai task này.

78/86,còn8: duc5(P8.8/P5.4/P5.7–P5.9),huy2(P5.5/P5.6),thanh1(P4.2-A). P8.8 vẫn2/5local3gatepending; provider/live/migration/human approval chưa đạt, không đóng Phase8/toàn hệ thống từ fixture.
