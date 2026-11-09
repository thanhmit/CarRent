# SePay webhook đã kết nối local — 2026-11-07

## Kết quả thực hiện

- Đã đăng nhập Dashboard SePay và tạo **một** webhook `CarRent Local - TPBank - Owner/Admin`; danh sách xác nhận webhook đang kích hoạt, ngân hàng TPBank, chỉ tiền vào và HMAC-SHA256.
- URL thật: `https://cloak-siberian-cartoon.ngrok-free.dev/webhooks/sepay/3`. ID 3 là kênh CarRent Owner; không phải ID ngân hàng SePay. Kênh Admin ID 4 dùng cùng tài khoản và trust boundary, được định tuyến theo mã intent.
- Định dạng JSON; bật tự gửi lại khi server trả lỗi. Chọn riêng tài khoản chính TPBank đã liên kết; không chọn VA/TKP. Bật “Dùng để xác thực thanh toán”; không bật “Chỉ gửi khi có mã thanh toán”, không giới hạn tiền tố ở webhook để giữ giao dịch thiếu mã cho đối soát. Không cấu hình gửi cảnh báo ra các kênh khác.
- Đã tạo HMAC secret ngẫu nhiên, điền cùng giá trị ở SePay và `.env` Git-ignored. Không ghi secret hoặc mật khẩu đăng nhập vào source/tài liệu. Mật khẩu đăng nhập không phải cấu hình backend.
- Đã thêm và lưu mẫu nhận diện **CR + đúng 30 ký tự số/chữ**, bật nhận diện mã. Giữ nguyên mẫu DH và các cấu hình khác. BIN TPBank đã đối chiếu catalog chính thức là **970423**; số tài khoản trong `.env` khớp tài khoản người dùng chỉ định, không chép lại vào handoff.
- Chạy `sepay-setup` xác minh mapping hiện có, sau đó `sepay-activate` bật hai kênh SEPAY thu 3/4. Không thay đổi kênh PAYOS chi.
- Đã thay runtime xem gói trước đó bằng profile **`sepay-local`**, cổng 8080, PID **18152** tại lúc bàn giao. Bật pilot Owner 2 / Admin 1, subscription/rental 30%/70%, inbox worker/deadline; **payout dispatch/query và hai provider payOS vẫn tắt**.

## Source và artifact

Root vẫn là `feat/phase5-profile-password`, HEAD `b829b05`, dirty baseline có sẵn. Phiên này không sửa Java/template hoặc đồng bộ worktree; phần tích hợp cần thiết đã có và được kiểm chứng lại. Chỉ cập nhật cấu hình private, trạng thái mapping, Dashboard, runtime và tài liệu. Không commit/push/migration.

Runtime dùng JAR đã xác minh `.scratch/sepay-20261107/root-build/carrent-0.0.1-SNAPSHOT.jar`, SHA256 `1b2091bc257b6c0e214efd959eba0d1ac3d9118ca2ce6c4af94ee5817cfdeba1`. Build kiểm chứng mới dùng directory riêng để không ghi đè artifact đang chạy. Nếu sửa code sau checkpoint này, cần build/test lại trước khi dùng JAR mới.

## Kiểm chứng thực tế và giới hạn

| Kiểm tra | Kết quả | Điều đã chứng minh |
|---|---|---|
| 8 lớp test collection/provider/setup/intent/receipt/subscription | **124/124**, 0 failure/error/skip | HMAC, validation, shared routing, dedup, QR, thu tiền và chặn chi trên fixture DB riêng |
| RentalAdvance, RentalBalance, RentalBalanceDeadline, FinalRentalJourney | **62/62**, 0 failure/error/skip | Luồng 30%/70%, hạn và hành trình tích hợp trên fixture |
| Dashboard sau tạo | Webhook kích hoạt, HMAC, TPBank, tiền vào, URL đúng | Cấu hình đã lưu thật trên SePay |
| Reload cấu hình chung | CR, min=max=30, số/chữ, active; DH giữ nguyên | Mã mới của CarRent có cấu trúc được SePay nhận diện |
| POST `{}` không chữ ký qua local và ngrok | **401** + `success:false` | Tunnel tới đúng backend và từ chối request không xác thực |
| POST `{}` ký HMAC hợp lệ qua ngrok | **400** + `success:false` | Secret được runtime nạp và xác thực đúng, payload bị chặn ở validation trước ingestion |

