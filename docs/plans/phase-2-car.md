# CarRent — Phase 2: Car Management — Implementation Plan

> **For agentic workers:** Chạy task-by-task. Checkbox `- [ ]` tracking. Repo đã được chủ dự án khởi tạo Git; hiện làm trên nhánh `feat/phase2-car`, không tự stage/commit/push. Quy ước verify chung ở `phase-0-init.md` (compile/boot/curl/SQL/POST-flow CSRF).

**Trạng thái:** Hoàn tất P2.1–P2.14; review từng task và review cuối Approved, kiểm tra app 8080/HTTP/browser đã đạt. Tiếp theo Phase 3.

**Goal:** Owner CRUD xe + upload ảnh; Admin duyệt/từ chối/ẩn xe; public search/filter/sort/phân trang/detail — FR-04→FR-09.

> **D-24 / SDD v2.2:** DB và dữ liệu mẫu đã có từ `scriptDB.sql`; Hibernate `ddl-auto=none`, entity map đúng §7.2/7.6, không tạo bảng hoặc seed Java. Các ví dụ “1 row” bên dưới áp dụng cho record mới của kịch bản, không phải tổng cả bảng; luôn lọc theo ID/owner của test hoặc so số lượng trước/sau. Mỗi xe mẫu có file placeholder riêng `uploads/seed-car-<id>.png`.

**Architecture:** `CarService` giữ mọi business rule (ownership, PENDING, xóa); `StorageService` lo upload; `CarSpecifications` dựng lọc động cho Pageable.

**Tech Stack:** Spring Data JPA Specification, MultipartFile, Thymeleaf + Tailwind CSS (design system Stitch).

**Spec:** `docs/SDD.md` §5 FR-04→FR-09, §6 BR-02/BR-03, §7.2 (cars/car_images/car_categories), §10 route, §8.2 templates; `docs/decisions.md` D-03, D-08, D-10, D-11, D-16.

---

## Global Constraints (tóm tắt)

- Quy ước phase-0 (gốc, mvnw, UI tiếng Việt/code Anh, Tailwind Play CDN, không Java unit test). Kiểm chứng bằng compile/boot/HTTP/JPA/SQL; bằng chứng local trong `.scratch/` và `.superpowers/` không được đưa vào Git.
- Notification cho sự kiện xe nằm ở **Phase 3** (P3.3) — task admin duyệt/từ chối ở phase này KHÔNG tạo notification.
- Thumbnail = ảnh có id nhỏ nhất của xe (D-10). Ảnh lưu `/uploads/<uuid>.<ext>`; thư mục vật lý `uploads/` ở gốc dự án.
- Car mới tạo: `approval_status=PENDING`, `listing_status=ACTIVE`.
- Mọi POST form cần CSRF.
- **UI port từ prototype Stitch** (`docs/design/stitch/`, design system + quy tắc ở `README.md`): giữ class Tailwind, layout A (public) / B (owner-admin). Không tự đổi màu/font.

## Review Focus

1. Owner A truy cập `/owner/cars/{id}/edit` xe của Owner B → 403/404, không render form — Task 5, 14.
2. Upload file `.txt` hoặc file 6MB → từ chối, form re-render lỗi tiếng Việt, không file rác — Task 3, 7.
3. Xe REJECTED/INACTIVE/PENDING không xuất hiện ở `/cars` public — Task 11, 12.
4. Min/max price đảo ngược (min=2tr max=1tr) → không exception, trả empty page hợp lệ — Task 11.
5. Trang `/cars` trống khi chưa có xe nào → empty state tiếng Việt, không lỗi template — Task 12.

---

### Task P2.1: CarCategory entity + repo + verify SQL seed

**Files:**
- Create: `src/main/java/com/carrent/car/CarCategory.java`, `CarCategoryRepository.java`.
- Không tạo/sửa DataSeeder; category đã được SQL seed trước Phase 1 (D-24).

**Interfaces:**
- Produces: `CarCategory` (Long id, String name unique ≤50, String description ≤255, LocalDateTime createdAt) — `@Table(name="car_categories")`.
- Produces: `CarCategoryRepository extends JpaRepository<CarCategory, Long>`: `List<CarCategory> findAllByOrderByIdAsc()`.

- [x] **Step 1: Viết entity + repo.**
- [x] **Step 2: Verify 6 category đã seed** (SEDAN, SUV, MPV, LUXURY, ECONOMY, ELECTRIC, description tiếng Việt 1 câu). Không insert category lúc boot; setup DB mới dùng `scriptDB.sql`.

- [x] **Step 3: Verify** — boot → SQL: `SELECT COUNT(*) FROM car_categories;` → 6. Boot lại → vẫn 6.

### Task P2.2: Car + CarImage entity + enums + repos

**Files:**
- Create: `src/main/java/com/carrent/car/Car.java`, `CarImage.java`, `ApprovalStatus.java`, `ListingStatus.java`, `Transmission.java`, `FuelType.java`, `CarRepository.java`, `CarImageRepository.java`.

**Interfaces:**
- Produces: `Car` per SDD §7.2 cars: `@ManyToOne` User owner (LAZY), `@ManyToOne` CarCategory category (LAZY), các field enum `@Enumerated(EnumType.STRING)`. `rejectionReason` nullable. Timestamps `@PrePersist/@PreUpdate`.
- Produces: `CarImage` (Long id, `@ManyToOne Car` — cascade KHÔNG đặt, ta quản lý thủ công; String imageUrl; createdAt) — `@Table(name="car_images")`.
- Produces: `CarRepository extends JpaRepository<Car, Long>` — chỉ khai báo method cơ bản + `Page<Car> findByOwnerId(Long ownerId, Pageable pageable)`. Method lọc động dùng Specification (P2.11).
- Produces: `CarImageRepository`: `List<CarImage> findByCarIdOrderByIdAsc(Long carId)`, `Optional<CarImage> findByIdAndCarId(Long imageId, Long carId)`, `boolean existsByCarId(Long carId)`.

- [x] **Step 1: Viết entities + enums + repos.**
- [x] **Step 2: Verify** — boot → `DESCRIBE cars;` + `DESCRIBE car_images;` có đủ cột + FK đúng SDD.

