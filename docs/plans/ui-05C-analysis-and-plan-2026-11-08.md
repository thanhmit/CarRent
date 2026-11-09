# UI-05C — Booking Admin và smoke cuối 15 trang

Phạm vi: `admin/bookings.html`, `admin/booking-detail.html`; tiếp tục tại root dirty `feat/phase5-profile-password`, bảo toàn baseline manifest `.scratch/ui-05C/baseline.json`. Không Git mutation, sửa backend/config/schema hoặc thêm dependency.

## Thiết kế và contract

- List tái sử dụng workspace Admin active `bookings`; header, notice chỉ đọc, toolbar status/attention, tổng kết quả, card hai cột desktop/một cột mobile, paging và ghi chú hạn. Dùng badge hiện có, không suy diễn cảnh báo từng hàng khi model không có field.
- Detail: breadcrumb/header → summary xe/khách/chủ xe/ngày/tổng tiền snapshot → payment summary/timeline → bằng chứng BEFORE/AFTER chỉ khi đã submit → ba nhóm nghĩa vụ/tiền nhận/hoàn tiền. Nhánh legacy không dựng timeline/tài chính giả. Nhánh không có bằng chứng hoặc giao dịch có giải thích.
- Giữ GET `/admin/bookings`, `/admin/bookings/{id}`, status/attention/page, điều kiện quyền/escaping/masking, link finance theo kind/id và subject. Không POST giao/nhận/resolve/refund. Chỉ form logout của shell có POST.
- Giữ `booking/fragments` và `admin/finance-row :: fields(r)` nguyên file/chữ ký. Không đổi caller Customer/Owner; smoke các caller để kiểm chứng tích hợp cuối.
- Dùng token font tồn tại (`font-title-md text-title-md`, `font-label-md text-label-md`), control/link mới tối thiểu 44px, focus rõ, wrap tên dài, menu no-JS có sẵn.

## Kiểm chứng

1. Focused baseline `AdminBookingTest,AdminPermissionsTest`; fixture schema ngẫu nhiên, port0, fake payout, schedulers tắt. Chụp trước ở bốn viewport.
2. Regression navigation Admin active qua public GET, RED rồi GREEN; giữ suite nghiệp vụ/ảnh/private data hiện có.
3. Browser filter/paging/no-JS/keyboard, legacy/empty/many rows/missing evidence, overdue theo clock, financial/BEFORE/AFTER deep links, sai quyền/stale session/400/404; GET không đổi 20 bảng.
4. Final smoke đủ 15 template, shared callers workspace/overview/refund-links/finance-row/timeline/header và JS polling; chạy focused + các suite kiểm tra integration UI và Node polling.
5. Audit source/resource/HEAD/index/protected config; đóng fixture, truy vấn schema absent và port closed. Bàn giao evidence, human/native zoom/live giữ riêng.

Người dùng đã yêu cầu chạy app/mở trình duyệt. Giữ PID17824 tại 127.0.0.1:8080; cuối đợt đồng bộ đúng hai template vào classpath app (cache local tắt) nếu xác minh được, không restart hoặc thay flags/provider/scheduler.
