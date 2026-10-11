# CarRent — Hồ sơ quyết định (Decisions)

**Ngày chốt:** 2026-10-09
**Liên quan:** `CarRent.md` §16 (10 câu hỏi mở), `docs/SDD.md` v2.0
**Trạng thái:** Đã chốt cùng chủ dự án trong buổi brainstorm.

Quy ước: **D-xx** là số quyết định. "Mặc định §16" = chấp nhận đề xuất mặc định trong CarRent.md v1.0.

---

## Nhóm A — Chấp nhận nguyên bộ đề xuất §16

### D-01. Role schema: cột enum `users.role`
Không tạo bảng `roles` / `user_roles`. Mỗi user 1 role. Lý do: role không quản lý động qua UI, giảm 2 bảng và mapping JPA phức tạp. *(Mặc định §16)*

### D-02. Đăng ký Owner tự do
Owner tự đăng ký công khai; không cần Admin kích hoạt. Admin không được tự đăng ký — tài khoản admin tạo bằng seed. *(Mặc định §16)*

### D-03. Sửa Car đã duyệt không cần duyệt lại
Chỉ duyệt lần đầu (PENDING). Sau khi APPROVED, Owner sửa trực tiếp; Admin có thể ẩn xe (listing_status) nếu phát hiện vi phạm. Giảm phức tạp workflow, chấp nhận rủi ro nội dung sai sau sửa. *(Mặc định §16)*

### D-04. Booking PENDING chặn lịch
PENDING và CONFIRMED đều tính là conflict khi kiểm tra overlap (BR-05). Không có timeout giải phóng booking quá hạn — **giới hạn đã ghi nhận của MVP**. *(Mặc định §16)*

### D-05. Giá tính theo ngày lịch
`days = end - start` (≥1), `total = price_per_day × days`. Không phí lẻ giờ, không phụ phí. Số tiền BIGINT VND nguyên. *(Mặc định §16)*

### D-06. Payment chỉ khi CONFIRMED — nhiều lần thử, 1 lần PAID
Customer thanh toán sau khi Owner confirm. Payment FAILED được thử lại (bảng payments không unique booking_id); Service chặn khi đã có payment PAID. Mock payment có tùy chọn giả lập thất bại để test. *(Mặc định §16)*

### D-07. COMPLETED do Owner đánh dấu thủ công
Sau khi `end_date` đã qua, Owner bấm "Hoàn tất". Không auto-complete bằng scheduler — tránh nghiệp vụ bàn giao chưa định nghĩa. *(Mặc định §16)*

### D-08. Ưu tiên vô hiệu hóa, không xóa cứng dữ liệu có lịch sử
User không bao giờ xóa cứng (chỉ đổi status). Car chỉ xóa cứng khi không có booking nào tham chiếu; ngược lại deactivate. *(Mặc định §16)*

### D-09. Booking snapshot giá và địa điểm
`price_per_day_snapshot` + `pickup_address` lưu vào booking lúc tạo — sửa giá/địa điểm xe không làm đổi lịch sử booking. *(Người chuẩn hóa từ mặc định §16)*

### D-10. Thumbnail = ảnh có id nhỏ nhất
Không có cột `is_primary` trên `car_images`. Ảnh đầu tiên (id nhỏ nhất) tự làm thumbnail ở danh sách. **Lựa chọn của người dùng** — đơn giản hơn cho MVP.

### D-11. Location là cột text trên `cars` — không có bảng locations
`province` (bắt buộc) + `district` + `address_detail`. Không dropdown từ bảng, không quản lý location qua UI. **Lựa chọn của người dùng** — lọc theo province qua text match. Nhánh: booking nhận xe tại chỗ xe (D-15).

### D-12. Payment REFUNDED khi hủy booking đã PAID
Hủy booking CONFIRMED mà có payment PAID → payment chuyển REFUNDED (giả lập) + notification. *(Người chuẩn hóa từ §16)*

### D-13. Chống đặt trùng và cập nhật booking đồng thời bằng khóa DB
**Chủ dự án duyệt thay đổi ngày 2026-10-11:** thay quyết định cũ chấp nhận race bằng pessimistic locking trong transaction, không thêm cột/bảng/unique constraint.

