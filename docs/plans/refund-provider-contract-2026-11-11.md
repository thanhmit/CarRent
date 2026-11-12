# Hoàn tiền: đối chiếu contract provider công khai

Ngày kiểm tra: **2026-11-11**. Đã đọc [báo cáo lookup 08-11](payos-recipient-lookup-check-2026-11-08.md), rồi GET lại các nguồn chính thức ở cuối bài (đều HTTP 200). Phạm vi chỉ là tài liệu và mã frontend công khai; không đọc secret/cấu hình riêng, không truy cập tài khoản, không gọi API xác thực/lookup/chuyển tiền và không gửi liên hệ bên ngoài. Mã JavaScript chỉ được đọc như văn bản, không thực thi.

## Kết luận có thể dùng ngay

**Chưa có bằng chứng công khai rằng khóa payout payOS hiện có dùng được cho một API lookup tên người nhận độc lập trước khi tạo lệnh chi.** API payOS công bố không có endpoint như vậy; VietQR có contract lookup rõ, nhưng chỉ dẫn lấy khóa tại `my.vietqr.io` và vẫn thông báo migrate CAS. CAS dùng bộ khóa và grant riêng; các trang đã đọc chưa cung cấp contract thay thế lookup theo BIN/STK. Không suy ra dịch vụ thương mại đó không tồn tại; entitlement của CarRent cần provider xác nhận. [S1–S5]

Vì vậy chưa đủ căn cứ đóng gate xác minh tên bằng cách gọi `estimate-credit`, balance hoặc tạo thử payout. Các điều kiện tiền nguồn/credit dịch vụ và OTP/hạn mức cũng phải được xác nhận riêng cho kênh thực tế. [S1][S6–S10]

## 1. Lookup độc lập và những endpoint dễ nhầm

| API | Contract được công bố | Hệ quả cho CarRent |
| --- | --- | --- |
| VietQR `POST https://api.vietqr.io/v2/lookup` | Header bắt buộc `x-client-id`, `x-api-key`, lấy tại `https://my.vietqr.io`. JSON bắt buộc `bin` (integer), `accountNumber` (string). Thành công `code="00"`, `data.accountName`. | Là tra cứu tính hợp lệ và tên tài khoản; không phải tạo chuyển tiền. Chưa có xác nhận tái dùng payOS payout keys hoặc cấp quyền mới/cũ khi migrate. [S2] |
| payOS `POST /v1/payouts/estimate-credit` | Auth `x-client-id`, `x-api-key`; header bắt buộc `x-signature`. Body bắt buộc `referenceId`, `payouts`; tùy chọn `category`, `validateDestination`. Phần tử mẫu có `referenceId`, `amount`, `description`, `toBin`, `toAccountNumber`. Response thành công mẫu chỉ có `data.estimateCredit` cùng `code`, `desc`. | `validateDestination=true` không chứng minh có tên chuẩn trong response. Không đủ làm verifier tên trước xác nhận. `estimateCredit:5000` là giá trị mẫu ước tính phí, không phải minimum payout hay biểu phí cố định. [S1] |
| payOS `GET /v1/payouts-account/balance` | Auth `x-client-id`, `x-api-key`. Response `data.accountNumber`, `accountName`, `currency`, `balance` của tài khoản chi; mẫu `balance` là string. | Không nhận BIN/STK đích; tên nguồn không phải tên người nhận. Không có trường số dư credit dịch vụ riêng trong response mẫu. [S1] |
| payOS `POST /v1/payouts` và `/v1/payouts/batch` | Response mẫu có `transactions[].toAccountName`; Markdown hiển thị phần tử mẫu bằng khóa `"0"`. GET danh sách/chi tiết cũng trả thông tin giao dịch đã tạo. | Tên xuất hiện gắn với lệnh chi đã tạo. Không tạo payout để lấy tên trước khách xác nhận. [S1] |

