# Phase5 đợt4 — P5.5 + P5.6 Profile và đổi mật khẩu

**Checkpoint P5.5 + P5.6 — 2026-11-04:** hoàn thành **3/3 + 3/3 checklist local**: profile của tài khoản hiện tại, chỉ sửa tên/điện thoại; đổi mật khẩu kiểm tra BCrypt hash DB mới nhất, tối thiểu6ký tự/tối đa72byte UTF-8, form lỗi không giữ mật khẩu. Kiểm tra ACTIVE/role hiện tại dưới khóa User; chống IDOR/mass assignment/CSRF/XSS và ghi đè hash khi đồng thời. Navbar tên cache được thông báo đăng nhập lại; giữ policy session/logout hiện hành. Focused **12/12**, full isolated **clean verify 1005/1005 JUnit, 0 failure/error/skip**, JAR; Chrome **113/113**, 24PNG ở1440/390/320; SQL 15khoảng/20bảng, provider0, cleanup đã xác nhận. **81/86 task**, còn **5** (`ducbinhnguyen07ars`4, `thanhmit`1; `huy69332`0). Phụ trách `huy69332`, reviewer `thanhmit`; agent review không thay human review. P8.8/P4.2-A live vẫn pending. Dừng sau đợt này; tiếp theo **P5.7 riêng của ducbinhnguyen07ars**. Bảo toàn phase0/config/seed/uploads/DB ứng dụng; không migrate/bật flags/provider/scheduler/restart8080/chuyển tiền thật/stage/commit/push/merge.

## Baseline và phạm vi

- Branch `feat/phase5-profile-password`, HEAD/main/origin-main baseline `b829b059a2f1af3c9ba79629759b608036470620`; không stage/commit/push/merge đợt này. Baseline Git trong handoff đợt3 là lịch sử trước publish; P8/P5 prerequisites đã có trong main.
- `docs/plans/phase-0-init.md` dirty từ đầu, giữ nguyên byte và ngoài phạm vi. Config/seed/uploads và DB ứng dụng7bảng giữ nguyên; schema/port kiểm chứng riêng đã dọn.
- Phụ trách `huy69332`, reviewer được giao `thanhmit`; hai agent review không có blocker, chưa có human review mới. Git author/login vẫn `ducbinhnguyen07ars`, chưa đổi hoặc ghi nhận tác giả commit cho hai task này.

## Hoàn tất local: P5.5 3/3, P5.6 3/3

- UserService duy nhất; profile scoped principal, chỉ sửa fullName/phone, DTO không hash. Read/validation/mutation đều chặn tài khoản không ACTIVE hoặc role principal cũ. Form lỗi escape/giữ input an toàn; thành công nhắc đăng nhập lại để đổi tên menu.
- Password dùng BCrypt encoder hiện có, kiểm tra hash DB dưới khóa; new/confirm/min6/72byte UTF-8. Error model/form/rejectedValue không giữ mật khẩu; chỉ hash được lưu, không sửa seed password.
- Khóa row User theo pattern Admin status chống lost updates và old-password reuse đồng thời; chờ khóa rồi ban bị403. Không đổi chính sách session hoặc thu hồi mọi phiên. GET logout404 chỉ đọc; POST logout CSRF và real login mật khẩu mới/cũ được kiểm chứng.
- Focused riêng mỗi task6/6, full clean verify/JAR và Chrome1440/390/320. SQL so sánh tất cả bảng/trường bằng hash: GET/lỗi/auth không mutation, success chỉ customer fixture95513 name/phone/hash/updated_at. Provider0; endpoint nghiệp vụ không mock, tài nguyên CSS/fonts bên ngoài phục vụ local.

## Evidence và giới hạn

- `.scratch/phase5/p55-p56`: `verification-summary.json`, `profile-read-red.log`, `profile-read-green.log`, `profile-edit-red.log`, `profile-edit-green.log`, `password-red.log`, `profile-password-green-2.log`, `full-clean-verify.log`, `focused-reports`, `build`, source/protected manifests, `review.md`, `standards-review.md`, `implementation-report.md`, `browser/report.md` và SQL/PNG/cleanup.
- `profile-password-green.log` là lượt lỗi binder và giả định GET logout200; không coi là pass. Browser attempt trước rebuild được giữ riêng dưới `browser/attempts`, không gộp vào kết quả cuối. Không ghi đè evidence cũ.
- Local/fake verification không chứng minh provider/live hoặc thay human review. Không migrate/reset/seed DB ứng dụng, bật flags/provider/scheduler ứng dụng, restart8080, chuyển tiền thật hay Git publication.

## Bàn giao tiếp theo

**P5.7 riêng — Dashboard Owner và trang chủ**, phụ trách `ducbinhnguyen07ars`, reviewer `thanhmit`. Đọc checklist P5.7 và scope doanh thu/hoàn/nợ theo Owner, dùng public policy Phase8 hiện có; không mở P5.8/P5.9 trong cùng đợt.

81/86,còn5: duc4(P8.8/P5.7–P5.9),huy0,thanh1(P4.2-A). P8.8 vẫn2/5local,3gatepending; P4.2-A live pending. Dừng sau P5.6.
