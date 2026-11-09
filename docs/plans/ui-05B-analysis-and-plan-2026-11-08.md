# UI-05B — Kế hoạch danh sách và hồ sơ tài khoản Admin

> **For agentic workers:** Dùng `superpowers:executing-plans`, không subagent; triển khai trong phiên được giao, kiểm chứng rồi dừng trước UI-05C. Không commit/push/chuyển worktree.

**Goal:** Hoàn thiện hai trang quản lý tài khoản với thông tin thật và thao tác trạng thái đúng quyền.

**Architecture:** Tái sử dụng shell Admin UI-01, token hiện có và projection/controller cũ. Danh sách dùng một table trong vùng cuộn có nhãn theo prototype, không nhân đôi DOM desktop/mobile. Detail tách hồ sơ, số liệu và form trạng thái. Chỉ sửa hai template và regression test.

**Tech Stack:** Java 21, Spring Boot 4.1.1, Thymeleaf, Tailwind; MySQL fixture/Maven/Chrome cách ly.

**Spec:** [15 trang/UI-05B](ui-15-pages-readiness-2026-11-08.md), [backlog §3.5](ui-completion-2026-11-07.md), [prototype users](../design/stitch/admin-quan-ly-nguoi-dung.html), [bàn giao UI-05A](ui-05A-handoff.md), [SDD §8.7](../SDD.md).

## Global Constraints

- Root `feat/phase5-profile-password`; giữ dirty changes/file xóa sẵn, không checkout/reset/stash/worktree/stage/commit/push/merge.
- Không đổi Java main, root POM, schema/seed ứng dụng, `.env`, local config/provider hoặc CSS/JS production. Seed/POST/clock kiểm chứng chỉ trong fixture schema/port riêng, fake provider; không chuyển tiền. Người dùng đã yêu cầu chạy dự án/mở trình duyệt sau khi hoàn tất UI-05B; thực hiện sau cleanup, giữ scheduler/provider giao dịch tắt, không bootstrap DB hoặc restart runtime đang có.
- Giữ `q/role/status/page`, method/action, CSRF, enum whitelist và các guard self/stale. Không thêm tạo/xóa user, bulk action, nhập email/role hoặc KYC/ngân hàng giả từ prototype.
- UI-05C và human/native zoom/full suite/JAR/live giữ riêng. Sau đợt này còn 1 đợt/2 trang trong phạm vi 15 trang.

## 1. Phân tích source và prototype

`AdminUserController` có GET `/admin/users`, GET `/admin/users/{id}` và POST `/admin/users/{id}/status`; chỉ chấp nhận `status,_csrf` ở POST. `UserRow(id,fullName,email,phone,role,status,createdAt)` không có password/provider field. `UserDetail(user,ownedCars,customerBookings,ownerBookings,subscriptionOrders,financialAvailable)` có bốn count theo target; thiếu financial schema phải hiện “Chưa sẵn sàng”.

Search service đã hỗ trợ tên/email/phone, escape `%/_/!`, giới hạn 150 ký tự, page size 20 và ID DESC. Table hiển thị đúng phone/ngày tạo từ projection, không query thêm. Filters giữ q/role/status/page khi paging; submit GET về page đầu và reset toàn bộ như cũ.

Status ACTIVE/INACTIVE/BANNED dùng nhãn Map thật, màu chỉ bổ trợ. POST khóa/kiểm tra current active Admin, cấm self-change và chỉ đổi target status; không đổi email/role/password, xe/booking/financial history. Không thêm xác nhận client hoặc đổi backend để hoàn thiện UI.

Prototype có shell/table/filter/badge và các CTA giả “thêm Admin”, “xóa”, role/status khác hệ thống. Chỉ dùng bố cục/table sáu cột (người dùng, phone, role, ngày tạo, status, hồ sơ); actions và data dùng hệ thống thật. Table cuộn nội bộ trên màn hẹp, label/focus/keyboard có sẵn, tên/email dài wrap đầy đủ.

## 2. File và seam

