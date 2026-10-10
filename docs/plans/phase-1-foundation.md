# CarRent — Phase 1: Foundation (Auth) — Implementation Plan

> **For agentic workers:** Chạy task-by-task. Checkbox `- [ ]` để tracking. **Không có bước git commit** (D-19: chưa git) — verify thay thế commit. Quy ước verify chung (compile / boot / curl / SQL / POST-flow CSRF): xem khối **Quy ước verify** ở cuối file `phase-0-init.md`, hoặc SDD §14.3.

**Goal:** Đăng nhập/đăng ký/đăng xuất + phân quyền 3 role trên DB và admin SQL seed sẵn — hoàn chỉnh FR-01.

**Trạng thái:** P1.1–P1.8 đã triển khai và verify ngày 2026-10-10; kết quả ở cuối file.

> **D-24 / SDD v2.1:** DB đã có đủ 7 bảng + seed (`scriptDB.sql`), Hibernate `ddl-auto=none`. Entity map đúng bảng/cột/enum/length/nullability theo SDD §7.2/7.6. Không seed lại từ Java. Verify theo record vừa tạo hoặc số lượng trước/sau; không giả định DB trống. Tài khoản mẫu xem SDD §7.7.

**Architecture:** Spring Security session-based form login; `CustomUserDetailsService` nạp User từ DB và chặn status ≠ ACTIVE; guard role ở `SecurityConfig`, ownership check để Service các phase sau.

**Tech Stack:** Spring Security 7.1.1 theo Spring Boot 4.1.1 hiện tại, BCrypt, Thymeleaf + Tailwind CSS (design system Stitch), Spring Data JPA.

**Spec:** `docs/SDD.md` §4 (phân quyền), §5 FR-01, §6 BR-01, §7.2 (users), §9 (package), §10 (route); `docs/decisions.md` D-01, D-02, D-17, D-20, D-21.

---

## Global Constraints (tóm tắt — bản đầy đủ ở phase-0)

- Gốc dự án `C:\Users\Admin\Documents\hoc\Car`; package `com.carrent`; build `.\mvnw`; port 8080.
- UI tiếng Việt, code tiếng Anh. Tailwind CSS (Play CDN). Không unit test, không git.
- `users.email` là chuỗi định danh unique — **không validate định dạng email** (D-20).
- Mọi form POST phải có CSRF token (Spring Security mặc định bật).
- Lombok: `@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder` cho entity/DTO; riêng `RegisterForm` dùng `@Getter @Setter` và constructor mặc định vì chỉ bind MVC, không có caller dùng builder/constructor đầy đủ.

## Review Focus

1. Đăng nhập với status ≠ ACTIVE → bị từ chối, thông báo tiếng Việt không phân biệt nguyên nhân (an toàn thông tin) — Task 2, 7.
2. Anonymous truy cập `/admin/**` → 302 `/login`; customer đã login truy cập `/admin/**` → trang 403 — Task 3, 7.
3. Đăng ký với email đã tồn tại → form hiện lỗi dưới field, không tạo user — Task 5, 7.
4. Password trong DB luôn là BCrypt (prefix `$2`) — Task 4, 7 (SQL check).
5. Form register thiếu trường → re-render giữ giá trị đã nhập, lỗi tiếng Việt dưới field — Task 5, 7.

---

### Task P1.1: Entity User + enums + UserRepository

**Files:**
- Create: `src/main/java/com/carrent/user/User.java`, `Role.java`, `UserStatus.java`, `UserRepository.java`.

**Interfaces:**
- Produces: `User` (Long id, String fullName, String email, String passwordHash, String phone, String avatarUrl, Role role, UserStatus status, LocalDateTime createdAt/updatedAt) — `@Table(name="users")`, `@Enumerated(EnumType.STRING)` cho role/status; `@PrePersist`/`@PreUpdate` set timestamps; email `@Column(unique=true, nullable=false, length=150)`.
- Produces: `UserRepository extends JpaRepository<User, Long>` với `Optional<User> findByEmail(String email)` và `boolean existsByEmail(String email)`.
- Produces: `Role` = {ADMIN, CUSTOMER, OWNER}; `UserStatus` = {ACTIVE, INACTIVE, BANNED}.

- [x] **Step 1: Viết 3 class + repository** theo SDD §7.2 `users`. Không cần column `updated_at` listener ngoài `@PreUpdate`.

- [x] **Step 2: Verify**

