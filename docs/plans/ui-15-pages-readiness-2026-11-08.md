# CarRent — 15 trang UI: Implementation Preparation Plan

> **For agentic workers:** REQUIRED SUB-SKILL khi được giao triển khai: dùng `superpowers:executing-plans`, nhận một đợt trong tài liệu này, kiểm chứng và bàn giao rồi dừng. Chỉ dùng subagent khi người dùng yêu cầu. UI-01 đã triển khai và kiểm chứng cục bộ theo [bàn giao 08/11](ui-01-handoff.md); human visual review còn chờ. UI-02 đã kiểm chứng cục bộ theo [bàn giao](ui-02-handoff.md); UI-03 đã kiểm chứng cục bộ theo [bàn giao](ui-03-handoff.md); UI-04 đã kiểm chứng cục bộ theo [bàn giao](ui-04-handoff.md); UI-05A đã kiểm chứng cục bộ theo [bàn giao](ui-05A-handoff.md); UI-05B đã kiểm chứng cục bộ theo [bàn giao](ui-05B-handoff.md); UI-05C đã kiểm chứng cục bộ theo [bàn giao](ui-05C-handoff.md), hoàn tất scope 15 trang về implementation/local.

**Goal:** Hoàn thiện bố cục và thao tác của đúng 15 trang nhóm A trong inventory hiện có, bảo toàn nghiệp vụ đang nằm trong working tree.

**Architecture:** Giữ Spring MVC/Thymeleaf, model và form hiện hành. Tái sử dụng token Stitch, tách sidebar/topbar đang lặp rồi thiết kế từng nhóm trang; kiểm tra các caller ngoài 15 trang khi fragment thay đổi.

**Tech Stack:** Java 21, Spring Boot 4.1.1, Thymeleaf, Spring Security, MySQL, Tailwind Play CDN, Inter, Material Symbols; không thêm dependency.