- Tạo booking: khóa hàng Car bằng `PESSIMISTIC_WRITE` trước khi kiểm tra visibility, giá/địa điểm và lịch; đọc xung đột PENDING/CONFIRMED bằng locking read theo trạng thái hiện tại, rồi insert booking + notification trong cùng transaction. Hai request trùng lịch trên cùng xe phải chỉ tạo được một booking. `@Transactional` và truy vấn count thông thường không đủ khi transaction đã có snapshot cũ.
- Mutator booking: dùng thứ tự khóa **Car → Booking → Payment** khi cần; sau khi giữ khóa, đọc lại entity hiện tại và kiểm tra role/status/ownership/transition. Owner confirm/reject/complete, Customer cancel/refund và Payment Phase 4 tuân thủ cùng protocol, tránh ghi đè từ entity đã được quản lý nhưng cũ. Car delete cũng khóa Car và kiểm tra lịch sử hiện tại trước xóa.
- **Ghi nhận kỹ thuật đợt 4 (2026-10-11):** Hibernate có thể phát SELECT thường khi `refresh(..., PESSIMISTIC_WRITE)` trên entity đã có lock mode, đọc snapshot REPEATABLE READ cũ. Booking/Payment dùng targeted detach rồi query FOR UPDATE lại dưới khóa; flush thay đổi hợp lệ trước reload, không clear toàn context. Đã tái hiện RED duplicate cancel và GREEN bảy race transition/current Payment. Đây là cách thực hiện hợp đồng current read đã duyệt.
- **Ghi nhận kỹ thuật đợt 5 (2026-10-11):** Car hard-delete khóa/read toàn lịch sử và image membership hiện tại. Dùng `CarImageRepository.findByCarIdForUpdate` sau khóa Car; SELECT ảnh thường ở REPEATABLE READ có thể bỏ sót upload vừa commit, FK cascade xóa row nhưng bỏ lại file. Đã RED→GREEN snapshot ảnh và hai race upload/delete cả hai thứ tự; file chỉ dọn afterCommit. Complete/cancel và create/delete/hide, timeout1205/deadlock1213 thật đã verify rollback Booking/Payment/notify +409.
- Khả dụng trên UI là thông tin tại lúc đọc, không giữ chỗ; submit luôn kiểm tra lại dưới khóa. Chỉ PENDING/CONFIRMED chặn lịch; hai khoảng chỉ chạm ngày kết thúc/bắt đầu không trùng ở cả hai chiều.
- Kiểm chứng bằng hai transaction/kết nối MySQL độc lập: tạo trùng, confirm/reject/cancel tranh chấp, stale managed entity và tạo booking cùng lúc xóa/ẩn xe. Không dùng Java `synchronized` thay khóa DB. Chi phí: các mutation cùng xe phải chờ nhau; transaction giữ ngắn, lock timeout/deadlock trả lỗi có kiểm soát, không tạo booking/thông báo một phần.

**Cách triển khai Phase 3 đã duyệt:** 5 đợt — P3.1–P3.3; P3.4–P3.5; P3.6 → P4.1 → P3.7; P3.8–P3.10; P3.11–P3.13. P4.1 chỉ mapping/repository, các chức năng thanh toán còn lại theo Phase 4. Đầu ra và checkpoint từng đợt tại `plans/phase-3-booking.md`.

### D-14. Notification chuyển lên Phase 3, trước Booking
Vì booking tạo phải notify Owner ngay, notification phải có trước. Khác roadmap CarRent.md v1.0 (để Phase 4).

---

## Nhóm B — Quyết định từ brainstorm với người dùng

### D-15. Booking nhận xe tại địa điểm xe
Không chọn địa điểm nhận khác. Pickup = địa chỉ xe lúc đặt, lưu snapshot text vào booking. **Lựa chọn của người dùng.**

