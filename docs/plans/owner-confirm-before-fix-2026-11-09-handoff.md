# Bàn giao fix Owner xác nhận → BEFORE — 2026-11-09

Đã triển khai và cập nhật app **http://localhost:8080**. Booking mới theo cấu hình chuẩn dùng `VIETQR_V1`/`inspectionRequired=true` cho mọi Owner hợp lệ. Owner chấp nhận thành công tự chuyển đến `/bookings/{id}/inspections/BEFORE` để nhập tình trạng, lưu nháp, thêm ảnh và gửi Customer.

## Thay đổi

- `BookingService.createBooking` không dùng payment pilot hoặc trạng thái Admin pilot để quyết định có BEFORE. Giữ kiểm tra Owner/Customer hiện tại dưới khóa, gói, quyền đăng xe, lịch trùng và giá.
- Cấu hình mặc định bật `carrent.rental.workflow.enabled`, `advance.enabled`, `balance.enabled`. Đây là domain workflow/handlers; không bật provider, payout hoặc scheduler.
- `OwnerBookingController.confirm` redirect BEFORE chỉ sau chấp nhận thành công của đơn mới có inspection. LEGACY và lỗi nghiệp vụ giữ route chi tiết cũ.
- Owner chấp nhận vẫn giữ `PENDING`, ghi `ownerAcceptedAt`; chưa xác nhận đã trả trước hoặc giao xe. `PaymentPilot` và kiểm tra kênh trong lớp thanh toán không đổi.
- Test lịch sử chọn LEGACY tường minh; fixture không bị đổi nghĩa ngầm bởi cấu hình mặc định mới. Test admission cũ được cập nhật để phân biệt quyền lập biên bản với quyền mở thanh toán.

## Kiểm chứng

| Kiểm tra | Kết quả |
|---|---|
| RED trước implementation, `OwnerBeforeEntryTest` | 6 trường hợp, 4 fail đúng nguyên nhân LEGACY/default flag/redirect, 2 pass cho guard và tương thích cũ |
| Focused `OwnerBeforeEntryTest,BeforeInspectionTest,PaymentPilotTest` | 58/58 pass |
| Full `mvnw.cmd -DargLine=-Xmx768m verify` | **1149/1149**, 76 suites, 0 failure/error/skip; JAR tạo thành công |
| `node --test src/test/js/*.test.cjs` | **18/18** |
| Chrome desktop/390px/320px | **22 kiểm tra** với Customer1, Owner1 và Owner2: tạo đơn → accept tự tới BEFORE → nhập/lưu/thêm 4 ảnh/gửi → Customer đọc; Owner khác không đọc được |
| Kiểm tra tài chính trong fixture browser | 2 BEFORE đã gửi; obligation/intent/receipt/refund/payout đều 0 |
| Root build sau copy | `compile` thành công; class của service/controller và application.properties khớp bản đã kiểm chứng |
| DB ứng dụng | Hash dữ liệu **20/20 bảng không đổi** trước/sau; không POST booking/confirm trên schema thật |
| Diff/cleanup | File nhiệm vụ khớp worktree; fixture runtime đóng và các schema task đã xóa |

Đây là full **verify**, không gọi là clean verify. Các log RED, focused GREEN và full verify được lưu riêng. Một thread dump đọc trong lúc full suite chạy dùng để xác nhận tiến trình còn hoạt động; full suite sau đó kết thúc bình thường, không phải chạy lại một phần thay cho aggregate.

## Runtime và Git

- App cũ PID 13152 đã thay bằng **PID 32872**, bind `127.0.0.1:8080`, profile `local`, chạy class root mới.
- CLI bật rõ workflow/advance/balance; giữ `ddl-auto=none`, `sql.init.mode=never`, provider/maintenance/deadline flags trước đó đang tắt.
- Đã copy **10 file nhiệm vụ** về root sau kiểm tra baseline hash. Index, private config, seed và thay đổi của người khác được giữ nguyên; không commit/push/merge.
- Worktree giữ ở `.scratch/owner-before-fix-20261109/worktree`, branch `feat/owner-before-entry`. Nó chứa snapshot các thay đổi local kế thừa; không commit toàn bộ worktree như một task độc lập.
- Evidence: `.scratch/owner-before-fix-20261109/verification-summary.json`, `entry-red.log`, `entry-green.log`, `full-verify.log`, `node-verified.log`, `browser-evidence.json`, ảnh trước/sau, `browser-cleanup.json`, `application-db-audit.json`, `local-publication.json`, `local-verification.json`.

## Phạm vi sử dụng

Tạo **đơn mới** ở khoảng ngày còn trống rồi Owner chấp nhận để dùng luồng đã sửa. Đơn cũ như **1700** vẫn là LEGACY, không tự có BEFORE và không được ép chuyển bằng cập nhật cột DB. Có thể chủ động cấu hình workflow=false để chạy chế độ tương thích, nhưng đó không còn là mặc định của bản chạy chuẩn.

Lập biên bản áp dụng ngoài payment pilot; bước Customer mở thanh toán vẫn yêu cầu scope/kênh hợp lệ. Không tuyên bố hoàn tất kích hoạt SePay/PayOS, thanh toán/hoàn tiền thật hoặc các gate P4.2-A/P8.8. Payment Settings UI vẫn là nhiệm vụ riêng.
