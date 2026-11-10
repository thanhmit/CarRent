# Thiết kế, hợp đồng và phân công CarRent

Các tài liệu dưới đây còn được dùng để phát triển và vận hành. Nhật ký từng
đợt, biên bản bàn giao lặp lại, chẩn đoán đã xử lý và thiết kế Payment Settings
đã hủy được bỏ khỏi cây hiện tại; phiên bản đã commit vẫn có trong Git.

## Đọc trước

- [Thiết kế tổng thể](../SDD.md).
- [Các quyết định kỹ thuật](../decisions.md).
- [Phân công ba thành viên](team-assignment.md).
- [Hướng dẫn launcher Windows](../local-startup.md).
- [Migration DB-first](../migrations/README.md).

## Thiết kế theo module

| Phần | Tài liệu |
| --- | --- |
| Khởi tạo và schema mới | [Phase 0](phase-0-init.md), [DB bootstrap](db-bootstrap.md) |
| Auth, phân quyền và nền tảng | [Phase 1](phase-1-foundation.md) |
| Catalog, quản lý xe và ảnh | [Phase 2](phase-2-car.md) |
| Booking và notification | [Phase 3](phase-3-booking.md) |
| Thanh toán, refund và đối soát | [Phase 4](phase-4-payment.md), [kiến trúc giao tiếp](phase-4-handoff.md) |
| Admin, hồ sơ và đổi mật khẩu | [Phase 5](phase-5-admin-polish.md) |
| BEFORE/AFTER, hợp đồng và vòng đời thuê | [Phase 6](phase-6-inspection.md) |
| Queue và concurrency | [Phase 7](phase-7-car-queue.md) |
| Gói Owner | [Phase 8](phase-8-owner-subscriptions.md), [pilot runbook](phase-8-pilot-runbook.md) |

## Provider và kiểm chứng vận hành

- [Hợp đồng provider/payment](payment-provider-contract.md).
- [Hợp đồng thu SePay](sepay-collection-contract-2026-11-07.md).
- [Hợp đồng lookup/recipient](p42a-p88-implementation-contract-2026-11-09.md).
- [Nguồn tài liệu lookup và giới hạn endpoint](payos-recipient-lookup-check-2026-11-08.md).
- [Các điều kiện nghiệm thu hoàn tiền còn lại](refund-gates-research-2026-11-09.md).
- [Hướng dẫn operator](../p42a-p88-operator-guide-2026-11-09.md).

Các checkpoint trong đặc tả module là bằng chứng theo thời điểm, không phải
trạng thái server/provider hiện tại. Dùng `start.bat -CheckOnly -NoOpen` và
`.local/SETUP-NEEDED.md` để kiểm tra máy đang chạy. Payment Settings S1/S2/S3
không nằm trong phạm vi triển khai hiện hành.
