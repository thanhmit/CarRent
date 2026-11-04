# P8.6 — bàn giao đợt4

## 1. Checkpoint và baseline

**Checkpoint P8.6 — 2026-11-04:** hoàn thành **5/5 checklist**: gia hạn giữ ý định đăng; hạ gói sau expiry ẩn toàn bộ xe cũ atomic với receipt/entitlement, Owner tự chọn xe theo quota. Full isolated **clean verify 943/943 JUnit, 0 failure/error/skip**, JAR; Chrome **87/87**, 42PNG ở320/390/1440 và no-JS forms. **74/86 task**, còn12 (45 task mới xong33). Phụ trách `ducbinhnguyen07ars`, reviewer được giao `thanhmit`, `huy69332` review Car/Booking; agent review không thay human review. P8.1–P8.6 chưa commit; feature `705f320`, main `6948664` giữ nguyên. Phase0/config/seed/uploads và DB ứng dụng7bảng giữ nguyên. Không migrate/bật workflow/provider/scheduler/restart8080/chuyển tiền thật/stage/commit/push. Dừng trước P8.7; tiếp theo **P8.7 riêng**, gate live vẫn chờ.

- Repo `C:\Users\Admin\Documents\hoc\Car`; branch `feat/phase6-rental-gate`.
- HEAD `705f320b51170c322a69ea7c36104313d043f9b7`; main `694866476951551985445fccdf8eb4a0be24ca73`; index không đổi. P8.1–P8.6 chưa commit, phase0 là thay đổi riêng phải bảo toàn.
- Spec [Phase8](phase-8-owner-subscriptions.md), [SDD §14.42](../SDD.md#1442-p86--gia-hạn-và-hạ-gói-sau-expiry-2026-11-04). SDD2.47.

## 2. Hành vi đã hoàn thành

- Same-plan renewal phục hồi đúng ACTIVE/APPROVED; INACTIVE/rejected giữ ẩn.
- Expired downgrade chỉ reset intent khi receipt thành công lần đầu. Financial allocation, entitlement, toàn fleet INACTIVE và notification atomic. Ordercreation/QR/refund/replay không thay listing.
- Khóa Owner→entitlement→order→Car ID tăng→financial; khóa fleet khi incoming quota thấp hơn kể cả còn hạn để phủ expiry crossing. Native update listing-only; current reread refresh fleet đã khóa. Entitlement chưa khóa detach/find WRITE để không dùng managed snapshot cũ.
- Owner tự bật/gửi lại theo quota; APPROVED giữ duyệt, rejected gửi lại PENDING.16→0→4 và chặn5; giữ16xe/ảnh/booking. Rental CONFIRMED vẫn hoàn thành nhận/trả/balance với evidence/debt nguyên vẹn dù xeẩn.

## 3. Kiểm chứng và giới hạn

- Targeted54/54; full isolated clean verify943/943,0failure/error/skip,JAR. Chrome87/87, 42PNG320/390/1440 và native no-JS forms. HTTP/session/CSRF/domain thật, Admin SePay receipt ký offline, không mock business endpoints. No-JS không xác nhận responsive styling vì Tailwind CDN hiện cần JavaScript.

- Evidence `.scratch/phase8/p86`: `verification-summary.json`, `verified-source-hashes.json`, `full-clean-verify.log`, RED/intermediate/targeted logs; `browser/browser-evidence.json`, `browser-source-manifest.json`, `browser-sql-invariants.txt`, PNG. App DB7bảng/source fingerprints và14protected files so baseline giữ nguyên. Disposable schemas đã xóa.
- Lệnh runner `java '@.scratch/phase8/p86/full-suite.args'`: tạo MySQL schema ngẫu nhiên, bootstrap seed ở đó, truyền datasource cho Maven Wrapper với POM/output cách ly và `-Dmaven.compiler.useIncrementalCompilation=false -Dtest=*Test clean verify`, rồi xóa schema. Không migrate/reset/seed app DB hoặc dùng fixture làm bằng chứng provider live.
- Agent review độc lập và primary review/verification; chưa có human contributor review. Cảnh báo SQL do test inject notification-failure là dự kiến, không phải suite failure.

## 4. Đợt tiếp theo — P8.7 riêng

- Phụ trách `ducbinhnguyen07ars`; reviewer `thanhmit`, `huy69332` review Car/Booking khi liên quan.74/86,còn12: `ducbinhnguyen07ars`9, `huy69332`2, `thanhmit`1.
- Hoàn thiện UI catalog quota/giá30ngày/không hoàn phí sau activation, chỉ gia hạn cùng loại khi còn hạn. Owner xem expiry/reserved gồm pending/xe tạmẩn/nút mua-gia hạn; hạ gói hướng dẫn chọn xe tại quản lýxe.
- Checkout/refund page đúng Owner, receiptduplicate/late dẫn đúng khoản hoàn. Admin thiếu tiền WAITING_FUNDS và nhắc Admin, không dùng tiền/kênh Owner; không giả SUCCEEDED. IDOR/CSRF/masking credentials và bank list.
- Reuse policy/payment/refund đã có, không viết lại lifecycle. Dừng trước P8.8; gate live P4.2-A/P8.8 vẫn chờ. Không migrate/reset/seed DB ứng dụng, bật workflow/provider/scheduler, restart8080, chuyển tiền thật hoặc stage/commit/push. Không reset/checkout làm mất P8 dirty/phase0.
