# P4.2 — Hợp đồng tích hợp provider

**Kiểm tra tài liệu:** 2026-10-29. **Phạm vi:** P4.2 provider offline hoàn thành; cấu hình tài khoản và gate provider/live chuyển sang **P4.2-A** theo yêu cầu chủ dự án, chờ đăng ký/kích hoạt đủ tài khoản. **Live:** chưa kiểm chứng. **Quyết định đã duyệt:** dùng API payOS cho hoàn tiền; ví Bảo Kim là nguồn tiền phía sau kênh chi payOS. Contract tiếp tục dùng cho P4.3–P4.8.

## 1. Lựa chọn tích hợp

Ứng dụng Spring Boot dùng HTTP/webhook trực tiếp; khi cần gọi API, dùng `RestClient` có sẵn. Chưa thêm SDK/plugin hoặc dependency. Nhận webhook là request vào ứng dụng, không cần SDK để tính HMAC.

SePay có [Test mode](https://developer.sepay.vn/vi/sepay-webhooks/test-mode/bat-dau-nhanh) tách biệt Live, có thể tạo ngân hàng thử và mô phỏng giao dịch. Test tự động hiện tại chỉ dùng fixture trong repo, **chưa gọi Test mode của SePay**.

## 2. Mapping SePay

| Mục | Hợp đồng / xử lý |
|---|---|
| Xác thực đã chọn | HMAC-SHA256; header `X-SePay-Signature: sha256=<hex>` và `X-SePay-Timestamp` (Unix giây). Ký `{timestamp}.` nối **bytes body gốc**, không parse/serialize lại trước xác thực. So sánh constant time; từ chối timestamp lệch quá ±300 giây và header bảo mật lặp. |
| Cấu hình | `carrent.payment.providers.sepay.<reference>.enabled/webhook-secret`. Không có cấu hình thì không kênh nào hoạt động. Kênh bật thiếu secret làm cấu hình thất bại; `toString()` che secret. |
| Chủ thể | Mỗi kênh DB ở P4.3 giữ credential reference phía server. Owner/Admin dùng reference và secret riêng. Không lấy reference từ form Customer; không thử lần lượt mọi secret khi xác thực thất bại. |
| Môi trường | Dùng reference riêng cho Test/Live; không trỏ cả hai vào cùng kênh DB hoặc dùng chung secret. Chưa có endpoint công khai trong P4.2. |
| ID webhook | `id` số nguyên dương, không đổi qua retry/replay; canonical `providerTransactionId` là chuỗi thập phân của ID số, không thêm namespace theo nguồn nhận. P4.3/P4.5 dùng UNIQUE(channelId, providerTransactionId); Test/Live và Owner/Admin có channelId riêng. Chữ ký hợp lệ không có nghĩa đã áp dụng tiền hoặc đã chống trùng. |
| Ngày ngân hàng | `transactionDate`: `yyyy-MM-dd HH:mm:ss`, giờ Việt Nam (`Asia/Ho_Chi_Minh`). Đây là thời gian giao dịch; timestamp chữ ký là thời gian gửi, không dùng thay ngày nhận tiền. |
| Số tiền / chiều | `transferAmount` số nguyên VND dương; chỉ `transferType=in` cho thu tiền. Số dư `accumulated` không phải số tiền thanh toán. Xác thực chữ ký không thay kiểm tra payload/tài khoản/intent ở P4.5. |
| ACK | P4.5 chỉ trả 200/201 + `{"success":true}` sau khi lưu inbox bền vững; không ACK chỉ vì đã xác thực hoặc đã enqueue RAM. Không chờ xử lý nghiệp vụ rồi mới ACK. |
| Hướng đối soát thu | Chọn API v1 numeric ID làm hướng tương thích: `GET https://my.sepay.vn/userapi/transactions/details/{transaction_id}` hoặc `/transactions/list`, Bearer API token riêng. v1 còn hoạt động nhưng ngừng phát triển tính năng. Chưa viết query client, chưa tự động ingest API vào inbox. |
| Gate định danh | Tài liệu gọi cả webhook.id và v1.transaction.id là ID giao dịch SePay nhưng chưa có bảo đảm tường minh hai giá trị bằng nhau. Trước ingest chung phải lấy cùng giao dịch trên kênh thật để xác minh ID, account/sub-account, chiều và amount khớp; mismatch giữ review, không tạo receipt thứ hai từ namespace khác. |
| API v2 / tham chiếu ngân hàng | v2 dùng UUID, không truyền webhook ID số vào endpoint v2 hoặc dùng UUID như giao dịch mới trong cùng luồng. Chưa có alias numeric↔UUID đã kiểm chứng; không ingest v2. `referenceCode` có thể rỗng, không dùng làm khóa dedupe thay ID hoặc khớp mờ theo ngày/tiền. |
| Chuẩn hóa API v1 khi triển khai | `id` response là chuỗi số; `amount_in`/`amount_out` là chuỗi thập phân. Parse bằng BigDecimal và chuyển VND bằng `longValueExact`, không float/round; reject giá trị lẻ, âm hoặc tràn. V1 có thể trả HTTP 200 với body lỗi/rỗng: phải kiểm tra status=200, messages.success=true, error rỗng và payload hợp lệ; HTTP 200 riêng lẻ không chứng minh đối soát thành công. |

Nguồn chính thức: [xác thực](https://developer.sepay.vn/vi/sepay-webhooks/xac-thuc), [payload/ACK/chống trùng](https://developer.sepay.vn/vi/sepay-webhooks/tich-hop-webhook), [đối soát](https://developer.sepay.vn/vi/sepay-webhooks/doi-soat-giao-dich).

Nguồn kiểm tra bổ sung 2026-10-29: [API giao dịch v1](https://developer.sepay.vn/vi/sepay-api/v1/api-giao-dich), [so sánh và chuyển đổi v1/v2](https://developer.sepay.vn/vi/sepay-api/v2/nang-cap-tu-v1). Sự tương thích numeric ID là hướng thiết kế của CarRent, chưa phải bằng chứng ID bằng nhau trên môi trường đã chọn. Quyền query/Test mode và equality còn là gate thực tế.

## 3. Hoàn tiền — đã chốt payOS

**Phân vai và dòng tiền nguồn:** Backend gọi API payOS để tạo/đối soát lệnh chi; tiền hoàn được trích từ ví Bảo Kim của đúng chủ thể đã liên kết với kênh chi. Owner cấp tiền nguồn để hoàn Customer; Admin cấp nguồn riêng để hoàn phí gói cho Owner. Tiền nhận qua SePay vào ngân hàng không tự nạp sang ví bởi CarRent. Customer chỉ cần tài khoản ngân hàng nhận hoàn. Xem [hướng dẫn kết nối ví chính thức](https://payos.vn/docs/huong-dan-su-dung/kenh-chi/ket-noi-tai-khoan/vi-baokim/) và [nạp ví nguồn](https://payos.vn/docs/huong-dan-su-dung/kenh-chi/nap-so-du-vi/). Chưa có bằng chứng nguồn payOS độc lập thay thế Bảo Kim cho kênh đang chọn; chỉ xem xét thay nguồn nếu provider xác nhận và chủ dự án duyệt. Không thêm API Bảo Kim trực tiếp.

- Chủ dự án đã chọn **payOS**. Backend gọi API payOS; không tích hợp API chi hộ Bảo Kim trực tiếp. Customer chỉ nhập tài khoản ngân hàng nhận hoàn.
- [Kênh chi payOS](https://payos.vn/dang-ky-dich-vu-chi-ho/) yêu cầu ví Bảo Kim làm nguồn tiền, định danh khớp chủ thể và cấu hình kênh/IP. Owner và Admin cần nguồn tiền riêng. Không suy ra đã có kênh chi từ việc có tài khoản nhận SePay.
- Chưa xác nhận dịch vụ chi hộ miễn phí. [payOS](https://payos.vn/chi-ho/) mô tả chi phí thấp; [Bảo Kim](https://baokim.vn/dich-vu/chi-ho) mô tả phí theo món. Cần xác nhận phí, VAT, phí nạp/rút, hạn mức và khoản tối thiểu (đặc biệt hoàn phí gói 5.000 VND) trên kênh thực tế. Không dùng thông tin miễn phí của kênh thu để suy ra phí kênh chi.
- [Gói dịch vụ chi payOS](https://payos.vn/docs/huong-dan-su-dung/kenh-chi/thanh-toan-goi-dich-vu/) có credit dịch vụ và gói tính theo số giao dịch; credit dùng thử không phải số dư tiền chuyển. [Ví nguồn Bảo Kim](https://payos.vn/docs/huong-dan-su-dung/kenh-chi/nap-so-du-vi/) phải nạp riêng. Kiểm chứng cả quyền/credit dịch vụ lẫn tiền nguồn; không gán lỗi credit chưa xác minh thành WAITING_FUNDS hoặc NOT_SENT.
- Đã triển khai `PayOsPayoutClient`/`HttpPayOsPayoutClient` bằng RestClient, các record có kiểu và fixture/stub test. P4.8 đã nối default bean với PayoutChannelResolver `payout_channel_id -> credential reference -> PayOsChannel` phía server và worker kiểm tra kênh thuộc đúng collector của refund trước khi gọi adapter; chưa có endpoint kích hoạt worker hoặc scheduler P4.9; không lấy ID kênh từ Customer.

### Mapping API payOS đã kiểm tra

| Mục | Hợp đồng adapter offline |
|---|---|
| Tạo lệnh chi đơn | `POST /v1/payouts`; body `referenceId`, `amount`, `description`, `toBin`, `toAccountNumber`. Không dùng API tạo link thanh toán hoặc batch. |
| Credential | Headers `x-client-id`, `x-api-key` của kênh chi đúng Owner/Admin; config `carrent.payment.providers.payos.<reference>.*`, mặc định tắt. Bật thiếu credential thì fail cấu hình. |
| Idempotency | Header `x-idempotency-key` bắt buộc do caller lấy từ payout attempt bền vững; adapter không tự sinh/đổi key và không tự retry. Public API/SDK chưa nêu retention, phạm vi uniqueness hoặc replay sau lỗi; chưa tự POST lại kể cả cùng key khi không rõ lệnh cũ đã gửi/retention. Không coi timeout hoặc key cũ là được tạo attempt mới. |
| Chữ ký | Request `x-signature`, response `x-signature` ký object `data`. Theo SDK chính thức: sort object keys đệ quy, giữ thứ tự array, JSON compact cho container, URI encode từng key/value (space `%20`), nối `key=value` bằng `&`, HMAC-SHA256 checksum key, hex thường. Chỉ envelope `code=00` với chữ ký hợp lệ mới được map kết quả. |
| Thành công | Đúng một `transactions` item; **transaction.state=SUCCEEDED**. `approvalState` không chứng minh đã chuyển tiền. `referenceId` phải là chuỗi không rỗng và khớp giữa payout/transaction. Create đối chiếu reference, số tiền, ngân hàng và tài khoản với command; get đối chiếu payout ID. `PayoutResult` trả thêm signed referenceId cho worker đối chiếu durable attempt trước khi ghi hoàn thành. |
| Đang xử lý | Transaction state RECEIVED, PROCESSING, ON_HOLD → PROCESSING. |
| Chưa rõ | FAILED/CANCELLED/REVERSED/state lạ, chữ ký thiếu/sai, body lỗi, mismatch, HTTP error hoặc timeout → UNKNOWN. Tài liệu đã đọc chưa bảo đảm các trạng thái lỗi đồng nghĩa chưa gửi; adapter **chưa trả NOT_SENT** để tránh chi trùng. |
| Truy vấn | `get(channelId,payoutId)` → `GET /v1/payouts/{id}`; `findByReference(channelId,referenceId)` → `GET /v1/payouts?referenceId=...&limit=2&offset=0` để phục hồi khi mất response tạo. 0/nhiều kết quả vẫn UNKNOWN, không phải bằng chứng chưa chi. Worker đối chiếu channel/source, ID đã biết, referenceId, amount và destination snapshot; GET/list chỉ cung cấp dữ liệu đối soát, không tự đổi refund thành SUCCEEDED. |
| Timeout | Client mặc định connect 5 giây/read 10 giây, không follow redirect, không tự retry POST. Network ngoài DB transaction/Car queue theo plan worker. |
| Phí/tối thiểu | API có `/v1/payouts/estimate-credit` nhưng chưa gọi; ví dụ estimateCredit không chứng minh minimum payout hoặc phí cố định. Chưa xác minh khoản hoàn 5.000 VND, phí/VAT hoặc số dư của kênh thực tế. Không khẳng định miễn phí. |
| Tên người nhận | Public API/SDK có toAccountName trong response payout, chưa tìm được endpoint lookup tên độc lập trước create. validateDestination ở batch/estimate chưa đủ chứng minh có chức năng lookup độc lập. Không gửi payout để tra tên; khả năng xác minh đích cho P4.7 còn gate provider. |

Nguồn: [API payOS, mục Lệnh chi](https://payos.vn/docs/api/); SDK Java chính thức commit `b1e15df6a9eac6872d5a431186595a12838f58d8`: [chữ ký](https://github.com/payOSHQ/payos-lib-java/blob/b1e15df6a9eac6872d5a431186595a12838f58d8/src/main/java/vn/payos/crypto/CryptoProviderImpl.java), [header và kiểm tra phản hồi](https://github.com/payOSHQ/payos-lib-java/blob/b1e15df6a9eac6872d5a431186595a12838f58d8/src/main/java/vn/payos/core/Client.java), [payout service](https://github.com/payOSHQ/payos-lib-java/blob/b1e15df6a9eac6872d5a431186595a12838f58d8/src/main/java/vn/payos/service/blocking/v1/payouts/PayoutsServiceImpl.java). Không thêm SDK dependency; fixture ký độc lập bằng Python theo canonical format này.

Đối chiếu thêm [Payout.referenceId](https://github.com/payOSHQ/payos-lib-java/blob/b1e15df6a9eac6872d5a431186595a12838f58d8/src/main/java/vn/payos/model/v1/payouts/Payout.java), [PayoutTransaction](https://github.com/payOSHQ/payos-lib-java/blob/b1e15df6a9eac6872d5a431186595a12838f58d8/src/main/java/vn/payos/model/v1/payouts/PayoutTransaction.java) và [state enum](https://github.com/payOSHQ/payos-lib-java/blob/b1e15df6a9eac6872d5a431186595a12838f58d8/src/main/java/vn/payos/model/v1/payouts/PayoutTransactionState.java). Tài liệu/enum chưa bảo đảm FAILED/CANCELLED/REVERSED là NOT_SENT. Mapping UNKNOWN là chính sách an toàn của CarRent; không phải tuyên bố provider không bao giờ retry được.

### Chính sách recovery đã chốt cho P4.8

1. Lưu attempt/reference/key/source/beneficiary/amount trước khi gửi; không đổi chúng sau timeout hoặc restart.
2. Sau khi có khả năng lệnh đã gửi, ưu tiên GET theo provider ID hoặc list theo reference trên đúng kênh. Query lỗi/rỗng/nhiều kết quả giữ UNKNOWN và tiếp tục đối soát có backoff.
3. Không tự gọi create lại, đổi key hoặc tạo attempt mới chỉ vì chưa tìm thấy lệnh. Chỉ mở retry khi có bảo đảm chưa chuyển và contract replay/retention phù hợp đã kiểm chứng. Adapter hiện chưa phát NOT_SENT.
4. Chỉ kết quả đã xác thực và khớp durable attempt mới đủ điều kiện ghi hoàn thành. Thiếu/sai reference, ID, amount hoặc đích không được ghi success; không lấy approvalState thay state transaction.

## 4. Gate còn lại — chuyển sang P4.2-A, chưa thực hiện

Theo quyết định tách task của chủ dự án, các mục chưa đạt bên dưới thuộc [P4.2-A](phase-4-payment.md#task-p42-a--tích-hợp-tài-khoản-thực-tế-và-kiểm-chứng-providerlive--hoãn), bắt đầu sau đăng ký/kích hoạt đủ tài khoản. Ba mục đã tick là đầu ra P4.2 offline, không phải bằng chứng live. Các kiểm chứng phụ thuộc inbox/worker cần chờ code tương ứng; chưa tạo cấu hình/kết nối thực tế trong lần cập nhật tài liệu này.

- [x] Chủ dự án chốt payOS làm nhà cung cấp API chi hộ.
- [x] Đối chiếu API/SDK chính thức và triển khai adapter/test stub payOS cho create, get và tìm theo reference.
- [x] Đợt 1 chốt chính sách định danh SePay và recovery payOS từ nguồn chính thức, phân biệt điều đã có tài liệu và điều cần kênh thật.
- [ ] Xác minh điều kiện NOT_SENT, thời gian giữ idempotency key, phí/tối thiểu và khả năng vận hành tự động của kênh thực tế; chưa bật live khi còn thiếu.
- [ ] Kiểm chứng cùng giao dịch: webhook.id bằng v1.transaction.id; quyền query và môi trường Test/Live tương ứng. Chưa hợp nhất nguồn trước khi gate đạt; UUID v2 chưa có alias.
- [ ] Xác minh khả năng tra/xác thực tài khoản nhận trước dispatch cho P4.7; không thay bằng tên người nhận tự khai hoặc gửi tiền để lookup.
- [ ] Liên kết ngân hàng thu của một Owner và Admin; cấu hình secret, chọn webhook tiền vào và public URL local qua tunnel.
- [ ] Xác nhận kênh chi riêng Owner/Admin được phép dùng; đủ số dư, phí và hạn mức phù hợp.
- [ ] Kiểm chứng webhook Test mode qua endpoint durable inbox khi P4.3/P4.5 sẵn sàng, rồi giao dịch live nhỏ theo checklist P4.11.

**Checkpoint lịch sử khi tách P4.2-A:** P4.2 **hoàn thành theo phạm vi kỹ thuật/offline**; **P4.2-A chưa thực hiện**, còn toàn bộ gate tài khoản/provider/live chưa đạt. Tổng tại checkpoint **46/86**, còn 40 task; thay đổi bộ đếm do tách phạm vi, không phải giao dịch thật đã được xác minh. Checkpoint này không thay đổi schema, Payment legacy hoặc luồng thanh toán đang có. Tiến độ mới nhất tại [README](README.md).

## Cập nhật P4.7 — 2026-10-30

`RefundDestinationVerifier.verify(channelId, bankBin, accountNumber)` là biên lookup **không chuyển tiền**. Production bean mặc định từ chối; chỉ fixture test trả tên đã xác minh. Preview và submit gọi verifier ngoài transaction/Car queue, xác nhận lại tên provider ở lần submit trước ghi READY; mismatch/timeout/lỗi không ghi deadline/đích. Tên tự khai không được dùng thay provider và không gửi payout để lookup.

P4.2-A giữ nguyên gate kiểm chứng khả năng lookup, quyền/cấu hình và adapter thật. P4.7 hoàn thành nền/offline không chứng minh API lookup hoặc khả năng xác nhận tài khoản thật đã hoạt động. P4.8 đã triển khai dispatch/reconcile offline riêng; vẫn không được bật live khi P4.2-A chưa đạt.

## Cập nhật P4.8 — 2026-10-30

Worker/transactions đã kiểm chứng lưu attempt trước HTTP, lease fencing, đối soát theo ID/reference cùng nguồn và không repost UNKNOWN. NOT_SENT có thêm NotSentReason (INSUFFICIENT_FUNDS/RETRYABLE/PERMANENT); constructor cũ giữ tương thích. **HTTP adapter production vẫn không trả NOT_SENT**, không tự suy diễn từ FAILED/CANCELLED/HTTP error. Funding/retry là capability fixture cho tới P4.2-A kiểm chứng provider bảo đảm chưa chi và loại lỗi tương ứng.

Định danh nguồn kênh (ID/user/role/credential reference) bất biến theo vận hành; đổi tài khoản phải tạo kênh mới, không repoint nguồn có lịch sử tài chính. Crash sau lưu PREPARED nhưng trước actual send vẫn chỉ query và có thể giữ UNKNOWN lâu dài khi lookup rỗng; chưa có hợp đồng replay/certainty để tự resend. P4.9 mới cung cấp scheduler; test worker/restart hiện tại không chứng minh tự phục hồi runtime. Live và lookup đích độc lập vẫn pending P4.2-A.