**Spec:** [SDD §8.7](../SDD.md#87-hoàn-thiện-thiết-kế-ui-sau-các-đợt-triển-khai-chức-năng--2026-11-07), [inventory và wireframe 07/11](ui-completion-2026-11-07.md), [phân tích worktree 08/11](ui-worktree-analysis-2026-11-08.md), [Stitch](../design/stitch/README.md).

## 1. Global Constraints

- Phạm vi redesign là **15 trang nhóm A**. UI-06/UI-07 của backlog 42 trang chưa thuộc lần triển khai này; các caller dùng chung vẫn phải được kiểm tra hồi quy.
- Giữ URL, HTTP method, model, field, CSRF, điều kiện quyền/trạng thái, escaping, masking và các selector JS/test đang dùng. Không thêm API, thống kê, bộ lọc hoặc thao tác nghiệp vụ.
- Giữ Thymeleaf/Tailwind và giá trị token Stitch. Không React, Bootstrap, thư viện biểu đồ hoặc chuyển sang Tailwind build.
- Không sửa backend, schema/seed, provider config, `.env`, `application-local.properties`, scheduler hoặc restart runtime 8080 để làm UI. Chỉ fixture được dùng cho kiểm tra mutation.
- Không checkout/reset/stash/merge/stage/commit/push. Không tạo worktree từ HEAD rồi mặc định đã có những thay đổi chưa commit của root.
- UI tiếng Việt; tiền và thời điểm do model cung cấp, hiển thị giờ Việt Nam. Pending/processing không phải thành công; thiếu module không phải số 0.
- Kiểm chứng UI bằng provider giả và dữ liệu riêng; không chuyển tiền thật và không đóng gate provider/pilot bằng kết quả UI.

## 2. Worktree đã kiểm chứng lại

Khảo sát tại `C:/Users/Admin/Documents/hoc/Car`, ngày 08/11/2026, trước khi thêm tài liệu này. Dirty entry là dòng `git status --porcelain`, có thể đại diện một thư mục untracked.

| Checkout, tương đối từ root | Branch | HEAD | Tracked dirty / untracked entry |
|---|---|---|---:|
| `.` | `feat/phase5-profile-password` | `b829b05` | 37 / 45 |
| `.scratch/completion-20261105/worktree` | `feat/completion-profile-pilot` | `1862aa9` | 24 / 35 |
| `.scratch/publish-p57-p58/main` | `main` | `1862aa9` | 0 / 0 |
| `.scratch/publish-p57-p58/p57` | `feat/p57-owner` | `ff1f04f` | 0 / 0 |
| `.scratch/publish-p57-p58/p58` | `feat/p58-audit` | `d5c7b52` | 0 / 0 |

- Root/completion đều có **51 HTML = 42 trang + 9 fragment**. Template và static không khác nội dung sau chuẩn hóa CRLF/LF; không có phiên bản UI hoàn thiện hơn trong completion.
- Java khác nội dung ở `BookingService.java`, `SubscriptionService.java`; test khác ở `FinalRentalJourneyTest`, `SubscriptionOrderTest`, `SubscriptionOwnerUiTest`, `SubscriptionPaymentTest`. Đây là khác biệt cần bảo toàn, không phải phạm vi sửa UI.
- `main` đi trước root HEAD bốn commit (`ff1f04f`, `908219b`, `d5c7b52`, `1862aa9`), nhưng root working files đã có phần tích hợp chưa commit. Không suy ra source bị thiếu chỉ từ commit graph.
- Chưa có `fragments/workspace.html` hoặc `fragments/ui.html`. Các trang đích vẫn có bố cục tối thiểu: Owner/Admin chủ yếu dùng public header, link chữ, card/đoạn văn; finances có `aria-current` nhưng tab cùng kiểu underline.
- Có 9 dòng dùng `font-title-lg`/`font-title-sm` ngoài token hiện có, ở 5 file. Đợt sở hữu trang sửa token liên quan; `booking/fragments.html` chỉ sửa nếu cần cho phần dùng chung, kèm hồi quy các caller.
- Đã xem hai ảnh Stitch Owner quản lý xe và Admin quản lý người dùng. Prototype chỉ có **13 bộ HTML/PNG**, không phải 15 mockup riêng cho 15 trang; wireframe trong kế hoạch 07/11 bổ sung phần thiếu.
- Java mặc định là Temurin **21.0.11**, Node **22.21.1**, Python **3.14**. Chưa kiểm tra kết nối MySQL hoặc chạy Maven/browser trong phiên chuẩn bị.

### Lựa chọn cách triển khai

1. **Khuyến nghị: tiếp tục tại root**, lập manifest trước/sau, giới hạn diff theo đợt. Có đủ source hiện hành; cần kiểm soát chặt file dùng chung và dirty baseline.
2. Worktree mới từ `main`: cách ly tốt hơn nhưng cần một bước tích hợp prerequisite riêng, đưa từng thay đổi chưa commit cần thiết sang bằng manifest rồi kiểm chứng. Chưa sẵn sàng chỉ bằng `git worktree add`.
3. Sửa từng trang và lặp sidebar: tránh thay shell ban đầu nhưng duy trì nhiều bản navigation khác nhau; không chọn vì sáu trang đã lặp shell thực tế.

**Trạng thái:** đủ dữ liệu để bắt đầu UI-01 khi được giao triển khai; chưa có baseline runtime/ảnh mới để nghiệm thu. Quyết định này không xác nhận backend hay provider đã sẵn sàng live.

## 3. Đúng 15 trang và sản phẩm cần đạt

Đường template tính từ `src/main/resources/templates/`. Mỗi trang chỉ có một đợt sở hữu redesign.

| # | Template | Route | Sản phẩm UI | Đợt |
|---:|---|---|---|---|
| 1 | `owner/dashboard.html` | `/owner/dashboard` | Shell Owner; KPI xe/đơn; gói; tiền đã phân bổ; cảnh báo hoàn/nợ tách biệt | UI-02 |
| 2 | `payment/finances.html` | `/payment/finances` | Tab có active style; nhãn phạm vi; bảng/card ba nguồn; paging giữ view | UI-02 |
| 3 | `payment/checkout.html` | `/payment/intents/{id}` khi ACTIVE | QR nguyên tỉ lệ; số tiền/nội dung/hạn rõ; ngữ cảnh 30%/70%/gói; tải lại/hủy | UI-03 |
| 4 | `payment/result.html` | `/payment/intents/{id}/result`, hoặc intent đã đổi trạng thái | Panel kết quả theo backend; số tiền; bước tiếp theo; hoàn tiền riêng | UI-03 |
| 5 | `payment/refund.html` | `/payment/refunds/{id}` | Nhập tài khoản → xem preview → xác nhận; theo dõi; lỗi đúng bước | UI-03 |
| 6 | `payment/refund-summary.html` | `/payment/finances/refunds/{id}` | Summary trạng thái/tiền/hạn/quá hạn, tài khoản theo quyền, link recipient | UI-03 |
| 7 | `subscription/plans.html` | `/owner/subscriptions` | Shell Owner; gói hiện hành/quota; ba tier từ catalog; CTA mua/gia hạn đúng điều kiện | UI-04 |
| 8 | `subscription/detail.html` | `/owner/subscriptions/orders/{id}` | Snapshot order; status; payment/refund links; hủy theo trạng thái | UI-04 |
| 9 | `admin/dashboard.html` | `/admin/dashboard` | Shell Admin; KPI toàn hệ thống tách phí gói ví Admin; cảnh báo có deep link | UI-05A |
| 10 | `admin/finance.html` | `/admin/finance` | Ba kind; toolbar/filter; danh sách desktop/mobile; paging giữ đủ query | UI-05A |
| 11 | `admin/finance-detail.html` | `/admin/finance/{kind}/{id}` | Summary; nguồn thu/chi; phân bổ/hạn; navigation; chỉ đọc | UI-05A |
| 12 | `admin/users.html` | `/admin/users` | Bảng desktop/card mobile theo Stitch; tìm/lọc/paging; badge và xem hồ sơ | UI-05B |
| 13 | `admin/user-detail.html` | `/admin/users/{id}` | Hồ sơ; số liệu liên quan; form trạng thái tách rõ; self-change guard | UI-05B |
| 14 | `admin/bookings.html` | `/admin/bookings` | Filter status/attention; danh sách/table/card; badge; legacy và VietQR phân biệt | UI-05C |
| 15 | `admin/booking-detail.html` | `/admin/bookings/{id}` | Summary → timeline → bằng chứng → tài chính; Admin chỉ đọc | UI-05C |

Tổng: **2 + 4 + 2 + 3 + 2 + 2 = 15**. UI-01 là prerequisite dùng chung, không cộng thêm trang redesign.

## 4. File dùng chung và hợp đồng phải giữ

| File/module | Interface đang dùng hoặc dự kiến | Caller phải kiểm tra |
|---|---|---|
| `fragments/workspace.html` — tạo trong UI-01 | `sidebar(activeSection, financeView)`, `topbar(title)` | Sáu trang đã có shell và các trang Owner/Admin được chuyển sang trong từng đợt |
| `fragments/header.html` | Giữ `header`, `authHeader`, `notificationBell`; active public dùng biến scope tùy chọn `publicNavSection` | Tất cả caller tìm bằng `rg`, cả trang ngoài nhóm A |
| `fragments/head.html`, `messages.html`, `pagination.html` | Giữ tên/chữ ký hiện hành; chỉ đổi khi có lỗi cụ thể | Render mọi caller bị ảnh hưởng; pagination đặc thù giữ query riêng |
| `subscription/overview.html` | `overview`, model `subscription`; ID `subscription-overview` | Owner dashboard, plans, owner/cars |
| `payment/refund-links.html` | `links`, model `intentRefunds`; ID `intent-refunds` | Payment result, subscription detail |
| `admin/finance-row.html` | `fields(r)` | Finance list/detail, Admin booking detail |
| `booking/fragments.html` | Giữ `progressBadge(booking)`, `rentalPaymentSummary(booking)`, `timeline` | Admin list/detail và các trang Customer/Owner gọi cùng fragment |
| `static/js/payment-polling.js` | Giữ nguyên JS, ID/data attributes mà script đọc | Checkout, result, refund, refund summary |

`activeSection` thuộc `dashboard/cars/bookings/subscriptions/users/finances`; `financeView` là `paid/collected/refunds/managed` hoặc `null`. Backend vẫn kiểm tra quyền; presentation state không cấp quyền.

Layout B dùng sidebar 256px từ `lg`/1024px, topbar 64px; dưới breakpoint dùng menu có keyboard và no-JS. Topbar dùng principal và `notificationBell`, không yêu cầu mỗi controller thêm tên người dùng. Layout A giữ cho checkout/result/refund và Customer finances; Owner/Admin finances dùng shell theo role. Subscription detail giữ khối order gọn trong shell Owner.

Ví dụ interface để các đợt sau cùng dùng:

```html
<div th:replace="~{fragments/workspace :: sidebar('dashboard', null)}"></div>
<div class="lg:pl-64 min-w-0">
  <header th:replace="~{fragments/workspace :: topbar('Tổng quan chủ xe')}"></header>
  <main class="p-4 lg:p-6 min-w-0"></main>
</div>
```

Đây là contract bố cục dự kiến, chưa phải code được triển khai. Không tạo `fragments/ui.html` trừ khi có ít nhất hai caller lặp thật; không trừu tượng hóa form/CSRF/field binding.

### Dữ liệu, form và selector nhạy cảm

- Dashboard Owner: `stats`, `subscription`, `subscriptionAvailable`; giữ `owner-dashboard`, `owner-counts`, unavailable IDs và mọi `data-metric`. `stats.publicCars == null` vẫn là chưa có dữ liệu.
- Finances: `view`, `obligations`, `refunds`, `reviewReceipts`, `pageNumber`; ba nguồn Page dùng chung page index hiện tại. Không thêm tên xe/khách/ngày khi row không có field đó. Link latest intent chỉ cho `o.payer`; refund account dùng đúng model đã che.
- Checkout/result: model `payment`, `intentRefunds`; giữ `payment-checkout`, `financial-status`, `payment-status`, `polling-message`, `payment-qr`, `payment-cancel`, `balance-result` và các `data-status-url/result-url/expires-at/booking-advance/intent/state/terminal` tương ứng. Không đổi ý nghĩa terminal để đơn giản hóa badge.
- Refund: `refund`, `recipientForm`, `destination`; giữ `bankBin`, `accountNumber`, `saveForLater`, `confirmedAccountName`, checkbox `confirmed`, preview/confirm POST và validation 400/409. Verifier lỗi phải hiện lỗi thật; không thêm trường nhập tên người nhận thủ công.
- Subscription: `plans`, `subscription`, `order`, `intentRefunds`; field POST `plan`; giữ `subscription-plans`, `subscription-order`, `data-order-id/status`, giá/quota/duration snapshot. Không tính lại snapshot bằng catalog hiện tại.
- Admin finance: `filter`, `rows`, `statuses`, `kind`, `row`; giữ `kind/status/overdue/subjectType/subjectId/collectorId/sort/page`.
- Admin user: `users/q/role/status`, `detail/self`, `statusLabels/roleLabels`; POST chỉ status và CSRF. Không thêm input email/role hoặc cho tự đổi trạng thái.
- Admin booking: `rows/statusFilter/attention/statuses/statusLabels`, `booking/timeline/finance`; filter `status/attention/page`; giữ BEFORE/AFTER chỉ khi được submit và nhánh legacy khi timeline null. Không thêm action giao/nhận xe/chi tiền.

## 5. Bảy đợt thực hiện tuần tự

Mỗi đợt: đọc source/caller → tạo baseline fixture/ảnh → sửa markup tối thiểu → chạy focused suite và browser → hash audit → handoff. UI-01/02 đã đạt kiểm chứng cục bộ theo bàn giao; checkbox còn mở là công việc tương lai. Tên test đã xác nhận có trong `src/test/java/com/carrent/payment/`.

### UI-01 — Shell và fixture baseline

**Files:** tạo `templates/fragments/workspace.html`; sửa `templates/fragments/header.html`; thay shell ở `owner/cars.html`, `owner/car-form.html`, `owner/bookings.html`, `owner/booking-detail.html`, `admin/cars.html`, `admin/car-detail.html`. Head/messages/pagination chỉ sửa nếu có lỗi đã tái hiện.

**Consumes:** prototype Owner/Admin; principal, notificationBell, menu/route hiện hành. **Produces:** interface workspace §4, active public header, baseline test/browser có thể dùng lại.

- [x] Ghi manifest source/index; kiểm tra fixture MySQL/schema/flags/provider giả; tạo ảnh trước từ source root mới.
- [x] Port sidebar/topbar đúng role; sửa đồng bộ breakpoint/offset ở sáu caller; giữ logout POST, notification, menu tài khoản và form nội dung trang.
- [x] Public header không tô sáng Trang chủ cố định; biến scope thiếu thì không tự chọn Trang chủ. Caller cần active phải truyền scope trong wrapper, giữ fragment không tham số cũ.
- [x] Chạy `OwnerRevenueTest,FinancialViewsTest,PaymentAccessAuditTest`; kiểm tra role/menu/active/logout/notification, đủ sáu caller và public/auth bị ảnh hưởng ở bốn viewport.
- [x] Lưu `.scratch/ui-01/` và `docs/plans/ui-01-handoff.md`; dừng trước UI-02.

### UI-02 — Dashboard Owner và finances (2/15)

**UI-02 — 2026-11-08:** dashboard Owner, finances đủ 9 role/view và overview dùng chung đã triển khai, kiểm chứng cục bộ: focused **47/47**, Chrome **1233 kiểm tra**, **28 ảnh trước/139 ảnh sau**; 20 bảng fixture không đổi, provider 0, cleanup đã xác nhận. Xem [bàn giao UI-02](ui-02-handoff.md). Human visual review/native zoom 200% còn chờ; UI-03 trở đi và gate live giữ mở.

**Files:** `templates/owner/dashboard.html`, `templates/payment/finances.html`, `templates/subscription/overview.html`. **Consumes:** workspace UI-01 và model §4. **Produces:** hai trang ưu tiên cùng overview dùng lại trong UI-04.

- [x] Chụp fixture Owner bình thường/không gói/hết hạn/module tắt, finances đủ bốn view; đọc wireframe §3.2/3.3 của kế hoạch 07/11.
- [x] Dashboard: heading/CTA → KPI xe/đơn → gói → tiền đã ghi nhận → cảnh báo; sửa token font không tồn tại. Không biến tổng booking thành số việc cần xử lý.
- [x] Finances: active tab có màu/nền rõ, nhãn scope; obligations/refunds/reviewReceipts giữ section và empty riêng; bảng/card có nhãn tiền và paging theo view. Không gộp thành ledger hoặc thêm tổng mới.
- [x] Chạy `OwnerRevenueTest,FinancialViewsTest,PaymentAccessAuditTest`; Customer chỉ paid/refunds, Owner thêm collected, Admin đủ bốn view; kiểm tra Owner A/B và overview trên plans/cars.
- [x] Ảnh trước/sau + interaction, `.scratch/ui-02/`, `docs/plans/ui-02-handoff.md`; dừng.

### UI-03 — Checkout/result/refund (4/15)

**UI-03 — 2026-11-08:** đã triển khai checkout/result/refund/refund-summary và refund-links; focused **135/135**, Node **8/8**, Chrome **828 kiểm tra + 28 bổ sung**, **36 ảnh trước/138 ảnh sau**. GET/poll/preview kiểm tra 20 bảng theo từng đoạn; 4 POST audit giữ invariant, fake payout 0; cleanup đã xác nhận. Xem [bàn giao UI-03](ui-03-handoff.md). Human visual review/native zoom 200% còn chờ; UI-04 trở đi và các gate live giữ mở.

**Files:** `templates/payment/checkout.html`, `result.html`, `refund.html`, `refund-summary.html`, `refund-links.html`. **Consumes:** model/selector §4. **Produces:** presentation thanh toán/hoàn tiền thống nhất; JS polling giữ nguyên.

- [x] Chụp checkout 30%/70%/gói; result ACTIVE/RECEIVED/EXPIRED/CANCELLED/REFUND_PENDING/REFUNDED/UNAVAILABLE và trường hợp cần kiểm tra theo backend.
- [x] Layout số tiền/QR/nội dung/hạn; result nổi bật trạng thái và bước tiếp theo; refund có nhập → preview → confirm, trạng thái chờ/tạm thiếu số dư/processing/succeeded/quá hạn.
- [x] Giữ hủy đặt xe khác hủy intent; reload không đổi hạn QR hoặc hạn nợ; xác nhận tài khoản không được báo đã hoàn tiền.
- [x] Chạy `PaymentAccessAuditTest,RentalBalanceUiTest,RentalCancellationUiTest,RefundRecipientTest,RefundServiceTest` và Node polling suite; browser kiểm tra mạng lỗi/401/403/expiry, lỗi form 400/409, masking và no-JS tải lại/submit.
- [x] `.scratch/ui-03/`, `docs/plans/ui-03-handoff.md`; ghi rõ trạng thái nào dùng verifier/provider giả; dừng.

### UI-04 — Gói và order (2/15)

**UI-04 — 2026-11-08:** đã hoàn thiện gói đăng xe và order trong workspace Owner; tái sử dụng overview/refund-links. Focused **69/69**, Chrome **348 kiểm tra**, **40 ảnh trước/51 ảnh sau**; 20 bảng theo từng đoạn đọc, 5 POST/2 control audit, browser payout 0; fixture đã dọn. Xem [bàn giao UI-04](ui-04-handoff.md). Human visual review/native zoom 200% còn chờ; UI-05A trở đi và gate live giữ mở.

**Files:** `templates/subscription/plans.html`, `detail.html`, `overview.html`. **Consumes:** workspace/overview UI-01/02, refund-links UI-03. **Produces:** hai trang gói dùng catalog/snapshot thật.

- [x] Fixture no-plan/active/expired/quota đầy/pending/tạm ẩn; đổi plans sang shell Owner, làm rõ ba card tier và CTA mua/gia hạn.
- [x] Order PENDING/APPLIED/EXPIRED/CANCELLED/REFUND_PENDING; tách summary/status/action; giữ deadline, payment links và refund từng khoản.
- [x] Chạy `SubscriptionUiTest,SubscriptionOwnerUiTest,SubscriptionDowngradeTest,SubscriptionOrderTest,SubscriptionPaymentTest`; kiểm tra gia hạn cùng tier, khóa tier khác khi còn hạn, hạ gói sau expiry, Owner A/B, overview trên dashboard/cars.
- [x] `.scratch/ui-04/`, `docs/plans/ui-04-handoff.md`; dừng.

### UI-05A — Dashboard và finance Admin (3/15)

**UI-05A — 2026-11-08:** đã hoàn thiện dashboard và tài chính Admin (3 trang), giữ `fields(r)` cho booking. Focused **32/32**, Chrome **427 kiểm tra**, **36 ảnh trước/53 ảnh sau**; 20 bảng GET không đổi theo đoạn và toàn phiên sau phục hồi control; payout 0, fixture đã dọn. Xem [bàn giao UI-05A](ui-05A-handoff.md). Còn **2 đợt / 4 trang: UI-05B và UI-05C** trong phạm vi 15 trang; human visual review/native zoom 200% và gate live giữ riêng.

**Files:** `templates/admin/dashboard.html`, `finance.html`, `finance-detail.html`, `finance-row.html`. **Consumes:** workspace, model Admin §4. **Produces:** ba trang Admin và `fields(r)` cho UI-05C.

- [x] Dashboard tách KPI toàn hệ thống, phí gói ví Admin, cảnh báo; không coi gross cash là doanh thu khả dụng hoặc gộp tiền Owner.
- [x] Finance: tab OBLIGATIONS/RECEIPTS/REFUNDS có active style; toolbar/filter; bảng/card/detail nhóm nguồn/tiền/trạng thái/hạn; pagination giữ đủ tám query parameter.
- [x] Chạy `AdminRevenueTest,AdminPermissionsTest`; render finance-row trong Admin booking detail hiện hành, filter lỗi vẫn 400, role thường bị chặn, GET không phát lệnh chi.
- [x] `.scratch/ui-05A/`, `docs/plans/ui-05A-handoff.md`; dừng.

### UI-05B — User list/detail Admin (2/15)

**UI-05B — 2026-11-08:** đã hoàn thiện danh sách/hồ sơ tài khoản Admin (2 trang), giữ self/CSRF/status-only. Focused **22/22**, Chrome **296 kiểm tra**, **16 ảnh trước/37 ảnh sau**; 20 bảng đọc theo đoạn, 3 POST audit chỉ target status/updatedAt, payout 0; fixture đã dọn. Xem [bàn giao UI-05B](ui-05B-handoff.md). Đã triển khai **13/15 trang**, còn **UI-05C: 1 đợt / 2 trang** và smoke cuối. Human/native zoom/live giữ riêng.

**Files:** `templates/admin/users.html`, `user-detail.html`. **Consumes:** workspace và prototype `admin-quan-ly-nguoi-dung.html`. **Produces:** hai trang quản lý user, không mở rộng quyền.

- [x] Port table/card/filter theo Stitch; dùng tên/email/role/status từ model thật; không copy CTA thêm/xóa user trong prototype khi app không có.
- [x] Detail tách hồ sơ/số liệu/form status, giải thích giới hạn; self-change vẫn không có form; email/role chỉ đọc.
- [x] Chạy `AdminUserServiceTest,AdminUserPermissionsTest`; browser tìm/lọc/paging, ACTIVE/INACTIVE/BANNED, self-change, CSRF và tên/email dài/escaped.
- [x] `.scratch/ui-05B/`, `docs/plans/ui-05B-handoff.md`; dừng.

### UI-05C — Booking list/detail Admin (2/15)

**UI-05C — 2026-11-08:** đã hoàn thiện booking list/detail Admin, đủ **15/15 trang** về implementation/kiểm chứng local. Suite cuối **89/89**, Node **8/8**, Chrome **482 kiểm tra**, **20 ảnh trước/109 ảnh sau**; smoke cuối đủ 15 trang và shared callers, 20 bảng đọc không đổi, payout 0 và fixture đã dọn. Xem [bàn giao UI-05C](ui-05C-handoff.md). Còn **0 đợt** trong scope gốc; human/native zoom/live giữ riêng, chưa triển khai UI-06/UI-07.

**Files:** `templates/admin/bookings.html`, `booking-detail.html`; `templates/booking/fragments.html` chỉ nếu cần sửa phần dùng chung. **Consumes:** workspace, `fields(r)`, timeline/badge hiện hành. **Produces:** hai trang cuối, đủ 15/15 về phạm vi thực hiện khi đã đạt các gate.

- [x] List: filter status/attention/paging; detail: summary → payment summary/timeline → bằng chứng → obligations/receipts/refunds. Sửa token font không hợp lệ của trang; legacy vẫn có nội dung riêng.
- [x] Giữ Admin chỉ đọc; nhánh chưa có timeline/bằng chứng/tài chính có thông điệp phù hợp. Nếu sửa booking fragment phải render Customer/Owner callers và chạy `RentalTimelineTest` thêm.
- [x] Chạy `AdminBookingTest,AdminPermissionsTest`; browser deep link tài chính/BEFORE/AFTER, overdue, legacy, empty, nhiều hàng và URL sai quyền.
- [x] Chạy lại smoke 15 trang và caller dùng chung trên source cuối; `.scratch/ui-05C/`, `docs/plans/ui-05C-handoff.md`; kết thúc phạm vi, chưa tự làm UI-06/UI-07.

## 6. Chuẩn bị kiểm chứng và điều kiện đóng đợt

**Fixture:** đọc `PaymentTestDatabase.java` và `PaymentFlowFixture.java`. Fixture tạo schema `carrent_p43_<UUID>` riêng nhưng dùng server/credential datasource theo cấu hình local/environment; phải xác minh server target trước khi chạy. Boot dùng port 0 và provider giả; kiểm tra lại mọi flag runtime trong harness mới. Harness browser P5.7 chỉ là tài liệu tham khảo: có đường Playwright cache/ready file riêng, không chạy nguyên lệnh cũ hoặc lấy ảnh cũ làm baseline.

Ví dụ lệnh tương lai tại root, sau khi xác minh fixture (phiên chuẩn bị chưa chạy):

```powershell
.\mvnw.cmd "-Dtest=OwnerRevenueTest,FinancialViewsTest,PaymentAccessAuditTest" test
node --test src/test/js/payment-polling.test.cjs
```

Dùng danh sách test của từng đợt trong `-Dtest=...`; không chạy lại toàn bộ suite cho mỗi thay đổi spacing. Template phải được render qua request theo role; build pass riêng không đủ.

- [ ] Request đúng role cho mọi trang sửa; anonymous/Customer/Owner A/B/Admin bị giới hạn như baseline, POST thiếu CSRF bị chặn, filter/paging/deep link không đổi scope.
- [ ] Viewport 1440×1000, 1024×768, 390×844, 320×740; không tràn toàn trang/chồng nav; bảng cuộn trong vùng có nhãn; QR không crop.
- [ ] Keyboard/focus, zoom 200%, tên control/lỗi field, target thao tác tối thiểu 44px; status có chữ; no-JS giữ đọc/submit/tải lại hiện hành. Tailwind Play CDN cần JS, không cam kết đủ style khi tắt JS.
- [ ] Fixture empty/nhiều trang/text dài/tiền lớn/module unavailable và trạng thái chờ; số liệu không đổi so với baseline, tài khoản vẫn che đúng quyền, GET không phát lệnh tiền.
- [ ] Focused suites/polling/browser đạt; có ảnh trước/sau mới cho từng trang đổi bố cục, kết quả thất bại ban đầu và rerun ghi riêng. Shared fragment đổi thì render đủ caller.
- [ ] Audit hash xác nhận backend/config/index không đổi ngoài allowlist; dọn schema/port do fixture sở hữu; handoff ghi routes/roles/states/test/ảnh/giới hạn. Human visual review vẫn `chờ review` đến khi có người review.

Assertion browser tối thiểu có thể đưa vào harness hiện hành, không kiểm tra chuỗi class Tailwind:

```javascript
if (await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 1)) {
  throw new Error('Trang bị tràn ngang');
}
// Chỉ dùng ở route finances đã đăng nhập đúng role/view.
const selected = page.locator('nav[aria-label="Phạm vi tài chính"] [aria-current="page"]');
if (await selected.count() !== 1) throw new Error('Phạm vi hiện tại không duy nhất');
```

Ảnh và kiểm tra computed style phải xác nhận active tab nhìn thấy được; assertion `aria-current` riêng chưa đủ. Logic/template interaction mới cần một assertion hành vi có ý nghĩa, không thêm test chỉ bắt tên class.

## 7. Kết quả phiên chuẩn bị và lệnh giao việc

Mục này lưu kết quả phiên chuẩn bị trước khi triển khai UI-01. Trạng thái implementation hiện tại nằm trong [bàn giao UI-01](ui-01-handoff.md); không chạy lại lệnh giao đợt đầu dưới đây như một đợt mới.

Đã kiểm chứng Git/worktree, inventory, template/controller/model/JS/fixture/caller và sự tồn tại test; đã xem hai ảnh prototype. Manifest trước/sau và kiểm tra tài liệu nằm tại `.scratch/ui-15-readiness-20261108/`; chỉ chứa đường dẫn/hash/trạng thái, không sao chép nội dung secret. Không sửa runtime source, không chạy Maven/browser/DB hoặc giao dịch. Các kế hoạch 07/11 và bản phân tích 08/11 được giữ nguyên.

Lệnh giao đợt đầu:

> Chỉ triển khai **UI-01** theo `docs/plans/ui-15-pages-readiness-2026-11-08.md`, đối chiếu SDD §8.7 và kế hoạch UI 07/11. Dùng root hiện hành, bảo toàn dirty baseline bằng manifest, tạo sidebar/topbar và thay shell đủ sáu caller; giữ public header contract. Dùng fixture riêng/provider giả, kiểm chứng role/menu/active/logout/notification và bốn viewport, lưu evidence/handoff rồi dừng trước UI-02. Không sửa backend/config/index, restart 8080, chuyển tiền thật, commit/push hoặc mở rộng sang UI-06/UI-07.
