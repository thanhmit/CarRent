# UI-04 — Bàn giao gói đăng xe và order

Ngày 2026-11-08. Root `feat/phase5-profile-password`, HEAD `b829b059a2f1af3c9ba79629759b608036470620`. **UI-04 hoàn tất triển khai và kiểm chứng cục bộ; dừng trước UI-05A.** Human visual review, native zoom 200% và gate live còn riêng.

## 1. Kết quả triển khai

- `subscription/plans.html`: chuyển sang workspace Owner, active Gói đăng xe trên desktop/mobile; giữ card overview UI-02. Ba tier card lấy tên/quota/giá/thời hạn từ catalog; cùng chiều cao ở desktop, xếp dọc trên mobile. Gói active có nhãn đúng tier; khác tier không có form mua và có lý do đọc được. Giữ thông báo lỗi, POST/CSRF và quy định mua/gia hạn.
- `subscription/detail.html`: workspace Owner, tách trạng thái → snapshot gói → bước tiếp theo → từng khoản hoàn → điều hướng. Hạn hiển thị từ `order.intent.expiresAt`, ghi rõ khác hạn gói; không tạo lại intent hoặc tính countdown. PENDING có QR/cancel, APPLIED dẫn quản lý xe; refund chuyển trùng không đổi order đã kích hoạt.
- `SubscriptionUiTest`: thêm regression navigation theo public GET và deadline intent không đổi qua reload. Giữ cờ subscription trong vòng đời fixture để template đọc đúng trạng thái module; cleanup cờ ở tearDown hoặc constructor failure.

**Chỉ hai template và một test source thay đổi trong đợt này.** Overview, refund-links, dashboard/cars callers, header/workspace, CSS/JS production và Java main giữ nguyên. Không thêm dependency, progress bar, lịch sử order, phương thức thanh toán hoặc nhãn giảm giá/phổ biến.

## 2. Phân tích và interface được giữ

Xem [phân tích và kế hoạch](ui-04-analysis-and-plan-2026-11-08.md).

`Overview(plan,validUntil,active,reserved,pending,temporarilyHidden,quota)` vẫn dùng chung trên plans/dashboard/cars; không tính lại số xe từ template hoặc giấu vượt quota. `SubscriptionOrderView(orderId,planCode,quotaSnapshot,priceSnapshot,durationDays,status,intent)` dùng snapshot order, không dùng giá catalog để thay lịch sử. Model không có `expiresAt` riêng, `canCancel` hoặc danh sách lịch sử.

Giữ `subscription-plans`, `subscription-order`, `data-order-id/data-status`, `subscription-overview`; form tạo dùng hidden `plan`, POST `/owner/subscriptions/orders`, cancel chỉ PENDING và POST `/owner/subscriptions/orders/{id}/cancel`. Giữ GET intent, no-arg `payment/refund-links :: links` cùng masked `intentRefunds`; không đưa account/name đầy đủ vào order.

## 3. Evidence mới

| Kiểm chứng | Kết quả |
|---|---|
| Baseline | **67/67** Java, 0 failure/error/skip |
| Catalog RED → GREEN | RED đúng thiếu Owner navigation; lượt GREEN đầu vướng lifecycle flag của fixture, đã sửa setup rồi GREEN |
| Order RED → GREEN | RED đúng thiếu nhãn/hạn datetime; GREEN trong focused suite cuối |
| Focused cuối | **69/69**, 0 failure/error/skip |
| Chrome | **348 kiểm tra**, 0 JavaScript error |
| Ảnh mới | **40 trước / 51 sau** tại 1440×1000, 1024×768, 390×844, 320×740 và reflow 720 CSSpx |
| Pure GET/read | **18 đoạn**, 20 bảng không đổi trong từng đoạn |
| POST thật qua browser | **5 audit**: tier khác bị từ chối, reuse cùng tier, cancel, tạo gia hạn, tạo hạ gói |
| Control qua service | **2 audit**: fake receipt gia hạn tới 29/12/2026, fake receipt hạ BUSINESS hết hạn xuống SMALL giữ xe ẩn, không tự chọn |
| Browser provider | Fake payout create/get/find **0**, không HTTP provider thật |
| Cleanup | **4 schema browser** đã xóa và truy vấn lại, các port đóng; 8080/config/index/HEAD giữ nguyên |

