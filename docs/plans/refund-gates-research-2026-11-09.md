# Hoàn tiền CarRent: các gate còn lại để nghiệm thu đầy đủ

Ngày đối chiếu: **2026-11-09**. Phạm vi: tài liệu và mã nguồn hiện tại ở checkout gốc; không đọc `.env`/credential, không truy vấn DB/runtime, không gọi provider, không chạy test hoặc chuyển tiền. Các kết quả ngày 07–08/11 dưới đây là **snapshot tài liệu**, chưa được kiểm chứng lại hôm nay. Báo cáo này không xác nhận live-ready.

## Kết luận

Luồng nhập tài khoản → preview → khách xác nhận → lưu đích → worker gửi/đối soát đã có nền triển khai. Blocker mã nguồn trực tiếp hiện nay là **production verifier luôn từ chối**; phần còn lại chủ yếu là entitlement lookup, quyền và điều kiện kênh chi, chính sách xử lý trạng thái chưa rõ, xác nhận nguồn Owner/Admin, review/cutover và bằng chứng hoàn tiền thật. Không có căn cứ viết lại toàn bộ module hoàn tiền. Nguồn: `src/main/java/com/carrent/config/PaymentProviderConfiguration.java:21`; `src/main/java/com/carrent/payment/RefundRecipientService.java:40`; `src/main/java/com/carrent/payment/PayoutWorker.java:18`.

“Đạt 100%” ở đây nghĩa là đủ điều kiện và bằng chứng cho **phạm vi vận hành đã chốt**, gồm cả trường hợp lỗi phải có người xử lý; không đồng nghĩa mọi lỗi ngân hàng đều có thể tự động phát lại. Checklist P4.2-A cho phép giữ UNKNOWN/query-only khi provider không bảo đảm chưa chuyển. Nguồn: `docs/plans/phase-4-payment.md:134`; `docs/plans/phase-4-payment.md:141`; `docs/plans/p42a-p88-completion-analysis-2026-11-08.md:35`.

## 1. Gate bắt buộc: lookup tên trước khi tạo lệnh

**Còn thiếu:** provider xác nhận đường lookup đang áp dụng cho tài khoản thực tế và cấp quyền; sau đó thêm một implementation `RefundDestinationVerifier` cùng cấu hình private và kiểm thử. Hiện bean mặc định ném BusinessException; tìm trong `src/main/java` chưa thấy implementation production khác. Flow hiện có đã gọi lookup lại lúc submit, đối chiếu BIN/STK/tên và chặn mismatch/tên rỗng/control characters; tái sử dụng flow này. Nguồn: `src/main/java/com/carrent/config/PaymentProviderConfiguration.java:23`; `src/main/java/com/carrent/payment/RefundRecipientService.java:45`; `src/main/java/com/carrent/payment/RefundRecipientService.java:54`.

**Đầu vào phải có:** endpoint/environment, auth/scope, quyền áp dụng, BIN/STK hợp lệ, tên chuẩn trả về, cam kết lookup không tạo/chuyển tiền, ngân hàng hỗ trợ, lỗi/timeout/rate limit và phí. VietQR `/v2/lookup` có schema công bố nhưng đang migration CAS; chưa có evidence entitlement CarRent. `estimate-credit` chỉ công bố phí; balance trả tên nguồn; create trả tên sau tạo lệnh, đều không thay lookup trước xác nhận. Nguồn: `docs/plans/payos-recipient-lookup-check-2026-11-08.md:15`; `docs/plans/payos-recipient-lookup-check-2026-11-08.md:16`; `docs/plans/payos-recipient-lookup-check-2026-11-08.md:31`.

**Tiêu chí đạt:** lookup đúng BIN/STK, giữ số 0 đầu; không payout trong lookup; lỗi/thiếu tên đều chặn; đổi BIN/STK hoặc tên giữa preview và confirm bắt buộc xác minh lại; timeout, JSON lỗi, HTTP 200 có code lỗi, 401/403/429/5xx được kiểm thử bằng HTTP fixture; có lookup thực được provider cho phép. Không tự dùng khóa payout cho sản phẩm lookup khác. Nguồn: `docs/plans/p42a-p88-completion-analysis-2026-11-08.md:31`; `docs/plans/p42a-p88-completion-analysis-2026-11-08.md:32`; `docs/plans/p42a-p88-completion-analysis-2026-11-08.md:33`.

## 2. Gate bắt buộc: kênh có quyền chi, nguồn đúng và đủ tiền

