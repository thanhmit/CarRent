# P4.2-A: contract lookup người nhận và vận hành payout

Ngày đối chiếu: **2026-11-08**. Phạm vi: tài liệu công khai chính thức payOS/Casso/VietQR và mã nguồn SDK do payOSHQ phát hành. Đã đọc hai báo cáo [lookup 08-11](payos-recipient-lookup-check-2026-11-08.md) và [prerequisites 07-11](payos-refund-live-prerequisites-2026-11-07.md), rồi GET lại nguồn bên dưới. Đây là báo cáo bổ sung, không thay thế bằng chứng entitlement hoặc giao dịch thật.

Không đọc credentials/cấu hình riêng/tài khoản/DB; không gọi API có xác thực, không lookup thật, không POST/tạo lệnh/chuyển tiền, không gửi yêu cầu hỗ trợ. Các ví dụ request/response được nêu là **ví dụ hoặc schema tài liệu**, không phải kết quả API đã chạy.

## Kết luận để quyết định

1. **Có contract lookup độc lập được công bố: VietQR `POST /v2/lookup`.** API nhận BIN và số tài khoản, trả `data.accountName`; mô tả là kiểm tra số tài khoản và trả tên, không phải tạo lệnh chi. Tuy nhiên trang vẫn thông báo đang migrate sang CAS Pay Out. Tài liệu công khai chưa xác nhận cấp quyền mới/cũ, mức giá hay tài khoản CarRent được dùng đường này. Vì vậy có thể chuẩn bị adapter theo contract này khi provider xác nhận áp dụng, nhưng **chưa đủ để bật verifier production hoặc đóng P4.2-A**. [S1]
2. **Không có nguồn tên người nhận trước create trong contract payOS đã đọc.** `estimate-credit` có `validateDestination` nhưng success chỉ chứa `estimateCredit`; SDK cũng chỉ khai báo trường đó. Tên trong create payout xuất hiện sau khi tạo lệnh. `balance.accountName` là tên nguồn chi. Không dùng ba dữ kiện này để thay lookup độc lập. [S2][S3][S4]
3. **CAS công khai thêm hướng dẫn recovery rõ, nhưng đó là API CAS `/transfer`.** CAS yêu cầu không phát lệnh lại khi state khác `SUCCESS`, giữ nguyên chờ làm rõ với ngân hàng và webhook. Điều này không tự trở thành contract retry của payOS `/v1/payouts`; không cần chuyển CarRent sang API chi CAS chỉ vì tài liệu này rõ hơn. [S12]
4. **Min/max, phí áp dụng, tự duyệt theo kênh và semantics retry payOS vẫn cần provider xác nhận.** Ví dụ 2.000đ/100.000đ/`estimateCredit=5000`, credit dùng thử 100K và OTP liên kết ví không chứng minh giới hạn hoặc hành vi payout thật. [S2][S5][S7][S8][S20]

## 1. Lookup độc lập: contract nào thực sự có thể dùng

