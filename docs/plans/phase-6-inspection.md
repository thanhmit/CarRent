# CarRent — Phase 6: Biên bản bàn giao và trả xe — Implementation Plan

**Ngày:** 2026-10-12

**Trạng thái:** Thiết kế được chủ dự án duyệt; P6.1–P6.14 chưa triển khai. File này là kế hoạch, chưa phải bằng chứng code/DB đã có.

> Chạy theo 4 đợt dưới đây sau Phase 4–5. Checkbox theo dõi từng task; giữ checkout/nhánh/thay đổi đang có, không tự stage/commit/push (D-19). Không triển khai Phase 6 trong lần cập nhật tài liệu này.

**Goal:** Owner báo cáo trước thuê, Customer xác nhận nhận xe; Customer báo cáo sau thuê, Owner xác nhận nhận lại. Lưu bằng chứng riêng tư, ghi nhận bất đồng cho Admin xử lý, cho nhận chưa PAID và chỉ complete sau khi xe đã trả, điều kiện biên bản đạt và PAID.

**Architecture:** Module `inspection` trong MVC hiện có, mỗi Booking có tối đa BEFORE/AFTER. BookingService sở hữu create/cancel/complete; InspectionService sở hữu nháp/gửi/xác nhận/event. Dùng repository biên bản trong BookingService cho guards; InspectionService gọi hợp đồng khóa của BookingService, không tạo phụ thuộc service vòng. Storage riêng tư có controller stream, độc lập ảnh listing. PaymentService giữ protocol D-13 và thêm Owner cash giả lập. Không thêm BookingStatus hay thư viện mới.

**Tech Stack:** Java 21, Spring Boot 4.1.1, MVC/Thymeleaf/Tailwind theo D-23, Spring Security session/CSRF, JPA/MySQL 8, ImageIO JDK, Maven Wrapper. Hibernate `ddl-auto=none`.

**Spec:** [SDD v2.14](../SDD.md) FR-10–FR-13/FR-16, BR-08–BR-15, §7.9, §8–§10, NFR-06; [decisions](../decisions.md) D-07/D-13/D-15/D-16/D-19/D-24/D-25. Các checkpoint Phase 3 là kết quả lịch sử; không sửa bằng chứng cũ thành hành vi mới.

## Global Constraints

- DB hiện có 7 bảng. P6.1 sẽ thêm 3 bảng và 3 cột Booking đúng SDD §7.9, bảo toàn data/seed. Cập nhật migration và bootstrap cùng contract, không DROP/TRUNCATE/DDL lúc boot; không unique payments.booking_id.
- Đơn có sẵn và đơn tạo trong lúc triển khai từng đợt vẫn `inspection_required=false`. Chỉ P6.14 kích hoạt BookingService.createBooking đặt true khi toàn module đạt gates. Fixture kiểm thử có thể được tạo với true; không đổi policy của dữ liệu thật. Ngừng ghi trong cửa sổ migration/kích hoạt.
- Mỗi mutation kiểm tra user ACTIVE/role/ownership theo DB và policy, Booking CONFIRMED, report/image membership. Client không được bind identity/status/policy/amount/timestamps. GET chỉ đọc, POST có CSRF, thành công PRG + flash.
- D-13: Car → Booking (nhiều row cùng xe theo ID tăng dần) → Payment nếu cần → Inspection → Image; current locking read và xử lý managed entity cũ. Event/notification cùng transaction; không giữ lock trong lúc chờ thao tác người dùng. Không lấy Payment sau khi đã khóa Inspection.
- BEFORE tác giả Owner; AFTER tác giả Customer. Draft chỉ tác giả xem/sửa. Submit khóa nội dung/ảnh vĩnh viễn. Bổ sung sau gửi bằng text event; không thay ảnh cũ, không update/delete event. Booking trạng thái cuối chỉ đọc bằng chứng.
- Submit phải đủ 5 trường và 4–10 ảnh theo FR-16; draft được thiếu. JPEG/PNG thực sự decode được, ≤5MiB/ảnh, ≤20 triệu pixel; upload từng đợt 1–4 ảnh ≤20MiB, report tổng ≤10. Giữ config multipart 5MB/30MB hiện tại. Không sử dụng storage listing public cho ảnh biên bản.
- Pickup: Customer xác nhận “đồng ý và đã nhận” khi `start_date ≤ hôm nay < end_date`; không cần PAID. Cancel chỉ trước pickup. Return: Owner nhận vật lý độc lập đồng ý tình trạng; Customer gửi AFTER không tự ghi xe đã trả.
- Complete chính sách mới: CONFIRMED + BEFORE ACCEPTED + hai timestamp + AFTER ACCEPTED/RESOLVED + PAID. Owner bấm thủ công, được trả sớm/đúng ngày; total/dates không đổi. Legacy giữ luật cũ, có nhãn riêng.
- Không phí phạt/cọc/bồi thường/tự refund do trả sớm, thanh toán thật/trả góp, GPS/EXIF xác minh, AI nhận diện, scheduler hoặc audit toàn app. Owner cash vẫn CASH_SIMULATED.
- Không dùng dữ liệu seed để verify mutation. Fixture định danh `p6-<runId>`; chụp baseline DB/ảnh seed. Backup/cleanup riêng fixture theo FK; không xóa bằng chứng local, không in password vào log/command history.

## 4 đợt và checkpoint

| Đợt | Task | Đầu ra bắt buộc trước chuyển đợt |
|---|---|---|
| 1 | P6.1–P6.3 | Migration/mapping pass, row cũ giữ nguyên, nháp và ảnh private có ownership/cleanup |
| 2 | P6.4–P6.7 | Owner BEFORE → Customer nhận/bất đồng; không hủy sau nhận; CTA/mobile đúng |
| 3 | P6.8–P6.11 | Customer AFTER → Owner nhận/bất đồng; returned/unpaid thanh toán được; complete/cash an toàn |
| 4 | P6.12–P6.14 | Admin resolve, concurrency/privacy/legacy/E2E và cutover pass; kích hoạt policy đơn mới |

