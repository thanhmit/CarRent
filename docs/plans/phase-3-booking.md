# CarRent — Phase 3: Notification & Booking — Implementation Plan

> **For agentic workers:** Chạy task-by-task. Checkbox `- [ ]` tracking. Không git (D-19). Quy ước verify chung ở `phase-0-init.md`.

**Goal:** Notification trong DB + toàn bộ nghiệp vụ Booking (tạo → duyệt → hủy → hoàn tất) — FR-10, FR-11, FR-13, FR-14.

> **D-24 / SDD v2.1:** Bảng bookings/notifications và seed đã có từ `scriptDB.sql`; Hibernate `ddl-auto=none`, entity map đúng §7.2/7.6, không tạo lại bảng. Mọi verify lọc theo ID/định danh của record kịch bản hoặc so số lượng trước/sau; không giả định cả bảng chỉ có 1 row. Seed có booking PENDING/CONFIRMED giữ lịch: chọn xe/ngày không trùng hoặc tạo xe riêng cho kịch bản.

> **Phụ thuộc do DB có payment seed:** Sau P3.6, chạy **P4.1 (Payment entity/enums/repository) trước P3.7–P3.8**. P3.7 phải đọc trạng thái thanh toán thật; P3.8 triển khai refund ngay để hủy booking mẫu đã PAID không tạo trạng thái CANCELLED+PAID. P4.2–P4.6 vẫn triển khai sau Phase 3; P4.1 không chạy lại.

**Architecture:** `NotificationService` là điểm hội tụ: các Service nghiệp vụ gọi nó trong cùng transaction. `BookingService` nắm rules ngày/overlap/giá/snapshot + transitions.

**Tech Stack:** Spring Data JPA, `@Transactional`, Thymeleaf.

**Spec:** `docs/SDD.md` §5 FR-10→FR-13, §6 BR-04→BR-08, §7.2 (bookings/notifications), §10; `docs/decisions.md` D-04, D-05, D-09, D-12, D-13, D-14, D-15.

---

## Global Constraints (tóm tắt)

- Quy ước phase-0. Notification type dùng đúng 10 giá trị SDD §7.2 (CAR_PENDING_APPROVAL, CAR_APPROVED, CAR_REJECTED, BOOKING_CREATED, BOOKING_CONFIRMED, BOOKING_REJECTED, BOOKING_CANCELLED, BOOKING_COMPLETED, PAYMENT_PAID, PAYMENT_FAILED).
- Booking tạo → PENDING, chặn lịch (D-04). Kiểm tra overlap + save cùng transaction (D-13).
- Refund payment PAID ngay tại P3.8 khi hủy booking; P4.1 mapping/repository đã chạy sau P3.6. P4.6 verify lại, không trì hoãn refund.
- Mọi POST CSRF. UI tiếng Việt.
- **UI port từ prototype Stitch** (`docs/design/stitch/`, quy tắc ở `README.md`): layout A cho customer, layout B cho owner/admin. Trang không có prototype (notification list) dựng theo cùng design system.

## Review Focus

1. Hai khoảng thuê chồng mép (A: 1–5, B: 5–10) không trùng theo BR-05 → cả hai tạo được — Task 5, 6.
2. Owner B confirm booking trên xe của Owner A → 403/404 — Task 10.
3. Customer hủy booking COMPLETED → BusinessException, status không đổi — Task 8.
4. Thêm date filter cho notification list nếu quá chậm — hiệu năng không phải lo ngại MVP.
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

- [ ] **Step 1: Viết entity + repo.** Column name cho field `read`: `@Column(name="is_read")` tránh từ khóa SQL.
- [ ] **Step 2: Verify** — boot → `DESCRIBE notifications;` đủ 9 cột, FK user_id đúng.

### Task P3.2: NotificationService + controller + view + badge navbar

**Files:**
- Create: `src/main/java/com/carrent/notification/NotificationService.java`, `NotificationController.java`, `web/NavbarControllerAdvice.java` (đặt ở `com.carrent.web`).
- Create: `src/main/resources/templates/notification/list.html`.
- Modify: `templates/fragments/header.html` — thêm icon chuông + badge unread.

**Interfaces:**
- Produces: `NotificationService`:
  - `notify(Long userId, String type, String title, String message, String relatedEntityType, Long relatedEntityId)` — build + save. (public, gọi từ Car/Booking/Payment service.)
  - `Page<NotificationDto> getNotifications(userId, pageable)` — DTO: id, title, message, type, read, relatedEntity..., createdAt (format trong template).
  - `markRead(id, userId)` — check `findByIdAndUserId` → sai → NotFoundException; save read=true.
  - `markAllRead(userId)` — findByUserIdAndReadFalse → set read → saveAll.
  - `long countUnread(userId)`.
