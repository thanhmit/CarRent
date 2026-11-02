# CarRent — Phase 6: Thuê xe, hợp đồng và biên bản — Implementation Plan

**Checkpoint P6.12 + P6.13 — 2026-11-02:** hoàn thành riêng **5/5 + 5/5 checklist**. Timeline Customer/Owner/Admin chỉ đọc, các mốc tiền/vật lý/hoàn độc lập; audit queue/deadline/current actor và gate rental liên tục. Full isolated **clean verify 856/856 JUnit, 0 failure/error/skip**, JAR; Chromium **109/109** trên source cuối, 320/390/1440 và no-JS. **68/86 task**, còn18 (45task mới xong27). Phụ trách `huy69332`, reviewer được giao `thanhmit`; có agent review, chưa có human contributor review. Nhánh `feat/phase6-rental-gate` từ main `82a3852`, chưa stage/commit/push. DB ứng dụng vẫn7bảng, phase0/config/seed/uploads giữ nguyên. Không migrate/bật workflow/provider/scheduler ứng dụng, restart8080 hoặc chuyển tiền thật. Dừng trước Phase8; tiếp theo **P8.1 riêng**, phụ trách `ducbinhnguyen07ars`. P4.2-A live vẫn chờ.

**Tích hợp P6.11 — 2026-11-02:** feature `6de7526`, merge main `7aeb8a8`, author `huy69332`; đã push và giữ nhánh `feat/phase6-paid-cancellation`. Source merge khớp source đã clean verify816/816/JAR và browser79/79; sau merge chạy lại32/32 cancellation/UI test trên schema riêng. Phase0 riêng nằm ngoài commit và giữ nguyên. Tiến độ66/86, còn20; tiếp theo P6.12→P6.13, phụ trách `huy69332`, reviewer `thanhmit`. Không migrate DB ứng dụng/bật workflow/provider/scheduler/restart8080 hoặc chuyển tiền thật.

**Tích hợp Git 2026-11-02:** P6.8–P6.10 dùng author `huy69332`, commit tính năng `983e7c9`, merge main `0a1d9e6`; giữ nhánh tính năng. Clean verify mới783/783/JAR; evidence `.scratch/phase6/p610/integration/full-clean-verify.log`. Thay đổi riêng phase-0 nằm ngoài commit và giữ nguyên; không migrate/bật DB ứng dụng/provider/scheduler hoặc chuyển tiền thật. Có agent review, chưa có human contributor review. P6.11 đã hoàn thành trong working tree; tiếp theo P6.12→P6.13.

> **For agentic workers:** Dùng superpowers:executing-plans theo đợt đã duyệt bên dưới, kiểm chứng/checkpoint riêng từng task; không tự stage/commit/push, không tick gate chưa chạy. Được chủ động dùng subagent cho công việc độc lập và review. Agent chính chịu trách nhiệm phân công, tránh sửa chồng file, tích hợp và kiểm chứng kết quả cuối.

**Ngày cập nhật:** 2026-11-02. **Trạng thái:** P6.1–P6.11 hoàn thành; P6.12–P6.13 chưa hoàn thành. **Checkpoint lịch sử P6.11 — 2026-11-02:** đủ **5/5 checklist**: Customer/Owner hủy trước handover, snapshot thời gian/phí/lý do và nghĩa vụ hoàn atomic; receipt gốc giữ tiền đã nhận. Full isolated clean verify **816/816 JUnit, 0 failure/error/skip**, JAR; Chromium **79/79**,25PNG,7/7SQL invariant. **66/86 task**, còn20 (45task mới xong25). Tiếp theo **P6.12 → P6.13** cùng đợt. Phụ trách `huy69332`, reviewer được giao `thanhmit`; có agent review, chưa có human contributor review. P6.11 đã tích hợp main; xem checkpoint Git phía trên. DB ứng dụng vẫn7bảng, phase0/config/seed/uploads giữ nguyên; không migrate/bật workflow/provider/scheduler ứng dụng, restart8080 hoặc chuyển tiền thật. P4.2-A live vẫn chờ.

**Tích hợp Git 2026-11-01:** P6.4–P6.7 thuộc `huy69332`, commit `4a6af85`, merge main `66c2d8b`; nhánh tính năng được giữ. Kiểm chứng mới clean verify683/683/JAR và post-merge handover45/45; có agent review, chưa có human review mới. Thay đổi riêng phase0 nằm ngoài commit, DB ứng dụng giữ nguyên. Đây là snapshot trước P6.8/P6.9; chat sau hiện dùng [handoff P6.11](phase-6-batch-3-handoff.md) từ main sau tích hợp; các checkpoint không commit/push bên dưới là lịch sử trước tích hợp.

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

<a id="cac-dot-da-duyet"></a>
## Các đợt còn lại — được duyệt 2026-11-01

P6.1–P6.13 đã hoàn thành phạm vi offline. Không còn đợt implement Phase6; tiếp theo P8.1 riêng, giữ gate live P4.2-A và không đổi ID/checklist.

| Đợt còn lại | Task | Phạm vi và điều kiện kết thúc |
|---|---|---|
| 1 — đã hoàn thành | **P6.8 + P6.9** | Customer gửi AFTER/evidence bất biến, nhắc Owner/Admin sau1giờ; Owner nhận lại xe, ghi bất đồng và tạo BALANCE/dueAt/notify atomic. Kiểm chứng submit → return và race reminder/return; gửi AFTER không tự trả xe. Chưa apply70% hoặc complete. |
| 2 — đã hoàn thành | **P6.10** | Giữ đợt riêng cho QR70%, receipt cũ/trùng/dư/thiếu, quá hạn và auto-COMPLETED; kiểm chứng tiền/concurrency, AFTER dispute không chặn complete. |
| 3 — đã hoàn thành | **P6.11** | Giữ đợt riêng cho hủy có phí/hoàn, backend quote snapshot và race cancel/handover; không chuyển payout trong transaction. |
| 4 — đã hoàn thành | **P6.12 → P6.13** | Hoàn thiện timeline/quyền/job/queue trước; sau đó gate E2E/concurrency/build/browser trên source cuối và đồng bộ SDD. Gate có kết quả độc lập, không tick từ việc UI đã xong. |

