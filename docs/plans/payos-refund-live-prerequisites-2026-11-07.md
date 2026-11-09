# payOS refund: điều kiện vận hành thật

Ngày đối chiếu: **2026-11-07**. Phạm vi: tài liệu công khai chính thức payOS/Casso/VietQR và mã nguồn SDK của payOSHQ. Chỉ GET tài liệu; không đọc cấu hình/khóa, không truy cập tài khoản vận hành, không gọi API xác thực, không tạo lệnh chi, không sửa DB hoặc mã ứng dụng. Đây là bằng chứng về hợp đồng công khai, chưa phải bằng chứng kênh CarRent đã được cấp quyền hoặc thử nghiệm giao dịch thật.

## Kết luận và hướng đi cụ thể

1. **Có sản phẩm tra cứu tên tài khoản độc lập được Casso công bố:** VietQR `POST https://api.vietqr.io/v2/lookup`, đầu vào `bin` và `accountNumber`, đầu ra thành công `data.accountName`. Tuy nhiên chính trang tài liệu thông báo đang chuyển sang **CAS Pay Out**; cần xác nhận endpoint, cấp quyền và giá áp dụng cho tài khoản trước khi triển khai. Không khẳng định API này miễn phí hoặc tài khoản mới vẫn dùng được endpoint cũ. [S1]
2. **`estimate-credit` chưa đủ làm contract lấy tên người nhận.** Tài liệu payOS có `validateDestination=true` nhưng response thành công được công bố chỉ chứa `data.estimateCredit`. SDK đồng nhất với điều này. Cần hỏi payOS xem có API/phiên bản được hỗ trợ trả tên trước khi chuyển; không suy luận rằng payOS tuyệt đối không có API nội bộ/thương mại như vậy. [S2][S3]
3. **Hành động ngắn nhất:** gửi yêu cầu hỗ trợ ở cuối báo cáo tới payOS/Casso, ưu tiên hỏi khả năng tra cứu với quan hệ tài khoản hiện có. Nếu lookup hiện tại được cấp quyền, tích hợp một verifier độc lập; nếu họ yêu cầu CAS mới, lấy contract lookup và điều kiện production chính thức rồi mới chọn. Không tự tái sử dụng khóa payout payOS cho VietQR/CAS. [S1][S8]
4. **Hai loại số dư khác nhau:** nạp ví Bảo Kim cung cấp tiền gốc để hoàn; mua gói/credit payOS trả cho dịch vụ chi. Credit dùng thử 100K không phải tiền gốc để hoàn. [S5][S6]
5. **Tài liệu mô tả chi tự động, nhưng chưa xác nhận cơ chế duyệt/OTP cho từng kênh thực tế.** Cần xác nhận quyền `POST`, IP nguồn, tự động duyệt và giới hạn với payOS; GET balance thành công không tự chứng minh quyền tạo lệnh. [S2][S4][S7]

## Bằng chứng theo vấn đề

### 1. Tra cứu tên tài khoản trước khi chi

