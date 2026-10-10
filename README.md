# CarRent

Ứng dụng thuê xe dùng Java 21, Spring Boot, Thymeleaf và MySQL 8. Phase 1 (đăng nhập, đăng ký, phân quyền) đã hoàn tất; Phase 2–5 chưa triển khai.

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

Mở http://localhost:8080/login. Tài khoản demo và mật khẩu nằm tại [SDD §7.7](docs/SDD.md#77-dữ-liệu-mẫu-và-cách-chạy-script). Trang đích xe/dashboard có thể trả 404 trước khi phase tương ứng hoàn tất; mở `/` để xem navbar sau đăng nhập. Tài nguyên giao diện bên ngoài cần internet.

## Tài liệu và file local

- [SDD](docs/SDD.md), [quyết định](docs/decisions.md), [plans](docs/plans/) và [prototype Stitch](docs/design/stitch/README.md).
- `CarRent.md` là bản thảo cũ giữ để tham khảo; SDD hiện tại mô tả yêu cầu đang áp dụng.
- `.scratch/` và `.superpowers/` giữ script, ảnh và biên bản kiểm tra ở local, bị Git bỏ qua. Bản clone mới không chứa các bằng chứng local được tham chiếu trong plan.
- `target/`, cache, log, cấu hình IDE và ảnh upload runtime bị Git bỏ qua; sáu ảnh seed vẫn đưa vào repo.
- Mật khẩu trong `scriptDB.sql` là tài khoản demo local. Đổi mật khẩu demo trước khi công khai ứng dụng.
