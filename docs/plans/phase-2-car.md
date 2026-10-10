# CarRent — Phase 2: Car Management — Implementation Plan

> **For agentic workers:** Chạy task-by-task. Checkbox `- [ ]` tracking. Không git (D-19). Quy ước verify chung ở `phase-0-init.md` (compile/boot/curl/SQL/POST-flow CSRF).

**Goal:** Owner CRUD xe + upload ảnh; Admin duyệt/từ chối/ẩn xe; public search/filter/sort/phân trang/detail — FR-04→FR-09.

> **D-24 / SDD v2.1:** DB và dữ liệu mẫu đã có từ `scriptDB.sql`; Hibernate `ddl-auto=none`, entity map đúng §7.2/7.6, không tạo bảng hoặc seed Java. Các ví dụ “1 row” bên dưới áp dụng cho record mới của kịch bản, không phải tổng cả bảng; luôn lọc theo ID/owner của test hoặc so số lượng trước/sau. Mỗi xe mẫu có file placeholder riêng `uploads/seed-car-<id>.png`.

**Architecture:** `CarService` giữ mọi business rule (ownership, PENDING, xóa); `StorageService` lo upload; `CarSpecifications` dựng lọc động cho Pageable.

**Tech Stack:** Spring Data JPA Specification, MultipartFile, Thymeleaf + Tailwind CSS (design system Stitch).

**Spec:** `docs/SDD.md` §5 FR-04→FR-09, §6 BR-02/BR-03, §7.2 (cars/car_images/car_categories), §10 route, §8.2 templates; `docs/decisions.md` D-03, D-08, D-10, D-11, D-16.

---

## Global Constraints (tóm tắt)

- Quy ước phase-0 (gốc, mvnw, UI tiếng Việt/code Anh, Tailwind Play CDN, không git/test).
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

- [ ] **Step 1: Viết entity + repo.**
- [ ] **Step 2: Verify 6 category đã seed** (SEDAN, SUV, MPV, LUXURY, ECONOMY, ELECTRIC, description tiếng Việt 1 câu). Không insert category lúc boot; setup DB mới dùng `scriptDB.sql`.

- [ ] **Step 3: Verify** — boot → SQL: `SELECT COUNT(*) FROM car_categories;` → 6. Boot lại → vẫn 6.

### Task P2.2: Car + CarImage entity + enums + repos

**Files:**
- Create: `src/main/java/com/carrent/car/Car.java`, `CarImage.java`, `ApprovalStatus.java`, `ListingStatus.java`, `Transmission.java`, `FuelType.java`, `CarRepository.java`, `CarImageRepository.java`.

**Interfaces:**
- Produces: `Car` per SDD §7.2 cars: `@ManyToOne` User owner (LAZY), `@ManyToOne` CarCategory category (LAZY), các field enum `@Enumerated(EnumType.STRING)`. `rejectionReason` nullable. Timestamps `@PrePersist/@PreUpdate`.
- Produces: `CarImage` (Long id, `@ManyToOne Car` — cascade KHÔNG đặt, ta quản lý thủ công; String imageUrl; createdAt) — `@Table(name="car_images")`.
- Produces: `CarRepository extends JpaRepository<Car, Long>` — chỉ khai báo method cơ bản + `Page<Car> findByOwnerId(Long ownerId, Pageable pageable)`. Method lọc động dùng Specification (P2.11).
- Produces: `CarImageRepository`: `List<CarImage> findByCarIdOrderByIdAsc(Long carId)`, `Optional<CarImage> findByIdAndCarId(Long imageId, Long carId)`, `boolean existsByCarId(Long carId)`.

- [ ] **Step 1: Viết entities + enums + repos.**
- [ ] **Step 2: Verify** — boot → `DESCRIBE cars;` + `DESCRIBE car_images;` có đủ cột + FK đúng SDD.

### Task P2.3: StorageService + WebConfig + multipart

**Files:**
- Create: `src/main/java/com/carrent/storage/StorageService.java`, `src/main/java/com/carrent/config/WebConfig.java`.

**Interfaces:**
- Produces: `StorageService.store(MultipartFile file) → String` (web path `/uploads/<uuid>.<ext>`):
  - Validate: file != null && !file.isEmpty(); ext ∈ {jpg, jpeg, png, webp} (lấy từ originalFilename — chỉ để chọn đuôi, KHÔNG tin nội dung tên); content-type bắt đầu `image/`; size ≤ 5MB.
  - Sai validate → ném `BusinessException` với message tiếng Việt tương ứng ("Định dạng ảnh không được hỗ trợ", "Ảnh vượt quá 5MB", ...).
  - Tạo thư mục `uploads/` nếu chưa có; tên file = `UUID.randomUUID()` + "." + ext; lưu file; trả `/uploads/<name>`.