**Còn thiếu bằng chứng áp dụng:** quyền POST production, activation, backend egress/IP, tự duyệt hay OTP/PIN/duyệt tay theo hạn mức, min/max/ngày, phí/VAT, cut-off, tiền gốc ví Bảo Kim và credit dịch vụ payOS. Khoản hoàn gói Small **5.000đ** phải thực hiện được; không lấy ví dụ `estimateCredit=5000` làm phí hoặc minimum. GET balance/list thành công không xác nhận quyền POST. Nguồn: `docs/plans/p42a-provider-contract-analysis-2026-11-08.md:34`; `docs/plans/p42a-provider-contract-analysis-2026-11-08.md:35`; `docs/plans/p42a-provider-contract-analysis-2026-11-08.md:45`; `docs/plans/p42a-p88-completion-analysis-2026-11-08.md:34`.

**Điểm cần quyết định nghiệp vụ:** checklist yêu cầu nguồn Owner/Admin riêng, nhưng snapshot 08/11 ghi hai reference cùng bộ khóa payOS, payout disabled. Cần xác nhận pilot dùng nguồn chung được duyệt hay cấp nguồn độc lập trước nghiệm thu; hai mapping không tự chứng minh hai ví. Không repoint kênh đã có lịch sử. Nguồn: `docs/plans/phase-4-payment.md:131`; `docs/plans/payos-account-configuration-2026-11-08.md:12`; `docs/plans/payos-account-configuration-2026-11-08.md:18`; `docs/plans/p42a-p88-completion-analysis-2026-11-08.md:62`; `docs/plans/payment-provider-contract.md:95`.

**Tiêu chí đạt:** evidence theo từng chủ thể/environment/channel; subscription refund dùng nguồn Admin, rental refund dùng nguồn Owner; nguồn tiền và phí đủ cho phạm vi pilot; người vận hành xác nhận quyền và cơ chế duyệt. Kênh chưa sẵn sàng có thể chặn ngay ở prepare trước khi lookup, nên thêm verifier riêng chưa làm flow vận hành được. Nguồn: `src/main/java/com/carrent/payment/RefundRecipientTransactions.java:44`; `src/main/java/com/carrent/payment/RefundRecipientTransactions.java:95`; `docs/plans/phase-4-payment.md:139`.

## 3. Gate bắt buộc: xử lý tiền chưa rõ và thiếu tiền có hợp đồng

Adapter hiện chỉ map transaction `SUCCEEDED` thành thành công; `RECEIVED/PROCESSING/ON_HOLD` thành đang xử lý; còn lại UNKNOWN. Nó **không phát NOT_SENT**. Vì vậy có code WAITING_FUNDS/retry không có nghĩa thiếu tiền thật đã tự được nhận dạng và chạy lại. Nguồn: `src/main/java/com/carrent/payment/provider/HttpPayOsPayoutClient.java:185`; `src/main/java/com/carrent/payment/PayoutTransactions.java:109`; `docs/plans/payment-provider-contract.md:93`.

**Hai cách đóng gate hợp lệ:**

- Provider có guarantee chắc chắn chưa chuyển cùng semantics idempotency/replay phù hợp: chỉ map NOT_SENT/lỗi thiếu tiền/retry theo contract đã xác nhận và test response có chữ ký; không suy diễn từ FAILED/HTTP error.
- Provider chưa bảo đảm: giữ UNKNOWN/query-only, ghi rõ những ca không tự retry và có quy trình đối soát/hỗ trợ được reviewer/người vận hành chấp thuận. Không đổi key/reference hoặc gửi lại vì query rỗng.

Nguồn: `docs/plans/phase-4-payment.md:134`; `docs/plans/payment-provider-contract.md:93`; `docs/plans/p42a-provider-contract-analysis-2026-11-08.md:55`; `docs/plans/p42a-provider-contract-analysis-2026-11-08.md:59`.

**Khoảng trống vận hành cần chốt:** ai nhận cảnh báo, thời điểm phải xử lý, chứng cứ provider/bank cần thu và cách đóng khoản còn treo; xử lý thiếu principal khác hết credit dịch vụ. UNKNOWN hiện được lên lịch query lại, backoff tối đa 300 giây; không thấy giới hạn số lần/thời gian ở nhánh này để tự giải quyết tình huống treo lâu. Không yêu cầu thêm một màn hình xử lý thủ công nếu dashboard/runbook hiện có đủ, nhưng phải có bằng chứng người vận hành xử lý được. Nguồn: `src/main/java/com/carrent/payment/PayoutTransactions.java:128`; `docs/plans/payment-provider-contract.md:39`; `docs/plans/phase-8-pilot-runbook.md:73`.

**Tiêu chí đạt:** crash/restart/mất response được diễn tập trên bản cuối; attempt/key/đích/nguồn không đổi; query ID/reference khôi phục được khi có kết quả hợp lệ; ca không thể xác định có owner và đường hỗ trợ. GET list theo reference có contract được báo cáo, nhưng query rỗng, uniqueness tuyệt đối và thời gian giữ key chưa được bảo đảm. Nguồn: `src/main/java/com/carrent/payment/PayoutWorker.java:27`; `src/main/java/com/carrent/payment/provider/HttpPayOsPayoutClient.java:126`; `docs/plans/p42a-provider-contract-analysis-2026-11-08.md:53`; `docs/plans/p42a-provider-contract-analysis-2026-11-08.md:55`.

