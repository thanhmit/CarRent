# SePay collection contract — 2026-11-07

Nguồn: tài liệu chính thức `developer.sepay.vn`, tài liệu legacy chính thức `docs.sepay.vn`, và danh mục ngân hàng `vietqr.app/banks.json` được SePay liên kết trực tiếp. Chỉ GET tài liệu công khai; không đọc credentials/cấu hình ứng dụng, không vào UI, không sửa DB/mã ứng dụng và không thực hiện giao dịch. Đây là contract tài liệu, chưa chứng minh cấu hình SePay live của CarRent.

## Kết luận thực thi

1. **Contract HMAC có timestamp là đúng tài liệu SePay hiện hành:** `X-SePay-Signature: sha256={hex_hash}`, `X-SePay-Timestamp` Unix seconds, HMAC-SHA256 trên `{timestamp}.{raw_body}`. Không có căn cứ đổi sang một scheme khác chỉ vì hướng dẫn cũ không nói tới HMAC. Chưa kiểm tra implementation CarRent trong nghiên cứu này. [S1]
2. **Giữ HMAC làm mode đã chọn.** API key là mode riêng được cấu hình ở SePay, header `Authorization: Apikey ...`; Bearer của API truy vấn là chức năng khác. Không tự chấp nhận API key khi signature HMAC sai hoặc thiếu. Đây là quy tắc tích hợp đề nghị để tránh hạ mức xác thực. [S1][S2]
3. **Mã mới phải phù hợp cấu hình SePay:** tiền tố 2–5 ký tự, hậu tố 1–30 ký tự; dùng `CR` + hậu tố chữ/số tối đa 30. `CR` + hậu tố 32 không thể cấu hình nguyên vẹn trong bộ nhận diện này. Không cắt ngắn mã cũ đã phát hành khi xử lý; migration mã cũ cần được kiểm tra riêng. [S4]
4. **Không thay v1 bằng v2 chỉ bằng URL:** webhook `id` vẫn integer; API v1 numeric ID, v2 UUID. Docs migration khẳng định numeric ID không dùng được trong v2 và yêu cầu đối chiếu trường nghiệp vụ để lấy UUID. Để dùng chung ingestion theo numeric SePay ID với thay đổi nhỏ nhất, giữ adapter đối soát v1 còn được hỗ trợ; nếu chọn v2 phải thiết kế định danh chung/alias và chống trùng webhook–reconciliation rõ ràng. [S3][S7][S8]
5. **TPBank QR:** `bank=970423` hoặc `TPBank`/`TPB` được contract cho phép; `gateway` webhook là tên ngân hàng `TPBank`, không phải BIN. QR hiện hành theo SePay là `https://vietqr.app/img?acc=...&bank=...&amount=...&des=...`. [S3][S5][S6]

## 1. Webhook production authentication

### HMAC-SHA256

| Thành phần | Contract công bố |
| --- | --- |
| Mode | Chọn **HMAC-SHA256** ở bước Bảo mật khi tạo/sửa webhook. |
| Header chữ ký | `X-SePay-Signature: sha256={hex_hash}` |
| Header thời gian | `X-SePay-Timestamp: {timestamp}`, Unix seconds tại lúc ký. |
| Dữ liệu ký | Timestamp, dấu chấm ASCII `.`, và **raw body gốc**, theo đúng thứ tự `{timestamp}.{raw_body}`. |
| Thuật toán | HMAC-SHA256 với Secret Key đã cấu hình; output hex; so sánh constant-time. |
| Replay | Ví dụ và hướng dẫn bảo mật từ chối timestamp cách hiện tại quá 300 giây ở cả hai chiều; đồng bộ NTP. |
| Body | Không parse JSON rồi serialize lại trước khi xác minh. Thứ tự keys, khoảng trắng và Unicode escaping làm chữ ký khác. |

Nguồn trực tiếp: [S1], bổ sung chống replay và URL HTTPS ở [S2]. Đây là mô tả byte-level; parser vẫn phải từ chối timestamp malformed/thiếu và signature malformed. Không ghi secret hay header đầy đủ vào log.

