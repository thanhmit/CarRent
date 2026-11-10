# CarRent — Phase 8: Gói đăng xe Owner — Implementation Plan

**Checkpoint P5.4 — 2026-11-04:** hoàn thành **4/4 checklist local** Admin booking/biên bản/khoản còn nợ: list/detail chỉ đọc, nghĩa vụ30/70 và receipts/refunds đúng booking, BEFORE/AFTER private kể cả COMPLETED còn bất đồng; lọc nợ70% quá hạn1giờ và hoàn quá hạn theo Clock/deadline nghiệp vụ. Admin không ký/giao nhận thay Owner hoặc buộc resolve AFTER. Full isolated **clean verify 993/993 JUnit, 0 failure/error/skip**, JAR; Chrome **205/205**, 30 PNG ở1440/390/320. **79/86 task**, còn **7** (`ducbinhnguyen07ars`4, `huy69332`2, `thanhmit`1). Phụ trách `ducbinhnguyen07ars`, reviewer `thanhmit`; agent review không thay human review. P8.8/P4.2-A live vẫn pending. Tiếp theo **P5.5 + P5.6 của huy69332**. Bảo toàn dirty prerequisites/phase0/config/seed/uploads/DB ứng dụng/HEAD/main/index; không migrate/bật flags/provider/scheduler/restart8080/chuyển tiền thật/stage/commit/push/merge. [Bàn giao](phase-5-admin-polish.md).

**Checkpoint P5.3 — 2026-11-04:** hoàn thành **4/4 checklist** Admin quản lý tài khoản: q/role/status/page20, hồ sơ và số liệu scoped target, POST status ACTIVE/INACTIVE/BANNED dưới khóa và kiểm tra Admin hiện tại; chống self-change/role-email tamper/CSRF, banned login và stale request trong queue. Full isolated **clean verify 986/986 JUnit, 0 failure/error/skip**, JAR; Chrome **107/107**, 33 PNG. **78/86 task**, còn **8** (`ducbinhnguyen07ars`5, `huy69332`2, `thanhmit`1). Phụ trách `ducbinhnguyen07ars`, reviewer `thanhmit`; agent review không thay human review. P8.8/P4.2-A gate thật vẫn pending. Tiếp theo **P5.4 riêng**. Bảo toàn dirty P8/P5,phase0/config/seed/uploads,DB ứng dụng,HEAD/main/index; không migrate/bật flags/provider/scheduler/restart8080/chuyển tiền thật/stage/commit/push. [Bàn giao](phase-5-admin-polish.md).

**Checkpoint P5.1 + P5.2 — 2026-11-04:** hoàn thành **5/5 + 5/5 checklist** về read model và dashboard/tài chính Admin. Phí gói từ order applied của đúng Admin; tiền thuê, tiền trùng/chờ đối soát và hoàn tiền tách riêng. Tháng theo giờ Việt Nam; filter/phân trang/sort whitelist, kiểm tra Admin ACTIVE hiện tại, tài khoản được che và GET chỉ đọc. Full isolated **clean verify 973/973 JUnit, 0 failure/error/skip**, JAR; Chrome **103/103**, 33 PNG. **77/86 task**, còn **9**: `ducbinhnguyen07ars` 6, `huy69332` 2, `thanhmit` 1. Phụ trách `ducbinhnguyen07ars`, reviewer `thanhmit`; agent review không thay human review. Đây là đợt code độc lập được chuẩn bị theo roadmap khi P8.8 đã xong local; **P8.8 và P4.2-A vẫn pending gate migration/human/provider/live**. Đợt code tiếp theo **P5.3 riêng**. Bảo toàn P8 chưa commit/phase0/config/seed/uploads, DB ứng dụng, HEAD/main/index; không migrate/bật flags/provider/scheduler/restart8080/chuyển tiền thật/stage/commit/push. [Bàn giao](phase-5-admin-polish.md).

