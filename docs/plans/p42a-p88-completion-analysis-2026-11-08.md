# P4.2-A và P8.8: phân tích code, schema/restore và đường nghiệm thu

Ngày kiểm tra: **2026-11-08**. Mục tiêu: hoàn tất đúng checklist P4.2-A và P8.8, phân biệt phần triển khai với quyền provider và bằng chứng tiền thật. **Chưa đóng hai task.**

## 1. Phạm vi và bằng chứng mới

- Phân tích checkout gốc `feat/phase5-profile-password`, HEAD `b829b05`, có thay đổi chưa commit; main là `00a6039`. Đây không phải cùng một source tree. Manifest các file liên quan nằm tại `.scratch/p42a-p88-readiness-20261108/source-manifest.json`.
- Đọc trực tiếp verifier/config, recipient preview/confirm, payout adapter/worker/transactions/resolver, pilot, SePay parser, các test liên quan và checklist/runbook. Không sửa Java, config, migration, trạng thái kênh hoặc ứng dụng đang chạy.
- Nghiên cứu lại nguồn chính thức: [báo cáo contract mới](p42a-provider-contract-analysis-2026-11-08.md). Không gọi API provider có xác thực hoặc tạo payout trong lần phân tích này.
- Thực hiện backup thật của DB local, restore vào schema dùng riêng, so fingerprint dữ liệu và metadata; kiểm tra CHECK/FK trên bản restore rồi dọn schema thử nghiệm. DB nguồn chỉ đọc; private config, HEAD và index giữ nguyên.
- Kiểm tra cổng 8080 hiện **không có listener**. Cấu hình/tunnel từng hoạt động ngày 07/11 không chứng minh runtime còn hoạt động tại thời điểm này. Không tự restart hoặc bật luồng tài chính trong lần phân tích.

## 2. Code hiện tại đã có gì và thiếu gì

| Thành phần | Hiện trạng trực tiếp từ code | Phần cần hoàn thiện |
| --- | --- | --- |
| `PaymentProviderConfiguration.refundDestinationVerifier()` | Bean mặc định luôn ném BusinessException; không có verifier HTTP production khác trong source | Một implementation lookup được provider xác nhận và cấp quyền, nối cấu hình credential riêng và kiểm thử |
| `RefundRecipientService.preview/submit` | Validate BIN/STK; lookup lại khi submit; so tên khách xác nhận; chặn null/trống/control character/mismatch; network ngoài transaction | Có thể tái sử dụng, không cần viết lại flow hai bước. Test adapter thật phải bao phủ đổi đích/tên giữa preview và submit |
| `RefundRecipientTransactions` | Kiểm tra recipient/current role/status, nguồn đúng collector; confirm dưới khóa; snapshot đích, deadline một giờ chỉ đặt lần đầu; không đổi đích khi đã có attempt | Kênh payout thực tế phải sẵn sàng. Hiện có thể bị chặn tại `requireChannel` trước cả bước gọi verifier |
| `HttpPayOsPayoutClient` | Create ký request với idempotency key từ caller; xác thực response; đối chiếu reference/BIN/STK/amount; chỉ transaction `SUCCEEDED` xác nhận thành công; GET/list phục hồi | Quyền create/auto-approval, phí/minimum, semantics lỗi và recovery theo đúng kênh chưa có bằng chứng live. GET list/balance không chứng minh quyền POST |
| `PayoutTransactions` / `PayoutWorker` | Attempt bền vững trước send, lease, khóa và snapshot; crash sau beginSend giữ UNKNOWN; reconcile chỉ query ID/reference; không tự phát lại UNKNOWN | Giữ chính sách này đến khi có guarantee provider. NOT_SENT/funding tự retry hiện được chứng minh bằng fixture, adapter production chưa map NOT_SENT |
| `PaymentPilot` | Mặc định từ chối; Owner/Admin khác nhau; scope cho mua gói và thuê xe; shared QR gate | Kiểm tra lại đúng source/JAR/profile/ID khi khởi động pilot sau khi đủ điều kiện |
| SePay | Parser HMAC/inbox, numeric transaction ID, account/chiều/amount/thời gian; cấu hình local thu chung và định tuyến collector đã có | Chưa tìm thấy client API v1 để tự query trong source. Cần chứng minh equality webhook/v1 trên cùng giao dịch; có thể đối chiếu thủ công bằng API có quyền, không nhất thiết viết một hệ thống polling mới |

