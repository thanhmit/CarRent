# Owner xác nhận → BEFORE — Implementation Plan

> **For agentic workers:** Dùng `superpowers:executing-plans` inline, TDD và verification-before-completion. Người dùng đã yêu cầu lập kế hoạch rồi implement trong cùng phiên; không cần thêm vòng duyệt kế hoạch.

**Goal:** Booking mới của mọi Owner đang được phép nhận đơn có BEFORE; Owner chấp nhận thành công tự mở form tình trạng xe.

**Architecture:** Bật workflow/advance/balance domain handlers trong cấu hình ứng dụng mặc định. Booking admission kiểm tra quyền Owner/Customer, lịch xe và gói như hiện có, không dùng payment pilot để quyết định có biên bản; thanh toán vẫn dùng PaymentPilot và payment channel checks hiện có. Redirect thành công của VIETQR_V1/inspectionRequired đến BEFORE; LEGACY và lỗi giữ route chi tiết cũ.

**Tech stack:** Spring MVC/JPA/MySQL/Thymeleaf hiện có, JUnit/MockMvc, Chrome headless. Không dependency hoặc schema mới.

**Spec:** Yêu cầu trong phiên: mỗi lần Owner xác nhận đơn mới thành công phải tự vào BEFORE để nhập tình trạng xe. Đơn LEGACY đã tồn tại không tự chuyển đổi. Chấp nhận không đồng nghĩa đã nhận tiền hoặc đã giao xe.

**Status:** IMPLEMENTED / VERIFIED / ACTIVE LOCAL. Full verify 1149/1149, Node 18/18, Chrome 22 kiểm tra; app8080 đã restart. Xem [bàn giao](owner-confirm-before-fix-2026-11-09-handoff.md).

## Phạm vi và điều kiện

- Áp dụng cho mọi Owner hợp lệ, không chỉ Owner pilot. Xe inactive/chưa duyệt, tài khoản sai role/bị khóa, lịch trùng và subscription chưa đủ vẫn bị từ chối như trước.
- Cấu hình chuẩn đặt `carrent.rental.workflow.enabled=true`, `advance.enabled=true`, `balance.enabled=true`; không bật provider, payout, webhook, scheduler hoặc subscription bằng thay đổi này.
- Nếu vận hành chủ động đặt workflow=false thì đó là chế độ tương thích LEGACY rõ ràng, không còn là mặc định của bản chạy chuẩn. Test lịch sử phải chọn mode này tường minh.
- Admin/pilot chưa cấu hình không chặn bước lập biên bản. Không thay `PaymentPilot` hoặc bỏ hạn chế ở `PaymentIntentTransactions`: thanh toán vẫn từ chối nếu chưa đủ scope/kênh.
- Không sửa đơn 1700 hoặc bất kỳ booking thật nào để thử. Tất cả POST xác nhận/lưu/gửi biên bản trong schema fixture riêng.
- Giữ dirty root/index, secret/config riêng, seed/uploads và lịch bận vừa hoàn thiện; triển khai trong worktree có snapshot/manifest.

## B1 — Baseline và test tái hiện

**Files:** `src/test/java/com/carrent/payment/OwnerBeforeEntryTest.java`, `BeforeInspectionTest.java` cùng thư mục; scratch manifest/runtime.

- [x] Tạo workspace cách ly từ HEAD và snapshot nguồn local hiện tại, DB-only config riêng; test dùng schema ngẫu nhiên do fixture sở hữu.
- [x] Test HTTP Customer tạo đơn → Owner1/Owner2 chấp nhận → redirect `/bookings/{id}/inspections/BEFORE` → GET form 200/editable → lưu và gửi biên bản có ảnh.
- [x] Dùng pilot 0/0 để chứng minh BEFORE không phụ thuộc khai báo payment pilot; assert không tạo payment obligation/intent/receipt/payout.
- [x] Test POST lặp giữ timestamp/notification, Owner khác/Customer không được xác nhận, CSRF, đơn lỗi giữ trang chi tiết; LEGACY cũ giữ hành vi cũ.
- [x] Chạy test trước implementation, giữ RED đúng nguyên nhân LEGACY hoặc redirect chi tiết.