VietQR còn công bố HTTP 200 có mã lỗi nghiệp vụ `42` (STK không hợp lệ), `41` (ngân hàng chưa hỗ trợ), `43` (lỗi không xác định), `45` (provider bận), `10` (thiếu tham số), `22` (BIN sai), `21` (STK sai), cùng HTTP 429. Response thành công không echo BIN/STK: cần gắn kết kết quả với đúng request. Bảng body ghi STK chỉ số, dài 6–19; ví dụ lỗi `21` lại mô tả alias số/chữ dài 4–25. Cần provider chốt quy tắc hiện hành; giữ STK dạng string để không mất số 0 đầu. Demo trang được ghi rõ dùng dữ liệu mẫu, không gọi lookup thật. Trang không công bố giá/rate limit định lượng, danh sách ngân hàng lookup được hỗ trợ hay SLA. [S2]

## 2. CAS chưa chứng minh có thể dùng khóa payOS cho lookup

- VietQR nói bộ CAS Pay Out **sẽ bao gồm** API tra cứu STK và chuyển tiền trực tiếp từ tài khoản doanh nghiệp; banner không kèm endpoint/schema lookup thay thế. [S2]
- CAS Quickstart yêu cầu đăng ký Console để có `x-client-id` và `x-secret-key`; môi trường `https://sandbox.bankhub.dev` và `https://production.bankhub.dev`, bắt đầu bằng Sandbox. Mẫu gọi API thêm `X-BankHub-Api-Version: 2023-01-01`; các API cần grant dùng `Authorization: <accessToken>`. Đây không phải hướng dẫn dùng `x-api-key` payout payOS. [S3]
- Pay Out hướng dẫn scope `transfer`: `/grant/token` → Cas Link để người dùng xác thực → đổi `publicToken` qua `/grant/exchange` lấy `accessToken` → gọi `/transfer`. Index Transfer công bố hai API: tạo chuyển tiền và truy vấn giao dịch đã tạo; chưa thấy lookup BIN/STK độc lập trong các trang này. [S4][S5]

Đây là kết luận giới hạn theo các trang đã đọc, không phải rà soát toàn bộ sản phẩm CAS hay xác minh entitlement tài khoản. Chỉ biết hai dịch vụ cùng thuộc hệ sinh thái Casso không đủ suy ra khóa dùng chung. [S2–S5]

## 3. Điều kiện payout, credit, số dư, minimum và OTP

| Điều kiện | Bằng chứng công khai mới đối chiếu | Điều chưa được chứng minh |
| --- | --- | --- |
| Kích hoạt/quyền/IP | Guide yêu cầu kích hoạt Kênh chi tại Thiết lập → Hồ sơ → Thay đổi dịch vụ; tạo kênh gắn ví/ngân hàng và thêm IP. API create có 401/403/429 và 500. [S1][S6][S7] | GET thành công không chứng minh quyền POST; chưa biết kênh CarRent đã activate, IP egress và hạn mức đúng hay chưa. |
| Tiền chuyển | Guide Bảo Kim yêu cầu nạp tiền vào nguồn chi; khi lệnh được khởi tạo và phê duyệt, tiền trích từ ví. Sau nạp có thể đợi 2–5 phút để cộng số dư, có thao tác đồng bộ. [S8] | Không xác minh số dư hoặc khả dụng của ví thực tế; không suy ra tiền nhận ở tài khoản khác tự sang nguồn chi. |
| Credit dịch vụ | Guide gói chi có các mức giá theo số giao dịch, nói tài khoản bắt đầu dùng kênh chi có **100K credit dùng thử**. Guide này tách khỏi nạp số dư ví nguồn. [S8][S9] | Không dùng credit thử làm tiền gốc chuyển. Chưa biết credit hiện có, giá/đơn vị trừ credit, phí/VAT hoặc phản hồi khi hết credit của kênh thực tế. |
| Minimum/maximum | API ghi `amount` integer; mẫu amount là 100.000, mẫu estimateCredit là 5.000. Các guide đã đọc không đưa min/max payout bằng số. [S1][S6–S9] | **Chưa chứng minh chuyển được khoản hoàn 5.000 VND**, hay mức tối đa/ngày, phí/cut-off áp dụng. Giá trị mẫu không phải hạn mức. |
| OTP/duyệt | Liên kết ví Bảo Kim qua CAS cần OTP gửi SĐT đăng ký; ví phải trùng tên tổ chức, chọn loại tài khoản tương ứng. Tài khoản doanh nghiệp được hướng dẫn chờ 1–2 ngày xác thực sau liên kết. [S10] | OTP liên kết không chứng minh mọi payout đều miễn OTP/PIN/duyệt tay. API create không có trường OTP trong schema công bố; response mẫu `approvalState="PROCESSING"` không phải cam kết tự duyệt mọi hạn mức. [S1][S10] |
| Môi trường thử | payOS nói không có sandbox/staging riêng; trang mô tả thử luồng thanh toán trên production. CAS có Sandbox riêng. [S3][S11] | Không coi CAS Sandbox là sandbox của payOS payout; không coi API create payOS là phép thử không chuyển tiền. |

