# CarRent — Phase 6: Thuê xe, hợp đồng và biên bản — Implementation Plan

> **For agentic workers:** Dùng superpowers:executing-plans theo task; không tự stage/commit/push, không tick gate chưa chạy. Chỉ dùng subagent khi được yêu cầu.

**Ngày cập nhật:** 2026-10-31. **Trạng thái:** P6.1 schema/model và P6.2 policy/guard đã hoàn thành; full 456 test đạt. P6.3–P6.13 chưa triển khai; workflow mới chưa bật trên DB ứng dụng.

**Goal:** Thực hiện đầy đủ BEFORE → trả trước 30% → Owner xác nhận hợp đồng/giao xe → AFTER/trả xe → 70%, cùng hủy/hoàn và cảnh báo đã chốt.

**Architecture:** BookingService giữ chuyển trạng thái và timestamps; InspectionService giữ nháp/ảnh/báo cáo bất biến; RentalPaymentHandler nối receipt của Phase 4 vào booking. Tiến độ giao xe suy ra từ dữ liệu vật lý, không cần Customer xác nhận nhận xe lần hai. Các mutation cùng carId qua Queue trước khi mở transaction.

**Tech Stack:** Spring MVC/Thymeleaf/JPA, MySQL, Java ImageIO cho JPEG/PNG private; Clock và payment/queue từ Phase 4/7.

**Spec:** [README](README.md) mục 3, 5, 6; thay FR-16/BR-11–BR-15 cũ khi đồng bộ SDD trước cutover.

**Phân công:** `huy69332` phụ trách toàn bộ task mới; `thanhmit` review. Xem [phân công nhóm](team-assignment.md).

## Global Constraints

- Sau Phase 7/4, trước Phase 8/5. Không phụ thuộc Admin dashboard đã hoàn thiện.
- Chỉ ngày nhận/trả; giờ hẹn ngoài ứng dụng; tổng tiền snapshot theo chênh lệch ngày, không phụ phí.
- Owner xác nhận hợp đồng + giao xe là mốc Đang thuê và chặn hủy cả hai bên. Không giữ thao tác Customer xác nhận nhận xe của plan cũ.
- Nhận đủ 30% trước giao; không có cash/mock receipt cho workflow mới. Luồng legacy giữ hành vi lịch sử riêng.
- Đã trả và đủ 100% → tự hoàn thành, không cần Owner bấm complete và không chặn vì AFTER có bất đồng.
- Hạn trả 70%: 1 giờ. Hạn QR: 15 phút. Nhắc Owner nhận lại sau gửi AFTER: 1 giờ. Nợ/quá hạn chỉ cảnh báo.
- Hủy Customer: miễn phí trong 1 giờ; sau đó hơn 3 ngày lịch phí 10% advance, còn lại kể cả sau ngày nhận khi chưa giao phí 50%. Owner hủy hoàn 100%.
- Lưu bằng chứng/actor/time riêng tư; nội dung sau submit bất biến, không public ảnh qua /uploads.
- Khóa, gate nhà cung cấp, thời gian/rounding/backlog theo README. Không triển khai cộng dồn hoặc hoàn lệch tiền advance đang hoãn.

## Map file

Đường dẫn Java tương đối `src/main/java/com/carrent/`:

- Modify `booking/Booking.java`, `BookingStatus.java`, `BookingRepository.java`, `BookingService.java`, `BookingController.java`, `OwnerBookingController.java`.
- Create `booking/RentalPolicy.java`, `CancellationQuote.java`, `RentalPaymentHandler.java`, `RentalDeadlineJob.java`, `HandoverForm.java`, `ReturnReceiptForm.java`.
- Create `inspection/BookingInspection.java`, `InspectionImage.java`, `InspectionEvent.java`, các enums/repositories, `InspectionService.java`, `InspectionController.java`, `InspectionForm.java`, `InspectionView.java`.
- Create `storage/InspectionStorageService.java`; private directory `private-uploads/inspections/`.
- Modify templates `booking/detail.html`, `booking/list.html`, `owner/booking-detail.html`, `owner/bookings.html`; create `inspection/form.html`, `inspection/detail.html`, `fragments/inspection-summary.html`.
- Create migration dự kiến `docs/migrations/2026-10-27-02-rental-workflow.sql`; update `scriptDB.sql`.
- Test `src/test/java/com/carrent/booking/RentalPolicyTest.java`, `RentalLifecycleTest.java`, `RentalConcurrencyTest.java`, `RentalDeadlinesTest.java`; `inspection/InspectionPrivacyTest.java`.

