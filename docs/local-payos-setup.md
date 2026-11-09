# Kết nối payOS Kênh chi cho CarRent local

Ngày đối chiếu: 2026-11-07. SePay thu tiền; payOS chi hoàn tiền từ nguồn Bảo Kim. Các lệnh dưới đây dành cho Windows PowerShell ở thư mục gốc project đã có phần tích hợp này.

## 1. Khóa và điều kiện phía payOS

1. Xác thực chủ thể/tổ chức trên payOS.
2. Thiết lập → Hồ sơ → Thay đổi dịch vụ → bật Kênh chi.
3. Ngân hàng → Thêm tài khoản → Tài khoản chi → liên kết Ví Bảo Kim cùng danh tính.
4. Kênh chuyển tiền → Thêm kênh chuyển tiền → chọn nguồn đã liên kết.
5. Trong chi tiết kênh, lấy Client ID, API Key và Checksum Key của kênh chi. Không dùng bộ khóa tạo link thanh toán.
6. Quản lý IP cho kênh → Thêm IP: IPv4 Internet đi ra của backend. Domain ngrok chỉ phục vụ webhook đi vào. Khi đổi IP/mạng/VPN, kiểm tra lại allowlist.
7. Kiểm tra tiền nguồn Bảo Kim và credit dịch vụ chi payOS riêng biệt; xác nhận phí, khoản tối thiểu 5.000 VND và điều kiện duyệt lệnh của kênh thực tế.

Nguồn chính thức:
- https://payos.vn/dang-ky-dich-vu-chi-ho/
- https://payos.vn/docs/huong-dan-su-dung/kenh-chi/tao-kenh-chuyen-tien
- https://payos.vn/docs/huong-dan-su-dung/kenh-chi/ket-noi-tai-khoan/vi-baokim/
- https://payos.vn/docs/api/

## 2. Cấu hình riêng, không đưa khóa vào Git/chat

Nếu đã điền `application-local.properties`, giữ nguyên file đó. Spring cũng đọc `.env` theo định dạng Java properties: không dùng `export`, không bọc giá trị trong dấu nháy.

```properties
carrent.payment.providers.payos.owner-payout.enabled=false
carrent.payment.providers.payos.owner-payout.client-id=
carrent.payment.providers.payos.owner-payout.api-key=
carrent.payment.providers.payos.owner-payout.checksum-key=
carrent.payment.providers.payos.admin-payout.enabled=false
carrent.payment.providers.payos.admin-payout.client-id=
carrent.payment.providers.payos.admin-payout.api-key=
carrent.payment.providers.payos.admin-payout.checksum-key=
```

Hoặc điền sáu biến `CARRENT_PAYOS_OWNER_*` / `CARRENT_PAYOS_ADMIN_*` trong `.env.example` đã sao chép thành `.env`. Chỉ dùng một cách cấu hình cho mỗi trường; giá trị trực tiếp trong file local có thể ghi đè placeholder trong application.properties. Không để cùng một property trống trong `.env` ghi đè khóa đã điền ở file local.

Hai kênh CarRent giữ reference riêng `owner-payout` và `admin-payout`. Nếu nguồn chi dùng chung đã được chủ dự án/provider xác nhận, có thể cấu hình Admin tham chiếu Owner ở file local:

```properties
carrent.payment.providers.payos.admin-payout.client-id=${carrent.payment.providers.payos.owner-payout.client-id}
carrent.payment.providers.payos.admin-payout.api-key=${carrent.payment.providers.payos.owner-payout.api-key}
carrent.payment.providers.payos.admin-payout.checksum-key=${carrent.payment.providers.payos.owner-payout.checksum-key}
```

Đây là nguồn chi dùng chung, vẫn có hai mapping Owner/Admin trong CarRent. Dùng chung ngân hàng thu SePay không tự xác định quyết định chia sẻ nguồn chi này.

## 3. Kiểm tra kết nối chỉ đọc

```powershell
.\mvnw.cmd -o spring-boot:run '-Dspring-boot.run.profiles=payos-check'
```

Lệnh này:

