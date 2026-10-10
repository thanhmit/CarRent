# CarRent — Phase 4: Payment — Implementation Plan

> **For agentic workers:** Chạy task-by-task. Checkbox `- [ ]` tracking. Không git (D-19). Quy ước verify chung ở `phase-0-init.md`.

**Goal:** Mock payment hoàn chỉnh (FR-12) + refund khi hủy booking đã PAID (BR-08) + hiển thị trạng thái thanh toán.

> **D-24 / SDD v2.1:** Bảng payments và seed đã có từ `scriptDB.sql`; Hibernate `ddl-auto=none`, entity map đúng §7.2/7.6, không tạo lại bảng. `booking_id` không unique. Verify theo booking/payment ID, không tổng cả bảng. Booking mẫu #3 CONFIRMED chưa có PAID dùng được cho payment; #2 đã PAID phải bị chặn thanh toán lại; #5 CANCELLED có REFUNDED. Không reset seed khi boot.

> **Thứ tự:** P4.1 được thực hiện ngay sau P3.6, trước P3.7/P3.8 (SDD §7.6), vì DB đã có payment mẫu. Nếu kịch bản P3.8 đã hủy #2 thì #2 hiện REFUNDED, không còn là fixture PAID; dùng booking mới CONFIRMED+PAID cho bài kiểm tra chặn thanh toán lại. P4.6 rà soát/verify refund đã implement tại P3.8.

**Architecture:** `PaymentService.processPayment` guard (chủ booking, CONFIRMED, chưa có PAID) → tạo Payment PAID/FAILED + notify; refund đã triển khai tại P3.8, task cuối rà soát và verify.

**Tech Stack:** Spring Data JPA, @Transactional, Thymeleaf.

**Spec:** `docs/SDD.md` §5 FR-12, §6 BR-09, §7.2 payments, §11.3; `docs/decisions.md` D-06, D-12.

---

## Global Constraints (tóm tắt)

- Quy ước phase-0. Payment chỉ cho booking CONFIRMED chưa có PAID (D-06). Booking giữ status CONFIRMED sau khi PAID.
- `transaction_reference` format `MCK-<epochMillis>-<random6>`.
- Notification: PAID → customer + owner; FAILED → customer.
- Mọi POST CSRF. UI tiếng Việt. Tiền VND BIGINT.
- **UI port từ prototype Stitch** (`docs/design/stitch/payment-thanh-toan.html`, quy tắc ở `README.md`): giữ class Tailwind, layout A.

## Review Focus

1. Thanh toán booking của customer khác → 404/403, không payment row — Task 2, 3.
2. Thanh toán lần 2 sau PAID (retry khi đã PAID) → blocked, không payment trùng — Task 2, 3.
3. Simulate failure → FAILED + retry tạo payment mới (payment thứ 2) — Task 3.
4. Hủy booking có PAID → payment REFUNDED (D-12) — Task 5.
5. Payment hiển thị trong booking detail cả 3 view (customer/owner/admin) — Task 4, 5.

---

### Task P4.1: Payment entity + enums + repo

**Files:**
- Create: `src/main/java/com/carrent/payment/Payment.java`, `PaymentStatus.java`, `PaymentMethod.java`, `PaymentRepository.java`.

**Interfaces:**
- Produces: `Payment` per SDD §7.2 payments: `@ManyToOne Booking` (FK booking_id, KHÔNG unique — D-06); amount Long; method enum; status enum; transactionReference String ≤50; paidAt LocalDateTime nullable; timestamps.
- Produces: `PaymentStatus` = {PENDING, PAID, FAILED, REFUNDED}; `PaymentMethod` = {MOCK_CARD, MOCK_BANK_TRANSFER, CASH_SIMULATED}.
- Produces: `PaymentRepository`:
  - `@Modifying @Query("update Payment p set p.status = com.carrent.payment.PaymentStatus.REFUNDED where p.booking.id = :bookingId and p.status = com.carrent.payment.PaymentStatus.PAID") int refundByBookingId(@Param("bookingId") Long bookingId)` — dùng tại P3.8 trong transaction của BookingService.
  - `List<Payment> findByBookingIdOrderByCreatedAtDesc(Long bookingId)`
  - `boolean existsByBookingIdAndStatus(Long bookingId, PaymentStatus status)` (check đã PAID)
  - `Optional<Payment> findByIdAndBookingId(Long id, Long bookingId)` (nếu cần — không dùng MVP)

- [ ] **Step 1: Viết entity + 2 enum + repo.**
- [ ] **Step 2: Verify** — boot → `DESCRIBE payments;` đủ cột, booking_id FK không unique (xem CREATE TABLE qua `SHOW CREATE TABLE payments;` — cột booking_id KHÔNG có `UNIQUE KEY`).

### Task P4.2: PaymentService.processPayment

**Files:**
- Create: `src/main/java/com/carrent/payment/PaymentService.java`, `PaymentForm.java`.

**Interfaces:**
- Produces: `PaymentForm`: method `@NotNull` (enum); simulateFailure boolean (checkbox).
- Produces: `PaymentService.processPayment(Long bookingId, PaymentForm form, CustomUserPrincipal principal) → Payment` — `@Transactional`:
  1. Load booking → không thấy → NotFoundException.
  2. Guard: principal.id == booking.customer.id (khác → ForbiddenException).
  3. Guard: booking.status == CONFIRMED (khác → BusinessException "Chỉ có thể thanh toán đơn đã được xác nhận").
  4. Guard: `!existsByBookingIdAndStatus(bookingId, PAID)` (đã PAID → BusinessException "Đơn này đã được thanh toán").
  5. Build Payment: amount = booking.totalAmount (server-side — BR-09); status = simulateFailure ? FAILED : PAID; paidAt = status==PAID ? now : null; ref = `"MCK-" + System.currentTimeMillis() + "-" + random 6 ký tự (String.format("%06d", new Random().nextInt(1000000)))`.
  6. Save + notify: PAID → customer (PAYMENT_PAID) + owner (PAYMENT_PAID); FAILED → customer (PAYMENT_FAILED, message kèm "vui lòng thử lại").