### Task P2.3: StorageService + WebConfig + multipart

**Files:**
- Create: `src/main/java/com/carrent/storage/StorageService.java`, `src/main/java/com/carrent/config/WebConfig.java`.

**Interfaces:**
- Produces: `StorageService.store(MultipartFile file) → String` (web path `/uploads/<uuid>.<ext>`):
  - Validate: file != null && !file.isEmpty(); ext ∈ {jpg, jpeg, png, webp} (lấy từ originalFilename — chỉ để chọn đuôi, KHÔNG tin nội dung tên); content-type bắt đầu `image/`; size ≤ 5MB.
  - Sai validate → ném `BusinessException` với message tiếng Việt tương ứng ("Định dạng ảnh không được hỗ trợ", "Ảnh vượt quá 5MB", ...).
  - Tạo thư mục `uploads/` nếu chưa có; tên file = `UUID.randomUUID()` + "." + ext; lưu file; trả `/uploads/<name>`.
- Produces: `WebConfig implements WebMvcConfigurer` — `addResourceHandlers`: map `/uploads/**` → `file:uploads/` (đường dẫn tương đối gốc dự án; dùng `Paths.get("uploads").toAbsolutePath().toUri()`).
- Thêm `validate(MultipartFile)` để kiểm tra cả batch trước khi ghi; `delete(String webPath)` chỉ xóa file trực tiếp trong thư mục uploads, chặn traversal. Đường dẫn resource phải có dấu `/` cuối. IO lỗi khi lưu phải dọn file ghi dở; lỗi dọn file phải được log.

- [x] **Step 1: Viết 2 class.** Lưu ý `spring.servlet.multipart.max-file-size=5MB` đã set ở P0.1.

- [x] **Step 2: Verify** — compile + boot; sáu ảnh seed HTTP 200 đúng bytes, ảnh không tồn tại HTTP 404, validation và dọn file ghi dở đạt.

### Task P2.4: CarService.create

**Files:**
- Create: `src/main/java/com/carrent/car/CarService.java`, `CarForm.java`.

**Interfaces:**
- Produces: `CarForm` (DTO form + validation): brand `@NotBlank @Size(max=50)`; model `@NotBlank @Size(max=50)`; manufactureYear `@NotNull @Min(1990)` và `@AssertTrue isManufactureYearWithinLimit()` kiểm tra động năm hiện tại + 1; licensePlate `@Size(max=20)`; color `@Size(max=30)`; seatCount `@NotNull @Min(2) @Max(15)`; transmission `@NotNull` enum; fuelType `@NotNull` enum; description `@Size(max=2000)`; pricePerDay Long `@NotNull @Min(1)` (VND); categoryId `@NotNull`; province `@NotBlank @Size(max=100)`; district `@Size(max=100)`; addressDetail `@Size(max=255)`.
- Produces: `CarService.createCar(CarForm form, List<MultipartFile> images, CustomUserPrincipal principal) → Car`:
  - Kiểm tra principal OWNER; lấy User theo principal.getId() và xác nhận role OWNER, status ACTIVE từ DB. Owner không lấy từ form.
  - Validate DTO ở service, category phải tồn tại, trim chuỗi và validate toàn bộ ảnh không rỗng trước khi ghi. Không có ảnh vẫn hợp lệ.
  - `@Transactional`: lưu Car PENDING/ACTIVE và các CarImage cùng giao dịch. Theo dõi đường dẫn mới và đăng ký transaction synchronization dọn file khi rollback, kể cả rollback ở caller hoặc lỗi commit. Commit giữ ảnh.
  - **Điều chỉnh theo phân tích trước implement:** ảnh lỗi phải rollback cả xe và ảnh mới; thay lựa chọn lưu xe một phần. Lỗi tạo xe yêu cầu thử lại. Lỗi IO dọn file được log; không thể bảo đảm dọn file khi quyền filesystem bị thay đổi.

- [x] **Step 1: Viết CarForm + CarService.createCar.**
- [x] **Step 2: Verify** — compile + self-check thật MySQL/JPA/filesystem: validation, quyền Owner, tạo không/có ảnh, rollback DB và file khi ảnh lỗi hoặc caller rollback. HTTP form kiểm tra ở P2.7; lỗi `manufactureYearWithinLimit` phải hiển thị cạnh field năm. Commit giữ ảnh/commit lỗi chưa được probe bằng commit thật.

### Task P2.5: CarService update/deactivate/activate

**Files:**
- Modify: `src/main/java/com/carrent/car/CarService.java`.

**Interfaces:**
- Produces: `CarService.updateCar(Long carId, CarForm form, CustomUserPrincipal principal) → Car` — load car, check ownership (BR-02: sai → `ForbiddenException("Bạn không có quyền với xe này")`), cập nhật field từ form, KHÔNG đổi approval_status (D-03), save.
- Produces: `deactivateCar(carId, principal)` / `activateCar(carId, principal)` — ownership check + đổi listing_status; activate yêu cầu `approval_status=APPROVED` (nếu còn PENDING/REJECTED → BusinessException "Xe chưa được duyệt, không thể đăng lại").
- (Xóa cứng xe dời sang P3.12 — cần BookingRepository.)
- Mutator kiểm tra principal OWNER và User OWNER/ACTIVE từ DB như create; update validate DTO/category, trim chuỗi, giữ nguyên owner/approval/rejectionReason/listing. Probe ghi thử dùng transaction rollback; HTTP form ở P2.8.

- [x] **Step 1: Viết 3 method.**
- [x] **Step 2: Verify** — compile và probe MySQL/Spring transaction rollback cho update/ẩn/đăng lại, cross-owner/stale principal, validation và giữ nguyên trạng thái duyệt; 121 assertions, đọc lại trạng thái từ SQL sau flush/clear. HTTP form kiểm tra ở P2.8.

### Task P2.6: OwnerCarController — danh sách xe của tôi

**Files:**
- Create: `src/main/java/com/carrent/car/OwnerCarController.java`.
- Create: `src/main/resources/templates/owner/cars.html`.