| Thành phần | Contract chính thức đã đọc | Kết luận cho triển khai |
| --- | --- | --- |
| VietQR lookup | `POST https://api.vietqr.io/v2/lookup`; header `x-client-id`, `x-api-key`; nguồn lấy khóa được chỉ dẫn là `https://my.vietqr.io`. Body có `bin` integer và `accountNumber` string. Thành công HTTP 200, `code="00"`, `data.accountName` string. | Có đủ hình dạng request/response cơ bản để thiết kế adapter. Chưa chứng minh entitlement, tính khả dụng production trong migration hoặc giá áp dụng. Không dùng khóa payout payOS thay cho khóa VietQR khi chưa được xác nhận. [S1] |
| VietQR lỗi | HTTP 200 có các mã `42` số tài khoản không hợp lệ; `41` ngân hàng chưa hỗ trợ; `43` lỗi chưa xác định; `45` các provider bận; `10` thiếu tham số; `22` BIN không hợp lệ; `21` số tài khoản không hợp lệ. HTTP 429 cho vượt rate limit. | Thành công phải kiểm tra code và tên có nội dung; không chỉ kiểm tra HTTP 200. Giới hạn định lượng, retry-after, timeout/SLA và danh sách ngân hàng lookup được hỗ trợ chưa được xác lập ở trang này. [S1] |
| VietQR đầu vào | Bảng body nói tài khoản chỉ số, dài 6–19 ký tự; ví dụ lỗi `21` lại nói số hoặc chữ/alias/nickname, dài 4–25 ký tự. | Đây là bất nhất tài liệu. Cần provider chốt quy tắc áp dụng; không tự mở alias theo một ví dụ lỗi. Giữ số tài khoản dạng chuỗi để không mất số 0 đầu. [S1] |
| VietQR liên kết kết quả | Success chỉ trả tên, không echo BIN/số tài khoản. Demo tương tác ghi rõ dùng dữ liệu mẫu, không lookup thật. | Backend phải gắn kết quả với chính request BIN/STK đang xử lý. Demo không phải bằng chứng nhận diện ngân hàng thật hoặc entitlement. [S1] |
| payOS `POST /v1/payouts/estimate-credit` | Body tài liệu có `referenceId`, `category`, `validateDestination`, `payouts`. Success mẫu là `{"code":"00","desc":"success","data":{"estimateCredit":5000}}`; SDK `EstimateCredit` chỉ có `estimateCredit: number`. | Không có `accountName`, `toAccountName` hoặc kết quả tên từng đích trong response đã công bố. Cờ `validateDestination` không đủ cho khách xem/xác nhận tên trước chi. Không kết luận provider không có API thương mại khác; yêu cầu contract nếu họ đề nghị dùng endpoint này. [S2][S3] |
| payOS create/balance | Create single/batch có `transactions[].toAccountName`; balance trả tên/số tài khoản nguồn chi, không nhận BIN/STK người nhận. SDK cho phép `toAccountName` là `null`. | Create là thao tác tạo lệnh chi, không dùng để dò tên. Balance không phải lookup người nhận. [S2][S3][S4] |
| CAS Transfer | Index ghi rõ hai API: `POST /transfer` và `GET /transfer/{id}`. Request chuyển tiền có amount, mô tả, nguồn và đích; response schema đã đọc không có tên người nhận. GET cần ID giao dịch đã tạo. | Không endpoint nào trong hai API này là lookup tên độc lập trước chuyển. [S11][S12][S13] |
| CAS Identity | `GET /identity` trả `accounts[].accountName`, owner/company cho tài khoản được cấp quyền; nhóm Open API yêu cầu grant của người dùng cuối. | Không phải lookup tài khoản bất kỳ bằng BIN/STK. Không yêu cầu khách cấp quyền ngân hàng chỉ để lấp khoảng trống contract hiện tại. [S14][S15] |

CAS `llms.txt`, sitemap, API index, Pay Out, Transfer và Identity được rà để tìm đường thay thế. Các trang này chưa công bố contract lookup người nhận độc lập thay `/v2/lookup`. Đây là kết luận trong phạm vi nguồn đã đọc, **không phải bằng chứng API đó không tồn tại**. Thông báo VietQR dùng câu CAS Pay Out **sẽ bao gồm** tra cứu STK và chuyển tiền; bản thân thông báo chưa nêu endpoint lookup CAS thay thế. [S1][S9][S10][S11][S14][S15][S17][S18]

## 2. Cấp quyền và điều kiện theo tài khoản