| Nguồn | Đã xác minh từ tài liệu | Chưa xác minh / giới hạn |
| --- | --- | --- |
| payOS `POST /v1/payouts/estimate-credit` | Mô tả là ước tính phí; body batch có `referenceId`, `category`, `validateDestination`, `payouts`. Mỗi payout chứa BIN và số tài khoản. Response mẫu: `{"code":"00","desc":"success","data":{"estimateCredit":5000}}`. | Không có `accountName`, danh sách kết quả hoặc tên gắn với từng BIN/số tài khoản trong response công bố. Không có cam kết công khai rằng cờ này trả tên, luôn thực hiện lookup ngay, hay hành vi lỗi đủ làm bằng chứng tên người nhận. **5000 là dữ liệu mẫu, không phải mức phí cố định hoặc số tiền chuyển tối thiểu.** [S2] |
| SDK payOSHQ `estimateCredit` | Gọi `/v1/payouts/estimate-credit`; nhận `PayoutRequest` hoặc `PayoutBatchRequest`; kiểu `EstimateCredit = { estimateCredit: number }`. | Mã SDK không đủ để kết luận dịch vụ không có endpoint khác hoặc trường mở rộng chưa tài liệu hóa. [S3] |
| payOS `POST /v1/payouts/batch` | Có `validateDestination`; response tạo payout có `transactions[].toBin`, `toAccountNumber`, `toAccountName`. | Đây là **tạo lệnh chi**, không phải phương thức tra cứu độc lập an toàn trước khi khách xác nhận. Không tạo payout để tìm tên. [S2] |
| payOS `GET /v1/payouts-account/balance` | Trả `accountNumber`, `accountName`, `currency`, `balance` của **nguồn chi**. | Không nhận BIN/số tài khoản người thụ hưởng; không dùng làm lookup khách hàng. [S2][S3] |
| VietQR/Casso lookup | Header `x-client-id`, `x-api-key` được tài liệu hướng dẫn lấy ở `https://my.vietqr.io`; body `{ "bin": 970415, "accountNumber": "..." }`; code `00` trả `data.accountName`. Các lỗi công bố gồm `42` tài khoản không hợp lệ, `41` ngân hàng không hỗ trợ, `43` lỗi không xác định, `45` provider bận; HTTP 429 giới hạn truy cập. | Trang thông báo đang migrate sang CAS Pay Out. Response không echo BIN/số tài khoản: ứng dụng phải gắn response với chính request của mình, không chấp nhận dữ liệu do trình duyệt tự khai. Tài liệu đang có mô tả độ dài tài khoản không nhất quán giữa bảng tham số và ví dụ lỗi; cần contract hiện hành. Demo trang là mô phỏng, không chứng minh lookup live. [S1] |
| CAS Pay Out hiện tại | Trang sản phẩm nói bộ chuyển tiền, dẫn tới nhóm Transfer; nhóm này hiện liệt kê tạo lệnh và truy vấn lệnh đã tạo. Quickstart công bố khóa CAS `x-client-id`/`x-secret-key`, Sandbox và Production riêng. | Qua các trang liên kết trực tiếp đã đọc, chưa có contract lookup độc lập mới đủ rõ để triển khai thay `/v2/lookup`. Không tự đặt endpoint hoặc coi API tra cứu giao dịch đã tạo là tra cứu tên người thụ hưởng. [S8][S9] |
| Bảo Kim trực tiếp | payOS công bố liên kết ví Bảo Kim để chi tự động. | Chưa thu được tài liệu API Bảo Kim trực tiếp có contract tra cứu tên đích độc lập; `https://docs.baokim.vn/` tại lúc GET chỉ trả trang Laravel mặc định. Đây là **chưa xác minh**, không phải chứng cứ lookup không tồn tại. Không cần chuyển thiết kế sang API chi Bảo Kim trực tiếp chỉ vì khoảng trống tài liệu. |

**Điều kiện để đóng gate verifier:** contract được cấp quyền phải xác nhận lookup không tạo/chuyển tiền; trả tên chuẩn cho đúng ngân hàng/số tài khoản đầu vào; có quy tắc thành công/lỗi/ngân hàng không hỗ trợ/timeout/rate limit và chi phí. Sau đó ứng dụng phải cho khách xem và xác nhận tên trả về, giữ liên kết với BIN/số tài khoản đã lookup và không duyệt khi lỗi hoặc thiếu tên. Đây là yêu cầu tích hợp CarRent, chưa phải kết quả đã chạy.

### 2. Kênh payout, quyền POST, IP, OTP và duyệt tự động

- **Đã xác minh:** tài liệu tạo kênh yêu cầu tài khoản payOS, tổ chức được xác thực và tài khoản liên kết; vào **Kênh chuyển tiền → Thêm kênh chuyển tiền**, chọn ví/ngân hàng; có bước **Quản lý IP cho kênh → Thêm IP**, có thể thêm nhiều IP. Backend cần dùng IP egress thực tế; URL webhook/tunnel không thay thế IP egress này. Cách chọn egress là hệ quả triển khai, không phải xác nhận IP của máy hiện tại. [S4]
- **Đã xác minh:** ví Bảo Kim liên kết phải trùng tên tổ chức đã xác thực; chọn đúng loại cá nhân/doanh nghiệp; hộ kinh doanh chọn cá nhân theo hướng dẫn. Bước liên kết có OTP gửi số điện thoại đăng ký ví; ví doanh nghiệp có thể chờ Bảo Kim xác minh 1–2 ngày sau khi liên kết. [S7]
- **Đã xác minh:** ví dụ SDK nói rõ credentials payout khác credentials tạo yêu cầu thanh toán. Single/batch create cần `x-client-id`, `x-api-key`, `x-signature`, `x-idempotency-key`; docs có response 401/403 cho create. [S2][S10]
- **Đã xác minh ở mức sản phẩm:** payOS quảng bá chi hoàn toàn tự động qua API; hướng dẫn nguồn chi nói tiền được trích khi lệnh khởi tạo và phê duyệt. SDK có `approvalState` như `DRAFTING`, `SUBMITTED`, `APPROVED`, `REJECTED`, `SCHEDULED`, `PROCESSING`, `COMPLETED`... [S5][S11][S3]
- **Chưa xác minh:** kênh cụ thể có quyền create production; cơ chế tự động duyệt được bật ở đâu; mọi lệnh hay lệnh vượt hạn mức cần OTP/PIN/duyệt tay; ai là người duyệt; thời gian cut-off; allowlist IP có bắt buộc khi chưa khai báo; hạn mức từng giao dịch/ngày và các điều kiện chặn. OTP lúc **liên kết** không chứng minh cần hoặc không cần OTP cho **mỗi payout**. Không dùng quảng cáo tự động hóa để cam kết backend luôn hoàn không cần thao tác người dùng.