**Checkpoint P8.8 local — 2026-11-04:** hoàn tất phần triển khai/kiểm chứng local: cặp Owner/Admin pilot mặc định từ chối, chặn tạo/reuse và ẩn QR ngoài phạm vi; receipt/refund/cancel/đối soát giữ nguyên. Full isolated **clean verify957/957 JUnit, 0 failure/error/skip**, JAR; Chrome **110/110**, 45PNG. E2E Small/quota/pending/expiry/gia hạn/Business→Small/manual selection và duplicate/late refund dùng provider offline. **P8.8 chưa đóng**: chờ migration/human review, P4.2-A, giao dịch/hoàn tiền thật và cho phép kích hoạt. Tiến độ giữ **75/86 task**, còn11. Phụ trách `ducbinhnguyen07ars`, reviewer `thanhmit`; agent review không thay human review. P8.1–P8.8 local chưa commit trên feature `705f320`/main `6948664`; DB ứng dụng/phase0/config/seed/uploads giữ nguyên. Không migrate/bật flags/provider/scheduler/restart8080/chuyển tiền thật/stage/commit/push. [Bàn giao](phase-8-owner-subscriptions.md).

**Checkpoint P8.7 — 2026-11-04:** hoàn thành **5/5 checklist**: UI Owner xem gói/hạn/quota gồm pending và xe tạm ẩn; gia hạn đúng loại; order/result dẫn từng refund của đúng Owner. Dùng chung Phase4: Admin thiếu tiền giữ WAITING_FUNDS, thông báo Admin và cảnh báo quá hạn; không dùng kênh Owner hoặc báo hoàn thành giả. Full isolated **clean verify 950/950 JUnit, 0 failure/error/skip**, JAR; Chrome **123/123**, 54PNG. **75/86 task**, còn11 (45 task mới xong34). Phụ trách `ducbinhnguyen07ars`, reviewer `thanhmit`; agent review không thay human review. P8.1–P8.7 chưa commit, feature `705f320`/main `6948664` giữ nguyên; phase0/config/seed/uploads và DB ứng dụng giữ nguyên. Không migrate/bật workflow/provider/scheduler/restart8080/chuyển tiền thật/stage/commit/push. Dừng trước **P8.8 riêng**, gate live vẫn chờ. [Bàn giao](phase-8-owner-subscriptions.md).

**Checkpoint P8.6 — 2026-11-04:** hoàn thành **5/5 checklist**: gia hạn giữ ý định đăng; hạ gói sau expiry ẩn toàn bộ xe cũ atomic với receipt/entitlement, Owner tự chọn xe theo quota. Full isolated **clean verify 943/943 JUnit, 0 failure/error/skip**, JAR; Chrome **87/87**, 42PNG ở320/390/1440 và no-JS forms. **74/86 task**, còn12 (45 task mới xong33). Phụ trách `ducbinhnguyen07ars`, reviewer được giao `thanhmit`, `huy69332` review Car/Booking; agent review không thay human review. P8.1–P8.6 chưa commit; feature `705f320`, main `6948664` giữ nguyên. Phase0/config/seed/uploads và DB ứng dụng7bảng giữ nguyên. Không migrate/bật workflow/provider/scheduler/restart8080/chuyển tiền thật/stage/commit/push. Dừng trước P8.7; tiếp theo **P8.7 riêng**, gate live vẫn chờ. [Bàn giao](phase-8-owner-subscriptions.md).

> **For agentic workers:** Dùng superpowers:executing-plans theo đợt đã duyệt bên dưới, kiểm chứng/checkpoint riêng từng task; không tự stage/commit/push. Được chủ động dùng subagent cho công việc độc lập và review. Agent chính chịu trách nhiệm phân công, tránh sửa chồng file, tích hợp và kiểm chứng kết quả cuối.

**Ngày:** 2026-10-27; lịch đợt được duyệt 2026-11-01. **Trạng thái:** P8.1 hoàn thành4/4, P8.2 hoàn thành5/5, P8.3 hoàn thành6/6, P8.4 hoàn thành6/6, P8.5 hoàn thành6/6, P8.6 hoàn thành5/5, P8.7 hoàn thành5/5; P8.8 đã triển khai/kiểm chứng local, còn gate migration/human/provider/live nên chưa đóng task.

**Goal:** Owner mua gói 30 ngày qua SePay vào Admin, quản lý hạn mức xe và tự động hoàn các khoản mua gói trả trùng/đến muộn từ Admin.

