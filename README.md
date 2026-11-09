# CarRent

**Quyết định 2026-11-09:** hủy Payment Settings S1/S2/S3, giữ source trước S1 và cấu hình provider bên ngoài DB như hiện có. [Phạm vi khôi phục và phát hành](docs/plans/pre-s1-release-2026-11-09.md).

**P4.2-A/P8.8 — 2026-11-09:** đã bổ sung VietQR recipient lookup mặc định tắt, xác minh lại khi xác nhận từng refund và diagnostic không payout. Provider/live còn pending; signed GET payOS hiện HTTP403. Xem [bàn giao](docs/plans/p42a-p88-implementation-handoff-2026-11-09.md) và [các trường private cần bổ sung](docs/p42a-p88-operator-guide-2026-11-09.md).

**UI-05C — 2026-11-08:** đã hoàn thiện booking list/detail Admin, đủ **15/15 trang** về implementation/kiểm chứng local. Suite cuối **89/89**, Node **8/8**, Chrome **482 kiểm tra**, **20 ảnh trước/109 ảnh sau**; smoke cuối đủ 15 trang và shared callers, 20 bảng đọc không đổi, payout 0 và fixture đã dọn. Xem [bàn giao UI-05C](docs/plans/ui-05C-handoff.md). Còn **0 đợt** trong scope gốc; human/native zoom/live giữ riêng, chưa triển khai UI-06/UI-07.

**UI-05B — 2026-11-08:** đã hoàn thiện danh sách/hồ sơ tài khoản Admin (2 trang), giữ self/CSRF/status-only. Focused **22/22**, Chrome **296 kiểm tra**, **16 ảnh trước/37 ảnh sau**; 20 bảng đọc theo đoạn, 3 POST audit chỉ target status/updatedAt, payout 0; fixture đã dọn. Xem [bàn giao UI-05B](docs/plans/ui-05B-handoff.md). Đã triển khai **13/15 trang**, còn **UI-05C: 1 đợt / 2 trang** và smoke cuối. Human/native zoom/live giữ riêng.

**UI-05A — 2026-11-08:** đã hoàn thiện dashboard và tài chính Admin (3 trang), giữ `fields(r)` cho booking. Focused **32/32**, Chrome **427 kiểm tra**, **36 ảnh trước/53 ảnh sau**; 20 bảng GET không đổi theo đoạn và toàn phiên sau phục hồi control; payout 0, fixture đã dọn. Xem [bàn giao UI-05A](docs/plans/ui-05A-handoff.md). Tại checkpoint UI-05A còn **2 đợt / 4 trang: UI-05B và UI-05C** trong phạm vi 15 trang; human visual review/native zoom 200% và gate live giữ riêng.

**UI-04 — 2026-11-08:** đã hoàn thiện gói đăng xe và order trong workspace Owner; tái sử dụng overview/refund-links. Focused **69/69**, Chrome **348 kiểm tra**, **40 ảnh trước/51 ảnh sau**; 20 bảng theo từng đoạn đọc, 5 POST/2 control audit, browser payout 0; fixture đã dọn. Xem [bàn giao UI-04](docs/plans/ui-04-handoff.md). Tại checkpoint UI-04, human visual review/native zoom 200% còn chờ; UI-05A trở đi và gate live giữ mở.

**UI-03 — 2026-11-08:** đã triển khai checkout/result/refund/refund-summary và refund-links; focused **135/135**, Node **8/8**, Chrome **828 kiểm tra + 28 bổ sung**, **36 ảnh trước/138 ảnh sau**. GET/poll/preview kiểm tra 20 bảng theo từng đoạn; 4 POST audit giữ invariant, fake payout 0; cleanup đã xác nhận. Xem [bàn giao UI-03](docs/plans/ui-03-handoff.md). Tại checkpoint UI-03, human visual review/native zoom 200% còn chờ; UI-04 trở đi và các gate live giữ mở.

**UI-02 — 2026-11-08:** dashboard Owner, finances đủ 9 role/view và overview dùng chung đã triển khai, kiểm chứng cục bộ: focused **47/47**, Chrome **1233 kiểm tra**, **28 ảnh trước/139 ảnh sau**; 20 bảng fixture không đổi, provider 0, cleanup đã xác nhận. Xem [bàn giao UI-02](docs/plans/ui-02-handoff.md). Tại checkpoint UI-02, human visual review/native zoom 200% còn chờ; UI-03 trở đi và gate live giữ mở.