**400/401 phía trên là kết quả mong đợi của kiểm tra bảo mật với payload không hợp lệ, không phải webhook thanh toán thành công.** Không gửi giao dịch ngân hàng giả vào DB ứng dụng, không tạo order/receipt/refund giả để có màn success. Chưa có chuyển tiền thật hoặc delivery giao dịch thật từ SePay được nghiệm thu trong phiên này. Hai lượt test tổng cộng **186/186** không thay bằng chứng đó.

Evidence không theo Git tại `.scratch/sepay-connect-20261107/`: `baseline-tests.log`, `rental-tests.log`, `setup-validation.log`, `activation.log`, `runtime.log`, `runtime-result.json`, `endpoint-check.json`, `webhook-created.png`. Không đọc ngrok inspector chứa header/payload. Connector web search lỗi OAuth trong phiên này; BIN được kiểm tra trực tiếp qua catalog `https://vietqr.app/banks.json`, cấu hình HMAC được xác nhận trên Dashboard và code đã test.

## Bước người dùng thực hiện để nghiệm thu thu tiền thật

1. Giữ ngrok và backend hoạt động. Mở `http://localhost:8080/owner/subscriptions`, đăng nhập đúng Owner pilot ID 2. Chọn gói phù hợp; Small hiện là 5.000 VNĐ/30 ngày/4 xe theo catalog. Chưa tự tạo đơn mua gói trong phiên này.
2. Mở QR **mới** do hệ thống tạo. Kiểm tra ngân hàng, chủ tài khoản, số tiền và nội dung CR trước khi người dùng tự chuyển tiền từ tài khoản gửi phù hợp. Không dùng intent cũ có hậu tố 32 ký tự, không sửa/cắt mã hoặc xóa lịch sử cũ.
3. Sau chuyển, đối chiếu SePay có giao dịch tiền vào, webhook trả HTTP 200 với `success:true`, inbox PROCESSED, receipt APPLIED và order APPLIED; gói cấp đúng một lần. Gửi lại cùng delivery không được cấp thêm gói/ghi thêm receipt.
4. Với thuê xe: đúng Customer/Owner thực hiện BEFORE và đồng ý → thanh toán 30% → nhận xe/AFTER/Owner nhận lại → thanh toán 70%; đối chiếu receipt/timeline/nghĩa vụ từng bước, không bỏ qua điều kiện bằng SQL.
5. Nếu tiền đã đi mà chưa cập nhật: kiểm tra lịch sử webhook và retry giao dịch cũ; **không chuyển lần hai**. Tích hợp tự query đối soát SePay chưa có, hiện phục hồi bằng gửi lại từ Dashboard. Giữ backend/tunnel online để nhận retry.

Thu tiền và chi hoàn là hai phần khác nhau: cấu hình này không hoàn thiện payOS lookup/quyền chi hoặc chứng minh hoàn tiền thật. P4.2-A/P8.8/P5.9 giữ các gate live/human còn lại; không thay số task chỉ bằng tạo webhook.

## Chạy lại ở phiên sau

Secret đã có trong `.env`; không chạy lại tạo webhook hoặc đổi secret nếu chưa có nhu cầu xoay khóa. Dùng hướng dẫn [SePay/ngrok local](../local-ngrok-sepay-setup.md), profile `sepay-local` và mapping hiện có. Restart có thể đổi PID; kiểm tra tiến trình/cổng trước thao tác. Mỗi thành viên clone dự án vẫn tự dùng ngrok, SePay và `.env` của mình.