**Architecture:** Subscription order snapshot quyền lợi và giá, nối một payment obligation của Phase 4. Một hàng entitlement theo Owner giữ hạn hiện tại; áp dụng receipt và cộng hạn cùng transaction. Quyền hiển thị/đặt xe được kiểm tra trực tiếp theo thời điểm hết hạn, không phụ thuộc job có chạy kịp hay không.

**Tech Stack:** Spring MVC/Thymeleaf/JPA/MySQL, payment core và payout của Phase 4; Clock và Car queue/DB locking hiện có.

**Spec:** [README](README.md) mục 2, 4–6; [SDD](../SDD.md) mục 1.5–1.6.

**Phân công:** `ducbinhnguyen07ars` phụ trách toàn bộ task; `thanhmit` review, `huy69332` review thêm tích hợp quota/Car/Booking. Xem [phân công nhóm](team-assignment.md).

**Checkpoint P8.4 + P8.5 — 2026-11-04:** hoàn thành **6/6 + 6/6 checklist**: quota theo Owner, current reads chống vượt suất, public predicate trực tiếp theo hạn gói và POST booking kiểm tra lại. Full isolated **clean verify 930/930 JUnit, 0 failure/error/skip**, JAR; Chrome **74/74**, 36PNG ở320/390/1440 và no-JS. **73/86 task**, còn13 (45 task mới xong32). Phụ trách `ducbinhnguyen07ars`, reviewer được giao `thanhmit`, `huy69332` review tích hợp Car/Booking; agent review không thay human review. P8.1–P8.5 chưa commit; feature `705f320`, main `6948664` giữ nguyên. Phase0/config/seed/uploads và DB ứng dụng7bảng giữ nguyên. Không migrate/bật workflow/provider/scheduler/restart8080/chuyển tiền thật/stage/commit/push. Dừng trước P8.6; tiếp theo **P8.6 riêng**, gate live vẫn chờ. [Bàn giao](phase-8-owner-subscriptions.md).

## Global Constraints

- Chạy sau Phase 7/4/6, trước Phase 5 chốt dashboard. Không triển khai nâng/hạ giữa kỳ hoặc self-service onboarding nhiều Owner.
- SMALL: 4 xe / 5.000 VND; STANDARD: 8 xe / 10.000 VND; BUSINESS: 16 xe / 15.000 VND. Giá tạm thời, mỗi lần mua đúng **30 ngày**.
- Còn hạn chỉ mua cùng loại gói; gia hạn cộng vào hạn cũ. Đã hết hạn có thể đổi loại; thời hạn mới từ activation +30 ngày.
- Hạn mức tính xe đang đăng và đang chờ duyệt; xe chủ động ẩn/bị từ chối không chiếm suất.
- Tiền gói vào ngân hàng Admin; hoàn từ ví/kênh chi Admin. Không dùng tiền thuê Owner làm doanh thu Admin.
- QR 15 phút; gói đã kích hoạt không hoàn theo ngày/suất chưa dùng. Hai lần chuyển thật cùng yêu cầu chỉ cấp gói một lần, hoàn khoản trùng.
- Khóa quota theo Owner. Car queue theo từng xe không đủ bảo vệ suất cuối của hai xe khác nhau.
- Các quy tắc giữ dữ liệu, credential, webhook, refund và gate live trong README áp dụng đầy đủ.

<a id="cac-dot-da-duyet"></a>
## Các đợt — được duyệt 2026-11-01