- Produces: `WebConfig implements WebMvcConfigurer` — `addResourceHandlers`: map `/uploads/**` → `file:uploads/` (đường dẫn tương đối gốc dự án; dùng `Paths.get("uploads").toAbsolutePath().toUri()`).

- [ ] **Step 1: Viết 2 class.** Lưu ý `spring.servlet.multipart.max-file-size=5MB` đã set ở P0.1.

- [ ] **Step 2: Verify** — compile + boot; `curl.exe -s -o $null -w "%{http_code}" http://localhost:8080/uploads/khong-co.jpg` → `404` (resource handler hoạt động — không Whitelabel).

### Task P2.4: CarService.create

**Files:**
- Create: `src/main/java/com/carrent/car/CarService.java`, `CarForm.java`.

**Interfaces:**
- Produces: `CarForm` (DTO form + validation): brand `@NotBlank @Size(max=50)`; model `@NotBlank @Size(max=50)`; manufactureYear `@NotNull @Min(1990) @Max(năm hiện tại+1)`; licensePlate `@Size(max=20)`; color `@Size(max=30)`; seatCount `@NotNull @Min(2) @Max(15)`; transmission `@NotNull` enum; fuelType `@NotNull` enum; description `@Size(max=2000)`; pricePerDay `@NotNull @Min(1)` (đơn vị VND — form input type number); categoryId `@NotNull`; province `@NotBlank @Size(max=100)`; district `@Size(max=100)`; addressDetail `@Size(max=255)`.
- Produces: `CarService.createCar(CarForm form, List<MultipartFile> images, CustomUserPrincipal principal) → Car`:
  - Lấy User owner từ principal.getId() (`UserRepository.getReferenceById`).
  - Build Car từ form (status PENDING/ACTIVE mặc định); save.
  - Với từng file ≠ empty: `storageService.store` → `new CarImage(car, path)`; save. Nếu store ném BusinessException → để lọt lên handler (redirect flash lỗi) — KHÔNG rollback xe đã tạo (chấp nhận đơn giản: xe tạo trước, ảnh lỗi hiện thông báo; owner sửa bổ sung ảnh ở edit). **Quyết định đơn giản hóa này ghi chú trong code comment.**

- [ ] **Step 1: Viết CarForm + CarService.createCar.**
- [ ] **Step 2: Verify** — compile OK (runtime gộp P2.7).

### Task P2.5: CarService update/deactivate/activate

**Files:**
- Modify: `src/main/java/com/carrent/car/CarService.java`.

**Interfaces:**
- Produces: `CarService.updateCar(Long carId, CarForm form, CustomUserPrincipal principal) → Car` — load car, check ownership (BR-02: sai → `ForbiddenException("Bạn không có quyền với xe này")`), cập nhật field từ form, KHÔNG đổi approval_status (D-03), save.
- Produces: `deactivateCar(carId, principal)` / `activateCar(carId, principal)` — ownership check + đổi listing_status; activate yêu cầu `approval_status=APPROVED` (nếu còn PENDING/REJECTED → BusinessException "Xe chưa được duyệt, không thể đăng lại").
- (Xóa cứng xe dời sang P3.12 — cần BookingRepository.)

- [ ] **Step 1: Viết 3 method.**
- [ ] **Step 2: Verify** — compile OK (runtime gộp P2.8).

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

- [ ] **Step 1: Viết controller + service method + 2 template.**
- [ ] **Step 2: Verify** — boot; login owner (tạo qua register UI/curl như P1.7) → `GET /owner/cars` = 200 body "Xe của tôi". Login customer → `GET /owner/cars` = 403 (access-denied). Anonymous → 302 /login.

### Task P2.7: Form tạo xe (multipart)

**Files:**
- Create: `src/main/resources/templates/owner/car-form.html`.
- Modify: `OwnerCarController` — thêm `GET/POST /owner/cars/new`.

**Interfaces:**
- Produces: `GET /owner/cars/new` → model `form` (CarForm rỗng) + `categories` → view `owner/car-form.html`; `POST /owner/cars/new` params `@Valid CarForm form, BindingResult, @RequestParam("images") List<MultipartFile> images` (optional — 0 file hợp lệ) → createCar → redirect `/owner/cars` + flash "Đăng xe thành công. Xe đang chờ Admin duyệt."
- Produces: template `owner/car-form.html` — **port từ `docs/design/stitch/owner-them-xe-moi.html` (layout B)**: form `enctype="multipart/form-data"`, `th:object="${form}"`, các field per CarForm + select category từ model + input file multiple `name="images"` (khối upload theo prototype — giữ dropzone/khung ảnh Tailwind). Lỗi field hiển thị dưới field. Nút submit.

- [ ] **Step 1: Viết controller mapping + template.**
- [ ] **Step 2: Verify — flow curl POST multipart (CSRF + session)**

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