## Hợp đồng dữ liệu và service dùng chung

Schema/length/FK/index/CHECK/whitelist lấy nguyên §7.9, không tạo bảng người dùng/role/địa chỉ hoặc enum Booking mới.

| Report | Trạng thái / thao tác | Kết quả |
|---|---|---|
| BEFORE | DRAFT → SUBMITTED (Owner) | Ghi submitted_at + SUBMITTED event; notify Customer |
| BEFORE | SUBMITTED/RESOLVED → ACCEPTED (Customer nhận) | vehicle_received_at một lần + PICKUP_CONFIRMED; notify Owner |
| BEFORE | SUBMITTED/RESOLVED → DISPUTED (Customer chưa nhận) | Lý do + DISPUTED; notify Owner/Admin ACTIVE; không pickup |
| AFTER | DRAFT → SUBMITTED (Customer) | Ghi submitted_at + SUBMITTED; notify Owner; chưa return |
| AFTER | SUBMITTED + RECEIVED_ACCEPTED (Owner) | ACCEPTED + vehicle_returned_at + RETURN_CONFIRMED; notify Customer |
| AFTER | SUBMITTED + RECEIVED_DISPUTED (Owner) | DISPUTED + vehicle_returned_at + RETURN_CONFIRMED/DISPUTED; notify Customer/Admin ACTIVE |
| AFTER | SUBMITTED + NOT_RECEIVED (Owner) | Giữ SUBMITTED, timestamp NULL, RETURN_NOT_RECEIVED + lý do; notify Customer bằng INSPECTION_SUBMITTED |
| Cả hai | DISPUTED → RESOLVED (Admin) | Kết luận + RESOLVED, notify hai bên; không sửa timestamp/money/Booking |
| Cả hai | ADDENDUM sau submit, booking chưa ở trạng thái cuối | Text event; không đổi status/bản gốc, không tự phát notification |

AFTER RESOLVED chỉ đóng bất đồng; Owner vẫn complete theo guards. BEFORE RESOLVED không là Customer đã nhận: Customer phải xác nhận nhận hoặc mở lại bất đồng khi chưa nhận. Owner đã xác nhận trả không được ghi trả lại lần hai; NOT_RECEIVED không làm mất dữ liệu và không là một dispute tình trạng. Gửi lặp transition thành công bị chặn; repeated NOT_RECEIVED với cùng lý do như event Owner cuối bị chặn để không spam. Addendum cho phép nhiều ghi chú hợp lệ.

**Interface cụ thể dự kiến:**

- `InspectionForm(String conditionNote, String visibleDamage, Integer odometerKm, Integer energyPercent, String accessoriesNote)` (DTO bind thường như form hiện có); length/numeric bounds ngay cả draft, required kiểm ở submit.
- `ReturnReceiptForm`: `ReturnReceiptDecision decision` ∈ {RECEIVED_ACCEPTED, RECEIVED_DISPUTED, NOT_RECEIVED}; `String note` ≤2000, bắt buộc với hai lựa chọn cuối.
- `BookingService.lockBookingForInspection(Long bookingId, CustomUserPrincipal principal) → Booking`, `@Transactional(propagation=MANDATORY)`: tái dùng lockedBooking Car→Booking, đọc lại user/ownership cho Customer/Owner/Admin liên quan, không tự cho phép một role thực hiện mọi action. Mỗi action InspectionService kiểm tra role cụ thể; BookingService không gọi InspectionService.
- `InspectionService.getView(Long bookingId, InspectionPhase phase, CustomUserPrincipal principal) → InspectionView`: report nullable để render empty state, image IDs/events và quyền CTA; không trả storageKey/path vật lý cho view.
- `InspectionService.getSummary(Long bookingId, CustomUserPrincipal principal) → List<InspectionView>`: dùng cho panel chi tiết Booking, ẩn toàn bộ nội dung/ảnh nháp khỏi người không phải tác giả, trả report nullable thay vì làm lỗi trang Booking. GET detail report gọi getView và trả 404 nếu nháp của người khác; không dùng summary để bypass quyền detail/ảnh.
- `InspectionService.getImage(Long bookingId, InspectionPhase phase, Long imageId, CustomUserPrincipal principal) → ImageResponse` (record Resource content/String mediaType/long sizeBytes): kiểm tra quyền và membership rồi mở Resource private; Controller chỉ stream content và headers.
- `InspectionService.saveDraft(Long bookingId, InspectionPhase phase, InspectionForm form, CustomUserPrincipal principal) → BookingInspection`.
- `InspectionService.addImages(Long bookingId, InspectionPhase phase, List<MultipartFile> files, CustomUserPrincipal principal) → List<InspectionImage>`; `deleteDraftImage(Long bookingId, InspectionPhase phase, Long imageId, CustomUserPrincipal principal) → void`.
- `InspectionService.submitBefore(Long bookingId, CustomUserPrincipal principal)`, `receiveVehicle(Long bookingId, CustomUserPrincipal principal)`, `disputeBefore(Long bookingId, String note, CustomUserPrincipal principal)`, `submitAfter(Long bookingId, CustomUserPrincipal principal)`, `recordReturn(Long bookingId, ReturnReceiptForm form, CustomUserPrincipal principal)`, `addNote(Long bookingId, InspectionPhase phase, String note, CustomUserPrincipal principal)`, `resolveDispute(Long inspectionId, String note, CustomUserPrincipal principal)` đều trả void.
- `PaymentService.recordCashReceipt(Long bookingId, CustomUserPrincipal principal) → Payment`; chỉ Owner của xe, CONFIRMED/chưa PAID, method CASH_SIMULATED, không simulateFailure hoặc nhận amount từ client.
- `InspectionView`: bookingId/phase/report/images/events, `canEditDraft`, `canSubmit`, `canReceive`, `canDispute`, `canRecordReturn`, `canAddNote`, `canResolve` và lý do chặn. Read DTO chỉ cho hiển thị, mutator luôn kiểm tra lại dưới khóa.
- `BookingService.canCancel(Booking booking) → boolean`, `canComplete(Booking booking, boolean hasPaid, BookingInspection before, BookingInspection after) → boolean` dùng chung UI/Service; service truyền dữ liệu current dưới khóa. Hàm pure giữ nhánh legacy, không tự query hoặc làm authorization.