SePay hỗ trợ JSON mặc định, form-urlencoded và multipart. Trang xác thực nói form bodies được ký theo chuỗi form-encoded tương tự `http_build_query` và vẫn yêu cầu raw body. Do chưa kiểm thử canonicalization multipart và CarRent nhận JSON, cấu hình **`application/json`** là lựa chọn có contract rõ; không mở rộng parser form trong task này. [S1][S3]

### API Key là mode riêng

```http
Authorization: Apikey <WEBHOOK_API_KEY>
```

Chọn **API Key** ở bước Bảo mật, cấu hình key ở SePay và lưu riêng trên server. HMAC Secret và API key webhook không đồng nhất với API token tra cứu. [S1]

`Authorization: Bearer <API_TOKEN>` là xác thực cho API truy vấn; OAuth webhook cũng có một luồng Bearer riêng do server merchant cấp. Sự giống nhau về chữ Bearer không tạo quyền dùng API token truy vấn để giả lập webhook. [S1][S8]

**Khuyến nghị:** tiếp tục HMAC nếu code và dashboard cùng dùng HMAC. Chỉ bổ sung mode API key khi cần hỗ trợ một cấu hình được chọn rõ ràng theo channel, không làm fallback sau lỗi HMAC. Đây là quyết định ứng dụng, không phải SePay tự động fallback.

## 2. Payload, routing và idempotency

| Trường webhook | Ý nghĩa chính thức | Hệ quả tích hợp |
| --- | --- | --- |
| `id` | Integer SePay transaction ID, giữ nguyên qua retry/replay. | Là khóa chống trùng webhook; không dùng bank reference trống thay thế tùy tiện. |
| `gateway` | Tên ngân hàng, ví dụ `Vietcombank`, `BIDV`, `TPBank`. | Nếu config dùng BIN, cần mapping bank name ↔ BIN đã kiểm tra; không so trực tiếp `TPBank` với `970423`. |
| `accountNumber` | Tài khoản ngân hàng. | Phải khớp nguồn nhận đã cấu hình và intent. |
| `subAccount` | VA chính thức khách chuyển vào hoặc mã TKP phân bổ theo nội dung; `""` khi không khớp. | Phân vùng nhận tiền theo VA/TKP nếu được dùng; không phải payment code. |
| `code` | Mã thanh toán do SePay trích từ content theo cấu hình công ty, có thể `null`. Không liên quan VA. | Chỉ lookup intent theo mã đầy đủ hợp lệ; không mặc định `subAccount` là code. |
| `content` | Nội dung gốc ngân hàng, không xử lý. | Nếu hỗ trợ fallback từ content khi code thiếu, cần quy tắc riêng tránh bắt mã là substring của token dài hơn/không khớp; đây là logic ứng dụng cần test. |
| `transactionDate` | `YYYY-MM-DD HH:mm:ss`, giờ Việt Nam. | Không mặc định UTC khi parse chuỗi không có offset. |
| `transferType` | `in` hoặc `out`. | Collection chỉ nhận `in`. |
| `transferAmount` | Integer VNĐ, luôn dương. | Kiểm tra số tiền theo intent; không dùng amount âm suy ra chiều. |
| `referenceCode` | Mã tham chiếu ngân hàng, có thể rỗng. | Có thể hỗ trợ cross-version match khi có đủ dữ liệu; chưa có cam kết universal unique. |
| `accumulated` | Số dư sau giao dịch; có thể 0 nếu ngân hàng không hỗ trợ. | Không dùng làm bằng chứng số tiền/settlement của intent. |

Nguồn: [S3]. SePay có thể gửi lại do retry, replay thủ công hoặc nhiều webhook trỏ cùng endpoint. Database uniqueness và transaction xử lý idempotent vẫn cần thiết dù signature hợp lệ.

**Phản hồi hợp lệ:** HTTP **200 hoặc 201**, JSON `{"success":true}`, hoàn tất trong **30 giây**. `202`, body rỗng hoặc JSON kiểu `{"status":"ok"}` không đạt contract. Acknowledge sau durable receipt; xử lý tiếp có thể qua queue theo thiết kế ứng dụng. [S3]

**Retry:** trang xử lý lỗi công bố 1 lần gửi đầu + 7 lần retry, chờ 1, 1, 2, 3, 5, 8, 13 phút, tổng khoảng 33 phút. Tài liệu có câu khác nhau về tiêu chí retry HTTP 2xx trong khi trang integration yêu cầu 200/201 + body chính xác; dùng response nghiêm ngặt 200/201 + success=true để tránh phụ thuộc khác biệt. [S3][S10]