Áp dụng [quy tắc nghiệm thu chung](README.md#cac-dot-con-lai-da-duyet): checklist/checkpoint riêng mỗi task, full regression và browser phù hợp cuối đợt. UI/reminder thuộc P6.8/P6.9 phải hoàn thành ngay đợt1; P6.12 tích hợp phần đã có. Không bật scheduler/workflow trên DB ứng dụng chỉ để kiểm chứng; fixture riêng phải chứng minh job/restart thật khi checklist yêu cầu. P4.2-A giữ riêng, không suy live pass từ fixture.

**Handoff chat mới:** [Phase6 đợt4 — P6.12→P6.13](phase-6-batch-4-handoff.md). Handoff đợt1/đợt2 giữ lịch sử, không dùng làm lệnh triển khai task đã đóng.

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

- [x] Tạo nháp bằng POST; GET không insert. BEFORE chỉ Owner của xe, AFTER chỉ Customer sau handover; legacy không bị ép có report.
- [x] Tái sử dụng yêu cầu ảnh đã duyệt: JPEG/PNG decode thật, ≤5 MiB/ảnh, ≤20 triệu pixel, report 4–10 ảnh khi submit; upload từng đợt 1–4 ảnh. Draft cho thiếu trường/ảnh.
- [x] Form giữ 5 trường: ghi chú tình trạng, hư hỏng thấy được, km, mức nhiên liệu/năng lượng 0–100, phụ kiện. Validate numeric/length ở draft; submit bắt buộc đầy đủ.
- [x] File UUID trong private storage, auth/membership mỗi lần stream; không phục vụ tĩnh qua /uploads, chặn path traversal và image ID thuộc report khác.
- [x] DB rollback không để file mồ côi do upload lỗi; xóa file sau commit khi hợp lệ. Test InspectionPrivacyTest cho tác giả, thành viên sau submit, user ngoài, nội dung giả ảnh, oversize và rollback.

**Bằng chứng:** [checkpoint P6.3](#checkpoint-p63--2026-11-01). Cả 5 mục đạt trong phạm vi nháp/ảnh; validation nhóm Submitted đã kiểm chứng, endpoint submit/gate tối thiểu4 ảnh thuộc P6.4/P6.8.

### Task P6.4 — Owner chấp nhận và gửi BEFORE

**Phụ trách:** `@huy69332` · **Review:** `@thanhmit`.

**Files:** BookingService.confirmBooking, InspectionService.submitBefore; OwnerBookingController/InspectionController.

- [x] VIETQR_V1: Owner accept ghi owner_accepted_at, vẫn PENDING; không gọi đó là đã cho thuê. Legacy confirm giữ logic cũ.
- [x] Submit BEFORE kiểm tra Owner/booking còn hiệu lực, đủ ảnh/trường; khóa report/ảnh, chuyển SUBMITTED và notify Customer cùng transaction.
- [x] Từ submit cấm sửa/xóa nội dung/ảnh gốc. Có thể append ghi chú trước Customer đồng ý; nội dung đã được đồng ý được giữ nguyên làm bằng chứng.
- [x] Customer chưa đồng ý có thể ghi bất đồng và không đi tiếp thanh toán; lịch sử báo cáo/ghi chú đang có phục vụ bước đồng ý sau trao đổi ở P6.5 (Customer đồng ý + advance chưa triển khai theo phạm vi được giao). Không cần xây Admin phán quyết hoặc tự tính bồi thường để mở checkout.
- [x] Test submit lặp, sai Owner, user INACTIVE, cancel-vs-submit; không tạo handover/payment chỉ vì gửi BEFORE.

**Bằng chứng:** [checkpoint P6.4](#checkpoint-p64--2026-11-01), BeforeInspectionTest46 + hồi quy518; browser31. Không tính thao tác đồng ý/tạo advance của P6.5 vào phần đã triển khai.

### Task P6.5 — Customer đồng ý BEFORE, mở giữ chỗ 15 phút

**Phụ trách:** `@huy69332` · **Review:** `@thanhmit`.

**Interface:** BookingService.acceptBefore(Long bookingId, CustomUserPrincipal actor).

- [x] Khóa Car → Booking, kiểm tra Customer/Owner accept, tạo hoặc khóa obligation ADVANCE business key BOOKING:id:ADVANCE và intent trước khi khóa report. Sau đó đọc report current, kiểm tra hợp lệ/chưa đồng ý và ghi accepted_at + BEFORE ACCEPTED. Nếu report không hợp lệ thì rollback cả intent/obligation, không để đảo thứ tự khóa.
- [x] hold_expires_at=thời điểm tạo intent+15 phút, không bắt đầu lại khi reload; toàn bộ đồng ý báo cáo và tạo yêu cầu trả trước cùng transaction.
- [x] UI hiển thị QR, số trả trước/số còn lại và deadline; không gọi hành động đồng ý BEFORE là đã nhận xe.
- [x] Hủy đặt xe tại trang trả trước: cancelBooking kết thúc intent và booking, giải phóng lịch, trang kết quả đã hủy. Không chỉ thoát trang rồi vẫn giữ lịch.
- [x] Test hai tab/POST lặp chỉ một nghĩa vụ/hold; fail tạo intent rollback accepted_at để không kẹt đơn.

**Bằng chứng:** [checkpoint P6.5](#checkpoint-p65--2026-11-01), BeforeAcceptanceTest42 + hồi quy564; Chromium31. Gate nhận tiền và expiry tự động vẫn thuộc P6.6.

### Task P6.6 — Apply advance, expiry và webhook chậm

**Phụ trách:** `@huy69332` · **Review:** `@thanhmit`.

**Interface:** RentalPaymentHandler cho BOOKING_ADVANCE; BookingService.applyAdvance(Long receiptId), expireHold(Long bookingId).

- [x] Implement PaymentDomainHandler.lockSubject(bookingId) khóa current Car/Booking; core apply/expiry chỉ khóa tiền sau bước này. Handler apply dùng khóa aggregate đã có, không lồng queue hoặc lấy lại khóa theo thứ tự khác.

- [x] Receipt đúng collector/code/amount và intent còn hợp lệ: cấp CONFIRMED, ghi ngân hàng creditedAt làm mốc advancePaidAt. Không ghi đã ký hợp đồng hoặc đã nhận xe.
- [x] Thực sự đến sau hạn hoặc sau cancel: tạo refund 100%, không phục hồi đơn. Lệch tiền advance → review theo backlog, không cộng dồn.
- [x] Job expiry và receipt áp dụng dưới cùng Car queue/DB locks. Nếu expiry đã commit thì giữ EXPIRED và hoàn đủ; nếu lịch đã giữ cho B tuyệt đối không hủy B để giành lại xe cho A.
- [x] Khi tiền đúng hạn nhưng xác nhận chậm: reason SYSTEM_CONFIRMATION_DELAY; UI ghi hệ thống xác nhận chậm, không quy Customer trả muộn.
- [x] Hai connection chạy cả hai thứ tự expiry/apply và booking B/apply A; assert không hai confirmed cùng khoảng, không vừa cấp lịch vừa tạo hoàn toàn bộ cùng receipt.
- [x] Hết hạn ẩn QR ở cả UI lẫn server view; QR đã quét ngoài app vẫn có thể chuyển nên late receipt không bị bỏ qua.

**Bằng chứng:** [checkpoint P6.6](#checkpoint-p66--2026-11-01), RentalAdvanceTest32 +606 hồi quy; Chromium25. Gate rental/provider/scheduler ứng dụng tiếp tục tắt.

### Task P6.7 — Owner xác nhận hợp đồng và giao xe

**Phụ trách:** `@huy69332` · **Review:** `@thanhmit`.

**Interface:** BookingService.confirmHandover(Long bookingId, boolean contractSigned, CustomUserPrincipal actor).

- [x] Viết guard test false checkbox/sai Owner/chưa đủ 30%/BEFORE chưa accepted/ngoài khoảng ngày/xe còn ở booking khác đều bị chặn.
- [x] Khi hợp lệ, ghi contract_confirmed_by/at và vehicle_received_at bằng server, atomic dưới khóa Car/Booking. Handover chỉ một lần; bấm lại không tạo event/notify mới.
- [x] UI checkbox và nút “Xác nhận đã giao xe”; Owner chịu trách nhiệm xác nhận giao thực tế. Ngay sau commit hiển thị Đang thuê và ẩn nút hủy hai bên.
- [x] Cancel endpoint cũng chặn backend sau handover; test race cancel-vs-handover cả hai thứ tự. Không CANCELLED+vehicle_received_at.
- [x] Custody check: booking trước nhận rồi/chưa trả chặn handover mới, kể cả lịch cũ đã hết; không suy ra xe đã trả từ listing status hoặc thiếu PAID.

**Bằng chứng:** [checkpoint P6.7](#checkpoint-p67--2026-11-01), RentalHandoverTest45 +638 hồi quy; Chromium32. Gate rental/provider/scheduler ứng dụng tiếp tục tắt.

### Task P6.8 — Customer gửi AFTER và nhắc Owner

**Phụ trách:** `@huy69332` · **Review:** `@thanhmit`.

**Interfaces:** InspectionService.submitAfter(Long bookingId, actor); RentalDeadlineJob.

- [x] Chỉ Customer đúng booking đã handover mới tạo/gửi AFTER; giữ ảnh/text immutable sau submit, notify Owner. Không tự ghi vehicle_returned_at.
- [x] Đủ 1 giờ từ submittedAt mà chưa vehicle_returned_at: atomic ghi after_reminder_sent_at + nhắc Owner/thông báo Admin một lần. Restart quét bù, reload không reset mốc.
- [x] UI so sánh BEFORE/AFTER và trạng thái chờ nhận lại; chặn phí phát sinh từ note hoặc km/energy khác.
- [x] Test gửi sớm/đúng ngày/trễ đều được sau handover; chưa nhận thực tế không được gửi AFTER. Lỗi notify rollback event/report state.

### Task P6.9 — Owner nhận lại xe và tạo nghĩa vụ 70%

**Phụ trách:** `@huy69332` · **Review:** `@thanhmit`.

**Interface:** BookingService.confirmReturn(Long bookingId, ReturnReceiptForm form, actor).

- [x] RECEIVED_ACCEPTED → vehicle_returned_at + AFTER ACCEPTED. RECEIVED_DISPUTED → vehicle_returned_at + AFTER DISPUTED và note bắt buộc. NOT_RECEIVED không ghi vehicle_returned_at.
- [x] Với quyết định nhận thật, khóa Car/Booking rồi tạo hoặc khóa obligation BALANCE trước khi khóa AFTER report; revalidate report dưới khóa, ghi vehicle_returned_at và dueAt=vehicle_returned_at+1 giờ, notify Customer cùng commit. Không cần Customer mở trang mới bắt đầu deadline. Report sai thì rollback cả nghĩa vụ; NOT_RECEIVED không tạo BALANCE.
- [x] Có AFTER đã gửi và đúng Owner mới được thao tác; không cho Admin thay Owner xác nhận vật lý. Bấm lại không tạo nghĩa vụ/nhắc nợ mới.
- [x] Không đổi start/end/total khi trả sớm/trễ hoặc có bất đồng. Xe trả rồi không còn outstanding custody; conflict theo ngày vẫn theo policy tìm xe hiện hành.
- [x] Test return-vs-receipt/repeated return, rollback nghĩa vụ/notify/timestamp đồng thời.

### Task P6.10 — Thanh toán 70%, quá hạn và hoàn thành

**Phụ trách:** `@huy69332` · **Review:** `@thanhmit`.

**Interface:** RentalBalancePaymentHandler BOOKING_BALANCE; BookingService.applyBalance(Long receiptId).

- [x] QR BALANCE hết 15 phút hoặc Customer bấm hủy: chỉ kết thúc attempt, không hủy booking/không xóa nợ/không reset dueAt. Cho POST tạo attempt mới.
- [x] Receipt qua QR cũ vẫn trả đúng obligation nếu còn nợ; số vượt phần còn phải trả tạo refund EXCESS_BALANCE từ Owner; receipt sau khi đủ tiền hoàn như trả trùng.
- [x] Receipt thấp hơn số cần trả: lưu review, không báo hoàn thành; tự động cộng dồn khoản thiếu chưa thuộc phạm vi phase này.
- [x] Đã vehicle_returned_at và balance được thanh toán đủ → COMPLETED trong transaction apply. AFTER DISPUTED không chặn, ảnh/note giữ nguyên; ẩn nút Owner complete cho VIETQR_V1.
- [x] Sau 1 giờ còn nợ: ghi balance_overdue_notified_at và thông báo Customer/Owner/Admin một lần. Không khóa tài khoản/chặn đặt mới/phạt hoặc refund advance.
- [x] Test đủ tiền đúng/sau hạn, QR cũ, khoản trùng, dư, tab đóng, restart, dispute + paid vẫn complete; booking legacy không bị auto-complete mới.

### Task P6.11 — Customer/Owner hủy trước handover và tạo hoàn

**Phụ trách:** `@huy69332` · **Review:** `@thanhmit`.

**Files:** BookingService.cancelBooking, thêm cancelByOwner(id, reason, actor); RentalPolicy/RefundService.

- [x] PENDING chưa nhận advance: cancel intent/booking, không tạo khoản hoàn giả. Receipt về sau tạo hoàn đủ.
- [x] CONFIRMED chưa giao: Customer hủy dùng quote snapshot ở P6.2; Owner hủy hoàn đủ. CanceledAt/fee/reason và nghĩa vụ hoàn được ghi cùng transaction, chuyển tiền thực hiện ngoài transaction bởi Phase 4.
- [x] Sau handover chặn cả hai bên; Owner phải dùng luồng return, không dùng cancel để xóa khoản nợ.
- [x] Nút hủy cần hiển thị số hoàn/phí dự kiến và backend tính lại khi submit; không tin quote client.
- [x] Test các biên 1 giờ/3 ngày/date đã qua, payout chờ recipient/funds; payment gốc vẫn là tiền đã nhận, không bulk update REFUNDED trước khi chuyển thành công.

### Task P6.12 — UI, quyền và job deadline tích hợp

**Phụ trách:** `@huy69332` · **Review:** `@thanhmit`.

- [x] Timeline ba view Customer/Owner/Admin phân biệt Owner accept, BEFORE agreed, advance paid, hợp đồng/handover, AFTER, returned, balance paid, refunds.
- [x] Trang checkout dùng Phase 4, lỗi/hết hạn/hủy/hoàn tách rõ; polling GET không chuyển trạng thái.
- [x] Ảnh report chỉ thành viên đúng booking/Admin ACTIVE sau submit; nháp chỉ tác giả. Text escape, lỗi field giữ input, UI 320/390/1440px không tràn trang.
- [x] Job 30 giây gọi dịch vụ domain qua queue; DB bền vững và mốc notified chống nhắc trùng. Tác vụ bị queue reject chưa được đánh dấu đã làm.
- [x] Các entry point mới không được quên nối CarCommandExecutor; service vẫn giữ current DB read theo thứ tự khóa README.

### Task P6.13 — Gate rental mới và đồng bộ SDD

**Phụ trách:** `@huy69332` · **Review:** `@thanhmit`.

- [x] Run `.\mvnw.cmd '-Dmaven.compiler.useIncrementalCompilation=false' clean verify`; RentalConcurrencyTest dùng hai kết nối thật, có rollback/notify failure/stale entity.
- [x] E2E fixture: report → 30% → checkbox/handover → AFTER dispute → Owner received → 70% → COMPLETED, cùng các nhánh cancel/refund/late receipt.
- [x] So baseline legacy/seed/private files; không tick từ kết quả plan cũ. Dữ liệu đã gửi không bị sửa để khớp kết quả test.
- [x] Đồng bộ SDD/decisions: bỏ pickup unpaid, Customer final pickup, cash giả lập mới, Admin resolve bắt buộc để complete, mốc 72 giờ; thêm hợp đồng Owner và policy ngày lịch.
- [x] Chưa bật workflow mới cho mọi user hoặc chạy demo đầy đủ trước Phase 8 và gate kênh thật. Chỉ fixture test mang VIETQR_V1 trong quá trình phát triển.

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

## Checkpoint P6.3 — 2026-11-01

**Phạm vi hoàn thành:** 5/5 mục P6.3, phụ trách `huy69332`, reviewer được giao `thanhmit`; chưa ghi nhận human review. Chỉ nháp/ảnh và form/detail tối thiểu qua route trực tiếp.

| Điều kiện | Triển khai và bằng chứng |
|---|---|
| Draft đúng tác giả và thời điểm | GET không insert; POST lặp giữ một report/phase. BEFORE đúng Owner trước handover, AFTER đúng Customer sau handover/trước returned; PENDING/CONFIRMED, VIETQR_V1 + inspection_required. Legacy404, account không ACTIVE và principal cũ bị chặn |
| Ảnh thật và giới hạn | ImageIO JPEG/PNG, actual bytes≤5MiB (đúng giới hạn được nhận), dimensions≤20 triệu trước decode. Batch1–4/report≤10; hai connection upload từ9 ảnh chỉ một thành công, giữ10rows/10files. Draft thiếu ảnh được lưu; 4ảnh tối thiểu khi submit thuộc P6.4/P6.8 |
| Năm trường và validation | Text2000/1000/500; km0–9.999.999, energy0–100. Draft cho null, Submitted bắt đủ trường và kế thừa giới hạn. HTTP từ chối mass assignment, giữ raw numeric input khi lỗi, escape script |
| Private storage và quyền | UUID, key nội bộ không có trong DTO/HTML; không static mapping, resolve key strict và read không theo symlink. Draft chỉ author (counterpart/Admin404), sau submit đúng thành viên/Admin ACTIVE được đọc; mọi imageID kiểm tra report membership; no-store/nosniff, CSRF cho3mutation |
| Atomicity và immutability | Queue trước transaction; current locks Car→Booking→Inspection→Image, stale managed DRAFT không ghi đè SUBMITTED tại REPEATABLE_READ. Upload/batch lỗi rollback dọn file; delete rollback giữfile, afterCommit mới dọn. SUBMITTED/ACCEPTED/DISPUTED/RESOLVED không sửa nội dung/ảnh |

**Kiểm chứng:** full isolated clean verify **518/518, 0 failure/error/skip**, JAR đóng gói; 456 hồi quy + InspectionPrivacyTest52 (service/HTTP/MySQL thật) + InspectionStorageTest10. Browser Chromium **24/24**: lưu trước/sau thuê, ảnh private/upload/delete, quyền Owner/Customer/Admin/outsider/anonymous, XSS, validation, no-JS và Tomcat thật trả413 cho5MiB+1. Không tràn ngang width320/390/1440; đã xem ảnh chụp Owner mobile/desktop và Customer AFTER. Harness dùng chính nội dung CDN/font đã tải cache, không giả endpoint ứng dụng. Đây là fixture local, không phải kiểm chứng workflow rental đầy đủ hoặc provider live.

**RED/GREEN:** `.scratch/phase6/p63/`: storage-red/storage-green bắt đầu từ thiếu class; storage-validation-red bắt null/size và pixel-limit, storage-validation-green10pass. draft-red/draft-green bắt đầu từ thiếu form/service; draft-current-red tái hiện stale managed draft, draft-current-green20pass. images-red/images-green và images-privacy-green kiểm chứng rollback/privacy. http-red/http-green từ route404; http-privacy-green có3 lỗi giữ raw numeric (type=number) và1 assertion login URL sai; đổi field sang text+inputmode/pattern và sửa assertion, http-privacy-final62pass. Kịch bản browser ban đầu timeout do selector tên nút login có icon; sửa selector harness, không đổi auth. Browser cuối `browser-check-final.log/.exit`, `browser-evidence.json`24pass; screenshots `owner-{320,390,1440}.png`, `customer-after-390.png`, `nojs-390.png`. Private fixture files/schema18063 đã dọn, evidence PNG/log được giữ local.

**Lệnh hồi quy:** dùng source/resources gốc, POM/output kiểm chứng riêng để tránh IDE ghi target; root POM không sửa:

```powershell
javac '@.scratch/phase6/p63/harness-compile.args'
java '@.scratch/phase6/p63/full-suite.args'
# Harness: .\mvnw.cmd -f .scratch/phase6/p63/verification-pom.xml -o '-Dmaven.compiler.useIncrementalCompilation=false' clean verify
```

`full-clean-verify.log`: BUILD SUCCESS,518 tests; `full-suite-runner.log/.exit`: exit0, disposable schema removed. DB ứng dụng vẫn7bảng, fingerprint schema/toàn bộ rows trước/sau giống nhau (`source-before.txt/source-after.txt`).10file bảo vệ gồm phase-0 riêng của chủ dự án/rootPOM/scriptDB/legacy fixture/6seedPNG có hash giữ nguyên; không sửa migration hoặc seed. Warnings deprecation/lỗi notification được inject trong hồi quy không phải test failure.

**Handoff:** service `getView/saveDraft/addImages/deleteDraftImage/readImage`; controller base `/bookings/{bookingId}/inspections/{BEFORE|AFTER}`, GET base hoặc `/draft`, POST `/draft`, `/images`, `/images/{imageId}/delete`, GET `/images/{imageId}`. Một `inspection/detail.html` cho editable draft/read-only submitted. Config server `carrent.inspection.storage-root` mặc định `private-uploads/inspections`, ngoài public uploads và được Git ignore; cần backup/permissions phù hợp khi cutover. Gọi mutation qua CarCommandExecutor trước khi mở service transaction; giữ current-read/lock order khi thêm submit.

**Giới hạn đã phân task:** submit BEFORE/notify/append note/Customer đồng ý-bất đồng ở P6.4/P6.5; AFTER submit ở P6.8; CTA/timeline booking ở P6.12. Chưa bật tạo booking VIETQR_V1, migrate DB ứng dụng, restart app8080, scheduler/provider/tiền thật hoặc commit/push. JAR mới vẫn cần schema P6.1 trước thay bản đang chạy; P4.2-A hoãn.

**Tiến độ:** **58/86**, còn28; tiếp theo **P6.4 — Owner chấp nhận và gửi BEFORE**. Nhánh local `feat/phase6-inspection-drafts`, thay đổi chưa commit.

## Checkpoint P6.4 — 2026-11-01

**Hoàn thành 5/5 mục trong phạm vi P6.4.** Base `main f8f12d81c30bafb66fa09ed7cebdd256d646151c`; nhánh local `feat/phase6-before-submission`, chưa commit. Phụ trách `huy69332`, reviewer dự kiến `thanhmit`; chưa ghi nhận human review.

| Checklist | Triển khai và bằng chứng |
|---|---|
| Owner accept | `BookingService.confirmBooking`: VIETQR_V1 ghi `owner_accepted_at` bằng Clock, giữ PENDING. POST lặp giữ thời điểm và một BOOKING_OWNER_ACCEPTED notification. Legacy vẫn CONFIRMED, không dựng acceptance evidence. UI gọi “Chấp nhận yêu cầu”, phân biệt chờ biên bản/trả trước/chưa giao xe |
| Submit BEFORE | Đúng Owner ACTIVE, workflow/inspection_required, PENDING, đã Owner accept, chưa BEFORE agreed/handover. Kiểm tra đủ 5 trường và 4–10 ảnh; current locks Car→Booking→Report→Images. Ghi SUBMITTED/submittedAt + event actor/time + INSPECTION_SUBMITTED cho Customer cùng transaction; lỗi notification rollback toàn bộ. Submit lặp ở SUBMITTED/DISPUTED không ghi lại |
| Immutable + addendum | Không sửa/xóa text/ảnh gốc ở SUBMITTED/DISPUTED/ACCEPTED/RESOLVED. Chỉ Owner append ADDENDUM khi SUBMITTED/DISPUTED và chưa agreed; note trim 1–2000 ký tự, actor/time do server. Addendum không xóa DISPUTED hoặc ghi đè bằng chứng. Accepted/Resolved và before_accepted_at chặn phản hồi cả backend/UI |
| Dispute | Đúng Customer ACTIVE của booking append DISPUTED với lý do bắt buộc, notify Owner atomic; không tạo thanh toán/hold/handover/giá mới. Hai bên đọc lịch sử theo createdAt/id; text escape, ảnh private giữ bytes gốc. Không thêm Admin phán quyết/bồi thường. Thao tác Customer đồng ý + advance giữ ở P6.5 |
| Test/race/boundary | 46 ca mới: sai Owner/role, INACTIVE/BANNED với session cũ, thiếu từng trường/ảnh, CSRF, mass assignment, sự kiện/notification một lần, hai connection submit lặp; cancel→submit bị chặn dưới current state, submit→cancel giữ bằng chứng đã gửi. DB-trigger notification failure rollback accept/submit/addendum/dispute. 518 hồi quy cùng đạt |

**Kiểm chứng cuối:** full isolated clean verify **564/564, 0 failure/error/skip**, JAR đóng gói. Browser Chromium **31/31**: accept→draft→upload→submit→Customer dispute→Owner addendum, ảnh bytes nguyên vẹn, quyền Customer/Owner/Admin/outsider, CSRF, XSS, boundary đã đồng ý và no-JS. Không tràn ngang 320/390/1440; đã xem ảnh Owner desktop và Customer mobile. Harness dùng CDN/font cache từ P6.3, không stub endpoint ứng dụng. Fixture accepted chỉ dùng để kiểm chứng chặn sửa sau đồng ý; không giả nhận đã triển khai P6.5.

**RED/GREEN và evidence local:** `.scratch/phase6/p64/`: accept-red bắt CONFIRMED thay PENDING; submit-red/events-red bắt API/DTO còn thiếu; các GREEN tương ứng pass. HTTP vòng đầu gặp XPath không đọc HTML entity tiếng Việt, đổi assertion sang HTTP content; http-red2 bắt form submit chưa tồn tại, http-green pass45. booking-ui-red bắt wording accept cũ, booking-ui-green98pass (46 mới +52 privacy). Browser hai vòng đầu gặp assertion chưa đợi nút sau navigation và phạm vi selector chứa navigation; sửa harness, debug xác nhận DOM đúng; browser-check-final2.exit=0, browser-evidence.json31pass. `full-clean-verify.log`564pass; `full-suite-runner.exit=0`. Warnings DB-trigger 1644/45000 và lỗi cố ý inject của hồi quy là evidence rollback, không phải failure cuối.

```powershell
javac '@.scratch/phase6/p64/harness-compile.args'
java '@.scratch/phase6/p64/full-suite.args'
# Wrapper cấp datasource DB disposable cho Maven; không dùng DB ứng dụng để chạy test.
# Maven: .\mvnw.cmd -f .scratch/phase6/p64/verification-pom.xml -o '-Dmaven.compiler.useIncrementalCompilation=false' '-Dtest=*Test' clean verify
```

**Bảo toàn:** fingerprint schema/toàn bộ rows DB ứng dụng trước/sau giống nhau, vẫn7bảng. SHA256 phase-0 riêng của chủ dự án/root POM/scriptDB giữ nguyên; không đổi migration/seed/fixture cũ. Browser18064/schema/private files đã dọn; log/PNG giữ local. Không restart app8080, migration DB ứng dụng, bật workflow/scheduler/provider, tiền thật, commit/push hoặc tuyên bố human review. Code/JAR cần schema P6.1 trước cutover được cho phép riêng.

**Handoff P6.5:** giữ `BookingService.acceptBefore` chưa triển khai. Report SUBMITTED **hoặc DISPUTED** cùng lịch sử ADDENDUM/DISPUTED đang có phải được Customer xem rồi đồng ý; không yêu cầu Admin RESOLVED để mở checkout. Khóa Car→Booking, tạo/khóa obligation ADVANCE + intent trước Report theo checklist P6.5; kiểm tra lại report hiện tại, ghi before_accepted_at/ACCEPTED và hold15phút cùng transaction. Sau đồng ý, không append thêm vào bằng chứng này. P6.4 không ghi accepted_at/hold/payment/contract/handover. Service mới: `submitBefore`, `appendBeforeAddendum`, `disputeBefore`; POST `/bookings/{id}/inspections/BEFORE/{submit|addendum|dispute}`. Minimal link Owner sau accept/Customer sau publication; full timeline/AFTER vẫn P6.8/P6.12.

**Tiến độ:** **59/86**, còn27; tiếp theo **P6.5 — Customer đồng ý BEFORE và tạo advance**. P4.2-A hoãn.

## Checkpoint P6.5 — 2026-11-01

**Kết quả:** đủ 5/5 checklist P6.5 trong phạm vi kỹ thuật/local đã được giao. Giữ nguyên toàn bộ bằng chứng P6.4 và thay đổi riêng phase-0.

| Checklist | Triển khai và kiểm chứng |
|---|---|
| Customer đồng ý atomic | POST `/bookings/{id}/accept-before` → một Car queue trước transaction → current Car→Booking→obligation ADVANCE→intent→Report→Images. Customer ACTIVE đúng booking, Owner đã accept, workflow VIETQR_V1/inspection_required, PENDING/chưa handover. SUBMITTED hoặc DISPUTED hợp lệ (đúng tác giả, đủ5trường/4–10ảnh) → before_accepted_at, ACCEPTED và event BEFORE_ACCEPTED cùng actor/time server; lỗi report/audit rollback cả tiền/hold/agreement |
| Hold15phút, idempotency | Business key BOOKING:id:ADVANCE; dùng RentalPolicy 30/70 từ snapshot. hold_expires_at và obligation.due_at trùng intent.createdAt+900giây. Hai tab/POST lặp và hai DB connection có stale managed state cùng trả một intent/event/deadline; không gia hạn khi reload hoặc sau hạn |
| Checkout | Hiện QR, advance/balance, code và deadline giờ Việt Nam; đồng ý chưa nhận tiền/nhận xe. Booking có link quay lại checkout; bằng chứng/ảnh/lịch sử không đổi, sau agreement đóng addendum/dispute. Owner không được reject đơn đã mở advance để tránh orphan intent |
| Hủy đặt xe | Checkout và booking đóng unpaid booking/obligation/ACTIVE intents cùng transaction, có timestamp/notify, giải phóng conflict calendar. Repeat không đổi thời gian/notify. Manual cancel tại hoặc sau QR deadline vẫn CANCELLED; đóng tab không hủy. Trang hết QR vẫn cho hủy rõ ràng; kết quả ghi Đã hủy đặt xe. Guard parties/paid/handover; notification lỗi rollback toàn bộ |
| Test/rollback | BeforeAcceptanceTest42: service/HTTP, role/status/CSRF, invalid report/channel, event/notification triggers, idempotency, hai connection agree/agree và accept/cancel hai thứ tự commit, giữ evidence sau dispute. Receipt apply trước P6.6 phải rollback settlement và giữ inbox RECEIVED/retry với APPLY_FAILED |

**Kiểm chứng cuối:** full isolated clean verify **606/606, 0 failure/error/skip**, JAR đóng gói. **Chromium 31/31**: original/dispute/addendum → agree → checkout → reload/second tab → cancel; private ảnh bytes nguyên vẹn, quyền/CSRF/XSS, no-JS, không tràn ngang320/390/1440. Đã xem ảnh checkout và kết quả. Harness cache CSS/font và dùng ảnh QR offline; không stub endpoint ứng dụng, không xác minh dịch vụ QR/provider hoặc tiền thật.


**Rulings/kết nối P6.6:** `PaymentObligationService.openAttempt` dùng Propagation.NEVER và queue, nên không gọi từ accept transaction. `PaymentIntentTransactions.openForLockedBooking` tái dùng core validation/open và kiểm tra aggregate đã khóa, không lồng queue/re-lock aggregate sau tiền. Return view từ intent đã khóa, tránh refresh đọc snapshot cũ tại REPEATABLE_READ (test đã RED→GREEN). RentalPaymentHandler.lockSubject và manual cancel là prerequisite P6.5; gate `carrent.rental.advance.enabled` mặc định tắt, chỉ bật trong fixture disposable. `apply` và `expire` chủ động báo chưa triển khai, rollback caller; không có callback rỗng làm giả nhận tiền hoặc hết giữ lịch. P6.6 phải hoàn thiện receipt/expiry/refund, race và readiness gate trước khi cân nhắc bật workflow. Manual cancel ADVANCE sau deadline gọi cancel, không gọi callback expiry. Paid cancellation còn thuộc P6.11.

**Schema:** bổ sung action BEFORE_ACCEPTED vào Java, bootstrap SQL và migration P6 chưa áp dụng; giữ PICKUP_CONFIRMED cũ để tương thích, không dùng action nhận xe cho agreement. Không áp dụng DDL lên DB ứng dụng. Cutover sau này cần schema hiện hành và P6.6/provider gates, không chỉ thay JAR.

**Evidence local:** `.scratch/phase6/p65/`: progress.md, accept-red/green.log, cancel-red/green.log, ui-red/green.log, integration.log/integration-green.log, parties-red.log, target-final.log, handover-regression-red.log, full-clean-verify.log, full-suite.exit, browser-evidence.json, browser-check-final.log và PNG. Các lần RED và lỗi assertion harness URL (Location tuyệt đối) được giữ nguyên; run cuối mới là gate hoàn thành. Handover-specific error cũ được phục hồi và regression xác minh trong full suite.

**Bảo toàn:** app DB fingerprint7bảng trước/sau giống nhau; phase-0 SHA256 F20EDF449FBE1680493684E44C44D9C85582EBB0C6E71ACD07104E8CA681B238 và root POM giữ nguyên. App8080 không restart. Browser18065/schema/private files đã dọn. HEAD f8f12d8, index không stage; không commit/push, migration ứng dụng hoặc enable workflow/provider/scheduler.

**Handoff P6.6:** hoàn thiện RentalPaymentHandler.apply/expire, BookingService.applyAdvance/expireHold; giữ lock Car→Booking trước tiền, không lồng queue. Advance đúng → CONFIRMED/advancePaidAt=bank creditedAt; không ghi hợp đồng/handover. Late/after-cancel refund100%, SYSTEM_CONFIRMATION_DELAY, các race expiry/apply/booking B và refund invariants. Giữ default-off gate đến khi readiness được cho phép. Tiến độ60/86, còn26; P4.2-A hoãn.

## Checkpoint P6.6 — 2026-11-01

**Kết quả:** đủ7/7 checklist trong phạm vi kỹ thuật/local đã được giao; bảo toàn P6.4/P6.5 và file phase-0 riêng.

| Hạng mục | Kết quả đã kiểm chứng |
|---|---|
| Apply | Callback tham gia transaction core và dùng current locks có sẵn. Bank creditedAt trước deadline → CONFIRMED/advancePaidAt; không ghi hợp đồng/giao xe. Duplicate inbox không thêm receipt/notify |
| Expiry | Đúng deadline đóng intent/nghĩa vụ/booking EXPIRED; gọi lặp không nhân notification; lịch được giải phóng, không mở lại attempt hoặc reset hold |
| Refund | Late/after-unpaid-cancel hoàn100% tiền chưa phân bổ, LATE_ADVANCE; expiry commit trước receipt đúng hạn dùng SYSTEM_CONFIRMATION_DELAY; giao dịch đúng số tiền chuyển trùng dùng DUPLICATE_PAYMENT; không hồi sinh A/hủy B |
| Review/recovery | Advance lệch giữ REVIEW_REQUIRED, không cộng dồn/gia hạn. Sai parties/code không cấp lịch; sai bank account bị chặn ở webhook ingress. Lỗi notify hoặc thiếu PAYOS Owner rollback toàn bộ và inbox retry, không dùng nguồn Admin; người dùng INACTIVE không làm bỏ receipt |
| Race | Hai connection thật, snapshot cũ: expiry/apply và B/applyA cả hai thứ tự commit; cancel/apply và hai giao dịch ngân hàng khác nhau. Assert không hai confirmed overlap, không vừa cấp lịch vừa hoàn đủ cùng receipt |
| UI | QR ẩn ở client/server sau deadline; expired/cancelled vẫn polling khoản chuyển ngoài app. Result phân biệt nhận advance với hợp đồng/giao xe; hủy không che pending/succeeded refund; hiển thị lý do hệ thống xác nhận chậm và refund summary |

**Kiểm chứng cuối:** full isolated clean verify **638/638, 0 failure/error/skip**, JAR đóng gói;606 hồi quy + RentalAdvanceTest32. Test P6.5 trước đó chờ callback fail-closed đã cập nhật sang receipt thành công, giữ42 ca. Chromium **25/25**, hai tab/polling/no-JS, chữ/bằng chứng escape, quyền financial403, 320/390/1440 không tràn ngang. Đã xem ảnh mobile system-delay và desktop received. Signed fixture webhooks đi qua HTTP thật, inbox ACK rồi gọi PaymentMaintenanceJob.scan thủ công; không stub endpoint ứng dụng. CSS/fonts cache và bitmap QR offline không chứng minh QR/provider live. Test trạng thái refund SUCCEEDED chỉ là fixture kiểm chứng thứ tự hiển thị, không phải chuyển tiền thật.

**Bằng chứng local:** `.scratch/phase6/p66/` — `apply-red.log`, `expiry-red.log`, `refund-red.log`, `ui-red.log`/`ui-green.log`, `integration-green.log`, `target-final.log`32ca, `full-clean-verify.log`, `full-suite-runner.log`/`.exit=0`; `browser-check-final.log`/`.exit=0`, `browser-evidence.json`, `browser-runtime.log`/`.exit=0`; `received-{320,390,1440}.png`, `system-delay-390.png`, `cancelled-refund-nojs-390.png`; source/protected/verified hashes và progress ledger.

**Rulings:** giữ bank creditedAt làm mốc; now qua deadline chưa tự phủ nhận khoản vào đúng hạn khi expiry chưa commit. Expiry đã commit không phục hồi lịch. Tiền đến sau unpaid cancel dùng LATE_ADVANCE cho phần chưa phân bổ; không áp phí hủy/allocated refund P6.11. Guard lock nhận PESSIMISTIC_FORCE_INCREMENT do Hibernate nâng sau flush, không lấy lại khóa aggregate. Handler gate mặc định tắt; chỉ fixture bật để kiểm chứng. Browser harness ban đầu dùng classpath cũ và kỳ vọng sai mã409/404; đã sửa theo build P6.6 và core terminal no-op/private403, chạy lại đủ25ca đạt. Review tự thực hiện theo chỉ dẫn không dùng subagent khi chưa yêu cầu; chưa có human review mới.

**Bảo toàn:** fingerprint schema/toàn bộ rows DB ứng dụng trước/sau giống nhau, vẫn7bảng. Phase-0 SHA256 F20EDF449FBE1680493684E44C44D9C85582EBB0C6E71ACD07104E8CA681B238; rootPOM/scriptDB/migration không đổi trong task này. Không restart8080. Browser18066/schema/private files đã dọn. HEAD f8f12d8 và index giữ nguyên; không commit/push, migration ứng dụng hoặc enable workflow/provider/scheduler.

**Handoff P6.7:** `BookingService.confirmHandover` bởi Owner ACTIVE đúng xe, CONFIRMED + advance đã nhận; contract actor/time và vehicle_received_at atomic dưới Car→Booking; chưa triển khai tại P6.6. Giữ các guard paid cancellation đến P6.11, không gọi Customer agreement là nhận xe. **61/86**, còn25; P4.2-A hoãn, default-off gate chỉ cân nhắc thay đổi khi cutover được cho phép.

## Checkpoint P6.7 — 2026-11-01

**Kết quả:** đủ5/5 checklist trong phạm vi kỹ thuật/local đã giao; bảo toàn P6.4–P6.6 và file phase-0 riêng.

| Checklist | Bằng chứng đã kiểm chứng |
|---|---|
| Guard | Checkbox true; đúng Owner ACTIVE; VIETQR_V1/inspection; CONFIRMED/Owner accepted/BEFORE agreed và report ACCEPTED; obligation ADVANCE SETTLED đủ30% khớp snapshot/payer/collector. Clock ngày Việt Nam [startDate,endDate), test biên start/end |
| Atomic/idempotency | Car queue trước transaction; current Car→Booking→custody→money→Report. Cùng Instant contract actor/time và vehicle_received_at, event PICKUP_CONFIRMED + notify Customer. Lỗi event/notify rollback, hai tab/retry ngoài cửa sổ ngày không thêm timestamp/event/notify |
| UI | Checkbox hợp đồng ngoài ứng dụng + nút giao xe, hoạt động no-JS. Detail/list hai bên Đang thuê, thời gian/actor và30/70; ẩn nút hủy sau giao xe. Chặn complete thủ công VIETQR_V1, legacy giữ nguyên |
| Cancel race | Hai connection/snapshot cũ, cả hai thứ tự unpaid cancel/handover và paid cancel/handover. Unpaid cancel được; paid cancel giữ fail-closed đến P6.11; handover đã commit không bị hủy. Không CANCELLED+received, không refund mới |
| Custody | Query locking hiện tại theo timestamps/inspection, không lọc date/status/payment. Booking cũ quá ngày/status COMPLETED vẫn chặn nếu chưa trả. Đã trả giải phóng custody; hai handover đồng thời chỉ một booking nhận xe |

**Kiểm chứng:** full isolated clean verify **683/683 JUnit, 0 failure/error/skip**, JAR; RentalHandoverTest45 mới và638 hồi quy. Chromium **32/32**, width320/390/1440/no-JS/CSRF/quyền/XSS/two-tab, đã xem PNG. Fixture incoming processor ghi nhận advance trước browser, không stub endpoint ứng dụng, CSS/fonts cache; không provider tiền thật. Browser cuối xác nhận2handover/2event/2notify,2receipt/2obligation giữ nguyên, không refund/return/complete.

**Evidence:** `.scratch/phase6/p67/full-clean-verify.log`, `full-suite-runner.log`, `browser-check.log`, `browser-runtime.log`, `browser-evidence.json`, `before-{320,390,1440}.png`, `renting-{320,390,1440}.png`, `nojs-renting-390.png`, `progress.md` và final-audit. RED missing endpoint404; guard21 ban đầu17fail rồi21pass; UI thiếu form RED rồi GREEN. Browser phát hiện khối custody Owner đặt nhầm sidebar; thêm HTTP assertion nội dung main RED rồi sửa đúng vị trí và chạy lại full/browser. Test harness sửa kỳ vọng HTTP inactive Owner404 theo private-read guard hiện có (service403), không đổi privacy. Review tự thực hiện, chưa human review mới.

**Bảo toàn:** app DB vẫn7bảng/schema+toàn bộ rows fingerprint nguyên vẹn. Phase-0/rootPOM/scriptDB/migration và dirty files ngoài scope giữ nguyên. HEAD/index không đổi; không commit/push, restart8080, migration ứng dụng hoặc enable workflow/provider/scheduler. Fixture schema/random-port/private files đã dọn.

**Handoff P6.8:** Customer đúng booking sau vehicle_received_at mới gửi AFTER; draft/ảnh private nền P6.3 đã có, submitAfter và nhắc Owner/Admin sau1giờ chưa triển khai. Không tự ghi vehicle_returned_at từ AFTER, không cộng phí hoặc sửa evidence đã gửi. Return/70% và hủy/hoàn paid vẫn P6.9–P6.11. **62/86**, còn24; P4.2-A hoãn.

## Checkpoint P6.8 — 2026-11-02

Đủ **4/4 checklist**; phụ trách `huy69332`, reviewer được giao `thanhmit`; chưa có human review.

| Điều kiện | Triển khai và bằng chứng |
|---|---|
| AFTER đúng người/mốc | `submitAfter` + POST phase AFTER qua Car queue; Customer đúng booking/current ACTIVE/role, CONFIRMED và đã handover/chưa nhận lại. Cho gửi sớm/đúng ngày/trễ, không dùng endDate để giả định trả xe. Năm trường/4–10 ảnh được kiểm tra lại dưới khóa. SUBMITTED/event/Owner notice cùng commit; retry không nhân event/notice, không set returnedAt/tạo BALANCE |
| Bất biến và quyền | Nội dung/ảnh gốc không sửa/xóa sau gửi; draft chỉ tác giả, submitted đúng thành viên/Admin ACTIVE. Test private images, CSRF/IDOR/XSS, hash ảnh không đổi, thiếu evidence và notification failure rollback |
| Reminder bền vững | `RentalDeadlineJob` quét keyset50 theo dữ liệu DB; executor → Car queue → transaction/current Car/Booking/AFTER; đủ submittedAt+1giờ, chưa returned/chưa marker thì marker+Owner/Admin notices cùng commit. Clock biên3599/3600s, lỗi Admin rollback cả Owner notice/marker, reject ở cả2queue, retry,50row lỗi không chặn row51; scheduler30s thật phục hồi sau restart. Flag `carrent.rental.deadlines.enabled` mặc định tắt |
| UI và kiểm chứng | So sánh năm trường/ảnh BEFORE-AFTER, chờ nhận lại/NOT_RECEIVED rõ; ghi chú/km/energy không tính phí. Browser draft→submit/refresh/private/no-JS/mobile; hai connection submit/submit và reminder/return hai thứ tự |


**Kiểm chứng chung source cuối:** full isolated clean verify **746/746 JUnit**, 0 failure/error/skip, JAR (683 hồi quy + RentalAfterReturnTest49 + AfterReturnUiTest9 + RentalAfterReminderJobTest5). Lệnh:

```powershell
java '@.scratch/phase6/p68-p69/full-suite.args'
# .\mvnw.cmd -f .scratch/phase6/p68-p69/verification-pom.xml -o '-Dmaven.compiler.useIncrementalCompilation=false' '-Dtest=*Test' clean verify
```

POM/harness dùng toàn bộ source/resources thật, output/schema tạm riêng; không sửa rootPOM hoặc DB ứng dụng. Log `full-clean-verify.log`, manifest `verified-source-hashes.json`; Chromium **58/58**,16 PNG,14 SQL invariant, `browser-evidence.json`/`browser-fixture.log`/`browser-source-manifest.json`, không mock endpoint nghiệp vụ (CSS/fonts cache riêng). Browser incoming processor/inbox offline thật; không gọi provider tiền thật. Đã xem PNG320/1440 và no-JS390, không tràn trang.

**Review và sửa finding:** UI/job được giao file tách biệt; hai agent cross-review phần của nhau và domain chính. Không tạo thêm reviewer mới do giới hạn thread; đây là agent review, chưa có human reviewer `thanhmit`. Đã tái hiện rồi sửa NOT_RECEIVED khóa ORM trên event `@Immutable` (đổi native scalar current read), và tài khoản BANNED sau snapshot RR cũ vẫn được thao tác (current shared actor read sau Car/Booking). Các test hai connection xác nhận cả ban/đổi role. Đã sửa setup UTC/contract reset và race receipt: claim ngoài transaction, ReceiptApplicationService.apply bên trong; không bỏ Propagation.NEVER của processor.

**Bảo toàn:** HEAD/index, diff/hash phase0, nguyên `scriptDB.sql`/seed,9ảnh uploads và fingerprint schema/toàn bộ rows ứng dụng trước/sau bằng nhau; ứng dụng vẫn7bảng. Fixture DB/private files/port đã dọn. Không thay source để khớp kết quả browser sau khi chạy. Evidence riêng `.scratch/phase6/p68-p69/`; không ghi đè evidence đợt cũ.

**Tiến độ sau task P6.8:** 63/86, còn23; P6.9 được hoàn thành ngay trong cùng đợt dưới đây.

## Checkpoint P6.9 — 2026-11-02

Đủ **5/5 checklist**; phụ trách `huy69332`, reviewer được giao `thanhmit`; chưa có human review.

| Điều kiện | Triển khai và bằng chứng |
|---|---|
| Ba quyết định vật lý | `ReturnReceiptForm` chỉ decision/note; RECEIVED_ACCEPTED ghi returnedAt + AFTER ACCEPTED; RECEIVED_DISPUTED ghi returnedAt + AFTER DISPUTED/event/note bắt buộc. NOT_RECEIVED giữ chưa trả và không BALANCE/deadline; note trống dùng câu xác nhận máy chủ vì SQL event yêu cầu note. Quyết định giống nhau lặp không nhân event/notice; quyết định khác sau nhận bị chặn |
| Atomic/lock | Owner current ACTIVE/role sau Car/Booking, rồi tạo/khóa `BOOKING:<id>:BALANCE` trước AFTER. Current detach+locking reread vượt stale managed snapshot. Đúng purpose/subject/payer Customer/collector Owner/snapshot; dueAt=server returnedAt+3600s, không chờ mở trang. Report/notify lỗi rollback obligation/timestamp/status/event; existing mismatched BALANCE bị từ chối, retry không reset |
| Guards/evidence | AFTER phải đã gửi, đúng Customer, submittedAt>=handover và không ở tương lai, đủ5trường/4–10ảnh. Owner đúng xe, không Admin thay nhận vật lý; overpost actor/time/amount bị bỏ qua. Form lỗi giữ input/escape/field errors, chưa trả/đã trả/dispute và số tiền/hạn/trạng thái70% chỉ đọc |
| Snapshot/custody | Test trả sớm/đúng/trễ giữ start/end/total/price snapshot; physical return giải phóng outstanding custody; date overlap vẫn giữ theo policy CONFIRMED hiện hành. Không auto-COMPLETED hoặc tính phụ phí |
| Hai connection thật | Submit/return/NOT_RECEIVED lặp, reminder/return hai thứ tự, report/obligation đổi sau RR snapshot, tài khoản ban/đổi role sau snapshot, return-vs-duplicate ADVANCE receipt hai thứ tự. Receipt/refund vẫn thuộc advance, không settle BALANCE hoặc xóa nợ; trigger lỗi thông báo rollback đồng thời |


**Kiểm chứng chung source cuối:** full isolated clean verify **746/746 JUnit**, 0 failure/error/skip, JAR (683 hồi quy + RentalAfterReturnTest49 + AfterReturnUiTest9 + RentalAfterReminderJobTest5). Lệnh:

```powershell
java '@.scratch/phase6/p68-p69/full-suite.args'
# .\mvnw.cmd -f .scratch/phase6/p68-p69/verification-pom.xml -o '-Dmaven.compiler.useIncrementalCompilation=false' '-Dtest=*Test' clean verify
```

POM/harness dùng toàn bộ source/resources thật, output/schema tạm riêng; không sửa rootPOM hoặc DB ứng dụng. Log `full-clean-verify.log`, manifest `verified-source-hashes.json`; Chromium **58/58**,16 PNG,14 SQL invariant, `browser-evidence.json`/`browser-fixture.log`/`browser-source-manifest.json`, không mock endpoint nghiệp vụ (CSS/fonts cache riêng). Browser incoming processor/inbox offline thật; không gọi provider tiền thật. Đã xem PNG320/1440 và no-JS390, không tràn trang.

**Review và sửa finding:** UI/job được giao file tách biệt; hai agent cross-review phần của nhau và domain chính. Không tạo thêm reviewer mới do giới hạn thread; đây là agent review, chưa có human reviewer `thanhmit`. Đã tái hiện rồi sửa NOT_RECEIVED khóa ORM trên event `@Immutable` (đổi native scalar current read), và tài khoản BANNED sau snapshot RR cũ vẫn được thao tác (current shared actor read sau Car/Booking). Các test hai connection xác nhận cả ban/đổi role. Đã sửa setup UTC/contract reset và race receipt: claim ngoài transaction, ReceiptApplicationService.apply bên trong; không bỏ Propagation.NEVER của processor.

**Bảo toàn:** HEAD/index, diff/hash phase0, nguyên `scriptDB.sql`/seed,9ảnh uploads và fingerprint schema/toàn bộ rows ứng dụng trước/sau bằng nhau; ứng dụng vẫn7bảng. Fixture DB/private files/port đã dọn. Không thay source để khớp kết quả browser sau khi chạy. Evidence riêng `.scratch/phase6/p68-p69/`; không ghi đè evidence đợt cũ.

**Tiến độ cuối đợt:** **64/86**, còn22 (45task mới xong23, còn22). **Tiếp theo P6.10**; P4.2-A vẫn hoãn. Apply/QR BALANCE, nhắc nợ70% và auto-COMPLETED thuộc P6.10; paid cancellation tiếp tục bị chặn đến P6.11. Scheduler chỉ bật trong schema/port fixture riêng.
Handoff: [P6.10](phase-6-batch-2-handoff.md).

## Checkpoint P6.10 — 2026-11-02

**Checkpoint P6.10 — 2026-11-02:** đủ **6/6 checklist**: QR BALANCE15phút độc lập hạn nợ1giờ, receipt QR cũ/dư/trùng/thiếu, auto-COMPLETED atomic sau physical return, dispute giữ evidence và nhắc nợ một lần. Full isolated clean verify **783/783 JUnit, 0 failure/error/skip**, JAR; Chromium **63/63**, 19 ảnh, 12 SQL invariant. **65/86 task**, còn21 (45task mới xong24). Tiếp theo **P6.11 riêng**. Phụ trách `huy69332`, reviewer được giao `thanhmit`; có agent review, chưa có human contributor review. P6.8–P6.10 đã tích hợp Git; DB ứng dụng vẫn7bảng, phase0/seed/ảnh giữ nguyên. Không migrate/bật workflow/provider/scheduler ứng dụng, restart8080, chuyển tiền thật hoặc stage/commit/push. P4.2-A live vẫn chờ.

| Checklist | Triển khai và bằng chứng |
|---|---|
| 1 — Attempt | `RentalBalancePaymentHandler` riêng, đăng ký BOOKING_BALANCE khi `carrent.rental.balance.enabled=true`; không thay handler ADVANCE. QR15phút không bị cắt theo dueAt; nợ quá hạn vẫn mở QR được. Cancel/expiry chỉ kết thúc attempt; POST tạo lại, GET/poll read-only. Attempt active đã hết hạn được kết thúc khi POST tạo lại, không cần chờ maintenance. |
| 2 — Receipt cũ/dư/trùng | Payment core cho BALANCE QR expired/cancelled thanh toán đúng obligation còn OPEN/nợ. Phân bổ tối đa phần còn thiếu; dư tạo EXCESS_BALANCE, đã đủ tạo DUPLICATE_PAYMENT, nguồn payout đúng Owner. Provider replay idempotent; receipt/obligation/booking/refund/notice cùng commit. |
| 3 — Chuyển thiếu | Receipt thấp hơn remaining giữ REVIEW_REQUIRED, không cộng dồn, không complete. UI giữ cảnh báo review dù nợ đã đủ; attempt đang mở có nút hủy riêng trước khi tạo lại. UI/poll tách trạng thái receipt/refund khỏi nợ và booking. |
| 4 — Hoàn tất | `BookingService.applyBalance(receiptId)` chỉ VIETQR_V1 đã giao/nhận lại, snapshot/parties/obligation hợp lệ và SETTLED đủ tiền; tự COMPLETED atomic. AFTER DISPUTED không chặn, text/ảnh/event gốc giữ nguyên; Owner complete manual vẫn bị chặn. |
| 5 — Quá hạn | `BalanceDeadlineService` current Car→Booking→BALANCE, dueAt tới và còn nợ thì marker balance_overdue_notified_at + Customer/Owner/Admin notices cùng commit một lần. Job keyset50/cursor riêng qua executor→Car queue; restart/reject/rollback không mất việc. Chỉ cảnh báo, không ban/chặn đặt/phạt/refund advance. |
| 6 — Gate | Test thực MySQL đủ/thiếu/dư/trùng/oldQR/sau hạn/restart/legacy/dispute, stale RR payer/obligation, concurrent receipts, reminder-vs-payment và return retry-vs-receipt hai thứ tự. Actual AFTER ảnh/submit/confirmReturn; rollback first return không tạo payable debt. Browser CSRF/quyền/POST/no-JS/mobile/đóng tab, GET/status không mutate. |

**Kiểm chứng source cuối:** isolated clean verify **783/783 JUnit**, 0 failure/error/skip, JAR; Chromium **63/63**, 19 PNG320/390/1440/no-JS và 12 SQL invariant. Targeted mới37/37 đạt trước full gate. Lệnh:

```powershell
java '@.scratch/phase6/p610/full-suite.args'
# .\mvnw.cmd -f .scratch/phase6/p610/verification-pom.xml -o '-Dmaven.compiler.useIncrementalCompilation=false' '-Dtest=*Test' clean verify
```

POM/output/schema fixture riêng; source/resources thật. Evidence `.scratch/phase6/p610/`: `full-clean-verify.log`, `targeted-green.log`, `verified-source-hashes.json`, `browser-evidence.json`, `browser-source-manifest.json`, `browser-sql-invariants.txt`, `browser-preservation.json`, `protected-checks.json`. Browser dùng HTTP/inbox/domain thật trong fixture offline, không mock endpoint nghiệp vụ. Provider/live chưa được kiểm chứng.

**Bảo toàn và giới hạn:** DB ứng dụng vẫn7bảng, fingerprint schema/rows và phase0/seed/ảnh không đổi; schema/private files/port fixture được dọn. Flags ADVANCE/BALANCE/deadlines chỉ bật fixture, cấu hình mặc định ứng dụng không đổi. Không migrate DB ứng dụng, restart8080, tiền thật hoặc stage/commit/push. Có phân công agent độc lập và review; chưa có human reviewer `thanhmit`. P4.2-A giữ pending.

**Tiếp theo:** [P6.11 riêng](phase-6-batch-3-handoff.md). Không gộp P6.12/P6.13 hoặc bật live từ fixture.

## Checkpoint P6.11 — 2026-11-02

**Checkpoint lịch sử P6.11 — 2026-11-02:** đủ **5/5 checklist**: Customer/Owner hủy trước handover, snapshot thời gian/phí/lý do và nghĩa vụ hoàn atomic; receipt gốc giữ tiền đã nhận. Full isolated clean verify **816/816 JUnit, 0 failure/error/skip**, JAR; Chromium **79/79**,25PNG,7/7SQL invariant. **66/86 task**, còn20 (45task mới xong25). Tiếp theo **P6.12 → P6.13** cùng đợt. Phụ trách `huy69332`, reviewer được giao `thanhmit`; có agent review, chưa có human contributor review. Nhánh `feat/phase6-paid-cancellation` từ main `a9c2a28`, chưa commit/push. DB ứng dụng vẫn7bảng, phase0/config/seed/uploads giữ nguyên; không migrate/bật workflow/provider/scheduler ứng dụng, restart8080 hoặc chuyển tiền thật. P4.2-A live vẫn chờ.

Chi tiết SDD §14.37. Lệnh: `java @.scratch/phase6/p611/full-suite.args`; POM/output/schema riêng, tương đương Maven Wrapper `clean verify` trên source thật. Evidence mới `.scratch/phase6/p611/`. Migration cancellation-reason chỉ chạy fixture; migration rental cũ giữ nguyên. Bảo toàn phase0/appDB/config/uploads; provider live chưa kiểm chứng. [Handoff đợt4](phase-6-batch-4-handoff.md).


## Checkpoint P6.12 và P6.13 — 2026-11-02

**Checkpoint P6.12 + P6.13 — 2026-11-02:** hoàn thành riêng **5/5 + 5/5 checklist**. Timeline Customer/Owner/Admin chỉ đọc, các mốc tiền/vật lý/hoàn độc lập; audit queue/deadline/current actor và gate rental liên tục. Full isolated **clean verify 856/856 JUnit, 0 failure/error/skip**, JAR; Chromium **109/109** trên source cuối, 320/390/1440 và no-JS. **68/86 task**, còn18 (45task mới xong27). Phụ trách `huy69332`, reviewer được giao `thanhmit`; có agent review, chưa có human contributor review. Nhánh `feat/phase6-rental-gate` từ main `82a3852`, chưa stage/commit/push. DB ứng dụng vẫn7bảng, phase0/config/seed/uploads giữ nguyên. Không migrate/bật workflow/provider/scheduler ứng dụng, restart8080 hoặc chuyển tiền thật. Dừng trước Phase8; tiếp theo **P8.1 riêng**, phụ trách `ducbinhnguyen07ars`. P4.2-A live vẫn chờ.

Đủ10/10 mục, giữ5/5 riêng mỗi task. Chi tiết checklist→source→evidence, RED/GREEN và giới hạn tại SDD §14.38 và `.scratch/phase6/p612-p613/ledger.md`. Các test/browser của P6.11 không dùng thay gate đợt này. Handoff nghiệm thu và prompt lần sau tại [đợt4](phase-6-batch-4-handoff.md).
