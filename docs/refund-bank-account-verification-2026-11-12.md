# Kiểm chứng hoàn tiền bằng ngân hàng + STK — 12/11/2026

Phần triển khai cục bộ đã hoàn tất và ứng dụng mới đã khởi động trên `http://localhost:8080`. Người nhận chọn ngân hàng, nhập số tài khoản rồi xác nhận. Không nhập tên chủ tài khoản, không gọi VietQR/CAS lookup và không cần đăng ký dịch vụ cho Customer.

Chưa xác nhận giao dịch tiền thật hoạt động trọn vẹn: kiểm tra GET PayOS hiện trả HTTP 403. Các cờ chi thật giữ nguyên ở trạng thái tắt. Kiểm thử không tạo khoản chuyển tiền thật.

## Luồng đã triển khai

| Bước | Hành vi |
| --- | --- |
| Chờ thông tin | Chỉ người nhận đang hoạt động được mở và xác nhận khoản hoàn của mình. |
| Nhập thông tin | Chọn ngân hàng theo tên từ danh mục đóng gói 48 đơn vị; STK là chuỗi chữ số để giữ số 0 đầu. |
| Xác nhận | Một POST có CSRF và checkbox xác nhận; không có bước preview/tên chủ tài khoản. Backend kiểm tra định dạng và danh mục, không tra cứu danh tính. |
| Sẵn sàng | Chỉ khoản hoàn có nguồn chi đủ điều kiện mới sang READY. Số tiền, người nhận và nguồn chi do server xác định; dữ liệu giả mạo trên form không thay được. |
| Gửi chi | Worker hiện có lưu durable attempt, reference, idempotency key và snapshot trước khi gọi PayOS. |
| Đối soát | Chỉ phản hồi có chữ ký hợp lệ, đúng reference/đích/số tiền và trạng thái SUCCEEDED mới hoàn tất. UNKNOWN chỉ GET đối soát, không POST lại. |
| Lưu tùy chọn | Chỉ lưu khi người nhận chọn; tên chưa được xác minh được lưu NULL, không tạo tên giả. |

Luồng thu tiền SePay, chính sách tính số tiền hoàn, nguồn Owner cho thuê xe và nguồn Admin cho phí gói được giữ nguyên. Tài khoản nhận bị khóa khi có lần chi/lease; sửa thông tin trước khi chi không đặt lại hạn một giờ.

Danh mục ngân hàng lấy từ dữ liệu định tuyến công khai, không phải lookup STK. Dữ liệu `transferSupported=1` không tự chứng minh từng ngân hàng đều được nguồn PayOS thực tế hỗ trợ; cần theo khả năng của kênh chi đang sử dụng.

## Kiểm thử đã chạy

| Nhóm | Kết quả |
| --- | --- |
| Toàn bộ Java, clean verify | **1.219/1.219**, 82/82 lớp; 0 failure, 0 error, 0 skipped. |
| JavaScript | **18/18**. |
| Launcher PowerShell | **55/55**. |
| Kiểm thử hồi quy tập trung trước full suite | 72/72; các ca này cũng nằm trong full suite, không cộng trùng vào tổng Java. |
| HTTP PayOS có chữ ký | Xác nhận không tên → một POST được ký → PROCESSING → khởi động lại → GET có chữ ký → SUCCEEDED; một attempt, giữ nguyên idempotency, không POST lần hai. |
| Mất phản hồi và phục hồi | Fake provider nhận lệnh nhưng mất phản hồi; sau khởi động lại chỉ đối soát, không gửi thêm. Bao phủ cả thuê xe và phí gói. |
| An toàn nghiệp vụ | Phân quyền, CSRF, giả mạo số tiền/nguồn/tên, tài khoản bất hợp lệ, ngân hàng ngoài danh mục, sửa đích sau khi có attempt, race/lease và giữ nguyên deadline. |
| Không phụ thuộc lookup | Xác nhận thành công khi verifier không khả dụng; normal workflow không gọi HTTP lookup. Preview cũ trả 404 sau đăng nhập. |
| Migration | Lưu NULL, từ chối tên rỗng, giữ hàng/tên lịch sử, schema khớp bootstrap, yêu cầu backup và từ chối áp dụng lại. |
| Giao diện trình duyệt | Đăng nhập Customer trên DB tạm, 48 lựa chọn ngân hàng, không có trường tên, STK số 0 đầu, checkbox bắt buộc, READY → tự cập nhật SUCCEEDED, form cuối bị khóa. |
| Mobile | Viewport 390×844, không tràn ngang; đã xem ảnh chụp READY và SUCCEEDED. |