## 4. Gate bắt buộc: đúng bản triển khai, schema và người duyệt

**Còn làm:** chốt source/JAR/manifest cuối; test adapter/recipient/worker/restart và regression chịu ảnh hưởng; full verification và browser khi có thay đổi code; reviewer duyệt đúng bản, baseline schema/migration, nguồn chung hay riêng và policy recovery; sau đó mới cấu hình/kích hoạt đúng cặp pilot và các worker/flags cần dùng theo phạm vi được cho phép. Nguồn: `docs/plans/p42a-p88-completion-analysis-2026-11-08.md:90`; `docs/plans/p42a-p88-completion-analysis-2026-11-08.md:92`; `docs/plans/phase-8-pilot-runbook.md:37`; `docs/plans/phase-8-pilot-runbook.md:62`.

**Không lặp bước đã có chỉ vì đọc plan cũ:** snapshot 08/11 đã có backup/restore local 20/20 bảng và 9/9 constraint cases; schema cần thiết đã tồn tại. Còn review baseline/17 CHECK và kiểm chứng deployment mục tiêu; không có căn cứ chạy lại migration one-shot hoặc bootstrap DB ứng dụng. Đây chưa phải restore production/offsite và chưa được xác minh lại hôm nay. Nguồn: `docs/plans/p42a-p88-completion-analysis-2026-11-08.md:45`; `docs/plans/p42a-p88-completion-analysis-2026-11-08.md:50`; `docs/plans/p42a-p88-completion-analysis-2026-11-08.md:54`; `docs/plans/p42a-p88-completion-analysis-2026-11-08.md:58`.

**Tiêu chí đạt:** reviewer P4.2-A `huy69332`; P8.8/P5.9 `thanhmit` duyệt evidence đúng bản/phạm vi; webhook HTTPS và IP hiện tại đúng; schema tương thích và có phương án phục hồi; flags/provider/scheduler chỉ mở cho scope được duyệt. Tài liệu cũ về port, tunnel, funds hoặc cấu hình không xác nhận runtime hôm nay. Nguồn: `docs/plans/phase-4-payment.md:121`; `docs/plans/phase-8-owner-subscriptions.md:171`; `docs/plans/phase-5-p59-verification.md:3`; `docs/plans/p42a-p88-completion-analysis-2026-11-08.md:69`; `docs/plans/phase-8-pilot-runbook.md:64`.

## 5. Gate bắt buộc: tiền thật đến đúng người và đối soát được

**Còn thiếu:** evidence giao dịch được người dùng chủ động xác định khoản tiền/kênh/đích, bao phủ nguồn Owner và Admin cần dùng. Có receipt/refund READY/PROCESSING hoặc webhook replay chưa chứng minh người nhận được tiền. SePay cần đối chiếu cùng giao dịch giữa numeric webhook.id và API v1.id/account/sub-account/chiều/amount/thời gian; chưa nhất thiết phải viết thêm polling client chỉ để làm một lần đối chiếu. Nguồn: `docs/plans/phase-4-payment.md:133`; `docs/plans/phase-4-payment.md:138`; `docs/plans/p42a-p88-completion-analysis-2026-11-08.md:23`; `docs/plans/p42a-p88-completion-analysis-2026-11-08.md:94`.

**Tiêu chí nghiệm thu hoàn tiền:** lookup thật → khách xác nhận đúng tên/đích → một attempt bền vững → payOS transaction SUCCEEDED đúng amount/BIN/STK/reference/nguồn → đối soát tiền vào tài khoản nhận; không chi trùng sau retry/restart/reconcile. Lưu environment, channel/chủ thể, provider IDs đã che dữ liệu nhạy cảm, thời gian, amount, receipt/refund/attempt và kết quả đối soát. Nguồn: `docs/plans/phase-5-p59-verification.md:24`; `docs/plans/p42a-p88-completion-analysis-2026-11-08.md:94`; `src/main/java/com/carrent/payment/provider/HttpPayOsPayoutClient.java:170`.

Để đóng luôn P8.8 còn phải có **hai giao dịch ngân hàng khác nhau cùng order**, chỉ cấp/gia hạn một lần và refund phần trùng từ Admin; order late không cấp gói và hoàn đủ. P5.9 còn nghiệm thu hành trình mua gói/thuê/30–70 và các khoản hoàn cần dùng; đóng P4.2-A không tự đóng P5.9 hoặc toàn hệ thống. Nguồn: `docs/plans/phase-8-owner-subscriptions.md:175`; `docs/plans/phase-8-pilot-runbook.md:65`; `docs/plans/phase-8-pilot-runbook.md:66`; `docs/plans/phase-5-p59-verification.md:9`; `docs/plans/phase-5-p59-verification.md:16`.

