# payOS: kiểm tra tài khoản và cấu hình kết nối

Ngày kiểm tra: 2026-11-08. Phạm vi: đăng nhập tài khoản theo yêu cầu chủ dự án, đọc cấu hình kênh chi, đối chiếu cấu hình private và kiểm chứng API chỉ đọc. Người dùng hoàn tất OTP trực tiếp trên trình duyệt.

## Đã xác minh

- Tổ chức đã xác thực; kênh chuyển tiền đang hoạt động và liên kết ví Bảo Kim. Dashboard hiển thị ví có số dư và credit dịch vụ chi có số dư; chưa suy ra quyền POST/tự duyệt hay khả năng chi mọi số tiền từ hai số dư này.
- Client ID, API Key và Checksum Key lấy từ đúng trang kênh chi đều khớp ba trường Owner đang có trong `application-local.properties`. Không cần ghi đè file. Admin tiếp tục dùng alias tới cùng bộ khóa, giữ hai mapping nghiệp vụ riêng theo cấu hình nguồn chung hiện có.
- `application-local.properties` và `.env` được Git bỏ qua. Không đưa mật khẩu, OTP, khóa, số tài khoản hoặc số CCCD vào tài liệu/evidence.
- IPv4 Internet hiện tại của máy backend được xác nhận bằng hai nguồn độc lập và không khớp IP cũ trong allowlist. Đã thêm đúng IPv4 hiện tại với mô tả `CarRent local backend - 2026-11-08`; giữ nguyên IP cũ. Không mở dải IP hoặc đổi liên kết ví.
- Trước thay đổi allowlist: Java GET-only check thất bại; truy vấn chẩn đoán trả HTTP 403 / Cloudflare 1010. Sau bổ sung IP: cùng JAR và bộ khóa kiểm tra thành công, cả Owner/Admin `read=VERIFIED source=FUNDED`. Không thay HTTP client, giả User-Agent hoặc bỏ kiểm tra chữ ký.
- Hai truy vấn là `GET /v1/payouts?limit=1&offset=0` và `GET /v1/payouts-account/balance`; adapter xác minh chữ ký phản hồi và cấu trúc dữ liệu. Hai reference dùng chung bộ khóa nên runner tái sử dụng cùng kết quả, không phải hai nguồn tiền độc lập.
- Build JAR từ source hiện tại thành công. Fingerprint và số dòng của toàn bộ 20 bảng DB ứng dụng trước/sau giữ nguyên; `refunds` và `payout_attempts` đều trống. Không phát lệnh chi hoặc restart ứng dụng đang dùng cổng 8080.

## Cấu hình được giữ

- Mapping PAYOS/PAYOUT: Owner user 2/channel 1/reference `owner-payout`; Admin user 1/channel 2/reference `admin-payout`. Đây là ID nội bộ CarRent.
- Hai mapping DB và hai provider flag tiếp tục disabled vì production `RefundDestinationVerifier` vẫn từ chối khi chưa có lookup được cấp quyền. Profile `sepay-local` cũng giữ `carrent.payment.payout.enabled=false`.
- Mạng/IP có thể thay đổi. Khi backend đổi mạng, kiểm tra lại IPv4 egress và allowlist trước khi kết luận khóa không hợp lệ.

## Phần chưa đạt để nghiệm thu 100%

1. Contract và quyền dùng API tra tên tài khoản theo BIN/số tài khoản, không tạo lệnh hoặc chuyển tiền. Cần tích hợp verifier thật và kiểm thử dữ liệu/lỗi sau khi có contract. Không dùng tên do khách tự khai, API số dư nguồn hoặc tạo payout để tra tên.
2. Xác nhận quyền tạo payout, tự duyệt/OTP, min/max/phí và cách phân loại lỗi thiếu tiền/NOT_SENT/recovery của kênh cụ thể. GET thành công không thay các bằng chứng này.
3. Review đúng source/JAR/schema và cấu hình pilot; giao dịch thực tế do người dùng chủ động, đối soát cả Owner → Customer và Admin → Owner. Chưa đánh dấu P4.2-A/P5.9/P8.8 hoàn thành.

Đối chiếu mới [API lookup ngày 08-11](payos-recipient-lookup-check-2026-11-08.md): VietQR vẫn công bố `/v2/lookup` trả `accountName` nhưng đang migration sang CAS; chưa xác nhận entitlement của tài khoản hiện tại. `estimate-credit` chỉ có schema phí, không có tên. Không tự tái sử dụng khóa payout payOS cho sản phẩm khác.

## Nội dung yêu cầu hỗ trợ đã soạn, chưa gửi

> Chào payOS/Casso, chúng tôi đang tích hợp hoàn tiền CarRent qua kênh chi payOS liên kết ví Bảo Kim. Kênh đang hoạt động; đã cập nhật IP backend và kiểm tra thành công GET danh sách payout/số dư, xác thực chữ ký. Cần xác minh tên chủ tài khoản nhận theo BIN + số tài khoản trước khi khách xác nhận và trước khi tạo payout.
>
> Xin xác nhận sản phẩm/API lookup độc lập không tạo lệnh hoặc chuyển tiền, có thể cấp quyền cho tài khoản hiện tại không. Nếu VietQR `/v2/lookup` đang chuyển sang CAS, xin endpoint sandbox/production hiện hành, cách cấp credential/scope riêng, request/response, lỗi, ngân hàng hỗ trợ, phí và rate limit. Xin hướng dẫn entitlement; chúng tôi không tự dùng khóa payout cho lookup.
>
> Xin xác nhận thêm quyền tạo payout/tự duyệt/OTP, min/max/phí của kênh Bảo Kim; contract thiếu tiền, FAILED/CANCELLED/REVERSED, idempotency retention và điều kiện chắc chắn chưa gửi để retry an toàn. Hiện GET đã đạt nhưng chưa thử chuyển tiền. Không gửi kèm mật khẩu, OTP, API Key hoặc Checksum Key.

Mẫu đầy đủ trước đó nằm tại [nghiên cứu prerequisites](payos-refund-live-prerequisites-2026-11-07.md#yêu-cầu-hỗ-trợ-có-thể-gửi). Chưa gửi thông điệp hỗ trợ ra ngoài.

## Evidence local

Các artifact được Git bỏ qua trong `.scratch/payos-20261107/`:

- `account-20261108-package.log`: build JAR mới thành công; không phải lượt chạy lại toàn bộ test.
- `account-20261108-read-check.json`: lần GET-only đầu thất bại.
- `account-20261108-after-ip-check.json`: lần GET-only sau thay đổi allowlist đạt, hai reference có nguồn FUNDED.
- `database-account-20261108-before.json` và `database-account-20261108-after.json`: đối chiếu toàn bộ 20 bảng, không đổi.

Không chép khóa vào evidence. Kết quả 170 test của lượt audit trước nằm trong `audit-20261108.log`; phiên cấu hình này không chạy lại toàn bộ test hoặc browser E2E nghiệp vụ.