- Chỉ gọi `GET /v1/payouts?limit=1&offset=0` và `GET /v1/payouts-account/balance`.
- Kiểm tra code `00`, một header chữ ký hợp lệ, cấu trúc response, tiền tệ VND và số dư nguyên không âm.
- Chạy được khi payout đang disabled; không thay đổi cờ provider hoặc mapping DB.
- Chỉ in `read=VERIFIED` và `source=FUNDED` hoặc `source=ZERO`; không in khóa, tên/số tài khoản hoặc số dư cụ thể.
- Nếu hai reference trỏ cùng bộ khóa, tái sử dụng một kết quả kiểm tra.
- Loại trừ scheduler tài chính/deadline bằng profile; từ chối nếu cờ scheduler vẫn được cấu hình bật.
- Kết thúc process sau kiểm tra; không restart process đang nghe cổng 8080.

`FUNDED` chỉ có nghĩa số dư nguồn lớn hơn 0; không bảo đảm đủ số tiền thử, phí, credit dịch vụ hay quyền tạo lệnh. Lỗi được trả bằng thông báo cố định, không đưa response HTTP vào log.

## 4. Tạo mapping kênh chi tắt

Điền `CARRENT_PAYOS_OWNER_ID` và `CARRENT_PAYOS_ADMIN_ID` trong `.env`, hoặc để trống để dùng `CARRENT_SEPAY_OWNER_ID` / `CARRENT_SEPAY_ADMIN_ID`. Đây là ID user CarRent, không phải ID payOS. User phải ACTIVE, đúng OWNER/ADMIN và khác nhau.

```powershell
.\mvnw.cmd -o spring-boot:run '-Dspring-boot.run.profiles=payos-setup'
```

Lệnh chạy hai GET xác thực trước, rồi trong một transaction tạo `PAYOS/PAYOUT` với reference Owner/Admin. Row mới luôn disabled; chạy lại giữ ID và trạng thái enabled cũ. Mapping khác chủ thể không bị trỏ lại; toàn bộ thao tác rollback khi có sai lệch. Không lưu API key/checksum vào DB.

Không bật `enabled=true` hoặc scheduler chỉ vì lệnh này thành công.

## 5. Trước khi chi thử

Cần đủ:

1. Kênh/source đúng chủ thể, quyền chi hoạt động, phí/tối thiểu/credit và tiền nguồn được kiểm chứng.
2. API xác minh tên chủ tài khoản nhận **không chuyển tiền**. SDK/API payOS được kiểm tra hiện chưa có hợp đồng độc lập đáp ứng yêu cầu này; endpoint số dư chỉ nói về nguồn chi. Production `RefundDestinationVerifier` tiếp tục từ chối cho tới khi có endpoint/auth/request/response đã xác nhận.
3. Giao dịch thử xác định: môi trường, booking/order/refund, người thực hiện, BIN và tài khoản nhận do người dùng kiểm soát, số tiền VND, kết quả mong đợi và reviewer. Thông tin ngân hàng đầy đủ chỉ giữ trong cấu hình/DB bảo vệ hoặc nhập trên form, không ghi vào handoff.
4. Chốt bản source/JAR, cấu hình pilot và thời điểm chạy. Chỉ kích hoạt phạm vi đã được duyệt.
5. Dùng luồng refund hiện có: xác minh người nhận → xác nhận tên → durable attempt → dispatch → GET đối soát. Không thêm endpoint tạo payout công khai hoặc sửa DB giả trạng thái READY để vượt qua xác minh người nhận.

## 6. Recovery và giới hạn hiện tại

- Lưu reference/idempotency key/source/đích/số tiền trước khi gửi; không thay key sau timeout.
- GET theo payout ID hoặc reference trên đúng kênh. Chỉ response đã xác thực và khớp snapshot mới ghi hoàn thành.
- Danh sách recovery phải có đúng một lệnh và `pagination.hasMore` kiểu boolean false; thiếu phân trang hoặc phân trang không rõ giữ UNKNOWN.
- `SUCCEEDED` lấy từ transaction state; approvalState không chứng minh đã chuyển tiền.
- Timeout, HTTP error, FAILED/CANCELLED/REVERSED hoặc truy vấn rỗng không phải bằng chứng chắc chắn chưa chi. Giữ UNKNOWN và query; không POST lại hay tự tạo attempt mới.
- Chưa có hợp đồng provider xác nhận NOT_SENT/retention/replay và mã thiếu tiền, nên chưa map các lỗi đó thành retry tự động/WAITING_FUNDS. Test fake của worker không thay evidence provider thật.

Đủ khóa và GET thành công chưa đóng P4.2-A/P8.8/P5.9. Hồ sơ còn thiếu: API lookup người nhận; quyền tạo lệnh, phí/minimum và recovery thực tế; giao dịch chi/nhận tiền và đối soát thật; contributor review.
