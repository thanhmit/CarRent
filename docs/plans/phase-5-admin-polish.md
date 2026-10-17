# CarRent — Phase 5: Admin & Hoàn thiện — Implementation Plan

> **For agentic workers:** Chạy task-by-task. Checkbox `- [ ]` tracking. Giữ checkout/nhánh/thay đổi đang có; không tự stage/commit/push (D-19). Quy ước verify chung ở `phase-0-init.md`.

**Goal:** Admin Dashboard + quản lý User + Profile/đổi mật khẩu + Owner dashboard + rà soát toàn hệ thống — FR-02, FR-03, FR-15.

> **Phạm vi sau bổ sung D-25:** P5.8/P5.9 chốt FR-01–FR-15 và nghiệp vụ Phase 0–5. Module biên bản nhận/trả xe FR-16, ảnh riêng tư, bất đồng và complete/cancel theo chính sách mới thuộc [Phase 6](phase-6-inspection.md); chưa thêm guard/query bảng inspection trong Phase 5. Giữ tiến độ Phase 6 là chưa triển khai khi chốt checkpoint Phase 5.

> **D-24 / SDD v2.1:** DB và seed đã có từ `scriptDB.sql`; Hibernate `ddl-auto=none`, không tạo lại bảng hoặc reset seed khi boot. Dashboard/revenue phải tính cả dữ liệu mẫu và dữ liệu mới; so với SQL hiện tại, không hardcode số lượng. Password mẫu và các trạng thái account xem SDD §7.7.

**Architecture:** `AdminService` gom mọi query thống kê (dashboard + user detail); `ProfileController` + `UserService` xử lý profile và đổi mật khẩu.

**Tech Stack:** Spring Data JPA aggregate queries, Thymeleaf.

**Spec:** `docs/SDD.md` §5 FR-02, FR-03, FR-15, §4.3, §6 BR-01, §10; `docs/decisions.md` D-21.

---

## Global Constraints (tóm tắt)

- Quy ước phase-0. Admin không đổi status của chính mình. User không tự đổi role qua form.
- Doanh thu = SUM(payments.amount) status PAID (BR-08 note trong SDD: chỉ PAID — REFUNDED không cộng).
- Mọi POST CSRF. UI tiếng Việt.
- **UI port từ prototype Stitch** (`docs/design/stitch/`, quy tắc ở `README.md`): admin dùng layout B (sidebar admin + topbar), phần lớn trang theo `admin-quan-ly-nguoi-dung.html` / `admin-duyet-xe.html`; dashboard/profile không có prototype → dựng theo cùng design system.

## Review Focus

1. Admin tự đổi status chính mình → blocked + thông báo — Task 2.
2. Dashboard doanh thu bỏ qua REFUNDED/FAILED — Task 1.
3. Đổi mật khẩu sai current password → lỗi field, không đổi — Task 4.
4. Admin filter user theo role/status + search email → kết quả đúng phân trang — Task 2.
5. User BANNED cố login lại → bị chặn ở login (verify lại toàn flow từ Phase 1).

---

### Task P5.1: AdminService — thống kê dashboard

**Files:**
- Create: `src/main/java/com/carrent/admin/AdminService.java`.

**Interfaces:**
- Produces: `AdminService.getDashboardStats() → DashboardStatsDto` — fields: totalUsers, totalCustomers, totalOwners, totalCars, pendingApprovalCars, totalBookings, pendingBookings, completedBookings, cancelledBookings, totalRevenue (Long), monthlyRevenue (Map<Month, Long> hoặc List<{month, revenue}> của năm hiện tại — chỉ PAID).
- Produces: các count query — userRepository.countByRole(...), carRepository.countByApprovalStatus(PENDING), bookingRepository.countByStatus(...), paymentRepository aggregate:
  - `@Query("select coalesce(sum(p.amount),0) from Payment p where p.status = PAID") Long totalRevenue()`
  - `@Query("select function('date_format', p.paidAt, '%Y-%m') as ym, coalesce(sum(p.amount),0) from Payment p where p.status = PAID and p.paidAt is not null group by function('date_format', p.paidAt, '%Y-%m')") List<Object[]> monthlyRevenue()` (map sang DTO — parse key `2026-10`.)

- [ ] **Step 1: Viết repo queries + DTO + service.**
- [ ] **Step 2: Verify** — compile + boot (template P5.2). SQL đối chiếu tay: `SELECT COUNT(*) FROM users WHERE role='CUSTOMER';` khớp số hiển thị sau P5.2.

### Task P5.2: Template admin dashboard

**Files:**
- Create: `src/main/java/com/carrent/admin/AdminController.java` (nếu chưa — chỉ `GET /admin/dashboard`), `src/main/resources/templates/admin/dashboard.html`.

