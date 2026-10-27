# CarRent — Phase 5: Admin và hoàn thiện — Implementation Plan

> **For agentic workers:** Dùng superpowers:executing-plans theo task; không tự stage/commit/push. Chỉ dùng subagent khi được yêu cầu.

**Ngày cập nhật:** 2026-10-27. **Trạng thái:** kế hoạch chưa triển khai; thực hiện cuối thứ tự 7 → 4 → 6 → 8 → 5.

**Goal:** Hoàn thiện dashboard/profile/quản lý tài khoản và màn hình theo dõi thanh toán/hoàn tiền đúng mô hình trung gian.

**Architecture:** AdminService gom read model thống kê, không tự quyết định thu/hoàn hoặc thay Owner xác nhận xe. Dashboard Admin tính phí gói; dashboard Owner tính thu/hoàn/nợ riêng của xe mình. Các action nghiệp vụ vẫn gọi service của Phase 4/6/8.

**Tech Stack:** Spring MVC/Thymeleaf, JPA query/read DTO, MySQL, session/BCrypt/CSRF hiện có.

**Spec:** [README](README.md), đặc biệt mục 2–4 và 6.4; giữ FR-02/FR-03/FR-15 hiện có, thay cách tính “SUM mọi payment PAID = doanh thu Admin” của plan cũ.

**Phân công:** `huy69332` phụ trách P5.5–P5.6; `ducbinhnguyen07ars` phụ trách P5.1–P5.4 và P5.7–P5.9; `thanhmit` review. Xem [phân công nhóm](team-assignment.md).

## Global Constraints

- Các phase tiền/rental/subscription phải có dữ liệu/hợp đồng trước khi chốt thống kê toàn hệ thống.
- Không tính tiền thuê Owner là doanh thu Admin; không tính receipt trùng/chưa phân bổ/đang chờ hoàn là phí gói đã bán.
- Nợ 70% và hoàn quá hạn chỉ cảnh báo, không khóa tài khoản hoặc chặn đặt/nhận đơn theo khoản nợ.
- Admin không được đổi trạng thái chính mình; user không tự đổi role/email qua form profile. Mọi POST có CSRF, GET chỉ đọc.
- UI tiếng Việt theo design system/prototype hiện có; không đưa provider secret hoặc implementation detail vào checkout.
- Không tự đăng nhập bằng mật khẩu hardcode trong plan; sử dụng tài khoản kiểm chứng do người vận hành cung cấp, không in secret.
- Áp dụng quyền, legacy và verification trong README; không đánh dấu cả hệ thống hoàn tất từ kiểm tra code tĩnh.

## Map file

Java tương đối `src/main/java/com/carrent/`:

- Create `admin/AdminService.java`, `AdminController.java`, `AdminUserController.java`, `AdminBookingController.java`, `AdminFinanceController.java` và DTO đọc.
- Create `owner/OwnerDashboardController.java`, `user/ProfileController.java`, `ProfileForm.java`, `ChangePasswordForm.java`; modify UserRepository và service user theo cấu trúc thực tế.
- Modify repositories payment/subscription/booking để có query có scope; không join nhiều bảng một-nhiều rồi SUM trùng.
- Create templates `admin/dashboard.html`, `admin/users.html`, `admin/user-detail.html`, `admin/bookings.html`, `admin/booking-detail.html`, `admin/finance.html`, `owner/dashboard.html`, `profile/view.html`, `profile/edit.html`, `profile/change-password.html`.
- Tests `src/test/java/com/carrent/admin/AdminRevenueTest.java`, `AdminPermissionsTest.java`; `user/ProfileSecurityTest.java`; `owner/OwnerRevenueTest.java`.

### Task P5.1 — Read model doanh thu và số liệu có scope

**Phụ trách:** `@ducbinhnguyen07ars` · **Review:** `@thanhmit`.

**Interface:** AdminService.getDashboardStats() trả DTO chứa counts, subscriptionRevenue, monthlySubscriptionRevenue, unmatchedReceipts, refundsPending/overdue và balanceDebtsOverdue. Không dùng DTO totalRevenue mơ hồ để gộp tiền thuê.

- [ ] Viết AdminRevenueTest: một gói 5.000, một tiền thuê 600.000, một khoản trả trùng 5.000 → doanh thu phí gói **5.000**, không phải 610.000.
- [ ] Query phí gói từ order đã applied; source collector đúng Admin:

```sql
SELECT COALESCE(SUM(price_snapshot), 0)
FROM subscription_orders
WHERE applied_at IS NOT NULL;
```

