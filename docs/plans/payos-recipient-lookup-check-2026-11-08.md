# payOS: kiểm tra contract tra cứu tên người nhận trước khi hoàn

Ngày đối chiếu: **2026-11-08**. Đã đọc báo cáo [điều kiện vận hành 07-11](payos-refund-live-prerequisites-2026-11-07.md), sau đó chỉ GET lại 5 trang tài liệu chính thức bên dưới. Không đọc cấu hình riêng, tài khoản hoặc DB; không gọi API xác thực, lookup thực tế hay chuyển tiền.

## Kết quả

**Chưa tìm được contract lookup mới để đóng gate xác minh tên người nhận của CarRent.** Contract công khai rõ nhất vẫn là VietQR `POST /v2/lookup`, nhưng trang này tiếp tục thông báo đang migrate sang CAS Pay Out. Tài liệu không xác nhận tài khoản payOS hiện có được cấp quyền lookup, cũng không xác nhận tài khoản VietQR mới/cũ còn được kích hoạt endpoint cũ theo điều kiện nào. [S1]

**`estimate-credit` không cung cấp tên người nhận trong response được tài liệu hóa.** Có `validateDestination`, nhưng success response chỉ chứa `data.estimateCredit`; không đủ làm nguồn tên chuẩn để khách xác nhận trước khi tạo payout. Đây là giới hạn bằng chứng công khai đã đọc, không phải khẳng định provider không có API thương mại khác. [S2]

## Contract đã xác lập từ tài liệu

| Dịch vụ | Endpoint và xác thực | Đầu vào / kết quả | Giới hạn sử dụng cho CarRent |
| --- | --- | --- | --- |
| VietQR lookup | `POST https://api.vietqr.io/v2/lookup`; header `x-client-id`, `x-api-key`; tài liệu chỉ dẫn lấy hai giá trị tại `https://my.vietqr.io`. | JSON có `bin` kiểu integer và `accountNumber` kiểu string. Thành công: `code="00"`, `data.accountName`. | Tài liệu mô tả tra cứu tính hợp lệ và trả tên tài khoản; không phải endpoint tạo lệnh chi. Tuy nhiên entitlement, giá và tình trạng cấp quyền khi migrate chưa được xác nhận cho tài khoản thực tế. Không suy ra khóa payout payOS dùng được cho VietQR. [S1] |
| payOS estimate-credit | `POST https://api-merchant.payos.vn/v1/payouts/estimate-credit`; `x-client-id`, `x-api-key`, `x-signature`. | Body: `referenceId`, `category`, `validateDestination`, `payouts`; từng payout mẫu có `referenceId`, `amount`, `description`, `toBin`, `toAccountNumber`. Success mẫu: `{"code":"00","desc":"success","data":{"estimateCredit":5000}}`. | Không có `accountName` / `toAccountName` hoặc kết quả từng người nhận trong response công bố. Không dùng `validateDestination=true` làm bằng chứng tên đã được tra cứu. `5000` chỉ là giá trị mẫu phí, không phải mức chuyển tối thiểu. [S2] |
| payOS create payout | `POST /v1/payouts` hoặc `/v1/payouts/batch`. | Response mẫu có `transactions[].toAccountName` (bản Markdown biểu diễn phần tử mẫu bằng khóa `"0"`). | Đây là tạo lệnh chi; tên xuất hiện sau create không đáp ứng yêu cầu biết tên trước khi khách xác nhận và trước khi tạo lệnh. Không tạo payout để thử tìm tên. [S2] |
| payOS balance | `GET /v1/payouts-account/balance`. | Trả `accountNumber`, `accountName`, `currency`, `balance` của tài khoản chi. | Không nhận BIN/số tài khoản người nhận; không phải lookup đích hoàn. [S2] |

VietQR công bố lỗi nghiệp vụ `42` (tài khoản không hợp lệ), `41` (ngân hàng chưa hỗ trợ), `43` (lỗi không xác định), `45` (provider bận), cùng HTTP `429` (vượt giới hạn). Response thành công không echo BIN/số tài khoản: phía server phải liên kết kết quả với chính request. Bảng đầu vào ghi số tài khoản chỉ gồm số, dài 6–19 ký tự, trong khi ví dụ lỗi `21` nói số/chữ alias dài 4–25; cần provider xác nhận quy tắc hiện hành trước khi triển khai. Demo tương tác của trang là dữ liệu mô phỏng, không gọi lookup thật. [S1]