Boot (Hibernate `ddl-auto=none`, entity map bảng `users` có sẵn). SQL:
```powershell
$env:MYSQL_PWD='<pass>'
& "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe" -u root carrent -e "DESCRIBE users;"
```
→ có 10 cột đúng tên kiểu SDD, `email` là `UNI`. Dừng app.

### Task P1.2: CustomUserPrincipal + CustomUserDetailsService

**Files:**
- Create: `src/main/java/com/carrent/security/CustomUserPrincipal.java`, `CustomUserDetailsService.java`.

**Interfaces:**
- Produces: `CustomUserPrincipal implements UserDetails` — constructor `from(User user)`; expose `Long getId()`, `String getEmail()`, `Role getRole()`; `getAuthorities()` → `ROLE_` + role.name(); password = `user.getPasswordHash()`; username = email.
- Produces: `CustomUserDetailsService implements UserDetailsService` — `loadUserByUsername(email)`: `userRepository.findByEmail` → nếu rỗng ném `UsernameNotFoundException`; nếu `status != ACTIVE` ném `DisabledException`; ngược lại trả `CustomUserPrincipal.from(user)`.

- [x] **Step 1: Viết 2 class** như trên (constructor `CustomUserPrincipal(User user)` hoặc static factory `from` — chọn 1, dùng nhất quán ở SecurityConfig Phase sau).

- [x] **Step 2: Verify** — `.\mvnw -q compile` BUILD SUCCESS (chưa có runtime test — Service chưa được security dùng).

### Task P1.3: SecurityConfig + role-based redirect + /access-denied

**Files:**
- Create: `src/main/java/com/carrent/config/SecurityConfig.java`, `src/main/java/com/carrent/security/RoleBasedAuthSuccessHandler.java`.
- Modify: `src/main/java/com/carrent/web/HomeController.java` (hoặc AuthController ở P1.5 — nếu AuthController chưa có thì tạm thêm vào HomeController, P1.5 sẽ dọn): thêm `GET /access-denied` → view `error/403`.

**Interfaces:**
- Produces: `SecurityFilterChain` bean:
  - permitAll: `/`, `/cars`, `/cars/{id}` (chỉ GET; dùng `requestMatchers("/cars/**", "/css/**", "/js/**", "/uploads/**", "/login", "/register", "/access-denied", "/favicon.ico").permitAll()` — `/cars/**` public vì FR-09; chi tiết visibility check ở Service P2.13).
  - `/admin/**` → `hasRole("ADMIN")`; `/owner/**` → `hasRole("OWNER")`; `/bookings/**` → authenticated (service check role hành động — BR-07); `anyRequest().authenticated()`.
  - `formLogin`: loginPage `/login`, successHandler = `RoleBasedAuthSuccessHandler`, failureUrl `/login?error`.
  - `logout`: POST `/logout`, logoutSuccessUrl `/login?logout`.
  - `exceptionHandling().accessDeniedPage("/access-denied")`.
- Produces: `RoleBasedAuthSuccessHandler implements AuthenticationSuccessHandler` — redirect: ADMIN → `/admin/dashboard`, OWNER → `/owner/dashboard`, CUSTOMER → `/cars` (FR-01). Dashboard route chưa tồn tại ở phase này → redirect 404 là chấp nhận được, P5.2/P2.6/P5.7 sẽ dựng trang.
- Produces: `PasswordEncoder` bean = `BCryptPasswordEncoder`.

- [x] **Step 1: Viết SecurityConfig + handler + route access-denied.** Không tắt CSRF (mặc định bật — mọi POST form cần `_csrf`).

- [x] **Step 2: Verify**

Boot → anonymous: `curl.exe -s -o $null -w "%{http_code}" -L http://localhost:8080/admin/dashboard` → kết quả cuối `200` trang login (302 → /login, `-L` theo redirect tới login permitAll). Kiểm không theo redirect: `curl.exe -s -o $null -w "%{http_code}" http://localhost:8080/admin/dashboard` → `302`, header `Location` chứa `/login`. `GET /` vẫn `200` (permitAll).

### Task P1.4: Verify tài khoản admin SQL seed sẵn

**Files:**
- Không tạo class seeder. Dữ liệu đã có trong `scriptDB.sql`.