## 3. Cấu hình mã thanh toán

Vị trí chính thức: **Cấu hình Công ty → Cấu hình chung → Cấu trúc mã thanh toán**. Bật nhận diện mã, mẫu hoạt động, chọn loại **Số và chữ** nếu CarRent dùng hex/alphanumeric. [S4]

| Thành phần | Giới hạn |
| --- | --- |
| Tiền tố | Tối thiểu 2, tối đa 5 ký tự; tự đổi chữ hoa khi lưu. |
| Hậu tố min | 1–30, mặc định 6. |
| Hậu tố max | 1–30, phải ≥ min, mặc định 8. |
| Loại ký tự | Chữ số 0–9 hoặc chữ số + chữ cái A–Z. |
| Nhiều mẫu | Quét mẫu đang hoạt động theo thứ tự; dừng khi tìm được mẫu khớp. |

Nhận diện tiền tố trong content không phân biệt hoa/thường; ví dụ `dh999999` cho code `DH999999`. Trang tạo webhook mô tả **lọc tiền tố** phân biệt hoa/thường. Do đó cấu hình `CR` viết hoa đồng nhất; không dựa vào việc hai giai đoạn có cùng quy tắc case. [S4][S11]

**Rủi ro cần kiểm tra trong app:** tài liệu chỉ mô tả tìm chuỗi khớp, không hứa chỉ match token có boundary hoặc không cắt token dài. Mã quá dài có thể không được trích đúng toàn bộ. Không chấp nhận prefix match hoặc tự truncate khi đối chiếu intent. Đổi generator phải giữ mã đầy đủ của intent đã tồn tại; không sửa hàng loạt mã đang chờ thanh toán.

Webhook có bộ lọc **Chỉ gửi khi có mã thanh toán** và **Lọc theo mã thanh toán**. Bật chúng khi mẫu `CR` sai có thể làm webhook không đến. Nếu nhận không lọc code, app vẫn phải xác minh tài khoản/ngân hàng/chiều/số tiền và mã hợp lệ; không tự coi mọi tiền vào là booking đã thanh toán. [S4][S11]

## 4. TPBank và QR

**Danh mục được SePay dẫn trực tiếp:** `https://vietqr.app/banks.json` trả TPBank:

```json
{"name":"Ngân hàng TMCP Tiên Phong","code":"TPB","bin":"970423","short_name":"TPBank","supported":true}
```

Nguồn: [S6]. API bank catalog Casso/VietQR cũng trả BIN `970423`, code `TPB`, shortName `TPBank`; đây là đối chiếu phụ [S12], không phải xác minh entitlement lookup.

QR contract [S5]:

```text
https://vietqr.app/img?acc=<ACCOUNT>&bank=970423&amount=<INTEGER_VND>&des=<URL_ENCODED_PAYMENT_CONTENT>
```

- `acc`, `bank` bắt buộc. `bank` nhận `short_name`, `alias`, `code` hoặc `bin` trong danh mục.
- `amount` integer VND; `des` là nội dung chuyển khoản. Với collection intent phải cung cấp đúng amount và payment code dù QR API cho phép bỏ trống.
- `holder` chỉ là tên hiển thị ảnh, **không phải tên chủ tài khoản đã được tra cứu/xác minh**. Không coi tạo QR thành công là recipient lookup hoặc payment success.
- Optional template hiện được docs liệt kê: bỏ trống, `compact`, `qronly`, `standee`.
- **TPBank** hỗ trợ tiền vào tài khoản chính cho cá nhân/hộ kinh doanh/doanh nghiệp, không có VA chính thức trong bảng SePay; có thể dùng VA nội dung TKP khi muốn phân vùng. [S13]
- Không dùng TKP: `acc` là tài khoản chính, `des` chứa payment code.
- Có TKP: `acc` vẫn tài khoản chính, `des` phải chứa `TKP` + mã TKP + nội dung, ví dụ dữ liệu giả `TKP001 CR...`; `subAccount` phản ánh phần phân vùng khi khớp. Không tự thêm TKP không tồn tại. [S5][S13]
- Không áp dụng điều kiện `SEVQR` của VietinBank cho TPBank. [S5]