- Produces: `NotificationController` `@GetMapping("/notifications")` (model notifications Page + pagination), `@PostMapping("/notifications/{id}/read")`, `@PostMapping("/notifications/read-all")` — cả 2 POST redirect `/notifications` + flash.
- Produces: `NavbarControllerAdvice` `@ControllerAdvice` — mọi view có attribute `unreadCount` (0 nếu anonymous — check `Authentication` null/principal instanceof).
- Produces: template list — **không có prototype → dựng theo design system layout A** (fragment `head`/`header`/`footer` từ P0.3): item list (icon theo type, title, message, thời gian tương đối (method tĩnh helper hiển thị "x phút trước" — optional, chấp nhận hiển thị format dd/MM/yyyy HH:mm), badge "Chưa đọc", nút "Đánh dấu đã đọc" form POST từng cái + nút "Đánh dấu tất cả".

- [ ] **Step 1: Viết service + controller + advice + template + sửa header.**
- [ ] **Step 2: Verify** — SQL insert tay 2 notification cho test user: `INSERT INTO notifications (user_id,title,message,type,is_read,created_at) VALUES ((SELECT id FROM users WHERE email='testcustomer'),'T1','Nội dung','CAR_APPROVED',0,NOW());` → login testcustomer → `GET /notifications` = 200, thấy 2 item "Chưa đọc", navbar badge = 2 (body chứa class badge + số 2). POST read 1 cái → SQL is_read=1, badge = 1. POST read-all → badge = 0 (login lại GET /).

### Task P3.3: Cắm notification cho sự kiện xe

**Files:**
- Modify: `CarService.java` — `createCar` notify mọi ADMIN (FR-13: "Có xe mới chờ duyệt" → type CAR_PENDING_APPROVAL, related CAR id); `approveCar`/`rejectCar` notify owner (CAR_APPROVED / CAR_REJECTED, message kèm brand/model + reason nếu reject).
- (NotificationService.notify cần UserRepository list admin: thêm `List<User> findByRole(Role.ADMIN)` vào UserRepository.)

- [ ] **Step 1: Viết cắm notify** — method riêng trong CarService `notifyAdminsNewCar(car)` / `notifyOwnerApproval(car)` để đỡ lặp.
- [ ] **Step 2: Verify** — curl tạo xe mới (owner) → SQL notifications có row user_id=admin, type CAR_PENDING_APPROVAL. curl approve → row user_id=owner, type CAR_APPROVED. Message chứa brand/model.

### Task P3.4: Booking entity + enum + repo (conflict query)

**Files:**
- Create: `src/main/java/com/carrent/booking/Booking.java`, `BookingStatus.java`, `BookingRepository.java`.

**Interfaces:**
- Produces: `Booking` per SDD §7.2 bookings: customer (ManyToOne User), car (ManyToOne Car), startDate/endDate LocalDate, pricePerDaySnapshot Long, totalAmount Long, pickupAddress String ≤300, status enum, customerNote/ownerNote ≤500 nullable, timestamps.
- Produces: `BookingStatus` = {PENDING, CONFIRMED, REJECTED, CANCELLED, COMPLETED}.
- Produces: `BookingRepository`:
  - `@Query("select count(b) > 0 from Booking b where b.car.id = :carId and b.status in (com.carrent.booking.BookingStatus.PENDING, com.carrent.booking.BookingStatus.CONFIRMED) and b.startDate < :end and b.endDate > :start") boolean existsConflict(@Param("carId") Long carId, @Param("start") LocalDate start, @Param("end") LocalDate end);`
  - `Page<Booking> findByCustomerId(Long customerId, Pageable pageable)`
  - `Page<Booking> findByCarOwnerId(Long ownerId, Pageable pageable)`
  - `Page<Booking> findAllBy... (admin — dùng findAll(spec, pageable) hoặc query tương tự theo filter; MVP: findAll(pageable) + filter status bằng query param: `Page<Booking> findByStatus(BookingStatus status, Pageable pageable)` kết hợp null-check.)
  - `boolean existsByCarId(Long carId)` (cho P3.12 xóa xe)

- [ ] **Step 1: Viết entity + enum + repo.**
- [ ] **Step 2: Verify** — boot → `DESCRIBE bookings;` đủ cột SDD, FK customer_id/car_id.

### Task P3.5: BookingService.create

**Files:**
- Create: `src/main/java/com/carrent/booking/BookingService.java`, `BookingCreateForm.java`.

**Interfaces:**
- Produces: `BookingCreateForm`: carId `@NotNull`; startDate `@NotNull @FutureOrPresent` (LocalDate); endDate `@NotNull`; customerNote `@Size(max=500)`.
- Produces: `BookingService.createBooking(BookingCreateForm form, CustomUserPrincipal principal) → Booking` — `@Transactional`:
  1. Load car (nếu không tồn tại → NotFoundException). Role check: principal phải CUSTOMER (owner/admin không đặt xe — BusinessException "Chỉ khách hàng mới có thể đặt xe").
  2. Validate ngày (BR-04): startDate < endDate (nếu sai → BusinessException "Ngày kết thúc phải sau ngày bắt đầu"); days = `ChronoUnit.DAYS.between(start, end)` ≥ 1.
  2. BR-03: car APPROVED + ACTIVE, ngược lại BusinessException "Xe hiện không nhận đơn".
  3. Overlap: `existsConflict` → true → BusinessException "Xe đã có đơn trùng thời gian thuê".
  4. Tính: total = `car.getPricePerDay() * days` (BR-06 — từ DB, KHÔNG từ form); snapshot pricePerDay + pickupAddress = province+district+addressDetail (join, chuỗi rỗng bỏ) (D-09, D-15).
  5. Build Booking status PENDING; save.
  6. notify Owner (BOOKING_CREATED, related BOOKING id) — message: brand/model + khoảng ngày.

- [ ] **Step 1: Viết form + service.**
- [ ] **Step 2: Verify** — compile (runtime gộp P3.6).

### Task P3.6: Form tạo booking + ước tính giá JS + availability check trên detail

**Files:**
- Create: `src/main/java/com/carrent/booking/BookingController.java`, `src/main/resources/templates/booking/create.html`, `src/main/resources/static/js/booking-price.js`.
- Modify: `templates/cars/detail.html` — bật form kiểm tra khả dụng (đã ẩn từ P2.13): GET `/cars/{id}?start=...&end=...` → model `availability` (service method `checkAvailability(carId, start, end) → boolean` dùng existsConflict) → banner xanh/đỏ.

**Interfaces:**
- Produces: `GET /bookings/new?carId=` → load car (APPROVED+ACTIVE check) → model car + form rỗng → view `booking/create.html`. Khuyến nghị: thêm service `CarService.getCarForBooking(carId)` — reuse P2.13 getPublicCar logic.
- Produces: `POST /bookings` — `@Valid BookingCreateForm` → service create → redirect `/bookings/{id}` + flash "Đặt xe thành công!"; BusinessException → flash error + redirect `/bookings/new?carId=`; validation lỗi → re-render form.
- Produces: `booking-price.js` — 2 input date + data-price attribute → live tính `days × price` hiển thị (chỉ UI, server là nguồn chân lý).
- Produces: template `booking/create.html` — **port từ `docs/design/stitch/booking-xac-nhan-dat-thue.html` (layout A)**: khối tóm tắt xe + khoảng ngày + giá ước tính + nút "Xác nhận đặt xe" giữ nguyên markup Tailwind, bind `th:object` BookingCreateForm.
- Produces: `BookingService.checkAvailability(carId, start, end) → boolean`.

- [ ] **Step 1: Viết controller + template + JS + sửa detail.**
- [ ] **Step 2: Verify — flow curl đầy đủ**

Login customer (P1.7 flow) → GET `/bookings/new?carId=<id xe APPROVED>` = 200. POST booking (CSRF + jar):
```
startDate=2026-11-01&endDate=2026-11-05&carId=<id>&customerNote=note&_csrf=...
```
→ 302 → SQL bookings: 1 row PENDING, total_amount = price×4, snapshot = price, pickup_address đầy đủ. Notifications: owner có row BOOKING_CREATED.

Overlap test: POST booking thứ 2 cùng xe `2026-11-04 → 2026-11-08` → flash lỗi "trùng" (redirect, không row mới). Chồng mép: booking 3 `2026-11-05 → 2026-11-09` → **tạo thành công** (5–9 không giao 1–5 theo BR-05: start < 5 = sai... end 9 > start 5 đúng, start 5 < end 5 sai → 5 < 5 false → không conflict). Ngược lại `2026-11-01 → 2026-11-05` so với 5–9: start 1 < 9 ok, end 5 > 1 ok → conflict).

### Task P3.7: Booking detail + list (customer) + quyền xem 3 role

**Files:**
- Modify: `BookingController.java`.
- Create: `src/main/resources/templates/booking/list.html`, `booking/detail.html`.

**Interfaces:**
- Produces: `GET /bookings` (customer) → Page<Bookings of principal> → view list — **port từ `docs/design/stitch/booking-don-thue-cua-toi.html` (layout A)**: card/table (xe, khoảng ngày, tổng tiền, badge status, link detail) + pagination + empty state.
- Produces: `GET /bookings/{id}` — service `getBookingForView(id, principal)`:
  - ADMIN → OK; customer = booking.customer → OK; owner = booking.car.owner → OK; khác → NotFoundException (không lộ).
  - Model: booking + car + `hasPaid` từ `paymentRepository.existsByBookingIdAndStatus(id, PAID)`; hiển thị "Đã thanh toán"/"Chưa thanh toán" theo DB, không giả định payment null vì chưa đến Phase 4. Lịch sử payment đầy đủ sẽ cắm ở P4.4.
- Template detail — **port từ `docs/design/stitch/booking-chi-tiet-don-thue.html` (layout A)**: thông tin xe (link), khoảng ngày, giá snapshot, pickup, status badge, customer/owner note, nút hành động theo role + trạng thái (customer: hủy khi PENDING/CONFIRMED, thanh toán khi CONFIRMED — link `/bookings/{id}/payment` Phase 4; owner: confirm/reject khi PENDING, complete khi CONFIRMED + end_date quá — task P3.9/P3.10 template riêng, detail view dùng chung fragment hiển thị nút).

- [ ] **Step 1: Viết 2 template + method service.**
- [ ] **Step 2: Verify** — customer GET `/bookings` = 200 (booking từ P3.6). GET `/bookings/{id}` customer owner-of-car = 200; customer khác (tạo customer 2) = 404; anonymous = 302 login.

### Task P3.8: Customer cancel

**Files:**
- Modify: `BookingService.java`, `BookingController.java` (thêm `POST /bookings/{id}/cancel`), `booking/detail.html` (nút hủy + confirm JS).

**Interfaces:**
- Consumes: `PaymentRepository` từ P4.1 đã chạy trước task này.
- Produces: `BookingService.cancelBooking(id, principal)` — `@Transactional`: load + check customer là chủ (khác → ForbiddenException); status ∈ {PENDING, CONFIRMED} (khác → BusinessException "Không thể hủy đơn này"); set CANCELLED; gọi `paymentRepository.refundByBookingId(id)` để chuyển mọi PAID của booking sang REFUNDED; nếu số row refund > 0 thì notify customer (type BOOKING_CANCELLED, message "Đơn đã hoàn tiền đầy đủ bằng hình thức giả lập"); notify owner (BOOKING_CANCELLED). Tất cả trong cùng transaction. Không đổi amount/paid_at hoặc tạo payment mới khi refund.

- [ ] **Step 1: Viết method + route + nút.**
- [ ] **Step 2: Verify** — curl POST cancel booking PENDING → SQL status=CANCELLED; notification owner BOOKING_CANCELLED. POST cancel lần 2 (đã CANCELLED) → flash lỗi, status không đổi. Hủy booking mẫu #2 (customer1, CONFIRMED+PAID) → booking CANCELLED + payment #2 REFUNDED cùng transaction; notification hoàn tiền cho customer. Sau đó giữ trạng thái đã xử lý, không chạy SQL để reset seed.

### Task P3.9: Owner bookings list + detail

**Files:**
- Create: `src/main/java/com/carrent/booking/OwnerBookingController.java`, `templates/owner/bookings.html`, `templates/owner/booking-detail.html`.
- Create: `src/main/java/com/carrent/booking/BookingSummaryDto.java` (dùng cho owner dashboard P5.7 sau) — fields: totalBookings, pendingBookings, totalRevenue... (tạm chỉ method count cơ bản).

**Interfaces:**
- Produces: `GET /owner/bookings` → `findByCarOwnerId(principal.getId(), pageable)` → view: table (xe, customer, ngày, total, badge, nút detail).
- Produces: `GET /owner/bookings/{id}` — reuse `getBookingForView` (owner-of-car pass) → view detail owner mode: thông tin customer (fullName, phone, email), nút Confirm/Reject/Complete theo trạng thái.

- [ ] **Step 1: Viết controller + 2 template.**
- [ ] **Step 2: Verify** — login owner → GET `/owner/bookings` = 200 (booking từ P3.6). Login owner khác (không sở hữu xe) → list empty (không crash). GET detail booking xe của owner A với owner B → 404.

### Task P3.10: Owner confirm/reject

**Files:**
- Modify: `OwnerBookingController.java`, `BookingService.java` (confirmBooking/rejectBooking), `templates/owner/booking-detail.html` (nút + modal lý do).

**Interfaces:**
- Produces: `BookingService.confirmBooking(id, principal)` — ownership check (owner của xe), status phải PENDING → CONFIRMED; notify customer BOOKING_CONFIRMED.
- Produces: `BookingService.rejectBooking(id, principal, reason)` — ownership; PENDING → REJECTED + ownerNote = reason (`@Size(max=500)`); notify customer BOOKING_REJECTED (message kèm reason).

- [ ] **Step 1: Viết 2 method + route POST + UI.**
- [ ] **Step 2: Verify** — curl POST confirm booking → CONFIRMED + notification customer. POST reject booking khác (tạo trước) → REJECTED + ownerNote + notification. Owner B confirm xe owner A → 403/404. Confirm booking đã CONFIRMED → flash lỗi.

### Task P3.11: Owner complete

**Files:**
- Modify: `OwnerBookingController.java`, `BookingService.java`, `templates/owner/booking-detail.html`.

**Interfaces:**
- Produces: `BookingService.completeBooking(id, principal)` — ownership; status CONFIRMED; `endDate < LocalDate.now()` (chưa tới → BusinessException "Chưa thể hoàn tất: thời gian thuê chưa kết thúc"); → COMPLETED; notify customer BOOKING_COMPLETED.

- [ ] **Step 1: Viết method + route + nút.**

- [ ] **Step 2: Verify** — Tạo booking CONFIRMED chưa có payment trên xe test riêng. Chuyển cả khoảng ngày và tổng tiền trong **một UPDATE**: `UPDATE bookings SET start_date=DATE_SUB(CURRENT_DATE(), INTERVAL 3 DAY), end_date=DATE_SUB(CURRENT_DATE(), INTERVAL 1 DAY), total_amount=price_per_day_snapshot*2 WHERE id=<testBookingId> AND status='CONFIRMED';` (thay ID thật; không sửa seed/booking đã có payment). CHECK ngày/tổng tiền vẫn hợp lệ, xe test riêng tránh overlap. POST complete → COMPLETED. Complete booking khác có end_date tương lai → flash lỗi. Khi kiểm tra riêng câu SQL trên DB seed, bọc START TRANSACTION/ROLLBACK để không thay dữ liệu mẫu.

### Task P3.12: Xóa cứng Car + verify tổng phase 3

**Files:**
- Modify: `CarService.java` (thêm `deleteCar(carId, principal)` — dời từ P2.5), `OwnerCarController.java` (POST `/owner/cars/{id}/delete`), `templates/owner/cars.html` (nút xóa chỉ enable khi 0 booking).

**Interfaces:**
- Produces: `deleteCar` — ownership; `bookingRepository.existsByCarId(carId)` → true → BusinessException "Xe đã có đơn thuê, không thể xóa — hãy ngừng đăng tin"; ngược lại xóa CarImages (bản ghi + file) → xóa Car.
- [ ] Verify tổng:
  - Xóa xe không booking → cars/car_images row biến mất + file uploads biến mất.
  - Xóa xe có booking → flash lỗi, xe còn nguyên.
- [ ] Rà soát: state diagram FR-11 đủ mọi transition đều có verify ở trên; BR-04/05/06/07 có bằng chứng; permission matrix cột Booking.
  Ghi "Kết quả verify" vào cuối file.

### Task P3.13: Verify tổng phase 3 (E2E kịch bản)

*(Tách từ P3.12 để làm checkpoint rõ ràng)*
- [ ] Kịch bản E2E bằng browser (người duyệt tự bấm): owner đăng nhập → tạo xe → admin duyệt → customer tìm xe (search q=brand) → đặt xe → owner confirm → customer hủy 1 booking khác... (dùng booking thứ 2) → owner complete booking end_date cũ. Mọi bước có flash message + notification đúng người nhận.
- [ ] SQL cuối: SELECT status, COUNT(*) FROM bookings GROUP BY status; → phân bố hợp lý theo kịch bản.