Query tháng theo applied_at trong múi giờ báo cáo Việt Nam. Gross cash nhận và cash đã hoàn dùng read model riêng; không trừ refund của receipt trùng từ tổng phí gói hợp lệ đã tính.
- [ ] Phân biệt tiền đã phân bổ, tiền chờ đối soát, nghĩa vụ hoàn và khoản đã hoàn thành công. Pending refund không được hiển thị đã trả cho người nhận.
- [ ] Không dùng SUM(legacy payments PAID) cho doanh thu Admin. Legacy hiển thị lịch sử giả lập có nhãn riêng.
- [ ] Chạy test với nhiều receipts/refunds một order để bắt lỗi join nhân dòng; SQL đọc trực tiếp phải khớp DTO.

### Task P5.2 — Admin dashboard và tài chính

**Phụ trách:** `@ducbinhnguyen07ars` · **Review:** `@thanhmit`.

- [ ] Dashboard counts user/car/booking từ DB, phí gói theo P5.1, tháng không có dữ liệu hiển thị 0; không hardcode baseline seed.
- [ ] AdminFinanceController GET danh sách nghĩa vụ/receipt/refund với filter trạng thái/quá hạn/chủ thể, phân trang và sort whitelist.
- [ ] Phân biệt ví Owner/Admin, waiting recipient/funds, processing, succeeded, review required; mask bank account trên list, chi tiết chỉ người có quyền.
- [ ] GET không gọi API chi hoặc gán paid. Chỉ hiển thị trạng thái/config health, không xây editor secret hay nút “đánh dấu đã nhận tiền” thủ công.
- [ ] Test Admin/Owner/Customer/anonymous, IDOR và XSS note/payload; error không lộ SQL/token/provider body nhạy cảm.

### Task P5.3 — Admin quản lý user

**Phụ trách:** `@ducbinhnguyen07ars` · **Review:** `@thanhmit`.

- [ ] GET /admin/users có q/role/status/page; Specification/filter hợp lệ, giữ filter qua pager.
- [ ] GET /admin/users/{id} có hồ sơ và số liệu liên quan có scope; POST status đổi ACTIVE/INACTIVE/BANNED sau xác thực Admin hiện tại.
- [ ] Từ chối tự đổi status của chính mình, role tamper, CSRF sai; account BANNED không đăng nhập mới hoặc thực hiện mutation sau khi đã bị chặn.
- [ ] Không tự ban từ khoản nợ/hoàn quá hạn. Test stale principal trong tác vụ đang chờ queue phải bị service kiểm tra lại khi chạy.

### Task P5.4 — Admin booking, biên bản và khoản còn nợ

**Phụ trách:** `@ducbinhnguyen07ars` · **Review:** `@thanhmit`.

- [ ] AdminBookingController list/detail hiển thị tiến độ thuê, các nghĩa vụ 30/70, receipts/refunds và BEFORE/AFTER private qua cùng quyền truy cập Phase 6.
- [ ] Bất đồng AFTER là ghi chú lưu lại, không bắt Admin resolve mới complete; không thêm nút Admin ký hợp đồng hoặc xác nhận nhận xe thay Owner.
- [ ] Lọc nợ quá hạn 1 giờ và hoàn quá hạn; thông báo deadline theo Clock, không dùng QR expiresAt làm hạn nợ.
- [ ] Test COMPLETED vẫn xem được bằng chứng/bất đồng, legacy có nhãn, người ngoài không stream ảnh bằng đoán URL.

### Task P5.5 — Profile xem/sửa

**Phụ trách:** `@huy69332` · **Review:** `@thanhmit`.

**Interfaces:** ProfileForm(fullName, phone); POST /profile/edit → updateProfile(userId, form). UserService nếu chưa có thì tạo `user/UserService.java` theo pattern hiện tại.

- [ ] GET profile đọc user hiện tại; update chỉ fullName 1–100 ký tự và phone tối đa 20 ký tự. Email/role/status/userId server quyết định.
- [ ] Form lỗi giữ input hợp lệ, escape nội dung; navbar/name lấy đúng trạng thái sau cập nhật hoặc ghi rõ cần đăng nhập lại nếu principal cache chưa làm mới.
- [ ] ProfileSecurityTest: user A không sửa B, gửi role/email/status không đổi DB, CSRF sai không mutate.

### Task P5.6 — Đổi mật khẩu

**Phụ trách:** `@huy69332` · **Review:** `@thanhmit`.

**Interface:** ChangePasswordForm(currentPassword, newPassword, confirmPassword); UserService.changePassword(userId, form).

