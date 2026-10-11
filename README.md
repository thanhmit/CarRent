# CarRent

Ứng dụng thuê xe dùng Java 21, Spring Boot, Thymeleaf và MySQL 8. Phase 1 (đăng nhập, đăng ký, phân quyền) đã hoàn tất. Phase 2 đã triển khai P2.1–P2.14: Owner tạo/sửa xe, quản lý ảnh và ẩn/đăng lại; Admin lọc danh sách, duyệt/từ chối và ẩn/đăng lại; public tìm xe có lọc/sắp xếp/phân trang và xem chi tiết. Phase 3 đã hoàn tất P3.1–P3.13: notification, booking toàn vòng đời (đặt/xác nhận/từ chối/hủy/hoàn tất), hoàn tiền giả lập và xóa xe an toàn theo lịch sử thuê; P4.1 Payment mapping/repository đã có. Tiếp theo Phase 4 từ P4.2 (payment service/UI).

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

3. Khởi động:

   ```powershell
   .\mvnw.cmd spring-boot:run
   ```

Mở http://localhost:8080/cars để tìm xe và xem chi tiết; danh sách chỉ hiển thị xe APPROVED+ACTIVE. Tài khoản demo và mật khẩu nằm tại [SDD §7.7](docs/SDD.md#77-dữ-liệu-mẫu-và-cách-chạy-script). Sau đăng nhập Owner, mở http://localhost:8080/owner/cars để thêm/sửa xe, quản lý ảnh và ẩn/đăng lại. Sau đăng nhập Admin, mở http://localhost:8080/admin/cars để lọc danh sách, duyệt/từ chối xe chờ duyệt và quản lý trạng thái đăng tin. Owner sở hữu/Admin còn ACTIVE có thể xem trước xe riêng tư; người khác nhận 404.

Admin có nút **Duyệt xe** trên header trang chủ/tìm xe (desktop và mobile), dẫn tới `/admin/cars`. Chọn **Kiểm tra hồ sơ** ở dòng xe để mở trang chi tiết và phê duyệt/từ chối xe chờ duyệt.

Notification **P3.1–P3.3 đã triển khai**: sau login, bấm chuông trên header hoặc mở http://localhost:8080/notifications để xem thông báo của mình, đánh dấu từng mục/tất cả đã đọc. Badge cập nhật khi tải trang. Owner tạo xe → thông báo đến các Admin; Admin duyệt/từ chối → thông báo đến Owner, có link vào hồ sơ/form xe. Trang có phân trang, CSRF và kiểm tra quyền sở hữu; không realtime. Kết quả kiểm chứng tại [plan Phase 3 — đợt 1](docs/plans/phase-3-booking.md#kết-quả-verify--đợt-1-p31p33-2026-10-11).