Nháp được tạo bằng POST saveDraft; GET draft không insert. Upload ảnh cần nháp đã có để có author/report ID. Chưa có report GET detail là empty state với CTA theo role; unauthorized/draft tác giả khác trả 404.

## Verification chạy khi implement

P6.1 tạo runner `.scratch/phase6/verify-phase6.ps1` và Java harness `.scratch/phase6/Phase6ContractCheck.java`, tái dùng cách khởi tạo Spring/JDBC/HTTP-CSRF từ bằng chứng Phase 3. Runner nhận `-Case` theo các tên dưới đây, fail bằng exit code khác 0/assert và ghi log timestamp/commit đang chạy. Các command là đầu ra phải tạo khi implement, **chưa tồn tại do lần này chỉ lập plan**.

```powershell
.\mvnw.cmd -q compile
.\.scratch\phase6\verify-phase6.ps1 -Case schema
```

Runner chạy schema trên DB kiểm chứng riêng trước áp dụng DB local; trỏ app/connection kiểm chứng bằng cấu hình local/env, không ghi password vào script. Các case nghiệp vụ dùng fixture riêng, cleanup theo FK events → images → inspections → payment/notification → booking → car/users và dọn đúng file fixture đã kiểm tra đường dẫn. Verify concurrency dùng hai connection/transaction MySQL thực, không chỉ mock/Java threads một transaction. Browser kiểm trên server kiểm chứng có CSRF/session thật. Dùng `ddl-auto=validate` riêng để verify mapping; baseline luôn `none`.

## Đợt 1 — DB/model/private storage

### Task P6.1 — Migration, entities và repositories

**Files:**

- Create: `docs/db/phase-6-inspection.sql`.
- Modify: `scriptDB.sql`, `src/main/java/com/carrent/booking/Booking.java`, `BookingRepository.java`.
- Create dưới `src/main/java/com/carrent/inspection/`: `BookingInspection.java`, `InspectionImage.java`, `InspectionEvent.java`, `InspectionPhase.java`, `InspectionStatus.java`, `InspectionAction.java`, `BookingInspectionRepository.java`, `InspectionImageRepository.java`, `InspectionEventRepository.java`.
- Create: `.scratch/phase6/verify-phase6.ps1`, `.scratch/phase6/Phase6ContractCheck.java` (bằng chứng local, Git ignored).

**Interfaces:** consumes SDD §7.9/7-table contract; produces 10-table contract, marker/timestamps Booking. `BookingInspectionRepository.findByBookingIdAndPhase(Long, InspectionPhase) → Optional<BookingInspection>` cho read, `findByBookingIdForUpdate(Long) → List<BookingInspection>` current lock theo id; `InspectionImageRepository.findByInspectionIdForUpdate(Long) → List<InspectionImage>` theo id; `InspectionEventRepository.findByInspectionIdOrderByCreatedAtAscIdAsc(Long) → List<InspectionEvent>`. `BookingRepository.findOutstandingCustodyForUpdate(Long carId) → List<Booking>` lọc policy true/received nonnull/returned null, khóa current theo id.

- [ ] **RED:** runner `-Case schema` trên clone DB 7 bảng ghi rõ thiếu 3 bảng/3 cột; chụp baseline row count/nội dung 7 bảng và file seed, không sửa DB thật.
- [ ] **Implement:** viết migration ALTER/CREATE và checks nguồn/đích. MySQL DDL implicit commit: backup trước, dừng ghi, fail khi contract sai; chạy lại không reset/backfill cờ. Map Java Long/Integer/LocalDateTime, enum string, đúng nullable/length. Cập nhật bootstrap máy mới cùng contract, seed cũ giữ false. Chưa đổi createBooking sang true.
- [ ] **GREEN:** `.\mvnw.cmd -q compile` và runner `-Case schema`: migrate clone, boot validate, đọc legacy false/timestamp NULL, nội dung cũ không đổi. Thử UNIQUE phase/FK/numeric/check timestamp sai phải fail; chạy SQL lần hai bảo toàn data; bootstrap DB mới có cùng schema. Cleanup đúng DB kiểm chứng; chỉ sau pass mới áp migration lên local theo cửa sổ đã backup, không coi rollback JDBC khôi phục DDL.

### Task P6.2 — Storage private và stream ảnh có quyền

**Files:**

- Create: `src/main/java/com/carrent/storage/InspectionStorageService.java`.
- Create: `src/main/java/com/carrent/inspection/InspectionController.java`, `InspectionService.java`, `InspectionView.java` (phần read/image).
- Modify: `src/main/java/com/carrent/config/SecurityConfig.java`, `.gitignore`, harness Phase6.
- Review: `WebConfig.java`, `StorageService.java` (không thêm handler public cho private folder).