Upload sai loại: `-F "images=@test.txt;type=text/plain"` → response re-render form (200) chứa lỗi "Định dạng ảnh không được hỗ trợ", không có file mới trong `uploads/`.

### Task P2.8: Form sửa xe + quản lý ảnh

**Files:**
- Modify: `OwnerCarController` — thêm `GET/POST /owner/cars/{id}/edit`, `POST /owner/cars/{id}/images/{imageId}/delete`.

**Interfaces:**
- Produces: `GET /owner/cars/{id}/edit` → load car (ownership check) → map entity → CarForm + model `car`, `images` (findByCarIdOrderByIdAsc) → view `owner/car-form.html` (reuse — mode edit: thêm section "Ảnh hiện tại" với thumbnail + nút xóa ảnh từng cái, dựng theo cùng design system `owner-them-xe-moi.html`).
- Produces: `POST /owner/cars/{id}/edit` — như new + updateCar → redirect flash "Cập nhật xe thành công.".
- Produces: `POST /owner/cars/{id}/images/{imageId}/delete` — ownership + ảnh thuộc xe → xóa bản ghi + file vật lý (`Paths.get(imageUrl).toFile().delete()` — bọc try-catch ignore) → redirect về edit.

- [ ] **Step 1: Viết mapping + method `CarForm.from(Car)` (mapper tĩnh) + template edit mode.**
- [ ] **Step 2: Verify** — curl POST edit đổi pricePerDay → SQL price đổi, approval_status vẫn như cũ (D-03: không duyệt lại). POST xóa ảnh → car_images giảm 1 row, file biến mất.

### Task P2.9: Admin — danh sách xe + filter

**Files:**
- Create: `src/main/java/com/carrent/car/AdminCarController.java`.
- Create: `src/main/resources/templates/admin/cars.html`.

**Interfaces:**
- Produces: `GET /admin/cars` — query params `q`, `approval`, `listing`, `page`, `size` (mặc định 10) → `carService.searchCarsForAdmin(q, approval, listing, pageable)` → view `admin/cars.html` — **port từ `docs/design/stitch/admin-duyet-xe.html` (layout B: sidebar admin)**: giữ class Tailwind; bảng (thumbnail, brand/model, owner fullName, price, province, badge status, nút Xem chi tiết), hàng filter form GET + tab trạng thái theo prototype, pagination fragment.

- [ ] **Step 1: Viết controller + method service (dùng Specification từ P2.11 — nếu P2.11 chưa làm, tạm dùng `carRepository.findAll(pageable)` không filter, đánh dấu TODO técnica gắn khi P2.11 xong; lý tưởng: làm P2.11 trước task này).** Thứ tự khuyến nghị: P2.9 chỉ dựng khung, lọc cắm sau P2.11.

**Lưu ý thứ tự:** plan liệt kê P2.9 trước P2.11 nhưng để tránh rework, khi chạy thực tế: làm P2.11 (Specifications) TRƯỚC P2.9/P2.12 — cùng block interface bên dưới.

- [ ] **Step 2: Verify** — login admin → `GET /admin/cars` = 200 (list có xe PENDING từ P2.7). Login owner → 403.

### Task P2.10: Admin — chi tiết xe + approve/reject

**Files:**
- Modify: `AdminCarController`.
- Create: `src/main/resources/templates/admin/car-detail.html`.

**Interfaces:**
- Produces: `GET /admin/cars/{id}` → model car + images + owner info → view chi tiết — **port panel xem xe + nút duyệt/từ chối từ `docs/design/stitch/admin-duyet-xe.html` (layout B)**: gallery, mọi field, thông tin owner (fullName, phone, email).
- Produces: `POST /admin/cars/{id}/approve` → service `approveCar(carId)` (check PENDING → APPROVED) → redirect flash "Đã duyệt xe."
- Produces: `POST /admin/cars/{id}/reject` — param `reason` `@NotBlank @Size(max=500)` → service `rejectCar(carId, reason)` (check PENDING → REJECTED + reason) → redirect flash "Đã từ chối xe."
- Transition sai (đã APPROVED duyệt lại) → BusinessException flash lỗi.

- [ ] **Step 1: Viết controller + 2 service method.**
- [ ] **Step 2: Verify** — curl POST approve xe từ P2.7 → SQL approval_status=APPROVED. POST reject xe khác (tạo xe thứ 2 trước) → REJECTED + reason ≠ NULL. POST approve xe đã APPROVED → flash lỗi, status không đổi.

### Task P2.11: CarSpecifications + search service (dùng chung public + admin)

**Files:**
- Create: `src/main/java/com/carrent/car/CarSpecifications.java`.
- Modify: `CarService.java` — thêm `searchPublicCars(CarSearchCriteria, Pageable)` + `searchCarsForAdmin(q, approval, listing, Pageable)`.