Sáu suite: `SubscriptionUiTest`, `SubscriptionOwnerUiTest`, `SubscriptionDowngradeTest`, `SubscriptionOrderTest`, `SubscriptionPaymentTest`, `WorkspaceUiTest`. Một số test nghiệp vụ cố ý dùng fake payout hoặc mô phỏng lỗi notification để kiểm tra rollback; chúng không phải giao dịch provider thật. Con số payout 0 ở bảng áp dụng cho runtime browser UI-04.

Browser kiểm tra no-plan, active quota đầy (4/4 gồm 2 pending), expired/tạm ẩn, vượt quota (5/4 vẫn giữ số thật), năm trạng thái order, APPLIED kèm hai refund chuyển trùng, các caller overview trên dashboard/cars. Owner A/B, Admin/Customer/anonymous, stale banned, IDOR cancel có CSRF hợp lệ, thiếu CSRF và enum plan sai đều kiểm tra response thật.

POST tier khác bị từ chối không ghi DB; reuse không tạo order/intent hoặc đổi hạn. Cancel/tạo mới không đổi quyền đăng xe hoặc các xe trước xác nhận tiền. Fields client price/amount/ownerId/collectorId bị bỏ qua, snapshot mới vẫn SMALL/4 xe/5.000/30 ngày, collector Admin 1. Gia hạn cùng tier cộng vào hạn cũ; hạ gói sau expiry không tự chọn bật xe. Fake receipt được xử lý qua service/webhook fixture và ghi thành control riêng, không tính là thao tác thanh toán thật của người dùng.

Không tràn ngang, một h1, control thuộc phạm vi sửa ≥44px, escaping tên Owner dài, menu mobile, keyboard, no-JS reuse/cancel/reload và flash error đã kiểm tra. Agent đã xem ảnh đại diện; human review vẫn chờ.

## 4. Lệnh và artifact

```powershell
.\mvnw.cmd -f .scratch/ui-04/pom.xml "-Dtest=SubscriptionUiTest,SubscriptionOwnerUiTest,SubscriptionDowngradeTest,SubscriptionOrderTest,SubscriptionPaymentTest,WorkspaceUiTest" test
$env:UI04_OUT = '.scratch/ui-04/browser-verified'
node .scratch/ui-04/browser-check.cjs
```

Browser cần Java `Ui04Browser` fresh fixture đúng output đã có ready; schema/credential của lượt cũ đã dọn, không reuse hoặc trỏ runtime 8080. Harness và POM cách ly là evidence local trong `.scratch`, không phải prerequisite của sản phẩm.

- `.scratch/ui-04/baseline.json`, `port8080-before.json`: baseline mới, bao gồm mọi dirty files trước UI-04 và file đã xóa sẵn.
- `baseline-tests.log`, `red-catalog.log`, `green-catalog.log`, `green-catalog-fixed.log`, `red-order.log`, `final-tests.log`: giữ các lượt độc lập, không gọi lượt lỗi là passing aggregate.
- `browser-before-final/capture.json`: 40 ảnh trước; `browser-verified/report.json`, SQL snapshots, `read-post-audit.json`, `provider-calls.json`, `accessibility.txt`, `cleanup.json`: lượt cuối.
- `schema-cleanup-audit.json`, `fixture-port-audit.json`, `validation.json`: scope/hash/contracts và xác nhận cleanup.

Ảnh đại diện:

