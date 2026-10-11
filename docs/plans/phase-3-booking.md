# CarRent — Phase 3: Notification & Booking — Implementation Plan

> **For agentic workers:** Dùng `superpowers:executing-plans`, chạy task-by-task theo 5 đợt đã duyệt; checkbox `- [ ]` tracking. Giữ checkout/nhánh và thay đổi đang có, không stage/commit/push (D-19). Quy ước compile/boot/HTTP/SQL ở `phase-0-init.md`; D-19 hiện tại và constraints bên dưới thay phần quy ước Git lịch sử của Phase 0.

**Goal:** Notification trong DB + toàn bộ nghiệp vụ Booking (tạo → duyệt → hủy → hoàn tất) — FR-10, FR-11, FR-13, FR-14.

> **D-24 / SDD v2.1:** Bảng bookings/notifications và seed đã có từ `scriptDB.sql`; Hibernate `ddl-auto=none`, entity map đúng §7.2/7.6, không tạo lại bảng. Mọi verify lọc theo ID/định danh của record kịch bản hoặc so số lượng trước/sau; không giả định cả bảng chỉ có 1 row. Seed có booking PENDING/CONFIRMED giữ lịch: chọn xe/ngày không trùng hoặc tạo xe riêng cho kịch bản.

> **Phụ thuộc do DB có payment seed:** Sau P3.6, chạy **P4.1 (Payment entity/enums/repository) trước P3.7–P3.8**. P3.7 phải đọc trạng thái thanh toán thật; P3.8 triển khai refund ngay để hủy booking có PAID không tạo trạng thái CANCELLED+PAID (verify trên fixture riêng, seed gốc chỉ đọc). P4.2–P4.6 vẫn triển khai sau Phase 3; P4.1 không chạy lại.

**Architecture:** `NotificationService` là điểm hội tụ: các Service nghiệp vụ gọi nó trong cùng transaction. `BookingService` nắm rules ngày/overlap/giá/snapshot + transitions.

**Tech Stack:** Spring Data JPA, `@Transactional`, Thymeleaf.

**Spec:** `docs/SDD.md` §5 FR-10→FR-13, §6 BR-04→BR-08, §7.2 (bookings/notifications), §10; `docs/decisions.md` D-04, D-05, D-09, D-12, D-13, D-14, D-15.

---

## Global Constraints (tóm tắt)

- Quy ước phase-0. Notification type dùng đúng 10 giá trị SDD §7.2 (CAR_PENDING_APPROVAL, CAR_APPROVED, CAR_REJECTED, BOOKING_CREATED, BOOKING_CONFIRMED, BOOKING_REJECTED, BOOKING_CANCELLED, BOOKING_COMPLETED, PAYMENT_PAID, PAYMENT_FAILED).
- Booking tạo → PENDING, chặn lịch (D-04). Khóa Car + current locking conflict read + save cùng transaction (D-13), không chấp nhận đặt trùng do race.
- Refund payment PAID ngay tại P3.8 khi hủy booking; P4.1 mapping/repository đã chạy sau P3.6. P4.6 verify lại, không trì hoãn refund.
- Mọi POST CSRF. UI tiếng Việt.
- **UI port từ prototype Stitch** (`docs/design/stitch/`, quy tắc ở `README.md`): layout A cho customer, layout B cho owner/admin. Trang không có prototype (notification list) dựng theo cùng design system.
- Không đổi schema, thêm dependency/seeder hoặc Java unit test. Verify bằng Spring/MySQL thật + HTTP/browser/assert script local trong `.scratch/phase3/`; không stage artifact `.scratch/`/`.superpowers/`.
- Lưu baseline seed rows và sáu ảnh seed. Ưu tiên transaction rollback; HTTP/concurrency/afterCommit cần commit thì tạo fixture riêng, ghi đúng ID và cleanup đúng fixture theo thứ tự FK (payments → bookings → car_images/cars, notifications liên quan, users nếu có). Không sửa/hủy/xóa seed gốc; so baseline sau cleanup.
- Guard role/status ACTIVE trong DB, ownership tại Service; CSRF, escape Thymeleaf và pagination có giới hạn như Phase 2. Availability UI chỉ tư vấn; submit kiểm tra lại.

## D-13 — Hợp đồng khóa DB đã duyệt

- Tạo booking: `CarRepository.findByIdForUpdate(Long)` đã có; khóa Car trước đọc visibility/giá/địa chỉ/conflicts. Sau khóa, đọc lại Car hiện tại; reuse cách xử lý stale managed Car đang có trong `CarService.lockedCar`, không tạo abstraction public chỉ để tái sử dụng.
- `BookingRepository.findConflictsForUpdate(carId, start, end)` dùng `@Lock(PESSIMISTIC_WRITE)` trên entity query PENDING/CONFIRMED, với `startDate < end && endDate > start`. MySQL REPEATABLE READ có thể đã có snapshot từ query User/Car trước đó; `existsConflict`/count thường chỉ dùng cho UI. Car lock serialize cả trường hợp query conflict không có row.
- Mutator: đọc carId từ booking chỉ để định tuyến khóa; sau đó khóa **Car → Booking → Payment** khi cần, reload Booking/Payment hiện tại trước guard/transition. Không lấy khóa Booking trước Car. Nếu entity đã managed, không dùng lại giá trị cũ chỉ vì query có `@Lock`; flush thay đổi hợp lệ rồi refresh/detach-reload có chủ đích trước khi đổi status, không clear toàn persistence context làm mất cập nhật.
- Đợt 4 đã tái hiện Hibernate bỏ `FOR UPDATE` khi refresh entity đã ghi lock mode: dùng targeted detach rồi query FOR UPDATE lại cho Booking/Payment, giữ khóa đến commit/rollback. Kiểm chứng với snapshot REPEATABLE READ và entity managed cũ, không chỉ dựa vào annotation.
- Car update/hide/delete cùng giữ khóa Car; delete đọc mọi lịch sử booking bằng locking read. Payment Phase 4 tuân thủ cùng thứ tự và current-state guard. Booking/payment/notifications cùng transaction; file cleanup chỉ sau commit.
- Lock timeout/deadlock rollback toàn transaction, trả thông báo tiếng Việt có kiểm soát; không retry mù hoặc dùng Java `synchronized` thay khóa DB. Transaction ngắn, không chờ tương tác người dùng khi đang giữ khóa.

## 5 đợt triển khai đã duyệt — 2026-10-11

| Đợt | Thứ tự | Đầu ra mong muốn |
|---|---|---|
| 1 | P3.1 → P3.2 → P3.3 | Notification lưu DB, list/read/read-all/badge; tạo/duyệt/từ chối xe notify đúng người |
| 2 | P3.4 → P3.5 | Entity/repo và tạo booking backend an toàn đồng thời, đã verify độc lập với UI |
| 3 | P3.6 → P4.1 → P3.7 | Customer đặt xe → detail/list; availability, snapshot và trạng thái payment thật |
| 4 | P3.8 → P3.9 → P3.10 | Hủy/hoàn tiền atomic; Owner xem và confirm/reject, chống transition tranh chấp |
| 5 | P3.11 → P3.12 → P3.13 | Complete, xóa xe an toàn; E2E/phân quyền/concurrency và baseline seed đầy đủ |

