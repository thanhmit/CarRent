# CarRent — Database bootstrap

**Goal:** Tạo schema và seed đầy đủ trước Phase 1 theo yêu cầu ngày 2026-10-10.
**Spec:** `docs/SDD.md` §7; schema SQL tại `scriptDB.sql` là nguồn chân lý.
**Constraints:** MySQL 8.0.45, InnoDB, utf8mb4_unicode_ci, 7 bảng, không xóa/ghi đè dữ liệu hiện có; không git, không unit test Java.

## Tasks

- [x] Kiểm tra DB trống và viết kiểm tra SQL trước khi tạo schema: `.scratch/db/verify-db.ps1` báo thiếu 7 bảng.
- [x] Viết và chạy `scriptDB.sql`: tạo 7 bảng đúng tên cột/enum/FK, seed 7 users, 6 categories, 6 cars, 6 images, 6 bookings, 6 payments, 10 notifications. Password BCrypt; có tài khoản ACTIVE/INACTIVE/BANNED và dữ liệu thanh toán retry/refund.
- [x] Kiểm tra cột, FK, index, CHECK, dữ liệu nghiệp vụ; thử INSERT sai FK, trùng định danh, sai ngày/tiền và rollback. Chạy lại script, so sánh snapshot: không thay dữ liệu.
- [x] Cập nhật SDD, decisions, các plan phase và `application.properties`: `ddl-auto=none`, entity map DB sẵn có, không seed lại từ Java. Compile và boot thành công; bằng chứng tại SDD §7.8. Review độc lập phát hiện và đã sửa phụ thuộc P4.1 cần chạy trước P3.7/P3.8 vì có PAID seed sẵn.