Create payout đơn yêu cầu header `x-idempotency-key`, `x-signature`, cùng auth `x-client-id`, `x-api-key`; body bắt buộc `referenceId`, `amount`, `description`, `toBin`, `toAccountNumber`, tùy chọn `category`. Batch thêm `payouts` bắt buộc và `validateDestination` tùy chọn. Markdown API không giải thích đầy đủ thời gian giữ idempotency key, guarantee khi timeout hoặc mã riêng chứng minh thiếu tiền/chưa gửi. Vì thế chưa có căn cứ từ các trang này để map lỗi credit/thiếu nguồn thành `NOT_SENT` rồi tự gửi lại. [S1]

## 4. Đầu vào cần provider xác nhận trước khi triển khai/cutover

1. Một contract lookup không tạo/chuyển tiền: endpoint/environment, headers/scope và bộ khóa nào được cấp, BIN/STK đầu vào, tên chuẩn đầu ra, lỗi/timeout/rate limit, ngân hàng hỗ trợ và giá. Nếu dùng VietQR, xác nhận tình trạng endpoint trong migration; nếu dùng CAS, cần contract lookup cụ thể. Nếu đề nghị `estimate-credit`, yêu cầu schema tên được hỗ trợ chính thức. [S1–S5]
2. Theo từng kênh/chủ thể: quyền tạo payout, activation/IP egress, nguồn tiền và credit khả dụng, phí/VAT, min/max/ngày (bao gồm khoản 5.000 VND), cơ chế duyệt/OTP/PIN và hạn mức áp dụng. [S1][S6–S10]
3. Contract recovery: semantics idempotency/replay, thời gian giữ key, cách query khi mất response, trạng thái/mã lỗi bảo đảm chưa chuyển. Khi chưa có guarantee, giữ đối soát/query thay vì suy luận gửi lại là an toàn. [S1]

Danh sách này là yêu cầu bằng chứng; chưa gửi hỏi provider và chưa xác nhận bất cứ điều kiện tài khoản thực tế nào.

## 5. Bổ sung: có nên bật “Tính năng thử nghiệm” trên payOS?

**Không bật chỉ vì muốn thử hoàn tiền hoặc nghĩ rằng công tắc này tạo sandbox/tiền ảo.** Tài liệu payOS xác nhận không có sandbox riêng; bản tài liệu đầy đủ không mô tả công tắc này. Tuy nhiên, đã tìm được đúng nhãn và mô tả trong bundle frontend chính chủ được tải công khai, không cần đăng nhập. [S11–S13]