| File | Trách nhiệm |
|---|---|
| `templates/admin/users.html` | Shell `sidebar('users',null)`, filter, semantic table, badges, empty/paging giữ contract |
| `templates/admin/user-detail.html` | Cùng shell; hồ sơ read-only, bốn count, form status giữ th:unless self |
| `AdminUserPermissionsTest.java` | Regression qua public GET list/detail cho active Admin navigation |
| `.scratch/ui-05B/` | Baseline/POM/build/fixture/ảnh/audit |
| `docs/plans/ui-05B-handoff.md`, README/SDD/checklists | Evidence và điểm dừng |

## 3. Checkpoint A — Baseline và RED

**UI-05B — 2026-11-08:** đã hoàn thiện danh sách/hồ sơ tài khoản Admin (2 trang), giữ self/CSRF/status-only. Focused **22/22**, Chrome **296 kiểm tra**, **16 ảnh trước/37 ảnh sau**; 20 bảng đọc theo đoạn, 3 POST audit chỉ target status/updatedAt, payout 0; fixture đã dọn. Xem [bàn giao UI-05B](ui-05B-handoff.md). Đã triển khai **13/15 trang**, còn **UI-05C: 1 đợt / 2 trang** và smoke cuối. Human/native zoom/live giữ riêng.

- [x] Lưu manifest/config/index/HEAD/port 8080; baseline `AdminUserServiceTest,AdminUserPermissionsTest,WorkspaceUiTest`; fixture chụp trước list/detail/self.
- [x] RED GET list/detail phải có `aria-label="Điều hướng quản trị"` và link `/admin/users` có `aria-current="page"`; chạy fail đúng thiếu shell trước sửa, không test class CSS.

## 4. Checkpoint B — Hai template

- [x] Dùng `<th:block th:replace="~{fragments/workspace :: sidebar('users', null)}"></th:block>` và topbar. Table bind `u.fullName/email/phone/role/status/createdAt`, th scope row/col, scroll region label/tabindex/focus, controls ≥44px; không CTA ngoài route thật.
- [x] Detail giữ expressions/profile/counts/financialAvailable/successMessage, các label và form `th:unless="${self}"` POST/CSRF. Email/role vẫn text. Form select status labels hiện có, không tự sửa field khác.
- [x] GREEN focused suite, giữ đầy đủ tests filter literal/paging/role/stale/CSRF/tampering/concurrency và mutation chỉ status.

## 5. Checkpoint C — Browser và bàn giao

- [x] Browser 1440/1024/390/320: no page overflow, table có thể cuộn bằng bàn phím, một h1, tên/email dài escaped/wrap, badges có text, targets ≥44px; no-JS và reflow 720 CSSpx. Human/native zoom giữ riêng.
- [x] Tìm tên/email/phone/literal, lọc từng role/status, 21 hàng paging giữ q/role/status/page, empty/400/404. Target/self detail và financial unavailable hiển thị đúng, không lộ password hash/provider.
- [x] Native login Admin/Owner/Customer/anonymous, stale banned/demoted và POST khác role/CSRF/self/tampering bị từ chối. POST thật ACTIVE→INACTIVE→BANNED→ACTIVE trong fixture; 20 bảng theo từng read segment, POST allowlist chỉ users và target status/updatedAt, payout 0.
- [x] Dọn fixture/credentials/ready, query schema absent/port đóng/8080 giữ nguyên. Audit baseline/resource/contracts và vùng ngoài scope; tài liệu cập nhật song song nếu có phải giữ riêng, không thay baseline gốc.
- [x] Cập nhật UI-05B 4 checkbox trong readiness, chỉ users/detail trong backlog UI-05 tổng, README/SDD/handoff; UI-05C và nghiệm thu 15 trang cuối vẫn mở.

Lệnh focused:

```powershell
.\mvnw.cmd -f .scratch/ui-05B/pom.xml "-Dtest=AdminUserServiceTest,AdminUserPermissionsTest,WorkspaceUiTest" test
```