**Interfaces:** `InspectionStorageService.validate(MultipartFile) → ValidatedImage` (record bytes/mediaType/extension, dimensions đã check), `store(ValidatedImage) → StoredImage` (record storageKey/mediaType/sizeBytes), `load(String storageKey) → Resource`, `delete(String storageKey) → void`; resolve path nằm dưới private root. GET ảnh ở SDD §10 do InspectionService cấp Resource sau authorization/quan hệ IDs, không đọc path client. Service không đưa key ra DTO.

- [ ] **RED:** runner `-Case privacy`: anonymous/người ngoài/Customer khác/Owner khác không nhận bytes; thử URL static private, nhầm booking/phase/image, user INACTIVE với session cũ. Ảnh listing seed vẫn public 200.
- [ ] **Implement:** validate cả extension, media type, bytes decode ImageIO JPEG/PNG; đọc dimensions trước decode để chặn >20MP, size 1–5.242.880. UUID filenames, private root ngoài resources; thêm ignore `/private-uploads/`, không thay quyền `/uploads/**`. Security authenticated cho route ảnh mới, Service kiểm tra user DB và membership kể cả nested IDs. Stream headers private/no-store/nosniff; không log path/nội dung.
- [ ] **GREEN:** compile + runner `-Case privacy`: tác giả xem draft được, bên còn lại/Admin không thấy draft; report đã gửi chỉ 3 chủ thể được xem. Payload hỏng/giả MIME/đuôi sai/traversal/0B/>5MiB/>20MP bị chặn, không row/file; JPEG/PNG hợp lệ đúng bytes/content-type/header; static private không lộ ảnh, public baseline còn nguyên.

### Task P6.3 — Nháp và upload ảnh theo đợt

**Files:**

- Create: `src/main/java/com/carrent/inspection/InspectionForm.java`.
- Modify: `InspectionService.java`, `InspectionController.java`, `BookingService.java`, harness Phase6.

**Interfaces:** produces saveDraft/addImages/deleteDraftImage và `BookingService.lockBookingForInspection` đã định nghĩa. Routes GET/POST draft, POST images/delete theo §10. Chỉ AFTER có vehicle_received_at/BEFORE đúng Owner; policy true/CONFIRMED. Ảnh upload tham số `files`, text form không nhận identity.

- [ ] **RED:** runner `-Case draft`: wrong author/phase/legacy/PENDING/AFTER trước nhận/draft edit sau submit phải không ghi; request 5 ảnh hoặc tổng report vượt10 bị chặn; không có draft thì upload trả lỗi “Lưu nháp trước khi thêm ảnh”.
- [ ] **Implement:** GET trả form rỗng hoặc draft, POST mới tạo row UNIQUE dưới khóa. Draft kiểm max length/range, submit-required để task sau. Prevalidate toàn batch trước giữ khóa/ghi file, dùng TransactionTemplate cho upload để giới hạn đoạn giữ khóa: lock current report/count, store validated bytes, save rows, register rollback cleanup. File write thất bại giữa batch dọn mọi file mới. Delete row dưới khóa, chỉ delete file afterCommit; đọc current image membership theo D-13. Không phụ thuộc vòng services.
- [ ] **GREEN:** compile + runner `-Case draft`: lưu text thiếu fields được; upload lần lượt 4+4+2 được tổng 10, thêm 1 bị chặn; xóa ảnh nháp rồi thêm lại được. Text draft còn nguyên khi upload lỗi. Fault/rollback giữ ảnh cũ, dọn ảnh mới, afterCommit không xóa ảnh báo cáo khác. Hai request tạo draft cùng phase chỉ một row, upload cùng report không vượt 10 ảnh. GET không thêm row.

**Checkpoint đợt 1:** schema/data/private storage/nháp pass, ghi log task IDs và baseline. Chưa kích hoạt policy cho booking tạo qua UI.

## Đợt 2 — BEFORE và Customer nhận xe

### Task P6.4 — Owner gửi BEFORE

**Files:** Modify `InspectionService.java`, `InspectionController.java`, `NotificationService.java`, harness Phase6.

**Interfaces:** produces `submitBefore`; BEFORE/submit route. Notification type `INSPECTION_SUBMITTED`, related BOOKING → Customer, message ngắn ≤1000; dùng report ID trong timeline, booking ID trong link.

- [ ] **RED:** runner `-Case before-submit`: 3 ảnh/field thiếu/range sai/wrong Owner/booking không CONFIRMED không submit; client gửi amount/status/timestamp không có hiệu lực.
- [ ] **Implement:** khóa current Booking/report/images, validate 5 fields + 4–10 ảnh, DRAFT → SUBMITTED, submitted_at do server ghi; SUBMITTED event + notification atomic. Từ đó cấm mọi edit/delete/upload bản gốc. Chưa ghi pickup timestamp và không kiểm PAID.
- [ ] **GREEN:** compile + runner `-Case before-submit`: 4 ảnh đủ →1 event/1 notify, ảnh/note giữ nguyên; chưa PAID gửi được. Repeat hoặc stale form sau submit bị chặn, không lặp event/notify; notify failure rollback toàn transition. Owner được chuẩn bị report trước start_date.

### Task P6.5 — Customer nhận xe hoặc bất đồng BEFORE

**Files:** Modify `InspectionService.java`, `InspectionController.java`, `BookingRepository.java`, `NotificationService.java`, harness Phase6.

**Interfaces:** produces receiveVehicle/disputeBefore; BEFORE/receive và BEFORE/dispute, lý do tham số `note` trim1–2000. Notifications VEHICLE_RECEIVED→Owner, INSPECTION_DISPUTED→Owner + các Admin ACTIVE. Các type mới của module thêm whitelist ở các task cần dùng.