**Interfaces:**
- Consumes: admin `admin@carrent.local`, role ADMIN, status ACTIVE, password BCrypt. Mật khẩu local ban đầu `CarRentAdmin@2026!` (SDD §7.7); biến `CARRENT_ADMIN_PASSWORD` không thay đổi SQL seed.
- Produces: bằng chứng admin login được và boot không thay record/password.

- [x] **Step 1: Kiểm tra seed admin đã có**; không insert/ghi đè lúc boot. Nếu setup máy mới, chạy `scriptDB.sql` trước khi boot.

- [x] **Step 2: Verify**

Boot → SQL:
```powershell
& "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe" -u root carrent -e "SELECT email, role, status, LEFT(password_hash,4) AS hash_prefix FROM users WHERE email='admin@carrent.local';"
```
→ 1 admin: `admin@carrent.local | ADMIN | ACTIVE | $2a$`. Boot lần 2 → admin vẫn đúng 1 row, hash không đổi; không yêu cầu cả bảng users có 1 row.

### Task P1.5: AuthController + AuthService + RegisterForm

**Files:**
- Create: `src/main/java/com/carrent/auth/AuthController.java`, `AuthService.java`, `RegisterForm.java`.

**Interfaces:**
- Produces: `GET /login` → view `auth/login`; `GET /register` → view `auth/register` + `RegisterForm` rỗng; `POST /register` → validate + register → redirect `/login?registered`.
- Produces: `RegisterForm` fields + validation: `fullName` `@NotBlank @Size(max=100)`; `email` `@NotBlank @Size(max=150)` (KHÔNG pattern email — D-20); `password` `@NotBlank @Size(min=6, max=72)`; `confirmPassword` `@NotBlank`; `phone` `@Size(max=20)` optional; `role` `@NotNull` — chỉ nhận CUSTOMER/OWNER (nếu ADMIN → field error).
- Produces: `AuthService.register(RegisterForm form)` → `User` — check `confirmPassword.equals(password)` (nếu sai: controller `rejectValue("confirmPassword", ...)`) ; check `existsByEmail` → controller `rejectValue("email", "Email đã được sử dụng")`; build User: passwordHash = `passwordEncoder.encode(password)`, status ACTIVE, role từ form. KHÔNG tự login sau register (PRG → login).

- [x] **Step 1: Viết controller + service + DTO.** Controller bind `@Valid RegisterForm form, BindingResult bindingResult` — có lỗi → re-render `auth/register` (giữ giá trị đã nhập); POST mapping annotation `@PostMapping("/register")`.

- [x] **Step 2: Verify** — compile OK; runtime verify gộp ở P1.7 (đợi template).

### Task P1.6: Template login + register + cập nhật navbar

**Files:**
- Create: `src/main/resources/templates/auth/login.html`, `auth/register.html`.
- Modify: `src/main/resources/templates/fragments/header.html` — hiển thị theo trạng thái: anonymous → link "Đăng nhập" + "Đăng ký"; authenticated → dropdown user (sec:authorize) với link "Hồ sơ" (`/profile` — route Phase 5, tạm render link được), "Đăng xuất" (form POST `/logout` với CSRF — không dùng GET), menu theo role: OWNER → "Xe của tôi" (`/owner/cars`, Phase 2), ADMIN → "Dashboard" (`/admin/dashboard`). Link chưa có route vẫn render được (404 khi bấm trước khi phase sau dựng) — ghi nhận, phase sau tự hoàn thiện.

**Interfaces:**
- Produces: `auth/login.html` — form `th:action="@{/login}"` POST, input `name="username"` (label "Email"), `name="password"`, hidden `_csrf`. Alert (theo token màu design system): `param.error` → "Email hoặc mật khẩu không đúng, hoặc tài khoản đã bị khóa."; `param.registered` → "Đăng ký thành công. Hãy đăng nhập."; `param.logout` → "Bạn đã đăng xuất."
- Produces: `auth/register.html` — form POST `/register`, fields per RegisterForm + radio CUSTOMER ("Người thuê xe") / OWNER ("Người cho thuê xe") + confirmPassword; lỗi từng field dưới field (`th:errors`), layout chuẩn P0.3.

