# Khôi phục checkout, Tier và kết nối hoàn tiền payOS

Ngày kiểm tra: 2026-11-10. Bản sửa đã được áp dụng vào source root và app local. Chủ dự án đã yêu cầu commit, push, merge vào main và giữ các nhánh; đối chiếu kết quả phát hành bằng lịch sử Git/remote. S1/S2/S3 Payment Settings vẫn bị hủy.

## Đã sửa

- Chuyển ánh xạ `CARRENT_SEPAY_OWNER_ID` / `CARRENT_SEPAY_ADMIN_ID` sang cấu hình chung để profile `local` đọc đúng cặp pilot. Thiếu ID vẫn từ chối checkout; việc chọn ID không tự bật provider hoặc scheduler.
- Khôi phục Tier bằng `carrent.subscription.enabled=true` trong cấu hình local. Không sửa hoặc bỏ điều kiện phân quyền của menu/controller.
- Bật kênh thu SePay Owner/Admin đã được provision và worker xử lý tiền vào; payout, nguồn payOS và rental-deadline job vẫn tắt.
- Thêm `User-Agent: CarRent/0.0.1` vào mọi request payOS. Đối chứng GET thực tế: client mặc định bị Cloudflare 403/1010; cùng credentials với User-Agent ứng dụng nhận JSON lỗi từ payOS: “Địa chỉ IP không được phép truy cập hệ thống”. Không thay TLS, chữ ký, idempotency, nguồn chi hoặc cơ chế UNKNOWN query-only.

## Kiểm chứng

| Bằng chứng | Kết quả |
| --- | --- |
| RED cấu hình local | Test ADVANCE thất bại đúng vì pilot không nhận ID từ `.env`. |
| Full `clean verify` sau sửa pilot | 1.212/1.212 JUnit, 79 suites; Node 18/18; DB fixture/tài khoản test đã dọn. Tài khoản test bị từ chối quyền truy cập DB ứng dụng. |
| RED transport payOS | 23 failure thiếu User-Agent trong 64 test; không có error. |
| GREEN transport riêng | 64/64 đạt. |
| Tích hợp source pilot và transport | 67/67 đạt; sau đó `package` thành công. Đây là kiểm tra bổ sung sau aggregate, không gọi là một lần full mới trên transport đã sửa. |
| Tier trên app trước/sau | Owner dashboard thiếu/có link; `/owner/subscriptions` từ 404 thành 200; đủ SMALL/STANDARD/BUSINESS và form CSRF. |
| BEFORE trên app | Customer mở BEFORE đơn 1875 HTTP 200; có form chấp nhận. Không POST xác nhận đơn thật. Luồng mở QR và hoàn tiền được kiểm thử bằng fixture/provider offline trong suite. |
| Bảo toàn dữ liệu | `.env` và các giá trị private ngoài flags giữ nguyên; source root khớp build; index chưa stage; fingerprint 20 bảng DB ứng dụng không đổi sau cập nhật. |
| Review | Source được duyệt độc lập; đã sửa đường rollback khởi động và bổ sung liên kết SHA-256 source/JAR trước khi restart. Không fault-inject rollback trên app thật. |

Runtime mới: PID **37268**, `127.0.0.1:8080`, profile `local`, DDL `none`, SQL init `never`. App cũ PID 32872 đã được thay bằng JAR kiểm chứng. Không mở tunnel hay chuyển tiền.

JAR SHA-256: `351404bdff3a51d7396b4341ecc8b1745781ac636e9d1972dc86976dccbe6f16`. JAR không chứa `.env`, private local config hoặc các lớp Payment Settings đã hủy. Resource cấu hình và lớp payOS đóng gói khớp output compiler.

Evidence: `.scratch/booking-payment-fix-20261110/` và `.scratch/payos-refund-fix-20261110/`. Cấu hình cũ và lệnh launch được giữ riêng trong `private-before/`; không đưa nội dung private vào Git.

## Hoàn tiền payOS thật còn bị chặn

Chủ dự án đã yêu cầu bật chi tiền thật và hoàn tất dự án. Phần còn thiếu hiện là khả năng truy cập provider và đầu vào lookup; không coi yêu cầu kích hoạt là bằng chứng API đã hoạt động hoặc tiền đã vào đích. Chưa đóng P4.2-A/P5.9/P8.8 hay ghi dự án hoàn tất 100% khi các điều kiện này chưa được kiểm chứng.

1. Signed/read-only API payOS chưa truy cập được. Cần thêm IP egress hiện tại trong `.scratch/booking-payment-fix-20261110/payos-ip-to-allowlist.txt` vào allowlist của kênh chi; sau đó chạy lại diagnostic GET để kiểm tra chữ ký/list/balance.
2. Chưa có `CARRENT_REFUND_LOOKUP_CLIENT_ID` và `CARRENT_REFUND_LOOKUP_API_KEY` riêng cho tra cứu người nhận. Điền trực tiếp file private `.scratch/p42a-p88-implementation-20261109/operator-lookup.properties`, rồi kiểm tra lookup không chuyển tiền theo operator guide. Không gửi keys/OTP hoặc thông tin ngân hàng trong chat.
3. Diagnostic GET bằng chính JAR mới vẫn thất bại có kiểm soát; không tạo payout hoặc bật nguồn chi. Computer Use đã dừng vì không xác định được URL Chrome đủ tin cậy, nên không chỉnh dashboard payOS.
4. Khi provider và lookup hoạt động, mới cấu hình/bật các nguồn chi cần thiết và kiểm chứng khoản hoàn cụ thể. Hiện `READY`/`PROCESSING` không được coi là tiền đã vào tài khoản; không phát lại UNKNOWN hoặc ghi SUCCEEDED thủ công.

Luồng refund trong code đã có kiểm chứng offline cho đúng nguồn Owner→Customer và Admin→Owner, xác minh/xác nhận đích, idempotency, mất response và khởi động lại/đối soát không chi trùng. Chưa có bằng chứng chuyển tiền thật hoặc ngân hàng đã ghi có.