- [ ] Kiểm tra mật khẩu cũ bằng BCrypt.matches, newPassword/confirm trùng, tối thiểu 6 ký tự và không vượt giới hạn BCrypt 72 byte UTF-8 theo auth hiện có.
- [ ] Chỉ lưu hash, không log form/password, lỗi không render lại mật khẩu. Session/logout giữ theo chính sách auth hiện hành, không chuyển logout GET thành mutation.
- [ ] Test wrong current/mismatch/UTF-8 overflow giữ hash cũ; đổi đúng rồi logout/login mới thành công, mật khẩu cũ thất bại. Fixture riêng, không sửa mật khẩu seed.

### Task P5.7 — Dashboard Owner và trang chủ

**Phụ trách:** `@ducbinhnguyen07ars` · **Review:** `@thanhmit`.

- [ ] Owner dashboard: gói/hạn/quota, số xe public/pending/ẩn, số booking, tiền thuê đã phân bổ, đã hoàn thành công, hoàn đang chờ, còn nợ và quá hạn.
- [ ] Query luôn có ownerId từ principal, không lấy ownerId form/query để đổi scope. Không cộng phí gói đã trả thành doanh thu cho thuê.
- [ ] OwnerRevenueTest: Owner A không thấy tiền/khoản hoàn Owner B; refund một phần không làm mất toàn bộ receipt; payout pending không được coi đã hoàn.
- [ ] Home chỉ hiện tối đa 8 xe mới đủ điều kiện public có gói hiệu lực theo Phase 8; direct URL/search/detail dùng cùng policy.

### Task P5.8 — Ma trận quyền, biên thời gian và hồi quy

**Phụ trách:** `@ducbinhnguyen07ars` · **Review:** `@thanhmit`.

- [ ] Lập route matrix Admin/Owner/Customer/anonymous cho webhook, checkout/status, refund recipient, subscription, ảnh private, profile và admin; ghi mã HTTP đúng quyền.
- [ ] Kiểm chứng tất cả POST người dùng có CSRF; chỉ webhook cụ thể được exempt và có verifier provider.
- [ ] Kiểm chứng time boundaries: 15 phút, 1 giờ, 3 ngày lịch, 30 ngày; user không điều khiển amount/timestamp/status/channel.
- [ ] Rà reads không enqueue, job không giữ queue/transaction trong HTTP provider, quota hai carId không vượt, app restart không mất inbox/attempt.
- [ ] Review tài liệu không còn mệnh lệnh triển khai mock mới, pickup unpaid, Customer final pickup, 72 giờ hay bắt resolve AFTER trước complete trong các plan hiện hành.

### Task P5.9 — E2E cuối, gate tiền thật và đồng bộ tài liệu

**Phụ trách:** `@ducbinhnguyen07ars` · **Review:** `@thanhmit`.

- [ ] Run `.\mvnw.cmd '-Dmaven.compiler.useIncrementalCompilation=false' clean verify`. Ghi kết quả và hạn chế thật, không suy runtime pass từ build hoặc tài liệu.
- [ ] E2E fixture/stub: Owner mua gói → đăng/gửi duyệt xe → Customer đặt → BEFORE agreed → 30% → Owner contract/handover → AFTER có bất đồng → Owner return → 70% → COMPLETED.
- [ ] Nhánh lỗi: Customer/Owner cancel trước giao, advance hết hạn/late webhook và B giữ lịch, thiếu recipient/funds, payout response mất, repeated webhook, khoản gói trùng/đến muộn, nợ 70% quá hạn, gói hết hạn/gia hạn/hạ gói.
- [ ] Browser 1440/390/320px: QR ẩn khi hết hạn, polling điều hướng đúng, form không mất dữ liệu, các trạng thái chờ không bị gọi là thành công.
- [ ] Gate live riêng: một Owner + Admin đã có kênh được phép, callback public cho local webhook, secret cấu hình đúng; người dùng chủ động thực hiện giao dịch kiểm chứng. Chưa chạy live phải ghi rõ, không dùng fixture pass thay bằng chứng nhận/chuyển thật.
- [ ] Đồng bộ SDD/decisions/task checkpoints với kết quả; giữ baseline seed/dữ liệu legacy. Chỉ đánh dấu toàn bộ phase hoàn thành sau khi từng gate thực sự đạt.

**Hoàn thành khi:** dashboard phản ánh đúng chủ thể và tiền, quyền riêng tư/profile an toàn, luồng đã chốt được kiểm chứng end-to-end, tài liệu phân biệt thiết kế/fixture/live. Phần thiếu/thừa advance vẫn nằm backlog, không tự mở rộng trong đợt này.