## Hợp đồng dữ liệu

- Booking: workflow_version default LEGACY; inspection_required default false; owner_accepted_at, before_accepted_at, hold_expires_at, advance_amount, balance_amount, advance_paid_at, contract_confirmed_by/at, vehicle_received_at, vehicle_returned_at, cancelled_at, cancellation_fee, after_reminder_sent_at, balance_overdue_notified_at.
- Giữ PENDING/CONFIRMED/REJECTED/CANCELLED/COMPLETED; thêm EXPIRED. VIETQR_V1: PENDING trước đủ advance; CONFIRMED sau đủ advance; tiến độ thuê từ timestamps. Legacy không có timestamps giả.
- Report unique (booking_id, phase BEFORE/AFTER), phase, author, status DRAFT/SUBMITTED/ACCEPTED/DISPUTED/RESOLVED, conditionNote/visibleDamage/odometerKm/energyPercent/accessoriesNote, submittedAt. BEFORE accepted chỉ là đồng ý báo cáo, không ghi vehicle_received_at.
- Ảnh private FK report; event append-only có actor/action/note/time. Không endpoint sửa/xóa event hoặc ảnh gốc đã submit. AFTER bất đồng có lý do, vẫn cho complete sau khi đã trả/đủ tiền.
- HandoverForm chỉ có boolean contractSigned. ReturnReceiptForm có decision RECEIVED_ACCEPTED/RECEIVED_DISPUTED/NOT_RECEIVED và note; không bind timestamp/amount/identity.

### Task P6.1 — Migration giữ legacy và mapping

**Phụ trách:** `@huy69332` · **Review:** `@thanhmit`.

- [x] Viết preflight/migration thêm trường Booking và 3 bảng inspection theo contract; CHECK vehicle_returned_at chỉ có khi vehicle_received_at có và không sớm hơn. Contract actor/time phải đi cùng vehicle_received_at; timestamp mới dùng Instant/UTC DATETIME(6), timestamp legacy giữ nguyên.
- [x] Thêm EXPIRED vào schema/BookingStatus; kiểm chứng conflict query hiện có đã explicit PENDING/CONFIRMED, chỉ các trạng thái giữ lịch hiện hành, không để EXPIRED/CANCELLED/COMPLETED chặn lịch. Giữ khoảng nửa mở và current read.
- [x] Không backfill đơn cũ thành VIETQR_V1 hoặc dựng biên bản quá khứ. Đồng bộ bootstrap, validate mapping trên DB kiểm chứng.
- [x] Viết RentalLifecycleTest cho migration/data cũ, source contract sai phải fail; run test trước/sau mapping để có RED/GREEN, giữ baseline seed.