**Checkpoint P5.8 + P5.9 local — 2026-11-04:** P5.8 hoàn thành **5/5**: ma trận quyền/CSRF/HMAC, biên thời gian, GET chỉ đọc, queue/quota/restart và tài liệu hiện hành. Sửa stale Owner/Admin khi mutation xe, vòng khóa notification/Admin status và HTTP405; regression RED/GREEN được giữ. P5.9 hoàn thành **5/6 checklist local**, còn **gate live**: hành trình HTTP mua gói → tạo/duyệt xe → booking mới → BEFORE →30%→Owner hợp đồng/giao→AFTER bất đồng→nhận lại→70%→COMPLETED; lối vào VIETQR_V1 mặc định tắt, giới hạn pilot và quyền Customer hiện tại. Full isolated **clean verify 1053/1053 JUnit, 0 failure/error/skip**, JAR; Node polling **6/6**; Chrome **256/256**, 108PNG tại1440/390/320. **83/86 task**, còn3 (`ducbinhnguyen07ars`2: P5.9/P8.8; `thanhmit`1: P4.2-A). **P5.9/P8.8/P4.2-A chưa đóng**, chưa có bằng chứng giao dịch/provider/human/migration live. Bảo toàn DB ứng dụng/config/seed/uploads/phase0/dirty prerequisites/refs/index; không kích hoạt hoặc Git publication. Phụ trách `ducbinhnguyen07ars`, reviewer `thanhmit`; agent review không thay human review. [Bàn giao](docs/plans/phase-5-batch-6-handoff.md).

**Checkpoint P5.7 — 2026-11-04:** hoàn thành **4/4 checklist local** dashboard Owner và trang chủ: gói/hạn/quota, xe/booking scoped principal, tiền thuê đã phân bổ và hoàn/nợ riêng; hoàn một phần giữ receipt, pending chưa tính đã hoàn, deadline theo Clock. Home tối đa8 xe mới nhất dùng public policy Phase8 hiện có; quyền preview riêng được giữ. Current ACTIVE OWNER/stale session/IDOR/XSS và GET chỉ đọc đã kiểm chứng. Focused **33/33**, full isolated **clean verify 1016/1016 JUnit, 0 failure/error/skip**, JAR; Chrome **152/152**, 36PNG ở1440/390/320; SQL invariants/provider0/cleanup đã xác nhận. **82/86 task**, còn **4** (`ducbinhnguyen07ars`3, `thanhmit`1; `huy69332`0). Phụ trách `ducbinhnguyen07ars`, reviewer `thanhmit`; agent review không thay human review. **P8.8/P4.2-A live vẫn pending**. Tiếp theo **P5.8 → P5.9**, không đóng P5.9 khi gate thật chưa đạt. Bảo toàn P5.5/P5.6 chưa commit, phase0/config/seed/uploads/DB ứng dụng/refs/index; không migrate/bật flags/provider/scheduler/restart8080/chuyển tiền thật/stage/commit/push/merge. [Bàn giao](docs/plans/phase-5-batch-5-handoff.md).

Ứng dụng thuê xe dùng Java21, Spring Boot, Thymeleaf và MySQL8. Phase0–3/P4.1 là nền lịch sử; nền tài chính offline P4.2–P4.11, queue Phase7, rental30/70 P6.1–P6.13, gói Owner P8.1–P8.7 và Admin/profile/audit P5.1–P5.8 đã được kiểm chứng local. P5.9 đạt5/6 checklist local; P5.9/P8.8/P4.2-A còn gate human/migration/provider/giao dịch thật nên chưa đóng. Workflow booking mới mặc định tắt; DB ứng dụng chưa được migrate hoặc kích hoạt trong đợt này. Xem bàn giao hiện hành ở checkpoint đầu trang.

## Chạy local

Chạy các lệnh từ thư mục gốc dự án:

1. Sao chép cấu hình mẫu và điền thông tin MySQL:

   ```powershell
   Copy-Item application-local.properties.example application-local.properties
   ```

   Sửa mật khẩu trong `application-local.properties`. File này bị Git bỏ qua; không đưa mật khẩu thật vào cấu hình mẫu hoặc `src/main/resources/application.properties`.

