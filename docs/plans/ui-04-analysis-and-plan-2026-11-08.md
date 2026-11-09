# UI-04 — Phân tích và kế hoạch gói đăng xe / order

> **For agentic workers:** Dùng `superpowers:executing-plans`, thực hiện trong phiên hiện tại theo yêu cầu phân tích và triển khai của người dùng. Không dùng subagent; dừng sau UI-04. Các checkbox đã được cập nhật theo evidence trong [bàn giao UI-04](ui-04-handoff.md).

**Goal:** Hoàn thiện hai trang gói và order, giúp Owner phân biệt quyền đăng xe, yêu cầu thanh toán và khoản hoàn.

**Architecture:** Tái sử dụng workspace Owner UI-01, overview UI-02 và refund-links UI-03. Chỉ sửa `subscription/plans.html`, `subscription/detail.html` và regression trong `SubscriptionUiTest`; không cần sửa overview đã hoàn thiện, backend hoặc JS.

**Tech Stack:** Java 21, Spring Boot 4.1.1, Thymeleaf, Tailwind tokens hiện có; Maven fixture MySQL riêng và Chrome/Playwright.

**Spec:** [Backlog §3.5/UI-04](ui-completion-2026-11-07.md), [15 trang UI-04](ui-15-pages-readiness-2026-11-08.md), [SDD §8.7](../SDD.md), [bàn giao UI-03](ui-03-handoff.md).

## Global Constraints

- Làm tại root `feat/phase5-profile-password`, giữ dirty baseline; không tạo/chuyển worktree, checkout/reset/stash/stage/commit/push/merge.
- Không đổi Java main, schema/seed ứng dụng, root POM, `.env`, cấu hình provider, CSS/JS production, scheduler hoặc runtime 8080. Chỉ fixture schema/port riêng, fake provider; không gọi dịch vụ tiền thật.
- Giữ URL/method/form/CSRF, `subscription-plans`, `subscription-order`, `data-order-id/data-status`, `subscription-overview` và fragment signatures. Không thêm lịch sử order hoặc endpoint mới.
- Chỉ số tiền, quota, thời hạn từ catalog/snapshot/backend. Giữ VNĐ và giờ Việt Nam; không countdown client, không tự khẳng định đã trả hoặc đã hoàn.
- UI-05+ và gate human/native zoom 200%/live giữ riêng. Kiểm chứng viewport/CSS reflow không thay native zoom hoặc human review.

## 1. Kết quả phân tích source

`SubscriptionController` cung cấp `plans=SubscriptionPlan.values()` và `subscription=Overview`; detail có `order` và `intentRefunds`. Catalog hiện SMALL (4 xe/5.000), STANDARD (8 xe/10.000), BUSINESS (16 xe/15.000), tất cả 30 ngày; template phải bind các field, không chép literal giá/quota.

`Overview(plan,validUntil,active,reserved,pending,temporarilyHidden,quota)` đã render thành card trong UI-02. Ba caller thật: plans, Owner dashboard và Owner cars. Card đã có hiệu lực, số suất gồm xe pending, xe tạm ẩn, quy định hạ gói và link quản lý xe. Không cần viết lại hoặc thêm progress tính toán.

`SubscriptionOrderView(orderId,planCode,quotaSnapshot,priceSnapshot,durationDays,status,intent)` không có `expiresAt`, `canCancel` hoặc lịch sử. Hạn thanh toán dùng `order.intent.expiresAt`, không nhầm với hạn quyền đăng xe. GET detail suy EXPIRED khi tới hạn nhưng không ghi DB. Intent của order có model thật và có link hiện hành.

Các mutation giữ nguyên: POST `/owner/subscriptions/orders` với hidden `plan`; POST `/owner/subscriptions/orders/{id}/cancel` chỉ PENDING. Còn hạn chỉ gia hạn cùng tier; POST tier khác bị service từ chối dù sửa DOM. Mở lại cùng tier đang pending reuse order/intent và deadline. APPLIED dẫn quản lý xe; các khoản chuyển trùng vẫn refund riêng, không đổi APPLIED thành chưa kích hoạt.

Order là trang trong workspace Owner, không phải checkout giao dịch Layout A. Cả plans/detail dùng `sidebar('subscriptions',null)` và topbar; checkout/refund UI-03 vẫn dùng public header.

## 2. File và interface

| File | Thay đổi / kiểm tra |
|---|---|
| `templates/subscription/plans.html` | Shell Owner; header, overview cũ, 3 card cùng chiều cao; giá/30 ngày/quota rõ, badge gói active, tier khác có lý do đọc được, form thật |
| `templates/subscription/detail.html` | Shell Owner; header/status/snapshot/deadline/action riêng; link QR, cancel PENDING, APPLIED chọn xe, fragment refund cũ |
| `templates/subscription/overview.html` | Giữ nguyên; kiểm tra trên plans/dashboard/cars |
| `templates/payment/refund-links.html` | Giữ nguyên no-arg `links`, model masked `intentRefunds`; kiểm tra nhiều refund trên APPLIED |
| `SubscriptionUiTest.java` | Public seam GET catalog/detail hiện có: navigation Owner active và deadline intent hiển thị mà không đổi hạn |
| `.scratch/ui-04/` | Baseline, POM/output cách ly, browser fixture, ảnh/evidence; không đưa harness vào runtime sản phẩm |
| `docs/plans/ui-04-handoff.md`, README/SDD/checklists | Kết quả, giới hạn và điểm dừng |

