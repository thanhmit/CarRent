# UI-05B — Bàn giao quản lý tài khoản Admin

Ngày 2026-11-08, root `feat/phase5-profile-password`, HEAD `b829b059a2f1af3c9ba79629759b608036470620`. **UI-05B đạt triển khai/kiểm chứng cục bộ; dừng trước UI-05C.** Người dùng yêu cầu chạy dự án và mở trình duyệt sau khi hoàn tất; runtime ứng dụng được ghi riêng sau cleanup fixture.

## 1. Đã triển khai

- `admin/users.html`: shell Admin active Tài khoản, filter tên/email/phone + role/status, semantic table sáu cột theo prototype. Dùng model thật cho tên/email/phone/ngày tạo/nhãn, badge trạng thái có text; vùng cuộn có label/tabindex/focus, tên/email dài wrap, không gây page overflow. Paging giữ q/role/status/page; empty và reset rõ.
- `admin/user-detail.html`: cùng shell; tách hồ sơ chỉ đọc, bốn số liệu theo target và form trạng thái. Null phone/financial unavailable có nhãn đúng; success flash giữ nguyên. Form chỉ khi không phải self, giữ POST action/select/CSRF và whitelist phía backend; không thêm editor email/role hoặc xác nhận giả.
- `AdminUserPermissionsTest`: regression public GET list/detail cho navigation Admin active. Đã RED trước source edit và GREEN trong focused suite cuối.

Chỉ hai template và một test source thay đổi. Không đổi backend/model/query, schema/seed ứng dụng, root POM, `.env`, local config, CSS/JS production, header/workspace hoặc caller khác. Prototype chỉ cung cấp bố cục/table/filter/badge; không port thêm/xóa Admin, bulk action, avatar/role/KYC/ngân hàng giả.

## 2. Phân tích và contract

Xem [kế hoạch UI-05B](ui-05B-analysis-and-plan-2026-11-08.md). `UserRow(id,fullName,email,phone,role,status,createdAt)` và `UserDetail(user,ownedCars,customerBookings,ownerBookings,subscriptionOrders,financialAvailable)` tiếp tục là nguồn dữ liệu. Phone/ngày tạo được dùng từ projection hiện có, không query thêm.

Search đã hỗ trợ phone và escape literal `%/_/!`, tối đa 150 ký tự; page size 20/ID DESC. GET giữ no-store, query validation/400/404. Mutation giữ POST `/admin/users/{id}/status`, chỉ nhận status/_csrf, kiểm tra current active Admin và cấm self. Giá trị email/role/password và dữ liệu xe/booking/tài chính không đổi khi cập nhật trạng thái.

## 3. Kiểm chứng mới

| Hạng mục | Kết quả |
|---|---|
| Baseline | **21/21**, 0 failure/error/skip |
| RED navigation | **1/1 fail** đúng thiếu shell Admin |
| Focused cuối | **22/22**, 0 failure/error/skip |
| Chrome | **296 kiểm tra**, 0 JavaScript error |
| Ảnh | **16 trước / 37 sau** |
| GET | **12 đoạn**, 20 bảng giữ nguyên trong từng đoạn |
| POST thật | **3 audit**: INACTIVE → BANNED → ACTIVE cho user #3, chỉ users/target status và updatedAt được đổi |
| Provider | Fake payout create/get/find **0**, không có provider/live call |
| Cleanup | **2 schema browser** đã xóa/truy vấn lại; fixture port đóng và ready/credentials dọn; 8080 giữ nguyên tại checkpoint trước chạy app theo yêu cầu |

Suite `AdminUserServiceTest,AdminUserPermissionsTest,WorkspaceUiTest` gồm kiểm tra literal search/paging, scoped counts, stale principal, tự đổi trạng thái, CSRF, field tampering và concurrency. Source cuối khớp 63 resource build riêng.

Browser 1440×1000, 1024×768, 390×844, 320×740 kiểm tra sáu cột, scroll nội bộ và ArrowRight thật, một h1, control ≥44px, tên/email dài escaped/wrap, empty, search tên/email/phone/literal, từng role/status và paging 21 hàng giữ bốn query. Target/self detail không có editor email/role; password hash/credential không render. Financial unavailable hiện “Chưa sẵn sàng”.

GET/POST Owner/Customer/anonymous/stale Admin bị từ chối; CSRF thiếu/sai, self POST, status sai/thiếu/rỗng và extra fields trả đúng 400/403. Native form status thật có flash và select cập nhật; snapshot kiểm tra toàn bộ users trước/sau để cấm đổi các field/target khác, các bảng xe/booking/tài chính không đổi. No-JS filter/paging/detail, keyboard, menu mobile và reflow 720 CSSpx đã kiểm tra. Agent xem ảnh đại diện; human/native zoom 200% chưa nghiệm thu.

## 4. Evidence