Không tạo/scan QR live trong nghiên cứu. Việc SePay đang đồng bộ được tài khoản TPBank cụ thể và dashboard đang chọn đúng account/VA phải kiểm chứng riêng bằng luồng vận hành được phép.

### QR Casso/VietQR đang dùng trong app vẫn có contract chính thức

Không cần đổi nhà cung cấp ảnh QR chỉ vì hướng dẫn SePay hiện dùng `vietqr.app`. Tài liệu chính thức Casso/VietQR công bố [S15]:

```text
https://img.vietqr.io/image/<BANK_ID>-<ACCOUNT_NO>-<TEMPLATE>.svg?amount=<AMOUNT>&addInfo=<DESCRIPTION>&accountName=<ACCOUNT_NAME>
```

- `BANK_ID` nhận BIN hoặc tên thường gọi/tên viết tắt trong danh mục ngân hàng; `970423`, `TPBank`, `TPB` có căn cứ từ danh mục [S12].
- `compact2` là template được công bố (540×640). Account tối đa 19 ký tự chữ/số; amount dương tối đa 13 chữ số; description tối đa 50 ký tự, không ký tự đặc biệt. URL encode các giá trị.
- `accountName` chỉ hiển thị trên ảnh, không nằm trong tiêu chuẩn payload VietQR; không phải account-name verification.
- Thông báo 14/10/2026 xác nhận **Quicklink PNG/JPEG vẫn được phục vụ**, SVG được khuyến khích; có kế hoạch dừng PNG/JPEG trong năm sau nhưng không công bố ngày chính xác. Vì vậy URL `...-compact2.png?amount=...&addInfo=...` hiện có cơ sở tương thích; chưa cần đổi provider để sửa webhook. [S16]
- Trang Quicklink còn nói dự án chính thức cần template riêng, trong khi thông báo mới nói ngừng tiếp nhận Custom Theme/Template mới và giữ template đã có. Đây là khác biệt tài liệu thương mại cần hỏi provider khi chốt production, không phải bằng chứng URL gốc đã ngừng chạy. [S15][S16]

**Mức chắc chắn bank mapping:** SePay payload mô tả `gateway` là tên và nêu `TPBank`; danh mục được SePay liên kết trả TPBank=970423. Đủ căn cứ mapping hẹp TPBank↔970423. Chưa có một bảng SePay gateway aliases toàn bộ ngân hàng trong nguồn đã đọc; không mở rộng mapping mọi alias không có bằng chứng.

## 5. Đối soát v1 và v2

| Thuộc tính | v1 | v2 |
| --- | --- | --- |
| Base | `https://my.sepay.vn/userapi/` | `https://userapi.sepay.vn/v2` |
| List | `GET transactions/list` | `GET transactions` |
| Detail | `GET transactions/details/{numeric_id}` | `GET transactions/{uuid}` |
| IDs | Số tự tăng, response có thể là string chứa số. | UUID. Numeric ID v1 không hoạt động ở v2. |
| Auth | `Authorization: Bearer <TOKEN>` | `Authorization: Bearer <TOKEN>` |
| Result | `status:200`, `messages.success`, `transactions`/`transaction`; lỗi có thể HTTP 200/body rỗng. | `status:"success"`, `data`, meta pagination; lỗi HTTP theo quy ước. |
| Amount | Decimal dạng string ví dụ `"18067000.00"`. | Integer VND. |
| Bank/VA fields | `bank_brand_name`, `account_number`, `sub_account`. | `bank_brand_name`, `account_number`, `va`, UUID `bank_account_id`, `va_id`. |
| Date filters | `transaction_date_min/max`. | `transaction_date_from/to`. |
| Pagination | `limit` tối đa 5000, numeric `since_id`. | `page/per_page`, tối đa 100, UUID `since_id`. |

Nguồn: v1 [S7], migration [S8], v2 [S9]. Docs migration nói v1 vẫn hoạt động và được dùng song song khi chuyển đổi; không cần đổi v2 để có reconciliation ngay.

### API v1 để phục hồi thủ công theo webhook ID số