| Dịch vụ | Quy trình công khai | Phần tài khoản cụ thể chưa xác minh |
| --- | --- | --- |
| VietQR lookup | Lấy Client ID/API Key tại `my.vietqr.io`; có thông báo migration CAS. | Tài khoản mới/cũ nào còn được kích hoạt `/v2/lookup`; lookup có bán riêng không; giá; hạn mức; production/sandbox; quyền có dùng với quan hệ payOS đang có không. Không suy luận chỉ cần đăng ký là được cấp. [S1] |
| payOS payout | Tổ chức xác thực, liên kết nguồn, vào **Kênh chuyển tiền → Thêm kênh chuyển tiền**, chọn ví/ngân hàng; có bước **Quản lý IP cho kênh → Thêm IP**, hỗ trợ nhiều IP. SDK nhấn mạnh credentials payout khác credentials tạo payment request. | Kênh cụ thể đã được active quyền POST chưa; IP egress backend đã đúng chưa; allowlist áp dụng khi chưa khai báo ra sao; giới hạn và cơ chế duyệt. GET balance/list thành công không chứng minh quyền create. [S5][S6] |
| Bảo Kim qua payOS | Ví liên kết phải trùng tên tổ chức đã xác thực; chọn đúng cá nhân/doanh nghiệp; hộ kinh doanh chọn cá nhân theo hướng dẫn. Liên kết qua CAS, có OTP gửi số điện thoại đăng ký ví. Ví doanh nghiệp có thể cần 1–2 ngày để đối tác xác minh sau liên kết. | OTP này thuộc onboarding. Không suy ra payout nào cũng cần OTP hoặc không payout nào cần OTP. Chưa xác nhận tự duyệt, ngưỡng duyệt tay/PIN/OTP hoặc quyền người duyệt trên kênh thực tế. [S7] |
| CAS | Quickstart chỉ dẫn đăng ký Console để lấy `x-client-id`, `x-secret-key`; Sandbox `https://sandbox.bankhub.dev`, Production `https://production.bankhub.dev`, bắt đầu bằng Sandbox. Pay Out tạo grant scope `transfer`, qua CAS Link, exchange public token lấy access token. | Đây là onboarding/quyền chuyển tiền, chưa chứng minh entitlement lookup độc lập. Bộ khóa CAS không được mặc định đồng nhất với payOS/VietQR. [S9][S10] |
| CAS dịch vụ được cấp | `GET /fi-services` được mô tả là danh sách dịch vụ tài chính **được cấp cho ứng dụng**, có mã, loại và BIN. | Tài liệu có đường kiểm tra dịch vụ được cấp, nhưng chưa gọi; danh sách dịch vụ nguồn được cấp cũng không tự chứng minh mọi ngân hàng đích đều lookup được. [S16] |

Trang sản phẩm payOS quảng bá chuyển tiền tự động qua API; tài liệu nguồn ví nói trích tiền khi lệnh được khởi tạo **và phê duyệt**. Hai câu này không xác định chính sách auto-approval theo kênh. SDK có riêng `approvalState` và transaction `state`, vì vậy không dùng một approval state để kết luận người nhận đã nhận tiền. [S3][S8][S19]

## 3. Số tiền, phí và nguồn tiền

| Chủ đề | Đã xác lập | Chưa xác lập |
| --- | --- | --- |
| Principal hoàn tiền | Nạp **số dư ví Bảo Kim** vào tài khoản nguồn chi; khi lệnh khởi tạo và phê duyệt, số tiền chi được trích từ ví. Tài liệu nói cộng số dư có thể mất 2–5 phút, có đồng bộ thủ công. [S8] | Số dư hiện có, ngưỡng duy trì, hạn mức nạp/rút và phí nguồn của tài khoản cụ thể. |
| Credit/gói payOS | Mua ở **Thiết lập → Sử dụng → Chi → Nạp tiền**; gói dựa trên lượng giao dịch. Tài liệu nói credit 100K dùng thử lúc bắt đầu sử dụng kênh Chi. [S20] | Đơn giá, VAT, thời hạn, tính phí lookup/lỗi/retry và việc hoàn credit. Credit dịch vụ không phải tiền gốc trong ví. |
| Ước tính phí | `estimate-credit` nhận request lệnh chi và trả `estimateCredit`. [S2][S3] | `5000` trong ví dụ không phải bảng phí, payout tối thiểu hoặc cam kết bao gồm mọi phí Bảo Kim/ngân hàng. |
| Min/max payout | Bảng payout API chỉ mô tả `amount` integer; SDK dùng number, không nêu min/max. Ví dụ docs dùng 100.000đ, ví dụ SDK dùng 2.000đ. [S2][S3][S5] | Min/max mỗi lệnh, hạn mức ngày, quy tắc theo ngân hàng/nguồn và mức tiền pilot. Không lấy ví dụ làm hạn mức. |
| Giá CAS | Trang giá phân biệt Free cho tài khoản test với Vendor cần đáp ứng tiêu chuẩn, từ 500 USD/tháng. [S21] | Không phải báo giá riêng lookup production; không suy ra mọi khách lookup phải mua Vendor, hoặc mọi lookup đều miễn phí. |

