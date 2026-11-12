# Mở quyền PayOS và tra cứu tài khoản VietQR

Kiểm tra ngày 11/11/2026. Chưa thay đổi tài khoản nhà cung cấp, mua gói hoặc bật chi tiền.

## Vì sao chưa tự thao tác được

Kết nối tới Chrome đã đăng nhập báo `The browser access host is unavailable`; kết nối ứng dụng native cũng không có pipe. Trình duyệt tự động thay thế mở được trang, nhưng PayOS yêu cầu đăng nhập Casso và VietQR chuyển tới `/login`. Phiên thay thế không có phiên đăng nhập Chrome của chủ tài khoản.

Đã làm mới IPv4 Internet đi ra của máy đang chạy backend. Giá trị khớp bằng chứng PayOS trước đó và nằm riêng trong `.scratch/provider-activation-20261111/backend-egress-ipv4.txt`. Không đưa IP/khóa vào tài liệu này.

## 1. PayOS: thêm IP vào đúng kênh chuyển tiền

1. Mở <https://my.payos.vn/> bằng Chrome đã đăng nhập, chọn đúng tổ chức.
2. Mở **Kênh chuyển tiền**, chọn kênh đang cấp bộ khóa cho CarRent. Đây là kênh chi; Client ID cần khớp `carrent.payment.providers.payos.owner-payout.client-id` trong `application-local.properties`.
3. Trong chi tiết kênh, tìm **Quản lý IP cho kênh** → **Thêm IP**.
4. Mở file IPv4 nêu trên, sao chép toàn bộ giá trị vào **Địa chỉ IP**. Có thể đặt mô tả `CarRent local backend`. Giữ các IP hiện có nếu vẫn đang sử dụng.
5. Nhấn **Thêm IP**, xác nhận IP xuất hiện trong danh sách. Nếu có OTP hoặc yêu cầu xác thực, chủ tài khoản thực hiện trực tiếp.
6. Cấu hình hiện tại dùng cùng Client ID PayOS cho Owner và Admin. Nếu dashboard xác nhận đây là cùng một kênh thì chỉ thêm IP trên kênh đó; nếu sau này đổi sang hai kênh riêng, cấu hình IP cho từng kênh.
7. Sau khi lưu, chạy lại kiểm tra GET có xác thực/chữ ký cho Owner và Admin. Chỉ khi API không còn từ chối mới xác nhận đã xử lý `IP_DENIED`.

IP cần thêm là IPv4 Internet đi ra của backend, không phải `127.0.0.1`, IP mạng LAN hoặc domain ngrok. Đổi mạng/VPN hoặc chuyển backend sang máy chủ khác cần kiểm tra lại IPv4. Chưa bật payout worker chỉ vì đã thêm IP.

Nguồn chính thức xác nhận các nhãn và thứ tự thao tác: <https://payos.vn/docs/huong-dan-su-dung/kenh-chi/tao-kenh-chuyen-tien>.

## 2. VietQR: yêu cầu đúng quyền lookup

1. Mở <https://my.vietqr.io/profile>, đăng nhập tài khoản đang cấp hai khóa đã cấu hình cho CarRent.
2. Kiểm tra mục **Gói hiện tại và sử dụng API lookup**: tên gói, quota sử dụng/còn lại và thời gian đặt lại. Nhãn này được đối chiếu từ bundle giao diện công khai; chưa quan sát tài khoản thật trong phiên này.
3. Nếu tài khoản có lựa chọn đăng ký/nâng gói, kiểm tra gói đó có ghi rõ quyền **tra cứu STK/tên chủ tài khoản** qua `POST https://api.vietqr.io/v2/lookup`. Chưa có bằng chứng một gói tạo mã QR bất kỳ sẽ mở quyền này; cần nhà cung cấp xác nhận trước khi mua.
4. Nếu không có lựa chọn phù hợp, mở <https://cas.so/support> hoặc kênh Zalo được liên kết trên tài liệu chính thức: <https://zalo.me/2322623168481751157>. Gửi yêu cầu mẫu dưới đây. Chưa có báo giá hoặc quy trình tự kích hoạt legacy lookup được xác minh công khai.
5. Thực hiện xác thực chủ thể, đăng ký và thanh toán trực tiếp theo phương án nhà cung cấp xác nhận. Yêu cầu họ xác nhận quyền đã hoạt động cho đúng Client ID.
6. Nếu họ tiếp tục cấp `/v2/lookup`, chạy thử một truy vấn không chuyển tiền với tài khoản do bạn kiểm soát. Thành công phải có `code="00"` và `data.accountName`; HTTP 200 riêng lẻ chưa đủ. Nếu được cấp bộ khóa mới, cập nhật trực tiếp trong `application-local.properties`.
7. Nếu họ chỉ cấp dịch vụ CAS mới, lấy tài liệu endpoint tra cứu và cơ chế xác thực chính thức. Adapter hiện tại của CarRent dùng VietQR `/v2/lookup`; không thể thay khóa CAS vào hai trường cũ rồi mặc định hoạt động. Cần điều chỉnh tích hợp theo hợp đồng mới trước khi bật lookup.