- [ ] **RED:** runner `-Case pickup`: unpaid được nhận khi hợp lệ; sai Customer/ngoài ngày thuê/BEFORE DISPUTED chưa resolve/xe còn ở booking khác phải bị chặn. Baseline trước sửa chứng minh cancel-vs-pickup cần serialize.
- [ ] **Implement:** check policy/CONFIRMED/report SUBMITTED hoặc RESOLVED và received NULL; receive đọc current outstanding custody cùng xe dưới Car lock, chỉ loại booking hiện tại. Accepted + received_at server + PICKUP_CONFIRMED atomic; không Payment guard. Dispute chuyển DISPUTED + note/event/notify, giữ timestamp NULL. Bổ sung type whitelist, không dùng listing_status để suy luận đã trả xe.
- [ ] **GREEN:** compile + runner `-Case pickup`: nhận unpaid1 lần, pending amount=total; không tạo PAID. Dispute không ghi pickup; RESOLVED vẫn cần Customer xác nhận. Booking trước quá hạn/chưa trả chặn pickup mới dù interval không trùng; đã trả/chưa PAID không chặn custody. Ngày trước start/ngày end bị chặn; nhận trong khoảng hợp lệ đạt.

### Task P6.6 — Cancellation/refund theo policy và đơn cũ

**Files:** Modify `BookingService.java`, `BookingController.java`, `src/main/resources/templates/booking/detail.html`, harness Phase6.

**Interfaces:** produces `canCancel(Booking)` pure; cancelBooking kiểm lại current timestamp sau Car→Booking và trước Payment/refund. consumes marker policy và timestamp, không phụ thuộc InspectionService.

- [ ] **RED:** runner `-Case cancellation`: policy mới đã nhận +CONFIRMED+PAID bị code cũ cho cancel/refund; case phải tái hiện trên fixture. Legacy PENDING/CONFIRMED vẫn theo luật cũ.
- [ ] **Implement:** guard status hợp lệ và (legacy hoặc received_at NULL), dùng chung pure predicate cho canCancel UI; refund chỉ khi guard qua, giữ logic atomic hiện có. Đã gửi/dispute BEFORE nhưng chưa nhận vẫn được cancel, evidence/event không bị xóa; report sau cancel chỉ đọc.
- [ ] **GREEN:** compile + runner `-Case cancellation`: trước pickup PAID cancel→REFUNDED; sau pickup unpaid/paid/đã trả đều cấm cancel, giữ payment/status/files. Legacy không bị ép inspection. Hai thứ tự cancel/pickup: cancel trước→pickup blocked; pickup trước→cancel blocked; không CANCELLED+received_at.

### Task P6.7 — UI BEFORE, CTA và mobile

**Files:**

- Create: `src/main/resources/templates/inspection/form.html`, `inspection/detail.html`, `fragments/inspection-summary.html`.
- Modify: `InspectionController.java`, `InspectionService.java`, `InspectionView.java`, `BookingController.java`, `OwnerBookingController.java`, `templates/booking/detail.html`, `templates/owner/booking-detail.html`.
- Create: `src/main/resources/static/js/inspection-form.js` (chỉ preview/input assistance).
- Modify: harness Phase6.

**Interfaces:** produces GET detail/draft + model InspectionView, form POST PRG; Owner detail → BEFORE draft, Customer detail → BEFORE review. Booking panel dùng getSummary để không lộ nháp của bên khác. Camera input `accept="image/jpeg,image/png" capture="environment"` + gallery input; tối đa 4 ảnh/request và 10 ảnh/report hiển thị rõ. JS không quyết định auth/validity.

- [ ] **RED:** runner `-Case before-ui`/browser: Customer không tìm được CTA nhận xe, unpaid thiếu cảnh báo, draft bị lộ cho bên khác, mobile hoặc không JS mất form.
- [ ] **Implement:** layout/design system D-23; Owner draft save text rồi upload theo đợt, gallery/private GET; Customer review/damage/accessories/odometer/energy/address và nút xác nhận receipt có confirm rõ “đã nhận xe thực tế”. Form dispute có note; status/timeline server. CTA theo role/current policy, legacy nhãn rõ, nhận rồi ẩn cancel nhưng backend vẫn bảo vệ. Text dùng th:text escape, photo có alt/labels/lỗi field, input hoạt động không JS.
- [ ] **GREEN:** compile + runner `-Case before-ui` và browser desktop/mobile: Owner tạo4ảnh→submit, Customer nhận chưa PAID→“Đang thuê / Chưa thanh toán”, cancel bị khóa; dispute nhánh giữ chờ nhận. Refresh không resubmit, thiếu CSRF403, giả role/nestedID không ghi. Disable JS vẫn lưu/upload/xác nhận được; camera hint không hứa xác minh thời điểm chụp.

**Checkpoint đợt 2:** flow BEFORE/receipt/cancel đạt trên fixture policy mới; text/ảnh gốc giữ nguyên, chưa có complete theo policy mới cho đến đợt3.

## Đợt 3 — AFTER, nhận lại xe và thanh toán

### Task P6.8 — Customer lập/gửi AFTER

**Files:** Modify `InspectionService.java`, `InspectionController.java`, `InspectionView.java`, harness Phase6.

**Interfaces:** produces submitAfter; AFTER draft/images tái dùng P6.3, AFTER/submit. INSPECTION_SUBMITTED→Owner.

- [ ] **RED:** runner `-Case after-submit`: trước pickup/khác Customer/legacy/booking terminal không tạo AFTER; chỉ gửi ảnh không được coi xe đã trả. Submit không đủ 4 ảnh phải fail.
- [ ] **Implement:** nhận rồi mới draft AFTER, chưa returned, CONFIRMED; cùng rules submit BEFORE, có date-return snapshot từ Booking. Hiển thị warning odometer thấp hơn BEFORE nhưng không chặn submit/physical return; các sai khác để Owner/Admin xem. Không đổi start/end/price/total, không tự phí/refund.
- [ ] **GREEN:** compile + runner `-Case after-submit`: đủ 4 ảnh → SUBMITTED/event/Owner notification, vehicle_returned_at vẫn NULL. Gửi sớm, đúng ngày end hoặc trễ đều được sau khi đã nhận xe thực tế. Submit lặp/sửa/xóa ảnh sau submit bị chặn; rollback không để event/notification/file một phần.