## 6. Payment Settings là hạng mục riêng

S1 Payment Settings trong tài liệu là **preimplementation**, chuẩn bị store credential mã hóa/resolver trong worktree riêng; nó không phải verifier lookup. Checkout gốc hiện còn inject `ProviderPaymentProperties` trực tiếp ở recipient/config. Hoàn thành form nhập khóa, encrypted store hay signed GET test chưa chứng minh có quyền chi hoặc tiền hoàn thật. Nếu mục tiêu thêm cả self-service quản lý credential thì nghiệm thu PS-01–08 riêng; không biến tính năng này thành lý do chờ trước khi xử lý các gate provider bắt buộc của refund. Nguồn: `docs/plans/payment-settings-S1-preimplementation-2026-11-08.md:3`; `docs/plans/payment-settings-S1-preimplementation-2026-11-08.md:11`; `src/main/java/com/carrent/payment/RefundRecipientTransactions.java:24`; `docs/plans/payment-settings-sessions-2026-11-08.md:106`.

## Các snapshot dễ hiểu nhầm

| Nội dung | Cách đọc đúng hiện tại |
| --- | --- |
| P4.2-A ghi “chưa triển khai” | Đó là checkpoint tách task cũ; setup/mapping/GET check đã có theo báo cáo 07–08/11, nhưng chưa đủ đóng chín mục. Nguồn: `docs/plans/phase-4-payment.md:123`; `docs/plans/p42a-p88-completion-analysis-2026-11-08.md:78`. |
| P5.9/runbook ghi chưa backup/migration | Không dùng để phủ nhận restore local mới hơn; cần review baseline/deployment mục tiêu, không chạy lại migration mù. Nguồn: `docs/plans/phase-5-p59-verification.md:72`; `docs/plans/p42a-p88-completion-analysis-2026-11-08.md:54`; `docs/plans/p42a-p88-completion-analysis-2026-11-08.md:58`. |
| Contract “chưa tìm được lookup payOS” | Không có lookup độc lập trong contract payOS đã đọc, nhưng VietQR có schema; entitlement/migration vẫn chưa rõ. Nguồn: `docs/plans/payment-provider-contract.md:56`; `docs/plans/p42a-provider-contract-analysis-2026-11-08.md:9`; `docs/plans/p42a-provider-contract-analysis-2026-11-08.md:10`. |
| UI-03 đã hoàn thiện, có browser/mock verifier | Chứng minh giao diện/guards, không phải lookup thật hoặc live payout. Nguồn: `docs/plans/ui-03-handoff.md:38`; `docs/plans/ui-03-handoff.md:93`. |
| Test full cũ 1053/1053 | Là evidence lịch sử; code/provider thay đổi thì cần kiểm chứng bản cuối. Không gọi đó là lượt chạy hôm nay. Nguồn: `docs/plans/phase-5-p59-verification.md:3`; `docs/plans/phase-5-p59-verification.md:25`. |

## Thứ tự thực hiện ít việc nhất

1. Chốt lookup được cấp quyền và capability payout; đồng thời quyết định nguồn Owner/Admin chung hay riêng và phạm vi pilot.
2. Thêm một verifier production/test theo contract, chỉ sửa payout mapping khi có bằng chứng cần; chọn giữ UNKNOWN/query-only nếu chưa đủ bảo đảm replay.
3. Nghiệm thu kỹ thuật trên bản cuối và DB riêng; review baseline/schema, recovery, source/JAR và cấu hình mục tiêu.
4. Kích hoạt scope được duyệt, người dùng thực hiện giao dịch thật, đối soát cả hai nguồn và các nhánh duplicate/late/rental cần dùng.
5. Reviewer xác nhận từng evidence rồi cập nhật P4.2-A/P8.8/P5.9 tương ứng; không suy diễn task khác tự hoàn tất.

Thứ tự này theo đường nghiệm thu hiện có, không phải yêu cầu viết lại kiến trúc. Nguồn: `docs/plans/p42a-p88-completion-analysis-2026-11-08.md:88`; `docs/plans/p42a-p88-completion-analysis-2026-11-08.md:95`.

Chưa kiểm tra live provider, DB/runtime, credentials, test hoặc browser trong lượt nghiên cứu này. Rủi ro phải giữ mở: entitlement lookup, quyền/điều kiện POST, nguồn chung hay riêng và khả năng khoản UNKNOWN kéo dài chưa có bằng chứng xử lý vận hành.