**Interfaces:**
- Produces: `@Controller @RequestMapping("/owner/cars")` — `GET /owner/cars` → `carService.findCarsByOwner(principal.getId(), pageable)` → model `cars`, `page` → view `owner/cars`.
- Produces: template `owner/cars.html` — **port từ `docs/design/stitch/owner-quan-ly-xe.html` (layout B: sidebar owner + stat cards + bảng xe)**: giữ class Tailwind; đổi: stat cards lấy số thật, mỗi row `th:each` trên `cars` — thumbnail = ảnh id nhỏ nhất (server truyền map `carId → imageUrl` qua model attribute `thumbnails`), brand+model, giá/ngày (format `#,###` VND), badge approval (PENDING vàng/ APPROVED xanh/REJECTED đỏ + tooltip rejectionReason), badge listing (ACTIVE/INACTIVE), nút Sửa + Tạo xe. Empty state. Pagination component dùng chung (dùng block fragment `fragments/pagination.html` — tạo ở task này, dùng `page` object + url giữ query param).

**Interfaces thêm (cho template):**
- `CarService.findCarsByOwner(Long ownerId, Pageable) → Page<Car>` (wrap `findByOwnerId`).
- Create: `src/main/resources/templates/fragments/pagination.html` — fragment `pagination(page, baseUrl)` tái dùng mọi list view.
- Stat cards: tổng xe của Owner, xe APPROVED+ACTIVE đang công khai, xe PENDING chờ duyệt; số thật toàn bộ dataset, không phụ thuộc page. Giữ card design, thay nhãn booking giả của prototype. Thêm count repository/service và query batched ảnh ID nhỏ nhất ngay task này để P2.12 dùng lại.
- List mặc định page 0/size 10, size 1–50, sort ID giảm dần; pagination window giới hạn, giữ query param và tránh lỗi offset/trang vượt cuối. Giao diện responsive, sidebar đúng Owner; logout POST CSRF. Không dựng search/filter/modal tĩnh chưa có hành vi.

- [x] **Step 1: Viết controller + service method + 2 template.**
- [x] **Step 2: Verify** — boot; seed Owner → `GET /owner/cars` = 200 body "Xe của tôi". CUSTOMER/ADMIN → 403; anonymous → 302 /login. Hai Owner đúng dữ liệu riêng, stats toàn bộ dataset, thumbnail ID nhỏ nhất, empty state, page/size và query params; desktop 1440px/mobile 390px đạt.

### Task P2.7: Form tạo xe (multipart)

**Files:**
- Create: `src/main/resources/templates/owner/car-form.html`.
- Modify: `OwnerCarController` — thêm `GET/POST /owner/cars/new`.

**Interfaces:**
- Produces: `GET /owner/cars/new` → model `form` (CarForm rỗng) + `categories` → view `owner/car-form.html`; `POST /owner/cars/new` params `@Valid CarForm form, BindingResult, @RequestParam("images") List<MultipartFile> images` (optional — 0 file hợp lệ) → createCar → redirect `/owner/cars` + flash "Đăng xe thành công. Xe đang chờ Admin duyệt."
- Produces: template `owner/car-form.html` — **port từ `docs/design/stitch/owner-them-xe-moi.html` (layout B)**: form `enctype="multipart/form-data"`, `th:object="${form}"`, các field per CarForm + select category từ model + input file multiple `name="images"` (khối upload theo prototype — giữ dropzone/khung ảnh Tailwind). Lỗi field hiển thị dưới field. Nút submit.
- Bind đủ field theo DB/DTO; bỏ deposit/amenities không có schema, thêm HYBRID và location/color còn thiếu trong prototype. Hiển thị lỗi năm tối đa cạnh field năm, lỗi parse số/enum tiếng Việt, BusinessException và multipart quá giới hạn bằng giao diện có thể thử lại; không thêm field DB.

- [x] **Step 1: Viết controller mapping + template.**
- [x] **Step 2: Verify — flow curl POST multipart (CSRF + session)**

```powershell
# login owner... (như P1.7) — jar.txt của owner
$h = curl.exe -s -b jar.txt -c jar.txt http://localhost:8080/owner/cars/new
$tok = [regex]::Match($h, 'name="_csrf"[^>]*value="([^"]+)"').Groups[1].Value
# tạo file ảnh test 1x1 pixel png
$png = [byte[]](0x89,0x50,0x4E,0x47,0x0D,0x0A,0x1A,0x0A,0x00,0x00,0x00,0x0D,0x49,0x48,0x44,0x52,0x00,0x00,0x00,0x01,0x00,0x00,0x00,0x01,0x08,0x06,0x00,0x00,0x00,0x1F,0x15,0xC4,0x89,0x00,0x00,0x00,0x0A,0x49,0x44,0x41,0x54,0x78,0x9C,0x63,0x00,0x01,0x00,0x00,0x05,0x00,0x01,0x0D,0x0A,0x2D,0xB4,0x00,0x00,0x00,0x00,0x49,0x45,0x4E,0x44,0xAE,0x42,0x60,0x82)
[IO.File]::WriteAllBytes("$pwd\test.png", $png)
curl.exe -s -i -b jar.txt -c jar.txt -X POST http://localhost:8080/owner/cars/new `
  -F "brand=Toyota" -F "model=Camry" -F "manufactureYear=2022" -F "licensePlate=30A12345" -F "color=Đen" -F "seatCount=5" -F "transmission=AUTOMATIC" -F "fuelType=GASOLINE" -F "description=Xe sạch" -F "pricePerDay=900000" -F "categoryId=1" -F "province=Hà Nội" -F "district=Cầu Giấy" -F "addressDetail=Số 1 Phạm Hùng" -F "images=@$pwd\test.png;type=image/png" -F "_csrf=$tok"