| Đợt | Task | Phạm vi và điều kiện kết thúc |
|---|---|---|
| 1 | **P8.1** | Catalog/schema/mapping và kiểm chứng giữ seed/dữ liệu trước khi thêm hành vi. Không migrate DB ứng dụng hoặc tự cấp gói cho Owner. |
| 2 | **P8.2 + P8.3** | Cùng vòng đời order/QR → receipt → entitlement. Chốt test/checkpoint tạo order trước, rồi kiểm chứng apply đúng một lần, retry/late/refund và khóa Owner. |
| 3 | **P8.4 + P8.5** | Quota và public visibility/POST booking dùng policy nhất quán; test hai carId tranh suất cuối, expiry giữa GET/POST và direct URL/search/home. |
| 4 | **P8.6** | Riêng cho gia hạn/hạ gói tác động nhiều xe; đúng thứ tự khóa trước phần tiền, giữ booking/dữ liệu và Owner tự bật xe. |
| 5 | **P8.7** | Hoàn thiện UI/quyền và nguồn hoàn Admin trên hợp đồng gói đã ổn định; chờ recipient/funds không được báo thành công. |
| 6 | **P8.8** | Gate E2E/build/baseline/SDD và điều kiện demo một Owner. Chỉ kích hoạt khi migration/provider/live/review đã đạt và được cho phép; fixture không hoàn thành gate live. |

Áp dụng [quy tắc nghiệm thu chung](README.md): một chat triển khai đợt được giao, từng task giữ checklist/bằng chứng riêng, kiểm chứng source cuối trước chốt đợt. UI tối thiểu cần cho order/quota vẫn đi cùng task; P8.7 hoàn thiện tích hợp. P8.8 giữ pending nếu thiếu gate thật, không gọi cả Phase8 hoàn tất từ kết quả local. Không thay thứ tự6→8→5 hoặc tự bật entitlement/workflow/provider.

## Map file

Java dưới `src/main/java/com/carrent/`:

- Create `subscription/SubscriptionPlan.java`, `SubscriptionOrder.java`, `OwnerSubscription.java`, repositories, `SubscriptionService.java`, `SubscriptionPolicy.java`, `SubscriptionPaymentHandler.java`, `SubscriptionMaintenanceJob.java`, `SubscriptionController.java`, `SubscriptionOrderView.java`.
- Modify `car/CarService.java`, `CarRepository.java`, `CarSpecifications.java`, `CarController.java`, `OwnerCarController.java`, `AdminCarController.java`; `booking/BookingService.java`; `web/HomeController.java`; `user/UserRepository.java` thêm owner lock khi cần.
- Create templates `subscription/plans.html`, `subscription/detail.html`; modify `owner/cars.html`, `owner/car-form.html`, public listing/detail/home và fragments quyền lợi.
- Migration dự kiến `docs/migrations/2026-10-27-03-owner-subscriptions.sql`, update `scriptDB.sql`.
- Tests hiện có tại `src/test/java/com/carrent/payment/`: `SubscriptionMigrationTest`, `SubscriptionModelTest`, `SubscriptionOrderTest`, `SubscriptionPaymentTest`, `SubscriptionUiTest`, dùng fixture MySQL/payment chung. Đợt3 bổ sung `SubscriptionQuotaTest`, `SubscriptionVisibilityTest`, `SubscriptionRentalContinuityTest` (16test).

### Task P8.1 — Catalog, migration và dữ liệu quyền lợi

**Phụ trách:** `@ducbinhnguyen07ars` · **Review:** `@thanhmit`.

- [x] Tạo catalog 3 gói với code/quota/price/duration đúng Global Constraints; chưa cần Admin CRUD bảng giá. Không dùng giá client gửi.
- [x] Tạo subscription_orders và owner_subscriptions theo README; ownerId entitlement UNIQUE, order.obligationId UNIQUE, appliedAt nullable; trạng thái order PENDING/APPLIED/EXPIRED/CANCELLED/REFUND_PENDING.
- [x] Mỗi order lưu plan_code/quota_snapshot/price_snapshot/duration_days=30; Java dùng planCode/quotaSnapshot/priceSnapshot/durationDays. Liên kết nghĩa vụ bằng business key SUBSCRIPTION:orderId; không cần bookingId giả hoặc sửa payment legacy.
- [x] Test schema/checks và giữ nguyên seed/xe cũ. Migration không tự gán gói miễn phí cho mọi Owner; fixture kiểm chứng có entitlement riêng.

**Bằng chứng:** schema21/21, catalog/JPA2/2, full clean verify 879/879 và JAR. [Checkpoint/bàn giao đợt1](phase-8-owner-subscriptions.md); không tự cấp entitlement hoặc migrate DB ứng dụng.

### Task P8.2 — Tạo yêu cầu mua/gia hạn và QR 15 phút