**Interfaces:**
- Produces: `GET /admin/dashboard` (ADMIN) → model stats → view — **không có prototype → dựng theo design system layout B (sidebar+topbar như `admin-duyet-xe.html`)**: stat cards Tailwind (4-5 card: Users, Cars, Bookings, Doanh thu, chờ duyệt), bảng doanh thu theo tháng (12 tháng năm hiện tại — tháng 0 doanh thu hiển thị 0), format VND `#,###`.

- [ ] **Step 1: Viết controller + template.**
- [ ] **Step 2: Verify** — login admin → GET `/admin/dashboard` = 200 body có "Doanh thu" + số liệu khớp SQL đối chiếu (testcustomer + admin = 2 users, cars từ phase 2...). Anonymous → 302 login; customer → 403.

### Task P5.3: Admin users — list + search + filter + pagination

**Files:**
- Modify: `AdminController.java` (hoặc tạo `AdminUserController` trong package admin), `UserRepository.java`, `UserService.java`.
- Create: `src/main/resources/templates/admin/users.html`.

**Interfaces:**
- Produces: `UserRepository`: `Page<User> findByRoleAndStatus...` — dùng Specification (như cars): tạo `UserSpecifications` (roleEq, statusEq, nameOrEmailContains) hoặc query method đơn giản theo đủ tổ hợp. Khuyến nghị Specification cho DRY.
- Produces: `GET /admin/users` — query `q`, `role`, `status`, `page` (default size 10) → **port từ `docs/design/stitch/admin-quan-ly-nguoi-dung.html` (layout B)**: bảng (fullName, email, phone, role badge, status badge, link detail), filter form + pagination giữ nguyên markup Tailwind.

- [ ] **Step 1: Viết spec + service + controller + template.**
- [ ] **Step 2: Verify** — login admin → GET `/admin/users?q=test` → 200, list lọc đúng. `role=OWNER` → chỉ owner. Anonymous → 302. Customer → 403.

### Task P5.4: Admin user detail + đổi status

**Files:**
- Modify: `AdminController.java`, `AdminService.java`, `UserService.java`.
- Create: `src/main/resources/templates/admin/user-detail.html`.

**Interfaces:**
- Produces: `GET /admin/users/{id}` — model user + stats liên quan (Owner: số xe; Customer: số booking) → view — **port panel chi tiết từ `docs/design/stitch/admin-quan-ly-nguoi-dung.html` (layout B)**: thông tin + form đổi status (3 radio ACTIVE/INACTIVE/BANNED + POST `/admin/users/{id}/status`).
- Produces: `POST /admin/users/{id}/status` — service `changeUserStatus(targetUserId, newStatus, currentAdminId)`:
  - `targetUserId == currentAdminId` → BusinessException "Không thể thay đổi trạng thái của chính mình".
  - set status + save → redirect flash "Đã cập nhật trạng thái tài khoản."
- Produces: `UserService.changeUserStatus`.

- [ ] **Step 1: Viết service + controller + template.**

- [ ] **Step 2: Verify — curl:** admin POST status BANNED cho testcustomer → SQL status BANNED → login testcustomer → 302 `/login?error` (blocked). POST status cho chính admin → flash lỗi, SQL status admin không đổi. Set lại ACTIVE cho testcustomer.

### Task P5.5: Profile view + edit

**Files:**
- Create: `src/main/java/com/carrent/user/ProfileController.java`.
- Create: `src/main/resources/templates/profile/view.html`, `profile/edit.html`, DTO `ProfileForm` (package user).

**Interfaces:**
- Produces: `GET /profile` — principal user → view (fullName, email, phone, role, createdAt). **Không có prototype → dựng theo design system layout A** (fragment P0.3).
- Produces: `GET /profile/edit` → form (fullName `@NotBlank @Size(max=100)`, phone `@Size(max=20)`); `POST /profile/edit` → `UserService.updateProfile(principal.getId(), form)` (email + role KHÔNG đổi — D-20/D-17 SDD) → flash "Đã lưu hồ sơ." → redirect `/profile`.

- [ ] **Step 1: Viết controller + DTO + service + 2 template.**
- [ ] **Step 2: Verify** — login customer → GET `/profile` = 200; POST edit đổi fullName → SQL đổi, email/role giữ nguyên. Lưu ý SecurityContext: sau đổi fullName, navbar hiển thị tên mới khi reload (principal đọc từ DB mỗi request qua CustomUserDetailsService — session cached; chấp nhận hiển thị sai tên đến login lại, hoặc set `sessionAuthenticationStrategy` — không cần, ghi nhận giới hạn).

### Task P5.6: Change password

**Files:**
- Modify: `ProfileController.java`, `UserService.java`.
- Create: `src/main/resources/templates/profile/change-password.html`, DTO `ChangePasswordForm` (currentPassword, newPassword `@Size(min=6)`, confirmPassword).

