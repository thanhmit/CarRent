# Phase 8 — bàn giao đợt 1 (P8.1)

**Ngày:** 2026-11-02. **Phụ trách:** `ducbinhnguyen07ars`. **Reviewer được giao:** `thanhmit`. Agent review không có blocker; human review chưa có mới.

## 1. Phạm vi đã hoàn thành

P8.1 **4/4 checklist**, dừng trước P8.2. Tiến độ **69/86 task**, còn17; 45task mới xong28/còn17. Trong phần còn lại: `ducbinhnguyen07ars`14, `huy69332`2, `thanhmit`1.

| Checklist | Kết quả và bằng chứng |
|---|---|
| Catalog | `SubscriptionPlan` enum bất biến: SMALL4/5000, STANDARD8/10000, BUSINESS16/15000, 30ngày; không Admin pricing CRUD/giá client. `SubscriptionModelTest` kiểm tra toàn bộ catalog. |
| Schema/entitlement | Hai bảng mới, owner PK, obligation UNIQUE nullable, last-applied-order UNIQUE, FK RESTRICT, enum trạng thái và CHECK; bootstrap/migration parity trong `SubscriptionMigrationTest`. |
| Snapshot/liên kết | Java `planCode/quotaSnapshot/priceSnapshot/durationDays`; fixture order-first liên kết nghĩa vụ thật `SUBSCRIPTION:id` / SUBSCRIPTION_ORDER / SUBSCRIPTION, payer Owner/collector Admin; không booking giả. JPA round-trip UTC microsecond dưới timezone khác. |
| Bảo toàn dữ liệu | Migration không cập nhật hàng cũ/cấp gói; fixture đối chiếu toàn bộ legacy/core rows và core DDL, bootstrap rerun giữ seed; entitlement riêng chỉ do test tạo. |

`owner_id` là ID của entity entitlement; association Owner chỉ đọc. Cách này tránh lỗi Hibernate merge identifier khi dùng OneToOne/MapsId với repository save của ID đã gán. Snapshot dương, không khóa cứng cặp giá/quota của catalog để không làm mất lịch sử khi bảng giá đổi về sau.

## 2. Kiểm chứng

- Schema **21/21**, catalog/JPA **2/2**, RED/GREEN có log; full isolated **clean verify 879/879**, không failure/error/skip, JAR đóng gói; resources khớp byte với source.
- Review toàn bộ P8.1 và delta fixture/mapping: không blocker. Hai test JPA legacy được bổ sung migration subscription trước Hibernate validate; production config không đổi.
- Evidence `.scratch/phase8/p81/verification-summary.json`, `full-clean-verify.log`, `green-model.log`, `schema/green.log`, `red-model.log`, `red-jpa-*.log`, `baseline.json`, `protected-before.json`, `source-before.txt`, `source-after.txt`.
- Runner `java '@.scratch/phase8/p81/full-suite.args'` dùng scratch POM tương đương project, `clean verify`, datasource là schema MySQL tạm và tự dọn. Không chạy Maven vào DB ứng dụng.
- Browser không chạy lại: P8.1 không có route/template/UI mới. P6.12/P6.13 có browser109/109 từ đợt trước; không ghi thành kết quả browser P8 mới.

## 3. Baseline và giới hạn vận hành

Nhánh hiện tại `feat/phase6-rental-gate`, HEAD `82a3852b12883a1a3b1c0919e0ccd9e72f701e14`; index rỗng, chưa commit/push. Giữ toàn bộ code/file P6.12/P6.13 đang dirty; chỉ SDD/roadmap/team là tài liệu dùng chung được cập nhật thêm P8. File phase-0 riêng cùng config/uploads/seed giữ nguyên. Fingerprint DB ứng dụng trước/sau giống nhau, vẫn7bảng legacy; bootstrap20bảng chỉ thuộc fixture.

Chưa migrate/reset/seed DB ứng dụng, bật workflow/provider/scheduler, restart8080, chuyển tiền thật hoặc stage/commit/push. Chưa cấp gói, handler receipt, checkout hoặc quota gate. Schema FK không kiểm tra thay role Owner ACTIVE/purpose/payer/collector và applied entitlement cùng Owner: service P8.2/P8.3 phải xác minh trong transaction.

Migration một lần và các preflight/backup/partial-failure tại [migration README](../migrations/README.md). Có file migration không có nghĩa đã được triển khai lên DB ứng dụng. Gate provider/live P4.2-A vẫn chưa đạt.

## 4. Đợt tiếp theo — P8.2 + P8.3

Cùng phụ trách `ducbinhnguyen07ars`, reviewer `thanhmit`. Bắt đầu từ working tree hiện hành; không checkout/reset/stash làm mất P6/P8 chưa commit và không đụng phase-0 riêng.

1. Chốt test tạo/hủy order trước: Owner ACTIVE, khóa Owner rồi entitlement/order, một pending hợp lệ mỗi Owner, chỉ mua cùng loại khi còn hạn; order + obligation + intent atomic, QR15phút nhận Admin, không tin giá/collector/ownerId client.
2. Sau checkpoint P8.2, triển khai handler SUBSCRIPTION_ORDER và receipt apply đúng một lần; kiểm tra creditedAt, trùng/late/incompatible/amount mismatch, refund nguồn Admin; rollback notification không để entitlement/appliedAt dở dang.
3. Tuân thủ Owner → entitlement → order; khi cần Car để hạ gói, lock Car tăng ID trước obligation/intent/receipt. Không giả carId để đi Car queue, không gọi provider trong transaction.
4. UI tối thiểu để kiểm chứng order đi cùng đợt; test/browser source cuối, cập nhật SDD và checklist riêng hai task. Dừng trước P8.4, không tự enable live hoặc migrate DB ứng dụng.

Spec đầy đủ: [Phase8](phase-8-owner-subscriptions.md), [roadmap](README.md#cac-dot-con-lai-da-duyet), [SDD §7.12](../SDD.md#712-schema-gói-owner--p81).
