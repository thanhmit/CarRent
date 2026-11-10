# P4.2-A/P8.8: contract công khai để chuẩn bị verifier

Đối chiếu **09-11-2026, khoảng 08:58 giờ Việt Nam** bằng GET tài liệu công khai chính thức. Không đọc credentials/DB, không gọi API lookup/payout, không POST hay gửi thông điệp ra ngoài. Raw response và URL cuối, HTTP status, thời điểm UTC, SHA-256 được lưu tại manifest (bằng chứng tạm đã dọn).

**Có thể triển khai adapter VietQR `/v2/lookup` tắt mặc định và kiểm thử offline theo schema đang công bố. Chưa đủ căn cứ bật production:** trang vẫn thông báo migrate sang CAS; tài liệu không xác nhận quyền lookup cho tài khoản CarRent, điều kiện cấp mới/cũ, giá hoặc contract CAS thay thế. [S1]

## Contract lookup đã GET lại

| Mục | Bằng chứng chính thức và constraint adapter |
| --- | --- |
| Request | `POST https://api.vietqr.io/v2/lookup`, JSON; headers `x-client-id`, `x-api-key`, `Content-Type: application/json`. Hai khóa được chỉ dẫn lấy tại `https://my.vietqr.io`; không có căn cứ dùng lại khóa payOS/CAS. [S1] |
| BIN | Body `bin` kiểu **integer**; lỗi `22` mô tả BIN 6 chữ số. Validate BIN 6 chữ số trước khi chuyển sang integer để gửi; không gửi tên/mã viết tắt ngân hàng. [S1] |
| Số tài khoản | Bảng body cả Markdown và HTML ghi **chỉ số, 6–19 ký tự**, kiểu string. Giữ chuỗi và số 0 đầu. Lỗi `21` lại ghi số/chữ alias 4–25: đây là bất nhất docs, không phải quyền tự mở rộng input. Chưa có xác nhận provider, adapter nên chỉ hỗ trợ `[0-9]{6,19}`. Giới hạn 1–50 ở form/domain CarRent không phải contract VietQR. [S1] |
| Success | HTTP `200`, `code` là chuỗi `"00"`, `data.accountName` là tên tài khoản. Response mẫu không echo BIN/STK. Chỉ nhận success khi schema hợp lệ và tên là string không rỗng; backend gắn tên với đúng BIN/STK request, tiếp tục yêu cầu khách xác nhận tên trước khi tạo lệnh. Điều kiện schema/tên không rỗng là chính sách fail-closed đề xuất của CarRent. [S1] |
| Lỗi trong HTTP 200 | `"42"`: tài khoản không hợp lệ; `"41"`: ngân hàng chưa hỗ trợ; `"43"`: lỗi không xác định; `"45"`: provider bận; `"10"`: thiếu tham số; `"22"`: BIN không hợp lệ; `"21"`: số tài khoản không hợp lệ. Không coi HTTP 200 đơn lẻ là success. [S1] |
| Rate limit / lỗi khác | Có HTTP `429` nhưng không công bố quota, timeout, SLA, lịch retry hoặc `Retry-After` bắt buộc. HTTP lỗi, code lạ, JSON/schema sai, tên trống, timeout đều phải chặn xác minh; không tự thay bằng tên do khách nhập. [S1] |
| Xác thực response / redirect | Trang lookup không công bố chữ ký response hay checksum key. HTTPS và credential headers là contract công bố; không được gán bảo đảm HMAC payOS cho VietQR. Đề xuất cố định URL HTTPS, xác thực TLS mặc định, không theo redirect khi mang credentials, không log headers/body/response chứa tài khoản. Đây là chính sách transport của ứng dụng, không phải cam kết provider. Không gọi endpoint API nên chưa đo redirect thực tế của lookup. [S1] |

GET tài liệu VietQR Markdown/HTML và payOS Markdown/HTML trả HTTP 200, URL cuối giữ nguyên; không có bằng chứng rằng endpoint API có cùng hành vi redirect. GET GitHub commit qua tên repo redirect sang repository ID; đây chỉ là redirect nguồn tài liệu công khai, không liên quan API thanh toán. [S1][S2][S3]

## Minimum, phí và recovery