### Task P6.9 — Owner ghi nhận nhận lại xe độc lập tình trạng

**Files:** Create `src/main/java/com/carrent/inspection/ReturnReceiptDecision.java`, `ReturnReceiptForm.java`; Modify `InspectionService.java`, `InspectionController.java`, `NotificationService.java`, harness Phase6.

**Interfaces:** produces recordReturn và AFTER/receipt; payload `decision`, `note`. VEHICLE_RETURNED → Customer; INSPECTION_DISPUTED → Customer/Admin ACTIVE. NOT_RECEIVED giữ report SUBMITTED, thêm RETURN_NOT_RECEIVED với note và thông báo INSPECTION_SUBMITTED cho Customer về việc chưa nhận lại xe.

- [ ] **RED:** runner `-Case return-receipt`: gửi report không tự nhận lại xe; thiếu decision/note hoặc sai Owner không ghi dữ liệu. Chọn “đã nhận/bất đồng” vẫn phải ghi timestamp trả.
- [ ] **Implement:** khóa current Booking/AFTER, yêu cầu vehicle_received_at có giá trị, vehicle_returned_at NULL và report SUBMITTED. RECEIVED_ACCEPTED → ACCEPTED + RETURN_CONFIRMED; RECEIVED_DISPUTED → DISPUTED + RETURN_CONFIRMED/DISPUTED; cả hai ghi server vehicle_returned_at và notification atomic. NOT_RECEIVED giữ timestamp/status, thêm note/event và chống lặp event cuối. Không để bất đồng xóa timestamp trả hoặc ghi trả trước nhận.
- [ ] **GREEN:** compile + runner `-Case return-receipt`: cả 3 lựa chọn và thứ tự thời gian; chưa/đã PAID đều nhận lại được. Xác nhận trả lặp không ghi timestamp/event/notification lần hai; NOT_RECEIVED rồi xác nhận đã nhận hợp lệ. Trả có bất đồng ghi xe đã trả và chặn complete; lỗi notification rollback tất cả.

### Task P6.10 — Cash giả lập, complete guard và hiển thị quyền thao tác

**Files:** Modify `payment/PaymentService.java`, `PaymentController.java`, `BookingService.java`, `OwnerBookingController.java` (dưới `src/main/java/com/carrent/`), harness Phase6.

**Interfaces:** produces recordCashReceipt, Owner cash route và canComplete pure. Cash dùng ref `CSH-<ownerId>-<epochMillis>-<random6>` ≤50 để phân biệt Owner ghi nhận với Customer mock method CASH_SIMULATED (ref MCK hiện có). Không nhận amount/actor/time từ request; method cố định CASH_SIMULATED, status PAID, paidAt server; PAYMENT_PAID → hai bên. canComplete dùng read model cho UI và locked current data cho Service.

- [ ] **RED:** runner `-Case payment-complete`: code cũ cho complete booking mới chưa PAID/thiếu return khi end đã qua, và cấm complete ngày end/trả sớm dù đủ bằng chứng. Cash/mock payment đồng thời không được tạo 2 PAID.
- [ ] **Implement:** completeBooking khóa Car → Booking → Payment → Inspection trước evaluate, dùng canComplete chung; legacy giữ `end_date < today`, không bị ép PAID. Bỏ predicate ngày hardcoded trong Owner controller, truyền đủ report/payment read model. Customer payment vẫn CONFIRMED-only: đã trả/chưa PAID còn CONFIRMED nên thanh toán được. Owner cash dùng cùng lock/role/ownership/at-most-one-PAID; tiền lấy total, không tự complete.
- [ ] **GREEN:** compile + runner `-Case payment-complete`: thiếu từng điều kiện đều bị chặn; đủ điều kiện được complete cả ngày end/trả sớm, tổng tiền/ngày không đổi. Trả chưa PAID → Customer pay → Owner complete; trả có bất đồng đã PAID chờ Admin. Cash chỉ Owner đúng/ACTIVE; sai role hoặc đã PAID bị chặn. Legacy complete như cũ. Lỗi payment/complete/notification rollback; hai connection cash-vs-pay chỉ một PAID.

### Task P6.11 — UI AFTER, so sánh và chờ thanh toán

**Files:**

- Modify: `InspectionController.java`, `InspectionService.java`, `InspectionView.java`, `BookingController.java`, `OwnerBookingController.java`, `admin/AdminBookingController.java`.
- Modify templates: `inspection/form.html`, `inspection/detail.html`, `fragments/inspection-summary.html`, `booking/detail.html`, `booking/list.html`, `owner/booking-detail.html`, `owner/bookings.html`, `admin/booking-detail.html`, `admin/bookings.html`, `payment/mock-payment.html`.
- Modify: harness Phase6.

**Interfaces:** produces panel/progress cho 3 role từ getSummary, list badges theo DB page, không N+1 ảnh mỗi row. Customer có CTA AFTER draft; Owner có CTA kiểm tra AFTER; Customer có payment CTA khi CONFIRMED/chưa PAID kể cả đã trả xe. Owner cash CTA cùng guard, ghi rõ giả lập và yêu cầu xác nhận đã thu. Hình so sánh dùng private image IDs, không lộ nháp.