## 3. Checkpoint A — Catalog gói

**UI-04 — 2026-11-08:** đã hoàn thiện gói đăng xe và order trong workspace Owner; tái sử dụng overview/refund-links. Focused **69/69**, Chrome **348 kiểm tra**, **40 ảnh trước/51 ảnh sau**; 20 bảng theo từng đoạn đọc, 5 POST/2 control audit, browser payout 0; fixture đã dọn. Xem [bàn giao UI-04](ui-04-handoff.md). Human visual review/native zoom 200% còn chờ; UI-05A trở đi và gate live giữ mở.

Consumes `plans`/`subscription`; produces Owner navigation và tier cards, giữ cùng POST contract.

- [x] Lưu manifest mới gồm index/config/HEAD/file xóa sẵn; baseline 6 suite; fixture chụp trước plans/detail và overview callers trên resource chưa sửa.
- [x] RED trong `SubscriptionUiTest` qua GET catalog: `assertThat(page("/owner/subscriptions")).contains("aria-label=\"Điều hướng chủ xe\"");` và link `/owner/subscriptions` có `aria-current="page"`. Không test tên class CSS.
- [x] Dùng shell hiện có, ví dụ `<th:block th:replace="~{fragments/workspace :: sidebar('subscriptions', null)}"></th:block>`; giữ overview, render ba card bind plan fields; badge current chỉ active và đúng tier. CTA cùng tier gia hạn, hết hạn/chưa mua chọn gói; tier khác không có POST và có lý do rõ.
- [x] Chạy GREEN catalog, kiểm tra no-plan/active/expired, pending tính vào quota, đầy/vượt quota không giấu số thật; dashboard/cars giữ overview.

## 4. Checkpoint B — Chi tiết order

Consumes `order`/`intentRefunds`; produces snapshot và status/action theo guard cũ, không đổi entitlement.

- [x] RED GET order PENDING: lấy `order.intent().expiresAt()` từ service, assert HTML chứa `<time` có datetime đúng và nhãn `Hạn thanh toán`; reload giữ expiry, một intent và status PENDING.
- [x] Dựng card tóm tắt/status/deadline; ví dụ `<time th:datetime="${order.intent.expiresAt}" th:text="${#temporals.format(order.intent.expiresAt.atZone(T(java.time.ZoneId).of('Asia/Ho_Chi_Minh')), 'dd/MM/yyyy HH:mm:ss') + ' (giờ Việt Nam)'}"></time>` trong guard `order.intent != null`. Giữ switch PENDING/APPLIED/EXPIRED/CANCELLED/REFUND_PENDING/fallback và guards/actions gốc.
- [x] GREEN tests, browser mỗi state và APPLIED có nhiều refund. Deadline được ghi rõ là hạn lần thanh toán, không phải hạn gói.

## 5. Checkpoint C — Kiểm chứng và bàn giao

- [x] Chạy `SubscriptionUiTest,SubscriptionOwnerUiTest,SubscriptionDowngradeTest,SubscriptionOrderTest,SubscriptionPaymentTest,WorkspaceUiTest` với POM riêng; giữ log baseline/RED/GREEN riêng.
- [x] Browser tại 1440/1024/390/320: no overflow, một h1, target control sửa ≥44px, keyboard/menu mobile/no-JS, escaping và flash error. Reflow 720 CSSpx ghi đúng giới hạn.
- [x] Native login Owner A/B, Admin/Customer/anonymous/stale; IDOR order/cancel và CSRF thiếu/enum sai bị từ chối. Dùng form thật tạo/reuse/cancel và gia hạn cùng tier; hạ gói sau expiry dùng real services/fake receipt và giữ bằng chứng xe không tự chọn.
- [x] Snapshot 20 bảng GET/read segment; POST/control tách segment, assert allowlist và tiền/recipient/deadline/entitlement/cars theo hành vi thật. Không đòi toàn phiên chứa mutation có snapshot trước/sau giống nhau; payout browser phải 0.
- [x] Dọn schema fixture/credentials/ready, xác nhận schema không tồn tại và port đóng; 8080/config/index/HEAD và baseline ngoài phạm vi giữ nguyên. Giữ log các lượt lỗi độc lập nếu có.
- [x] Cập nhật checklist UI-04 (5 ở backlog, 4 ở readiness), README/SDD và handoff, để UI-05+ mở; ghi riêng human review/native zoom/full suite/live chưa chạy.

Lệnh focused:

```powershell
.\mvnw.cmd -f .scratch/ui-04/pom.xml "-Dtest=SubscriptionUiTest,SubscriptionOwnerUiTest,SubscriptionDowngradeTest,SubscriptionOrderTest,SubscriptionPaymentTest,WorkspaceUiTest" test
```
