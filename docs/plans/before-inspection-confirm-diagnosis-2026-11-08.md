# Chẩn đoán Owner xác nhận đơn nhưng không mở BEFORE

Ngày **2026-11-08**. Phạm vi: đọc code, DB và HTTP của app local; chạy đối chứng trên DB fixture riêng. Chưa sửa code, đổi workflow của booking, bật flags hoặc restart8080. Kế hoạch Payment settings/S1 giữ nguyên.

## Kết quả tái hiện

- App localhost:8080 đang có listener PID 480, main class CarRentApplication, profile CLI **local**.
- Đơn mới nhất kiểm tra **1698**, Owner user 2 và Customer user 4: status CONFIRMED, workflow_version LEGACY, inspection_required=0, owner_accepted_at/before_accepted_at NULL.
- Đơn ngay trước đó 1697 cũng LEGACY nhưng đã CANCELLED. Hai đơn cũ 1627/1628 dùng VIETQR_V1, inspection_required=1 và có owner_accepted_at.
- Đăng nhập local Owner1 rồi chỉ GET trang chi tiết 1698: HTTP 200, không có link `/bookings/1698/inspections/BEFORE`. GET thẳng route đó: HTTP 404.
- GET chi tiết 1628: HTTP 200, có link BEFORE. Đây là đối chứng điều kiện hiển thị, không khẳng định đơn đã CANCELLED vẫn được sửa BEFORE.
- Không gửi POST confirm/cancel/handover, không sửa đơn thật hoặc chuyển tiền. Login chỉ tạo phiên xác thực; không lưu password/cookie trong report.

Loop tái hiện: `python -X utf8 .scratch/before-confirm-diagnosis-20261108/reproduce.py --expect-before`. Assertion “CONFIRMED booking 1698 has no BEFORE entry” thất bại đúng triệu chứng. Đây là tái hiện kỳ vọng của người dùng trên đơn LEGACY, không phải một regression test đã được sửa xanh.

## Chuỗi nguyên nhân trong code

1. `BookingService.createBooking()` chọn luồng mới bằng `rentalWorkflowEnabled && pilot.allowsRentalOwner(ownerId)`. False thì ghi LEGACY/inspection_required=false. Cờ `carrent.rental.workflow.enabled` mặc định false; cấu hình bật workflow/advance/balance và pilot IDs nằm trong profile sepay-local.
2. `BookingService.confirmBooking()` với LEGACY chuyển thẳng CONFIRMED. Với VIETQR_V1, giữ PENDING và ghi owner_accepted_at để Owner chuẩn bị BEFORE.
3. Link “Lập / xem biên bản trước thuê” trong `owner/booking-detail.html` chỉ có khi workflow VIETQR_V1, inspection_required=true và owner_accepted_at khác NULL. Đơn 1698 không đạt ba điều kiện này.
4. `InspectionService.checkSupported()` từ chối LEGACY hoặc không inspection_required bằng NotFound, giải thích HTTP 404 khi mở thẳng BEFORE.
5. `OwnerBookingController.confirm()` luôn redirect về `/owner/bookings/{id}`; code chưa tự chuyển thẳng sang form BEFORE, kể cả VIETQR_V1. Với luồng mới, người dùng hiện phải bấm link trên trang chi tiết.

Kết luận trực tiếp: **đơn mới đang đi theo luồng LEGACY nên không có BEFORE; chức năng BEFORE không bị mất**. Runtime profile local khác với profile sepay-local dùng cho pilot thu tiền trước đó. Không đọc được trực tiếp giá trị boolean trong JVM qua actuator; không coi source default là kết quả introspection runtime, nhưng dữ liệu persisted và HTTP đã xác nhận nhánh thực tế là LEGACY.

## Đối chứng bằng fixture

Source bốn file liên quan ở root và worktree Payment settings khớp hash. Chạy test trên worktree cô lập với provider offline và schema UUID riêng:

- BeforeInspectionTest: Owner chấp nhận VIETQR giữ PENDING/đặt timestamp; HTTP form BEFORE và submit/feedback hoạt động.
- FinalRentalJourneyTest: admission default-off/pilot/handler được kiểm tra; hành trình mua gói → BEFORE → 30/70 → hoàn tất dùng đúng workflow.

Kết quả mới: **4/4 tests, 0 failure/error/skip**, Maven exit 0. Evidence `.scratch/before-confirm-diagnosis-20261108/fixture-controls.json` và `.log`. Không dùng kết quả này làm bằng chứng đã sửa cấu hình runtime hoặc đã sửa redirect.

## Cách giải quyết theo đúng mục tiêu

- Muốn chạy nghiệp vụ BEFORE: cấu hình đúng workflow/advance/balance, pilot Owner/Admin và handler trên bản chạy đã duyệt; profile sepay-local hiện có những cờ này nhưng cũng bật inbox/deadline nên thay profile là thao tác vận hành cần xem đủ phạm vi. Giữ PayOS payout disabled khi verifier/capability chưa đạt.
- Sau thay đổi runtime, đơn mới của Owner pilot phải lưu VIETQR_V1/inspection_required=1. Đơn 1698 đã LEGACY không tự đổi vì restart; không update trực tiếp hai cột để ép vào state machine khác. Đơn thật cần quy trình xử lý riêng theo trạng thái/nghĩa vụ hiện có.
- Muốn sau nhấn chấp nhận tự mở form: đổi redirect chỉ cho **VIETQR_V1 thành công**, giữ failure/LEGACY về trang chi tiết; viết regression POST-confirm → redirect BEFORE trước khi sửa. Đây là thay đổi UX bổ sung, không giải quyết việc tạo booking LEGACY nếu runtime vẫn sai chế độ.

Chưa thực hiện hai thay đổi trên trong lượt chẩn đoán này.