**Phụ trách:** `@ducbinhnguyen07ars` · **Review:** `@thanhmit`.

**Interfaces:** `SubscriptionOrderView createOrder(SubscriptionPlan plan, CustomUserPrincipal actor)`; `void cancelOrder(Long orderId, CustomUserPrincipal actor)`. View chứa orderId, snapshot plan/quota/price, intent view, status.

- [x] Viết test Owner đúng role/ACTIVE, không mua gói khác khi còn hạn, một order ACTIVE mỗi Owner dù double POST.
- [x] Khóa hàng Owner trước đọc entitlement và pending order; nếu có pending hợp lệ thì trả lại yêu cầu đó, không tạo thêm QR khác. Owner chỉ xem/hủy order của mình.
- [x] Tạo order + obligation + intent cùng transaction, deadline từ backend +15 phút. Checkout dùng QR tài khoản thu Admin; refresh không gia hạn.
- [x] Hủy/hết hạn kết thúc order/intent, không xóa dữ liệu và không mất hạn gói hiện tại. Tiền về sau canceled order dùng mặc định hoàn 100% trong README.
- [x] Test tamper amount/collector/ownerId, queue không có carId không được lấy một carId giả; đây là transaction khóa Owner, không Car queue.

**Bằng chứng:** Order6/6 và HTTP4/4; full clean verify 914/914, Chromium47/47. [Checkpoint riêng đợt2](phase-8-owner-subscriptions.md).

### Task P8.3 — Áp dụng thanh toán và gia hạn đúng một lần

**Phụ trách:** `@ducbinhnguyen07ars` · **Review:** `@thanhmit`.

**Interfaces:** `void applyPayment(Long receiptId)` trong SubscriptionService, được SubscriptionPaymentHandler subject SUBSCRIPTION gọi cùng apply transaction của Phase 4.

- [x] Viết test chuyển khoản đầu/trùng webhook/chuyển lần hai thật; assert một lần cấp quyền lợi và đúng khoản hoàn cho tiền trùng.
- [x] Implement PaymentDomainHandler.lockSubject(orderId): khóa Owner → entitlement → order; nếu hạ gói, khóa các Car cần ẩn theo ID tăng trước khi core khóa obligation/intent/receipt. Trong applyPayment dùng các khóa aggregate đã có; không lấy Car sau khi đã khóa tiền. Kiểm tra tài khoản nhận Admin, code, amount và creditedAt; order đã applied thì receipt mới tạo hoàn DUPLICATE_PAYMENT, không cộng thêm ngày.
- [x] Thời hạn:

```java
Instant base = current != null && current.validUntil().isAfter(now)
    ? current.validUntil() : now;
Instant nextExpiry = base.plus(Duration.ofDays(30));
```

Khi còn hạn, loại gói phải trùng. Order.appliedAt và entitlement update trong cùng commit; provider retry không được cộng lại.

- [x] Thực sự trả sau expiresAt: LATE_SUBSCRIPTION → hoàn 100% từ Admin, không cấp gói. Trả đúng hạn nhưng webhook chậm: kiểm tra creditedAt và entitlement hiện tại; không ghi đè một gói khác không tương thích, dùng refund INCOMPATIBLE_SUBSCRIPTION theo mặc định README.
- [x] Lệch amount mua gói: receipt REVIEW_REQUIRED, không cấp gói hoặc giả lập đã hoàn. Payout từ Admin đi qua RefundService chung, không tự viết HTTP chi trong SubscriptionService.
- [x] Test các biên validUntil==now, creditedAt==expiresAt, hai order/webhook cạnh tranh, lỗi notify rollback cả entitlement và appliedAt.

**Bằng chứng:** Payment25/25; full clean verify 914/914, Chromium47/47. [Checkpoint riêng đợt2](phase-8-owner-subscriptions.md).

### Task P8.4 — Suất xe và khóa Owner

**Phụ trách:** `@ducbinhnguyen07ars` · **Review:** `@thanhmit`.

**Interface:** SubscriptionPolicy.requireCanReserveSlot(ownerId) dùng dưới khóa Owner; chỉ cho thêm một suất khi gói active và reservedCount < quota.