# → 302 /owner/cars. SQL: SELECT id, brand, approval_status, listing_status FROM cars; → 1 row PENDING/ACTIVE
# SQL: SELECT image_url FROM car_images; → 1 row /uploads/<uuid>.png; file uploads\<uuid>.png tồn tại
```

Upload sai loại: `-F "images=@test.txt;type=text/plain"` → response re-render form (200) chứa lỗi validation tiếng Việt từ `StorageService`, không có file mới trong `uploads/`.

### Task P2.8: Form sửa xe + quản lý ảnh

**Files:**
- Modify: `OwnerCarController` — thêm `GET/POST /owner/cars/{id}/edit`, `POST /owner/cars/{id}/images/{imageId}/delete`.
- Modify: `templates/owner/cars.html` — form ẩn/đăng lại xe; `OwnerCarController` thêm POST `/owner/cars/{id}/deactivate` và `/owner/cars/{id}/activate` gọi service P2.5, CSRF + flash tiếng Việt. Kiểm tra ownership và chỉ xe APPROVED được đăng lại.

**Interfaces:**
- Produces: `GET /owner/cars/{id}/edit` → load car (ownership check) → map entity → CarForm + model `car`, `images` (findByCarIdOrderByIdAsc) → view `owner/car-form.html` (reuse — mode edit: thêm section "Ảnh hiện tại" với thumbnail + nút xóa ảnh từng cái, dựng theo cùng design system `owner-them-xe-moi.html`).
- Produces: `POST /owner/cars/{id}/edit` — như new + updateCar → redirect flash "Cập nhật xe thành công.".
- Produces: `POST /owner/cars/{id}/images/{imageId}/delete` — ownership + ảnh thuộc xe → xóa bản ghi; file chỉ xóa sau commit bằng `StorageService.delete(imageUrl)` (web path được kiểm tra), log nếu cleanup thất bại → redirect về edit. Rollback phải giữ ảnh cũ.
- Upload ảnh mới khi sửa thực hiện trong cùng transaction với update; validate cả batch trước ghi và dọn file mới khi rollback như create. Giữ interface update 3 tham số P2.5, thêm overload nhận uploads.

- [x] **Step 1: Viết mapping + method `CarForm.from(Car)` (mapper tĩnh) + template edit mode.**
- [x] **Step 2: Verify** — curl POST edit đổi pricePerDay → SQL price đổi, approval_status vẫn như cũ (D-03: không duyệt lại). POST xóa ảnh → car_images giảm 1 row, file biến mất.

### Task P2.9: Admin — danh sách xe + filter

**Files:**
- Create: `src/main/java/com/carrent/car/AdminCarController.java`.
- Create: `src/main/resources/templates/admin/cars.html`.

**Interfaces:**
- Produces: `GET /admin/cars` — query params `q`, `approval`, `listing`, `page`, `size` (mặc định 10) → `carService.searchCarsForAdmin(q, approval, listing, pageable, principal)` → view `admin/cars.html` — **port từ `docs/design/stitch/admin-duyet-xe.html` (layout B: sidebar admin)**: giữ class Tailwind; bảng (thumbnail, brand/model, owner fullName, price, province, badge status, nút Xem chi tiết), hàng filter form GET + tab trạng thái theo prototype, pagination fragment.

- [x] **Step 1: Viết controller + template + service lọc thực.** Tạo ngay các clause `brandOrModelContains`, `approvalEq`, `listingEq` trong `CarSpecifications`, thêm `JpaSpecificationExecutor<Car>` và `searchCarsForAdmin`; dùng lại ở P2.11. Không UI lọc giả/placeholder.

**Lưu ý thứ tự:** Chủ dự án yêu cầu P2.7–P2.10 trong đợt 4; phần prerequisite Specification cho Admin làm trong P2.9. P2.11 bổ sung các clause public và `CarSearchCriteria`/`searchPublicCars`, không tạo lại phần Admin.

- [x] **Step 2: Verify** — login admin → `GET /admin/cars` = 200 (list có xe PENDING từ P2.7). Login owner → 403.

### Task P2.10: Admin — chi tiết xe + approve/reject

**Files:**
- Modify: `AdminCarController`.
- Create: `src/main/resources/templates/admin/car-detail.html`.

**Interfaces:**
- Produces: `GET /admin/cars/{id}` → model car + images + owner info → view chi tiết — **port panel xem xe + nút duyệt/từ chối từ `docs/design/stitch/admin-duyet-xe.html` (layout B)**: gallery, mọi field, thông tin owner (fullName, phone, email).
- Produces: `POST /admin/cars/{id}/approve` → service `approveCar(carId, principal)` (ADMIN/ACTIVE thực trong DB, check PENDING → APPROVED) → redirect flash "Đã duyệt xe."
- Produces: `POST /admin/cars/{id}/reject` — param `reason` không trống, ≤500 ký tự → service `rejectCar(carId, reason, principal)` (ADMIN/ACTIVE thực trong DB, check PENDING → REJECTED + reason) → redirect flash "Đã từ chối xe.". Lý do sai hiển thị lỗi tiếng Việt và giữ input trên detail.
- Transition sai (đã APPROVED duyệt lại) → BusinessException flash lỗi.

- [x] **Step 1: Viết controller + 2 service method.**
- [x] **Step 2: Verify** — curl POST approve xe từ P2.7 → SQL approval_status=APPROVED. POST reject xe khác (tạo xe thứ 2 trước) → REJECTED + reason ≠ NULL. POST approve xe đã APPROVED → flash lỗi, status không đổi.

### Task P2.11: CarSpecifications + search service (dùng chung public + admin)

**Files:**
- Create: `src/main/java/com/carrent/car/CarSpecifications.java`.
- Modify: `CarService.java` — thêm `searchPublicCars(CarSearchCriteria, Pageable)` và dùng lại `searchCarsForAdmin(q, approval, listing, Pageable, principal)`; P2.14 bổ sung principal để kiểm tra ADMIN/ACTIVE thực từ DB.

**Interfaces:**
- Produces: `CarSearchCriteria` (DTO GET query): `q` (String), `categoryId` (Long), `province` (String), `minPrice` (Long), `maxPrice` (Long), `seatCount` (Integer), `transmission` (Transmission), `fuelType` (FuelType), `sort` (String: `priceAsc|priceDesc|newest` — default newest).
- Produces: `CarSpecifications` static methods: `isPublic()` (APPROVED + ACTIVE), `brandOrModelContains(q)`, `categoryEq`, `provinceEq`, `priceBetween`, `seatEq`, `transmissionEq`, `fuelEq`, `approvalEq`, `listingEq`.
- Produces: `CarService.searchPublicCars(criteria, pageable)`: `Specification.allOf` với `isPublic` bắt buộc và từng điều kiện có giá trị + sort mapping cho `priceAsc|priceDesc|newest`, thêm ID làm tie-breaker → `carRepository.findAll(spec, pageable)`. Reuse các clause/Admin search đã triển khai P2.9, không viết lại.
- Produces: `searchCarsForAdmin` — như trên nhưng bỏ `isPublic`, thêm approval/listing filter + q search.

- [x] **Step 1: Viết Specification + 2 service method.**
- [x] **Step 2: Verify** — compile và self-check Spring/MySQL 39 assertions; public predicate luôn áp dụng, sort whitelist và ID tie-breaker. Review P2.11 Approved.

### Task P2.12: Public `/cars` — danh sách + filter UI + pagination

**Files:**
- Create: `src/main/java/com/carrent/car/CarController.java` (public).
- Create: `src/main/resources/templates/cars/list.html`.
- Modify: `templates/home/index.html` — form search trỏ đúng `/cars` (đã có từ P0.3).

**Interfaces:**
- Produces: `GET /cars` — bind `CarSearchCriteria` (Spring tự bind query param vào DTO field) + `page` (default 0), `size` (default 12) → service search → model `cars` (Page<Car>), `criteria`, `categories` → view `cars/list.html` — **port từ `docs/design/stitch/cars-tim-xe-2.html` (layout A)**: giữ class Tailwind; grid card xe `th:each` (thumbnail, brand model, category badge, giá, province, link detail), filter bar + sort dropdown giữ nguyên markup — chỉ bind `criteria` + `th:action="@{/cars}"`; empty state tiếng Việt; pagination fragment.
- Thumbnails: model attribute `thumbnails` Map<Long,String> build từ `carImageRepository` — `min(id) group by car_id` cho list page hiện tại (query `@Query("select c.car.id, min(c.imageUrl) from CarImage c where c.car.id in :ids group by c.car.id")` — lưu ý: `min(imageUrl)` không đúng "id nhỏ nhất"; D-10 nói **ảnh id nhỏ nhất** → query `select ci.car_id, ci.image_url from car_images ci where ci.id in (select min(id) from car_images group by car_id) and car_id in :ids` — viết bằng native query `@Query(value = "...", nativeQuery = true)`.)
- Reuse `CarImageRepository.findFirstImagesByCarIds` đã tạo ở P2.6; không thêm query thumbnail trùng lặp.

- [x] **Step 1: Viết controller + template + query thumbnail.**
- [x] **Step 2: Verify — dữ liệu test** — 65 assertions Spring/MySQL và HTTP thật; giữ dữ liệu gốc, probe bổ sung rollback-only. Review P2.12 Approved.

Tạo 4 xe (2 APPROVED+ACTIVE, 1 PENDING, 1 REJECTED; khác category/province/price) qua UI/curl. Rồi:
- `GET /cars` (anonymous) = 200, grid có đúng 2 xe công khai.
- `GET /cars?categoryId=1&minPrice=500000` → chỉ xe khớp điều kiện.
- `GET /cars?sort=priceAsc` → thứ tự giá tăng.
- `GET /cars?minPrice=2000000&maxPrice=1000000` → 200, empty state (không exception).
- `GET /cars?page=999` → 200 empty (Pageable an toàn).

### Task P2.13: Public `/cars/{id}` — detail + quyền xem

**Files:**
- Modify: `CarController.java`.
- Create: `src/main/resources/templates/cars/detail.html`.

**Interfaces:**
- Produces: `GET /cars/{id}` → service `getPublicCar(carId, principal)`:
  - Car APPROVED+ACTIVE → OK.
  - Car khác → nếu principal còn ADMIN/ACTIVE trong DB → OK; nếu principal còn OWNER/ACTIVE trong DB và sở hữu xe → OK; ngược lại `NotFoundException` (404 — không lộ tồn tại xe).
  - Model: car, images (OrderByIdAsc), thumbnails/detail gallery, owner info (fullName, phone).
- Produces: template — **port từ `docs/design/stitch/cars-chi-tiet-xe-dat-thue.html` (layout A)**: gallery (ảnh đầu lớn + thumbnail strip), bảng thông số, giá, địa điểm, thông tin owner, nút "Đặt xe ngay" (link `/bookings/new?carId=` — Phase 3 dựng trang, tạm 404 chấp nhận), form kiểm tra khả dụng (2 input date + submit GET lại `/cars/{id}?start=...&end=...` → model `availability` check "Xe khả dụng"/"Xe bận" — service check conflict booking — **Booking chưa có ở Phase 2** → tạm render form + banner "Tính năng kiểm tra lịch sẽ hoạt động sau Phase 3" hoặc ẩn form; quyết định: **ẩn form đến Phase 3** — ghi chú TODO trong template, P3.6 bật).

- [x] **Step 1: Viết controller + service + template.**
- [x] **Step 2: Verify** — 67 assertions Spring/MySQL và HTTP thật: xe APPROVED+ACTIVE anonymous 200; xe riêng tư 404, chỉ Owner sở hữu/Admin còn ACTIVE trong DB được preview 200. Review P2.13 Approved.

### Task P2.14: Checkpoint rà soát phase 2

- [x] Checklist: (1) BR-02/BR-03 cross-role/cross-owner với hai Owner sẵn trong DB, không thêm user; (2) upload validation/rollback/after-commit; (3) search/filter/sort/pagination/query encoding; (4) permission matrix Car và CSRF/stale principal; (5) empty states. Kết quả đợt cuối ghi bên dưới.
- Rà và sửa các vấn đề đã phân tích: cập nhật trạng thái đồng thời, `ThreadLocal` upload, URL query encoding; kiểm tra Owner–Admin–Public, browser desktop/mobile và restart app 8080. Xóa cứng Owner vẫn thuộc P3.12; availability check thuộc P3.6, notification thuộc P3.3.

## Kết quả verify — đợt 1 (P2.1–P2.2), 2026-10-10

- Hoàn tất 10 file entity/enum/repository trong `src/main/java/com/carrent/car/`; map schema sẵn, không thêm dependency hoặc Java seeder.
- Compile exit 0; Hibernate `ddl-auto=validate` đạt trên MySQL thật, cấu hình thường giữ `none`.
- JPA đọc đúng toàn bộ field/quan hệ xe, ảnh và category; repository phân trang Owner và truy vấn ảnh đúng xe. Insert/update rollback kiểm tra default PENDING/ACTIVE, timestamp và tiền 3–4 tỷ VND bằng Long/BIGINT.
- Mọi record, fingerprint, cột và constraint của 7 bảng giữ nguyên; counts users/categories/cars/images/bookings/payments/notifications = 9/6/6/6/6/6/10. Khởi động lại vẫn 6 category; smoke HTTP Phase 1 đạt.
- Review đợt 1: Spec đạt, quality Approved, không còn finding production. Bằng chứng local: `.scratch/phase2/VerifyCarMapping.java`, `verify-db.py`, `db-before.json`, các log batch-1/reboot và `.superpowers/sdd/phase-2-car/batch-1-review.md`.
- Harness timestamp đã sửa giả định truncation: MySQL DATETIME(6) làm tròn lúc insert, createdAt giữ nguyên khi update; production theo callback `User.java`.
- Probe ghi được rollback; AUTO_INCREMENT có thể tăng tạo khoảng trống ID. Không chạy Java unit test theo quy ước dự án; chưa stage/commit/push. Upload/service/UI thuộc các task sau.

## Kết quả verify — đợt 2 (P2.3–P2.4), 2026-10-10

**Hoàn tất:** `StorageService`, `WebConfig`, `CarForm`, `CarService.createCar`. Tạo xe kiểm tra Owner/ACTIVE trong DB, category và DTO; validate cả batch ảnh trước ghi. Xe và ảnh mới được rollback cùng nhau khi upload hoặc giao dịch thất bại, thay lựa chọn lưu xe một phần trong plan cũ.

| Kiểm tra | Kết quả |
|---|---|
| Compile | `.\mvnw.cmd -q -DskipTests compile` exit 0, kiểm tra lại bởi controller |
| RED/GREEN P2.3 | Thiếu StorageService trước code; GREEN bytes/UUID, extension/MIME/5MB, tên gốc có traversal, stream lỗi không file ghi dở, delete an toàn |
| RED/GREEN P2.4 | Thiếu CarForm trước code; GREEN các giới hạn DTO, năm hiện tại+1, role/status thực từ DB, category, trim và giá Long |
| MySQL/Spring/filesystem | Tạo không ảnh và hai ảnh, bỏ placeholder rỗng; hàng ảnh/file khớp; caller rollback và ảnh thứ hai lỗi đều dọn xe/ảnh mới |
| Resource HTTP | Sáu ảnh seed anonymous HTTP 200 đúng bytes, ảnh thiếu HTTP 404; kiểm tra cả context port 0 và app 8080 |
| Khởi động và auth smoke | App mới PID 46144, cổng 8080; login/register/home/CSS, login ba role, role redirect, sai role HTTP 403, logout CSRF/session đạt |
| Bảo toàn dữ liệu | Mọi row fingerprint/cột/constraint của 7 bảng khớp baseline; sáu hash ảnh gốc và tên file không đổi, không probe tồn dư |
| Review | P2.3/P2.4 Approved; không Critical/Important. Minor: stack trace IO được cố ý tạo trong GREEN log gây nhiễu khi đọc |

**Điều chỉnh harness:** Một assertion sai về principal INACTIVE trong bộ nhớ (DB vẫn ACTIVE) đã tạo nhầm một xe probe không ảnh; đã xác định/xóa đúng một row đó và kiểm chứng baseline. Sau sửa, thay đổi role/status chỉ trong transaction rollback. Probe thiếu principal ID đã phát hiện sai exception và bổ sung guard. Smoke cuối sửa expected redirect Owner từ `/owner/cars` sang `/owner/dashboard` theo handler hiện hữu; không đổi auth production.

**Bằng chứng local:** `.scratch/phase2/VerifyStorage.java`, `VerifyCarCreate.java`, `verify-checkpoint.py`, `verify-db.py`, `task-3-green.log`, `task-4-green.log`, `task-4-null-id-red.log`, `task-3-uploads-before.json`, `app-batch-2.log`; briefs/reports/reviews trong `.superpowers/sdd/phase-2-car/`. Các artifacts này bị Git bỏ qua theo lựa chọn chủ dự án.

**Giới hạn:** Không Java unit test theo quy ước. Chưa kiểm tra giữ ảnh sau commit và cleanup khi commit lỗi bằng commit thật; callback đã review. Metadata không bảo đảm nội dung ảnh hợp lệ. Lỗi quyền xóa hoặc process dừng đột ngột có thể để lại file, cleanup failure được log. AUTO_INCREMENT có thể có khoảng trống sau probe. UI/form tạo xe và xử lý multipart quá giới hạn thuộc P2.7; helper lỗi năm cần hiển thị cạnh field năm. **Tiếp theo P2.5 + P2.6**, chưa stage/commit/push.

## Kết quả verify — đợt 3 (P2.5–P2.6), 2026-10-10

**Hoàn tất:** Service cập nhật/ẩn/đăng lại xe và trang Owner `/owner/cars`. Update dùng validation/category/authorization chung với create, giữ owner/approval/rejectionReason/listing; ẩn mọi trạng thái, đăng lại chỉ APPROVED. Danh sách chỉ lấy xe của principal, stats thật toàn bộ dataset, thumbnail query batch theo ID ảnh nhỏ nhất; pagination fragment tái dùng cho task sau.

| Kiểm tra | Kết quả |
|---|---|
| Compile | Controller chạy `.\mvnw.cmd -q -DskipTests compile`, exit 0 sau bản sửa cuối |
| P2.5 MySQL/Spring | 121 assertions đạt: update field/trim/giá Long, ownership trước validation, stale role/status, ID/category/DTO sai, giữ trạng thái duyệt; lifecycle đầu tiên/lặp lại flush/clear và đọc SQL; create regression đạt |
| P2.6 HTTP thật | Anonymous 302/login, CUSTOMER/ADMIN 403; owner1 `[5,2,1]`, stats `3/2/0`; owner2 `[6,4,3]`, stats `3/1/1`; stats không đổi khi size1, bỏ qua ownerId/sort, negative/high/max page, size0/999, query params lặp/encoded và empty Owner đạt |
| Max page regression | Trước sửa `page=2147483647&size=1` timeout; sau sửa HTTP 200, empty state, tối đa 5 số trang và mọi link về trang hợp lệ. Root chạy lại cùng probe trên app 8080, exit 0 |
| Rollback-only render | MockMvc trong Spring/MySQL thật: ID ảnh nhỏ nhất thắng URL lexicographic, giá VND và escape lý do từ chối đạt; outer transaction rollback-only trước ghi |
| Desktop/mobile | Screenshot 1440px/390px đã xem; document width đúng viewport, 3 ảnh tải được; bảng cuộn riêng 358/850px trên mobile và nút sửa truy cập được |
| Startup/auth/uploads | PID33704, 8080, Started 23:24:29 +07:00, ACCEPTING_TRAFFIC; auth smoke, ba role login/redirect, sai quyền 403, CSRF logout, sáu ảnh seed đúng bytes/hash đạt; không app ERROR/WARN, console chỉ cảnh báo Tailwind Play CDN hiện có |
| DB | `verify-db.py` exit 0; mọi row hash/timestamp/cột/constraint của 7 bảng khớp baseline, counts 9/6/6/6/6/6/10; không dữ liệu probe tồn dư |
| Review | P2.5/P2.6 Approved; finding persistence-check, overflow và class stat-card đã xử lý; `git diff --check` exit 0 |

**Bằng chứng local:** `.scratch/phase2/VerifyCarUpdate.java`, `verify-owner-cars-http.py`, `VerifyOwnerCarsRender.java`, `batch-3-final-http.log`, `app-batch-3-final.log`, `batch-3-*-check.json`, `owner-cars-desktop.png`, `owner-cars-mobile.png`; `.superpowers/sdd/phase-2-car/task-5-fix-1-review.md`, `task-6-review.md`, `task-6-fix1-report.md`, `task-6-fix1-review.md`. Artifacts bị Git bỏ qua.

**Giới hạn checkpoint:** Nút tạo/sửa và POST ẩn/đăng lại chờ P2.7/P2.8; login Owner vẫn redirect `/owner/dashboard` chưa có trang, mở `/owner/cars` trực tiếp. Không Java unit test theo quy ước; giới hạn upload/commit-cleanup đợt 2 vẫn áp dụng. **Tiếp theo P2.7 + P2.8**; chưa stage/commit/push, toàn Phase 2 chưa hoàn tất.

## Kết quả verify — đợt 4 (P2.7–P2.10), 2026-10-11

**Hoàn tất:** Form tạo/sửa xe phía Owner kèm quản lý ảnh và thao tác ẩn/đăng lại trên UI; danh sách Admin có lọc `q`/approval/listing, thumbnail, badge PENDING toàn cục và phân trang; chi tiết xe Admin với duyệt/từ chối kèm lý do. Không thêm dependency, không đổi schema/seed, không Java unit test.

| Kiểm tra | Kết quả |
|---|---|
| RED trước code | Owner `/edit`, `/deactivate`, `/activate`, `/images/{id}/delete` → 404; `/admin/cars` → 403 với owner và 404 với admin trên bản cũ. Log `.scratch/phase2/task-8-red.log` |
| Compile | `./mvnw -q -o -DskipTests compile` exit 0 sau mọi bản sửa |
| P2.7 GREEN | `VerifyCreateForm` exit 0: form 14 control/multipart/`_csrf`, tạo rollback-only 0 và 2 ảnh (trim, PENDING/ACTIVE, `ownerId` giả bị bỏ qua, UUID đúng bytes, rollback dọn file), 14 biến thể lỗi field tiếng Việt không lộ lỗi binding, `<script>` được escape, guard ẩn danh 302/role 403/CSRF 403, 413 HTTP thật trả trang tiếng Việt |
| P2.8 GREEN | `VerifyOwnerCarEdit` exit 0, **348 checks**, 14 nhóm PASS: GET edit điền 14 control và form phẳng, guard sở hữu/role/CSRF/stale, update atomic giữ approval/listing/reason và nối ảnh mới, 14 body lỗi không đổi DB/file, xóa ảnh giữ file tới commit, ẩn/đăng lại idempotent, đăng lại xe PENDING bị từ chối, xóa committed gỡ file |
| P2.9 + P2.10 GREEN | `VerifyAdminCars` exit 0, **186 checks**, 17 nhóm PASS: guard truy cập, danh sách nhiều owner/trạng thái, thumbnail là ảnh id nhỏ nhất, `q` theo hãng/model không phân biệt hoa thường với `%`/`_` khớp literal, lọc approval/listing kết hợp AND, empty state, badge PENDING toàn cục, phân trang chặn size/page/âm/vượt, escape; chi tiết xe với gallery và empty state, id thiếu 404 và id không phải số 400, xe đã xử lý ẩn control, duyệt xóa lý do từ chối cũ và giữ listing/metadata, lặp lại bị từ chối, từ chối trim + lưu + hiển thị lại cho Owner, lý do rỗng/khoảng trắng/thiếu re-render 200 giữ PENDING, biên 500 nhận/501 từ chối, chuyển trạng thái sai bị từ chối, guard role/CSRF/404, lý do `<script>` được escape |
| Regression | Chạy lại `VerifyOwnerCarsRender` và `VerifyCarCreate` exit 0 sau khi thêm handler 400 mới |
| DB và uploads | `python -I .scratch/phase2/verify-db.py` exit 0 sau mọi lần chạy; mọi row/cột/constraint của 7 bảng khớp baseline, counts 9/6/6/6/6/6/10; sáu ảnh gốc byte-identical; app 8080 PID33704 vẫn LISTENING, không bị đụng |
| Review | Báo cáo `task-7/8/9/10-report.md`; lỗi thật đã sửa: nested form xóa ảnh trong `owner/car-form.html`; enum sai trong query trả 500 do catch-all che mapping 400 của Spring cho `MethodArgumentTypeMismatchException` — thêm handler riêng + `error/400.html`; upload quá lớn trả JSON 413 tiếng Anh — thêm `error/413.html` |

**Bằng chứng local:** `.scratch/phase2/VerifyCreateForm.java`, `VerifyOwnerCarEdit.java`, `VerifyAdminCars.java`, `harness-classpath.args`, `task-7-green.log`, `task-8-green.log`, `task-9-run2.log`, `task-8-red.log`, `verify-db.py`; briefs/reports trong `.superpowers/sdd/phase-2-car/`. Artifacts bị Git bỏ qua.

**Giới hạn checkpoint:** Đường giới hạn tổng request multipart (33MB qua 8 file) không quan sát được — Tomcat hủy body chunked trước error dispatch nên không trang nào render; đường giới hạn từng file đã chứng minh. Chưa có test đồng thời cho duyệt/từ chối: kiểm tra chuyển trạng thái nằm cùng transaction với ghi, brief không cho phép claim double-write chưa kiểm. Mọi fixture ghi đều rollback-only nên AUTO_INCREMENT có thể tăng; không có row probe tồn dư. Chưa có notification (Phase 3). **Tiếp theo P2.11 + P2.12**; root còn xem diff, chụp desktop 1440px/mobile 390px và khởi động lại app 8080; chưa stage/commit/push.

## Kết quả verify — đợt cuối (P2.11–P2.14), 2026-10-11

Các đợt 1–4 phía trên là lịch sử tại từng checkpoint. Đợt này chốt toàn bộ chức năng Phase 2; không đổi schema/seed/dependency, không Java unit test theo quy ước.

| Kiểm tra | Kết quả |
|---|---|
| P2.11 search service | 39 assertions Spring/MySQL: public predicate bắt buộc, các filter kết hợp, Long price, wildcard literal, whitelist sort/ID tie-breaker và pageable; review Approved |
| P2.12 public list | 65 assertions cùng HTTP thật: dữ liệu/thumbnail thật, 9 filter controls, sorting/pager/query encoding, typed/negative input 400 tiếng Việt, range đảo empty200; review Approved |
| P2.13 detail | 67 assertions và HTTP ma trận: public200, private chỉ own Owner/Admin còn ACTIVE, người khác404; gallery theo ID/empty state/contact giới hạn/preview không đặt thuê; review Approved |
| P2.14 concurrency và cleanup | Focused checker cuối **22 checks**: hai connection duyệt/từ chối một winner; managed-stale Admin từ chối quyết định cũ; managed-stale Owner sửa và ẩn giữ rejection/reason đã commit; call nối trong transaction giữ listing và dọn đủ hai ảnh rollback; fixture commit exact-ID đã dọn |
| Owner/Admin regression | Admin **212 checks**, Owner edit **350 checks**, create/storage/public regressions và multipart form đạt; Admin hide/republish/CSRF và persisted ADMIN/ACTIVE read guard, edit wording, decided-car script và encoded Admin q đã sửa |
| Compile/startup | Root `.\mvnw.cmd -q -DskipTests compile` exit0 trước/sau sửa template cuối; hidden Java cuối **PID31176**, port8080, Started08:45:15 +07:00, readiness ACCEPTING_TRAFFIC và `/cars` HTTP200; stderr rỗng |
| Final normal8080 | `verify-auth.py --smoke`, `verify-checkpoint.py`, `final/verify-http.py` exit0; **65 HTTP assertions**, ba-role sessions/logout/CSRF, public IDs1/2/6, private matrix, input/pager/encoding và sáu ảnh seed byte-identical |
| Browser | Desktop1440/mobile390 không tràn ngang toàn trang; ảnh tải đúng, mobile filter submit/reset và gallery link hoạt động; Admin decided không script error, pending dialog/quick reason/Escape hoạt động; Owner edit CTA đúng, không nested forms |
| Bảo toàn DB/uploads | `verify-db.py` exit0 cho mọi row/timestamp/hash/cột/constraint; counts **9/6/6/6/6/6/10**. Sáu ảnh seed name/hash giữ nguyên, không probe tồn dư. AUTO_INCREMENT có thể tăng |
| Review | P2.11–P2.14 Approved, stale Owner evidence đã bổ sung. Review toàn bộ source không Critical/Important; hai Minor (province Admin và lời nhắc approval) sửa cùng đợt, focused render RED/GREEN đạt, final scoped re-review **Approved**, không finding còn mở |

**Bằng chứng local:** `.scratch/phase2/VerifyPhase2Checkpoint.java`, `VerifyPublicCarSearch.java`, `VerifyPublicCarsPage.java`, `VerifyPublicCarDetail.java`, `VerifyFinalPresentation.java`, các checker Owner/Admin trước đó; `final/verify-http.py`, `final/final-checks.md`, `final/app.log`, screenshot trong `final/`; `.superpowers/sdd/phase-2-car/task-{11,12,13,14}-report.md`, review từng task, `task-14-fix1-review.md`, `final-review.md`, `final-fix-report.md` và `final-fix-review.md`. Artifacts giữ local/Git ignored theo lựa chọn chủ dự án.

**Giới hạn còn lại:** Upload chỉ kiểm extension/MIME/kích thước, chưa decode ảnh. Chunked request vượt tổng30MB có thể bị Tomcat ngắt trước trang413; từng file vượt5MB đã chứng minh trả trang tiếng Việt. Filesystem sau DB commit không atomic: lỗi quyền xóa hoặc process crash có thể để lại file và được log. Booking/lịch khả dụng **P3.6**, notification **P3.3**, xóa cứng xe Owner **P3.12**, featured homepage **P5.8**; dashboard/profile chưa có trang, mở `/owner/cars`/`/admin/cars` trực tiếp sau login. Chưa stage/commit/push; giữ branch/workspace/app để test.