- [ ] **RED:** runner `-Case after-ui`/browser: upload bị gắn nhãn “Đã trả”, đã trả/chưa PAID thiếu payment CTA, nút complete sai policy hoặc Owner không phân biệt được “bất đồng” với “chưa nhận”.
- [ ] **Implement:** dùng lại form/detail/fragment, so sánh fields/ảnh/thời điểm/actor BEFORE/AFTER; receipt form 3 lựa chọn, lý do bắt buộc theo decision. Tách badge Booking/payment/progress/dispute; đã trả/chưa PAID có “Đã trả xe — chờ thanh toán”, tổng tiền còn phải trả, timeline và lý do chưa complete được. Legacy có nhãn và guards cũ. List batch read summary đúng page, không tải toàn bộ ảnh/event mỗi row, không thêm trang công nợ riêng.
- [ ] **GREEN:** compile + runner `-Case after-ui` và browser mobile/desktop: submit AFTER vẫn “Chờ chủ xe nhận lại”; NOT_RECEIVED không đổi; RECEIVED_DISPUTED vừa “Đã trả” vừa “Bất đồng”; ACCEPTED chưa PAID hiện payment/cash CTA. PAID không tự complete. Quyền CTA cả 3 role đúng, snapshot địa chỉ không đổi sau sửa Car, upload/receipt không JS hoạt động; text được escape và ảnh không lộ.

**Checkpoint đợt 3:** complete/cash/payment guards đủ trên fixture; bất đồng giữ chờ Admin. Luồng không bất đồng chạy đủ trước đợt 4.

## Đợt 4 — Admin, verification và kích hoạt

### Task P6.12 — Admin xử lý bất đồng và ghi chú bổ sung

**Files:**

- Create: `src/main/java/com/carrent/inspection/AdminInspectionController.java`, `src/main/resources/templates/admin/inspections.html`.
- Modify: `InspectionService.java`, `InspectionController.java`, `BookingInspectionRepository.java`, `InspectionView.java`, `NotificationService.java`, `templates/inspection/detail.html`.
- Modify: `templates/fragments/header.html` và điều hướng Admin đang dùng trong `templates/admin/` (đối chiếu tất cả caller khi implement, thêm link nhất quán), harness Phase6.

**Interfaces:** `InspectionService.findForAdmin(InspectionStatus status, Pageable pageable, CustomUserPrincipal principal) → Page<BookingInspection>`; GET `/admin/inspections` mặc định DISPUTED, size 1–50, sort submittedAt/id DESC. Chỉ filter SUBMITTED/ACCEPTED/DISPUTED/RESOLVED; DRAFT hoặc status sai trả 400, query luôn loại DRAFT. resolveDispute/addNote routes ở SDD; note trim 1–2000. Link mỗi row tới report detail; INSPECTION_RESOLVED → Customer/Owner.

- [ ] **RED:** runner `-Case disputes`: sai Admin/role/user status hoặc report không DISPUTED không resolve; resolve BEFORE không tự pickup, resolve AFTER không xóa timestamp trả; ghi chú không ghi đè bản gốc và HTML không được render thô.
- [ ] **Implement:** Admin list/query/page/filter và link **Bất đồng bàn giao**; resolve khóa Car → Booking → Inspection, append RESOLVED với Admin actor/note, status RESOLVED và notification atomic. Addendum cho Customer/Owner liên quan/Admin sau gửi và booking chưa terminal, chỉ append text. Không Admin complete/payment/damages, không thay ảnh/note gốc. BEFORE resolve vẫn cần Customer nhận hoặc mở lại bất đồng; AFTER resolve chỉ đóng dispute để Owner complete khi đủ PAID.
- [ ] **GREEN:** compile + runner `-Case disputes` và browser: từng nhánh BEFORE/AFTER, mở lại BEFORE giữ mọi event. BEFORE đã PAID/chưa nhận không complete; AFTER đã trả/chưa PAID resolve vẫn CONFIRMED và chưa complete được. Admin xem nháp người khác 404 và không thấy nháp ở list/Booking panel; sai role addNote 404; resolve lặp chỉ một event/notification cho mỗi lần mở dispute. Filter/pagination đúng, link từ header/sidebar mobile tới list/detail dùng được.

### Task P6.13 — Đồng thời, riêng tư, rollback và legacy

**Files:** Create `.scratch/phase6/Phase6RaceCheck.java`, `.scratch/phase6/Phase6HttpCheck.ps1`; Modify runner/harness Phase6. Sửa file production ở task gốc nếu case tìm lỗi, không đổi spec để cho pass.

**Interfaces:** produces runner cases `concurrency`, `security`, `regression`; log RED/GREEN + fixture IDs/timestamp/commit + bằng chứng SQL current read. HTTP dùng session cookie riêng từng role/CSRF thật; RaceCheck dùng hai connection MySQL REPEATABLE READ, giữ transaction snapshots cũ giống Phase 3.

- [ ] **RED:** mở snapshot/managed Booking/report/image cũ, chạy transition ở connection đối phương rồi dùng entity cũ; assertions phải phát hiện stale writes/duplicate receipt/file leak. Tái hiện rủi ro đã phát hiện trước sửa, ghi reproduction cho bug cụ thể.
- [ ] **Implement/repair:** batch cases độc lập, có timeout/cleanup/baseline. Bao phủ cancel-vs-pickup hai thứ tự; hai pickup khác booking cùng Car; submit-vs-edit/upload/delete; upload đồng thời vượt 10 ảnh; nhận/trả lặp; return/resolve-vs-complete; cash-vs-pay; cash/pay-vs-cancel trước pickup; stale PAID/report; timeout 1205/deadlock 1213 và lỗi notification/file write. Cùng Car phải serialize, không hai Customer cùng giữ xe; vẫn đặt được booking ngày tương lai, bàn giao phải chờ xe thực tế.
- [ ] **GREEN:** compile + runner `-Case concurrency`, `-Case security`, `-Case regression`: trạng thái cuối/event counts/notification counts đúng, không CANCELLED + received, không COMPLETED chưa PAID/chưa trả, không hai custody/hai PAID, không ảnh public. Kiểm XSS/CSRF403, giả phase/image/author/timestamps, user inactive/session cũ, decode/path/file bounds. Legacy cancel/refund/complete vẫn đúng; ảnh Car public và thống kê PAID Phase 4/5 không sai. Rollback giữ ảnh gốc/dọn file mới; baseline seed/data/files không đổi, fixture cleanup không chạm dữ liệu thật.