- **Chưa có bằng chứng chính thức cho minimum payout/refund 5.000đ.** payOS payout khai báo `amount` integer, không nêu min/max trong Markdown và HTML đã tải. `5000` nằm trong response mẫu `data.estimateCredit` của `/v1/payouts/estimate-credit`; không phải minimum hay giá cố định. Không hard-code giới hạn provider từ con số này. Nếu CarRent có minimum nghiệp vụ riêng, phải ghi rõ đó là quy tắc ứng dụng và đối chiếu provider trước pilot. [S2]
- `estimate-credit` có `validateDestination` nhưng response công bố chỉ có `estimateCredit`; không cung cấp tên tài khoản trước chi. `transactions[].toAccountName` xuất hiện ở create/detail payout và có thể null theo SDK, không thay thế lookup trước khi tạo lệnh. [S2][S3]
- Create payout yêu cầu `x-idempotency-key` và `x-signature`. SDK chính thức giải thích dùng UUID/chuỗi entropy cao để retry cùng request được nhận diện; bỏ key thì SDK tự tạo UUID mới. HEAD hiện tại vẫn là `cf970978e4d5f0ce5887ef2171f9ec5b6e9eb73e`, đã tải lại source pin theo commit này. List có lọc `referenceId` và pagination, detail theo payout ID. [S2][S3]
- Các nguồn payOS đã đọc **không xác lập** TTL/scope key, cùng key khác body, unique constraint `referenceId`, thời gian nhất quán list/detail, hay guarantee list rỗng = chắc chắn chưa chi. SDK không có transaction state `NOT_SENT`. Không mở resend tự động cho `UNKNOWN`, timeout, signature sai, list rỗng, `FAILED`/`CANCELLED`/`REVERSED` chỉ từ những bằng chứng này. Đây là giới hạn contract tìm được, không phải khẳng định provider không có hợp đồng riêng. [S2][S3]
- CAS Transfer ghi trực tiếp: trạng thái khác `SUCCESS` thì giữ nguyên, tuyệt đối không tự phát lệnh lại; CAS phối hợp ngân hàng và thông báo cuối qua webhook. Cảnh báo này thuộc CAS, không được áp enum/semantics CAS sang payOS. [S4]
- Onboarding payOS có bước xác thực tổ chức, chọn ví/ngân hàng nguồn và thêm IP cho kênh. Gói dịch vụ chi dựa vào số lượng giao dịch, có mô tả credit dùng thử; không xác nhận entitlement/giá lookup, auto-approval hay số dư thực tế của CarRent. [S5][S6]

## Điều kiện bật sau triển khai offline

Provider cần xác nhận bằng chứng không chứa secrets: tài khoản được dùng VietQR endpoint cũ hay contract CAS mới; environment, headers/quyền, BIN/STK được hỗ trợ, tên chuẩn, tính chất không chuyển tiền, quota/giá; riêng payout cần min/max/phí, approval/IP, idempotency và trạng thái chắc chắn chưa gửi/đã hoàn tiền. Adapter tắt mặc định, test stub và tài liệu công khai không đóng gate entitlement, migration, vận hành hay pilot/đối soát tiền thật.

## Nguồn chính thức

- **[S1]** VietQR/Casso: [Markdown](https://www.vietqr.io/tra-cuu-stk.md), [HTML](https://www.vietqr.io/tra-cuu-stk), raw `vietqr-lookup.md`, `vietqr-lookup.html`: endpoint, auth, input, codes, migration, demo mô phỏng.
- **[S2]** payOS: [API Markdown](https://payos.vn/docs/api.md), [API HTML](https://payos.vn/docs/api/), raw `payos-api.md`, `payos-api.html`: payout/create/list/detail/estimate-credit/balance.
- **[S3]** payOSHQ: [HEAD metadata](https://api.github.com/repos/payOSHQ/payos-node/commits/main), [payouts.ts tại commit đã pin](https://github.com/payOSHQ/payos-node/blob/cf970978e4d5f0ce5887ef2171f9ec5b6e9eb73e/src/resources/v1/payouts/payouts.ts), raw `payos-sdk-head.json`, `payos-payouts-sdk.ts`: idempotency, response signatures, types/state.
- **[S4]** CAS: [Transfer](https://cas.so/general/api/product-transfer/transfer.md), [Transfer Information](https://cas.so/general/api/product-transfer/transfer-info.md), raw `cas-transfer.md`, `cas-transfer-info.md`: request/response và cảnh báo không phát lệnh lại.
- **[S5]** payOS: [Tạo kênh chuyển tiền](https://payos.vn/docs/huong-dan-su-dung/kenh-chi/tao-kenh-chuyen-tien.md), raw `payos-channel.md`: tổ chức, nguồn, IP.
- **[S6]** payOS: [Thanh toán gói dịch vụ chi](https://payos.vn/docs/huong-dan-su-dung/kenh-chi/thanh-toan-goi-dich-vu.md), raw `payos-package.md`: gói/credit.

Chưa kiểm tra entitlement, lookup thật, cấu hình/DB, migration hoặc payout thật. Rủi ro còn lại: API lookup đang migration và các giới hạn vận hành theo tài khoản chưa được provider xác nhận.