```powershell
.\mvnw.cmd -f .scratch/ui-05B/pom.xml "-Dtest=AdminUserServiceTest,AdminUserPermissionsTest,WorkspaceUiTest" test
$env:UI05B_OUT = '.scratch/ui-05B/browser-final'
node .scratch/ui-05B/browser-check.cjs
```

Browser cần fresh `Ui05BBrowser` đã ready đúng output; không reuse schema/credentials đã dọn hoặc dùng app 8080 để chạy mutation kiểm chứng. POM/build/runtime fixture nằm riêng `.scratch/ui-05B/`.

- `baseline.json`, `port8080-before.json`: manifest đầu phiên bao gồm dirty files/file đã xóa/config/index/HEAD/branch.
- `baseline-tests.log`, `red-tests.log`, `final-tests.log`: giữ riêng các lượt.
- `browser-before/capture.json` và `browser-final/report.json`, SQL trước/segment/sau, `read-post-audit.json`, `provider-calls.json`, metadata/cleanup/accessibility: kết quả browser.
- `schema-cleanup-audit.json`, `fixture-port-audit.json`, `validation.json`: cleanup và audit giới hạn thay đổi/contracts/resources. Snapshot toàn phiên không yêu cầu giống trước POST; mỗi read segment giữ nguyên và từng POST chỉ được đổi field đã duyệt.

Ảnh đại diện:

- [Danh sách desktop](../../.scratch/ui-05B/browser-final/list-1440.png), [paging mobile](../../.scratch/ui-05B/browser-final/paging-last-390.png), [empty](../../.scratch/ui-05B/browser-final/empty-320.png).
- [Hồ sơ dài](../../.scratch/ui-05B/browser-final/profile-3-320.png), [self](../../.scratch/ui-05B/browser-final/profile-1-1440.png), [BANNED sau POST](../../.scratch/ui-05B/browser-final/updated-banned-390.png).

## 5. Giới hạn và điểm dừng

Không chạy full suite/JAR, native zoom 200% hoặc provider thật. CSS reflow/Chrome/agent review không thay human nghiệm thu. Test/browser fixture dùng cache Tailwind/fonts, chặn external HTTP.

Không stage/commit/push/merge/chuyển worktree, bootstrap/migrate DB hoặc áp seed ứng dụng. Mọi dirty changes ngoài phạm vi giữ nguyên, gồm `uploads/seed-car-5.png` đã xóa từ trước. Scheduler/provider giao dịch không được tự bật khi chạy app.

Đã triển khai **13/15 trang** trong phạm vi gốc; còn **UI-05C (1 đợt / 2 trang)**: booking list/detail Admin và smoke lại 15 trang/caller trên source cuối. UI-06/07 ngoài phạm vi; gate P4.2-A/P5.9/P8.8/human/live giữ riêng. Backlog UI-05 tổng vẫn mở các mục booking/nghiệm thu toàn nhóm.

## 6. Runtime ứng dụng theo yêu cầu người dùng

Sau cleanup/audit fixture, người dùng yêu cầu chạy dự án và mở trình duyệt. Đã khởi động `com.carrent.CarRentApplication` từ `.scratch/ui-05B/build/classes` cùng dependency classpath, không đưa test-classes/fixture vào runtime. **Ứng dụng được giữ chạy**, PID **17824**, tool session **17196**, bind **127.0.0.1:8080**, profile `local`.

CLI override giữ `ddl-auto=none`, `sql.init.mode=never`, payment maintenance/rental deadlines và bốn cờ SePay/payOS giao dịch tắt; không sửa file cấu hình, apply schema/seed hoặc thực hiện giao dịch. Profile setup/check provider không hoạt động. App dùng cấu hình DB local hiện hành và dữ liệu ứng dụng sẵn có.

HTTP smoke mới: `/` **200**, `/login` **200**, `/admin/users` **302** sang login khi chưa xác thực. Đã mở `http://localhost:8080/admin/users` bằng Playwright MCP; browser hiện `http://localhost:8080/login`, title “Đăng nhập - CarRent”, để người dùng đăng nhập Admin. Không đăng nhập tự động hoặc dùng credential fixture vào ứng dụng thật.

Lệnh PowerShell Start-Process bị chính sách công cụ từ chối. Phương án đã chạy thành công là server trực tiếp trong phiên quản lý, chỉ loopback, và công cụ browser navigate. Runtime log `.scratch/ui-05B/app-runtime.log`, trạng thái/PID/HTTP/browser tại `app-runtime.json`. Browser live có favicon.ico 404 và cảnh báo Tailwind CDN hiện hữu; không đổi chúng trong UI-05B.

`port8080-after.json`/`validation.json` ghi checkpoint cleanup **trước** thao tác chạy app được yêu cầu; sau đó 8080 có listener ứng dụng theo chủ ý. Đây không phải fixture còn sót. Chưa thực hiện authenticated mutation hoặc nghiệm thu provider trên ứng dụng thật. Khi cần dừng, xác nhận PID/command của ứng dụng này trước khi stop; không dừng các worktree/runtime khác.