```http
GET https://my.sepay.vn/userapi/transactions/details/<NUMERIC_TRANSACTION_ID>
Authorization: Bearer <API_TOKEN>
Content-Type: application/json
```

Nguồn [S7] công bố success envelope `status:200`, `error:null`, `messages.success:true`, object `transaction` chứa `id` numeric string, `transaction_date`, `bank_brand_name`, `account_number`, `sub_account`, `amount_in`, `amount_out`, `accumulated`, `code`, `transaction_content`, `reference_number`, numeric-string `bank_account_id`. **Lấy đúng object `transaction`**, không phải list `transactions` hoặc v2 `data`. Chỉ query là read-only; trong nghiên cứu chưa gọi endpoint này với ID/token thật.

Không coi HTTP 200 là đủ: migration [S8] mô tả auth failure v1 có thể trả 200/body rỗng và lỗi nghiệp vụ HTTP 200. Nguồn đã đọc không công bố error schema đầy đủ cho mọi v1 detail not-found/permission; response trống/không đúng schema là thất bại hoặc chưa biết, không phải chứng cứ đã đối soát. Response đúng schema vẫn cần kiểm tra ID yêu cầu, bank/account/subaccount, chiều, số tiền, mã/content trước khi đưa qua ingestion.

API v1 rate limit **2 requests/giây theo IP**, HTTP 429 có `x-sepay-userapi-retry-after` (seconds). v2 là 3 requests/giây và `Retry-After`. [S17] Token production được SePay công bố dùng chung v1/v2 và chưa có phân quyền chi tiết; token Test Mode chỉ dùng **v2 Sandbox**, không dùng v1/production. API gateway và OAuth dùng credentials riêng. [S18]

### Mapping v1 vào payload dùng chung

Contract fields tương ứng để tránh viết một bộ business validation khác:

| v1 API | Webhook model |
| --- | --- |
| `id` numeric string | numeric `id` canonical string |
| `bank_brand_name` | `gateway` |
| `account_number` | `accountNumber` |
| `sub_account` | `subAccount` |
| `transaction_date` | `transactionDate` |
| `transaction_content` | `content` |
| `reference_number` | `referenceCode` |
| `code` | `code` |
| positive `amount_in`, zero `amount_out` | `transferType=in`, `transferAmount` exact integer VND |

Đây là mapping đề nghị từ tên trường chính thức; app phải parse tiền exact, không làm tròn số lẻ hoặc dùng float mất chính xác. Amount out hoặc dữ liệu mơ hồ không được đưa vào collection như tiền vào.

### Nếu chọn v2

- Không dùng v2 UUID làm một payment identity hoàn toàn mới cho cùng giao dịch webhook numeric ID. Việc này có thể tạo hai payment records cho một lần chuyển tiền.
- Docs migration yêu cầu lấy UUID từ list v2 rồi đối chiếu dựa trên số tài khoản, mã tham chiếu, v.v. Không có field numeric legacy ID được công bố trong response v2 đã đọc. [S8][S9]
- Docs đối soát nói dùng UUID hoặc `reference_number` tìm bản ghi thiếu; tuy nhiên reference webhook có thể rỗng, chưa có cam kết uniqueness toàn hệ thống. Cần match đúng bank/account/reference/amount/time/content và fail closed khi không thể phân biệt. Không có phép chuyển numeric ID → UUID bằng format đơn giản. [S3][S8][S14]
- Poll v2 theo `since_id` cần `transaction_date_sort=asc`, đọc hết trang rồi mới dùng ID giao dịch cuối làm cursor; ID không tồn tại trả danh sách rỗng, không tự coi là đã sync đầy đủ. [S9]
- Rate limit công bố 3 requests/giây. Tài liệu giới thiệu có phần Overview còn ghi base my.sepay.vn, nhưng mục **Môi trường API**, migration và endpoint cụ thể đều dùng `https://userapi.sepay.vn/v2`; dùng endpoint cụ thể. [S8][S9]

## 6. Các gate chưa được nghiên cứu này đóng