- [x] **Step 1: Viết 2 template + sửa header** — **port từ `docs/design/stitch/auth-dang-nhap.html` và `auth-dang-ky.html`**: giữ class Tailwind nguyên văn, thay action/field theo form thật (login: input `name="username"` label "Email", `password`, hidden `_csrf` — Thymeleaf tự chèn qua `th:action`; register: các field theo RegisterForm + radio CUSTOMER/OWNER theo prototype). Dùng `sec:authorize="hasRole('...')"` cho menu theo role (khai `xmlns:sec` + dependency `thymeleaf-extras-springsecurity6`); layout qua fragment `head`/`header` từ P0.3. Navbar update theo SDD §8.5 layout A: link theo role + icon chuông (badge unread Phase 3).

- [x] **Step 2: Verify**

Boot: `GET /login` → 200 body chứa "Đăng nhập"; `GET /register` → 200 chứa "Đăng ký".

### Task P1.7: Verify tổng phase 1 (curl flow đầy đủ)

- [x] **Step 1: Flow đăng ký bằng curl (CSRF + session)**

```powershell
# 1. Lấy trang + CSRF + cookie
$h = curl.exe -s -c jar.txt http://localhost:8080/register
$tok = [regex]::Match($h, 'name="_csrf"[^>]*value="([^"]+)"').Groups[1].Value
# 2. POST register
$r = curl.exe -s -i -b jar.txt -c jar.txt -X POST http://localhost:8080/register `
  -d "fullName=Test Customer&email=testcustomer&password=123456&confirmPassword=123456&phone=&role=CUSTOMER&_csrf=$tok"
```
→ response `302` Location `/login?registered`. SQL check: user `testcustomer` role CUSTOMER status ACTIVE, hash prefix `$2a$`.

- [x] **Step 2: Flow đăng nhập + phân quyền**

```powershell
$h = curl.exe -s -c jar.txt http://localhost:8080/login
$tok = [regex]::Match($h, 'name="_csrf"[^>]*value="([^"]+)"').Groups[1].Value
curl.exe -s -i -b jar.txt -c jar.txt -X POST http://localhost:8080/login `
  -d "username=testcustomer&password=123456&_csrf=$tok"   # → 302, Location=/cars
