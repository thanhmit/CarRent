# CarRent UI — Worktree Analysis and Implementation Plan Refresh

> **For agentic workers:** Đọc tài liệu này cùng kế hoạch UI ngày 07/11 và SDD §8.7. Khi được giao triển khai, dùng `superpowers:executing-plans`, nhận đúng một đợt hoặc một phần đợt, kiểm chứng rồi bàn giao. Phiên 08/11 chỉ phân tích và cập nhật tài liệu; không tạo nhánh/worktree, không triển khai UI.

**Goal:** Chốt baseline và thứ tự hoàn thiện các trang UI còn sơ sài mà không làm mất thay đổi nghiệp vụ đang có.

**Architecture:** Giữ Spring MVC/Thymeleaf và Tailwind tokens của Stitch; tái sử dụng controller, read model, form và JS hiện hành. Tách sidebar/topbar thực sự lặp, rồi cập nhật từng nhóm trang; không đổi hợp đồng tiền hoặc quyền truy cập.

**Tech Stack:** Java 21, Spring Boot 4.1.1, Thymeleaf, Spring Security, MySQL, Tailwind Play CDN, Inter và Material Symbols; không thêm dependency.

**Spec:** [SDD §8.7](../SDD.md#87-hoàn-thiện-thiết-kế-ui-sau-các-đợt-triển-khai-chức-năng--2026-11-07), [kế hoạch chi tiết và inventory 42 trang](ui-completion-2026-11-07.md), [Stitch](../design/stitch/README.md). Tài liệu này bổ sung quyết định baseline và chia đợt; inventory, wireframe và ràng buộc nghiệp vụ của kế hoạch gốc vẫn áp dụng.

## 1. Global Constraints

- Chỉ thay presentation và kiểm tra liên quan; giữ URL, tên field, method, CSRF, điều kiện quyền/trạng thái, masking và escaping.
- UI tiếng Việt, dùng đúng token Stitch; không React, Bootstrap, chart library hoặc Tailwind build migration.
- Không sửa service nghiệp vụ, schema/seed, provider config, `.env`, `application-local.properties`, scheduler hoặc runtime 8080 để làm UI.
- Mọi kiểm tra có mutation dùng DB/schema và provider fixture riêng; không dùng chuyển tiền thật để nghiệm thu giao diện.
- Không checkout/reset/stash/merge/stage/commit/push hoặc tạo worktree trong phiên phân tích này. Phiên triển khai không tự công bố thay đổi Git.
- Các checkbox bên dưới đều là công việc tương lai. Không thay tiến độ task nghiệp vụ hoặc đóng gate provider bằng kết quả UI.

## 2. Worktree hiện tại — xác minh ngày 08/11/2026

Đường dẫn tính từ `C:/Users/Admin/Documents/hoc/Car`. Số dirty là số dòng `git status --porcelain` mặc định: entry untracked có thể là một thư mục, không phải số file.

| Checkout | Branch / HEAD | Tracked dirty / untracked entry | Vai trò đề xuất |
|---|---|---|---|
| `.` | `feat/phase5-profile-password` / `b829b05` | 37 / 44 | Nguồn chuẩn UI và source hiện hành cho các đợt tiếp theo |
| `.scratch/completion-20261105/worktree` | `feat/completion-profile-pilot` / `1862aa9` | 24 / 35 | Đối chiếu lịch sử; không copy đè root |
| `.scratch/publish-p57-p58/main` | `main` / `1862aa9` | 0 / 0 | Baseline Git đã tích hợp P5.7/P5.8; không chứa đủ phần chưa commit |
| `.scratch/publish-p57-p58/p57` | `feat/p57-owner` / `ff1f04f` | 0 / 0 | Nhánh tham chiếu P5.7 |
| `.scratch/publish-p57-p58/p58` | `feat/p58-audit` / `d5c7b52` | 0 / 0 | Nhánh tham chiếu P5.8 |

Root là tổ tiên của `main`, thiếu bốn commit: `ff1f04f`, `908219b`, `d5c7b52`, `1862aa9`. Tuy nhiên working files đã chứa nhiều phần tương ứng dưới dạng chưa commit; không dùng riêng HEAD để kết luận thiếu chức năng, và không merge ngay trên checkout đang dirty.

### Đối chiếu nội dung root với completion

- Cùng **51 HTML: 42 trang + 9 fragment**; nội dung template và toàn bộ static assets giống nhau sau chuẩn hóa CRLF/LF. Có 19 HTML và một CSS khác byte chỉ vì xuống dòng. Không tạo đợt sửa format trên các file đó.
- Java thực sự khác **2 service**: root chỉ gọi `requireCurrentActor(customer, Role.CUSTOMER)` ở vị trí sau khóa lịch khi `rentalWorkflow` bật; completion gọi vô điều kiện tại vị trí đó. Root còn chuẩn hóa thời điểm tạo subscription order về micro giây, completion chưa có phần này. Đây là khác biệt cần giữ riêng, chưa phải kết luận audit tính đúng của service.
- Có **4 test file** khác nội dung: `FinalRentalJourneyTest`, `SubscriptionOrderTest`, `SubscriptionOwnerUiTest`, `SubscriptionPaymentTest`; những Java/test khác biệt còn lại trong phạm vi đã đối chiếu chỉ khác CRLF/LF.
- Chưa có `fragments/workspace.html` hoặc `fragments/ui.html`. Kế hoạch 07/11 còn 39 checkbox mở, 0 checkbox đóng. Điều này xác nhận backlog chưa ghi nhận nghiệm thu, không có nghĩa các trang hiện hành chưa có CSS/chức năng.

### Quyết định baseline

**Đề xuất tiếp tục trong root, giới hạn diff vào presentation và test/handoff của đợt được giao.** Root và completion không cung cấp hai phiên bản UI khác nhau để lựa chọn; root còn có bản sửa deadline Tier mới hơn. Giữ nguyên branch, backend dirty và index, lập hash manifest trước/sau; không chuyển branch chỉ để sửa template.

Nếu phiên sau có yêu cầu worktree độc lập hoặc công bố Git, phải làm một bước tích hợp baseline riêng: bắt đầu từ `main`, xác định và đưa sang từng prerequisite chưa commit bằng manifest, rồi kiểm chứng lại. Không mặc định worktree tạo từ `main` có sẵn profile, bản sửa Tier, cấu hình hoặc source runtime của root; không mang `.env`/secret sang checkout đó.

## 3. Khoảng trống UI đã xác nhận bằng source

| Nhóm | Số trang | Hành động |
|---|---:|---|
| Cần tổ chức lại bố cục | 15 | Owner dashboard và tài chính cá nhân (2), checkout/result/refund/refund summary (4), gói/order (2), Admin dashboard/finance/detail/users/detail/bookings/detail (7) |
| Đã có UI, cần đồng bộ | 13 | Profile (3), booking/inspection/quản lý xe (9), notifications (1) |
| Trạng thái hệ thống | 8 | Hai unavailable và sáu error page; giữ đúng HTTP và lý do |
| Trang tham chiếu/hồi quy | 6 | Home, tìm xe, chi tiết xe, login, register, tạo booking; giữ thiết kế hiện có |

Các phát hiện cụ thể:

1. **Sidebar/topbar bị lặp ở 6 trang**: `owner/cars`, `owner/car-form`, `owner/bookings`, `owner/booking-detail`, `admin/cars`, `admin/car-detail`. Kế hoạch cũ đưa 5 trang vào UI-01; bổ sung `admin/car-detail` ngay ở bước thay shell, phần nội dung vẫn thuộc UI-06C.
2. **Owner dashboard và phần lớn Admin dùng header public**, navigation chủ yếu link chữ, khác shell quản trị của trang xe. Cần đồng bộ bố cục và phân cấp CTA/KPI, không bổ sung thống kê chưa có model.
3. **Finances đã có `aria-current` nhưng các tab vẫn cùng kiểu link underline**, các dòng tiền được xếp thành đoạn văn. Cần active style nhìn thấy được, nhãn phạm vi và bảng/card cho cả bốn view; giữ giới hạn của Customer/Owner/Admin.
4. **Có 9 dòng dùng `font-title-lg`/`font-title-sm` chưa được khai báo** trong `fragments/head.html`, ở 5 file: Owner dashboard, subscription plans/overview, booking fragments và Admin booking detail. Thay bằng cặp token có thật trong đợt sở hữu file; không thêm token tùy ý.
5. **Header public đang đánh dấu Trang chủ bằng class cố định.** UI-01 cần active state đúng trang, giữ chữ ký `header`, `authHeader`, `notificationBell` để không phá caller.
6. **Đang phụ thuộc Tailwind Play CDN và font ngoài.** No-JS cần giữ thao tác/đọc nội dung và fallback hiện hành; không dùng việc chụp ảnh khi có CDN để tuyên bố mọi trang có style khi tắt JS. Migration assets nằm ngoài backlog này.

Đây là đánh giá source. Phiên 08/11 không mở app/browser, không xác nhận trạng thái DOM hoặc thẩm mỹ của runtime đang chạy; không dùng ảnh P5.7 cũ làm ảnh trước/sau của UI mới.

## 4. Thứ tự triển khai

Giữ bảy ID cha của kế hoạch gốc; tách UI-05 và UI-06 thành ba phần có thể bàn giao riêng. Tổng cộng **11 đơn vị triển khai**, không phải 11 nhóm nghiệp vụ mới.

| Thứ tự | Đợt | Sản phẩm và phạm vi file | Điều kiện bắt đầu / đóng |
|---:|---|---|---|
| 1 | UI-01 | Shell/sidebar/topbar, public header active, flash/pagination; thay shell ở 6 caller nêu trên | Bắt đầu bằng manifest + fixture baseline; đóng khi role/menu/logout/notification và 1440/1024/390/320 đạt |
| 2 | UI-02 | `owner/dashboard.html`, `payment/finances.html`, `subscription/overview.html` | UI-01 đạt; dashboard đủ KPI/unavailable, finances đủ bốn view và đúng quyền |
| 3 | UI-03 | `payment/checkout.html`, `result.html`, `refund.html`, `refund-summary.html`, `refund-links.html` | UI-01/02; giữ polling, preview-confirm, deadline, QR và masking; lỗi verifier vẫn là lỗi thật |
| 4 | UI-04 | `subscription/plans.html`, `detail.html`, `overview.html` | UI-01/02 và regression link hoàn từ UI-03; đủ trạng thái active/expired/quota/pending/order |
| 5 | UI-05A | `admin/dashboard.html`, `finance.html`, `finance-detail.html`, `finance-row.html` | UI-01/02; KPI/phạm vi tiền đúng, đủ RECEIPTS/OBLIGATIONS/REFUNDS, filter/paging giữ nguyên |
| 6 | UI-05B | `admin/users.html`, `user-detail.html` | UI-01; theo prototype users, có table/card, form trạng thái và self-change guard |
| 7 | UI-05C | `admin/bookings.html`, `booking-detail.html` | UI-05A để tái dùng finance-row; timeline/bằng chứng/tài chính phân nhóm, Admin chỉ đọc |
| 8 | UI-06A | `booking/list.html`, `detail.html`, `fragments.html`, `owner/bookings.html`, `booking-detail.html` | UI-03 và UI-05C; status/bước tiếp theo rõ; kiểm tra tất cả caller timeline/badge |
| 9 | UI-06B | `inspection/detail.html` | UI-06A; form/ảnh/compare/nháp dễ dùng, giữ quyền private và no-JS |
| 10 | UI-06C | `owner/cars.html`, `car-form.html`, `admin/cars.html`, `car-detail.html` | UI-01/04; hoàn thiện nội dung/form/dialog, không làm lại shell lần hai |
| 11 | UI-07 | Profile, notifications, unavailable/error; hồi quy 6 trang tham chiếu | Các đợt trước đạt; review đủ phạm vi và ghi rõ human visual review còn chờ nếu chưa có |

UI-05 chỉ đóng khi A/B/C đều đạt; UI-06 tương tự. Các phần dùng chung file phải đi tuần tự để tránh ghi đè. Những dependency trong bảng này làm rõ thứ tự bàn giao; không thay đổi quyền hoặc workflow nghiệp vụ.

## 5. Chốt giao diện dùng chung trước UI-02

UI-01 cần bàn giao fragment có tên/chữ ký rõ, không để đợt sau tự đoán. Đề xuất tối thiểu:

```html
<!-- Caller Owner cars; main và form nghiệp vụ giữ tại trang. -->
<div th:replace="~{fragments/workspace :: sidebar('cars', null)}"></div>
<div class="lg:pl-64 min-w-0">
  <header th:replace="~{fragments/workspace :: topbar('Cổng thông tin chủ xe')}"></header>
</div>
```

- `sidebar(activeSection, financeView)`: dùng `sec:authorize` hiện có để chọn nav theo role; `activeSection` thuộc `dashboard/cars/bookings/subscriptions/users/finances`, `financeView` là `paid/collected/refunds/managed` hoặc `null`. Đây là presentation state, không phải đầu vào quyết định quyền backend.
- `topbar(title)`: dùng principal hiện có để hiện tên, gọi lại `notificationBell`; không yêu cầu thêm `ownerName/adminName` từ mọi controller. Chỉ render một topbar/header ở mỗi layout.
- Public `header` giữ chữ ký cũ; caller có thể đặt `publicNavSection` bằng `th:with` trong scope cha của fragment. Chỉ các trang public/customer tương ứng đặt `home/cars/bookings`; missing value không mặc định tô sáng Trang chủ. Các caller đổi phải nằm trong manifest và được render lại, không thêm request API chỉ để biết active route.
- Mobile menu dùng `<details>`/`<summary>` hoặc thành phần tương đương có keyboard và không cần JS. Breakpoint sidebar dùng `lg` nhất quán; không để `md:pl-64` còn sót khi sidebar đã đổi breakpoint.
- `fragments/ui.html` chỉ tạo nếu ít nhất hai caller dùng cùng heading/empty/status panel; không cần generic button/form fragment. `messages` và `pagination` giữ contract hiện hành; filter đặc thù không ép qua pagination chung nếu làm mất query.

Snippet mô tả contract dự kiến, chưa phải toàn bộ layout triển khai. Main/body, nhãn menu, các route và điều kiện hiển thị phải được port từ source/prototype thật khi thực hiện UI-01.

## 6. Kiểm chứng và tiêu chí nghiệm thu

### Chuẩn bị cho từng đợt

- [ ] Đọc lại `git worktree list --porcelain`, `git status --short`, branch và HEAD; xác nhận baseline chưa bị người khác thay đổi.
- [ ] Ghi SHA-256 source, index và file được phép sửa. Dùng snapshot hiện hành làm baseline; không dùng hash ngày 08/11 thay kiểm tra ở phiên sau.
- [ ] Đọc `PaymentTestDatabase.java` và `PaymentFlowFixture.java`: fixture tạo schema `carrent_p43_<UUID>` riêng, dùng port ngẫu nhiên và provider giả. Xác minh target/flags trước khi chạy; không xuất credential ra log/handoff.
- [ ] Dùng harness P5.7 làm tham khảo, không chạy nguyên lệnh cũ: `browser-check.cjs` phụ thuộc đường Playwright cache cục bộ, file ready và credentials fixture đã được dọn. P5.8 hiện có thư mục con `browser/lifecycle` và `browser/subscription`, không có `browser/report.md` ở cấp cha.
- [ ] Chụp ảnh trước bằng fixture mới trên source của đợt; sau sửa dùng cùng dữ liệu/viewport để so sánh.

### Bộ test hiện có theo đợt

Tên class dưới đây đã được xác nhận tồn tại ngày 08/11. Chạy bằng PowerShell tại root sau khi chuẩn bị fixture; property Maven có dấu ngoặc kép. Đây là lệnh tương lai, chưa chạy trong phiên lập kế hoạch.

| Đợt | `-Dtest=` |
|---|---|
| UI-01/02 | `OwnerRevenueTest,FinancialViewsTest,PaymentAccessAuditTest` |
| UI-03 | `PaymentAccessAuditTest,RentalBalanceUiTest,RentalCancellationUiTest,RefundRecipientTest,RefundServiceTest` |
| UI-04 | `SubscriptionUiTest,SubscriptionOwnerUiTest,SubscriptionDowngradeTest,SubscriptionOrderTest,SubscriptionPaymentTest` |
| UI-05A | `AdminRevenueTest,AdminPermissionsTest` |
| UI-05B | `AdminUserServiceTest,AdminUserPermissionsTest` |
| UI-05C | `AdminBookingTest,AdminPermissionsTest` |
| UI-06A | `RentalTimelineTest,RentalBalanceUiTest,RentalCancellationUiTest,AdminBookingTest` |
| UI-06B | `BeforeInspectionTest,AfterReturnUiTest,InspectionPrivacyTest` |
| UI-06C | `CarDateSearchIntegrationTest,CarMutationAuthorizationTest,SubscriptionOwnerUiTest,SubscriptionQuotaTest` |
| UI-07 | `ProfilePasswordTest,ProfileSecurityTest,PaymentAccessAuditTest` |

Ví dụ chạy focused suite cho UI-01/02 và polling regression cho UI-03:

```powershell
.\mvnw.cmd "-Dtest=OwnerRevenueTest,FinancialViewsTest,PaymentAccessAuditTest" test
node --test src/test/js/payment-polling.test.cjs
```

Không suy ra build pass đồng nghĩa template đã render: cần request thật các route theo role trên fixture. Không thêm test kiểm tra tên class Tailwind; nếu thêm logic template/interaction mới, thêm assertion hành vi có ý nghĩa vào test/harness liên quan.

### Gate của mỗi lần bàn giao

- [ ] Đúng dữ liệu và action cho anonymous/Customer/Owner A/Owner B/Admin; kiểm tra trực tiếp URL sai quyền, POST thiếu CSRF và paging/filter theo phạm vi bị sửa.
- [ ] Desktop 1440×1000, tablet 1024×768, mobile 390×844 và 320×740; không tràn ngang toàn trang, không chồng header/sidebar, CTA rõ, bảng cuộn trong vùng có nhãn.
- [ ] Keyboard/focus, target control tối thiểu 44px, zoom 200%, lỗi form và trạng thái có chữ; no-JS giữ đọc/submit hiện hữu, không yêu cầu đủ style CDN khi JS tắt.
- [ ] Empty, nhiều hàng, text dài, tiền lớn, unavailable và các trạng thái tiền còn chờ; không dùng số 0 thay thiếu dữ liệu hoặc tự báo thành công.
- [ ] Các test liên quan và browser interaction đạt, ảnh trước/sau mới; ghi lỗi ban đầu và rerun riêng. Shared fragments phải render mọi caller bị ảnh hưởng.
- [ ] Hash backend/config/index giữ nguyên so với đầu đợt; handoff ghi file/route/role/state, lệnh/kết quả, ảnh, giới hạn và review bằng người. Dọn fixture rồi xác nhận port/schema do fixture tạo đã được đóng/dọn.

Evidence mỗi đợt đặt `.scratch/ui-XX/`; handoff ở `docs/plans/ui-XX-handoff.md`, dùng hậu tố A/B/C cho đợt đã tách. Chỉ đánh dấu nghiệm thu UI sau khi đủ evidence; ghi riêng phần review bằng người còn chờ.

## 7. Bước triển khai đầu tiên

> Chỉ thực hiện **UI-01** theo `docs/plans/ui-completion-2026-11-07.md` và bản đối chiếu `docs/plans/ui-worktree-analysis-2026-11-08.md`. Dùng root làm baseline, không đổi branch/backend/index/config. Tách sidebar/topbar, giữ public header contract, thay shell ở đủ 6 caller, chỉ tạo fragment UI khi có caller lặp thật. Kiểm chứng role/menu/logout/notification/active route bằng fixture và ảnh 1440/1024/390/320; lưu manifest/evidence/handoff rồi dừng trước UI-02. Không restart 8080, bật provider/scheduler, dùng tiền thật, commit hoặc push.

Phiên 08/11 đã kiểm kê/đối chiếu source và kiểm tra tài liệu; chưa chạy Maven, browser hoặc giao dịch. Snapshot cục bộ: `.scratch/ui-audit-20261108/source-audit.json`; đây là evidence hỗ trợ, không phải prerequisite để đọc kế hoạch trên máy khác.
