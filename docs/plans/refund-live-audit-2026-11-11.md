# Kiểm tra hoàn tiền thực tế — 11/11/2026

## Kết luận

**Chưa hoạt động đầy đủ với tiền thật.** Đã kiểm tra code, cấu hình riêng theo trạng thái có/thiếu, DB bằng truy vấn chỉ đọc và API GET PayOS. API trả HTTP 403, mã `403`, thông báo **“Địa chỉ IP không được phép truy cập hệ thống”**. Không có căn cứ xác nhận khóa hợp lệ, quyền POST, số dư hoặc credit khi IP còn bị chặn.

Phiên trình duyệt không truy cập được: Computer Use báo `The browser access host is unavailable` và native pipe không tồn tại. Vì vậy chưa đọc dashboard PayOS, chưa lấy được dữ liệu từ phiên đăng nhập và chưa sửa allowlist trên tài khoản.

## Luồng đã có trong code

1. Domain thuê xe/gói đăng xe xác định lý do và số tiền. `RefundService.request` kiểm tra receipt, giới hạn số tiền, bên thu/bên nhận, chống tạo lặp và chọn kênh PAYOS của bên thu; tạo `WAITING_RECIPIENT`.
2. Người nhận nhập BIN ngân hàng và số tài khoản. Preview gọi lookup; submit lookup lại, so tên khách đã xác nhận và khóa đích trước khi lưu `READY`. Khoản hoàn có hạn xử lý một giờ từ lần xác nhận đầu.
3. Worker lưu attempt/reference/idempotency và snapshot trước HTTP; dùng lease để tránh xử lý trùng. Request gửi bên ngoài transaction DB.
4. Response phải có chữ ký đúng và khớp reference/BIN/STK/amount/provider ID mới ghi nhận thành công. Mất response hoặc kết quả chưa rõ chuyển sang query/reconcile; không tự tạo lại lệnh chi.
5. Adapter PayOS hiện không phát `NOT_SENT`. Nhánh `WAITING_FUNDS` được kiểm thử bằng provider giả, chưa chứng minh tự nhận biết thiếu tiền thật rồi gửi lại. Giữ `UNKNOWN` đối soát khi chưa có guarantee từ provider.

Nguồn: `RefundService`, `RefundRecipientService`, `RefundRecipientTransactions`, `PayoutTransactions`, `PayoutWorker`, `HttpPayOsPayoutClient`. Đã so sánh 10 class hoàn tiền của JAR đang chạy với class biên dịch từ checkout: **10/10 giống byte**. PID quan sát: `37268`, profile `local`; không restart ứng dụng.

## Trạng thái cấu hình và DB quan sát được

| Hạng mục | Bằng chứng hiện tại | Hệ quả |
| --- | --- | --- |
| Bộ khóa PAYOS Owner/Admin | Mỗi vai trò có đủ Client ID, API Key, Checksum Key; hai vai trò cùng bộ khóa | Chưa chứng minh hai nguồn tiền độc lập |
| PAYOS channel 1 | OWNER user 2, `owner-payout`, `enabled=0` | Không chi tiền |
| PAYOS channel 2 | ADMIN user 1, `admin-payout`, `enabled=0` | Không chi tiền |
| Provider enable flags | Owner/Admin đều `false` | Prepare/confirm nguồn chi bị chặn |
| Worker payout | `carrent.payment.payout.enabled=false` | Scan bỏ qua dispatch và reconcile |
| Payment maintenance | `true` | Đang phục vụ tác vụ thanh toán; không đồng nghĩa payout được bật |
| Recipient lookup | `enabled=false`; không có hai khóa lookup trong file, process/user/machine environment | Chưa thể xác minh tên nhận thật |
| Schema hoàn tiền | Có `payment_channels`, `refunds`, `payout_attempts`, `refund_recipient_accounts` | Không cần tự chạy lại migration để thêm các bảng này |
| Dữ liệu hoàn tiền | 0 refund, 0 payout attempt | Chưa có bằng chứng hoàn thật hay khoản hiện tại để đối soát |
| API PayOS | List và balance đều HTTP 403 do IP | Chưa kiểm chứng được quyền đọc/số dư; quyền POST vẫn chưa kiểm chứng |

Đây là trạng thái file/DB cùng code đã đối chiếu JAR, không phải đọc toàn bộ Spring Environment của process đang chạy. Không in khóa, số tài khoản, tên riêng hoặc IP ra báo cáo.

## Trường còn thiếu và cách lấy