Full suite dùng database tạm riêng cho các Spring integration test; fixture tài chính sở hữu các schema tạm riêng. Không chạy bootstrap/test tạo giao dịch lên database chính. Các cảnh báo lỗi DB được chủ động tiêm trong test rollback/recovery không phải failure của suite.

## Review

Review độc lập không tìm thấy lỗi chức năng cụ thể. Đã xử lý hai góp ý: bổ sung journey HTTP PayOS có chữ ký và sửa những phần tài liệu cũ còn xem lookup là điều kiện bắt buộc. Không bổ sung đối chiếu tên/ownership vì người dùng đã loại bỏ yêu cầu này.

## Database và bản chạy

- Đã dump riêng `refund_recipient_accounts`, kiểm tra checksum, restore vào schema tạm và so khớp toàn bộ hàng trước khi áp dụng migration.
- Chỉ thay `refund_recipient_accounts.account_name` thành nullable bằng migration có guard; không reseed/reset dữ liệu.
- Fingerprint dữ liệu 20 bảng chính giữ nguyên; `.env` và `application-local.properties` giữ nguyên.
- Không còn schema fixture mới; giữ nguyên 9 schema cũ tồn tại từ trước phiên này. File upload giữ nguyên.
- JAR mới được tạo bởi clean verify; đối chiếu **322 file class/resource** với nội dung JAR rồi kiểm tra bản sao dùng để chạy.
- SHA256 JAR: `94798f754cd99bc7f9ca88db95e3950421e3d00d77f989015683737072583296`.
- Đã xác minh đúng process CarRent cũ rồi thay PID 30820 bằng PID 9456, cổng 8080. Không commit/push.
- Smoke test bản mới: `/cars` 200; `/login` 200; GET trang hoàn khi chưa đăng nhập 302 về login; POST recipient không CSRF 403.

## Giới hạn kiểm chứng tiền thật

GET `/v1/payouts` và `/v1/payouts-account/balance` trả HTTP 403 ở lần kiểm tra hiện tại. Owner/Admin cùng bộ khóa nên kết quả cùng nguồn được tái sử dụng. Chưa xác minh quyền tạo lệnh, phê duyệt/OTP, credit/phí/giới hạn, tiền nguồn, hỗ trợ từng ngân hàng và nhận/đối soát tiền thật. Không lấy thành công của mock hoặc HTTP 200 làm bằng chứng tiền đã tới ngân hàng.

Cần giải quyết quyền truy cập PayOS trên đúng kênh chi, kiểm tra nguồn/điều kiện chi, rồi thực hiện một khoản hoàn thật được chỉ định rõ người nhận và số tiền trước khi xác nhận vận hành trọn vẹn. Không cần mua gói VietQR lookup cho luồng này.

Không có đối chiếu tên chủ tài khoản: nhập nhầm một STK hợp lệ có thể chuyển tiền tới người khác. Định dạng hợp lệ không bảo đảm tài khoản tồn tại hoặc thuộc Customer.

## Bằng chứng

Thư mục `.scratch/refund-bank-only-20261112/` lưu `full-suite-summary.json`, `full-suite.log`, XML `full-surefire/`, `node-tests.log`, `launcher-tests.log`, `browser-summary.json`, ảnh giao diện, `primary-migration-summary.json`, `artifact-summary.json`, `restart-summary.json`, `runtime-smoke-summary.json`, `database-before.json`, `database-after.json` và `payos-readonly-summary.json`. Backup riêng ở cùng thư mục, không đưa vào Git.

Mô tả luồng: [refund-bank-account-only.md](refund-bank-account-only.md). Cấu hình/vận hành: [local-payos-setup.md](local-payos-setup.md).