### 3. Nguồn tiền, credit dịch vụ, tối thiểu và phí

| Khoản | Đã xác minh | Chưa xác minh |
| --- | --- | --- |
| Tiền gốc | **Ngân hàng → Tài khoản chi Bảo Kim → Nạp số dư**. QR nạp ví xuất hiện sau khi chọn số tiền. Số tiền chi được trích từ ví khi lệnh khởi tạo và duyệt. Hướng dẫn nói cộng số dư có thể mất 2–5 phút và có đồng bộ thủ công. [S5] | Số dư thực tế, nguồn tiền được chấp nhận, hạn mức, phí nạp/rút, thời gian áp dụng cho tài khoản cụ thể. |
| Credit payOS | **Thiết lập → Sử dụng → Chi → Nạp tiền** mua gói dịch vụ theo số lượng giao dịch. Tài liệu nói lúc bắt đầu dùng kênh Chi có **credit 100K dùng thử**. [S6] | Tài khoản hiện có còn credit hay không; đơn giá; thuế; hiệu lực credit; credit thất bại/lookup có bị tính; cách trừ và hoàn phí. Không đồng nhất credit với tiền gốc. |
| Phí ước tính | Có endpoint `estimate-credit` cho request cụ thể. [S2] | Không suy ra phí cố định từ response mẫu `5000`; không khẳng định phí chuyển Bảo Kim đã bao gồm trong đó. |
| Số tiền chuyển tối thiểu | Không có mức min/max được xác lập trong phần contract payout công khai đã đối chiếu. Ví dụ SDK dùng 2.000đ; ví dụ docs dùng 100.000đ. [S2][S10] | Ví dụ không phải giới hạn nghiệp vụ. Cần payOS/Bảo Kim xác nhận mức min/max cho payout và các phí thực tế. |
| 10.000đ ở onboarding ví | Hướng dẫn tạo ví nói chuyển 10.000đ để xác minh liên kết ngân hàng và số này cộng vào ví. [S12] | Đây là bước xác minh liên kết ngân hàng, **không phải payout tối thiểu**. Không thực hiện trong nghiên cứu này. |
| CAS thương mại | Trang giá phân biệt Free để xây dựng/thử nghiệm với tài khoản test và Vendor từ 500 USD/tháng đáp ứng tiêu chuẩn. [S13] | Không gán giá Vendor cho riêng lookup; chưa có báo giá lookup production, chính sách tài khoản cũ hoặc cam kết miễn phí. |

### 4. Idempotency, NOT_SENT và recovery

- **Đã xác minh:** `x-idempotency-key` là bắt buộc cho create single/batch trong docs; SDK mô tả dùng UUID/chuỗi entropy cao để lần retry cùng request được nhận ra và tránh chi trùng. SDK mặc định tạo UUID mới mỗi lần gọi nếu caller bỏ qua. Vì thế ứng dụng cần giữ một khóa bền vững cho một lệnh logic nếu phải dùng cơ chế này. [S2][S3]
- **Đã xác minh:** list payout có filter `referenceId` và phân trang; detail dùng payout ID. SDK liệt kê transaction states `RECEIVED`, `PROCESSING`, `CANCELLED`, `SUCCEEDED`, `ON_HOLD`, `REVERSED`, `FAILED`. [S2][S3]
- **Chưa được công bố đủ:** thời gian giữ idempotency key, phạm vi key theo channel/tổ chức, hành vi cùng key khác body, tính duy nhất tuyệt đối của `referenceId`, khả năng đọc ngay sau create, semantics khi response timeout hoặc 429/5xx, thời gian hoàn tiền và phí khi failed/reversed.
- **Không có contract NOT_SENT được xác lập trong nguồn đã đọc.** `NOT_SENT` không nằm trong enum transaction SDK; docs có mã HTTP lỗi nhưng không cam kết mọi 4xx/5xx/timeout hoặc list rỗng tương đương chắc chắn chưa chuyển. Không coi việc thiếu trạng thái trong SDK là bằng chứng dịch vụ tuyệt đối không có mã khác.
- **Quy tắc đề nghị cho CarRent:** mất response create, signature lỗi, kết quả mơ hồ hoặc query không thấy lệnh vẫn cần recovery/đối soát; không tạo lệnh mới với key/reference mới chỉ vì lookup rỗng. `FAILED`/`CANCELLED`/`REVERSED` phải có semantics provider rõ trước khi coi là đủ để cho phép chi lại. Đây là lựa chọn an toàn của ứng dụng, chưa được quảng bá thành bảo đảm của payOS.
- **Sai khác tài liệu cần chú ý:** example list trong docs dùng `approvalState=SUCCEEDED`, trong khi SDK để `SUCCEEDED` ở transaction state và tập approval state riêng. Không quyết định trạng thái tiền từ một example approval state. [S2][S3]

