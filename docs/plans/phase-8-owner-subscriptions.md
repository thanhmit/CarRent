# CarRent — Phase 8: Gói đăng xe Owner — Implementation Plan

> **For agentic workers:** Dùng superpowers:executing-plans theo task; không tự stage/commit/push. Chỉ dùng subagent khi được yêu cầu.

**Ngày:** 2026-10-27. **Trạng thái:** kế hoạch mới, chưa triển khai.

**Goal:** Owner mua gói 30 ngày qua SePay vào Admin, quản lý hạn mức xe và tự động hoàn các khoản mua gói trả trùng/đến muộn từ Admin.

**Architecture:** Subscription order snapshot quyền lợi và giá, nối một payment obligation của Phase 4. Một hàng entitlement theo Owner giữ hạn hiện tại; áp dụng receipt và cộng hạn cùng transaction. Quyền hiển thị/đặt xe được kiểm tra trực tiếp theo thời điểm hết hạn, không phụ thuộc job có chạy kịp hay không.

**Tech Stack:** Spring MVC/Thymeleaf/JPA/MySQL, payment core và payout của Phase 4; Clock và Car queue/DB locking hiện có.

**Spec:** [README](README.md) mục 2, 4–6; [SDD](../SDD.md) mục 1.5–1.6.

**Phân công:** `ducbinhnguyen07ars` phụ trách toàn bộ task; `thanhmit` review, `huy69332` review thêm tích hợp quota/Car/Booking. Xem [phân công nhóm](team-assignment.md).

## Global Constraints

- Chạy sau Phase 7/4/6, trước Phase 5 chốt dashboard. Không triển khai nâng/hạ giữa kỳ hoặc self-service onboarding nhiều Owner.
- SMALL: 4 xe / 5.000 VND; STANDARD: 8 xe / 10.000 VND; BUSINESS: 16 xe / 15.000 VND. Giá tạm thời, mỗi lần mua đúng **30 ngày**.
- Còn hạn chỉ mua cùng loại gói; gia hạn cộng vào hạn cũ. Đã hết hạn có thể đổi loại; thời hạn mới từ activation +30 ngày.
- Hạn mức tính xe đang đăng và đang chờ duyệt; xe chủ động ẩn/bị từ chối không chiếm suất.
- Tiền gói vào ngân hàng Admin; hoàn từ ví/kênh chi Admin. Không dùng tiền thuê Owner làm doanh thu Admin.
- QR 15 phút; gói đã kích hoạt không hoàn theo ngày/suất chưa dùng. Hai lần chuyển thật cùng yêu cầu chỉ cấp gói một lần, hoàn khoản trùng.
- Khóa quota theo Owner. Car queue theo từng xe không đủ bảo vệ suất cuối của hai xe khác nhau.
- Các quy tắc giữ dữ liệu, credential, webhook, refund và gate live trong README áp dụng đầy đủ.

## Map file

Java dưới `src/main/java/com/carrent/`:

- Create `subscription/SubscriptionPlan.java`, `SubscriptionOrder.java`, `OwnerSubscription.java`, repositories, `SubscriptionService.java`, `SubscriptionPolicy.java`, `SubscriptionPaymentHandler.java`, `SubscriptionMaintenanceJob.java`, `SubscriptionController.java`, `SubscriptionOrderView.java`.
- Modify `car/CarService.java`, `CarRepository.java`, `CarSpecifications.java`, `CarController.java`, `OwnerCarController.java`, `AdminCarController.java`; `booking/BookingService.java`; `web/HomeController.java`; `user/UserRepository.java` thêm owner lock khi cần.
- Create templates `subscription/plans.html`, `subscription/detail.html`; modify `owner/cars.html`, `owner/car-form.html`, public listing/detail/home và fragments quyền lợi.
- Migration dự kiến `docs/migrations/2026-10-27-03-owner-subscriptions.sql`, update `scriptDB.sql`.
- Tests `src/test/java/com/carrent/subscription/SubscriptionPolicyTest.java`, `SubscriptionPaymentTest.java`, `SubscriptionQuotaTest.java`, `SubscriptionVisibilityTest.java`.

