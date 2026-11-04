# Phase5 đợt1 — P5.1 + P5.2

**Checkpoint P5.1 + P5.2 — 2026-11-04:** hoàn thành **5/5 + 5/5 checklist** về read model và dashboard/tài chính Admin. Phí gói từ order applied của đúng Admin; tiền thuê, tiền trùng/chờ đối soát và hoàn tiền tách riêng. Tháng theo giờ Việt Nam; filter/phân trang/sort whitelist, kiểm tra Admin ACTIVE hiện tại, tài khoản được che và GET chỉ đọc. Full isolated **clean verify 973/973 JUnit, 0 failure/error/skip**, JAR; Chrome **103/103**, 33 PNG. **77/86 task**, còn **9**: `ducbinhnguyen07ars` 6, `huy69332` 2, `thanhmit` 1. Phụ trách `ducbinhnguyen07ars`, reviewer `thanhmit`; agent review không thay human review. Đây là đợt code độc lập được chuẩn bị theo roadmap khi P8.8 đã xong local; **P8.8 và P4.2-A vẫn pending gate migration/human/provider/live**. Đợt code tiếp theo **P5.3 riêng**. Bảo toàn P8 chưa commit/phase0/config/seed/uploads, DB ứng dụng, HEAD/main/index; không migrate/bật flags/provider/scheduler/restart8080/chuyển tiền thật/stage/commit/push.

## 1. Baseline và phân công

- Branch `feat/phase6-rental-gate`; HEAD `705f320b51170c322a69ea7c36104313d043f9b7`; main `694866476951551985445fccdf8eb4a0be24ca73`. Index giữ nguyên, chưa commit P8/P5.1/P5.2; thay đổi riêng `phase-0-init.md` phải bảo toàn.
- Phụ trách `ducbinhnguyen07ars`; review `thanhmit`. Có agent review không blocker, chưa có human review mới.
- [Checklist](phase-5-admin-polish.md), [SDD2.50 §14.45](../SDD.md#1445-p51--p52--read-model-và-dashboard-tài-chính-admin-2026-11-04), [gate P8.8](phase-8-pilot-runbook.md).

## 2. Kết quả và bằng chứng

- P5.1 **5/5**: AdminService, ví dụ5.000gói +600.000thuê +5.000trùng cho doanh thu gói5.000; không join nhân dòng, scope collector và tháng Việt Nam, cash/allocated/refund phân biệt, legacy loại khỏi doanh thu.
- P5.2 **5/5**: dashboard counts thực, tài chính nghĩa vụ/receipt/refund có lọc/phân trang/sort và detail; nguồn Owner/Admin rõ ràng, tài khoản che, trạng thái pending/processing/succeeded/review đúng policy. Current ACTIVE Admin, role matrix, stale session, filter injection/XSS và missing-schema fallback được kiểm chứng. GET không ghi dữ liệu hoặc gọi provider.
- Focused16/16 (AdminRevenue6, AdminPermissions10), full clean973/973 với0failure/error/skip và JAR. Trước đó có lỗi fixture enum/deadline và assertion nhầm nhãn option; log trung gian được giữ. Browser phát hiện select refund tràn320px, đã sửa co form; full đầu bị dừng chủ động để chạy lại source cuối, không ghi thành pass. Kết quả nghiệm thu là full clean/browser mới.
- Browser Chrome103/103, 33PNG ở320/390/1440; native filter/pager/detail và no-JS chức năng. SQL invariants và source manifest được đối chiếu, schema/listener tạm đã dọn. No-JS không xác nhận styling responsive.

- Evidence `.scratch/phase5/p51-p52/verification-summary.json`, `full-clean-verify.log`, `targeted-green.log`, `review.md`, `reviewed-source-hashes.json`, `verified-source-hashes.json`, `browser/`. Runner `java '@.scratch/phase5/p51-p52/full-suite.args'` dùng Maven Wrapper/POM/output/datasource tạm, không migrate DB ứng dụng.
- Browser103UI assertions pass; raw Java snapshot assertion fail do users.id1.updated_at đổi khi fixture test stale Admin.15bảng khác khớp tuyệt đối, role/status đã khôi phục; audit artifact normalize chỉ timestamp này cho16bảng khớp. File invariant cũ stale đã thay thế, giữ server.log lỗi; provider calls0 và cleanup được xác minh riêng. Không trình bày raw browser server exit là thành công.
- Đối chiếu345baseline files,14protected files và7bảng DB ứng dụng; giữ P8dirty/phase0/seed/config/uploads,HEAD/main/index. Không đổi schema/dependency hoặc bật flags/provider/scheduler/restart8080/chuyển tiền thật/stage/commit/push.

## 3. Tiếp theo

- **P5.3 riêng — Admin quản lý user**, phụ trách `ducbinhnguyen07ars`, reviewer `thanhmit`: filter q/role/status, detail, status change kiểm tra Admin hiện tại; chống self-change/role tamper/CSRF và stale principal. Không tự ban từ nợ hoặc hoàn quá hạn. Chưa triển khai task này.
- P8.8 giữ2/5 checklist local,3gate pending; P4.2-A vẫn pending. Không dùng kết quả Admin read UI để suy ra tiền thật/live readiness.
- Tổng77/86,còn9: `ducbinhnguyen07ars`6 (P8.8/P5.3/P5.4/P5.7–P5.9), `huy69332`2 (P5.5/P5.6), `thanhmit`1 (P4.2-A). P5.9 còn gate thật, không đóng toàn hệ thống từ fixture.
