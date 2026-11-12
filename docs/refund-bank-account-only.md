# Hoàn tiền PayOS bằng ngân hàng và số tài khoản

Từ thay đổi ngày 12/11/2026, người nhận chọn ngân hàng, nhập STK và xác nhận tại `/payment/refunds/{id}`. Không nhập tên chủ tài khoản, không có bước preview/lookup và không cần đăng ký VietQR/CAS. CarRent không xác minh STK có đứng tên người nhận hay không.

Ngân hàng được chọn từ danh mục định tuyến công khai đóng gói trong `src/main/resources/payment/refund-banks.json`: 48 ngân hàng có `transferSupported=1` tại `https://api.vietqr.io/v2/banks`, lấy ngày 12/11/2026. Danh mục này không tra cứu STK và không gọi mạng khi người nhận mở/gửi form. Cập nhật file này khi nhà cung cấp thay đổi ngân hàng hỗ trợ.

## Điều kiện được giữ

- Người đang hoạt động chỉ xác nhận khoản hoàn thuộc chính mình.
- Số tiền, nguồn chi và người nhận do server xác định từ giao dịch đã thu; không lấy từ form.
- Bấm xác nhận chỉ đưa khoản hoàn đủ điều kiện sang READY. Worker PayOS xử lý khi được kích hoạt và nguồn chi sẵn sàng.
- Tài khoản nhận bị khóa khi có lease/lần chi. Không đặt lại hạn một giờ khi người nhận sửa thông tin trước khi chi.
- Chỉ ghi SUCCEEDED khi kết quả PayOS đã xác thực và khớp snapshot; UNKNOWN chỉ được đối soát, không gửi thêm lệnh.
- Ngân hàng/STK sai nhưng hợp lệ có thể đưa tiền đến sai người. Tên nhập tay hoặc tên lịch sử không được dùng để tuyên bố đã xác minh.

Luồng thu tiền SePay và cách tính số tiền được hoàn không thay đổi. Hoàn tiền thuê sử dụng nguồn Owner; hoàn phí gói sử dụng nguồn Admin như trước.

## Dữ liệu và nâng cấp

Các cột tên lịch sử trong `refunds` và `payout_attempts` được giữ để không mất dữ liệu. Tài khoản mới xác nhận lưu `account_name=NULL`; worker không yêu cầu tên.

Tùy chọn lưu tài khoản vẫn có. Với database đã cài refund-accounts v1, thực hiện migration `docs/migrations/2026-11-12-01-refund-bank-account-only.sql` sau khi sao lưu và xác minh bản sao. Chọn đúng database và đặt `@carrent_refund_bank_account_backup_confirmed=1` trước khi chạy; dừng ngay nếu lỗi, không dùng `--force`. Migration chỉ đổi `refund_recipient_accounts.account_name` thành nullable, giữ các hàng và constraint khác. Bootstrap mới trong `scriptDB.sql` đã tương ứng.

Tên cột `verified_at` trong bảng lưu tài khoản là tên legacy; với lần lưu mới, giá trị là thời điểm người nhận xác nhận, không phải bằng chứng đã lookup. Giao diện không trình bày nó là xác minh của nhà cung cấp.

## Cấu hình

Không cần `CARRENT_REFUND_LOOKUP_CLIENT_ID`, `CARRENT_REFUND_LOOKUP_API_KEY` hay bật `CARRENT_REFUND_LOOKUP_ENABLED` cho luồng hoàn bình thường. Các adapter/profile chẩn đoán lookup cũ chỉ còn phục vụ kiểm tra riêng, không nằm trong đường xác nhận người nhận.

Ba khóa PayOS, quyền chi, IP được cho phép, nguồn tiền và điều kiện phê duyệt/credit vẫn cần cho giao dịch thật. Giữ các cờ kích hoạt và mapping theo trạng thái được duyệt; không bật chi chỉ vì test giả lập thành công. Không tạo lệnh chi thật chỉ để thử tìm tên tài khoản.
