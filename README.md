# CarRent

Ứng dụng thuê xe dùng Java 21, Spring Boot, Thymeleaf và MySQL 8. Phase 1 (đăng nhập, đăng ký, phân quyền) đã hoàn tất. Phase 2 đã triển khai P2.1–P2.14: Owner tạo/sửa xe, quản lý ảnh và ẩn/đăng lại; Admin lọc danh sách, duyệt/từ chối và ẩn/đăng lại; public tìm xe có lọc/sắp xếp/phân trang và xem chi tiết. Phase 3–5 chưa triển khai.

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

Redirect `/owner/dashboard` và `/admin/dashboard`, profile, notifications và đặt thuê vẫn thuộc phase sau, có thể trả 404; dùng các route trực tiếp ở trên để test Phase 2. Lịch khả dụng/đặt thuê thuộc Phase 3; danh sách xe nổi bật trên trang chủ thuộc P5.8. Tài nguyên giao diện bên ngoài cần internet. Kết quả kiểm chứng và giới hạn tại [plan Phase 2](docs/plans/phase-2-car.md).

## Tài liệu và file local

- [SDD](docs/SDD.md), [quyết định](docs/decisions.md), [plans](docs/plans/) và [prototype Stitch](docs/design/stitch/README.md).
- `CarRent.md` là bản thảo cũ giữ để tham khảo; SDD hiện tại mô tả yêu cầu đang áp dụng.
- `.scratch/` và `.superpowers/` giữ script, ảnh và biên bản kiểm tra ở local, bị Git bỏ qua. Bản clone mới không chứa các bằng chứng local được tham chiếu trong plan.
- `target/`, cache, log, cấu hình IDE và ảnh upload runtime bị Git bỏ qua; sáu ảnh seed vẫn đưa vào repo.
- Mật khẩu trong `scriptDB.sql` là tài khoản demo local. Đổi mật khẩu demo trước khi công khai ứng dụng.