Không cần mở rộng kiến trúc hoàn tiền hoặc viết lại gói Owner. Việc cần thêm chủ yếu là verifier production, các điều chỉnh adapter thực sự phát sinh từ contract và bằng chứng vận hành.

## 3. Lookup/capability: điều nào đủ căn cứ

- VietQR công bố `POST https://api.vietqr.io/v2/lookup` với `x-client-id`/`x-api-key`, body BIN/STK, success `code=00` và `data.accountName`. Trang đang thông báo migration CAS; chưa có bằng chứng tài khoản hiện tại được cấp quyền hoặc endpoint này còn được provision theo điều kiện nào.
- payOS `estimate-credit` chỉ công bố `estimateCredit`; cờ `validateDestination` không cung cấp contract trả tên. `balance.accountName` là nguồn chi; `create.transactions[].toAccountName` xuất hiện sau tạo lệnh. Không dùng hai đường này thay lookup trước xác nhận.
- Không tái sử dụng khóa payout cho VietQR/CAS bằng suy đoán, không gọi create để tìm tên, không viết adapter đoán endpoint CAS.
- Khi provider xác nhận đường lookup, dùng `RefundDestinationVerifier` hiện có. Implementation tối thiểu cần timeout, không redirect sang host khác, credentials qua cấu hình private, kiểm tra response code/schema/tên, gắn tên với chính BIN/STK request, fail closed và không log body/tài khoản/keys.
- Test cần có: thành công; giữ số 0 đầu STK; lỗi nghiệp vụ trong HTTP 200; ngân hàng không hỗ trợ; 401/403/429/5xx; timeout; JSON lỗi/tên trống/control characters; BIN/STK/tên thay đổi giữa preview và confirm; không có payout trong thao tác lookup.
- Xác nhận riêng min/max/phí/VAT, hạn mức ngày, nguồn ví và credit dịch vụ, quyền POST/auto-approval/OTP theo kênh; phải đáp ứng khoản hoàn Small **5.000đ**. Số 5000 trong ví dụ estimate không phải mức phí hay minimum được bảo đảm.
- Không bắt buộc mở auto-retry để đóng task: checklist P4.2-A cho phép giữ UNKNOWN/query-only khi provider không bảo đảm chưa chuyển. Khi đó phải ghi rõ giới hạn, đường đối soát/hỗ trợ và không cam kết tự phục hồi mọi trường hợp. Không cần bịa NOT_SENT hoặc vô thời hạn chờ TTL nếu quyết định vận hành đã chọn không replay.

Nguồn và giới hạn từng kết luận nằm trong [báo cáo contract](p42a-provider-contract-analysis-2026-11-08.md). Không có yêu cầu hỗ trợ nào được gửi ra ngoài; bản nháp câu hỏi đã có trong [báo cáo tài khoản](payos-account-configuration-2026-11-08.md).

## 4. Kết quả schema và restore mới

Evidence: `.scratch/p42a-p88-readiness-20261108/`.

| Kiểm tra | Kết quả mới |
| --- | --- |
| Server/source | MySQL **8.0.45**, local schema `carrent`, **20 bảng InnoDB**, 104 hàng tại snapshot |
| Stored code | Không có routine, trigger, event hoặc view cần đánh giá thêm trước clone |
| Backup | `mysqldump --single-transaction`, schema + dữ liệu; không `--databases`, không SOURCE/USE sang DB nguồn; file private giữ trong thư mục Git-ignored |
| Restore | Restore thành công vào schema UUID riêng; số dòng, hash dữ liệu và hash metadata của **20/20 bảng** khớp nguồn |
| Bảo toàn nguồn | Snapshot trước/sau giống nhau; không DML/DDL vào bảng nguồn; private config, HEAD và index không đổi |
| So bootstrap hiện tại | Đủ 20 bảng; cột/kiểu/null/default/charset, index và FK khớp. Có **17 CHECK trên 12 bảng** khác charset introducer của literal ASCII (`_cp850`/`_ascii` so với `_utf8mb4`); biểu thức khớp sau chuẩn hóa riêng phần introducer |
| Kiểm tra enforcement trên restore | **9/9** ca từ chối đúng: quota, duration, plan, validity, BIN/STK, numeric webhook ID, storage key, workflow và Owner FK; CHECK trả 3819, FK trả 1452 |
| Cleanup | Các schema restore/expected do lượt audit tạo đã được xóa và xác minh; không sửa/drop schema ứng dụng |