P4.1 chỉ mapping/enums/repository, chạy một lần ở đợt 3. Không chạy P4.2+ trong Phase 3. **Cả 5 đợt/P3.1–P3.13 và P4.1 đã implement/verify ngày 2026-10-11. Phase 3 hoàn tất; tiếp theo Phase 4 từ P4.2. P4.2+ chưa triển khai.**

- [x] **Checkpoint đợt 1:** compile/boot; notification đúng recipient/type/link; read sai user 404, POST thiếu CSRF 403; badge desktop/mobile cả public và Owner/Admin; rollback mutation xe không để notification. Lưu evidence và diff review.
- [x] **Checkpoint đợt 2:** verify service qua Spring/MySQL thật trước UI: ngày, overlap hai chiều, tiền/snapshot, role/status, hai connection đặt trùng và snapshot cũ; đúng một booking + notification, rollback giữ baseline.
- [x] **Checkpoint đợt 3:** form submit → detail/list hoạt động, không redirect tới endpoint thiếu; 3 role quyền xem, payment seed đọc đúng; desktop/mobile, estimate không ảnh hưởng backend, availability không giữ chỗ.
- [x] **Checkpoint đợt 4:** cancel/refund/notify atomic; Owner scope và reason validation; confirm/reject/cancel đồng thời không ghi đè stale status hoặc notify thất bại; fixture cleanup/baseline.
- [x] **Checkpoint đợt 5:** complete/date guard, delete/history/afterCommit, create-vs-hide/delete, lock failure rollback và E2E P3.13; compile/boot, tài liệu/checklist và seed/images baseline.

## Review Focus

1. Hai khoảng thuê chồng mép (A: 1–5, B: 5–10) không trùng theo BR-05 → cả hai tạo được — Task 5, 6.
2. Owner B confirm booking trên xe của Owner A → 403/404 — Task 10.
3. Customer hủy booking COMPLETED → BusinessException, status không đổi — Task 8.
4. Hai request đặt trùng và transition đồng thời phải đọc dữ liệu hiện tại dưới khóa, kể cả transaction có snapshot/managed entity cũ — Task 4, 5, 8, 10, 13.
5. Owner nút Complete khi end_date chưa tới → blocked, thông báo rõ — Task 11.

---

### Task P3.1: Notification entity + repo

**Files:**
- Create: `src/main/java/com/carrent/notification/Notification.java`, `NotificationRepository.java`.