### Task P6.14 — E2E, cutover chính sách và chốt tài liệu

**Files:** Modify `src/main/java/com/carrent/booking/BookingService.java` (kích hoạt policy cho createBooking), `docs/SDD.md`, `docs/decisions.md`, `docs/plans/phase-6-inspection.md`, `README.md`; runner/harness Phase6. Review migration/bootstrap/privateignore.

**Interfaces:** produces server createBooking `inspectionRequired=true` sau activation, row cũ false giữ nguyên, checklist vận hành và bằng chứng Phase 6 hoàn tất chỉ sau khi pass.

- [ ] **RED/gate:** runner `-Case cutover` thấy createBooking hiện còn false; kiểm các gate P6.1–P6.13, mọi task phải GREEN trước đổi. Backup DB/files, rà soát đơn legacy đang thuê và ghi nhận Owner biết giới hạn custody; ngừng ghi khi switch, không backfill false → true.
- [ ] **Implement:** đặt true tại server creation builder, không nhận form flag; bootstrap mới vẫn seed legacy false. Boot với schema đã migrate/ddl-auto=none. Bật policy mới sau khi toàn module đạt gates, áp dụng từ lần tạo tiếp theo; không thêm config toggle động. Smoke tạo booking fixture qua service → policy true, row trước activation false. Nếu migration fail dừng rollout và phục hồi backup có kiểm soát; không chạy code thiếu bảng.
- [ ] **GREEN:** compile + runner `-Case cutover`, `-Case e2e`, regression gates và browser desktop/mobile:
  1. Owner/Customer fixtures, Car được duyệt; tạo booking mới → Owner confirm → BEFORE 4 ảnh → Customer nhận chưa PAID; cancel bị cấm.
  2. Customer AFTER → Owner “Chưa nhận” → vẫn chờ return → Owner đã nhận/đồng ý → chưa PAID, CONFIRMED → Customer pay → Owner complete; ngày/tổng tiền nguyên.
  3. Booking khác: BEFORE dispute → Admin resolve → Customer thực nhận; AFTER trả có bất đồng → timestamp có → Admin resolve → Owner cash → complete.
  4. Hai phương thức payment cùng thử chỉ một PAID; PAID trước nhận rồi cancel thì refund, không refund sau pickup. Trả sớm/đúng ngày/trễ giữ total; xe trả trễ chặn pickup booking kế, xe đã trả/chưa PAID không còn bị giữ thực tế.
  5. Legacy đọc/cancel/refund/complete như cũ, không ép 4 ảnh; người không có quyền không nhận private image bytes; baseline dữ liệu/seed đối chiếu không mất.
- [ ] **Chốt:** ghi commands/exit codes/fixture IDs/timestamp/commit/vị trí raw logs vào “Kết quả verify”; đánh dấu checkbox theo bằng chứng. SDD/README mô tả hành vi đã có, DB vật lý 10 bảng sau migration pass. Chỉ ghi Phase 6 hoàn tất khi mọi gate đạt; không tự stage/commit/push.

## Ma trận yêu cầu → task → bằng chứng

| Yêu cầu đã duyệt / rủi ro | Task | Bằng chứng dự kiến |
|---|---|---|
| DB-first, dữ liệu/đơn cũ | P6.1, P6.6, P6.13, P6.14 | Schema clone/current, baseline, legacy/cutover |
| Ảnh riêng tư, decode, upload ≤request limit | P6.2–P6.3, P6.13 | Privacy/draft/security/file rollback |
| BEFORE Owner → Customer nhận/bất đồng, chưa PAID | P6.4–P6.7 | before-submit/pickup/before-ui |
| Cấm hủy/refund sau nhận, race pickup/cancel | P6.6, P6.13 | cancellation/concurrency |
| AFTER Customer → Owner vật lý khác tình trạng | P6.8–P6.9, P6.11 | after-submit/return-receipt/after-ui |
| Đã trả/chưa thanh toán vẫn trả tiền được, một PAID, Owner ghi nhận cash | P6.10–P6.11, P6.13 | payment-complete, cash-vs-pay, UI |
| Complete thủ công, PAID/biên bản/return, trả sớm | P6.10, P6.12–P6.14 | Thiếu từng điều kiện, dispute/E2E |
| Evidence bất biến, events append, Admin không thay receipt/money | P6.3–P6.5, P6.9, P6.12–P6.13 | Bản gốc/hash/time không đổi, disputes/security |
| Xe đang được giữ khi trả trễ khác overlap đặt trước | P6.5, P6.13–P6.14 | Hai pickup cùng Car/đã trả chưa PAID/đặt ngày tương lai |
| CTA/điều hướng, mobile/không JS, notification cho 3 role | P6.7, P6.11–P6.12, P6.14 | HTTP/CSRF + browser + SQL notifications |

## Kết quả verify

**2026-10-12 — Chỉ lập kế hoạch:** chủ dự án đã duyệt workflow và lịch Phase 6. Chưa tạo/chạy migration, chưa thay đổi schema/code, chưa verify app theo nghiệp vụ Phase 6. Các checkbox task đều để trống. Bổ sung kết quả thực thi vào đây khi từng đợt đạt checkpoint; giữ kết quả RED/lỗi/fix và timestamp/commit để truy vết.