Các file chính: `schema-restore-evidence.json`, `schema-differences.json`, `schema-compatibility.json`, `restored-constraint-checks.json`, `source-manifest.json` và ba script diễn tập. Backup chứa dữ liệu riêng, không đưa vào Git/log/chat. Đây là bằng chứng phục hồi DB local trên cùng MySQL, **không phải** restore trên host production khác, backup offsite hay phê duyệt cutover.

Lượt đầu so hash DDL thô báo restore khác vì MySQL dump làm tường minh `CHARACTER SET` trên cột. Đã giữ `initial-raw-ddl-evidence.json`, kiểm tra lại bằng metadata (không bỏ qua khác kiểu/cột/constraint), và xác nhận restore khớp. Sai khác với bootstrap còn được ghi nguyên trạng ở `schema-differences.json`; không sửa schema nguồn để làm đẹp diff.

Do schema cần thiết đã tồn tại, **không có căn cứ chạy lại các migration one-shot hiện có**. Reviewer cần xác nhận nguồn tạo schema/17 CHECK và chấp thuận baseline hiện tại; nếu phát hiện khác biệt chức năng thật, tạo migration mới thay vì bootstrap hoặc chạy lại migration cũ.

DB đang có 3 subscription order, 1 entitlement, 1 receipt và 1 inbox; refund/attempt đều 0. Các con số này không chứng minh tiền thật: cần đối chiếu provenance và provider của đúng giao dịch, không tự tick live hoặc tạo receipt giả.

Mapping đọc trực tiếp: PayOS Owner channel 1 và Admin channel 2 đang disabled; SePay Owner channel 3 và Admin channel 4 đang enabled. Hai vai trò có mapping riêng nhưng báo cáo cấu hình 08/11 ghi dùng chung bộ khóa PayOS; không coi đó là hai ví độc lập. Phải chốt phạm vi pilot nguồn chung trong review hoặc cung cấp nguồn riêng nếu yêu cầu nghiệp vụ vẫn đòi nguồn riêng. Không repoint kênh đã có lịch sử.

## 5. Đối chiếu chín mục P4.2-A

| Mục checklist gốc | Trạng thái đủ chứng cứ hiện tại | Bằng chứng cần để đóng |
| --- | --- | --- |
| Owner/Admin và quyền thu/chi | Có mapping; trước đó GET PayOS được xác thực và có tiền; payout vẫn disabled | Đúng chủ thể/phạm vi nguồn chung hay riêng, quyền POST và chính sách duyệt |
| References/webhook/HTTPS/IP | Đã cấu hình theo báo cáo 07–08/11; không kiểm tra lại dashboard trong lượt này | Kết nối hiện tại trên source/runtime được duyệt, tunnel/IP hiện tại và secret đúng |
| Equality SePay webhook/v1 | Parser numeric đã có; chưa có evidence cho cùng giao dịch | Query v1 có quyền, đối chiếu ID/account/sub-account/chiều/amount/thời gian, giữ nguyên dữ liệu nhạy cảm |
| Retention/replay/NOT_SENT/recovery | Code query-only UNKNOWN đã có | Contract/xác nhận provider hoặc giới hạn query-only được chốt; kiểm chứng lost response/restart và đường xử lý cần hỗ trợ |
| Phí/minimum/funding/duyệt | Nguồn ví và credit từng có số dư; chưa có hợp đồng giới hạn cụ thể | Bảng phí/hạn mức/cơ chế duyệt theo kênh và 5.000đ; đủ principal và credit cho pilot |
| Lookup đích trước chi | **Thiếu implementation production và entitlement** | Contract áp dụng, cấp quyền, adapter/test và lookup thật được xác minh |
| Nối config/adapter/test | Adapter payout và test offline có; lookup mặc định từ chối | Thay đổi tối thiểu theo contract đã xác nhận, regression trên source cuối |
| Test mode/live/recovery | Có bằng chứng fixture lịch sử; không có bằng chứng live mới trong lượt này | Test provider đúng môi trường, durable inbox/ACK/retry và payout thực được đối soát theo phạm vi khoản tiền/đích xác định |
| Evidence/reviewer | Báo cáo và evidence chuẩn bị đã bổ sung | Reviewer `huy69332` xác nhận đúng phiên bản source, kênh/môi trường và các giới hạn còn lại |