- [ ] **Step 1: Viết form + service.**
- [ ] **Step 2: Verify** — compile (runtime gộp P4.3).

### Task P4.3: Trang payment + POST

**Files:**
- Create: `src/main/java/com/carrent/payment/PaymentController.java`, `src/main/resources/templates/payment/mock-payment.html`.
- Modify: `templates/booking/detail.html` — nút "Thanh toán" (hiện khi customer + CONFIRMED + chưa PAID) link `/bookings/{id}/payment`; nếu đã PAID → badge "Đã thanh toán".

**Imports cần:** n/a.

**Interfaces:**
- Produces: `GET /bookings/{id}/payment` — service `getBookingForView` (chỉ customer của booking được vào — owner/admin vào trang này → 404/redirect) → model booking + form → view — **port từ `docs/design/stitch/payment-thanh-toan.html` (layout A)**: tóm tắt booking (xe, ngày, tổng tiền), 3 radio method (label tiếng Việt: "Thẻ (giả lập)" / "Chuyển khoản (giả lập)" / "Tiền mặt khi nhận xe") theo prototype, checkbox "Giả lập thanh toán thất bại", nút "Thanh toán".
- Produces: `POST /bookings/{id}/payment` — `@Valid PaymentForm` → processPayment → redirect `/bookings/{id}` + flash "Thanh toán thành công!" hoặc "Thanh toán thất bại (giả lập). Vui lòng thử lại."; BusinessException → flash + redirect về payment page.

- [ ] **Step 1: Viết controller + template + nút.**
- [ ] **Step 2: Verify — curl flow**

Login customer → GET `/bookings/<id booking CONFIRMED>` = 200 có nút Thanh toán → GET payment page = 200. POST payment (CSRF) `method=MOCK_CARD&simulateFailure=false` → 302 booking detail; SQL payments: 1 row PAID, amount = booking.total_amount, ref format MCK-..., paid_at NOT NULL; notifications: customer + owner PAYMENT_PAID.

Retry sau PAID: GET payment page → redirect/flash lỗi "đã thanh toán". POST lần 2 → không row mới.

Simulate failure: booking CONFIRMED khác → POST `simulateFailure=true` → row FAILED, paid_at NULL, notification customer PAYMENT_FAILED. Retry trên booking này (simulateFailure=false) → row PAID thứ 2 (khác id) — tổng 2 rows cho booking.

Booking PENDING → GET payment page → flash lỗi (không vào form được).

### Task P4.4: Booking detail hiển thị payment

**Files:**
- Modify: `BookingService.getBookingForView` — model thêm `payments` (findByBookingIdOrderByCreatedAtDesc) + `hasPaid` (existsByBookingIdAndStatus PAID).
- Modify: `templates/booking/detail.html` — section "Thanh toán": nếu hasPaid → badge xanh "Đã thanh toán" + list lịch sử payment (method, amount, status badge, ref, paidAt); ngược lại → "Chưa thanh toán".

- [ ] **Step 1: Sửa service + template.**
- [ ] **Step 2: Verify** — GET `/bookings/{id}` customer (booking đã PAID từ P4.3) = 200, section "Thanh toán" có badge Đã thanh toán + row payment; booking chưa PAID → "Chưa thanh toán".

### Task P4.5: Admin booking detail hiện payment

**Files:**
- Create: `src/main/java/com/carrent/admin/AdminBookingController.java` (nếu chưa — Phase 5 dựng dashboard/users; booking admin view đứng riêng đây).
- Create: `src/main/resources/templates/admin/booking-detail.html`.

**Interfaces:**
- Produces: `GET /admin/bookings` — Page findAll + filter status (query param `status`) + pagination → view `templates/admin/bookings.html` (table + badge).
- Produces: `GET /admin/bookings/{id}` — getBookingForView (ADMIN pass) → view admin booking-detail (full thông tin + section payment như P4.4).

- [ ] **Step 1: Viết controller + 2 template.**
- [ ] **Step 2: Verify** — login admin → GET `/admin/bookings` = 200 list; GET `/admin/bookings/<id đã PAID>` = 200 có payment. Customer truy cập `/admin/bookings` → 403.

### Task P4.6: Refund khi hủy booking đã PAID + verify tổng phase 4

**Files:**
- Review: `BookingService.cancelBooking` và `PaymentRepository.refundByBookingId` đã triển khai ở P3.8/P4.1: CANCELLED + PAID→REFUNDED + notification customer BOOKING_CANCELLED với message hoàn tiền, cùng transaction. Không thêm refund lần hai hoặc đổi notification type.

- [ ] **Step 1: Rà soát refund logic + notify hiện có**; sửa nếu chưa đáp ứng BR-08, không nhân đôi hành vi.
- [ ] **Step 2: Verify — kịch bản**
  - Booking CONFIRMED + PAID → curl POST cancel → SQL: booking CANCELLED + payment REFUNDED; notification customer "hoàn tiền".
  - Booking PENDING (không payment) → cancel → chỉ booking CANCELLED (không đụng payment).
- [ ] **Verify tổng phase 4:** checklist (1) FR-12 mọi bullet có bằng chứng; (2) BR-09; (3) 3 method payment render label tiếng Việt đúng; (4) history payment hiển thị đủ 3 role view. Ghi "Kết quả verify" cuối file.