## Yêu cầu hỗ trợ có thể gửi

**Chưa gửi ra ngoài.** Kênh hỗ trợ công bố của payOS: `cskh@payos.vn`, hotline `1900 8144`. [S11] Người dùng có thể gửi qua kênh chính thức, tự cung cấp ID kênh/tổ chức an toàn khi phía hỗ trợ yêu cầu; không gửi secret/OTP/PIN/mật khẩu.

> Chào payOS/Casso, chúng tôi đang tích hợp hoàn tiền qua kênh chi payOS liên kết ví Bảo Kim, cần xác minh tên tài khoản khách trước khi khách xác nhận và trước khi tạo lệnh chuyển tiền. Nhờ hỗ trợ xác nhận:
>
> 1. Hiện có API tra cứu độc lập, không tạo lệnh/chuyển tiền, trả tên chủ tài khoản theo BIN + số tài khoản dùng với quan hệ tài khoản payOS hiện có không? `estimate-credit` với `validateDestination=true` có contract trả tên chuẩn hay chỉ trả phí? Xin tài liệu response/schema, error codes, cam kết không chuyển tiền và phí gọi lookup.
> 2. Trang VietQR `/tra-cuu-stk` nói `/v2/lookup` đang migrate sang CAS Pay Out. Tài khoản mới/cũ hiện dùng endpoint nào? Có thể cấp entitlement lookup cho chúng tôi không? Khóa lookup được cấp riêng ở đâu; xin xác nhận không tự dùng khóa payout payOS. Nếu dùng CAS mới, xin endpoint lookup production/sandbox, headers, scope/quyền, ngân hàng hỗ trợ, timeout/rate limit, giá và điều kiện onboarding.
> 3. Kênh payout production cần quyền POST/activation nào ngoài GET balance/list? IP allowlist áp dụng ra sao? Có tự duyệt single payout qua API không, và có OTP/PIN/duyệt tay mỗi lệnh hoặc theo hạn mức không? Xin hướng dẫn chính xác cho loại ví của chúng tôi.
> 4. Xin mức chuyển tối thiểu/tối đa, hạn mức ngày, phí payOS/Bảo Kim/VAT và các phí khi lỗi/hoàn; ví Bảo Kim và credit dịch vụ cần mức nào để chạy pilot nhỏ? Credit dùng thử có giới hạn gì?
> 5. Xin contract idempotency: thời gian giữ key, scope, cùng key khác body, unique referenceId, delay query sau create. Những error code/trạng thái nào bảo đảm lệnh chưa gửi ngân hàng và an toàn tạo lại? Timeout/5xx/không tìm thấy reference được recovery như thế nào? Xin hướng dẫn đối soát FAILED/CANCELLED/REVERSED và hoàn tiền/phí.

## Bằng chứng cần có trước pilot thật

1. Trả lời provider hoặc tài liệu cập nhật xác nhận lookup được hỗ trợ và được cấp quyền, cùng contract nêu trên.
2. Chứng cứ đã liên kết đúng nguồn cho từng vai trò tài chính cần dùng, quyền POST và IP egress; trạng thái duyệt/OTP/hạn mức được xác nhận. Owner/Admin vẫn giữ hai mapping nội bộ; có thể dùng chung nguồn nếu chủ dự án và điều kiện provider cho phép. Một GET bằng bộ khóa dùng chung không chứng minh cả hai luồng nghiệp vụ đã được nghiệm thu.
3. Verifier đã tích hợp và kiểm thử: trả tên đúng request; tên trống/mismatch/lỗi/unsupported/timeout đều chặn; khách xác nhận đúng tên và đích đó trước khi chi.
4. Nguồn ví và credit riêng đủ dùng; min/fees được xác nhận; durable idempotency/recovery phù hợp contract.
5. Pilot người dùng chủ động và đối soát với provider được phê duyệt theo runbook dự án. Báo cáo này không thực hiện hoặc thay thế pilot.

