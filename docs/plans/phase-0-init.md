# CarRent — Phase 0: Init — Implementation Plan

> **For agentic workers:** Plan được thiết kế để chạy task-by-task bằng AgentCode. Các bước dùng checkbox (`- [x]`) để tracking. **Không có bước git commit** (D-19: dự án chưa dùng git) — mỗi bước verify thay thế commit.

**Goal:** Khởi tạo skeleton Spring Boot + database `carrent` + layout Thymeleaf chung + trang lỗi, đạt chuẩn "compile → boot → GET / = 200".

**Architecture:** Monolith MVC 3-layer theo SDD §3; phase này chỉ dựng vỏ: scaffold, cấu hình, fragments, trang home/error.

**Tech Stack:** Java 21, **Spring Boot 4.1.x** (mặc định mới của start.spring.io — khác dòng 3.x ở artifact `spring-boot-starter-webmvc` thay cho `spring-boot-starter-web`; API MVC/Security/JPA/Thymeleaf tương thích Boot 3, các task sau không cần đổi), Thymeleaf, Spring Security, Spring Data JPA, MySQL 8, Maven Wrapper, Lombok, Tailwind CSS Play CDN + design tokens MD3 từ prototype Stitch (D-18/D-23).

**Spec:** `C:\Users\Admin\Documents\hoc\Car\docs\SDD.md` (đặc biệt §2, §8.2, §14.3) và `docs\decisions.md`.

---

## Global Constraints (áp dụng mọi task)

