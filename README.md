# CarRent

Ứng dụng thuê xe dùng Java 21, Spring Boot 4.1, Thymeleaf và MySQL 8.

## Chạy dự án trên Windows

Double-click **[start.bat](start.bat)**. Launcher kiểm tra/cài môi trường còn
thiếu, chuẩn bị DB mới khi cần, build bằng Maven Wrapper, chạy app và ngrok.
Thiếu hoặc sai cấu hình sẽ được ghi vào `.local/SETUP-NEEDED.md` kèm hướng dẫn.
Các bước đăng nhập, OTP và cấp khóa dịch vụ do dev thực hiện trên dashboard.

- [Hướng dẫn chạy một click và lấy các trường cấu hình](docs/local-startup.md).
- [Cấu hình ngrok, ngân hàng và webhook SePay](docs/local-ngrok-sepay-setup.md).
- [Cấu hình nguồn chi payOS](docs/local-payos-setup.md).
- [Tra cứu người nhận và kiểm chứng hoàn tiền](docs/p42a-p88-operator-guide-2026-11-09.md).

```powershell
# Chỉ kiểm tra; không cài/build/start hoặc ghi DB.
.\start.bat -CheckOnly -NoOpen

# Chạy thủ công sau khi đã chuẩn bị cấu hình và DB.
.\mvnw.cmd spring-boot:run
```

Mở <http://localhost:8080/cars>. Cấu hình riêng nằm trong `.env` và
`application-local.properties`; mẫu tương ứng là `.env.example` và
`application-local.properties.example`. Không đưa secret hoặc log private lên Git.

`scriptDB.sql` dành cho DB mới. DB đã có dữ liệu phải backup và làm theo
[hướng dẫn migration](docs/migrations/README.md); Hibernate giữ `ddl-auto=none`.

## Tài liệu

- [Thiết kế hệ thống hiện hành](docs/SDD.md) và [quyết định kỹ thuật](docs/decisions.md).
- [Chỉ mục thiết kế, hợp đồng và phân công](docs/plans/README.md).
- [Luồng dữ liệu phần Huy](docs/analysis/huy-functional-data-flows-2026-11-09.md)
  và [phần thanhmit](docs/analysis/thanhmit-functional-data-flows-2026-11-09.md).
- [Bộ tài liệu bàn giao Word/Excel](tailieu/README.md).
- [Nhật ký sử dụng AI](ai-log.md): lưu bằng chứng lịch sử; các đường dẫn
  tới trace/fixture tạm trong nhật ký có thể đã được dọn.

Payment Settings S1/S2/S3 đã bị hủy. Cấu hình provider tiếp tục dùng properties
và environment. Việc build/chạy được app không xác nhận thanh toán thật;
refund `UNKNOWN` phải được truy vấn/đối soát, không tự gửi lại lệnh chi.

## Kiểm tra launcher

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/test-start-local.ps1
```

Kiểm thử MySQL/app riêng, điều kiện và lệnh build nằm trong
[hướng dẫn launcher](docs/local-startup.md#startup). Test Java dùng fixture
riêng theo tài liệu thiết kế; không chạy test/migration lên DB đang sử dụng.