**Bằng chứng:** [checkpoint P6.1](#checkpoint-p61--2026-10-30), `RentalLifecycleTest` 38 ca và `RentalModelTest` 2 ca; cả hai nằm trong package test `com.carrent.payment` để tái dùng fixture MySQL disposable. Không áp dụng migration lên DB ứng dụng hoặc ghi nhận human review.

### Task P6.2 — Policy giá, phần 30/70 và phí hủy

**Phụ trách:** `@huy69332` · **Review:** `@thanhmit`.

**Interfaces:**

```java
record CancellationQuote(long fee, long refund) {}
// RentalPolicy: advance(total), balance(total),
// cancellation(totalAdvance, paidAt, cancelledAt, startDate, ownerCancels).
```

- [x] Viết test pure policy với Clock/Instant: 30/70 cộng đúng tổng, không overflow; theo ngày lịch Asia/Ho_Chi_Minh; 1 giờ đúng biên miễn phí; 3 ngày đúng biên 50%; 4 ngày 10%; sau ngày nhận chưa giao 50%; Owner 0%.
- [x] Implement lõi lịch và grace theo thứ tự:

```java
if (ownerCancels || !cancelledAt.isAfter(paidAt.plus(Duration.ofHours(1)))) {
    return new CancellationQuote(0, advance);
}
long days = ChronoUnit.DAYS.between(
    cancelledAt.atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toLocalDate(), startDate);
int percent = days > 3 ? 10 : 50;
// Tính fee bằng BigDecimal/BigInteger chính xác, làm tròn xuống; refund = advance - fee.
```

- [x] Guard không được hủy sau handover nằm ở service khóa current state, không chỉ policy/UI.
- [x] Run `.\mvnw.cmd '-Dtest=RentalPolicyTest' test`; ví dụ tổng 2.000.000, advance 600.000: hoàn 600.000/540.000/300.000 tương ứng.

**Bằng chứng:** [checkpoint P6.2](#checkpoint-p62--2026-10-31). `RentalPolicyTest` 42 ca; `RentalCancellationGuardTest` 7 ca dùng fixture MySQL disposable trong package payment. Policy được kiểm chứng độc lập; integration tiền thật thuộc task rental sau, không áp phí vào legacy.

### Task P6.3 — Nháp và ảnh private

**Phụ trách:** `@huy69332` · **Review:** `@thanhmit`.

**Interfaces:** InspectionService.saveDraft(bookingId, phase, InspectionForm, actor), addImages(...), deleteDraftImage(...), getView(...); storage chỉ trả Resource qua controller có auth.

- [ ] Tạo nháp bằng POST; GET không insert. BEFORE chỉ Owner của xe, AFTER chỉ Customer sau handover; legacy không bị ép có report.
- [ ] Tái sử dụng yêu cầu ảnh đã duyệt: JPEG/PNG decode thật, ≤5 MiB/ảnh, ≤20 triệu pixel, report 4–10 ảnh khi submit; upload từng đợt 1–4 ảnh. Draft cho thiếu trường/ảnh.
- [ ] Form giữ 5 trường: ghi chú tình trạng, hư hỏng thấy được, km, mức nhiên liệu/năng lượng 0–100, phụ kiện. Validate numeric/length ở draft; submit bắt buộc đầy đủ.
- [ ] File UUID trong private storage, auth/membership mỗi lần stream; không phục vụ tĩnh qua /uploads, chặn path traversal và image ID thuộc report khác.
- [ ] DB rollback không để file mồ côi do upload lỗi; xóa file sau commit khi hợp lệ. Test InspectionPrivacyTest cho tác giả, thành viên sau submit, user ngoài, nội dung giả ảnh, oversize và rollback.

### Task P6.4 — Owner chấp nhận và gửi BEFORE

**Phụ trách:** `@huy69332` · **Review:** `@thanhmit`.

**Files:** BookingService.confirmBooking, InspectionService.submitBefore; OwnerBookingController/InspectionController.

- [ ] VIETQR_V1: Owner accept ghi owner_accepted_at, vẫn PENDING; không gọi đó là đã cho thuê. Legacy confirm giữ logic cũ.
- [ ] Submit BEFORE kiểm tra Owner/booking còn hiệu lực, đủ ảnh/trường; khóa report/ảnh, chuyển SUBMITTED và notify Customer cùng transaction.
- [ ] Từ submit cấm sửa/xóa nội dung/ảnh gốc. Có thể append ghi chú trước Customer đồng ý; nội dung đã được đồng ý được giữ nguyên làm bằng chứng.
- [ ] Customer chưa đồng ý có thể ghi bất đồng và không đi tiếp thanh toán; sau trao đổi có thể chấp nhận báo cáo/ghi chú đang có. Không cần xây Admin phán quyết hoặc tự tính bồi thường để mở checkout.
- [ ] Test submit lặp, sai Owner, user INACTIVE, cancel-vs-submit; không tạo handover/payment chỉ vì gửi BEFORE.

### Task P6.5 — Customer đồng ý BEFORE, mở giữ chỗ 15 phút

**Phụ trách:** `@huy69332` · **Review:** `@thanhmit`.

**Interface:** BookingService.acceptBefore(Long bookingId, CustomUserPrincipal actor).

- [ ] Khóa Car → Booking, kiểm tra Customer/Owner accept, tạo hoặc khóa obligation ADVANCE business key BOOKING:id:ADVANCE và intent trước khi khóa report. Sau đó đọc report current, kiểm tra hợp lệ/chưa đồng ý và ghi accepted_at + BEFORE ACCEPTED. Nếu report không hợp lệ thì rollback cả intent/obligation, không để đảo thứ tự khóa.
- [ ] hold_expires_at=thời điểm tạo intent+15 phút, không bắt đầu lại khi reload; toàn bộ đồng ý báo cáo và tạo yêu cầu trả trước cùng transaction.
- [ ] UI hiển thị QR, số trả trước/số còn lại và deadline; không gọi hành động đồng ý BEFORE là đã nhận xe.
- [ ] Hủy đặt xe tại trang trả trước: cancelBooking kết thúc intent và booking, giải phóng lịch, trang kết quả đã hủy. Không chỉ thoát trang rồi vẫn giữ lịch.
- [ ] Test hai tab/POST lặp chỉ một nghĩa vụ/hold; fail tạo intent rollback accepted_at để không kẹt đơn.

### Task P6.6 — Apply advance, expiry và webhook chậm

**Phụ trách:** `@huy69332` · **Review:** `@thanhmit`.

**Interface:** RentalPaymentHandler cho BOOKING_ADVANCE; BookingService.applyAdvance(Long receiptId), expireHold(Long bookingId).

- [ ] Implement PaymentDomainHandler.lockSubject(bookingId) khóa current Car/Booking; core apply/expiry chỉ khóa tiền sau bước này. Handler apply dùng khóa aggregate đã có, không lồng queue hoặc lấy lại khóa theo thứ tự khác.

- [ ] Receipt đúng collector/code/amount và intent còn hợp lệ: cấp CONFIRMED, ghi ngân hàng creditedAt làm mốc advancePaidAt. Không ghi đã ký hợp đồng hoặc đã nhận xe.
- [ ] Thực sự đến sau hạn hoặc sau cancel: tạo refund 100%, không phục hồi đơn. Lệch tiền advance → review theo backlog, không cộng dồn.
- [ ] Job expiry và receipt áp dụng dưới cùng Car queue/DB locks. Nếu expiry đã commit thì giữ EXPIRED và hoàn đủ; nếu lịch đã giữ cho B tuyệt đối không hủy B để giành lại xe cho A.
- [ ] Khi tiền đúng hạn nhưng xác nhận chậm: reason SYSTEM_CONFIRMATION_DELAY; UI ghi hệ thống xác nhận chậm, không quy Customer trả muộn.
- [ ] Hai connection chạy cả hai thứ tự expiry/apply và booking B/apply A; assert không hai confirmed cùng khoảng, không vừa cấp lịch vừa tạo hoàn toàn bộ cùng receipt.
- [ ] Hết hạn ẩn QR ở cả UI lẫn server view; QR đã quét ngoài app vẫn có thể chuyển nên late receipt không bị bỏ qua.

### Task P6.7 — Owner xác nhận hợp đồng và giao xe

**Phụ trách:** `@huy69332` · **Review:** `@thanhmit`.

**Interface:** BookingService.confirmHandover(Long bookingId, boolean contractSigned, CustomUserPrincipal actor).

- [ ] Viết guard test false checkbox/sai Owner/chưa đủ 30%/BEFORE chưa accepted/ngoài khoảng ngày/xe còn ở booking khác đều bị chặn.
- [ ] Khi hợp lệ, ghi contract_confirmed_by/at và vehicle_received_at bằng server, atomic dưới khóa Car/Booking. Handover chỉ một lần; bấm lại không tạo event/notify mới.
- [ ] UI checkbox và nút “Xác nhận đã giao xe”; Owner chịu trách nhiệm xác nhận giao thực tế. Ngay sau commit hiển thị Đang thuê và ẩn nút hủy hai bên.
- [ ] Cancel endpoint cũng chặn backend sau handover; test race cancel-vs-handover cả hai thứ tự. Không CANCELLED+vehicle_received_at.
- [ ] Custody check: booking trước nhận rồi/chưa trả chặn handover mới, kể cả lịch cũ đã hết; không suy ra xe đã trả từ listing status hoặc thiếu PAID.

### Task P6.8 — Customer gửi AFTER và nhắc Owner

**Phụ trách:** `@huy69332` · **Review:** `@thanhmit`.

**Interfaces:** InspectionService.submitAfter(Long bookingId, actor); RentalDeadlineJob.

- [ ] Chỉ Customer đúng booking đã handover mới tạo/gửi AFTER; giữ ảnh/text immutable sau submit, notify Owner. Không tự ghi vehicle_returned_at.
- [ ] Đủ 1 giờ từ submittedAt mà chưa vehicle_returned_at: atomic ghi after_reminder_sent_at + nhắc Owner/thông báo Admin một lần. Restart quét bù, reload không reset mốc.
- [ ] UI so sánh BEFORE/AFTER và trạng thái chờ nhận lại; chặn phí phát sinh từ note hoặc km/energy khác.
- [ ] Test gửi sớm/đúng ngày/trễ đều được sau handover; chưa nhận thực tế không được gửi AFTER. Lỗi notify rollback event/report state.

### Task P6.9 — Owner nhận lại xe và tạo nghĩa vụ 70%

**Phụ trách:** `@huy69332` · **Review:** `@thanhmit`.

**Interface:** BookingService.confirmReturn(Long bookingId, ReturnReceiptForm form, actor).

- [ ] RECEIVED_ACCEPTED → vehicle_returned_at + AFTER ACCEPTED. RECEIVED_DISPUTED → vehicle_returned_at + AFTER DISPUTED và note bắt buộc. NOT_RECEIVED không ghi vehicle_returned_at.
- [ ] Với quyết định nhận thật, khóa Car/Booking rồi tạo hoặc khóa obligation BALANCE trước khi khóa AFTER report; revalidate report dưới khóa, ghi vehicle_returned_at và dueAt=vehicle_returned_at+1 giờ, notify Customer cùng commit. Không cần Customer mở trang mới bắt đầu deadline. Report sai thì rollback cả nghĩa vụ; NOT_RECEIVED không tạo BALANCE.
- [ ] Có AFTER đã gửi và đúng Owner mới được thao tác; không cho Admin thay Owner xác nhận vật lý. Bấm lại không tạo nghĩa vụ/nhắc nợ mới.
- [ ] Không đổi start/end/total khi trả sớm/trễ hoặc có bất đồng. Xe trả rồi không còn outstanding custody; conflict theo ngày vẫn theo policy tìm xe hiện hành.
- [ ] Test return-vs-receipt/repeated return, rollback nghĩa vụ/notify/timestamp đồng thời.

### Task P6.10 — Thanh toán 70%, quá hạn và hoàn thành

**Phụ trách:** `@huy69332` · **Review:** `@thanhmit`.

**Interface:** RentalPaymentHandler BOOKING_BALANCE; BookingService.applyBalance(Long receiptId).

- [ ] QR BALANCE hết 15 phút hoặc Customer bấm hủy: chỉ kết thúc attempt, không hủy booking/không xóa nợ/không reset dueAt. Cho POST tạo attempt mới.
- [ ] Receipt qua QR cũ vẫn trả đúng obligation nếu còn nợ; số vượt phần còn phải trả tạo refund EXCESS_BALANCE từ Owner; receipt sau khi đủ tiền hoàn như trả trùng.
- [ ] Receipt thấp hơn số cần trả: lưu review, không báo hoàn thành; tự động cộng dồn khoản thiếu chưa thuộc phạm vi phase này.
- [ ] Đã vehicle_returned_at và balance được thanh toán đủ → COMPLETED trong transaction apply. AFTER DISPUTED không chặn, ảnh/note giữ nguyên; ẩn nút Owner complete cho VIETQR_V1.
- [ ] Sau 1 giờ còn nợ: ghi balance_overdue_notified_at và thông báo Customer/Owner/Admin một lần. Không khóa tài khoản/chặn đặt mới/phạt hoặc refund advance.
- [ ] Test đủ tiền đúng/sau hạn, QR cũ, khoản trùng, dư, tab đóng, restart, dispute + paid vẫn complete; booking legacy không bị auto-complete mới.

### Task P6.11 — Customer/Owner hủy trước handover và tạo hoàn

**Phụ trách:** `@huy69332` · **Review:** `@thanhmit`.

**Files:** BookingService.cancelBooking, thêm cancelByOwner(id, reason, actor); RentalPolicy/RefundService.

- [ ] PENDING chưa nhận advance: cancel intent/booking, không tạo khoản hoàn giả. Receipt về sau tạo hoàn đủ.
- [ ] CONFIRMED chưa giao: Customer hủy dùng quote snapshot ở P6.2; Owner hủy hoàn đủ. CanceledAt/fee/reason và nghĩa vụ hoàn được ghi cùng transaction, chuyển tiền thực hiện ngoài transaction bởi Phase 4.
- [ ] Sau handover chặn cả hai bên; Owner phải dùng luồng return, không dùng cancel để xóa khoản nợ.
- [ ] Nút hủy cần hiển thị số hoàn/phí dự kiến và backend tính lại khi submit; không tin quote client.
- [ ] Test các biên 1 giờ/3 ngày/date đã qua, payout chờ recipient/funds; payment gốc vẫn là tiền đã nhận, không bulk update REFUNDED trước khi chuyển thành công.

### Task P6.12 — UI, quyền và job deadline tích hợp

**Phụ trách:** `@huy69332` · **Review:** `@thanhmit`.

- [ ] Timeline ba view Customer/Owner/Admin phân biệt Owner accept, BEFORE agreed, advance paid, hợp đồng/handover, AFTER, returned, balance paid, refunds.
- [ ] Trang checkout dùng Phase 4, lỗi/hết hạn/hủy/hoàn tách rõ; polling GET không chuyển trạng thái.
- [ ] Ảnh report chỉ thành viên đúng booking/Admin ACTIVE sau submit; nháp chỉ tác giả. Text escape, lỗi field giữ input, UI 320/390/1440px không tràn trang.
- [ ] Job 30 giây gọi dịch vụ domain qua queue; DB bền vững và mốc notified chống nhắc trùng. Tác vụ bị queue reject chưa được đánh dấu đã làm.
- [ ] Các entry point mới không được quên nối CarCommandExecutor; service vẫn giữ current DB read theo thứ tự khóa README.

### Task P6.13 — Gate rental mới và đồng bộ SDD

**Phụ trách:** `@huy69332` · **Review:** `@thanhmit`.

- [ ] Run `.\mvnw.cmd '-Dmaven.compiler.useIncrementalCompilation=false' clean verify`; RentalConcurrencyTest dùng hai kết nối thật, có rollback/notify failure/stale entity.
- [ ] E2E fixture: report → 30% → checkbox/handover → AFTER dispute → Owner received → 70% → COMPLETED, cùng các nhánh cancel/refund/late receipt.
- [ ] So baseline legacy/seed/private files; không tick từ kết quả plan cũ. Dữ liệu đã gửi không bị sửa để khớp kết quả test.
- [ ] Đồng bộ SDD/decisions: bỏ pickup unpaid, Customer final pickup, cash giả lập mới, Admin resolve bắt buộc để complete, mốc 72 giờ; thêm hợp đồng Owner và policy ngày lịch.
- [ ] Chưa bật workflow mới cho mọi user hoặc chạy demo đầy đủ trước Phase 8 và gate kênh thật. Chỉ fixture test mang VIETQR_V1 trong quá trình phát triển.

**Hoàn thành khi:** các mốc giao/nhận/tiền độc lập, 30/70 và mọi deadline có kiểm chứng, không mất tiền khi cancel/expiry/webhook chậm, không khóa quyền đặt/nhận đơn vì quá hạn, legacy giữ được lịch sử.


## Checkpoint P6.1 — 2026-10-30

**Phạm vi đã đóng:** migration/schema/model, không gộp P6.2/P6.3 hoặc bật luồng thuê mới. Phụ trách `huy69332`, reviewer được giao `thanhmit`; chưa ghi nhận human review.

| Yêu cầu | Bằng chứng |
|---|---|
| Migration có kiểm soát | `docs/migrations/2026-10-27-02-rental-workflow.sql`: backup marker, MySQL 8.0.16+/strict/InnoDB, contract 70 cột legacy/PK/unique/FK/CHECK/index, payment-core prerequisite, từ chối partial/reapply trước ALTER/CREATE dữ liệu |
| Giữ nguyên dữ liệu | Hash các cột gốc của 7 bảng legacy **và** dữ liệu 7 bảng core đã populate trước/sau migration bằng nhau; không backfill VIETQR_V1 hoặc dựng report quá khứ; 3 bảng mới rỗng |
| Schema/mapping | 16 cột Booking, EXPIRED, 3 entity/3 enum/3 repository inspection; FK RESTRICT, unique report/phase và storage key, CHECK custody/actor/money/numeric/submission/image/action/note; bootstrap cùng DDL |
| UTC và event | Instant/TIMESTAMP_UTC mới round-trip microsecond khi JVM America/Los_Angeles, JDBC Asia/Ho_Chi_Minh; timestamp legacy giữ nguyên; event ORM immutable, repo không expose delete/update |
| Availability | PENDING/CONFIRMED giữ lịch; EXPIRED/CANCELLED/REJECTED/COMPLETED giải phóng lịch; cả search Specification và locking repository được kiểm chứng, giữ khoảng nửa mở. Query production đã đúng nên không sửa thừa |
| Hồi quy | PaymentModel fixture áp dụng rental migration trước JPA validate; full clean verify 407/407, 0 failure/error/skip, JAR đóng gói; 38 lifecycle + 2 model mới và 367 hồi quy |
| Baseline ứng dụng | DB nguồn vẫn 7 bảng, fingerprint schema/toàn bộ rows giống nhau; file phase-0 của chủ dự án, fixture legacy cố định, sáu ảnh và toàn bộ seed statements giữ nguyên; DB tạm được dọn |

**Lệnh và log:** dùng POM kiểm chứng tại `.scratch/phase6/p61/verification-pom.xml` với source/resources gốc và output riêng để tránh IDE ghi `target`; không sửa POM dự án. Harness `FullSuite.java` cấp datasource bằng biến môi trường sang schema tạm và dọn sau cùng:

```powershell
java '@.scratch/phase6/p61/full-suite.args'
# Harness chạy: .\mvnw.cmd -f .scratch/phase6/p61/verification-pom.xml -o '-Dmaven.compiler.useIncrementalCompilation=false' clean verify
```

`full-clean-verify.log`: BUILD SUCCESS, 407 tests, 09:09 phút; `full-suite-runner.log`: exit=0 và disposable schema removed. RED ban đầu trong tool log xác nhận thiếu migration; `contract-red.log` bắt thiếu source/actor/image checks (có một lỗi setup nullable được sửa), `index-red.log` ghi lỗi setup drop index đang phục vụ FK, `index-red-fixed.log` xác nhận drift index chưa bị chặn. GREEN: `contract-green.log` 37 ca trước bổ sung index; bản cuối full suite có 38 ca lifecycle. `model-red.log` bắt thiếu interface Instant; `model-green.log` và full suite xác nhận mapping UTC/ORM immutable. Các cảnh báo deprecation và lỗi notification/transaction được inject trong test hồi quy không phải lỗi build.

**Handoff:** dùng canonical `vehicle_received_at`/`vehicle_returned_at` cho tất cả task sau; không tạo alias `handed_over_at`/`returned_at`. Actor/time hợp đồng được ghi atomic cùng thời gian giao. Báo cáo/ảnh chưa có service/controller; số ảnh, quyền truy cập, bất biến sau submit và domain locks ở P6.3 trở đi. App createBooking vẫn LEGACY/false. Đọc [migration runbook](../migrations/README.md) trước thay JAR trên DB hiện hữu; mapping mới cần schema mới dù workflow chưa bật. Không browser/UI mới, provider/network tiền thật, human review, stage/commit/push hoặc migration ứng dụng trong phiên này.

**Tiến độ:** 56/86, còn 30. Tiếp theo **P6.2 — Policy giá, phần 30/70 và phí hủy**; P4.2-A vẫn tạm hoãn. Nhánh local `feat/phase6-rental-schema`, thay đổi chưa commit.


## Checkpoint P6.2 — 2026-10-31

**Phạm vi hoàn thành:** cả 4 mục checklist P6.2: policy, validation/rounding/time, guard dưới khóa và kiểm chứng; không gộp P6.3/P6.11. Phụ trách `huy69332`, reviewer được giao `thanhmit`; chưa ghi nhận human review.

| Điều kiện | Triển khai và bằng chứng |
|---|---|
| Chia tiền chính xác | `RentalPolicy.advance(total)` làm tròn xuống 30% VND; `balance(total)=total-advance`. BigInteger nội bộ, long đầu vào/đầu ra; từ chối total <4 vì không đủ hai phần dương. Test tổng lẻ, mức nhỏ nhất và Long.MAX_VALUE; không đổi công thức tổng giá/ngày hiện có |
| Phí hủy | `CancellationQuote(fee, refund)`; Owner hoặc trong/đúng 1 giờ: phí 0. Sau đó >3 ngày lịch Việt Nam: 10%, còn lại 50%; fee làm tròn xuống, refund=advance-fee. Mốc paidAt do nghiệp vụ lấy creditedAt đã xác minh, không lấy giờ trình duyệt. Từ chối advance<=0, null và cancelledAt<paidAt trước nhánh miễn phí |
| Thời gian và giới hạn | 42 test pure policy: Clock/Instant, trước/đúng/sau 1 giờ đến nanosecond, 3/4 ngày, quá ngày nhận, nửa đêm Asia/Ho_Chi_Minh và JVM UTC/Los_Angeles/Tokyo, tiền lẻ và Long.MAX_VALUE |
| Guard dưới khóa | `BookingService.cancelBooking` đọc current Car→Booking, kiểm tra đúng Customer/status rồi chặn mọi vehicleReceivedAt!=null trước payment/refund/notification. 7 test MySQL/service/HTTP: PENDING/CONFIRMED đã giao, đã trả xe vẫn chặn, HTTP redirect có error và không success, legacy hai trạng thái giữ mock refund, stale managed entity trong REPEATABLE_READ với connection ID khác đã commit handover |
| Không side effect khi bị chặn | Fingerprint toàn bộ 7 bảng legacy trước/sau service/HTTP bằng nhau trong fixture; payment vẫn PAID, trạng thái vẫn CONFIRMED ở test stale. DB ứng dụng vẫn 7 bảng và fingerprint schema/toàn bộ dữ liệu trước/sau full suite giống nhau |
| Hồi quy | 456/456 full isolated clean verify, zero failure/error/skip; 407 hồi quy + 49 mới; JAR đóng gói. Không sửa root POM, SQL/migration/seed/fixture/ảnh; giữ file phase-0 riêng của chủ dự án; schema kiểm chứng được dọn |

**RED/GREEN:** `.scratch/phase6/p62/split-red.log` bắt thiếu policy; `split-validation-red.log` bắt total không hợp lệ; `grace-red.log` bắt thiếu quote/cancellation; `tier-red.log` bắt 4 nhánh Customer chưa tính phí; `cancellation-validation-red.log` bắt 10 trường hợp dữ liệu hủy sai; các log GREEN tương ứng đạt. `guard-red.log` tái hiện đơn đã giao vẫn hủy được; `guard-green.log` xác nhận guard chặn. `guard-final-green.log` có 3 assertion fail vì booking seed1 không có payment; đã sửa fixture tạo payment9001 riêng trong DB tạm, không sửa seed dự án. `guard-fixture-green.log` đạt cả 7 ca. `policy-final-green.log` đạt 42; full suite xác nhận cả 42+7 trên source cuối.

**Lệnh:** policy TDD dùng `.scratch/phase6/p62/policy-pom.xml` chỉ compile policy/BusinessException và test tương ứng; guard dùng `verification-pom.xml`, source/resources gốc và output riêng tránh IDE ghi target. POM dự án giữ nguyên. Lượt cuối biên dịch sạch đầy đủ, không skip main/test:

```powershell
java '@.scratch/phase6/p62/full-suite.args'
# Harness: .\mvnw.cmd -f .scratch/phase6/p62/verification-pom.xml -o '-Dmaven.compiler.useIncrementalCompilation=false' clean verify
```

Log `full-clean-verify.log` có BUILD SUCCESS và 456 test; `full-suite-runner.log` có exit=0, disposable schema removed. Warnings deprecation và lỗi notification/provider configuration do test chủ động inject không phải failure của build.

**Handoff:** policy là hàm thuần, không bean/DB/provider; caller lấy Clock/creditedAt từ server và dùng snapshot booking, không bind giá/thời điểm/actor từ client. Rule cố định theo VIETQR_V1; đổi chính sách phải có version mới để không đổi lịch sử. Chưa gắn policy vào createBooking/cancellation tiền thật: snapshot 30/70 và payment apply thuộc P6.5/P6.6/P6.9/P6.10, Customer/Owner hủy và tạo RefundService obligation thuộc P6.11. Endpoint Owner hủy chưa tồn tại; P6.7/P6.11 phải giữ guard hai bên và race handover-vs-cancel. Luồng legacy không bị áp phí mới; createBooking vẫn LEGACY/false. Minimum provider thực tế do gate tạo intent kiểm tra, không đoán hạn mức ở policy.

**Giới hạn phiên:** không màn hình/template mới, browser hoặc tiền thật; HTTP được kiểm chứng bằng MockMvc với Spring Security/CSRF và MySQL thật trên schema tạm. Không migration DB ứng dụng, khởi động lại app8080, bật workflow/scheduler/provider, human review hoặc commit/push. Code từ P6.1 vẫn cần schema đã migrate trước khi thay JAR trên DB hiện hữu.

**Tiến độ:** **57/86**, còn 29; tiếp theo **P6.3 — nháp và ảnh private**. P4.2-A tạm hoãn; nhánh local `feat/phase6-rental-policy`, thay đổi chưa commit.