- Mode HMAC thực sự được chọn trên webhook live, JSON content type, secret đúng, server clock, URL HTTPS đang tới đúng app.
- TPBank thực sự liên kết/hoạt động, webhook chọn đúng account/TKP, payment-code pattern `CR` bật và suffix phù hợp.
- API token có quyền query đúng company/account; một giao dịch live có cùng numeric ID qua webhook/v1 hoặc mapping v2 có bằng chứng.
- Test provider/local fixtures, migration mã cũ, duplicate/replay/cross-channel/race và reconciliation đồng thời.
- Một pilot chuyển tiền người dùng chủ động cùng đối soát sau đó. Không chạy scheduler, restart app, gửi webhook live hoặc bật provider trong task nghiên cứu này.

## Nguồn

- **[S1]** [SePay webhook authentication](https://developer.sepay.vn/vi/sepay-webhooks/xac-thuc.md): HMAC headers/canonical input, API key và OAuth modes.
- **[S2]** [Webhook security](https://developer.sepay.vn/vi/sepay-webhooks/bao-mat.md): HTTPS, replay ±5 phút, validation và reconciliation.
- **[S3]** [Webhook integration](https://developer.sepay.vn/vi/sepay-webhooks/tich-hop-webhook.md): payload, stable numeric ID, `gateway/code/content/subAccount`, acknowledgment.
- **[S4]** [Payment-code configuration](https://developer.sepay.vn/vi/sepay-webhooks/cau-hinh-ma-thanh-toan.md): prefix/suffix/charset, case và extraction.
- **[S5]** [SePay VietQR generation](https://developer.sepay.vn/vi/tien-ich-khac/tao-qr-code.md): current image URL, bank parameters, VA/TKP rules, templates.
- **[S6]** [SePay-linked bank catalog](https://vietqr.app/banks.json): TPBank BIN 970423, code TPB, supported true.
- **[S7]** [Legacy official transaction API](https://docs.sepay.vn/api-giao-dich.html): v1 numeric IDs, amount strings, response và filters.
- **[S8]** [Upgrade v1 → v2](https://developer.sepay.vn/vi/sepay-api/v2/nang-cap-tu-v1.md): simultaneous support, ID incompatibility, mapping guidance, pagination.
- **[S9]** [v2 transaction list](https://developer.sepay.vn/vi/sepay-api/v2/giao-dich/danh-sach.md), [detail](https://developer.sepay.vn/vi/sepay-api/v2/giao-dich/chi-tiet.md), [intro](https://developer.sepay.vn/vi/sepay-api/v2/gioi-thieu.md): UUID contract, polling, environment and rate limits.
- **[S10]** [Webhook errors/retries](https://developer.sepay.vn/vi/sepay-webhooks/xu-ly-loi.md): retry timetable and diagnostics.
- **[S11]** [Create webhook](https://developer.sepay.vn/vi/sepay-webhooks/tao-webhook.md): modes, account/code filters, send-test payload is mock.
- **[S12]** [Casso/VietQR bank list](https://api.vietqr.io/v2/banks): secondary first-party cross-check TPBank BIN; lookup entitlement not tested.
- **[S13]** [Supported webhook banks](https://developer.sepay.vn/vi/sepay-webhooks/tai-khoan-ngan-hang.md): TPBank main account and TKP vs official VA.
- **[S14]** [Reconciliation guide](https://developer.sepay.vn/vi/sepay-webhooks/doi-soat-giao-dich.md): periodic API reconciliation and identity fields.
- **[S15]** [VietQR Quicklink](https://www.vietqr.io/qr-dong), [Markdown](https://www.vietqr.io/qr-dong.md): existing image URL contract, BIN, compact2, amount/addInfo/accountName.
- **[S16]** [VietQR October 2026 update](https://www.vietqr.io/thong-bao-cap-nhat.md): SVG encouraged, Quicklink PNG/JPEG still supported, future deprecation and template policy.
- **[S17]** [Official SePay API introduction](https://docs.sepay.vn/gioi-thieu-api.html): v1/v2 rate limits and retry headers.
- **[S18]** [Official SePay API tokens](https://docs.sepay.vn/tao-api-token.html): Bearer authentication, shared production v1/v2 token, sandbox separation and token privileges.

Không xác minh live credentials/dashboard/giao dịch hoặc sửa code. Rủi ro chính: cấu hình live chưa đối chiếu; mã đang chờ cũ và định danh v1/v2 cần giữ tính idempotent khi thay đổi.