### Task P8.1 — Catalog, migration và dữ liệu quyền lợi

**Phụ trách:** `@ducbinhnguyen07ars` · **Review:** `@thanhmit`.

- [ ] Tạo catalog 3 gói với code/quota/price/duration đúng Global Constraints; chưa cần Admin CRUD bảng giá. Không dùng giá client gửi.
- [ ] Tạo subscription_orders và owner_subscriptions theo README; ownerId entitlement UNIQUE, order.obligationId UNIQUE, appliedAt nullable; trạng thái order PENDING/APPLIED/EXPIRED/CANCELLED/REFUND_PENDING.
- [ ] Mỗi order lưu plan_code/quota_snapshot/price_snapshot/duration_days=30; Java dùng planCode/quotaSnapshot/priceSnapshot/durationDays. Liên kết nghĩa vụ bằng business key SUBSCRIPTION:orderId; không cần bookingId giả hoặc sửa payment legacy.
- [ ] Test schema/checks và giữ nguyên seed/xe cũ. Migration không tự gán gói miễn phí cho mọi Owner; fixture kiểm chứng có entitlement riêng.

### Task P8.2 — Tạo yêu cầu mua/gia hạn và QR 15 phút

**Phụ trách:** `@ducbinhnguyen07ars` · **Review:** `@thanhmit`.

**Interfaces:** `SubscriptionOrderView createOrder(SubscriptionPlan plan, CustomUserPrincipal actor)`; `void cancelOrder(Long orderId, CustomUserPrincipal actor)`. View chứa orderId, snapshot plan/quota/price, intent view, status.

- [ ] Viết test Owner đúng role/ACTIVE, không mua gói khác khi còn hạn, một order ACTIVE mỗi Owner dù double POST.
- [ ] Khóa hàng Owner trước đọc entitlement và pending order; nếu có pending hợp lệ thì trả lại yêu cầu đó, không tạo thêm QR khác. Owner chỉ xem/hủy order của mình.
- [ ] Tạo order + obligation + intent cùng transaction, deadline từ backend +15 phút. Checkout dùng QR tài khoản thu Admin; refresh không gia hạn.
- [ ] Hủy/hết hạn kết thúc order/intent, không xóa dữ liệu và không mất hạn gói hiện tại. Tiền về sau canceled order dùng mặc định hoàn 100% trong README.
- [ ] Test tamper amount/collector/ownerId, queue không có carId không được lấy một carId giả; đây là transaction khóa Owner, không Car queue.

### Task P8.3 — Áp dụng thanh toán và gia hạn đúng một lần

**Phụ trách:** `@ducbinhnguyen07ars` · **Review:** `@thanhmit`.

**Interfaces:** `void applyPayment(Long receiptId)` trong SubscriptionService, được SubscriptionPaymentHandler subject SUBSCRIPTION gọi cùng apply transaction của Phase 4.

- [ ] Viết test chuyển khoản đầu/trùng webhook/chuyển lần hai thật; assert một lần cấp quyền lợi và đúng khoản hoàn cho tiền trùng.
- [ ] Implement PaymentDomainHandler.lockSubject(orderId): khóa Owner → entitlement → order; nếu hạ gói, khóa các Car cần ẩn theo ID tăng trước khi core khóa obligation/intent/receipt. Trong applyPayment dùng các khóa aggregate đã có; không lấy Car sau khi đã khóa tiền. Kiểm tra tài khoản nhận Admin, code, amount và creditedAt; order đã applied thì receipt mới tạo hoàn DUPLICATE_PAYMENT, không cộng thêm ngày.
- [ ] Thời hạn:

```java
Instant base = current != null && current.validUntil().isAfter(now)
    ? current.validUntil() : now;
Instant nextExpiry = base.plus(Duration.ofDays(30));
```

Khi còn hạn, loại gói phải trùng. Order.appliedAt và entitlement update trong cùng commit; provider retry không được cộng lại.

