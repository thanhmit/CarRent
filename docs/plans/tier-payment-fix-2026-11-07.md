# Sửa lỗi mua Tier và kiểm thử gian hàng — 2026-11-07

## Nguyên nhân và phạm vi sửa

- Khi bấm mua bất kỳ Tier nào, runtime trả flash `Chủ thể thanh toán không khớp đơn thuê.`. Đã tái hiện trên trình duyệt bằng tài khoản Owner pilot và trên cả ba gói trong MySQL fixture.
- `SubscriptionService.createOrder` tạo thời hạn bằng `Clock.instant()` có nano giây. Cột `subscription_orders.expires_at` và `payment_obligations.due_at` là `DATETIME(6)`. Khóa/đọc lại các entity làm một giá trị được đọc từ DB với micro giây trong khi giá trị kia còn nano giây trong persistence context; `validate` so sánh bằng tuyệt đối nên từ chối đơn hợp lệ.
- Chuẩn hóa `now` về micro giây ngay khi tạo đơn, trước khi tạo cả hai thời hạn. Giữ nguyên so sánh tuyệt đối, kiểm tra payer/collector, quyền Owner/Admin và pilot.
- Bản sửa không cần migration hoặc sửa dữ liệu cũ. Đơn tạo thất bại được transaction rollback.

## Kiểm chứng

- RED: `SubscriptionOrderTest#allPlansCreateAndCancelWithSubMicrosecondClock`, clock `.123456789`, cả SMALL/STANDARD/BUSINESS đều lỗi đúng thông báo người dùng; 3 errors/3 tests.
- GREEN ban đầu: `SubscriptionOrderTest,SubscriptionOwnerUiTest`, 17/17 đạt.
- Bổ sung một trường hợp sửa `due_at` lệch đúng 1 micro giây: vẫn phải bị từ chối, không cấp gói.
- E2E HTTP qua Spring Security/CSRF, MySQL disposable và webhook HMAC fixture: mua SMALL → mở VietQR → chưa trả tiền thì không được đăng xe → webhook hợp lệ → đơn APPLIED/quota4 → đăng xe PENDING → Admin duyệt → public xem được → Owner ẩn → public404/danh sách không có xe → hồ sơ Owner vẫn còn, trả lại một suất.
- Webhook fixture chỉ gửi đến application context dùng DB disposable, không gửi giao dịch giả vào DB ứng dụng hoặc ngrok.
- Regression + package: **148/148**, 0 failure/error/skip; BUILD SUCCESS. Bao gồm SubscriptionOrder/Payment/OwnerUi/Ui/Quota/Visibility/Downgrade/RentalContinuity, PaymentIntent, ReceiptApplication, SePaySharedAccount và CarMutationAuthorization.
- JAR: `.scratch/tier-e2e-20261107/build/carrent-0.0.1-SNAPSHOT.jar`; SHA256 `322c1e10c55c3434081991a6aa8225b8121f8a3317f7fd316e573e313b3a711c`. Runtime thay PID18152 bằng PID38568, giữ profile `sepay-local` và cờ tắt chi payOS.
- Bằng chứng: `.scratch/tier-e2e-20261107/{red.log,green.log,regression-package.log,runtime-result.json,browser-before.png}`. Bản sửa nguồn tại root dirty branch hiện hành; không đồng bộ completion worktree hoặc commit/push.

## Trình duyệt runtime thật sau sửa

- Đã đăng nhập lại Owner pilot2, tạo thành công cả ba Tier và xem ảnh VietQR tải hoàn chỉnh; không có nội dung trả trước đơn thuê ở checkout mua gói.
- BUSINESS: đơn5 / intent1, 15.000 VNĐ, 16 xe; đã hủy bằng form ứng dụng, trạng thái CANCELLED.
- STANDARD: đơn6 / intent2, 10.000 VNĐ, 8 xe; đã hủy bằng form ứng dụng, trạng thái CANCELLED.
- SMALL: đơn7 / intent3, 5.000 VNĐ, 4 xe; để PENDING cho người dùng test chuyển khoản. Checkout `http://localhost:8080/payment/intents/3`, đơn `/owner/subscriptions/orders/7`; hạn QR **07/11/2026 23:22:44 giờ Việt Nam**. Nếu đã hết hạn, quay lại danh sách gói và tạo yêu cầu mới, không chuyển theo QR cũ.
- Ảnh thực tế: `browser-business.png`, `browser-standard.png`, `browser-small.png` trong thư mục bằng chứng. Chỉ các đơn thử trên đây được tạo/hủy qua UI; không sửa trạng thái thanh toán trực tiếp trong DB ứng dụng.
- ngrok vẫn dùng `https://cloak-siberian-cartoon.ngrok-free.dev` trỏ8080, webhook `/webhooks/sepay/3` giữ nguyên. Chưa có bằng chứng tiền thật hoặc đăng/ẩn xe thật từ đơn này tại thời điểm bàn giao; E2E hoàn chỉnh hiện là HTTP fixture.

## Thứ tự test thật

1. Đăng nhập Owner pilot, mở `http://localhost:8080/owner/subscriptions`.
2. Chọn SMALL: 5.000 VNĐ, 4 xe, 30 ngày. Mở liên kết **Xem thanh toán và trạng thái QR**.
3. Kiểm tra ngân hàng/người nhận trong ứng dụng ngân hàng, chuyển đúng tiền và giữ nguyên nội dung CR. Thanh toán trước hạn QR; không dùng QR đã hủy/hết hạn.
4. Chờ webhook được xử lý. Checkout tự cập nhật; tải lại trang đơn và `/owner/subscriptions` để xác nhận **APPLIED / đã kích hoạt**, hạn gói và quota.
5. Owner mở `/owner/cars/new`, nhập thông tin xe và đăng. Xe mới phải ở trạng thái **chờ duyệt**; chưa công khai ngay.
6. Đăng nhập Admin bằng trình duyệt/profile khác, mở `/admin/cars`, duyệt đúng xe mới.
7. Kiểm tra xe xuất hiện ở `/cars` và mở được chi tiết bằng cửa sổ ẩn danh.
8. Owner mở `/owner/cars`, bấm **Ẩn xe** của xe vừa tạo. Kiểm tra public không còn xe/chi tiết404; Owner vẫn quản lý được hồ sơ và suất đã được trả lại.

Nếu nhận tiền nhưng đơn chưa kích hoạt, giữ lại mã đơn/thời gian và kiểm tra inbox/receipt; không chuyển thêm tiền để thử lại. Chỉ xác nhận thu thật sau khi receipt được áp dụng và gói được kích hoạt từ webhook ngân hàng thật.