- [Gói active desktop](../../.scratch/ui-04/browser-verified/plans-active-1440.png), [chưa có gói 320px](../../.scratch/ui-04/browser-verified/plans-no_plan-320.png), [gói hết hạn](../../.scratch/ui-04/browser-verified/plans-expired-390.png).
- [Order PENDING 320px](../../.scratch/ui-04/browser-verified/order-pending-320.png), [APPLIED có refund](../../.scratch/ui-04/browser-verified/order-applied-1440.png), [REFUND_PENDING](../../.scratch/ui-04/browser-verified/order-refundPending-390.png).
- [Sau hạ gói](../../.scratch/ui-04/browser-verified/plans-downgraded-390.png).

## 5. Giới hạn và các lượt lỗi setup

- Snapshot được so ở từng đoạn đọc, tách các lệnh seed/clock/control và mutation. Không yêu cầu toàn phiên có POST/fake receipt giữ DB giống ban đầu. Các invariant tiền/hạn/quyền/xe được assert độc lập; BLOB chuẩn hóa hex và snapshot có self-check ổn định.
- Lượt chụp trước đầu vướng CHECK vì fixture đặt validFrom bằng validUntil; đã sửa thời kỳ quá khứ hợp lệ, không nới schema. Schema lỗi được đóng bằng try-with-resources và truy vấn lại trong cleanup audit; lượt trước cuối chụp 40 ảnh trên fixture mới.
- Lượt GREEN catalog đầu không thấy active navigation vì test bỏ cờ subscription ngay sau boot; sửa vòng đời fixture, không đổi guard module sản phẩm. Một lệnh javac chạy khi Maven đang compile test gặp class chưa tồn tại; compile lại tuần tự sau build. Lệnh browser quá sớm thiếu metadata được giữ tại `browser-before-ready.log`; lượt cuối chỉ khởi chạy sau ready.
- Lượt browser ma trận đầu đã tới cuối hành trình nhưng ghi artifact bị lỗi do biến route tên `path` che module Node cùng tên. Đổi biến route thành `pendingPath`, giữ đầy đủ artifact lỗi và chạy lại toàn bộ trên fixture mới; không tính lượt lỗi thành PASS hoặc thay đổi assertion nghiệp vụ.
- Không chạy full suite/JAR hoặc provider/live. CSS reflow 720px không phải native zoom 200%. Tailwind/fonts dùng cache local, ảnh từ fixture; không nghiệm thu CDN/ngân hàng thật.
- Giữ mọi dirty changes khác, `.env`, local config, schema ứng dụng, seed/uploads, index và HEAD; `uploads/seed-car-5.png` đã xóa từ baseline, không phục hồi. Không stage/commit/push/merge, chuyển worktree, restart 8080 hoặc bật provider/scheduler ứng dụng.
- Audit đầu phát hiện tài liệu payment-settings được cập nhật trong lúc làm UI-04: ba file có sẵn (`docs/design/payment-settings-2026-11-08.md`, `payment-settings-implementation-2026-11-08.md`, `payment-settings-worktree-2026-11-08.md`) và hai file mới (`payment-settings-S1-preimplementation-2026-11-08.md`, `payment-settings-sessions-2026-11-08.md` trong `docs/plans`). Không chỉnh hoặc hoàn nguyên chúng. Hash baseline/quan sát được giữ riêng tại `.scratch/ui-04/concurrent-documents.json`; final audit phân biệt rõ chúng với UI-04, không ghi đè baseline để che chênh lệch. Mọi file ngoài hai phạm vi đã liệt kê phải giữ hash ban đầu.

## 6. Điểm dừng

Đóng checklist triển khai UI-04 và cập nhật README/SDD. Tiếp theo là UI-05A dashboard/tài chính Admin; chưa triển khai đợt này. Gate P4.2-A/P5.9/P8.8/human/live giữ riêng, không dùng kết quả UI để đóng task nghiệp vụ.