- Thư mục gốc dự án: `C:\Users\Admin\Documents\hoc\Car` — code Spring Boot nằm ngay gốc (cùng cấp `docs\`).
- Java 21; package gốc `com.carrent`; Maven Wrapper `.\mvnw` (không có `mvn` toàn máy).
- MySQL 8 local, service `MySQL80`, database `carrent` utf8mb4; user `root`, mật khẩu người dùng cung cấp khi chạy.
- **D-24 (2026-10-10):** `spring.jpa.hibernate.ddl-auto=none`; `scriptDB.sql` là nguồn chân lý schema. 7 bảng + seed đã provision trước Phase 1; entity chỉ map bảng sẵn có (SDD v2.1 §7.6).
- UI tiếng Việt, code + comment tiếng Anh (D-17). CSS: **Tailwind CSS Play CDN + design tokens Material Design 3 từ prototype Stitch** (D-18/D-23) — mọi trang port từ `docs/design/stitch/*.html`.
- Port giao diện: đọc `docs/design/stitch/README.md` (design system + quy tắc port) và file prototype tương ứng của từng trang — **giữ nguyên class Tailwind**, thay tĩnh bằng Thymeleaf.
- Port 8080. Không git, không unit test (verify theo SDD §14.3).

## Quy ước verify dùng chung mọi phase

```powershell
# (1) Compile
.\mvnw -q compile        # → BUILD SUCCESS

# (2) Boot — chạy trong terminal nền:
.\mvnw spring-boot:run
# Chờ sẵn sàng — poll đến khi trả 200/302 (tối đa 90 giây):
do { Start-Sleep 2; $code = try { (curl.exe -s -o $null -w "%{http_code}" http://localhost:8080/) } catch { "000" } } until ($code -ne "000" -and $code -ne "")
# Log phải chứa "Started CarRentApplication", không có Exception/ERROR.

# (3) Dừng app (khi hết cần):
Stop-Process -Id (Get-NetTCPConnection -LocalPort 8080 -State Listen).OwningProcess -Force

# (4) SQL — đặt 1 lần mỗi phiên shell, mật khẩu do người dùng cung cấp:
$env:MYSQL_PWD = '<root password>'
& "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe" -u root -e "<query>"
```

POST flow cần CSRF + session (dùng từ Phase 1 trở đi):

```powershell
# Lấy token từ trang chứa form (th:action tự chèn hidden _csrf):
$h = curl.exe -s http://localhost:8080/login
$tok = [regex]::Match($h, 'name="_csrf"[^>]*value="([^"]+)"').Groups[1].Value
# POST kèm cookie jar:
curl.exe -s -i -c jar.txt -X POST http://localhost:8080/login -d "username=<email>&password=<pass>&_csrf=$tok"
# Các POST sau: dùng lại -b jar.txt -c jar.txt, token lấy từ trang form của chính action đó.
```

## Review Focus (hữu ích nhất phase này)

1. App không boot được khi MySQL không chạy → log lỗi kết nối rõ ràng (không treo vô hạn) — verify ở Task 4.
2. Route không tồn tại → trang 404 tiếng Việt, không whitelabel — Task 4.
3. Lỗi không xác định → trang lỗi chung, không lộ stack trace — Task 4.
4. `custom.css` được serve (`/css/custom.css` = 200) — Task 3.
5. Trạng thái boot phải đợi "Started CarRentApplication" mới verify HTTP (đảm bảo poll) — quy ước trên.

---

### Task P0.1: Scaffold Spring Boot + application.properties

**Files:**
- Create: `pom.xml`, `mvnw`, `mvnw.cmd`, `.mvn/`, `src/main/java/com/carrent/CarRentApplication.java`, `src/main/resources/application.properties`, `src/main/resources/templates/` (từ starter zip).
- Create: `src/main/resources/static/css/custom.css`.
- Reference: `docs/design/stitch/README.md` (design system + quy tắc port).

**Interfaces:**
- Produces: project Maven buildable bằng `.\mvnw`; dependency set: web, thymeleaf, security, data-jpa, validation, mysql-connector, lombok.

- [x] **Step 1: Tải và giải nén starter**

```powershell
Set-Location C:\Users\Admin\Documents\hoc\Car
curl.exe -L -o starter.zip "https://start.spring.io/starter.zip?type=maven-project&language=java&groupId=com.carrent&artifactId=carrent&name=carrent&packageName=com.carrent&javaVersion=21&dependencies=web,thymeleaf,security,data-jpa,validation,mysql,lombok"
Expand-Archive starter.zip -DestinationPath . -Force
Remove-Item starter.zip
```

starter zip chứa `pom.xml`, `mvnw*`, `.mvn/`, `src/...` ngay gốc — giải nén thẳng vào thư mục hiện tại là đúng (không tạo subfolder `carrent/`).

- [x] **Step 2: Viết `src/main/resources/application.properties`**

```properties
spring.application.name=carrent
spring.config.import=optional:file:./application-local.properties
server.port=8080
spring.datasource.url=jdbc:mysql://localhost:3306/carrent?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Ho_Chi_Minh
spring.datasource.username=root
# Mật khẩu thật đặt trong application-local.properties (Git bỏ qua) hoặc biến môi trường.
spring.datasource.password=${CARRENT_DB_PASSWORD:}
spring.jpa.hibernate.ddl-auto=none
spring.jpa.open-in-view=true
spring.servlet.multipart.max-file-size=5MB
spring.servlet.multipart.max-request-size=30MB
```

- [x] **Step 3: Tạo `static/css/custom.css` tối giản**

Chỉ tinh chỉnh nhỏ: footer sticky-bottom, card hover shadow, badge trạng thái. Không chứa layout logic (Tailwind đảm nhiệm).

- [x] **Step 4: Verify compile**

Run: `.\mvnw -q compile` → BUILD SUCCESS, không lỗi dependency.

### Task P0.2: Tạo database `carrent`

**Files:** không tạo file code (thao tác DB).

**Interfaces:**
- Produces: database `carrent` (utf8mb4) với 7 bảng và seed từ `scriptDB.sql` (D-24); Hibernate các phase sau không tự tạo bảng.

- [x] **Step 1: Tạo database**

```powershell
$env:MYSQL_PWD = '<root password>'
& "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe" --protocol=TCP -h localhost -P 3306 -u root --default-character-set=utf8mb4 --execute="source C:/Users/Admin/Documents/hoc/Car/scriptDB.sql"
```

- [x] **Step 2: Verify**

```powershell
& "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe" -u root -e "SHOW DATABASES LIKE 'carrent';"
```
→ 1 row. Verify kết nối từ app: chạy boot (quy ước §2) → log không có "CommunicationsException"/"Access denied".

### Task P0.3: Fragments + trang home (port từ prototype Stitch)

**Files:**
- Create: `src/main/java/com/carrent/web/HomeController.java` (tạm đặt ở package `web`; Phase 1+ thêm controller riêng theo module).
- Create: `src/main/resources/templates/fragments/header.html`, `footer.html`, `messages.html`; `src/main/resources/templates/fragments/head.html` (khối `<head>` chung: meta + Google Fonts Inter + Material Symbols + Tailwind CDN + `tailwind.config` tokens + custom.css).
- Create: `src/main/resources/templates/home/index.html` — **port từ `docs/design/stitch/home-trang-chu.html`**: hero + search widget (địa điểm + ngày nhận/trả + nút "Tìm xe ngay") + pill gợi ý + section "Danh sách xe" (grid card xe) + footer.

**Interfaces:**
- Produces: `fragments/head.html` (fragment `head(title)` — dùng ở MỌI trang: `<head th:replace="~{fragments/head :: head(title='...')}"/>`, giữ đúng thứ tự: meta → Inter → Material Symbols → tailwind CDN → tailwind.config → style font-family/material-symbols).
- Produces: `fragments/header.html` (`header`, layout A theo prototype: navbar `fixed h-16` + logo CarRent + nav Trang chủ/Tìm xe/... + nút Tài khoản; Phase 1 sẽ thêm sec:authorize, phase này để tĩnh).
- Produces: `fragments/footer.html` (`footer`), `fragments/messages.html` (`messages` — flash `successMessage`/`errorMessage` dạng alert theo token màu `secondary-fixed`/`error-container`).
- Produces: `HomeController.GET /` → view `home/index` (model `cars` rỗng/`List.of()` phase này; P5.8 đổ dữ liệu thật).

- [x] **Step 1: Tạo `fragments/head.html`** — copy nguyên văn block head của `home-trang-chu.html` (đổi title thành tham số fragment). Không đổi một token nào trong `tailwind.config`.

- [x] **Step 2: Tạo header/footer/messages** — copy markup header + footer từ `home-trang-chu.html`; messages dựng mới theo token màu (chưa có trong prototype).

- [x] **Step 3: Tạo `home/index.html`** — copy toàn bộ `<main>` của prototype; thay: link `href="#"` → `th:href="@{/cars}"` (nút Tìm xe ngay → form GET `/cars` với các input name `province,startDate,endDate` khớp CarSearchCriteria), khối card xe lặp → `th:each` trên `cars` (Phase này list rỗng → hiển thị empty state "Chưa có xe nào" — thêm `th:if="${#lists.isEmpty(cars)}"`), ảnh → placeholder.

- [x] **Step 4: Verify**

Boot (quy ước §2) → `curl.exe -s -o $null -w "%{http_code}" http://localhost:8080/` → `200`. Body chứa "Tìm chiếc xe phù hợp" (hero) + "CarRent" + "Tìm xe ngay". `curl.exe -s -o $null -w "%{http_code}" http://localhost:8080/css/custom.css` → `200`. Mở browser 1 lần: layout đúng như screenshot `home-trang-chu.png` (font Inter, màu navy `#001632`, nút xanh `#0051d5`).

### Task P0.4: Trang lỗi + exception handler

**Files:**
- Create: `src/main/java/com/carrent/common/exception/NotFoundException.java`, `ForbiddenException.java`, `BusinessException.java` (các class extends RuntimeException, trường `message` đủ dùng).
- Create: `src/main/java/com/carrent/common/exception/CommonExceptionHandler.java` (`@RestControllerAdvice`? — KHÔNG: dùng `@ControllerAdvice` vì phải trả view).
- Create: `src/main/resources/templates/error/403.html`, `404.html`, `error.html`.

**Interfaces:**
- Produces (dùng ở mọi phase sau):
  - `NotFoundException(String message)` → render `error/404.html`, HTTP 404.
  - `ForbiddenException(String message)` → `error/403.html`, HTTP 403.
  - `BusinessException(String message)` → flash attribute `errorMessage` + redirect về referer (fallback `/`).
  - `Exception` (fallback) → `error/error.html`, HTTP 500, log full stack bằng logger, không lộ stack cho view.

- [x] **Step 1: Viết 3 exception class**

Chỉ cần constructor `public XxxException(String message) { super(message); }`.

- [x] **Step 2: Viết `CommonExceptionHandler`**

```java
@ControllerAdvice
public class CommonExceptionHandler {
    // @ExceptionHandler(NotFoundException.class) → return "error/404" (Model msg, HttpStatus.NOT_FOUND)
    // @ExceptionHandler(ForbiddenException.class) → "error/403" (HttpStatus.FORBIDDEN)
    // @ExceptionHandler(BusinessException.class) → RedirectAttributes.addFlashAttribute("errorMessage", ex.getMessage())
    //     return "redirect:" + (referer != null ? referer : "/")
    // @ExceptionHandler(Exception.class) → log.error("Unhandled", ex); return "error/500 view error/error" (HttpStatus.INTERNAL_SERVER_ERROR)
}
```

Lưu ý: khi later phase thêm `MethodArgumentNotValidException` (validation form) thì KHÔNG xử lý ở đây — controller tự re-render form.

- [x] **Step 3: Viết 3 template lỗi**

Dựng theo design system Stitch (fragment `head` + layout A), icon Material Symbols + thông điệp tiếng Việt: 404 "Không tìm thấy trang bạn yêu cầu."; 403 "Bạn không có quyền truy cập trang này."; error "Đã xảy ra lỗi không mong muốn. Vui lòng thử lại sau." Nút "Về trang chủ" `th:href="@{/}"` (style nút CTA theo token `secondary`).

- [x] **Step 4: Verify**

Boot → `curl.exe -s -o $null -w "%{http_code}" http://localhost:8080/khong-ton-tai` → `404`, body chứa "Không tìm thấy". (403/500 sẽ verify được từ Phase 1 khi có security + route thật.)

### Task P0.5: Verify tổng phase 0

- [x] **Step 1: Chuỗi verify đầy đủ**

1. `.\mvnw -q clean compile` → BUILD SUCCESS.
2. Boot → log "Started CarRentApplication", không ERROR.
3. `GET /` = 200; `GET /css/custom.css` = 200; `GET /khong-ton-tai` = 404 (trang tiếng Việt).
4. Dừng app (quy ước §3). Chạy lại boot 1 lần nữa (khởi động sạch) → vẫn OK.

- [x] **Step 2: Đánh dấu hoàn thành Phase 0**

Chốt: mọi checkbox trên đây tick; gửi kết quả verify (mã HTTP + log) cho người duyệt.