## B2 — Sửa shared service/controller/config

**Files:** `BookingService.java`, `OwnerBookingController.java`, `src/main/resources/application.properties`.

- [x] Đổi admission từ `rentalWorkflowEnabled && pilot.allowsRentalOwner(ownerId)` thành `rentalWorkflowEnabled`.
- [x] Bỏ kiểm tra Admin pilot tại createBooking và dependency PaymentPilot không còn dùng ở BookingService; giữ kiểm tra Owner/Customer hiện tại dưới khóa, gói, lịch, giá và server-owned workflow fields.
- [x] Sau `confirmBooking` commit thành công, nếu VIETQR_V1 và inspectionRequired thì return BEFORE; lỗi/LEGACY return detail.
- [x] Bật 3 domain flags trong cấu hình mặc định, giữ provider/payout/job flags nguyên trạng. Không đổi annotation default khi application.properties không được nạp.
- [x] Không thay thời điểm CONFIRMED của VIETQR: Owner chấp nhận vẫn PENDING, BEFORE có ownerAcceptedAt; thanh toán/giao xe có bước riêng.

## B3 — Cập nhật regression theo nghiệp vụ mới

**Files:** `PaymentFlowFixture.java`, `FinalRentalJourneyTest.java`, `CarCommandTransactionTest.java`, `CarQueueHttpIntegrationTest.java`, `CarDateSearchIntegrationTest.java`.

- [x] Fixture lịch sử truyền workflow/advance/balance=false tường minh khi test không yêu cầu bật; test chuyên lifecycle truyền giá trị sysprop đã chọn. Không dùng thay đổi global defaults để ngầm thay fixture.
- [x] Test admission cũ đổi kỳ vọng: workflow tắt tường minh vẫn LEGACY, workflow bật thì Owner ngoài pilot/no-pair/missing Admin vẫn có BEFORE, missing handler/Owner invalid vẫn bị chặn.
- [x] Kiểm chứng `PaymentPilotTest` vẫn từ chối mở/hiện QR ngoài scope; Customer không thể mở thanh toán chỉ vì đã có BEFORE.
- [x] Chạy suites mới và các suite Before/RentalJourney/PaymentPilot/Advance/Handover/queue/date-search liên quan trên datasource fixture.

## B4 — Chrome, tích hợp local và bàn giao

- [x] Chrome fixture end-to-end cho 2 Owner: tạo booking, accept tự tới BEFORE, nhập/lưu và gửi tình trạng xe; đối chiếu số bản ghi thanh toán và role/CSRF.
- [x] Review diff tại shared service/controller/config, so hash trước khi copy file nhiệm vụ về root, không ghi đè sửa đồng thời.
- [x] Build root mới và restart app8080 theo cấu hình hiện có để fix có hiệu lực; giữ provider/job flags đang tắt, ddl-auto=none, sql.init=never. GET smoke, không tạo đơn thử ở DB thật.
- [x] Dọn runtime/schema fixture; ghi handoff gồm tests thực chạy, code/config đã vào runtime và giới hạn thanh toán/đơn cũ. Không commit/push/merge.

## Tiêu chí hoàn thành

- [x] Booking mới theo cấu hình chuẩn luôn VIETQR_V1/inspectionRequired cho cả 2 Owner, không nhận workflow/status từ form.
- [x] Owner xác nhận thành công mở ngay BEFORE nhập được thông tin, lưu/gửi được; accept không tạo QR hoặc ghi nhận giao xe.
- [x] Legacy cũ, từ chối quyền, lỗi nghiệp vụ, duplicate confirm và chống đặt trùng không hỏng.
- [x] Pilot/kênh/kiểm chứng thanh toán chưa đủ vẫn từ chối ở lớp tài chính; không phát sinh giao dịch thật hoặc thay đổi đơn cũ.
- [x] Có evidence test + browser + cleanup và smoke app8080 sau restart.