| Thông tin | Tên trường/cấu hình | Khả năng tự lấy trong phiên này |
| --- | --- | --- |
| IP backend được phép gọi PayOS | IP trong allowlist của đúng kênh chi | Đã lấy IP kết nối chẩn đoán và lưu riêng tại `.scratch/refund-audit-20261111/diagnostic-egress-ip.txt`; dashboard chưa kết nối. Cần đối chiếu IP egress của môi trường triển khai, không mặc định IP máy phát triển dùng cho production |
| Khóa dịch vụ lookup | `CARRENT_REFUND_LOOKUP_CLIENT_ID`, `CARRENT_REFUND_LOOKUP_API_KEY` | Chưa có ở máy. Tài liệu VietQR chỉ dẫn lấy tại `my.vietqr.io`; chưa có căn cứ tái dùng khóa payout PayOS. Cần entitlement khi VietQR đang migrate CAS |
| Quyền/nguồn chi | Kích hoạt kênh, nguồn Bảo Kim, cơ chế duyệt, hạn mức | Cần dashboard/provider; chưa đọc được. Hai mapping cùng khóa không tạo ra hai ví |
| Tiền nguồn và credit | Số dư tiền chuyển và credit phí dịch vụ | API balance đang bị IP chặn; credit dịch vụ cần kiểm tra riêng. Chưa chứng minh chuyển được khoản 5.000 VND |
| Tài khoản người nhận từng refund | `bankBin`, `accountNumber`, tên chuẩn lookup và xác nhận của người nhận | Do người nhận cung cấp/xác nhận; DB hiện không có refund. Không suy ra STK nhận hoàn từ tài khoản đã chuyển tiền đến |

BIN/STK/tên/xác nhận đã có trường trong model và UI. Thiếu cấu hình/quyền provider và dữ liệu giao dịch thực; chưa thấy nhu cầu thêm cột DB. Xem [đối chiếu contract provider hôm nay](refund-provider-contract-2026-11-11.md) cho nguồn chính thức và giới hạn OTP/minimum/credit/recovery.

## Phần đã khắc phục và kiểm chứng

Đã sửa fixture dùng chung `PaymentFlowFixture` để cờ subscription/payout của cấu hình máy không làm sai chế độ test; vẫn giữ override System property khi khởi động cho những bài test chủ động bật/tắt tính năng. `WorkspaceUiTest` đổi cờ của context đang chạy bằng property source tạm, rồi gỡ trong `finally`, để kiểm tra menu khi tắt subscription. Không đổi hành vi production.

- Run ban đầu: 129 tests được báo cáo, 4 lỗi khởi động nhóm do subscription handler trùng. Log `.scratch/refund-audit-20261111/tests.log`.
- Thử nghiệm chỉ đổi `carrent.subscription.enabled=false`: ca tạo hoàn chống lặp pass 1/1, xác nhận nguyên nhân cấu hình kế thừa. Log `hypothesis.log`.
- Run sau sửa cờ subscription: 246 tests, 3 ca maintenance payout thất bại vì tiếp tục kế thừa cờ payout tắt. Giữ riêng log `tests-fixed.log`; không coi là run thành công.
- Sau hai sửa đổi fixture: **247/247 pass, 12 nhóm, 0 failure/error/skip**; bao gồm thuê xe, gói đăng xe, recipient, adapter HTTP, worker, restart/recovery và collection-only. Log `tests-final.log`.
- Review phát hiện test UI dùng System property sau startup bị CLI override che. Đã chạy ca đó trước khi sửa: 1/1 thất bại đúng assertion menu; log `workspace-red.log`. Sau sửa, toàn bộ `WorkspaceUiTest` **8/8 pass**, log `workspace-final.log`; review độc lập không còn finding.
- Tổng hai run cuối: **255/255 tests được chọn, 13 nhóm, 0 failure/error/skip**. Đây là kiểm chứng local bằng DB/provider fixture, không phải nghiệm thu hoàn tiền thật.
- Kiểm tra bảo toàn sau run hoàn tiền: mapping kênh, thống kê refund/attempt, số khoản chờ dispatch và hash `.env`/`application-local.properties` khớp trước kiểm tra. Bằng chứng `preservation-check.json`; không suy rộng thành fingerprint toàn bộ DB.

Lệnh kiểm chứng hoàn tiền:

```powershell
.\mvnw.cmd '-Dtest=RefundServiceTest,RefundRecipientTest,PayoutWorkerTest,PayOsPayoutClientTest,PayOsConnectionCheckTest,VietQrRefundLookupTest,LocalRefundLookupCheckTest,PaymentMaintenancePayoutTest,ProviderPaymentPropertiesTest,RentalPaidCancellationTest,SubscriptionPaymentTest,SePayCollectionModeTest' test
.\mvnw.cmd -o '-Dtest=WorkspaceUiTest' test
```

Không chạy nguyên bộ test repository trực tiếp trên cấu hình này: ba nhóm `@SpringBootTest` của car/concurrency không có datasource riêng và có ghi fixture vào DB được cấu hình. Nhóm test hoàn tiền được chọn dùng DB `carrent_p43_<UUID>` riêng. Không migrate/seed DB ứng dụng, không đổi cấu hình riêng, không bật payout, không tạo lệnh chuyển tiền, không commit/push.

## Việc còn chặn nghiệm thu thật

