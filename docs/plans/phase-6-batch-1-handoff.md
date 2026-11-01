# CarRent — Handoff chat mới: Phase 6 / đợt 1 còn lại — P6.8 + P6.9

**Ngày bàn giao:** 2026-11-01. **Trạng thái:** lịch đợt đã được chủ dự án duyệt; P6.8/P6.9 chưa triển khai, chưa có kết quả test mới của hai task.

**Mục tiêu chat tiếp theo:** hoàn thành 100% checklist **P6.8 + P6.9** trong cùng đợt, gồm backend, UI, job nhắc, test/concurrency, browser và SDD/checkpoint riêng từng task; dừng trước P6.10. Dùng `superpowers:executing-plans` và các skill áp dụng. **Quy tắc đã được chủ dự án duyệt 2026-11-01:** Được chủ động dùng subagent cho công việc độc lập và review. Agent chính chịu trách nhiệm phân công, tránh sửa chồng file, tích hợp và kiểm chứng kết quả cuối. Các ghi nhận không dùng subagent ở checkpoint P6.7 là lịch sử, không phải giới hạn cho đợt mới.

## 1. Đọc trước và xác nhận workspace

1. Đọc [roadmap/lịch đã duyệt](README.md#cac-dot-con-lai-da-duyet), [Phase6](phase-6-inspection.md), [SDD](../SDD.md) §14.1/§14.29–14.32 và [handoff tài chính](phase-4-handoff.md). Checklist P6.8/P6.9 trong plan là điều kiện nghiệm thu, không thay bằng tóm tắt này.
2. CWD `C:\Users\Admin\Documents\hoc\Car`, PowerShell. Tại lúc bàn giao: branch `feat/phase6-before-submission`; HEAD `f8f12d81c30bafb66fa09ed7cebdd256d646151c`.
3. Chạy `git status --short`, `git branch --show-current`, `git rev-parse HEAD`; đọc diff hiện tại trước khi sửa. **P6.4–P6.7 đã triển khai nhưng chưa commit**, gồm nhiều file tracked sửa và `RentalPaymentHandler.java`/test untracked. Tiếp tục trên working tree này; không checkout/reset/clean hoặc dựng lại chỉ từ HEAD/main.
4. Bảo toàn toàn bộ thay đổi hiện có, đặc biệt thay đổi riêng của người dùng trong `docs/plans/phase-0-init.md`. Chụp hash/diff/HEAD/index và baseline DB/private files trước đợt mới; không dùng kết quả bảo toàn của P6.7 thay kiểm tra mới.

## 2. Tiến độ và nền đã có

- **62/86 task hoàn thành, còn24**: Phase6 còn6 (P6.8–P6.13), Phase8 còn8, Phase5 còn9, P4.2-A còn1. Trong45task mới đã đóng21. Chỉ cập nhật số sau khi từng task đạt đủ checklist; cả P6.8/P6.9 đạt thì64/86, còn22.
- P6.1–P6.7 đã đóng: schema/model, giá30/70/policy hủy, draft/ảnh private, Owner accept/BEFORE/addendum/dispute, Customer đồng ý + advance, apply/expiry/late advance, Owner hợp đồng/handover.
- P6.7 đã có Owner handover atomic; giữ `CONFIRMED` và suy ra Đang thuê từ `vehicle_received_at`; Owner phải ACTIVE/đúng chủ, BEFORE accepted, đủ30%, ngày Việt Nam trong `[startDate,endDate)`, không custody khác. Actor/time/event/notify một lần, retry idempotent.
- Manual complete cho `VIETQR_V1` đã bị chặn; legacy giữ hành vi riêng. Paid cancellation đang bị chặn có chủ đích chờ P6.11, không mở trong đợt này.
- `InspectionService` có draft/upload/delete/private streaming và guard AFTER sau handover; **chưa có submitAfter/confirmReturn/applyBalance hoặc RentalDeadlineJob**. `booking/RentalPaymentHandler.java` hiện chỉ xử lý `BOOKING_ADVANCE`, không nằm trong package payment.
- Các cột `after_reminder_sent_at`, `vehicle_returned_at`, `balance_overdue_notified_at` và mô hình nghĩa vụ đã có nền; đối chiếu source/schema trước khi quyết định bổ sung. Không thêm migration/abstraction nếu nền hiện có đủ dùng.

### Bằng chứng P6.7 để tham khảo, không phải lần chạy mới

- Full isolated clean verify **683/683 JUnit**,0failure/error/skip, JAR đóng gói; Chromium **32/32** desktop/mobile320/390/1440/no-JS/two-tabs/privacy/CSRF.
- Log/harness: `.scratch/phase6/p67/full-clean-verify.log`, `full-suite.exit`0, `browser-check.log`, `browser-check.exit`0, `browser-evidence.json`, `progress.md`, `verified-source-hashes.json`.
- App DB khi chốt P6.7 vẫn7bảng, fingerprint schema/toàn bộ rows không đổi; fixture DB/private files đã dọn. Không có human contributor review hoặc provider/live mới.
- Đã sửa lỗi browser phát hiện: summary custody phải nằm trong `<main>` Owner detail, không ở sidebar navigation. Giữ regression này.
- `.scratch/phase6/p67/finish-docs.py` **đã chạy, không idempotent; không chạy lại**. `final-audit.py`/source-hash manifest là kiểm tra checkpoint P6.7, có thể không còn phù hợp sau cập nhật tài liệu/đợt mới. Tạo evidence riêng `.scratch/phase6/p68-p69/`, không ghi đè bằng chứng cũ.

## 3. P6.8 — gửi AFTER và nhắc Owner

**Interface theo plan:** `InspectionService.submitAfter(Long bookingId, actor)`; `RentalDeadlineJob`.

- Chỉ đúng Customer ACTIVE của booking đã `vehicle_received_at` mới gửi; dùng validation/ảnh private nền hiện có. Report/ảnh/text sau submit bất biến; lưu `submittedAt`, event và notify Owner atomic. Lỗi notify phải rollback.
- Sau handover được gửi sớm/đúng ngày/trễ; chưa handover phải bị chặn. Không lấy ngày dự kiến làm bằng chứng nhận/trả thực tế.
- Không set `vehicle_returned_at`, không tạo BALANCE, không complete hoặc tính phụ phí từ notes/km/energy khi gửi AFTER.
- Từ `submittedAt +1giờ`, nếu chưa nhận lại: atomic ghi `after_reminder_sent_at` và nhắc Owner/thông báo Admin một lần. DB là nguồn công việc bền vững; restart quét bù, reload không đổi mốc.
- Hoàn thiện UI so sánh BEFORE/AFTER, trạng thái chờ Owner nhận lại; lỗi form giữ input và text được escape. Không hoãn UI/reminder sang P6.12.

## 4. P6.9 — Owner nhận lại xe và tạo nghĩa vụ70%

**Interface theo plan:** `BookingService.confirmReturn(Long bookingId, ReturnReceiptForm form, actor)`; form chỉ `decision` và `note`, không bind actor/time/amount.

- Cần đúng Owner ACTIVE và AFTER đã gửi; Admin không thay Owner xác nhận nhận thực tế.
- `RECEIVED_ACCEPTED`: ghi thời điểm trả xe và AFTER `ACCEPTED`. `RECEIVED_DISPUTED`: ghi thời điểm trả xe và AFTER `DISPUTED`, note bắt buộc, lưu evidence bất đồng.
- `NOT_RECEIVED`: không set `vehicle_returned_at`, không tạo BALANCE hoặc deadline trả70%.
- Mutation vào `CarCommandExecutor` trước transaction. Khóa current **Car → Booking → tạo/khóa BALANCE obligation → AFTER report**, revalidate dưới khóa. Không dùng entity snapshot cũ của REPEATABLE_READ làm quyết định.
- Với nhận thực tế: timestamp trả xe + obligation BALANCE + `dueAt = vehicle_returned_at +1giờ` + notify Customer cùng commit. Report lỗi hoặc notify lỗi phải rollback toàn bộ.
- Bấm lại không tạo nghĩa vụ, timestamp hoặc notification mới. Không chờ Customer mở trang mới bắt đầu deadline.
- Giữ startDate/endDate/tổng tiền snapshot khi trả sớm/trễ/bất đồng. Đã trả giải phóng custody vật lý; chính sách overlap theo ngày vẫn áp dụng.
- UI phân biệt chưa nhận/đã nhận/bất đồng, số70% và hạn trả; ghi rõ trạng thái thanh toán thực tế. Apply70%, QR BALANCE và auto-COMPLETED thuộc P6.10.

## 5. Phạm vi file và kiểm chứng

**Đọc/sửa theo nhu cầu, tái sử dụng pattern hiện có:**

- `src/main/java/com/carrent/inspection/{InspectionService,InspectionController,InspectionView,BookingInspectionRepository,InspectionEventAction}.java` và repositories ảnh/event.
- `src/main/java/com/carrent/booking/{BookingService,BookingRepository,BookingController,OwnerBookingController,RentalPaymentHandler}.java`; tạo `ReturnReceiptForm.java`, `RentalDeadlineJob.java` khi cần theo plan. Handler advance chỉ sửa nếu tích hợp thực sự đòi hỏi, không thêm xử lý BALANCE sớm.
- `src/main/java/com/carrent/payment/{PaymentObligationService,PaymentObligationRepository,PaymentLocks,PaymentMaintenanceJob}.java` và cấu hình job/queue để tái sử dụng đúng quyền, nguồn thu Owner và deadline bền vững. Provider HTTP luôn ngoài transaction/Car queue.
- `src/main/java/com/carrent/notification/NotificationService.java`; templates `inspection/detail.html`, `booking/detail.html`, `owner/booking-detail.html` và các fragments/list liên quan.
- Tests hiện tại ở `src/test/java/com/carrent/payment/`, gồm `BeforeInspectionTest`, `BeforeAcceptanceTest`, `RentalAdvanceTest`, `RentalHandoverTest`; tái sử dụng fixture/pattern, bổ sung test AFTER/return/deadline có ý nghĩa. Tên file test mới chọn theo convention thực tế, không cần di chuyển test cũ.

**Trình tự trong đợt:** đọc checklist/source → test RED/GREEN cho P6.8 → P6.9 → kiểm chứng tích hợp reminder/return/receipt và tự review → build/test/browser source cuối → cập nhật tài liệu/checkpoint riêng. Không kết thúc phiên chỉ vì P6.8 đã đạt trong khi P6.9 còn việc.

**Các gate bắt buộc:**

- Quyền/ACTIVE/current actor, IDOR/CSRF, chưa handover, thiếu report/ảnh/trường/note, immutable evidence/private images/XSS, retry không nhân đôi, legacy không đổi.
- Clock biên đủ1giờ; restart quét bù; reminder/return race; queue reject chưa được đánh dấu đã làm; reload không reset. Kiểm chứng job tự quét trong fixture khi chạy scheduler, không chỉ gọi processor trực tiếp rồi tuyên bố restart/job pass.
- MySQL hai connection thật cho submit/return lặp, return-vs-receipt và reminder/return; notify/report failure rollback timestamp/obligation/event/notice. Không lấy concurrency test mock thay khóa DB thật.
- Full clean verify bằng `.\mvnw.cmd '-Dmaven.compiler.useIncrementalCompilation=false' clean verify`, JAR. Theo pattern P6.7 có thể dùng POM/output/schema tạm riêng tránh IDE ghi `target`; vẫn chạy full source thật, ghi chính xác lệnh và kết quả.
- Browser trên HTTP ứng dụng fixture thật: Customer gửi AFTER → Owner nhận/bất đồng/NOT_RECEIVED; BEFORE/AFTER comparison, refresh/two-tabs/no-JS cần thiết,320/390/1440, lỗi giữ form và quyền ảnh. Cache/stub tài nguyên ngoài nếu cần phải ghi rõ; không stub endpoint nghiệp vụ để tuyên bố flow pass.
- Chụp hash/fingerprint trước/sau để chứng minh DB ứng dụng, dữ liệu legacy/seed, ảnh và file riêng không bị thay đổi; dọn fixture DB/private files/port, không dọn dữ liệu ứng dụng.
- Cập nhật `docs/SDD.md`, `docs/plans/phase-6-inspection.md`, `docs/plans/README.md`, `docs/plans/team-assignment.md`; ghi kết quả thật, hạn chế, review là self-review nếu chưa có người review khác. Không suy đạt task từ số test hoặc từ plan cũ.

## 6. Giới hạn đã chốt

Không migrate DB ứng dụng; `ddl-auto=none`, DB-first `scriptDB.sql`. Không bật workflow/provider/scheduler trên ứng dụng, restart app8080, stage/commit/push hoặc giao dịch tiền thật. Scheduler chỉ bật trong fixture cô lập khi kiểm chứng. P4.2-A còn hoãn; không coi offline pass là provider/live ready. Không sửa mật khẩu seed hoặc file riêng phase0. Không mở rộng sang P6.10/P6.11/P6.12/P6.13; chỉ chuẩn bị cấu trúc tối thiểu thực sự cần cho checklist P6.8/P6.9.

## 7. Prompt dán vào chat mới

```text
Implement CarRent Phase6 / đợt1 còn lại — P6.8 + P6.9 theo lịch tôi đã duyệt ngày2026-11-01.
Repo C:\Users\Admin\Documents\hoc\Car. Đọc docs/plans/phase-6-batch-1-handoff.md, roadmap và checklist hai task trước.
Tiếp tục working tree hiện tại trên feat/phase6-before-submission, HEAD f8f12d8; P6.4–P6.7 đã làm nhưng chưa commit, không reset về main/HEAD.
Mục tiêu hoàn thành100% cả hai checklist: AFTER/evidence immutable, reminder1giờ có restart recovery, Owner nhận/bất đồng/NOT_RECEIVED, BALANCE70% + dueAt + notify atomic/idempotent.
Làm đủ UI, test hai connection MySQL, full clean verify, browser và cập nhật SDD/checkpoint riêng từng task; bảo toàn mọi thay đổi hiện có và docs/plans/phase-0-init.md.
Chưa migrate DB ứng dụng, bật workflow/provider/scheduler ứng dụng, restart8080, chuyển tiền thật hoặc stage/commit/push. Dừng trước P6.10.
Được chủ động dùng subagent cho công việc độc lập và review. Agent chính chịu trách nhiệm phân công, tránh sửa chồng file, tích hợp và kiểm chứng kết quả cuối.
```