## Nguồn chính thức

- **[S1]** VietQR/Casso, [Tra cứu STK](https://www.vietqr.io/tra-cuu-stk), [Markdown](https://www.vietqr.io/tra-cuu-stk.md). Contract lookup, credentials, lỗi, thông báo migration và demo mô phỏng.
- **[S2]** payOS, [API reference](https://payos.vn/docs/api/), [Markdown](https://payos.vn/docs/api.md), các mục `create-single-payout`, `create-batch-payout`, `estimate-credit`, `get-payouts`, `get-account-balance`.
- **[S3]** payOSHQ SDK Node, commit `cf970978e4d5f0ce5887ef2171f9ec5b6e9eb73e` (HEAD khi đọc), [payouts.ts](https://github.com/payOSHQ/payos-node/blob/cf970978e4d5f0ce5887ef2171f9ec5b6e9eb73e/src/resources/v1/payouts/payouts.ts), [batch.ts](https://github.com/payOSHQ/payos-node/blob/cf970978e4d5f0ce5887ef2171f9ec5b6e9eb73e/src/resources/v1/payouts/batch/batch.ts), [payouts-account.ts](https://github.com/payOSHQ/payos-node/blob/cf970978e4d5f0ce5887ef2171f9ec5b6e9eb73e/src/resources/v1/payouts-account/payouts-account.ts).
- **[S4]** payOS, [Tạo kênh chuyển tiền](https://payos.vn/docs/huong-dan-su-dung/kenh-chi/tao-kenh-chuyen-tien/), [Markdown](https://payos.vn/docs/huong-dan-su-dung/kenh-chi/tao-kenh-chuyen-tien.md).
- **[S5]** payOS, [Nạp số dư ví Bảo Kim](https://payos.vn/docs/huong-dan-su-dung/kenh-chi/nap-so-du-vi/), [Markdown](https://payos.vn/docs/huong-dan-su-dung/kenh-chi/nap-so-du-vi.md).
- **[S6]** payOS, [Thanh toán gói dịch vụ chi](https://payos.vn/docs/huong-dan-su-dung/kenh-chi/thanh-toan-goi-dich-vu/), [Markdown](https://payos.vn/docs/huong-dan-su-dung/kenh-chi/thanh-toan-goi-dich-vu.md).
- **[S7]** payOS, [Kết nối ví Bảo Kim](https://payos.vn/docs/huong-dan-su-dung/kenh-chi/ket-noi-tai-khoan/vi-baokim/), [Markdown](https://payos.vn/docs/huong-dan-su-dung/kenh-chi/ket-noi-tai-khoan/vi-baokim.md).
- **[S8]** Casso, [CAS Pay Out](https://cas.so/product/pay-out), [Quickstart](https://cas.so/quickstart). Quy trình CAS, khóa CAS và môi trường riêng.
- **[S9]** Casso, [Transfer API index](https://cas.so/general/api/product-transfer). Tại lúc đọc index chỉ liệt kê tạo chuyển tiền và thông tin giao dịch đã tạo.
- **[S10]** payOSHQ, [payout-operations.ts](https://github.com/payOSHQ/payos-node/blob/cf970978e4d5f0ce5887ef2171f9ec5b6e9eb73e/examples/payout-operations.ts). Credentials payout riêng, ví dụ payload; không chạy ví dụ.
- **[S11]** payOS, [Ra mắt kênh chi](https://payos.vn/ra-mat-kenh-chi-tren-payos-giai-phap-chi-tu-dong-qua-api/), [trang sản phẩm chi hộ](https://payos.vn/chi-ho/). Mô tả chi tự động và hỗ trợ công khai.
- **[S12]** payOS, [Hướng dẫn tạo ví Bảo Kim](https://payos.vn/huong-dan-tao-tai-khoan-bao-kim-bao-kim-e-wallet/). Định danh, liên kết và khoản xác minh 10.000đ.
- **[S13]** Casso, [Bảng giá CAS](https://cas.so/pricing). Chỉ dùng để phân biệt môi trường test và điều kiện thương mại; không phải báo giá lookup.

Không kiểm tra live entitlement, tài khoản, tiền thật hoặc gửi yêu cầu hỗ trợ. Rủi ro còn lại: API lookup đang migration, quyền tạo lệnh/duyệt và semantics retry chưa được provider xác nhận cho kênh thực tế.
