# Phase 8 — bàn giao đợt 2 (P8.2 + P8.3)

**Ngày:** 2026-11-03. **Phụ trách:** `ducbinhnguyen07ars`. **Reviewer được giao:** `thanhmit`. Agent review độc lập và primary kiểm tra source; chưa có human review mới.

## 1. Kết quả

**Checkpoint P8.2 + P8.3 — 2026-11-03:** hoàn thành **5/5 + 6/6 checklist**: order/QR Admin 15 phút, receipt cấp/gia hạn 30 ngày đúng một lần, hoàn trùng/muộn/hủy/không tương thích từ Admin. Full isolated **clean verify 914/914 JUnit, 0 failure/error/skip**, JAR; Chromium **47/47**, 320/390/1440 và no-JS. **71/86 task**, còn15 (45 task mới xong30). Phụ trách `ducbinhnguyen07ars`, reviewer được giao `thanhmit`; agent review không thay human review. P8.1–P8.3 chưa commit; P6.12/P6.13 đã tích hợp main `6948664` (feature `705f320`). Phase0/config/seed/uploads và DB ứng dụng7bảng giữ nguyên. Không migrate/bật workflow/provider/scheduler/restart8080/chuyển tiền thật/stage/commit/push. Dừng trước P8.4; tiếp theo **P8.4 + P8.5**, gate live vẫn chờ. [Bàn giao](phase-8-owner-subscriptions.md).

| Task | Checklist và kiểm chứng |
|---|---|
| P8.2 — 5/5 | Actor OWNER/ACTIVE hiện tại, IDOR; khóa Owner và reuse order/QR cả khi snapshot cũ có trước commit; snapshot server/thu Admin; atomic order/obligation/intent15phút; cancel/expiry giữ lịch sử và gói cũ; HTTP bỏ qua giá/collector/ownerId client, carId=null. SubscriptionOrderTest6 + SubscriptionUiTest4, browser tạo/reuse/refresh/cancel/CSRF/no-JS. |
| P8.3 — 6/6 | Cấp/gia hạn đúng một lần; Owner→entitlement→order→Car nếu cần→financial; nhận Admin/code/amount/creditedAt; trùng event và chuyển thật lần hai; late/cancel/incompatible hoàn100% Admin, lệch tiền REVIEW_REQUIRED; validUntil==now/creditedAt==expiresAt; hai order và webhook cạnh tranh; lỗi notification rollback và retry. SubscriptionPaymentTest25 và browser signed inbox/SQL. |

## 2. Bằng chứng và lệnh

- Full isolated clean verify **914/914**, không failure/error/skip; JAR và resources khớp source. Chromium **47/47**, 24PNG (8 trạng thái × 320/390/1440), no-JS; business endpoints thật trong app fixture, chỉ external assets thay bằng bản local. SQL cuối4orders/2entitlements/5receipts/3refunds; tất cả hoàn nguồn Admin WAITING_RECIPIENT.

- Runner: `java '@.scratch/phase8/p82-p83/full-suite.args'`; scratch POM tương đương project, datasource schema MySQL tạm, tự dọn. Không chạy Maven vào DB ứng dụng.
- Browser: `javac '@.scratch/phase8/p82-p83/browser/browser-compile.args'`, `java '@.scratch/phase8/p82-p83/browser/browser-run.args'`, `node .scratch/phase8/p82-p83/browser/browser-check.cjs`; fixture dùng snapshot build riêng, random port, tự dọn khi stop. SePay signed receipt và payout là offline, không phải tiền/provider thật.
- `.scratch/phase8/p82-p83/verification-summary.json`, `verified-source-hashes.json`, `full-clean-verify.log`, `target-green.log`, `browser/browser-evidence.json`, `browser/browser-source-manifest.json`, `browser/browser-sql-invariants.txt`, PNG và `ledger.md`.
- RED/intermediate failures được giữ trong red-order.log, tests/red-missing-service.log và target-*-failed.log: khóa Hibernate FORCE_INCREMENT, cancelled provenance, refresh intent/obligation và entitlement RR snapshot. Ca tamper bị DB constraint chặn được kiểm chứng riêng với ca nghiệp vụ REVIEW_REQUIRED. Không diễn giải các lần failed đó là clean suite; full cuối chạy lại từ đầu.

## 3. Transaction, phạm vi và baseline

Service/controller/handler chỉ đăng ký khi `carrent.subscription.enabled=true`; mặc định tắt, không thêm cấu hình bật hoặc scheduler. Apply nhận lock aggregate từ payment core; entitlement đọc bằng identity map đã load/lock, tránh query snapshot cũ. Khi hạ quota sau hết hạn, createOrder khóa Car trước cả end renewal cũ; lockSubject dùng cùng helper. Downgrade mới chỉ chuẩn bị khóa Car trước khóa tiền; chưa ẩn/quota/visibility. Không gọi provider trong transaction.

HEAD `705f320b51170c322a69ea7c36104313d043f9b7`, nhánh `feat/phase6-rental-gate`; main `694866476951551985445fccdf8eb4a0be24ca73`. P6.12/P6.13 đã commit/push/merge theo yêu cầu trước; P8.1–P8.3 còn dirty, index rỗng. Handoff đợt1 giữ snapshot lịch sử lúc đó; baseline hiện hành dùng mục này. Toàn bộ P8.1 giữ nguyên byte trừ repository pending-lock và các tài liệu dùng chung được mở rộng có chủ đích. Phase0 riêng, config/uploads/seed/migration/bootstrap không đổi trong đợt này; DB ứng dụng7bảng có fingerprint trước/sau trùng.

Không migrate/reset/seed DB ứng dụng, bật workflow/provider/scheduler, restart8080, chuyển tiền thật hoặc stage/commit/push. Gate P4.2-A/live và human review vẫn chờ; không đóng toàn Phase8.

## 4. Chat tiếp theo — P8.4 + P8.5

Tiến độ71/86, còn15; `ducbinhnguyen07ars` còn12, `huy69332` còn2, `thanhmit` còn1. Đợt3 vẫn của `ducbinhnguyen07ars`, reviewer `thanhmit`; `huy69332` review tích hợp Car/Booking.

1. Đọc [plan](phase-8-owner-subscriptions.md), [SDD §14.40](../SDD.md#1440-p82--p83--order-qr-và-áp-dụng-gói-owner-2026-11-03) và ledger; bắt đầu từ working tree hiện hành, bảo toàn phase0/P8 chưa commit.
2. P8.4: policy active entitlement và reservedCount theo trạng thái ý định đăng, khóa Owner trước create/resubmit/reactivate, hai carId tranh suất cuối; bảo toàn sửa xe/booking cũ.
3. P8.5: policy trực tiếp theo thời gian cho home/search/detail/POST booking, xử lý hết hạn giữa GET/POST; không phụ thuộc job và không làm mất rental đang diễn ra.
4. Checklist/test riêng từng task, full clean verify/browser/SDD source cuối. Dừng trước P8.6; không tự migrate/bật live/commit/push. P8.6 mới thực hiện ẩn xe khi hạ quota, P8.7 hoàn thiện điều hướng/UI, P8.8 giữ gate live riêng.