**Interfaces:**
- Produces: `CarSearchCriteria` (DTO GET query): `q` (String), `categoryId` (Long), `province` (String), `minPrice` (Long), `maxPrice` (Long), `seatCount` (Integer), `transmission` (Transmission), `fuelType` (FuelType), `sort` (String: `priceAsc|priceDesc|newest` — default newest).
- Produces: `CarSpecifications` static methods: `isPublic()` (APPROVED + ACTIVE), `brandOrModelContains(q)`, `categoryEq`, `provinceEq`, `priceBetween`, `seatEq`, `transmissionEq`, `fuelEq`, `approvalEq`, `listingEq`.
- Produces: `CarService.searchPublicCars(criteria, pageable)`: Specifications.where(isPublic).and(...từng điều kiện non-null) + sort mapping (`priceAsc` → `price_per_day ASC`...) → `carRepository.findAll(spec, pageable)`.
- Produces: `searchCarsForAdmin` — như trên nhưng bỏ `isPublic`, thêm approval/listing filter + q search.

- [ ] **Step 1: Viết Specification + 2 service method.**
- [ ] **Step 2: Verify** — compile. (Chức năng runtime verify ở P2.12.)

### Task P2.12: Public `/cars` — danh sách + filter UI + pagination

**Files:**
- Create: `src/main/java/com/carrent/car/CarController.java` (public).
- Create: `src/main/resources/templates/cars/list.html`.
- Modify: `templates/home/index.html` — form search trỏ đúng `/cars` (đã có từ P0.3).

**Interfaces:**
- Produces: `GET /cars` — bind `CarSearchCriteria` (Spring tự bind query param vào DTO field) + `page` (default 0), `size` (default 12) → service search → model `cars` (Page<Car>), `criteria`, `categories` → view `cars/list.html` — **port từ `docs/design/stitch/cars-tim-xe-2.html` (layout A)**: giữ class Tailwind; grid card xe `th:each` (thumbnail, brand model, category badge, giá, province, link detail), filter bar + sort dropdown giữ nguyên markup — chỉ bind `criteria` + `th:action="@{/cars}"`; empty state tiếng Việt; pagination fragment.
- Thumbnails: model attribute `thumbnails` Map<Long,String> build từ `carImageRepository` — `min(id) group by car_id` cho list page hiện tại (query `@Query("select c.car.id, min(c.imageUrl) from CarImage c where c.car.id in :ids group by c.car.id")` — lưu ý: `min(imageUrl)` không đúng "id nhỏ nhất"; D-10 nói **ảnh id nhỏ nhất** → query `select ci.car_id, ci.image_url from car_images ci where ci.id in (select min(id) from car_images group by car_id) and car_id in :ids` — viết bằng native query `@Query(value = "...", nativeQuery = true)`.)

- [ ] **Step 1: Viết controller + template + query thumbnail.**
- [ ] **Step 2: Verify — dữ liệu test**

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
  - Car khác → nếu principal ADMIN → OK; nếu principal là owner của xe → OK; ngược lại `NotFoundException` (404 — không lộ tồn tại xe).
  - Model: car, images (OrderByIdAsc), thumbnails/detail gallery, owner info (fullName, phone).
- Produces: template — **port từ `docs/design/stitch/cars-chi-tiet-xe-dat-thue.html` (layout A)**: gallery (ảnh đầu lớn + thumbnail strip), bảng thông số, giá, địa điểm, thông tin owner, nút "Đặt xe ngay" (link `/bookings/new?carId=` — Phase 3 dựng trang, tạm 404 chấp nhận), form kiểm tra khả dụng (2 input date + submit GET lại `/cars/{id}?start=...&end=...` → model `availability` check "Xe khả dụng"/"Xe bận" — service check conflict booking — **Booking chưa có ở Phase 2** → tạm render form + banner "Tính năng kiểm tra lịch sẽ hoạt động sau Phase 3" hoặc ẩn form; quyết định: **ẩn form đến Phase 3** — ghi chú TODO trong template, P3.6 bật).

- [ ] **Step 1: Viết controller + service + template.**
- [ ] **Step 2: Verify** — `GET /cars/<id xe APPROVED>` anonymous = 200 body brand xe. `GET /cars/<id xe PENDING>` anonymous = 404. Login owner của xe đó → 200. Login customer → 404. Login admin → 200.

### Task P2.14: Checkpoint rà soát phase 2

- [ ] Checklist: (1) BR-02/BR-03 verify cross-role (owner A ≠ owner B — tạo 2 owner bằng curl, owner A thử edit xe owner B → 403/404); (2) upload validation hoạt động; (3) search/filter/sort/pagination verify ở P2.12; (4) permission matrix cột Car (SDD §4.2) — đi qua từng cell bằng curl; (5) empty states. Ghi "Kết quả verify" vào cuối file plan này.