- [x] Chốt query suất dựa trên trạng thái ý định đăng:

```sql
SELECT COUNT(*) FROM cars
WHERE owner_id = :ownerId
  AND listing_status = 'ACTIVE'
  AND approval_status IN ('PENDING', 'APPROVED');
```

- [x] Khóa hàng Owner trước create xe/gửi duyệt/bật lại, rồi lock entitlement/Car nếu đã có. Hai tác vụ khác carId vẫn tranh cùng khóa Owner; không chuyển sang queue toàn app.
- [x] Owner chưa có gói/gói hết hạn: chặn tạo/gửi duyệt/bật xe; vẫn xem/sửa hồ sơ sẵn có. Xe mới PENDING giữ một suất ngay khi gửi. APPROVED ACTIVE đã chiếm suất thì edit không cộng thêm suất.
- [x] Hide hoặc Admin reject giải phóng suất trong cùng transaction; Admin approve PENDING ACTIVE chuyển một suất đã giữ sang xe đăng, không cộng lần hai. Hidden car không tự bật chỉ vì Admin duyệt.
- [x] Bật lại APPROVED giữ approval; gửi lại PENDING chỉ khôi phục suất chờ duyệt, không tự approve. REJECTED muốn gửi lại phải qua chuyển về PENDING có kiểm tra quota và nội dung hợp lệ.
- [x] Test MySQL hai connection: Owner có 3/4 suất, đồng thời hai xe xin suất → chỉ một thành công; rollback không chiếm suất giả. Test Owner khác không tranh một hàng Owner.

### Task P8.5 — Hết gói, public visibility và chặn đơn mới

**Phụ trách:** `@ducbinhnguyen07ars` · **Review:** `@thanhmit`.

**Files:** CarSpecifications/Repository/Controller, HomeController, BookingService.createBooking; SubscriptionVisibilityTest.

- [x] Dùng một policy public nhất quán cho listing/detail/home/search: APPROVED + ý định ACTIVE + entitlement còn hạn. Chủ xe/Admin vẫn xem xe ẩn theo quyền hiện có.
- [x] Không chỉ ẩn nút trên UI: createBooking kiểm tra gói current dưới khóa Owner trước khóa Car, tại thời điểm submit. Gói hết hạn giữa lúc xem trang và POST phải bị chặn.
- [x] Quét job chỉ để nhắc/hiển thị, public predicate kiểm tra validUntil trực tiếp; job trễ không để xe tiếp tục nhận đơn mới.
- [x] Hết hạn không đổi/xóa hồ sơ, report, booking, debt hoặc refund; không chặn nhận/trả/thanh toán các đơn đã xác nhận. Các yêu cầu thuê đang có không bị tự hủy chỉ vì expiry gói.
- [x] Giữ listing_status thể hiện Owner muốn đăng/ẩn; tính trạng thái tạm ẩn do gói riêng để không bật nhầm xe Owner đã ẩn khi gia hạn.
- [x] Test public direct URL, home/search/filter/count/pagination và POST booking đều dùng cùng policy, không chỉ trang Owner.

### Task P8.6 — Gia hạn cùng gói và mua gói thấp hơn

**Phụ trách:** `@ducbinhnguyen07ars` · **Review:** `@thanhmit`.

- [x] Gia hạn cùng gói khôi phục public cho xe đủ điều kiện trước expiry; xe chủ động INACTIVE hoặc bị Admin từ chối vẫn không hiện.
- [x] Khi hết hạn mua gói thấp hơn: trong transaction apply, giữ dữ liệu và đưa ý định đăng của các xe cũ về INACTIVE; lockSubject đã khóa Owner/entitlement/order và Car theo ID tăng trước phần tài chính. Không tự chọn 4/8 xe thay Owner.
- [x] Owner tự bật/gửi duyệt lại tối đa quota; đừng reset approval để bắt xe đã duyệt duyệt lại chỉ vì đổi gói. Chuyến đã xác nhận tiếp tục xử lý dù xe đang ẩn.
- [x] Test Business có 16 xe → hết hạn → Small: số xe public và reservedCount ban đầu 0, bật đủ 4 được, xe thứ 5 bị chặn; dữ liệu 16 xe và booking cũ còn nguyên.
- [x] Không thêm prorating/credit, nâng hạ giữa kỳ hoặc tự thu phí gia hạn.