## 4. payOS idempotency và recovery

**Contract xác nhận được:** create single/batch yêu cầu `x-idempotency-key` và `x-signature`, ngoài `x-client-id`/`x-api-key`. SDK single-create giải thích dùng UUID/chuỗi entropy cao để retry cùng request được nhận ra và tránh payout trùng; single/batch mặc định sinh UUID mới nếu caller bỏ qua. List hỗ trợ `referenceId`, limit/offset và pagination; detail dùng payout ID. SDK xác minh chữ ký response ở header cho create/list/detail/balance. [S2][S3][S4]

**SDK không thay thế cam kết vận hành:** chưa tìm được trong các nguồn đã đọc thời gian giữ key, scope key, cùng key khác body, unique constraint của `referenceId`, độ trễ đọc sau create, hay guarantee list rỗng đồng nghĩa chắc chắn chưa chi. Tài liệu nêu 401/403/429/500 nhưng không xác nhận tập mã nào an toàn để tạo lại sau khi mất response. `NOT_SENT` không có trong enum SDK đã đọc và chưa có contract công khai được xác lập cho khái niệm này. [S2][S3]

Các transaction state SDK là `RECEIVED`, `PROCESSING`, `CANCELLED`, `SUCCEEDED`, `ON_HOLD`, `REVERSED`, `FAILED`. Approval state là tập khác: `DRAFTING`, `SUBMITTED`, `APPROVED`, `REJECTED`, `CANCELLED`, `SCHEDULED`, `PROCESSING`, `FAILED`, `PARTIAL_COMPLETED`, `COMPLETED`. Ví dụ list docs lại dùng `approvalState=SUCCEEDED`; đây là bất nhất docs/SDK, không phải căn cứ để bỏ qua transaction state. [S2][S3]

**Lựa chọn ứng dụng có thể thực hiện mà không bịa contract:** giữ key/reference bền vững cho một payout logic; đối chiếu amount/BIN/STK/reference và chữ ký trước khi ghi thành công; mất response, signature sai, trạng thái lạ, nhiều kết quả hoặc không thấy reference thì giữ cần đối soát, không cấp key/reference mới để gửi lại. Đây là yêu cầu an toàn đề xuất cho CarRent, không phải tuyên bố provider bảo đảm exactly-once. Chưa xác nhận semantics principal/fee khi `FAILED`, `CANCELLED`, `REVERSED`, vì vậy không tự coi những chuỗi này là phép chi lại. [S2][S3]

### Bằng chứng bổ sung CAS, không áp sang payOS

CAS `POST /transfer` yêu cầu `x-interaction-id` UUID v4. Schema create có `SUCCESS`, `PENDING`, `TIMEOUT`, `ERROR` và ghi rõ với trạng thái **khác `SUCCESS`**: giữ nguyên trạng thái, tuyệt đối không tự phát lệnh lại; CAS chưa xác định kết quả cuối, sẽ phối hợp ngân hàng và thông báo cuối qua webhook. `GET /transfer/{id}` lại có enum `SUCCESS`, `FAILED`, `PENDING`, `ERROR`. Các enum khác nhau cho thấy cần đọc contract từng endpoint; không suy ra CAS `ERROR` hay payOS `FAILED` là chắc chắn chưa chuyển. Header interaction ID cũng chưa tự chứng minh TTL hay replay semantics idempotency. [S12][S13]

