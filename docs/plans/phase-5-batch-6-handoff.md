# Phase5 đợt6 — P5.8 và P5.9 local

**Checkpoint P5.8 + P5.9 local — 2026-11-04:** P5.8 hoàn thành **5/5**: ma trận quyền/CSRF/HMAC, biên thời gian, GET chỉ đọc, queue/quota/restart và tài liệu hiện hành. Sửa stale Owner/Admin khi mutation xe, vòng khóa notification/Admin status và HTTP405; regression RED/GREEN được giữ. P5.9 hoàn thành **5/6 checklist local**, còn **gate live**: hành trình HTTP mua gói → tạo/duyệt xe → booking mới → BEFORE →30%→Owner hợp đồng/giao→AFTER bất đồng→nhận lại→70%→COMPLETED; lối vào VIETQR_V1 mặc định tắt, giới hạn pilot và quyền Customer hiện tại. Full isolated **clean verify 1053/1053 JUnit, 0 failure/error/skip**, JAR; Node polling **6/6**; Chrome **256/256**, 108PNG tại1440/390/320. **83/86 task**, còn3 (`ducbinhnguyen07ars`2: P5.9/P8.8; `thanhmit`1: P4.2-A). **P5.9/P8.8/P4.2-A chưa đóng**, chưa có bằng chứng giao dịch/provider/human/migration live. Bảo toàn DB ứng dụng/config/seed/uploads/phase0/dirty prerequisites/refs/index; không kích hoạt hoặc Git publication. Phụ trách `ducbinhnguyen07ars`, reviewer `thanhmit`; agent review không thay human review.

## Đã triển khai

- P5.8: [ma trận quyền/CSRF/HMAC, biên thời gian, queue/recovery và audit tài liệu](phase-5-p58-audit.md). Sửa Owner/Admin stale authorization, Admin status/notification lock cycle và unsupported method405; có regression RED trước sửa.
- P5.9: [hành trình HTTP liền mạch, nhánh lỗi, browser và live gates](phase-5-p59-verification.md). Sửa public booking admission mặc địnhtắt cho đúng pilot; kiểm tra lại Customer bị revoke khi chờ khóa. Giữ AFTER bất đồng sau COMPLETED, đúng Admin/Owner collector.
- Không thêm dependency, không đổi schema/config/seed ứng dụng. Giữ branch `feat/phase5-profile-password`, dirty P5.5–P5.7/P8, phase0, refs/index.

## Bằng chứng source cuối

- `.scratch/phase5/p58/verification-summary.json`: JUnit1053, Node6, Chrome256, PNG108, source303files, fingerprint/protected/cleanup.
- `full-clean-verify.log`, `full-run-result.json`, `build/surefire-reports/`, `verified-source-hashes.json`; runner dùng Maven Wrapper `clean verify` với POM/output/schema dùng riêng, incremental compilationfalse, toàn bộ source/tests thật; không chạy vào datasource ứng dụng.
- JAR `.scratch/phase5/p58/build/carrent-0.0.1-SNAPSHOT.jar`, SHA256 `79b8104f94fa4019938a1902d561df2dcfb97109c8f554e93eae6d6ac53cfaa7`.
- `browser/lifecycle/` và `browser/subscription/`: Chrome/native HTTP/CSRF, snapshots/PNG/result logs,3viewports. Lifecycle browser bắt đầu từ booking fixture; new-public-booking journey là `FinalRentalJourneyTest`. Bitmap QR/CSS/fonts được phục vụ local; không chứng minh provider live hoặc QR quét được.
- `task-report.md`, `p59-journey-report.md` ghi RED/compile/fixture failures riêng; không coi lượt đỏ là suite sạch. `final-standards-review.md` và `final-spec-review.md`; review agent không thay reviewer con người.
- Lượt full đầu heap384MiB không đạt:1035executions,0assertionfailure/2booterrors do OutOfMemoryError ở nhóm subscription cuối. Giữ toàn bộ tại `attempts/full-heap384/`; lần cuối chạy lại toàn bộ clean verify với heap768MiB dùng riêng, source303files không đổi, không lọc test. Đây không phải kết quả tất cả các lượt đều sạch và không đổi JVM/config ứng dụng. Xem `full-retry.md`, `pom-equivalence.json`.
- `preservation.json`, `protected-before.json`, `source-before.txt`/`source-after.txt`, `schemas-before.json`/`schemas-after.json`:30protected files, DB ứng dụng7bảng,9schema có trước, refs/index giữ nguyên. Schema do từng fixture sinh đã dọn, không xóa schema không thuộc đợt.

## Còn để đạt P5.9 đủ100%

**Điểm bắt đầu cho phiên sau:** đọc [trạng thái phụ thuộc và trình tự tiếp tục P5.9](phase-5-p59-verification.md#tiếp-tục-p59-từ-trạng-thái-hiện-tại). P4.2-A pending chặn gate live P5.9; còn phải đạt các gate review/migration/kích hoạt P8.8 và nghiệm thu thật của P5.9. Production lookup tài khoản nhận vẫn fail-closed, nên không xem phần còn lại chỉ là điền credential hoặc bật cờ.

P5.9 **5/6**, còn gate live: một Owner/Admin có kênh thu/nguồn chi được duyệt, callback public, secret server cấu hình đúng, capability lookup/refund/funding được xác nhận và người dùng chủ động giao dịch/hoàn tiền thật có đối soát. [Danh sách evidence cần có](phase-5-p59-verification.md#gate-còn-cần-bằng-chứng-thật). Migration/human review/kích hoạt theo [runbook](phase-8-pilot-runbook.md).

**83/86**, còn P5.9/P8.8/P4.2-A. Không tick toàn bộ Phase5 hoặc toàn hệ thống từ kết quả local. Không migrate/bật workflow/provider/scheduler/restart8080/chuyển tiền thật hoặc stage/commit/push/merge trong đợt này.
