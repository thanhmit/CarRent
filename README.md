# CarRent

Ứng dụng thuê xe dùng Java 21, Spring Boot, Thymeleaf và MySQL 8. Phase 1 (đăng nhập, đăng ký, phân quyền) đã hoàn tất. Phase 2 đã triển khai P2.1–P2.14: Owner tạo/sửa xe, quản lý ảnh và ẩn/đăng lại; Admin lọc danh sách, duyệt/từ chối và ẩn/đăng lại; public tìm xe có lọc/sắp xếp/phân trang và xem chi tiết. Phase 3 đã hoàn tất P3.1–P3.13: notification, booking toàn vòng đời (đặt/xác nhận/từ chối/hủy/hoàn tất), hoàn tiền giả lập và xóa xe an toàn theo lịch sử thuê; P4.1 Payment mapping/repository đã có. Phase 7/P7.1–P7.4 đã hoàn tất queue/transaction. Nền tài chính offline P4.2–P4.11 và schema/model P6.1 đã hoàn thành; P6.2 policy/guard và P6.3 nháp biên bản/ảnh private đã hoàn thành (518 test toàn bộ và 24 kiểm tra trình duyệt đạt); P4.2-A/provider live tạm hoãn, tiếp theo P6.4. Chưa bật workflow thuê 30/70 trên DB ứng dụng.

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

Redirect `/owner/dashboard` và `/admin/dashboard`, profile vẫn thuộc các đợt sau, có thể trả 404; dùng các route trực tiếp ở trên để test. **Phase 3/P4.1, Phase 7, nền tài chính offline và P6.1–P6.3 đã hoàn tất; tiếp theo P6.4**, rồi theo [roadmap 7 → 4 → 6 → 8 → 5](docs/plans/README.md). Các POST xe/booking/ảnh và nháp biên bản đi qua queue trước service transaction; GET và tạo xe mới bypass. Queue đầy hoặc hết 10 giây chờ trước chạy trả 503 tiếng Việt; tác vụ đã chạy đợi kết quả DB với transaction timeout 30 giây, vẫn giữ khóa DB và handler 409. Queue chỉ RAM, trong một tiến trình. Danh sách xe nổi bật trên trang chủ thuộc P5.7. Tài nguyên giao diện bên ngoài cần internet. Kết quả kiểm chứng và giới hạn chức năng xe tại [plan Phase 2](docs/plans/phase-2-car.md).

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
