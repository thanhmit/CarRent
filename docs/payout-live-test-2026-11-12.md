# Bật chi thật để người dùng tự test — 12/11/2026

Theo yêu cầu trực tiếp của người dùng, cấu hình chi PayOS đã được bật và ứng dụng đã restart. Trạng thái này thay thế ghi nhận chi OFF trong báo cáo kiểm thử cục bộ trước đó.

## Trạng thái đã xác minh

- `carrent.payment.payout.enabled=true`.
- `carrent.payment.maintenance.enabled=true` — worker được quét mỗi 30 giây.
- `carrent.payment.providers.payos.owner-payout.enabled=true`.
- `carrent.payment.providers.payos.admin-payout.enabled=true`.
- Hai mapping PAYOS/PAYOUT trong DB đã bật: Owner user ID 2 (`owner-payout`) và Admin user ID 1 (`admin-payout`). Giữ nguyên chủ thể, reference và bộ khóa đã cấu hình.
- Lookup vẫn OFF; luồng hiện tại không cần VietQR/CAS.
- Không có refund/payout attempt tồn trước khi bật; sau khởi động kiểm tra vẫn là 0/0. Không tạo giao dịch hoàn hoặc lệnh chi để test thay người dùng.
- Process mới PID 35404, cổng 8080, dùng cùng JAR đã kiểm thử. Command line xác nhận worker, maintenance và hai kênh PayOS đều ON.
- Chỉ ba dòng cờ trong `application-local.properties` thay đổi; có bản sao riêng trước thay đổi. Không sửa khóa API.

Launcher trước đây ép worker OFF; đã sửa để đọc cờ được cấu hình rõ ràng, mặc định OFF và giá trị không hợp lệ vẫn OFF. Kiểm thử trước sửa thất bại đúng ở trường hợp bật rõ ràng; sau sửa **58/58 launcher checks** qua. Không thay đổi mã Java/JAR trong lần kích hoạt này.

## Cách tự test

1. Mở `http://localhost:8080`. Phạm vi pilot hiện tại dùng Owner user ID 2 cho tiền thuê và Admin user ID 1 cho phí gói.
2. Thực hiện thanh toán thực tế và thao tác tạo khoản hoàn hợp lệ trên giao diện. Hệ thống tự tính số tiền và nguồn chi theo giao dịch đã thu.
3. Người nhận mở thông báo khoản hoàn, chọn ngân hàng, nhập STK của mình và xác nhận.
4. Theo dõi READY → PROCESSING → SUCCEEDED. Worker quét mỗi 30 giây; thời gian ngân hàng/PayOS xử lý có thể lâu hơn. Chỉ SUCCEEDED đã đối soát mới là xác nhận hoàn tất.
5. Nếu kết quả chưa rõ, dùng mã khoản hoàn để kiểm tra/đối soát. UNKNOWN không được tự gửi lại hoặc sửa DB thành công.

## PayOS vẫn chặn truy cập

Kiểm tra GET-only ngay sau kích hoạt: `/v1/payouts` và `/v1/payouts-account/balance` đều trả HTTP 403. Bật cờ trong CarRent không cấp quyền trên PayOS. Cần xử lý quyền/IP trên đúng kênh chuyển tiền và bảo đảm nguồn tiền, credit/phí/điều kiện duyệt lệnh để giao dịch thật được chấp nhận. Chưa có bằng chứng chuyển tiền thật thành công.

Nếu giao dịch test gặp lỗi, cung cấp mã khoản hoàn và trạng thái hiển thị; không cần gửi STK hoặc khóa API qua chat. Nhập nhầm một STK hợp lệ có thể hoàn tiền nhầm người vì luồng này không đối chiếu tên.

Bằng chứng và bản sao cấu hình riêng: `.scratch/payout-live-enable-20261112/`. Không commit/push trong lần kích hoạt này.