Trong màn Hồ sơ, tooltip của **“Tính năng thử nghiệm”** ghi: “Các tính năng đang được phát triển và thử nghiệm trước khi được sử dụng rộng rãi, có thể chưa ổn định trong quá trình sử dụng”. Dialog bật ghi “Các tính năng thử nghiệm có thể chưa hoạt động ổn định”, cảnh báo có thể ảnh hưởng trải nghiệm và “Chỉ nên sử dụng cho mục đích thử nghiệm”. Dialog tắt ghi: “Bạn sẽ quay về phiên bản ổn định và không còn truy cập được các tính năng thử nghiệm.” [S13]

Mã frontend cho thấy xác nhận bật/tắt đổi vai trò tính năng của **tổ chức** giữa `EXPERIMENT_USER` và `MANUAL_USER`, rồi tải lại trang; thao tác này tách khỏi lựa chọn dịch vụ Thu/Chi. Đây là bằng chứng về đăng ký dùng tính năng sớm, không phải môi trường tiền giả. Không gọi endpoint đổi vai trò nào trong lần nghiên cứu này. [S13]

**Khuyến nghị:** giữ chế độ ổn định để kiểm thử contract payout hiện có. Chỉ cân nhắc bật khi cần một tính năng cụ thể đang được payOS phát hành thử và đã đọc dialog của chính tài khoản đó. Công tắc không cung cấp bằng chứng quyền lookup, đủ số dư/credit, tự duyệt, hoặc tạo payout không chuyển tiền. Chưa có danh sách chính thức đầy đủ tính năng được mở hay bảo đảm server-side sau công tắc; nếu mô tả người dùng thấy khác nội dung trên, cần đối chiếu lại mô tả đó trước khi áp dụng kết luận. [S1][S11][S13]

## 6. Bổ sung: lấy khóa VietQR ở đâu, tài khoản mới có dùng lookup được không?

Điểm bắt đầu chính thức vẫn là **`https://my.vietqr.io`** theo tài liệu lookup. Mã frontend public của Portal hiện ánh xạ trang **`https://my.vietqr.io/profile`** đến màn tài khoản, có mục **“API Key”**, hai dòng **“Client ID”** và **“API Key”** với nút hiện/ẩn và sao chép. Cùng màn có **“Gói hiện tại và sử dụng API lookup”**, số đã dùng/còn lại và thời gian đặt lại. Người dùng đăng nhập tài khoản VietQR của mình rồi mở trang profile để tự lấy khóa; không gửi khóa vào chat. Đây là vị trí suy ra từ mã nguồn chính chủ hiện hành, chưa quan sát UI tài khoản thật. [S2][S14]

**Chưa xác nhận tài khoản mới được tự động cấp entitlement lookup trong migration CAS.** Thông báo cập nhật 14/10/2026 nói chỉ `/v2/generate` ngừng hỗ trợ từ 01/01/2027, Portal và các API khác vẫn hoạt động; không nói ngừng toàn bộ lookup. Nhưng thông báo này cũng không cam kết onboarding/gói/quota lookup cho tài khoản mới. Có trường khóa trên profile không tự chứng minh API còn được cấp quyền cho merchant cụ thể. Không tự dùng khóa payOS thay cho khóa VietQR. [S2][S14][S15]

Một API đọc công khai có ích là **`GET https://api.vietqr.io/v2/banks`**: tài liệu công bố `lookupSupported` bằng 0/1 theo ngân hàng, không cần client ID/API key và khuyên làm mới danh sách theo ngày. Nó chỉ cho biết khả năng hỗ trợ lookup của ngân hàng, **không trả tên chủ STK**, không thay `/v2/lookup`. Thông tin “không cần key” của `getBanks()`/thư viện QR mới cũng không miễn xác thực lookup. SLA/rate limit ghi ở trang banks áp dụng cho API banks, không được chuyển sang contract lookup. [S16]

Ngoài `/v2/lookup` đã có schema, các nguồn chính thức đã đọc chưa cung cấp một endpoint lookup tên đích không tạo chuyển tiền khác có thể dùng ngay với payOS payout keys. CAS grant/identity là luồng tài khoản đã liên kết, chưa phải bằng chứng lookup người nhận bất kỳ theo BIN/STK. [S1–S5]