## 5. Phần đủ căn cứ để làm và phần còn chờ

| Việc | Trạng thái từ nghiên cứu này | Điều kiện hoàn thành |
| --- | --- | --- |
| Viết adapter VietQR `/v2/lookup` cho contract được xác nhận | **Có schema cơ bản để chuẩn bị, chưa đủ bật production**. Không cần viết adapter dự đoán cho CAS chưa có endpoint. [S1] | Provider xác nhận endpoint còn hỗ trợ cho tài khoản, entitlement, quy tắc đầu vào đang áp dụng và điều kiện thương mại. Adapter/test không thay thế bước xác nhận này. |
| Cơ chế gắn tên lookup với BIN/STK và khách xác nhận | **Có thể đặc tả/kiểm thử offline**, là quy tắc ứng dụng. | Tên do backend nhận từ verifier; tên trống/lỗi/mismatch/unsupported/timeout đều chặn; thay BIN/STK phải lookup/xác nhận lại. Chưa triển khai hoặc kiểm thử trong báo cáo này. |
| Hard-code minimum/fee hay cam kết tự duyệt | **Chưa đủ căn cứ**. [S2][S7][S20] | Bảng giới hạn/phí, auto-approval/OTP/cut-off theo đúng kênh và tài khoản được provider xác nhận. |
| Retry tự động khi timeout/list rỗng/FAILED | **Chưa đủ contract để mở**. [S2][S3] | TTL/scope/key-body policy, eventual consistency và trạng thái bảo đảm chưa gửi/đã hoàn tiền do payOS xác nhận; runbook recovery/đối soát. |
| Bật kênh/refund live, pilot thật | **Chưa đủ bằng chứng từ nghiên cứu tài liệu**. | Entitlement lookup và payout, nguồn tiền/credit, mapping Owner/Admin thực tế, IP egress, verifier đã kiểm thử, contract recovery và pilot theo runbook được nghiệm thu. Báo cáo không kiểm tra những mục này. |

Hành động kế tiếp ngắn nhất là lấy trả lời chính thức cho **đường lookup** trước, song song xin contract payout theo kênh. Có thể dùng bản nháp yêu cầu hỗ trợ trong [báo cáo 07-11](payos-refund-live-prerequisites-2026-11-07.md); lần này **chưa gửi**. Cần chốt: VietQR cũ còn được provision hay endpoint CAS mới; endpoint/environment/auth/scope trả tên mà không tạo lệnh; giá/rate limit/ngân hàng; và riêng payOS min/max/phí/auto-approval/idempotency/recovery. Không cần đưa secrets, OTP/PIN hoặc thông tin đăng nhập vào chat hay báo cáo.

## Nguồn chính thức đã đối chiếu trực tiếp

Các nguồn dưới được GET công khai trong lần nghiên cứu ngày 08-11, HTTP 200 (CAS `/pricing` redirect sang `/pricing/`). SDK được pin theo HEAD `cf970978e4d5f0ce5887ef2171f9ec5b6e9eb73e` lấy lại từ GitHub API; không chạy ví dụ SDK.