curl.exe -s -b jar.txt -c jar.txt -o $null -w "%{http_code}" http://localhost:8080/admin/dashboard   # → 200? KHÔNG — trang access-denied (403) vì có session customer
```
Anonymous `/admin/dashboard` → `302` `/login`. Đăng nhập admin (`admin@carrent.local` / `CarRentAdmin@2026!`, hoặc mật khẩu đã đổi qua P5.6) → 302 Location `/admin/dashboard`. Sai mật khẩu → 302 `/login?error`.

- [x] **Step 3: Đăng ký trùng email** — POST register email `testcustomer` lần 2 → response 200 re-render form, body chứa "Email đã được sử dụng", users table vẫn 1 row `testcustomer`.

### Task P1.8: Checkpoint rà soát phase 1

- [x] Đi qua checklist: (1) FR-01 mọi bullet có bằng chứng verify ở P1.7; (2) navbar đúng theo trạng thái login (mở `/` bằng browser sau khi đăng nhập — kiểm tay 1 lần); (3) không route nào quên guard (`/profile`, `/notifications` đã authenticated qua `anyRequest`); (4) SQL: 7 users mẫu còn nguyên; user mới tăng đúng số lượng, không trùng định danh và hash BCrypt. Ghi kết quả vào file `docs/plans/phase-1-foundation.md` cuối trang (thêm section "Kết quả verify" — người chạy tự điền).

## Kết quả verify — 2026-10-10

P1.1–P1.8 đã triển khai. Không thêm dependency, Java unit test, seeder Java hoặc đổi schema; `scriptDB.sql` giữ nguyên.

| Kiểm tra | Kết quả thực tế |
|---|---|
| `.\mvnw.cmd -q -DskipTests compile` | Exit 0 |
| Boot với MySQL và `ddl-auto=none` | `Started CarRentApplication`; app trên http://localhost:8080 |
| Mapping `User` | Hibernate `validate` đã đạt; `DESCRIBE users` đúng 10 cột, email unique |
| Admin seed qua nhiều lần boot | Một ACTIVE ADMIN; login redirect `/admin/dashboard`; hash và mọi field của 7 seed không đổi |
| Đăng ký CUSTOMER/OWNER | 302 `/login?registered`; không tự login; ACTIVE, hash BCrypt |
| Hai request cùng định danh | Đúng một 302 và một 200 lỗi email; MySQL UNIQUE 1062 được chuyển thành field error, không 500 |
| Định danh trùng/khác hoa thường/có khoảng trắng | 200, “Email đã được sử dụng”; login trim và không yêu cầu dấu @ |
| Form sai | Chặn trống, role ADMIN/UNKNOWN, xác nhận khác, password ngắn/quá 72 byte UTF-8, tên/email/phone quá dài |
| Re-render lỗi | Giữ tên/định danh/phone/role; password và confirmPassword luôn trống |
| Redirect/saved request | ADMIN → admin dashboard; OWNER → owner dashboard; CUSTOMER → cars; saved URL không ghi đè |
| Phân quyền | Anonymous trang bảo vệ 302 login; đã login sai role 403 với trang tiếng Việt |
| Đăng nhập lỗi | Sai mật khẩu, user không có, INACTIVE/BANNED, password quá dài → cùng `/login?error`, không 500 |
| CSRF/logout | Thiếu/sai token 403; POST logout hợp lệ 302 `/login?logout`, session hết; GET không logout |
| Navbar/dialect | CUSTOMER/OWNER/ADMIN có đúng menu; `thymeleaf-extras-springsecurity6` hoạt động với Security 7.1.1 |
| Chromium UI | Desktop 1440px, mobile 320px/390px; không overflow navbar/menu; focus bàn phím rõ và dropdown thao tác bằng Enter/Tab |
| Browser form/ảnh | Blank/short submit có lỗi tiếng Việt dưới field; toggle password cập nhật accessible name; mỗi trang một banner; ảnh auth tải được |

**SQL sau verify:** users 9 (7 seed + `p1_0683edc2_customer`, `p1_0683edc2_owner`), car_categories 6, cars 6, car_images 6, bookings 6, payments 6, notifications 10. Hai user test được giữ lại; không xóa lịch sử. SHA256 toàn bộ field/hash/timestamp của seed users bằng snapshot trước triển khai.

**Bằng chứng và chạy lại:**

- `.scratch/phase1/verify-auth.py`: HTTP/session/CSRF assertions; `--smoke` chỉ đọc, full mode tạo hai định danh test mới. Đã chạy full đăng ký/race; sau khi sửa kiểm tra HTML entity, các bước còn lại chạy lại trên cùng hai user, tránh thêm record.
- `.scratch/phase1/auth-results.json`: hai định danh và PASS; `verify-db.py` đối chiếu SQL thật, cần đặt `MYSQL_PWD`. Nếu chạy lại full mode, chụp COUNT(users) mới vào `users-before-count.txt` trước khi chạy; `seed-users-before.tsv` là snapshot 7 fixture gốc.
- `.scratch/phase1/app*.log`: nhiều lần boot; lỗi duplicate 1062 là race được xử lý. Security ghi lỗi nội bộ khi service ném DisabledException cho INACTIVE/BANNED theo contract P1.2; HTTP vẫn trả generic login error đúng yêu cầu.
- Ảnh browser: `login-desktop.png`, `login-mobile.png`, `register-desktop.png`, `register-mobile.png`, `customer-mobile-menu.png` trong `.scratch/phase1/`.
- Task reports/reviews tại `.superpowers/sdd/phase-1-foundation/`. Giữ ledger vì dự án không dùng git.

**Các sửa sau review/browser:** registration form dùng `novalidate` để server render lỗi tiếng Việt; compact auth header dùng banner div để không bị `:: header` chọn hai lần; khôi phục đầy đủ URL ảnh đăng ký từ prototype. Cả ba có scoped review và kiểm tra trực tiếp.

**Final review:** `.superpowers/sdd/phase-1-foundation/final-review.md` đã duyệt checkpoint local: spec PASS, quality/security PASS, READY; không còn finding chưa xử lý.

**Quyết định đã chốt:** redirect cố định theo role được ưu tiên hơn saved URL (không quay lại deep link sau login); RegisterForm chỉ sinh getter/setter và constructor mặc định, builder/constructor đầy đủ sẽ bổ sung khi có caller cần.

**Giới hạn checkpoint:** Không triển khai Phase 2–5. `/cars`, `/owner/dashboard`, `/admin/dashboard`, `/profile`, `/notifications` có thể 404 dù link/redirect/guard đã đúng. OAuth, remember-me, quên mật khẩu và workflow điều khoản không thuộc Phase 1. Tailwind/font/ảnh demo dùng nguồn bên ngoài theo prototype; chưa kiểm tra khi offline. Favicon chưa có từ Phase 0 nên có thể 404.