## Nguồn chính thức đã GET lại

- **[S1]** payOS [API reference](https://payos.vn/docs/api.md): create/query payout, estimate-credit, balance, auth/headers/schema/response.
- **[S2]** VietQR [API tra cứu STK](https://www.vietqr.io/tra-cuu-stk.md): lookup, khóa, lỗi, migration và demo.
- **[S3]** CAS [Quickstart](https://cas.so/quickstart/): khóa, môi trường, grant và access token.
- **[S4]** CAS [Pay Out](https://cas.so/product/pay-out/): scope transfer và onboarding.
- **[S5]** CAS [Transfer index](https://cas.so/general/api/product-transfer/): hai API create/query giao dịch.
- **[S6]** payOS [Đăng ký dịch vụ chi hộ](https://payos.vn/dang-ky-dich-vu-chi-ho/): activation, nguồn ví, cấu hình kênh và hạn mức.
- **[S7]** payOS [Tạo kênh chuyển tiền](https://payos.vn/docs/huong-dan-su-dung/kenh-chi/tao-kenh-chuyen-tien.md): chọn nguồn và thêm IP.
- **[S8]** payOS [Nạp số dư ví](https://payos.vn/docs/huong-dan-su-dung/kenh-chi/nap-so-du-vi.md): nguồn tiền chuyển và độ trễ cộng ví.
- **[S9]** payOS [Gói dịch vụ chi](https://payos.vn/docs/huong-dan-su-dung/kenh-chi/thanh-toan-goi-dich-vu.md): credit dùng thử và gói giao dịch.
- **[S10]** payOS [Liên kết Bảo Kim](https://payos.vn/docs/huong-dan-su-dung/kenh-chi/ket-noi-tai-khoan/vi-baokim.md): định danh, OTP liên kết, chờ xác thực doanh nghiệp.
- **[S11]** payOS [Môi trường test](https://payos.vn/docs/moi-truong-test.md): production và không có sandbox riêng.
- **[S12]** payOS [LLM index](https://payos.vn/docs/llms.txt), [toàn bộ tài liệu Markdown](https://payos.vn/docs/llms-full.txt), [sitemap](https://payos.vn/docs/sitemap.xml): kiểm tra chỉ mục/nội dung công khai; không có mô tả đúng nhãn công tắc trong bản đầy đủ đã đọc.
- **[S13]** payOS [bundle màn Profile công khai](https://my.payos.vn/assets/index-Cld8VKNa.js), được dẫn bởi [entry bundle](https://my.payos.vn/assets/index-C4lilPT_.js) của [website chính thức](https://my.payos.vn/): đúng tooltip/dialog “Tính năng thử nghiệm”, vai trò tổ chức và reload. Tên file có hash có thể đổi khi deploy; bằng chứng là snapshot ngày kiểm tra.
- **[S14]** VietQR [bundle Portal công khai](https://my.vietqr.io/static/js/main.298abdd9.js), được dẫn bởi [Portal chính thức](https://my.vietqr.io/): route `/profile`, mục API Key/Client ID và quota lookup. Chỉ đọc source, không gọi các API tài khoản được nhắc trong source.
- **[S15]** VietQR [Thông báo cập nhật Payment Kit](https://www.vietqr.io/thong-bao-cap-nhat.md): thay đổi ngày 14/10/2026, chỉ `/v2/generate` ngừng từ 01/01/2027, các API khác/Portal vẫn hoạt động.
- **[S16]** VietQR [API danh sách ngân hàng](https://www.vietqr.io/api-bank.md): `lookupSupported`, không cần key cho banks và khuyến cáo làm mới cache theo ngày.

Không kiểm tra tài khoản, số dư/credit thực, quyền POST hay giao dịch thật. Rủi ro còn mở: entitlement lookup, khoản 5.000 VND, phí/hạn mức/duyệt và guarantee recovery chưa được xác nhận cho CarRent.
