# Git handoff P5.7/P5.8 — 2026-11-05

Chủ dự án cho phép tạo hai nhánh, commit/push/merge vào `main` và giữ nhánh. Credential HTTPS và tác giả commit: `ducbinhnguyen07ars`. Reviewer nghiệp vụ được giao: `thanhmit`; thao tác Git không thay bằng chứng human review hoặc live.

## Phạm vi P5.7

- Nhánh `feat/p57-owner`: dashboard Owner, reusable finance/debt read predicates, liên kết điều hướng và `OwnerRevenueTest`.
- Trang chủ dùng public policy Phase8 hiện có; không cần đổi source trang chủ.
- P5.5/P5.6 của `huy69332` chưa được đưa vào nhánh này. P5.9 local và gate live tiếp tục pending ngoài phạm vi Git được chọn.
- Evidence trước tách: [bàn giao P5.7](phase-5-batch-5-handoff.md), full1016 và Chrome152/36PNG trên working tree có cả profile. Đây là kết quả lịch sử, không thay kiểm thử tree Git riêng.
- Kiểm thử tree Git P57: full isolated `clean verify` **1004/1004 JUnit**, 0 failure/error/skip, JAR; Node polling6/6; schema tự sinh đã dọn, provider offline. Evidence local `.scratch/publish-p57-p58/p57-verification/`, JAR SHA256 `4e5057899a531d0e3ac853819b79434434c054e20ffc81681fe93cc3a6976a17`. Kết quả này không kiểm chứng tiền thật.

## Tiếp tục sau Git publication

Workspace gốc giữ các file chưa commit của P5.5/P5.6 và P5.9, cùng sửa tài liệu/phase0 từ trước. Snapshot local P5.9 đạt5/6 checklist, còn gate live. P4.2-A pending là blocker trực tiếp; sau đó vẫn cần review, backup/restore/migration, kích hoạt pilot và giao dịch thật có đối soát theo [runbook](phase-8-pilot-runbook.md). Production lookup tài khoản nhận hiện fail-closed. P5.9/P8.8/P4.2-A chưa được đóng.

Các snapshot có dòng “không commit/push” mô tả phạm vi lần triển khai trước; publication ngày2026-11-05 được người dùng cho phép riêng. Cấu hình bí mật, DB ứng dụng, seed/uploads và workflow/provider/scheduler giữ nguyên.

## Phạm vi P5.8

- Nhánh `feat/p58-audit`, sau P5.7: CarService current actor locks, AdminUserService notification-compatible lock order, CommonExceptionHandler và view405; CarMutationAuthorizationTest và PaymentAccessAuditTest.
- Matrix Git có38 POST user mappings (missing/invalid CSRF); profile matrix2 POST nằm trong source local chưa commit của P5.5/P5.6. Blocked-car-lane read test trên tree Git dùng Owner dashboard thay profile chưa tồn tại; các matrix tiền/recipient/subscription/private image/Admin và webhook giữ nguyên.
- BookingService, PaymentPilot, FinalRentalJourneyTest và cấu hình workflow mới của P5.9 không có trong commit. Production/config/seed/phase0/user-profile files ngoài phạm vi giữ nguyên.
- Audit đầy đủ local đã kiểm chứng source kết hợp1053/1053 JUnit, Chrome256/108PNG và Node6 trước khi tách. Không gán kết quả đó cho tree Git riêng hoặc provider live.
- Kiểm thử tree Git P58: full isolated `clean verify` **1037/1037 JUnit**, 0 failure/error/skip, JAR; Node polling6/6; schema tự sinh đã dọn, provider offline. Evidence local `.scratch/publish-p57-p58/p58-verification/`, JAR SHA256 `a5acc95b81bcae65c032501dbe733dd2b670682e17b9d18a45c71e65c0081a90`. Kết quả này không kiểm chứng tiền thật.