Không đánh dấu cả mục là hoàn tất chỉ vì một phần của mục có bằng chứng. Trạng thái “chưa triển khai” trong plan 30/10 đã cũ so với các lần cấu hình 07–08/11, nhưng chín checkbox chưa thể đồng loạt chuyển thành đạt.

## 6. Đối chiếu năm mục P8.8

1. **Build/baseline/migration:** đã có kiểm chứng local lịch sử; lượt này bổ sung backup/restore và schema audit hiện tại. Cần review baseline/17 CHECK, chốt source/JAR triển khai và chạy kiểm chứng trên source cuối sau khi sửa provider. Không gọi test cũ là kết quả chạy mới.
2. **Small/quota/expiry/renewal/downgrade:** implementation và evidence offline đã có; chỉ chạy lại khi source liên quan đổi hoặc cần chứng minh phiên bản cuối. Không cần viết lại nghiệp vụ.
3. **Hai chuyển thật/late refund:** còn thiếu evidence. Duplicate phải là hai giao dịch ngân hàng khác nhau cùng order, không chỉ replay một webhook; một lần cấp/gia hạn, một refund đúng Admin. Late không cấp gói và hoàn đủ.
4. **Owner/Admin/pilot activation:** có mapping/flags cấu hình, nhưng payout tắt, verifier chưa có và hiện không listener 8080. Cần P4.2-A, review và khởi động đúng source/JAR/profile; Owner khác không mở checkout. Khi dừng pilot vẫn giữ xử lý tiền/đối soát đang pending.
5. **SDD/decisions/public scope:** đã có local; cập nhật theo implementation cuối và evidence thật, reviewer `thanhmit` xác nhận (Car/Booking thêm `huy69332` khi liên quan).

## 7. Thứ tự ngắn nhất để đạt 100%

1. **Đầu vào provider:** chốt endpoint lookup đang được hỗ trợ/cấp quyền cho tài khoản; auth/scope/response không chuyển tiền; giá/rate limit/ngân hàng. Đồng thời lấy điều kiện payout/minimum/auto-approval và recovery của đúng kênh. Không phải yêu cầu gửi keys/OTP vào chat.
2. **Triển khai code còn thiếu:** thêm một verifier thật và cấu hình riêng theo contract đã chốt; tận dụng flow preview/confirm. Chỉ sửa payout mapping/validation nếu nguồn chính thức chứng minh cần. Giữ UNKNOWN query-only khi chưa có guarantee replay.
3. **Kiểm chứng bản cuối:** test adapter HTTP và flow recipient, refund worker/restart/lost response; regression subscription/pilot/financial views; full build và browser nghiệp vụ trên DB riêng. Chốt manifest/JAR hash.
4. **Review/cutover:** dùng kết quả restore ở trên; chốt baseline schema và chính sách nguồn chung, review source/config/mapping; kiểm tra IP/tunnel hiện tại rồi chạy đúng bản. Không chạy lại migration one-shot khi bảng đã tồn tại.
5. **Nghiệm thu provider và P8.8:** người dùng chủ động xác định khoản tiền/kênh/đích rồi thực hiện các giao dịch thật được yêu cầu. Đối chiếu SePay webhook/v1, receipt/order/entitlement, PayOS attempt/transaction và tiền vào tài khoản nhận. P4.2-A phải chứng minh cả phạm vi nguồn Owner và Admin cần dùng; P8.8 tập trung Admin hoàn cho Owner.
6. **Đóng checklist:** đủ evidence cho từng mục và reviewer tương ứng xác nhận; cập nhật trạng thái/tài liệu, không suy ra P5.9 đã hoàn tất chỉ vì đóng hai task này.

Hiện phần backup/restore local đã có bằng chứng mới. Điểm chặn có tác động lớn nhất vẫn là **entitlement/contract lookup và capability kênh chi**, không phải thiếu schema hoặc thiếu code quota.

Giới hạn: chưa gọi provider có xác thực, chưa chạy lại JUnit/browser, chưa thu/chi tiền thật, chưa triển khai/commit/push. Không sửa dữ liệu nghiệp vụ. Nghiên cứu có thư mục tài liệu công khai còn ở `%TEMP%/carrent-p42a-contract-research-20261108`; thao tác cleanup bị công cụ chặn, không tính là đã dọn.