- [ ] Thực sự trả sau expiresAt: LATE_SUBSCRIPTION → hoàn 100% từ Admin, không cấp gói. Trả đúng hạn nhưng webhook chậm: kiểm tra creditedAt và entitlement hiện tại; không ghi đè một gói khác không tương thích, dùng refund INCOMPATIBLE_SUBSCRIPTION theo mặc định README.
- [ ] Lệch amount mua gói: receipt REVIEW_REQUIRED, không cấp gói hoặc giả lập đã hoàn. Payout từ Admin đi qua RefundService chung, không tự viết HTTP chi trong SubscriptionService.
- [ ] Test các biên validUntil==now, creditedAt==expiresAt, hai order/webhook cạnh tranh, lỗi notify rollback cả entitlement và appliedAt.

### Task P8.4 — Suất xe và khóa Owner

**Phụ trách:** `@ducbinhnguyen07ars` · **Review:** `@thanhmit`.

**Interface:** SubscriptionPolicy.requireCanReserveSlot(ownerId) dùng dưới khóa Owner; chỉ cho thêm một suất khi gói active và reservedCount < quota.

- [ ] Chốt query suất dựa trên trạng thái ý định đăng:

```sql
SELECT COUNT(*) FROM cars
WHERE owner_id = :ownerId
  AND listing_status = 'ACTIVE'
  AND approval_status IN ('PENDING', 'APPROVED');
```

- [ ] Khóa hàng Owner trước create xe/gửi duyệt/bật lại, rồi lock entitlement/Car nếu đã có. Hai tác vụ khác carId vẫn tranh cùng khóa Owner; không chuyển sang queue toàn app.
- [ ] Owner chưa có gói/gói hết hạn: chặn tạo/gửi duyệt/bật xe; vẫn xem/sửa hồ sơ sẵn có. Xe mới PENDING giữ một suất ngay khi gửi. APPROVED ACTIVE đã chiếm suất thì edit không cộng thêm suất.
- [ ] Hide hoặc Admin reject giải phóng suất trong cùng transaction; Admin approve PENDING ACTIVE chuyển một suất đã giữ sang xe đăng, không cộng lần hai. Hidden car không tự bật chỉ vì Admin duyệt.
- [ ] Bật lại APPROVED giữ approval; gửi lại PENDING chỉ khôi phục suất chờ duyệt, không tự approve. REJECTED muốn gửi lại phải qua chuyển về PENDING có kiểm tra quota và nội dung hợp lệ.
- [ ] Test MySQL hai connection: Owner có 3/4 suất, đồng thời hai xe xin suất → chỉ một thành công; rollback không chiếm suất giả. Test Owner khác không tranh một hàng Owner.

### Task P8.5 — Hết gói, public visibility và chặn đơn mới

**Phụ trách:** `@ducbinhnguyen07ars` · **Review:** `@thanhmit`.

**Files:** CarSpecifications/Repository/Controller, HomeController, BookingService.createBooking; SubscriptionVisibilityTest.

- [ ] Dùng một policy public nhất quán cho listing/detail/home/search: APPROVED + ý định ACTIVE + entitlement còn hạn. Chủ xe/Admin vẫn xem xe ẩn theo quyền hiện có.
- [ ] Không chỉ ẩn nút trên UI: createBooking kiểm tra gói current dưới khóa Owner trước khóa Car, tại thời điểm submit. Gói hết hạn giữa lúc xem trang và POST phải bị chặn.
- [ ] Quét job chỉ để nhắc/hiển thị, public predicate kiểm tra validUntil trực tiếp; job trễ không để xe tiếp tục nhận đơn mới.
- [ ] Hết hạn không đổi/xóa hồ sơ, report, booking, debt hoặc refund; không chặn nhận/trả/thanh toán các đơn đã xác nhận. Các yêu cầu thuê đang có không bị tự hủy chỉ vì expiry gói.
- [ ] Giữ listing_status thể hiện Owner muốn đăng/ẩn; tính trạng thái tạm ẩn do gói riêng để không bật nhầm xe Owner đã ẩn khi gia hạn.
- [ ] Test public direct URL, home/search/filter/count/pagination và POST booking đều dùng cùng policy, không chỉ trang Owner.

### Task P8.6 — Gia hạn cùng gói và mua gói thấp hơn