Booking backend **P3.4–P3.5 đã triển khai**: mapping bảng có sẵn, kiểm tra CUSTOMER/ACTIVE, ngày và lịch trùng, chụp giá/địa chỉ và tính tiền ở server, tạo PENDING cùng thông báo Owner. Khóa MySQL theo D-13 chặn hai yêu cầu đặt trùng cả khi transaction giữ snapshot cũ; lỗi chờ khóa trả 409 tiếng Việt. Form/detail/list đã nối trong đợt 3 bên dưới. Kết quả 149 assertions Spring/MySQL và 16 kiểm tra HTTP tại [plan Phase 3 — đợt 2](docs/plans/phase-3-booking.md#kết-quả-verify--đợt-2-p34p35-2026-10-11).

Booking UI **P3.6 → P4.1 → P3.7 đã triển khai**: đăng nhập Customer → `/cars` → chi tiết xe → chọn ngày **Kiểm tra khả dụng** hoặc **Đặt xe** → `/bookings/new?carId=<id>` → submit → `/bookings/{id}`. Xem danh sách ở `/bookings` hoặc **Đơn thuê** trên header. Availability không giữ chỗ; server kiểm tra lại khi submit. Giá/địa chỉ snapshot và trạng thái PAID thật được hiển thị. Owner sở hữu xe/Admin có thể mở generic detail từ BOOKING notification; Customer/Owner khác nhận 404. Nút cancel và Owner confirm/reject đã nối ở đợt 4 bên dưới; payment UI/history chi tiết thuộc Phase 4. Kết quả đợt 3: 118 assertions Spring/MySQL/MVC, 149 hồi quy backend, 117 HTTP/MySQL và 47 browser checks; DB/ảnh gốc giữ nguyên.

Booking transitions **P3.8–P3.10 đã triển khai**: Customer mở `/bookings/{id}` → **Hủy đơn thuê** cho PENDING/CONFIRMED; payment PAID được REFUNDED cùng transaction và notification, chỉ mô phỏng. Owner mở `/owner/bookings` hoặc **Đơn thuê** trong sidebar trang xe → **Xem chi tiết** → **Xác nhận đơn thuê** / **Từ chối đơn thuê** (nhập lý do 1–500 ký tự) khi PENDING. Từ BOOKING notification, Owner có link **Quản lý đơn thuê** trên generic detail. Khóa DB và current read chặn xử lý trạng thái cũ khi thao tác đồng thời. Kiểm chứng: 232 Spring/MySQL/MVC, 118 hồi quy, 139 HTTP/MySQL, 57 browser + 11 DB checks; baseline DB/ảnh giữ nguyên.

Booking hoàn tất/xóa xe **P3.11–P3.13 đã triển khai**: Owner mở `/owner/bookings/{id}` → **Hoàn tất đơn thuê** khi CONFIRMED và ngày trả xe đã qua (hôm nay chưa được, không cần PAID). Mở `/owner/cars` → icon **Xóa** để xóa vĩnh viễn xe chưa từng có đơn; xe đã có lịch sử thuê ở bất kỳ trạng thái nào phải dùng **Ẩn xe**. Xóa ảnh chỉ sau commit, rollback giữ ảnh; kiểm chứng đồng thời đặt/ẩn/xóa và upload/xóa không để dữ liệu hoặc file mồ côi. Kết quả: 192 Spring/MySQL/MVC, 149+118+232 hồi quy, 225 HTTP/MySQL, 65+6 browser, 5 browser DB checks; timeout/deadlock thật rollback+409, review Approved, DB/sáu ảnh seed nguyên vẹn. [Plan Phase 3 — kết quả đợt 5](docs/plans/phase-3-booking.md#kết-quả-verify--đợt-5-p311p313-2026-10-11).

Redirect `/owner/dashboard` và `/admin/dashboard`, profile vẫn thuộc các đợt sau, có thể trả 404; dùng các route trực tiếp ở trên để test. **Phase 3 hoàn tất; tiếp theo Phase 4 từ P4.2** (P4.1 đã có, không làm lại). Danh sách xe nổi bật trên trang chủ thuộc P5.8. Tài nguyên giao diện bên ngoài cần internet. Kết quả kiểm chứng và giới hạn chức năng xe tại [plan Phase 2](docs/plans/phase-2-car.md).

## Tài liệu và file local

- [SDD](docs/SDD.md), [quyết định](docs/decisions.md), [plans](docs/plans/) và [prototype Stitch](docs/design/stitch/README.md).
- `CarRent.md` là bản thảo cũ giữ để tham khảo; SDD hiện tại mô tả yêu cầu đang áp dụng.
- `.scratch/` và `.superpowers/` giữ script, ảnh và biên bản kiểm tra ở local, bị Git bỏ qua. Bản clone mới không chứa các bằng chứng local được tham chiếu trong plan.
- `target/`, cache, log, cấu hình IDE và ảnh upload runtime bị Git bỏ qua; sáu ảnh seed vẫn đưa vào repo.
- Mật khẩu trong `scriptDB.sql` là tài khoản demo local. Đổi mật khẩu demo trước khi công khai ứng dụng.