2. Tạo database và dữ liệu mẫu nếu máy chưa có DB:

   ```powershell
   mysql -h localhost -P 3306 -u root -p --execute="source scriptDB.sql"
   ```

   Nếu `mysql` chưa có trong PATH, dùng đường dẫn đầy đủ tới `mysql.exe`. Hibernate dùng `ddl-auto=none`; schema do `scriptDB.sql` quản lý. Giữ sáu ảnh `uploads/seed-car-1.png` đến `seed-car-6.png` cùng dữ liệu mẫu.

   Với DB đã có dữ liệu: bootstrap không ALTER bảng cũ. Bản code từ P6.1 cần các cột/bảng rental mới trước khi khởi động; thực hiện backup, diễn tập và migration theo [hướng dẫn vận hành](docs/migrations/README.md), không chạy bootstrap để sửa DB hiện hữu. Phiên implement chỉ kiểm chứng trên DB tạm.

3. Khởi động:

   ```powershell
   .\mvnw.cmd spring-boot:run
   ```

Mở http://localhost:8080/cars để tìm xe và xem chi tiết; danh sách chỉ hiển thị xe APPROVED+ACTIVE. Tài khoản demo và mật khẩu nằm tại [SDD §7.7](docs/SDD.md#77-dữ-liệu-mẫu-và-cách-chạy-script). Sau đăng nhập Owner, mở http://localhost:8080/owner/cars để thêm/sửa xe, quản lý ảnh và ẩn/đăng lại. Sau đăng nhập Admin, mở http://localhost:8080/admin/cars để lọc danh sách, duyệt/từ chối xe chờ duyệt và quản lý trạng thái đăng tin. Owner sở hữu/Admin còn ACTIVE có thể xem trước xe riêng tư; người khác nhận 404.

Admin có nút **Duyệt xe** trên header trang chủ/tìm xe (desktop và mobile), dẫn tới `/admin/cars`. Chọn **Kiểm tra hồ sơ** ở dòng xe để mở trang chi tiết và phê duyệt/từ chối xe chờ duyệt.

Notification **P3.1–P3.3 đã triển khai**: sau login, bấm chuông trên header hoặc mở http://localhost:8080/notifications để xem thông báo của mình, đánh dấu từng mục/tất cả đã đọc. Badge cập nhật khi tải trang. Owner tạo xe → thông báo đến các Admin; Admin duyệt/từ chối → thông báo đến Owner, có link vào hồ sơ/form xe. Trang có phân trang, CSRF và kiểm tra quyền sở hữu; không realtime. Kết quả kiểm chứng tại [plan Phase 3 — đợt 1](docs/plans/phase-3-booking.md#kết-quả-verify--đợt-1-p31p33-2026-10-11).