**Phụ trách:** `@ducbinhnguyen07ars` · **Review:** `@thanhmit`.

- [ ] Gia hạn cùng gói khôi phục public cho xe đủ điều kiện trước expiry; xe chủ động INACTIVE hoặc bị Admin từ chối vẫn không hiện.
- [ ] Khi hết hạn mua gói thấp hơn: trong transaction apply, giữ dữ liệu và đưa ý định đăng của các xe cũ về INACTIVE; lockSubject đã khóa Owner/entitlement/order và Car theo ID tăng trước phần tài chính. Không tự chọn 4/8 xe thay Owner.
- [ ] Owner tự bật/gửi duyệt lại tối đa quota; đừng reset approval để bắt xe đã duyệt duyệt lại chỉ vì đổi gói. Chuyến đã xác nhận tiếp tục xử lý dù xe đang ẩn.
- [ ] Test Business có 16 xe → hết hạn → Small: số xe public và reservedCount ban đầu 0, bật đủ 4 được, xe thứ 5 bị chặn; dữ liệu 16 xe và booking cũ còn nguyên.
- [ ] Không thêm prorating/credit, nâng hạ giữa kỳ hoặc tự thu phí gia hạn.

### Task P8.7 — UI Owner và các khoản hoàn từ Admin

**Phụ trách:** `@ducbinhnguyen07ars` · **Review:** `@thanhmit`.

- [ ] Trang chọn gói hiển thị quota, giá, 30 ngày và không hoàn phí sau kích hoạt; còn hạn chỉ cho gia hạn cùng loại, backend vẫn kiểm tra lại.
- [ ] Owner xem hạn hiện tại, suất đang dùng gồm pending, xe tạm ẩn do hết gói và nút thanh toán/gia hạn. Khi gói thấp hơn, hướng dẫn chọn bật xe trong quản lý xe.
- [ ] Checkout/phản hồi dựa trạng thái Phase 4; receipt trùng hoặc late dẫn tới refund page của đúng Owner. Nếu cần tài khoản nhận thì yêu cầu khi phát sinh, không bắt liên kết dịch vụ nhận.
- [ ] Admin wallet thiếu tiền: WAITING_FUNDS và nhắc Admin, không lấy kênh Owner thay thế. Hoàn quá hạn dùng cơ chế cảnh báo chung, không giả lập SUCCEEDED.
- [ ] IDOR: Owner A không đọc order/refund/bank recipient Owner B; không show credential hoặc số tài khoản đầy đủ trên list. Form POST có CSRF.

### Task P8.8 — Gate tích hợp gói và kích hoạt demo một Owner

**Phụ trách:** `@ducbinhnguyen07ars` · **Review:** `@thanhmit`.

- [ ] Run `.\mvnw.cmd '-Dmaven.compiler.useIncrementalCompilation=false' clean verify` bằng PowerShell; so baseline seed/schema theo migration đã duyệt.
- [ ] E2E: Owner mua Small → SePay receipt → đăng 4 suất (có pending) → suất thứ 5 fail → hết hạn public ẩn → gia hạn cùng gói phục hồi đúng lựa chọn → đổi gói thấp hơn sau expiry tự chọn xe.
- [ ] E2E hai lần chuyển thật cùng order → chỉ một lần gia hạn + một refund từ Admin; late order → không cấp gói + hoàn đủ. Phân biệt fixture với live và không tự chuyển tiền trong test.
- [ ] Một Owner thử nghiệm và Admin có đủ kênh được duyệt; các Owner khác chưa cấu hình không được mở checkout tiền thật. Chỉ bật flags sau review migration, provider gate và các luồng Phase 6/8 đã pass.
- [ ] Đồng bộ SDD/decisions và quyền danh sách public trước bật entitlement gate; không sửa seed lịch sử để “có gói” cho mọi Owner.

**Hoàn thành khi:** quyền gói và quota không vượt khi race, mọi mặt public thống nhất, gia hạn không cộng hai lần, hết hạn/đổi gói không phá đơn đang chạy và tiền gói sai vòng đời được ghi nhận/hoàn đúng nguồn đã chốt.