**Interfaces:**
- Produces: `POST /profile/change-password` — service `changePassword(userId, form)`:
  - verify current: `passwordEncoder.matches(current, user.getPasswordHash())` → sai → controller rejectValue("currentPassword", "Mật khẩu hiện tại không đúng").
  - newPassword == confirmPassword; set hash mới → flash "Đã đổi mật khẩu."
- Sau đổi thành công → redirect `/logout` tùy chọn? KHÔNG — redirect `/profile` + flash; session giữ nguyên (Spring Security re-authenticate mỗi request bằng session — password mới có hiệu lực lần đăng nhập sau; hiện tại session vẫn hợp lệ).

- [ ] **Step 1: Viết form + service + template.**
- [ ] **Step 2: Verify** — POST đúng current → SQL hash prefix $2a$ + khác hash cũ → logout → login password mới OK, password cũ sai. POST sai current → lỗi field, hash không đổi.

### Task P5.7: Owner dashboard

**Files:**
- Create: `src/main/java/com/carrent/owner/OwnerDashboardController.java` (package riêng hoặc car package — theo cấu trúc SDD §9: đặt `com.carrent.owner`), `src/main/resources/templates/owner/dashboard.html`.
- Modify: `BookingSummaryDto`/repo (P3.9) hoặc dùng AdminService-style queries scope theo owner.

**Interfaces:**
- Produces: `GET /owner/dashboard` (OWNER) → stats của owner hiện tại: totalCars, activeCars, pendingApprovalCars, totalBookings (trên xe của owner), pendingBookings, totalRevenue (SUM payments PAID của booking xe owner — paymentRepository join booking/car owner), unreadNotifications count. **Không có prototype → dựng theo design system layout B** (sidebar owner như `owner-quan-ly-xe.html`).
- Produces: queries: `@Query` joins: car count by owner; booking count by car.owner.id; revenue: `select coalesce(sum(p.amount),0) from Payment p join p.booking b where b.car.owner.id = :ownerId and p.status = PAID`.

- [ ] **Step 1: Viết queries + controller + template.**
- [ ] **Step 2: Verify** — login owner (có xe + booking PAID từ phase trước) → GET `/owner/dashboard` = 200, số revenue khớp SQL: `SELECT SUM(amount) FROM payments p JOIN bookings b ON p.booking_id=b.id JOIN cars c ON b.car_id=c.id WHERE c.owner_id=<ownerId> AND p.status='PAID';`. Customer → 403.

### Task P5.8: Rà soát BR toàn hệ thống

- [ ] **Step 1: Ma trận quyền bằng curl** — với 3 phiên (admin/customer/owner cookie jar riêng): đi qua route table SDD §10, từng route → expected code (200/302/403/404). Ghi bảng kết quả vào section "Kết quả verify" cuối file.
- [ ] **Step 2: Business rules spot-check**
  - BR-02: owner A edit xe owner B (URL id đổi) → 403/404.
  - BR-05: overlap curl (như P3.6) — pass.
  - BR-06: POST booking total_amount SQL ≠ giá client gửi (client không gửi giá).
  - BR-08: hủy PENDING/CONFIRMED only.
  - BR-09: payment CONFIRMED only, 1 PAID only.
  - Empty states: owner không xe, customer không booking, notifications trống.
- [ ] **Step 3: Trang chủ hiển thị xe mới nhất** (8 APPROVED+ACTIVE mới nhất) — modify HomeController: model cars + thumbnails (reuse query P2.12) → home hero + grid. Verify GET `/` có xe.

### Task P5.9: E2E cuối + chốt SDD

- [ ] **Step 1: Kịch bản E2E đầy đủ bằng browser** (người duyệt bấm):
  1. Admin login (mật khẩu SQL seed local `CarRentAdmin@2026!`; đổi qua P5.6 trước khi công khai — D-21/D-24).
  2. Register owner mới → đăng xe (2 ảnh) → admin duyệt.
  3. Register customer → search/lọc xe → đặt xe → owner confirm → customer thanh toán (MOCK_CARD).
  4. Customer đặt xe 2 (để hủy) → hủy → owner nhận notification hủy.
  5. Admin dashboard: số liệu tăng đúng; user list + ban 1 tài khoản test → login bị chặn.
  6. Owner complete booking 1 (SQL set end_date quá khứ trước) → customer nhận notification COMPLETED.
- [ ] **Step 2: Rà soát SDD** — FR-01–FR-15 có bằng chứng; cập nhật file `docs/SDD.md` mục 14.1 đánh dấu Phase 0–5 hoàn thành (✅). Chốt checkpoint MVP Phase 0–5, FR-16/Phase 6 vẫn chưa triển khai.

**Hoàn thành Phase 0–5:** FR-01–FR-15 và NFR áp dụng cho phạm vi này được phủ, permission matrix verify, kịch bản E2E pass. CarRent MVP Phase 0–5 sẵn sàng demo/portfolio; module được duyệt thêm sẽ tiếp tục ở Phase 6.
