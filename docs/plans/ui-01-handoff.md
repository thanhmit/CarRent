# UI-01 — Shell/navigation: bàn giao 2026-11-08

**Trạng thái:** đã triển khai và kiểm chứng cục bộ. **Human visual review: chờ review.** Dừng trước UI-02; chưa hoàn thiện nội dung 15 trang nhóm A và chưa đóng backlog UI tổng thể.

**Baseline:** root `C:/Users/Admin/Documents/hoc/Car`, branch `feat/phase5-profile-password`, HEAD `b829b059a2f1af3c9ba79629759b608036470620`. Working tree đã dirty trước khi bắt đầu; không checkout, stash, merge, stage, commit hoặc push. Kế hoạch thực hiện: [15 trang](ui-15-pages-readiness-2026-11-08.md), [UI-01 chi tiết](ui-completion-2026-11-07.md#ui-01--shell-navigation-và-quy-chuẩn-thành-phần).

## 1. Sản phẩm

- Tạo `src/main/resources/templates/fragments/workspace.html`: sidebar Owner/Admin và topbar dùng chung; desktop từ `lg`/1024px dùng sidebar 256px, mobile dùng `<details>/<summary>` có keyboard và no-JS.
- Thay đủ **6 shell caller**: `owner/cars.html`, `owner/car-form.html`, `owner/bookings.html`, `owner/booking-detail.html`, `admin/cars.html`, `admin/car-detail.html`. Chỉ render một topbar; giữ form, business expression, timeline, dialog và script của từng trang.
- Menu Owner có tổng quan/xe/đơn/gói và ba phạm vi tài chính; đường mua gói ẩn khi flag tắt. Menu Admin tách tra cứu toàn hệ thống và tài chính cá nhân; không có link nghiệp vụ Owner.
- `fragments/header.html` giữ `header`, `authHeader`, `notificationBell`; tách thêm `accountMenu` để public header và workspace dùng cùng tài khoản/CSRF logout. Topbar lấy tên từ principal, không sửa controller/model.
- Public header có active state và `aria-current` đúng section, không mặc định Trang chủ. Sáu caller truyền scope: home, cars list/detail, booking create/list/detail. Trang không truyền scope không có active public item.
- Bell, account summary, các link header bị chạm và pagination có target tối thiểu 44px. `fragments/pagination.html` giữ nguyên query/điều kiện phân trang.
- Ba vùng bảng Owner cars/Owner bookings/Admin cars có focus và scrollbar nhìn thấy; bổ sung nhãn vùng cho hai trang xe đang thiếu. Giữ scrollbar trong vùng bảng, không mở cuộn ngang toàn trang hoặc thay CSS toàn app.
- Không tạo `fragments/ui.html`: chưa có khối heading/status lặp cần interface mới. Không sửa head/tokens, messages, footer, CSS/JS hoặc backend.

## 2. Interface để đợt sau dùng

```html
<th:block th:replace="~{fragments/workspace :: sidebar('dashboard', null)}"></th:block>
<div class="lg:pl-64 min-w-0">
  <header th:replace="~{fragments/workspace :: topbar('Tổng quan chủ xe')}"></header>
  <main class="p-4 lg:p-6 min-w-0"></main>
</div>
```

- `sidebar(activeSection, financeView)`: section `dashboard/cars/bookings/subscriptions/users/finances`; finance view `paid/collected/refunds/managed` hoặc `null`. Đây là presentation state; backend vẫn quyết định quyền.
- `topbar(title)`: principal hiện hành, notificationBell và accountMenu. Không render public fixed header trên cùng trang workspace.
- `navigation(activeSection, financeView)` là fragment nội bộ, dùng lại cùng markup cho desktop/mobile; không cần gọi trực tiếp từ page.
- `accountMenu` là fragment không tham số mới; ba fragment header cũ giữ nguyên chữ ký.
- Public active state truyền trong **wrapper**, không đặt `th:with` cùng node `th:replace`:

```html
<div th:with="publicNavSection='cars'">
  <th:block th:replace="~{fragments/header :: header}"></th:block>
</div>
```

Desktop/mobile có hai bản navigation trong DOM, chỉ một bản nhìn thấy. Browser assertion phải giới hạn `:visible`; không coi hai `aria-current` trong hai layout ẩn/hiện là hai mục active cùng lúc.

## 3. Kiểm chứng

Build/test dùng POM riêng `.scratch/ui-01/pom.xml`, trỏ source/resource về root, output tại `.scratch/ui-01/build`, test working directory tại root. Không ghi đè `target` đang có. JDBC host được kiểm tra là local; fixture tự tạo schema `carrent_p43_<UUID>`, port ngẫu nhiên, provider giả, maintenance và rental deadline scheduler tắt.

| Kiểm tra | Kết quả |
|---|---|
| Baseline `OwnerRevenueTest,FinancialViewsTest,PaymentAccessAuditTest` | **30/30**, 0 failure/error/skip |
| Test trước implementation: `WorkspaceUiTest` | RED đúng mục tiêu: 3 failure thiếu active/menu, 0 error |
| Final focused suite: ba class baseline + `WorkspaceUiTest` | **35/35**, 0 failure/error/skip |
| Chrome qua Playwright, đăng nhập form thật có CSRF | **634 assertion đạt**, 0 lỗi JavaScript |
| Ảnh trước/sau | **36 ảnh trước**, **60 ảnh sau**; đủ 6 trang shell ở 1440/1024/390/320 |
| Shared callers | 32 smoke entries bổ sung: profile/gói/finance/payment/Admin/inspection/error/unavailable |
| Dữ liệu fixture trước/sau browser | Hash **20/20 bảng giống nhau**, gồm BLOB được chuẩn hóa; self-check xác nhận hash ổn định |
| Provider giả | **0 calls** |
| Cleanup | Spring context đóng, schema đã drop và kiểm tra lại không tồn tại; port fixture không còn listener |

Lệnh focused suite cuối, đã chạy:

```powershell
.\mvnw.cmd -f .scratch/ui-01/pom.xml "-Dtest=WorkspaceUiTest,OwnerRevenueTest,FinancialViewsTest,PaymentAccessAuditTest" test
node .scratch/ui-01/browser-check.cjs
```

Lệnh Node cần chạy cùng Java fixture `ShellBrowser` vừa boot, có ready file và credential tạm. Không chạy độc lập trên runtime 8080 hoặc dùng file ready của lần trước. Harness nằm trong `.scratch`, không phải prerequisite khi clone máy khác; `WorkspaceUiTest.java` là test source đi cùng implementation.

**Hành vi đã kiểm tra:** active section công khai và Owner/Admin; menu mobile bằng Enter; target 44px, phân trang, bảng cuộn bằng ArrowRight; bell/unread badge; tên dài và escaping; Customer không vào Owner, Owner không vào Admin; Owner B xem booking Owner A nhận **404 hiện hành**; anonymous được chuyển login; thiếu CSRF logout bị 403; native logout/no-JS navigation hoạt động. Layout tại 720 CSS px được kiểm tra như viewport tương đương khi zoom 200% từ 1440, không thay cho thao tác browser zoom thủ công.

Error/unavailable templates được render qua endpoint **chỉ có trong fixture** `UiPages`; không dùng kết quả HTTP 200 của endpoint này để chứng minh status lỗi thật. Các status/quyền thật được kiểm tra qua route nghiệp vụ và focused suite.

## 4. Evidence và review

Tính từ root:

- `.scratch/ui-01/baseline.json`: HEAD/branch/index, status và hash file trước sửa; file bị xóa sẵn trong baseline vẫn giữ trạng thái xóa.
- `.scratch/ui-01/baseline-tests.log`, `red-tests.log`, `focused-tests.log`, `final-tests-verified.log`: baseline, RED, chạy lại và kết quả cuối được giữ riêng.
- `.scratch/ui-01/browser/capture.json`, các PNG cùng thư mục: 36 ảnh trước trên build baseline; schema trước đã dọn.
- `.scratch/ui-01/browser-after/report.json`: 634 checks, viewport, route smoke và 60 PNG sau trên source cuối; `metadata.json`, `sql-before.json`, `sql-after.json`, `provider-calls.json`, `cleanup.json` đi cùng lần cuối.
- `.scratch/ui-01/body-audit.json`: đối chiếu sáu phần main với baseline completion đã xác minh tương đương root; chỉ cho phép padding breakpoint và accessibility vùng bảng đã nêu.
- `.scratch/ui-01/validation.json`: allowlist/hash audit, source/resource của build cuối, kiểm tra tài liệu và cleanup/port.

Ảnh xem nhanh:

- [Owner desktop](../../.scratch/ui-01/browser-after/owner-owner-cars-1440.png)
- [Owner mobile 390](../../.scratch/ui-01/browser-after/owner-owner-cars-390.png)
- [Menu Owner 320](../../.scratch/ui-01/browser-after/owner-owner-cars-320-menu.png)
- [Admin desktop](../../.scratch/ui-01/browser-after/admin-admin-cars-1440.png)
- [Admin detail 320](../../.scratch/ui-01/browser-after/admin-admin-cars-1-320.png)

Agent đã xem ảnh đại diện của đủ sáu trang shell và menu hẹp, đồng thời kiểm tra layout bằng browser. **Human visual review còn chờ**, chưa được tính thay bằng kết quả tự kiểm.

### Lỗi ban đầu và giới hạn bằng chứng

- Lượt GREEN đầu còn 1 assertion menu gói: test xóa System property ngay sau boot khiến environment không còn flag. Test đã giữ flag suốt fixture, dọn trong `AfterAll`, kiểm tra riêng khi flag tắt; final suite 35/35.
- Browser harness ban đầu gặp transaction ID fixture không đúng constraint số, locator profile trùng desktop/mobile, refund thiếu intent, thiếu tên `@RequestParam` và kỳ vọng 403 thay vì 404 hiện hành. Các lỗi setup/assertion được sửa trong harness, giữ các `failure-*.json`/PNG và log lỗi; không nới quyền hoặc sửa service.
- Hash BLOB ban đầu dùng `byte[].toString()` nên không ổn định; đây không phải bằng chứng DB bị mutation. Lượt cuối dùng hex chuẩn hóa + self-check và xác nhận đủ 20 bảng không đổi. Report trước audit chuẩn hóa được giữ riêng tại `browser-before-canonical-audit.json`.
- Tailwind/font được phục vụ từ bản cache cục bộ; request ngoài bị chặn, ảnh ngoài dùng placeholder fixture. Kết quả này không chứng minh CDN/provider thật đang hoạt động.
- `uploads/seed-car-5.png` đã bị xóa từ baseline, nên ảnh seed đó còn thiếu trong fixture. Giữ nguyên trạng thái này, không khôi phục upload ngoài phạm vi UI-01.
- Không chạy full suite, Node polling suite hoặc test giao dịch tiền thật: không đổi JS/payment behavior; focused suite và render/interaction là phạm vi kiểm chứng lần này. Không gọi đây là nghiệm thu live hoặc duyệt bằng người.

## 5. Điểm dừng

Giữ nguyên backend/config/index/HEAD và các dirty changes ngoài allowlist; không restart 8080, không commit/push. UI-02–07 còn mở. Đợt tiếp theo khi được giao là **UI-02: Owner dashboard và finances đủ bốn view**, đọc interface ở §2, tạo baseline/fixture mới rồi kiểm chứng theo kế hoạch; không dùng lại ready/credential hoặc schema đã dọn của UI-01.