**Interfaces:**
- Produces: `Notification` per SDD §7.2: Long id; `@ManyToOne User` (LAZY, FK user_id); String title ≤150; String message ≤1000; String type ≤50; boolean read (cột `is_read`, field Java `read` — Lombok getter `isRead()); String relatedEntityType ≤30 nullable; Long relatedEntityId nullable; LocalDateTime createdAt (`@PrePersist`).
- Produces: `NotificationRepository`:
  - `Page<Notification> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable)`
  - `long countByUserIdAndReadFalse(Long userId)`
  - `Optional<Notification> findByIdAndUserId(Long id, Long userId)`
  - `List<Notification> findByUserIdAndReadFalse(Long userId)`

- [x] **Step 1: Viết entity + repo.** Column name cho field `read`: `@Column(name="is_read")` tránh từ khóa SQL.
- [x] **Step 2: Verify** — boot → `DESCRIBE notifications;` đủ 9 cột, FK user_id đúng.

### Task P3.2: NotificationService + controller + view + badge navbar

**Files:**
- Create: `src/main/java/com/carrent/notification/NotificationService.java`, `NotificationController.java`, `NotificationDto.java`, `src/main/java/com/carrent/web/NavbarControllerAdvice.java`.
- Create: `src/main/resources/templates/notification/list.html`.
- Modify: `src/main/resources/templates/fragments/header.html`, `src/main/resources/templates/owner/cars.html`, `src/main/resources/templates/owner/car-form.html`, `src/main/resources/templates/admin/cars.html`, `src/main/resources/templates/admin/car-detail.html` — thêm link chuông + badge unread desktop/mobile; giữ link Admin **Duyệt xe** `/admin/cars`.

**Interfaces:**
- Produces: `NotificationService`:
  - `notify(Long userId, String type, String title, String message, String relatedEntityType, Long relatedEntityId)` — build + save. (public, gọi từ Car/Booking/Payment service.)
  - `Page<NotificationDto> getNotifications(userId, pageable)` — DTO: id, title, message, type, read, relatedEntity..., createdAt (format trong template).
  - `markRead(id, userId)` — check `findByIdAndUserId` → sai → NotFoundException; save read=true.
  - `markAllRead(userId)` — findByUserIdAndReadFalse → set read → saveAll.
  - `long countUnread(userId)`.
- Produces: `NotificationController` `@GetMapping("/notifications")` (model notifications Page + pagination), `@PostMapping("/notifications/{id}/read")`, `@PostMapping("/notifications/read-all")` — cả 2 POST redirect `/notifications` + flash.
- Produces: `NavbarControllerAdvice` `@ControllerAdvice` — mọi view có attribute `unreadCount` (0 nếu anonymous — check `Authentication` null/principal instanceof).
- Produces: template list — **không có prototype → dựng theo design system layout A** (fragment `head`/`header`/`footer` từ P0.3): item list (icon theo type, title, message, thời gian `dd/MM/yyyy HH:mm`), badge "Chưa đọc", form POST read từng cái + read-all; không viết helper thời gian tương đối. Related CAR: ADMIN → `/admin/cars/{id}`, OWNER → `/owner/cars/{id}/edit`; BOOKING → `/bookings/{id}` chỉ bật khi P3.7 có endpoint, PAYMENT link lịch sử nối P4.4. Type/ID thiếu hoặc endpoint chưa có thì chỉ hiện nội dung, không link hỏng; không tin URL từ message.

- [x] **Step 1: Viết service + controller + advice + template + sửa header.**
- [x] **Step 2: Verify** — user fixture riêng có 2 notification unread → login → `GET /notifications` = 200, đúng 2 item và badge 2. POST read 1 → SQL is_read=1, badge 1; POST read-all → badge 0. User khác POST read ID fixture → 404, row không đổi; POST thiếu CSRF → 403. Nội dung chứa HTML được escape; kiểm tra pagination/badge/link trên public và Owner/Admin desktop/mobile. Cleanup đúng fixture, seed không đổi.

### Task P3.3: Cắm notification cho sự kiện xe

**Files:**
- Modify: `CarService.java` — `createCar` notify mọi ADMIN (FR-13: "Có xe mới chờ duyệt" → type CAR_PENDING_APPROVAL, related CAR id); `approveCar`/`rejectCar` notify owner (CAR_APPROVED / CAR_REJECTED, message kèm brand/model + reason nếu reject).
- (NotificationService.notify cần UserRepository list admin: thêm `List<User> findByRole(Role.ADMIN)` vào UserRepository.)

- [x] **Step 1: Viết cắm notify** — method riêng trong CarService `notifyAdminsNewCar(car)` / `notifyOwnerApproval(car)` để đỡ lặp.
- [x] **Step 2: Verify** — xe fixture mới → ADMINs có CAR_PENDING_APPROVAL; approve/reject trên hai xe fixture → owner có đúng CAR_APPROVED/CAR_REJECTED và reason. Lọc theo related ID/type/recipient, không count toàn bảng. Ép rollback mutation → không có notification mới hoặc status đổi; cleanup đúng fixture.

### Task P3.4: Booking entity + enum + repo (conflict query)

**Files:**
- Create: `src/main/java/com/carrent/booking/Booking.java`, `BookingStatus.java`, `BookingRepository.java`.

**Interfaces:**
- Produces: `Booking` per SDD §7.2 bookings: customer (ManyToOne User), car (ManyToOne Car), startDate/endDate LocalDate, pricePerDaySnapshot Long, totalAmount Long, pickupAddress String ≤300, status enum, customerNote/ownerNote ≤500 nullable, timestamps.
- Produces: `BookingStatus` = {PENDING, CONFIRMED, REJECTED, CANCELLED, COMPLETED}.
- Produces: `BookingRepository`:
  - `@Query("select count(b) > 0 from Booking b where b.car.id = :carId and b.status in (com.carrent.booking.BookingStatus.PENDING, com.carrent.booking.BookingStatus.CONFIRMED) and b.startDate < :end and b.endDate > :start") boolean existsConflict(@Param("carId") Long carId, @Param("start") LocalDate start, @Param("end") LocalDate end);`
    Chỉ dùng cho availability UI, không quyết định insert.
  - `@Lock(PESSIMISTIC_WRITE)` + entity query cùng predicate → `List<Booking> findConflictsForUpdate(Long carId, LocalDate start, LocalDate end)`; dùng `@Param` khớp query, chạy sau Car lock.
  - `@Lock(PESSIMISTIC_WRITE)` + `select b from Booking b where b.id = :id` → `Optional<Booking> findByIdForUpdate(Long id)`.
  - `@Lock(PESSIMISTIC_WRITE)` + `select b from Booking b where b.car.id = :carId order by b.id` → `List<Booking> findHistoryForUpdate(Long carId)`; mọi status, cho delete/current read.
  - `Page<Booking> findByCustomerId(Long customerId, Pageable pageable)`
  - `Page<Booking> findByCarOwnerId(Long ownerId, Pageable pageable)`
  - `Page<Booking> findByStatus(BookingStatus status, Pageable pageable)`; null status dùng `findAll(pageable)` kế thừa, Admin list dùng tại P4.5.
  - `boolean existsByCarId(Long carId)` (cho P3.12 xóa xe)

- [x] **Step 1: Viết entity + enum + repo.**
- [x] **Step 2: Verify** — boot đọc đủ 5 status seed, DESCRIBE/SHOW CREATE TABLE khớp schema; kiểm tra SQL locking query là current `FOR UPDATE` trên MySQL thật, FK customer_id/car_id, không DDL sinh thêm.

### Task P3.5: BookingService.create

**Files:**
- Create: `src/main/java/com/carrent/booking/BookingService.java`, `BookingCreateForm.java`.
- Modify: `src/main/java/com/carrent/common/exception/CommonExceptionHandler.java` — mapping lock failure sau transaction rollback sang lỗi 409 tiếng Việt, reuse `error/error`.
- Modify: `src/main/resources/templates/error/error.html` — paragraph dùng `th:text="${errorMessage ?: 'Đã xảy ra lỗi không mong muốn. Vui lòng thử lại sau.'}"` để hiển thị lỗi khóa và giữ fallback; escape thông báo, không render stack trace/SQL.

**Interfaces:**
- Produces: `BookingCreateForm`: carId `@NotNull`; startDate `@NotNull @FutureOrPresent` (LocalDate); endDate `@NotNull`; customerNote `@Size(max=500)`.
- Produces: `BookingService.createBooking(BookingCreateForm form, CustomUserPrincipal principal) → Booking` — `@Transactional`:
  1. Validate form tại Service cả khi không qua MVC; principal/user DB phải CUSTOMER + ACTIVE. Owner/admin không đặt xe; ID khách lấy từ principal, không từ form.
  2. Validate ngày không null, start ≥ hôm nay, start < end, end ≤31/12/9999 (phạm vi DATE MySQL, chặn trước query); days = `ChronoUnit.DAYS.between(start, end)` ≥1; note ≤500.
  3. Khóa Car bằng `findByIdForUpdate`, reread current state theo D-13 (không tồn tại → NotFoundException). Car phải APPROVED + ACTIVE, ngược lại BusinessException "Xe hiện không nhận đơn".
  4. `findConflictsForUpdate(carId,start,end)` không rỗng → BusinessException "Xe đã có đơn trùng thời gian thuê". Không thay bằng `existsConflict` snapshot query.
  5. `Math.multiplyExact(car.getPricePerDay(), days)`; ArithmeticException → business error rõ ràng. Snapshot giá/địa chỉ lấy từ Car đã khóa, ghép địa chỉ bỏ phần trống; pickupAddress >300 → business error, không truncate (D-09/D-15).
  6. Build Booking PENDING; save và lấy ID; notify Owner BOOKING_CREATED (related BOOKING id, brand/model + ngày), cùng transaction. Giữ Car lock tới commit/rollback.

- [x] **Step 1: Viết form + service.**
  Thêm handler cho lỗi khóa được Spring translate, không catch rồi tiếp tục trong transaction:

  ```java
  @ExceptionHandler({org.springframework.dao.PessimisticLockingFailureException.class,
          org.springframework.dao.QueryTimeoutException.class})
  @ResponseStatus(HttpStatus.CONFLICT)
  public String concurrentUpdate(Model model) {
      model.addAttribute("errorMessage", "Dữ liệu đang được xử lý bởi yêu cầu khác. Vui lòng thử lại.");
      return "error/error";
  }
  ```
- [x] **Step 2: Verify backend độc lập** — compile/boot; script Spring-context/MySQL thật gọi Service: invalid/null/past/equal/reversed dates, note >500, non-CUSTOMER/INACTIVE, xe không public bị chặn; 4 ngày đúng giá×4, form không có trường price/customer; snapshot giữ nguyên khi xe sửa; địa chỉ >300 và overflow trả business error, không row/notify. Boundary DATE 9999/10000 có RED→GREEN.
- [x] **Step 3: Verify concurrency đợt 2** — hai connection/transaction độc lập trên xe fixture: A giữ Car lock, tạo PENDING rồi commit; B đã thực hiện ordinary read trước A commit, cố tạo trùng phải chờ và sau đó bị chặn, chỉ một booking/BOOKING_CREATED. Kiểm tra managed Car cũ bị ẩn/đổi giá trước lấy khóa; không đặt xe đã ẩn, snapshot dùng giá mới. Hai khoảng chạm mép ở cả hai chiều được tạo trên fixture sạch; PENDING/CONFIRMED chặn, 3 status còn lại không chặn. Rollback/cleanup giữ baseline.

### Task P3.6: Form tạo booking + ước tính giá JS + availability check trên detail

**Files:**
- Create: `src/main/java/com/carrent/booking/BookingController.java`, `src/main/resources/templates/booking/create.html`, `src/main/resources/static/js/booking-price.js`.
- Modify: `templates/cars/detail.html` — bật form kiểm tra khả dụng (đã ẩn từ P2.13): GET `/cars/{id}?start=...&end=...` → model `availability` (service method `checkAvailability(carId, start, end) → boolean` dùng existsConflict) → banner xanh/đỏ.
- Modify: `src/main/java/com/carrent/car/CarController.java`, `src/main/java/com/carrent/config/SecurityConfig.java` — nối query ngày; matcher CUSTOMER cho GET `/bookings/new`, GET `/bookings`, POST `/bookings`, POST `/bookings/*/cancel` trước matcher authenticated `/bookings/**`; generic detail cho cả 3 role với Service guard.

**Interfaces:**
- Produces: `GET /bookings/new?carId=` → reuse `CarService.getPublicCar(carId, null)` của P2.13 để chỉ APPROVED+ACTIVE, không cấp preview quyền Owner/Admin cho form đặt xe → model car + form rỗng; không thêm wrapper nếu không cần.
- Produces: `POST /bookings` — `@Valid BookingCreateForm` → service create → redirect `/bookings/{id}` + flash "Đặt xe thành công!"; BusinessException → flash error + redirect `/bookings/new?carId=`; validation lỗi → re-render form.
- Produces: `booking-price.js` — 2 input date + data-price attribute → live tính `days × price` hiển thị (chỉ UI, server là nguồn chân lý).
- Produces: template `booking/create.html` — **port từ `docs/design/stitch/booking-xac-nhan-dat-thue.html` (layout A)**: khối tóm tắt xe + khoảng ngày + giá ước tính + nút "Xác nhận đặt xe" giữ nguyên markup Tailwind, bind `th:object` BookingCreateForm.
- Produces: `BookingService.checkAvailability(carId, start, end) → boolean`.
  Validate đủ ngày và BR-04; dữ liệu sai trả thông báo rõ, không 500. Kiểm tra public car và `existsConflict`; UI không giữ chỗ. CTA đặt xe trỏ form thật. Đợt 3 phải chạy P4.1 rồi P3.7 trước checkpoint để redirect detail hoạt động.

- [x] **Step 1: Viết controller + template + JS + sửa detail.**
- [x] **Step 2: Verify — flow curl đầy đủ**

Login customer (P1.7 flow) → GET `/bookings/new?carId=<id xe APPROVED>` = 200. POST booking (CSRF + jar):
```
startDate=2026-11-01&endDate=2026-11-05&carId=<id>&customerNote=note&_csrf=...
```
→ 302 → SQL bookings: 1 row PENDING, total_amount = price×4, snapshot = price, pickup_address đầy đủ. Notifications: owner có row BOOKING_CREATED.

Overlap test trên xe fixture sạch: booking `01→05`, request `04→08` bị chặn, request `05→09` thành công. Trên fixture khác đặt `05→09` trước rồi `01→05` cũng thành công vì `existing.start=05 < requested.end=05` là false. Chọn ngày tương lai tương đối theo ngày chạy, không dùng ngày ví dụ đã qua. Lọc SQL bằng fixture ID, cleanup sau verify.

### Task P3.7: Booking detail + list (customer) + quyền xem 3 role

**Files:**
- Modify: `BookingController.java`.
- Modify: `src/main/resources/templates/fragments/header.html` — Customer link **Đơn thuê của tôi** `/bookings`, desktop/mobile; giữ notification và Admin **Duyệt xe**.
- Create: `src/main/resources/templates/booking/list.html`, `booking/detail.html`.

**Interfaces:**
- Produces: `GET /bookings` (customer) → Page<Bookings of principal> → view list — **port từ `docs/design/stitch/booking-don-thue-cua-toi.html` (layout A)**: card/table (xe, khoảng ngày, tổng tiền, badge status, link detail) + pagination + empty state.
- Produces: `GET /bookings/{id}` — service `getBookingForView(id, principal)`:
  - User DB ACTIVE đúng role: ADMIN → OK; CUSTOMER có ID = booking.customer → OK; OWNER có ID = booking.car.owner → OK; khác → NotFoundException (không lộ).
  - Model: booking + car + `hasPaid` từ `paymentRepository.existsByBookingIdAndStatus(id, PAID)`; hiển thị "Đã thanh toán"/"Chưa thanh toán" theo DB, không giả định payment null vì chưa đến Phase 4. Lịch sử payment đầy đủ sẽ cắm ở P4.4.
- Template detail — **port từ `docs/design/stitch/booking-chi-tiet-don-thue.html` (layout A)**: thông tin xe, ngày, giá/địa chỉ snapshot, status, notes và trạng thái payment thật. Nút cancel bật từ P3.8; Owner action từ P3.10/P3.11. Nút/link payment chỉ bật P4.3 khi endpoint có thật, không đưa người dùng vào 404 trong checkpoint Phase 3. Admin chỉ xem; màn riêng `/admin/bookings` thuộc P4.5.

- [x] **Step 1: Viết 2 template + method service.**
- [x] **Step 2: Verify** — customer GET `/bookings` = 200 (booking từ P3.6). GET `/bookings/{id}` customer owner-of-car = 200; customer khác (tạo customer 2) = 404; anonymous = 302 login.

### Task P3.8: Customer cancel

**Files:**
- Modify: `BookingService.java`, `BookingController.java` (thêm `POST /bookings/{id}/cancel`), `booking/detail.html` (nút hủy + confirm JS).

**Interfaces:**
- Consumes: `PaymentRepository` từ P4.1 đã chạy trước task này.
- Produces: `BookingService.cancelBooking(id, principal)` — `@Transactional`: khóa Car → Booking, reload current booking; DB role CUSTOMER/ACTIVE + đúng chủ, status ∈ {PENDING, CONFIRMED}; khóa/read payment PAID hiện tại sau Booking rồi set CANCELLED và `refundByBookingId(id)`. Bulk refund phải flush pending booking update, reload/detach managed Payment liên quan sau update, không clear toàn context làm mất cập nhật. Nếu refund >0 notify customer BOOKING_CANCELLED với message hoàn tiền; luôn notify owner BOOKING_CANCELLED. Booking/payment/notify cùng transaction; amount/paid_at giữ nguyên, không tạo payment mới.

- [x] **Step 1: Viết method + route + nút.**
- [x] **Step 2: Verify** — fixture PENDING → CANCELLED + owner notify; lần 2 bị chặn/no notify thêm. Fixture CONFIRMED+PAID (không hủy seed #2) → CANCELLED+REFUNDED, amount/paid_at không đổi, customer refund notify; service rollback khi notify lỗi phải giữ CONFIRMED+PAID. Managed Payment đã load phải đọc được REFUNDED sau refund. Sai customer/role, COMPLETED/REJECTED bị chặn. Hai cancel đồng thời chỉ một thành công; cancel-vs-confirm/reject theo D-13 không ghi đè state cũ và chỉ notify cho transition thành công. Cleanup đúng fixture, seed baseline không đổi.

### Task P3.9: Owner bookings list + detail

**Files:**
- Create: `src/main/java/com/carrent/booking/OwnerBookingController.java`, `templates/owner/bookings.html`, `templates/owner/booking-detail.html`.
- Modify: `src/main/resources/templates/owner/cars.html`, `src/main/resources/templates/owner/car-form.html` — link **Đơn thuê** `/owner/bookings`; hai trang Owner mới cũng có link và notification badge. `BookingSummaryDto` chỉ tạo ở P5.7 nếu dashboard cần; P3.9 không tạo DTO chưa có consumer.

**Interfaces:**
- Produces: `GET /owner/bookings` → `findByCarOwnerId(principal.getId(), pageable)` → view: table (xe, customer, ngày, total, badge, nút detail).
- Produces: `GET /owner/bookings/{id}` — reuse `getBookingForView` (owner-of-car pass) → view detail owner mode: thông tin customer (fullName, phone, email), nút Confirm/Reject/Complete theo trạng thái.

- [x] **Step 1: Viết controller + 2 template.**
- [x] **Step 2: Verify** — Owner A list có fixture của A; Owner B list không có ID fixture của A (không giả định toàn list B rỗng vì có seed). Detail A với B → 404; Customer/Admin không vào `/owner/**`; pagination và link/sidebar desktop/mobile hoạt động.

### Task P3.10: Owner confirm/reject

**Files:**
- Modify: `OwnerBookingController.java`, `BookingService.java` (confirmBooking/rejectBooking), `templates/owner/booking-detail.html` (nút + modal lý do).

**Interfaces:**
- Produces: `BookingService.confirmBooking(id, principal)` — khóa Car → Booking, reload current state; DB OWNER/ACTIVE và đúng owner; PENDING → CONFIRMED; notify customer BOOKING_CONFIRMED cùng transaction.
- Produces: `BookingService.rejectBooking(id, principal, reason)` — cùng protocol; reason trim bắt buộc, không blank, ≤500 ở MVC và Service; PENDING → REJECTED + ownerNote = reason; notify customer BOOKING_REJECTED cùng transaction.

- [x] **Step 1: Viết 2 method + route POST + UI.**
- [x] **Step 2: Verify** — fixture confirm/reject đúng state/note/recipient; blank/whitespace/>500 reason bị chặn; Owner B, role/status sai và transition lặp bị chặn/no notify. Hai connection confirm-vs-reject cùng PENDING chỉ một transition/notify thành công; transaction có managed Booking cũ trước cancel không confirm/reject ghi đè CANCELLED. Với cancel-vs-confirm, nếu confirm trước thì cancel từ CONFIRMED vẫn hợp lệ; kết quả CANCELLED, notify mỗi transition thành công đúng một lần. Verify bằng DB current state, cleanup baseline.

### Task P3.11: Owner complete

**Files:**
- Modify: `OwnerBookingController.java`, `BookingService.java`, `templates/owner/booking-detail.html`.

**Interfaces:**
- Produces: `BookingService.completeBooking(id, principal)` — khóa Car → Booking/reload theo D-13; DB OWNER/ACTIVE + ownership; status CONFIRMED; `endDate < LocalDate.now()` (hôm nay/tương lai bị chặn); → COMPLETED + customer BOOKING_COMPLETED cùng transaction. Không thêm guard PAID ngoài spec.

- [x] **Step 1: Viết method + route + nút.**

- [x] **Step 2: Verify** — Tạo booking CONFIRMED chưa có payment trên xe fixture riêng. Chuyển cả ngày và tổng tiền trong **một UPDATE**: `UPDATE bookings SET start_date=DATE_SUB(CURRENT_DATE(), INTERVAL 3 DAY), end_date=DATE_SUB(CURRENT_DATE(), INTERVAL 1 DAY), total_amount=price_per_day_snapshot*2 WHERE id=<testBookingId> AND status='CONFIRMED';` (thay đúng ID fixture; không sửa seed/booking đã có payment). POST complete → COMPLETED dù chưa PAID; fixture end_date hôm nay/tương lai, sai owner/role/status, transition lặp bị chặn. Complete-vs-cancel dưới khóa chỉ một terminal state/notify, không stale overwrite; rollback notify lỗi giữ CONFIRMED. Cleanup fixture/baseline.

### Task P3.12: Owner xóa cứng Car không có lịch sử booking

**Files:**
- Modify: `CarService.java` (thêm `deleteCar(carId, principal)` — dời từ P2.5), `OwnerCarController.java` (POST `/owner/cars/{id}/delete`), `templates/owner/cars.html` (nút xóa chỉ enable khi 0 booking).

**Interfaces:**
- Produces: `deleteCar` — `@Transactional`, DB OWNER/ACTIVE; reuse locked owned Car pattern; `findHistoryForUpdate(carId)` có bất kỳ status → BusinessException "Xe đã có đơn thuê, không thể xóa — hãy ngừng đăng tin". UI có thể dùng `existsByCarId` để disable, nhưng Service dùng current read dưới Car lock. Đọc image paths hiện tại bằng `CarImageRepository.findByCarIdForUpdate` sau khóa Car (không đọc snapshot thường), xóa DB CarImages/Car; reuse transaction synchronization của Phase 2 để dọn file **sau commit**, log lỗi cleanup; rollback giữ file. FK cascade chỉ xóa row, không file.
- [x] **Step 1:** Viết Service/route/nút, giữ ownership/CSRF và xác nhận xóa tiếng Việt.
- [x] **Step 2: Verify:** xe fixture 0 booking có ảnh → DB rows + file fixture mất sau commit; rollback trước commit → DB/file còn. Bất kỳ booking PENDING/CONFIRMED/REJECTED/CANCELLED/COMPLETED đều chặn delete. Sai owner/role/status và CSRF bị chặn, không đổi seed/sáu ảnh seed.
- [x] **Step 3: Verify concurrency:** hai connection create-vs-delete và create-vs-hide. Nếu create commit trước, delete bị chặn bởi history; nếu delete trước, create báo không tồn tại. Nếu hide trước, create bị chặn; nếu create trước, booking giữ hợp lệ và hide sau. Không orphan booking/ảnh hoặc notification từ transaction thất bại; snapshot cũ không quyết định delete.

### Task P3.13: Verify tổng phase 3 (E2E kịch bản)

- [x] **E2E browser/HTTP trên fixture riêng:** Owner tạo xe → Admin từ header **Duyệt xe** vào approve → Customer tìm theo brand duy nhất → đặt/list/detail → Owner confirm. Dùng các booking fixture riêng để verify reject, cancel unpaid, cancel paid/refund và complete (chỉnh ngày fixture đúng tổng tiền như P3.11). Mỗi ID có state cuối dự kiến cụ thể: CONFIRMED, REJECTED, CANCELLED, CANCELLED+REFUNDED, COMPLETED; notify đúng recipient/type/related ID, không chỉ nhìn tổng count toàn bảng.
- [x] **An toàn/concurrency:** đủ BR-04→BR-08 và matrix Booking 3 role; missing CSRF, giả customer/owner/price, XSS, pagination; hai connection tạo trùng, touching cả hai chiều, confirm/reject/cancel/complete tranh chấp, stale managed entity/read snapshot, create-vs-hide/delete. Complete-vs-cancel trên fixture đủ ngày: chỉ một terminal transition/notify; timeout/deadlock rollback cả booking/payment/notify và trả lỗi có kiểm soát.
- [x] **UI/checkpoint:** desktop/mobile public/Owner/Admin, navigation notification/bookings/Duyệt xe, flash/error/empty-state, payment thật từ seed và không link vào payment chưa implement. Compile/boot không lỗi, không DDL schema changes.
- [x] **Bảo toàn và tài liệu:** cleanup đúng fixture ID, so seed rows và sáu ảnh seed với baseline; ghi commands/assert outcomes + evidence local trong `.scratch/phase3/`, cập nhật checklist plan/SDD/README theo chức năng đã verify. Không stage/commit/push. P4.1 đánh dấu hoàn thành tại plan Phase 4 đúng một lần; P4.2+ giữ chưa làm.

## Kết quả verify — đợt 1 (P3.1–P3.3, 2026-10-11)

- RED trước implement: metamodel chưa có Notification; GET `/notifications` trả 404; tạo xe chưa tạo CAR_PENDING_APPROVAL. GREEN: `VerifyNotifications` **52 assertions** trên Spring context/MySQL thật đạt (mapping/BIT, empty state, DTO/escape, phân trang, 2→1→0 unread, sai user 404, CSRF 403, stale INACTIVE 403, header và events/all ADMINs/rollback).
- HTTP app thật 8080: **58 checks** đạt, gồm tạo xe qua form → Admin approve/reject → notify Owner; message/reason/link/role, read/read-all commit, không thay unread user khác, anonymous và pagination bounds. Browser: **27 kịch bản** đạt, 3 role, notification/public/Owner/Admin ở 1440/390/320px, bell hiện và không overflow; click CAR link đúng trang, read flow 2→1→0, không pageerror. Đã xem screenshot desktop/mobile.
- Notification và mutation xe cùng transaction REQUIRED; không REQUIRES_NEW, không realtime, không gọi ngoài DB. Một quyết định thất bại/lặp không tạo event mới. Nội dung được escape; POST dùng CSRF; status ACTIVE kiểm tra lại ở Service, notification của user khác không lộ.
- Fixture rollback hoặc commit có ID theo dõi rồi cleanup chính xác; đối chiếu toàn bộ rows/timestamps/cột/constraints của 7 bảng và hash sáu ảnh seed khớp baseline. Không đổi schema/seed/dependency; không Java unit test.
- Review độc lập **Approved**, không finding cần sửa. Evidence local: `.scratch/phase3/{VerifyNotifications.java,run-check.ps1,batch1-check.log,verify-http.py,browser-check.js,verify-db.py}`, screenshot `*-notifications-{1440,390}.png`; review `.superpowers/sdd/phase-3-booking/batch-1-review.md`.
- Regression phần bị tác động: `VerifyCarCreate` đạt validation/authorization, tạo 0/2 ảnh, DB/file rollback và lỗi upload thứ hai; `VerifyAdminCars` **212 checks** đạt list/detail/approve/reject/CSRF/role. Logs `.scratch/phase3/car-create-regression.log` và `admin-regression.log`; baseline vẫn nguyên sau regression.
- App đang chạy 8080; dùng `/notifications` hoặc icon chuông để test. Badge cập nhật khi tải trang; BOOKING/PAYMENT related links chưa bật đến P3.7/P4.4. Dashboard/profile/booking vẫn theo phase sau. Giữ checkout/nhánh `feat/phase2-car`, không stage/commit/push. **Tiếp theo đợt 2: P3.4 → P3.5.**

## Kết quả verify — đợt 2 (P3.4–P3.5, 2026-10-11)

- RED trước implement: Booking chưa có trong metamodel và chưa có bean `bookingService`. GREEN: entity/repo đọc đủ 5 status seed và 13 cột, associations LAZY không cascade; 15 assertions mapping/repository đạt. SQL conflict/ID/history thực tế dùng `FOR UPDATE` (log `mapping-sql.log`); DDL/FK/constraints bảng sẵn có giữ nguyên.
- Backend: **149 assertions Spring/MySQL thật đạt** — null/past/equal/reversed ngày, note 500/501, CUSTOMER/ACTIVE và stale principal, missing/private/hidden Car, giá Long × số ngày, snapshot bất biến, pickup 300/301 và bỏ phần trống, overflow không tạo đơn/event. PENDING/CONFIRMED chặn mọi overlap, REJECTED/CANCELLED/COMPLETED không chặn; chạm mép cả hai hướng thành công. Nhiều create trong cùng transaction giữ đủ đơn/event và car edit đã flush trước detach.
- Hai connection độc lập ở REPEATABLE READ: B có snapshot thường và managed Car trước A commit; B chờ đúng khóa Car của A, sau commit bị current conflict read chặn → đúng một booking/BOOKING_CREATED. Stale managed Car sau hide/change price được reload dưới khóa: không đặt xe đã ẩn, giá snapshot mới đúng. Quan sát lock wait bằng `performance_schema.data_lock_waits` + threads/Car data_locks; không phụ thuộc nhãn `INNODB_TRX` từng trả RUNNING trong khi wait thực sự tồn tại.
- MySQL timeout thật 1205/40001 → **HTTP 409** tiếng Việt, không lộ SQL/stack trace; cả đơn/event thành công trước đó trong cùng transaction cũng rollback. QueryTimeout handler, escape message và fallback kiểm chứng qua MVC handler chỉ tồn tại trong harness local, không thêm endpoint production. Warning timeout trong log là kịch bản chủ động.
- Review phát hiện ngày năm 10000 hợp lệ trong Java nhưng vượt DATE MySQL. RED tái hiện JpaSystemException trước fix; thêm service guard end ≤31/12/9999 trước mọi query, GREEN chặn cả start/end vượt phạm vi và đọc lại đúng ngày biên 31/12/9999. Review cuối **Approved**, không finding còn mở.
- Commands đã chạy: `mvnw.cmd -q compile`; `mvnw.cmd -q package -DskipTests`; `.scratch/phase3/batch2/run-check.ps1 all`; `run-check.ps1 mapping` (SQL); `run-check.ps1 errors`; `python .scratch/phase3/batch2/verify-db.py`; `.scratch/phase3/restart.ps1`; `python .scratch/phase3/batch2/verify-http.py`.
- App 8080 đã restart; **16 checks HTTP chỉ đọc** đạt cho login 3 role, public/Owner/Admin cars, notification/CSRF/bell và Admin **Duyệt xe**. Toàn bộ row/timestamp/hash/cột/constraints của 7 bảng khớp baseline đầu đợt; sáu ảnh seed khớp baseline gốc. Fixture đã rollback/cleanup đúng ID; không đổi schema/seed/dependency, không Java unit test/stage/commit/push.
- Evidence local: `.scratch/phase3/batch2/{VerifyBookings.java,run-check.ps1,check.log,mapping-sql.log,errors.log,date-range-red.log,verify-db.py,db-before.json,verify-http.py,http.log}`; review `.superpowers/sdd/phase-3-booking/batch-2-review.md`. Clone mới không có artifact bị Git bỏ qua.
- Giới hạn: chưa có controller/form/list/detail/availability booking hoặc payment mapping; chưa browser booking hay inject deadlock riêng. Đợt này verify backend độc lập, `/bookings/new` vẫn 404. **Tiếp theo đợt 3: P3.6 → P4.1 → P3.7.**

## Kết quả verify — đợt 3 (P3.6 → P4.1 → P3.7, 2026-10-11)

- Hoàn tất form Customer chỉ cho xe public, POST CSRF + validation/PRG, estimate JS và availability trên detail; list scoped/pagination/empty state, generic detail ba role, giá/địa chỉ snapshot và trạng thái payment thật. Port ba prototype booking theo layout A; thêm status fragment, ảnh thiếu có fallback truy cập được. Header Đơn thuê desktop/mobile và Duyệt xe Admin hoạt động; BOOKING notification nối detail.
- P4.1 hoàn tất đúng một lần trong đợt này: entity/enums/repo map payments có sẵn, retry FK không unique, khóa current Payment sau Car/Booking. Refund rollback fixture giữ Booking update/persistence context và amount/paid_at, chỉ PAID→REFUNDED, reload đúng. Chưa triển khai PaymentService/UI hoặc cancel.
- RED ban đầu: form/list 404, Payment chưa có metamodel. GREEN **118 assertions Spring/MySQL/MVC**: seed enum/status/method, lock/refund, form/bound fields/identity/forgery, CSRF, role/status/ownership, snapshot, payment thật, notification link, list/empty/pagination, availability và invalid dates. **149 assertions backend hồi quy** (batch 2 all) đạt, gồm stale snapshot/concurrency/timeout rollback.
- **117 kiểm tra HTTP/MySQL** đạt: create PENDING + Owner event, overlap/chạm mép cả hai hướng, validation tiếng Việt, snapshot sau car edit, quyền ba role, payment seed, CSRF và hai submit đồng thời chỉ một booking/notification. **47 checks Playwright** đạt: actual submit và DB/event đối chiếu, estimate 4 ngày, BigInt Long max/overflow, equal/reversed dates, form/list/detail 1440/390/320px không tràn ngang/pageerror, mobile Đơn thuê, Owner notification, Admin PAID/unpaid/Duyệt xe. Đã xem ảnh desktop/mobile.
- Review phát hiện malformed date lộ English converter; RED ở validation-red.log, sửa 2 message keys rồi GREEN. HTTP RED phát hiện availability BusinessException làm transaction rollback detach Car/Category được load trước đó; load view entities sau availability xử lý lỗi, GREEN trả 400 đầy đủ cho ngày thiếu/sai/past/equal/reversed/out-of-range. Review độc lập Approved. Script MySQL fixture chỉ định utf8mb4 khi đọc/ghi tiếng Việt; baseline hash giữ charset như snapshot ban đầu.
- Commands: `mvnw.cmd -q compile`; `mvnw.cmd -q package -DskipTests`; `.scratch/phase3/batch3/run-check.ps1 all`; `.scratch/phase3/batch2/run-check.ps1 all`; `.scratch/phase3/restart.ps1`; `python .scratch/phase3/batch3/verify-http.py prepare|run|cleanup`; Playwright `browser-check.js`; `python .scratch/phase3/batch3/verify-db.py` và SHOW CREATE TABLE payments.
- Fixture commit ghi đúng ID, gồm booking tạo từ browser, đã cleanup theo FK; mọi row/timestamp/hash/cột/constraint của 7 bảng và sáu ảnh seed khớp baseline đầu đợt. Không schema/dependency/seed change, không Java unit test/stage/commit/push. App 8080 chạy bản mới; AUTO_INCREMENT có thể tăng dù cleanup/rollback.
- Evidence local `.scratch/phase3/batch3/{VerifyBookingUi.java,run-check.ps1,red.log,validation-red.log,availability-http-red.log,check.log,backend-regression.log,build.log,http.log,browser-check.js,browser-result.json,form-*.png,list-*.png,detail-*.png,payment-ddl.txt,fixtures-cleaned.json,db-before.json,baseline.log}`; review `.superpowers/sdd/phase-3-booking/batch-3-review.md`. Artifact bị Git bỏ qua.
- Giới hạn: chưa cancel/refund nghiệp vụ, Owner list/confirm/reject/complete, Admin booking riêng hoặc payment service/history/UI; chưa inject deadlock riêng. **Tiếp theo đợt 4: P3.8 → P3.9 → P3.10.**

## Kết quả verify — đợt 4 (P3.8–P3.10, 2026-10-11)

- Hoàn tất Customer cancel/refund PAID atomic + notification, giữ amount/paid_at/FAILED retry. Owner scoped list/detail layout B, phân trang/empty state/thumbnail fallback và Customer contact/snapshot/payment thật; confirm/reject guard PENDING + Owner/ACTIVE/ownership. Reject trim 1–500 ở MVC và Service, modal focus/Escape/no-JS form; Customer cancel và Owner confirm có xác nhận JS. Các trang xe/đơn Owner có Đơn thuê/chuông; generic detail có Quản lý đơn thuê. Complete/payment endpoint chưa bật.
- RED thiếu cancelBooking trước code. GREEN **232 assertions Spring/MySQL/MVC**: cancel PENDING/CONFIRMED+PAID, terminal/repeat/role/status/ownership, refund hiện tại và chỉ PAID, paid_at/amount giữ nguyên, MVC CSRF/PRG/nav/scope/pagination, lý do null/blank/control-only/501/500. Notification bị lỗi rollback cả trạng thái/refund và event đã ghi (bao gồm lỗi event thứ hai); confirm/reject rollback state/event.
- Hai connection REPEATABLE READ với Booking managed/snapshot cũ, xác nhận chờ khóa Car bằng performance_schema.data_lock_waits: cancel→cancel/confirm/reject, confirm→reject/cancel, reject→confirm/cancel. Sau commit A, B chỉ chạy khi state mới hợp lệ: confirm→cancel có hai transition/event và cuối CANCELLED; các race khác chỉ một transition/event. Stale managed Payment PENDING nhưng DB mới PAID vẫn refund đúng, outer rollback giữ thay đổi độc lập.
- Review + RED race phát hiện Hibernate refresh trên entity đã giữ khóa phát SELECT thường, đọc snapshot cũ và cancel trùng. Sửa Booking targeted detach→FOR UPDATE; Payment tương tự trước guard/refund và sau bulk flush, không clear context. GREEN bảy race/current Payment đạt. Sửa reason control-only trở thành rỗng sau trim và duplicate flash. Review **Approved**, không finding còn mở.
- `mvnw.cmd -q compile`, `mvnw.cmd -q package -DskipTests` thành công. **118 assertions hồi quy batch3** đạt; chỉ cập nhật kỳ vọng no-cancel đã lỗi thời, giữ bộ kiểm tra cũ. **139 HTTP/MySQL checks** đạt: cancel/refund/repeat, confirm/reject/reason/escape, scope/CSRF, snapshots, recipient, hai submit cancel đồng thời và confirm-vs-reject chỉ một event. **57 Playwright + 11 DB checks** đạt: 1440/390/320px, list/detail/modal không tràn page, nav/chuông, focus/Escape, một flash, actual confirm/reject/cancel-refund và no-JS reject. Native confirm quan sát ở lượt đầu; accept/dismiss dùng controlled window.confirm lượt sau để tool trả kết quả đầy đủ. Đã xem ảnh desktop/mobile; bảng Owner mobile có vùng cuộn ngang riêng.
- Commands: `.scratch/phase3/batch4/run-check.ps1`; `run-regression.ps1`; `.scratch/phase3/restart.ps1`; `python .scratch/phase3/batch4/verify-http.py prepare|run|cleanup`; Playwright `browser-check.js`; `python .scratch/phase3/batch4/verify-browser-db.py`; `python .scratch/phase3/batch4/verify-db.py`. Fixture rollback hoặc commit ghi đúng ID; cleanup theo FK và baseline 7 bảng/sáu ảnh seed đạt. Không schema/seed/dependency/Java unit test/stage/commit/push.
- Evidence local `.scratch/phase3/batch4/{VerifyTransitions.java,run-check.ps1,red.log,stale-refresh-red.log,check.log,compile.log,build.log,batch3-regression.log,verify-http.py,http.log,browser-check.js,browser-result.json,verify-browser-db.py,owner-*.png,reject-modal-390.png,customer-cancelled-320.png,fixtures-cleaned.json,db-before.json,baseline.log}`; review `.superpowers/sdd/phase-3-booking/batch-4-review.md`. Artifact bị Git bỏ qua; AUTO_INCREMENT có thể có khoảng trống.
- Sau cleanup, `final-smoke.py` đạt **21 kiểm tra HTTP chỉ đọc** bằng tài khoản seed ba role; form/list/detail Customer, Owner bookings/cars/notifications và Admin review/header đều hoạt động. Evidence `final-smoke.log`; app 8080 chạy bản mới, `git diff --check` không lỗi, index không staged changes.
- Giới hạn: chưa complete/hard-delete/history/E2E cuối Phase 3, chưa payment service/history/UI; chưa inject deadlock riêng. **Tiếp theo đợt 5: P3.11 → P3.12 → P3.13.**


## Kết quả verify — đợt 5 (P3.11–P3.13, 2026-10-11)

- **P3.11:** Owner complete qua POST `/owner/bookings/{id}/complete`, nút **Hoàn tất đơn thuê** trên detail riêng Owner chỉ khi CONFIRMED và ngày trả đã qua. Service guard OWNER/ACTIVE/ownership, khóa Car → Booking và reload current; hôm nay/tương lai, status khác và thao tác lặp bị chặn. COMPLETED + Customer BOOKING_COMPLETED atomic; không bắt buộc PAID. Có xác nhận tiếng Việt; fixture chưa thanh toán hoàn tất thành công.
- **P3.12:** POST `/owner/cars/{id}/delete` và icon xóa trên Xe của tôi; UI disable khi bất kỳ lịch sử thuê, Service vẫn kiểm tra current history dưới khóa Car. Xóa DB CarImages/Car rồi dọn file sau commit; rollback giữ row/file, lỗi dọn file được log. Query lịch sử cho các xe trên một page chạy theo batch. Tái sử dụng callback cho deleteImage; regression commit/rollback ảnh riêng đạt.
- RED từ tool output: thiếu completeBooking/deleteCar; stale snapshot ảnh gây file mồ côi; Admin long brand/email tràn ngang 320px. PowerShell chuyển stderr thành terminating error nên raw RED log rỗng; transcript chính xác tại `red-evidence.md`, ảnh RED `admin-overflow-red-320.png`. Sửa image paths bằng locking query và Admin inherited overflow-wrap:anywhere. Review độc lập cuối **Approved**, không finding còn mở.
- GREEN **192 assertions Spring/MySQL/MVC** đạt: date/status/role/ACTIVE/stale role/ownership/CSRF/PRG/notification, complete lỗi event rollback, mọi status history chặn xóa, afterCommit và rollback file/row. Hai connection REPEATABLE READ kiểm chứng wait đúng Car bằng performance_schema: complete→cancel, cancel→complete, complete→complete; create→delete, delete→create, create→hide, hide→create; upload→delete và delete→upload. Snapshot/entity cũ không quyết định transition/lịch sử/image membership; không orphan row/file/event.
- MySQL **timeout 1205 và deadlock 1213 thật** (không chỉ ném exception giả): MVC 409 tiếng Việt, không lộ SQL/stacktrace. Kịch bản đã hủy đơn PAID trước khi lỗi khóa vẫn rollback về CONFIRMED+PAID và 0 event; failed create không để row/event. Handler thử chỉ đăng ký trong harness local, không có endpoint production.
- Hồi quy **149 backend booking + 118 form/list/detail/payment + 232 transitions** đạt; không thay bộ kiểm tra lịch sử. Build/package và boot8080 thành công. **225 HTTP/MySQL checks** đạt đủ E2E ba role và 5 kết quả từng ID: CONFIRMED, REJECTED, CANCELLED chưa PAID, CANCELLED+REFUNDED, COMPLETED; đúng recipient/type/related ID, identity/price/status forgery bị bỏ qua, XSS escaped, CSRF/permissions/pagination/date/history gates.
- **65 Playwright + 6 browser checks bổ sung + 5 DB assertions** đạt. E2E browser thật gồm upload ảnh/tạo xe → Admin từ header Duyệt xe → approve → Customer tìm/đặt/list/detail → Owner notification/Quản lý đơn thuê/confirm → Customer notification detail. Complete/delete thực hiện từ UI, dismiss và accept xác nhận qua controlled window.confirm; context sạch bổ sung quan sát native confirm và dismiss thật của cả hai thao tác. Desktop/mobile 1440/390/320px không tràn page/pageerror; Owner table cuộn nội bộ. Đã xem screenshots complete/delete/customer final và Admin long-text320 sau sửa.
- Commands: `mvnw.cmd -q compile`; `mvnw.cmd -q package -DskipTests`; `batch5/run-check.ps1`; `batch2/run-check.ps1 all`; `batch4/run-check.ps1`; `batch4/run-regression.ps1`; `phase3/restart.ps1`; `python batch5/verify-http.py prepare|run|browser-db|cleanup`; Playwright `browser-check.js`, `browser-extra.js`; `python batch5/verify-db.py`; `python batch5/final-smoke.py` (các path relative `.scratch/phase3/`).
- Fixture rollback/commit ghi đúng ID, cleanup theo FK và file upload fixture; **baseline toàn bộ rows/timestamps/hash/schema/constraints của 7 bảng và sáu ảnh seed giữ nguyên**. Sau cleanup **21 HTTP smoke chỉ đọc** bằng seed users ba role đạt. App8080 đang chạy; `git diff --check` không lỗi, index trống, giữ nhánh `feat/phase2-car`; không schema/seed/dependency/Java unit test/stage/commit/push. AUTO_INCREMENT có thể tăng sau rollback/cleanup.
- Evidence local `.scratch/phase3/batch5/{VerifyFinal.java,run-check.ps1,red-evidence.md,check.log,compile.log,build.log,batch2-regression.log,batch3-regression.log,batch4-regression.log,http.log,browser-result.json,browser-extra-result.json,browser-db.log,fixtures-cleaned.json,db-before.json,baseline.log,final-smoke.log,*.png}`; review `.superpowers/sdd/phase-3-booking/batch-5-review.md`. Artifact bị Git bỏ qua. **Phase 3 hoàn tất; tiếp theo P4.2**, P4.1 đã làm đúng một lần. Payment service/UI/history, Admin booking riêng và dashboard/profile còn theo các phase sau; hoàn tiền hiện mô phỏng, không kết nối cổng tiền thật.