- **[S1]** VietQR/Casso, [Tra cứu STK Markdown](https://www.vietqr.io/tra-cuu-stk.md), [HTML](https://www.vietqr.io/tra-cuu-stk): lookup, errors, migration, demo.
- **[S2]** payOS, [API Markdown](https://payos.vn/docs/api.md), [HTML](https://payos.vn/docs/api/), mục [single](https://payos.vn/docs/api/#tag/payout/operation/create-single-payout), [batch](https://payos.vn/docs/api/#tag/payout/operation/create-batch-payout), [estimate-credit](https://payos.vn/docs/api/#tag/payout/operation/estimate-credit), [list](https://payos.vn/docs/api/#tag/payout/operation/get-payouts), [detail](https://payos.vn/docs/api/#tag/payout/operation/get-payout-info), [balance](https://payos.vn/docs/api/#tag/payout-account/operation/get-account-balance).
- **[S3]** payOSHQ, [payouts.ts](https://github.com/payOSHQ/payos-node/blob/cf970978e4d5f0ce5887ef2171f9ec5b6e9eb73e/src/resources/v1/payouts/payouts.ts), [batch.ts](https://github.com/payOSHQ/payos-node/blob/cf970978e4d5f0ce5887ef2171f9ec5b6e9eb73e/src/resources/v1/payouts/batch/batch.ts): idempotency, types, signatures, states.
- **[S4]** payOSHQ, [payouts-account.ts](https://github.com/payOSHQ/payos-node/blob/cf970978e4d5f0ce5887ef2171f9ec5b6e9eb73e/src/resources/v1/payouts-account/payouts-account.ts): account nguồn, balance, chữ ký.
- **[S5]** payOSHQ, [payout-operations.ts](https://github.com/payOSHQ/payos-node/blob/cf970978e4d5f0ce5887ef2171f9ec5b6e9eb73e/examples/payout-operations.ts): credentials payout riêng, ví dụ amount.
- **[S6]** payOS, [Tạo kênh chuyển tiền](https://payos.vn/docs/huong-dan-su-dung/kenh-chi/tao-kenh-chuyen-tien.md): onboarding, nguồn, IP.
- **[S7]** payOS, [Kết nối ví Bảo Kim](https://payos.vn/docs/huong-dan-su-dung/kenh-chi/ket-noi-tai-khoan/vi-baokim.md): tên, loại ví, OTP liên kết, xác minh doanh nghiệp.
- **[S8]** payOS, [Nạp số dư ví](https://payos.vn/docs/huong-dan-su-dung/kenh-chi/nap-so-du-vi.md): tiền gốc và trích tiền sau khởi tạo/phê duyệt.
- **[S9]** CAS, [Quickstart](https://cas.so/quickstart.md): Console, khóa, environments, grant flow.
- **[S10]** CAS, [Pay Out](https://cas.so/product/pay-out.md): scope transfer, grant/link/exchange và transfer.
- **[S11]** CAS, [Transfer index](https://cas.so/general/api/product-transfer.md): hai API được liệt kê.
- **[S12]** CAS, [POST Transfer](https://cas.so/general/api/product-transfer/transfer.md): request, state, cảnh báo không phát lệnh lại.
- **[S13]** CAS, [Transfer Information](https://cas.so/general/api/product-transfer/transfer-info.md): GET theo giao dịch đã tạo, enum riêng.
- **[S14]** CAS, [Identity](https://cas.so/general/api/product-identity/get-auth.md): thông tin tài khoản/chủ sở hữu có grant.
- **[S15]** CAS, [API index](https://cas.so/general/api.md): Open API cần grant, các nhóm Public API.
- **[S16]** CAS, [Fi services](https://cas.so/general/api/institutions/get-fi-services.md): dịch vụ được cấp theo ứng dụng.
- **[S17]** CAS, [llms.txt](https://cas.so/llms.txt): index tài liệu Markdown.
- **[S18]** CAS, [sitemap](https://cas.so/sitemap.xml): phạm vi các trang tìm đường lookup.
- **[S19]** payOS, [Ra mắt kênh chi](https://payos.vn/ra-mat-kenh-chi-tren-payos-giai-phap-chi-tu-dong-qua-api/), [Sản phẩm chi hộ](https://payos.vn/chi-ho/): mức mô tả sản phẩm tự động qua API.
- **[S20]** payOS, [Gói dịch vụ chi](https://payos.vn/docs/huong-dan-su-dung/kenh-chi/thanh-toan-goi-dich-vu.md): credit/gói dịch vụ.
- **[S21]** CAS, [Bảng giá](https://cas.so/pricing/): Free test và Vendor; không phải báo giá lookup.

Không kiểm tra tài khoản, entitlement live, lookup thật hoặc giao dịch thật; không sửa mã ứng dụng. Rủi ro còn lại: lookup đang migration và các điều kiện payout theo kênh chưa được provider xác nhận.