Khôi phục kết nối trình duyệt để đọc và sửa đúng allowlist; cấp quyền/khóa lookup phù hợp; xác minh nguồn tiền/credit, chủ thể, hạn mức và cơ chế duyệt; sau đó mới chuẩn bị kích hoạt đúng phạm vi. Nghiệm thu cần một khoản hoàn do người dùng xác định, người nhận xác nhận tài khoản, trạng thái provider thành công và đối chiếu tiền thực nhận. Chưa có bằng chứng đó trong phiên này.

## Bổ sung sau yêu cầu về tính năng thử nghiệm và khóa lookup

- Đã xác định từ frontend công khai chính chủ: công tắc **Tính năng thử nghiệm** mở tính năng beta theo tổ chức, không phải sandbox/tiền giả. Không cần bật chỉ để kiểm thử contract hoàn tiền hiện có. Chưa đổi công tắc tài khoản.
- Nơi lấy khóa: đăng nhập `https://my.vietqr.io/profile`, mục **API Key**, gồm **Client ID** và **API Key**; cùng trang có quota lookup. Cần kiểm tra entitlement tài khoản thực tế. Nguồn và giới hạn xem mục 5–6 của [báo cáo provider](refund-provider-contract-2026-11-11.md).
- Đã thêm ba biến lookup còn thiếu vào `.env`: enabled giữ `false`, Client ID/API Key để trống chờ chủ tài khoản điền trực tiếp. Đây là placeholder, chưa phải bộ khóa hoạt động.
- Phát hiện override local ghi cứng lookup `false`, che biến enable được tài liệu hóa. Đã đổi đúng dòng đó thành `carrent.payment.recipient-lookup.enabled=${CARRENT_REFUND_LOOKUP_ENABLED:false}`; các khóa và cờ chi khác được giữ nguyên.
- Self-check bằng `StandardEnvironment`/`ResourcePropertySource` của Spring trước sửa báo `Local configuration masks the documented lookup enable variable`; sau sửa pass cả bật/tắt biến trong bộ đọc cấu hình, đồng thời xác nhận provider payout vẫn tắt. Chỉ dựng Environment; không khởi động app, không gọi lookup hoặc tạo payout. Harness ở `.scratch/refund-audit-20261111/LookupConfigurationCheck.java`.
- API balance kiểm tra lại vẫn HTTP 403. IPv4 chẩn đoán đã làm mới và không đổi. Phiên trình duyệt kiểm tra lại vẫn thiếu browser host/native pipe; chưa thể tự lấy khóa hoặc sửa allowlist.
- Hai file cấu hình riêng đã thay đổi có chủ đích trong bước bổ sung này; kết quả hash khớp ở phần kiểm tra trước chỉ áp dụng trước các sửa đổi này. App cổng 8080 không restart, nên chưa nạp thay đổi file cấu hình.

Thông tin cần chủ tài khoản bổ sung: khóa/quyền lookup trong `.env` hoặc thông báo không được cấp quyền; khôi phục kết nối trình duyệt để xử lý allowlist. Người nhận chỉ nhập ngân hàng (BIN) và STK rồi xác nhận tên lookup; không cung cấp API key và không tự điền tên để bỏ qua xác minh.

## Bổ sung: gộp cấu hình vào properties theo quyết định mới

Theo yêu cầu tiếp theo của chủ dự án, đã chuyển toàn bộ **10 trường** từ `.env` vào `application-local.properties` và điền hai khóa VietQR được cung cấp. Các khóa `CARRENT_*` hiện là property trong cùng file; `.env` chỉ còn ghi chú, không có giá trị cấu hình. Giữ nguyên 21 property local đã có và các cờ kích hoạt trước chuyển đổi. Bản trước chuyển đổi nằm trong `.scratch/config-properties-20261111/`.

Kiểm chứng mới: Spring xác minh **52 giá trị hiệu lực** bằng hash so với baseline/khóa được cung cấp; bind `RefundLookupProperties` thành công. Launcher cũng đọc đúng 52 giá trị; bộ kiểm tra launcher pass **55/55**. Chưa restart app đang nghe 8080, nên runtime hiện tại chưa nạp file mới.

Đã gọi lookup không chuyển tiền với ngân hàng/STK thu đang cấu hình. VietQR trả HTTP 200, business code **`47`**, mô tả **`The Free Plan will no longer support from August 20, 2024.`** Không trả tên chủ tài khoản. Bằng chứng đã loại dữ liệu nhạy cảm ở `.scratch/config-properties-20261111/lookup-check-result.json`.

Vì lookup thực tế bị provider từ chối theo gói dịch vụ, `CARRENT_REFUND_LOOKUP_ENABLED` vẫn `false`. Cần VietQR xác nhận/kích hoạt gói hoặc entitlement có hỗ trợ lookup; chưa có căn cứ tự đổi endpoint, dùng tên tự nhập hay bật chi tiền để vượt qua lỗi này. Chưa mua/nâng cấp gói dịch vụ.