Booking backend **P3.4–P3.5 đã triển khai**: mapping bảng có sẵn, kiểm tra CUSTOMER/ACTIVE, ngày và lịch trùng, chụp giá/địa chỉ và tính tiền ở server, tạo PENDING cùng thông báo Owner. Khóa MySQL theo D-13 chặn hai yêu cầu đặt trùng cả khi transaction giữ snapshot cũ; lỗi chờ khóa trả 409 tiếng Việt. Form/detail/list đã nối trong đợt 3 bên dưới. Kết quả 149 assertions Spring/MySQL và 16 kiểm tra HTTP tại [plan Phase 3 — đợt 2](docs/plans/phase-3-booking.md#kết-quả-verify--đợt-2-p34p35-2026-10-11).

Booking UI **P3.6 → P4.1 → P3.7 đã triển khai**: Customer đặt xe → `/bookings/{id}`; xem danh sách ở `/bookings` hoặc **Đơn thuê** trên header. Giá/địa chỉ snapshot và trạng thái PAID thật được hiển thị. Owner sở hữu xe/Admin có thể mở generic detail từ BOOKING notification; Customer/Owner khác nhận 404. Nút cancel và Owner confirm/reject đã nối ở đợt 4 bên dưới; payment UI/history chi tiết thuộc Phase 4. Kết quả lịch sử đợt 3: 118 assertions Spring/MySQL/MVC, 149 hồi quy backend, 117 HTTP/MySQL và 47 browser checks; DB/ảnh gốc giữ nguyên.

**Cập nhật tìm xe theo ngày (2026-10-17, D-26):** tại `/cars`, chọn **Ngày nhận xe / Ngày trả xe** trong nhóm **Thời gian thuê** ở đầu sidebar → **Áp dụng bộ lọc** → chỉ còn xe trống suốt khoảng thuê. Bỏ trống cả hai ngày để xem tất cả xe công khai; chọn thiếu/sai ngày được báo ngay tại bộ lọc và giữ dữ liệu. Ngày đi theo phân trang/sắp xếp → **Xem chi tiết** → **Đặt thuê xe**, điền sẵn vào form và vẫn cho sửa. Phần **Kiểm tra khả dụng** riêng ở chi tiết đã bỏ. Kết quả tìm kiếm không giữ chỗ; backend kiểm tra lại lịch dưới khóa khi gửi đơn. Lỗi tạo đơn giữ ngày/ghi chú trên form. PENDING/CONFIRMED chặn lịch; hai khoảng chỉ chạm biên không trùng. Không đổi schema DB.

Booking transitions **P3.8–P3.10 đã triển khai**: Customer mở `/bookings/{id}` → **Hủy đơn thuê** cho PENDING/CONFIRMED; payment PAID được REFUNDED cùng transaction và notification, chỉ mô phỏng. Owner mở `/owner/bookings` hoặc **Đơn thuê** trong sidebar trang xe → **Xem chi tiết** → **Xác nhận đơn thuê** / **Từ chối đơn thuê** (nhập lý do 1–500 ký tự) khi PENDING. Từ BOOKING notification, Owner có link **Quản lý đơn thuê** trên generic detail. Khóa DB và current read chặn xử lý trạng thái cũ khi thao tác đồng thời. Kiểm chứng: 232 Spring/MySQL/MVC, 118 hồi quy, 139 HTTP/MySQL, 57 browser + 11 DB checks; baseline DB/ảnh giữ nguyên.

Booking hoàn tất/xóa xe **P3.11–P3.13 đã triển khai**: Owner mở `/owner/bookings/{id}` → **Hoàn tất đơn thuê** khi CONFIRMED và ngày trả xe đã qua (hôm nay chưa được, không cần PAID). Mở `/owner/cars` → icon **Xóa** để xóa vĩnh viễn xe chưa từng có đơn; xe đã có lịch sử thuê ở bất kỳ trạng thái nào phải dùng **Ẩn xe**. Xóa ảnh chỉ sau commit, rollback giữ ảnh; kiểm chứng đồng thời đặt/ẩn/xóa và upload/xóa không để dữ liệu hoặc file mồ côi. Kết quả: 192 Spring/MySQL/MVC, 149+118+232 hồi quy, 225 HTTP/MySQL, 65+6 browser, 5 browser DB checks; timeout/deadlock thật rollback+409, review Approved, DB/sáu ảnh seed nguyên vẹn. [Plan Phase 3 — kết quả đợt 5](docs/plans/phase-3-booking.md#kết-quả-verify--đợt-5-p311p313-2026-10-11).

Admin dashboard và khu vực `/profile` đã triển khai local; hồ sơ cho xem/sửa họ tên, điện thoại và đổi mật khẩu. Sau sửa tên, đăng nhập lại để cập nhật menu; đổi mật khẩu giữ policy session hiện hành. `/owner/dashboard` đã triển khai local, trang chủ giới hạn8xe mới nhất theo public policy. Tiến độ **83/86**, còn3; P5.8 đã đóng, P5.9 local đạt5/6; **P8.8/P4.2-A live vẫn pending**, xem [roadmap](docs/plans/README.md) và [bàn giao P5.8/P5.9 local](docs/plans/phase-5-batch-6-handoff.md). Các POST xe/booking/ảnh và nháp biên bản đi qua queue trước service transaction; GET và tạo xe mới bypass. Queue đầy hoặc hết 10 giây chờ trước chạy trả 503 tiếng Việt; tác vụ đã chạy đợi kết quả DB với transaction timeout 30 giây, vẫn giữ khóa DB và handler 409. Queue chỉ RAM, trong một tiến trình. Danh sách xe nổi bật trên trang chủ thuộc P5.7. Tài nguyên giao diện bên ngoài cần internet. Kết quả kiểm chứng và giới hạn chức năng xe tại [plan Phase 2](docs/plans/phase-2-car.md).

## Kiểm thử tìm xe theo ngày

Với MySQL và cấu hình local như phần chạy ứng dụng, chạy:

```powershell
.\mvnw.cmd clean test
```

`src/test/java/com/carrent/car/CarDateSearchIntegrationTest.java` có 34 test trực tiếp Spring/MySQL/MVC, gồm trùng lịch/trạng thái/biên ngày, lọc trước phân trang, ngày thiếu/sai, chuyển ngày tới đặt thuê, sửa ngày, giữ dữ liệu khi lỗi và phân quyền/CSRF. Fixture đọc được rollback; fixture POST qua queue phải commit trước để worker nhìn thấy rồi cleanup đúng ID sau test. Không tạo hoặc đổi schema. Dùng build sạch để tránh dùng lại `.class` do trình biên dịch nền IDE ghi vào `target`.

## Kiểm thử lõi queue theo carId

```powershell
.\mvnw.cmd '-Dtest=CarCommandExecutorTest' test
```

19 test không cần DB: FIFO, song song giữa xe, giới hạn 50/500 tác vụ chờ, timeout trước chạy, interruption, shutdown, trả nguyên lỗi và đua enqueue/cleanup. `CarQueueHttpIntegrationTest` thêm 41 test Spring/MySQL/MVC cho 14 route, queue đầy/timeout, reads bypass, quyền sau khi chờ, confirm/cancel và rollback booking/payment/notification khi lỗi. Xóa ảnh kiểm tra dọn file sau commit.

`CarCommandTransactionTest` thêm 9 test thật cho transaction khi chờ/chạy, commit/rollback trước trả kết quả và trước lệnh kế tiếp, hai kết nối bỏ qua queue vẫn chống đặt trùng, current read với snapshot/entity cũ và chặn enqueue trong transaction đang khóa xe. Các ca chờ khóa cần quyền đọc `performance_schema.data_lock_waits`, `data_locks`, `threads` của MySQL 8.

Chạy toàn bộ bằng `.\mvnw.cmd '-Dmaven.compiler.useIncrementalCompilation=false' clean verify` cần MySQL local. Checkpoint P7.4: **103 test đạt** (19 + 34 + 41 + 9), không đổi schema/seed hoặc dependency; dữ liệu/ảnh seed trước và sau kiểm chứng giữ nguyên. Xem [plan Phase 7](docs/plans/phase-7-car-queue.md); gate local Phase 7 hoàn tất và contract worker tài chính đã ghi. Inbox/retry/restart và kênh tiền thật sẽ được triển khai/kiểm chứng tại Phase 4.

## Tài liệu và file local

- [SDD](docs/SDD.md), [quyết định](docs/decisions.md), [plans](docs/plans/) và [prototype Stitch](docs/design/stitch/README.md).
- **Phase 6:** [kế hoạch hiện hành gồm 13 task](docs/plans/phase-6-inspection.md). P6.1 đã hoàn thành migration/bootstrap và mapping Booking/inspection (407/407 test toàn bộ đạt); P6.2 đã hoàn thành policy/guard (full 456 test đạt); P6.3 đã hoàn thành nháp/ảnh private với 518 test toàn bộ và 24 kiểm tra trình duyệt đạt; submit/tích hợp rental từ P6.4 trở đi chưa triển khai. Workflow đích: BEFORE → trả trước 30% → Owner xác nhận hợp đồng/giao xe → AFTER/Owner nhận lại → 70% → tự hoàn tất. DB ứng dụng vẫn 7 bảng, chưa migration/kích hoạt workflow mới.
- `CarRent.md` là bản thảo cũ giữ để tham khảo; SDD hiện tại mô tả yêu cầu đang áp dụng.
- `.scratch/` và `.superpowers/` giữ script, ảnh và biên bản kiểm tra ở local, bị Git bỏ qua. Bản clone mới không chứa các bằng chứng local được tham chiếu trong plan.
- `target/`, cache, log, cấu hình IDE và ảnh upload runtime bị Git bỏ qua; sáu ảnh seed vẫn đưa vào repo.
- Mật khẩu trong `scriptDB.sql` là tài khoản demo local. Đổi mật khẩu demo trước khi công khai ứng dụng.

**P6.3 runtime:** form/detail qua `/bookings/{id}/inspections/{BEFORE|AFTER}/draft` cho booking workflow mới trong DB đã migrate; GET không tạo report. BEFORE chỉ Owner, AFTER chỉ Customer sau giao xe; draft không chia sẻ cho counterpart/Admin. Ảnh private tại `private-uploads/inspections` (config server `carrent.inspection.storage-root`), không public qua `/uploads`; cần bảo toàn thư mục khi triển khai. Chưa có CTA/timeline booking (P6.12) hoặc nút gửi (P6.4/P6.8); booking ứng dụng hiện vẫn LEGACY/false. [Checkpoint P6.3](docs/plans/phase-6-inspection.md#checkpoint-p63--2026-11-01).