### Task P8.7 — UI Owner và các khoản hoàn từ Admin

**Phụ trách:** `@ducbinhnguyen07ars` · **Review:** `@thanhmit`.

- [x] Trang chọn gói hiển thị quota, giá, 30 ngày và không hoàn phí sau kích hoạt; còn hạn chỉ cho gia hạn cùng loại, backend vẫn kiểm tra lại.
- [x] Owner xem hạn hiện tại, suất đang dùng gồm pending, xe tạm ẩn do hết gói và nút thanh toán/gia hạn. Khi gói thấp hơn, hướng dẫn chọn bật xe trong quản lý xe.
- [x] Checkout/phản hồi dựa trạng thái Phase 4; receipt trùng hoặc late dẫn tới refund page của đúng Owner. Nếu cần tài khoản nhận thì yêu cầu khi phát sinh, không bắt liên kết dịch vụ nhận.
- [x] Admin wallet thiếu tiền: WAITING_FUNDS và nhắc Admin, không lấy kênh Owner thay thế. Hoàn quá hạn dùng cơ chế cảnh báo chung, không giả lập SUCCEEDED.
- [x] IDOR: Owner A không đọc order/refund/bank recipient Owner B; không show credential hoặc số tài khoản đầy đủ trên list. Form POST có CSRF.

### Task P8.8 — Gate tích hợp gói và kích hoạt demo một Owner

**Phụ trách:** `@ducbinhnguyen07ars` · **Review:** `@thanhmit`.

**Cập nhật 2026-11-09:** đã bổ sung adapter lookup người nhận và đường chẩn đoán riêng không chi tiền; chuẩn bị nghiệm thu duplicate/late bằng giao dịch thật cùng order và hai nguồn Owner/Admin. P8.8 vẫn pending entitlement/capability/review/kích hoạt và đối soát thực tế. Xem [bàn giao mới](p42a-p88-implementation-contract-2026-11-09.md), [hướng dẫn và file private cần điền](../p42a-p88-operator-guide-2026-11-09.md).

- [ ] Run `.\mvnw.cmd '-Dmaven.compiler.useIncrementalCompilation=false' clean verify` bằng PowerShell; so baseline seed/schema theo migration đã duyệt.
- [x] E2E: Owner mua Small → SePay receipt → đăng 4 suất (có pending) → suất thứ 5 fail → hết hạn public ẩn → gia hạn cùng gói phục hồi đúng lựa chọn → đổi gói thấp hơn sau expiry tự chọn xe.
- [ ] E2E hai lần chuyển thật cùng order → chỉ một lần gia hạn + một refund từ Admin; late order → không cấp gói + hoàn đủ. Phân biệt fixture với live và không tự chuyển tiền trong test.
- [ ] Một Owner thử nghiệm và Admin có đủ kênh được duyệt; các Owner khác chưa cấu hình không được mở checkout tiền thật. Chỉ bật flags sau review migration, provider gate và các luồng Phase 6/8 đã pass.
- [x] Đồng bộ SDD/decisions và quyền danh sách public trước bật entitlement gate; không sửa seed lịch sử để “có gói” cho mọi Owner.

**Bằng chứng local P8.8:** full957/957/JAR và browser110/110 trên schema tạm. Hai mục E2E nghiệp vụ và đồng bộ tài liệu đã đạt local; task vẫn pending. Mục clean verify đã đạt phần test/baseline trên schema kiểm chứng nhưng còn review migration vận hành. Hai mục giao dịch thật và Owner/Admin/kích hoạt chưa đạt. Xem [bàn giao](phase-8-owner-subscriptions.md), [runbook](phase-8-pilot-runbook.md).

**Hoàn thành khi:** quyền gói và quota không vượt khi race, mọi mặt public thống nhất, gia hạn không cộng hai lần, hết hạn/đổi gói không phá đơn đang chạy và tiền gói sai vòng đời được ghi nhận/hoàn đúng nguồn đã chốt.