Yêu cầu mẫu, chưa gửi:

> Tôi vận hành CarRent và cần API tra cứu tên chủ tài khoản ngân hàng để xác minh người nhận hoàn tiền. Tài khoản VietQR hiện gọi POST https://api.vietqr.io/v2/lookup trả code 47: "The Free Plan will no longer support from August 20, 2024." Xin xác nhận tài khoản của tôi có thể kích hoạt quyền lookup trên endpoint này không; nếu được, cho biết gói/giá, quota và yêu cầu xác thực. Nếu dịch vụ chỉ còn trên CAS, xin cung cấp quy trình đăng ký, endpoint tra cứu tên STK, cơ chế xác thực và tài liệu tích hợp. CarRent đang dùng PayOS để chi tiền và cần làm rõ khả năng sử dụng API lookup độc lập.

Không đính kèm API Key, Checksum Key, mật khẩu hoặc OTP vào yêu cầu hỗ trợ. Khách hàng nhận hoàn tiền không cần đăng ký VietQR/CAS.

Nguồn: <https://www.vietqr.io/tra-cuu-stk.md> thông báo migration sang CAS; <https://cas.so/product/pay-out/>; <https://cas.so/support>. Hai khóa VietQR đã có trong cấu hình; thay đổi cờ local không thể tự mở quyền/gói trên nhà cung cấp.

## Trạng thái sau lần chuẩn bị này

- Chưa sửa allowlist do không truy cập được phiên đăng nhập.
- Chưa kích hoạt/mua gói VietQR hoặc CAS; chưa có giá được xác minh.
- Lookup và payout vẫn tắt. Chưa gửi tiền hoặc tạo hoàn tiền thật.
- Bằng chứng chuẩn bị: `.scratch/provider-activation-20261111/activation-status.json` và các bản tài liệu công khai tải cùng thư mục.

## Kiểm tra lại ngày 12/11/2026

- Chủ dự án quyết định giữ luồng PayOS hiện tại; phương án chuyển khoản thủ công mới chỉ được phân tích, chưa triển khai.
- Chrome tiếp tục báo không có browser access host/native pipe. Phiên trình duyệt thay thế vẫn ở màn hình đăng nhập PayOS và VietQR, nên chưa lưu được allowlist.
- IPv4 đi ra đã thay đổi so với lần trước. Hai nguồn IPv4 độc lập trả cùng một giá trị. Đã cập nhật file `.scratch/provider-activation-20261111/backend-egress-ipv4.txt` mà chủ dự án đang dùng; bản cùng ngày ở `.scratch/provider-activation-20261112/backend-egress-ipv4.txt`.
- GET danh sách payout vẫn trả HTTP 403, code `403`, IP không được phép truy cập. Owner/Admin hiện dùng cùng Client ID.
- Chủ dự án báo Portal hiện Free, dùng 0/25, còn 25 lượt. Một lần lookup không chuyển tiền với bộ khóa hiện cấu hình vẫn trả HTTP 200, code `47`, `The Free Plan will no longer support from August 20, 2024.`, không có tên chủ tài khoản. Chưa có bằng chứng lỗi do hết quota; cần provider xác nhận entitlement trên đúng Client ID và khả năng tiếp tục sử dụng endpoint legacy.
- Không đổi cấu hình ứng dụng, không bật lookup/payout và không tạo lệnh chi. Bằng chứng đã loại khóa/STK/IP: `.scratch/provider-activation-20261112/provider-recheck.json`.