### D-16. Upload ảnh thật (không phải URL ngoài)
Owner upload file → app validate (jpg/jpeg/png/webp, ≤5MB, content-type image/*) → tên UUID + đuôi gốc → lưu vào `uploads/` ở gốc dự án (ngoài resources) → DB lưu web path `/uploads/<file>`. **Lựa chọn của người dùng.**

### D-17. UI tiếng Việt, code tiếng Anh
Label/menu/thông báo/trang lỗi tiếng Việt. Tên class/method/comment tiếng Anh. Không i18n bundle. **Lựa chọn của người dùng.**

### D-18. Giao diện dùng Tailwind CSS + design system từ prototype Stitch
Bản đầu chốt Bootstrap 5; **đổi thành Tailwind CSS (Play CDN) + design tokens Material Design 3** để khớp đúng prototype đã thiết kế trên Google Stitch — port HTML prototype sang Thymeleaf giữ nguyên class. Không Tailwind build step (Play CDN). Thêm `static/css/custom.css` chỉ cho tinh chỉnh nhỏ. **Lựa chọn của người dùng (đổi khi cập nhật prototype).**

### D-23. Prototype Stitch là nguồn chân lý cho UI
Prototype đã tải local vào `docs/design/stitch/` (hiện giữ 13 HTML + 13 screenshot sau khi dọn bản cũ theo lựa chọn của chủ dự án ngày 2026-10-10); design system + quy tắc port + mapping màn hình→template→task ở `docs/design/stitch/README.md` và SDD §8.5. Khi implement bất kỳ template nào, port từ file prototype tương ứng. Trang prototype không cover (notification, profile, admin dashboard, error pages, empty states) → dựng theo cùng design system, dùng layout A (public/customer) hoặc B (owner/admin). MCP server `stitch` đã cấu hình ở user scope để tải lại/reference khi cần. **Lựa chọn của người dùng.**

### D-19. Thư mục dự án & quy ước Git hiện tại
Gốc: `C:\Users\Admin\Documents\hoc\Car`. Code Spring Boot nằm tại gốc, docs trong `docs\`. Quy ước chưa dùng Git là lịch sử trước khi chủ dự án khởi tạo repo. Hiện giữ checkout/nhánh và thay đổi đang có; không tự stage/commit/push. `.scratch/`, `.superpowers/`, build, config local và ảnh runtime bị Git bỏ qua; giữ sáu ảnh seed trong repo theo lựa chọn chủ dự án. Không xóa bằng chứng local khi chốt đợt.

### D-20. Đăng nhập bằng email — email chỉ là chuỗi định danh
Username = email (cột `users.email`, unique). **Không cần email thật**: không validate định dạng `@`, không gửi thư, không xác thực email. Chỉ kiểm tra không trống + unique. Mặc định Spring Security form login. Session-based, CSRF bật. Không JWT. *(Chủ dự án chốt khi duyệt SDD.)*

### D-21. Admin tự seed mật khẩu đổi lần đầu
Tài khoản admin `admin@carrent.local` được seed BCrypt trong `scriptDB.sql` trước Phase 1 (D-24), mật khẩu local `CarRentAdmin@2026!`. Không tạo `DataSeeder` Java hoặc fallback `admin@123`; đổi mật khẩu qua P5.6 và trước khi công khai ứng dụng. Chạy lại SQL không ghi đè mật khẩu đã đổi.

### D-22. Task chia theo tầng, hạt mảnh + verify tự động
Plan sinh 55 task trên 6 phase (P0.1–P5.9). Mỗi task có mục tiêu/bước/ràng buộc/verify chạy được (compile → boot → curl → SQL → POST-flow CSRF; UI phức tạp thì kèm kịch bản duyệt web). **Lựa chọn của người dùng.**

### D-24. Tạo đầy đủ DB và seed trước Phase 1
Ngày 2026-10-10, chủ dự án yêu cầu provision ngay tất cả bảng và dữ liệu mẫu để các phase sau implement trên DB có sẵn. `scriptDB.sql` tại gốc là nguồn chân lý schema; SDD v2.1 §7 mô tả mapping. Hibernate `ddl-auto=none`, không tự tạo/sửa bảng; admin/categories do SQL seed, không seed lại bằng Java. Giữ 7 bảng và các enum đã chốt; bổ sung FK, CHECK và index tương ứng. Các quy tắc nhiều bảng vẫn kiểm tra tại Service. Script chạy lại giữ nguyên record hiện hữu; không có DROP/TRUNCATE. **Yêu cầu trực tiếp của người dùng.**

---

## Tóm tắt nhanh

| # | Quyết định |
|---|---|
| D-01 | Role = cột enum trên users |
| D-02 | Owner tự đăng ký; Admin seed |
| D-03 | Sửa xe không duyệt lại |
| D-04 | PENDING chặn lịch (không timeout) |
| D-05 | Giá = price_per_day × days |
| D-06 | Payment sau CONFIRMED, retry ok, 1 PAID |
| D-07 | COMPLETED thủ công bởi Owner |
| D-08 | Soft-disable thay vì xóa cứng |
| D-09 | Booking snapshot giá + địa điểm |
| D-10 | Thumbnail = ảnh id nhỏ nhất |
| D-11 | Location = text trên cars, không bảng riêng |
| D-12 | Hủy sau PAID → REFUNDED |
| D-13 | Khóa DB chống đặt trùng/chuyển trạng thái đồng thời; Phase 3 chia 5 đợt |
| D-14 | Notification ở Phase 3 |
| D-15 | Pickup tại chỗ xe |
| D-16 | Upload ảnh thật, UUID, uploads/ |
| D-17 | UI tiếng Việt, code tiếng Anh |
| D-18 | UI: Tailwind + design system Stitch |
| D-19 | Gốc Documents\hoc\Car, giữ repo/nhánh; không tự stage/commit/push |
| D-20 | Login bằng email |
| D-21 | Admin SQL seed, đổi mật khẩu trước khi công khai |
| D-22 | Task mảnh theo tầng + verify tự động |
| D-23 | Prototype Stitch là nguồn chân lý UI |
| D-24 | DB-first: scriptDB.sql + seed trước Phase 1, Hibernate không sửa schema |