## CAS: onboarding có tài liệu, lookup thay thế chưa có contract tại các trang liên quan

- Trang VietQR nói CAS Pay Out **sẽ bao gồm** API tra cứu STK và chuyển tiền trực tiếp từ tài khoản doanh nghiệp. Đây là thông báo migration, không cung cấp endpoint lookup CAS thay thế. [S1]
- CAS Quickstart hướng dẫn đăng ký tại Console để lấy `x-client-id` và `x-secret-key`; tách `https://sandbox.bankhub.dev` và `https://production.bankhub.dev`, bắt đầu bằng Sandbox. Đây là bộ khóa CAS, không phải chỉ dẫn tái sử dụng khóa payOS/VietQR. [S3]
- Trang CAS Pay Out hướng dẫn scope `transfer`: tạo `/grant/token`, mở Cas Link, đổi `publicToken` qua `/grant/exchange` để lấy `accessToken`, rồi gọi API chuyển tiền. Luồng này là kết nối/quyền chuyển tiền, chưa chứng minh quyền lookup độc lập có sẵn. [S4]
- Index Transfer ghi rõ chỉ gồm hai API: tạo chuyển tiền và truy vấn thông tin giao dịch chuyển tiền đã tạo. Cả index và trang Pay Out được đọc lại hôm nay không cung cấp endpoint/schema lookup tên độc lập theo BIN + số tài khoản. Không suy ra dịch vụ không tồn tại ở trang khác hoặc theo hợp đồng riêng. [S4][S5]

## Bước tiếp theo có thể thực hiện

Chưa có contract mới đủ để viết verifier production. Cần câu trả lời chính thức của payOS/Casso về **một** trong hai đường: cấp quyền VietQR `/v2/lookup` đang có tài liệu, hoặc cung cấp contract lookup CAS hiện hành. Yêu cầu trả lời phải gồm endpoint production/sandbox; headers/quyền và nơi cấp khóa; BIN/số tài khoản đầu vào; tên chuẩn đầu ra; xác nhận không tạo/chuyển tiền; lỗi, timeout/rate limit, ngân hàng hỗ trợ và giá. Nếu họ đề nghị `estimate-credit`, cần schema trả tên được hỗ trợ chính thức thay cho suy luận từ cờ `validateDestination`. [S1–S5]

Đối với tài khoản hiện có, chỉ dẫn công khai đến `my.vietqr.io` hoặc Console CAS chưa đủ chứng minh entitlement. Không có kiểm tra tài khoản nào trong lần nghiên cứu này. Chưa gửi yêu cầu hỗ trợ ra ngoài.

Các trang đã đọc không bổ sung cam kết minimum/retry phù hợp để thay đổi kết luận báo cáo 07-11; lần này không mở rộng nghiên cứu payout recovery vì không giải quyết gate lookup. [S2]

## Nguồn chính thức đã GET lại

- **[S1]** VietQR/Casso, [API tra cứu STK](https://www.vietqr.io/tra-cuu-stk.md), HTTP 200: endpoint, auth, schema, lỗi, migration và giới hạn demo.
- **[S2]** payOS, [API reference](https://payos.vn/docs/api.md), HTTP 200: `estimate-credit`, create payout, balance và response samples.
- **[S3]** CAS/Casso, [Quickstart](https://cas.so/quickstart/), HTTP 200: Console, bộ khóa CAS, môi trường và grant/access token.
- **[S4]** CAS/Casso, [Pay Out](https://cas.so/product/pay-out/), HTTP 200: scope `transfer`, luồng liên kết và gọi chuyển tiền.
- **[S5]** CAS/Casso, [Transfer API index](https://cas.so/general/api/product-transfer/), HTTP 200: hai API chuyển tiền/truy vấn giao dịch đã tạo. URL CAS không có dấu `/` cuối redirect 308 sang các URL trên; đã đọc đích cuối.

Không kiểm tra khóa, entitlement live, lookup thật hay giao dịch thật. Rủi ro còn lại: migration và quyền lookup cho tài khoản thực tế chưa được provider xác nhận.
