# P4.2-A/P8.8 — bàn giao triển khai 2026-11-09

## Phạm vi và trạng thái

Đã triển khai phần lookup người nhận còn thiếu trong code và chuẩn bị đường nghiệm thu không chuyển tiền. **P4.2-A/P8.8 chưa complete100%** vì chưa có entitlement lookup, quyền chi/ngân sách/phạm vi nguồn được xác minh và giao dịch thật đối soát đủ hai luồng. Không cộng task hoàn thành từ test local.

Kế hoạch: [implementation](p42a-p88-implementation-2026-11-09.md). Contract: [nguồn chính thức được kiểm tra lại](p42a-p88-implementation-contract-2026-11-09.md). Vận hành: [hướng dẫn và file private](../p42a-p88-operator-guide-2026-11-09.md).

## Mỗi khoản hoàn phải được xác minh và xác nhận

1. Người nhận mở đúng refund; tài khoản mặc định chỉ điền sẵn BIN/STK. Mỗi user có tối đa một bản ghi tài khoản mặc định, được lưu khi opt-in; không tự tạo quyền chi.
2. Preview gọi lookup tên chính thức cho đúng BIN/STK, chưa ghi READY hay tạo payout.
3. Người nhận kiểm tra tên, tick xác nhận rồi submit. Backend lookup lại, yêu cầu tên khớp và kiểm tra lại người nhận, receipt, source, credentials reference, trạng thái và attempt dưới khóa DB.
4. Hợp lệ mới lưu snapshot trên refund, đặt READY và deadline lần đầu +1giờ; thay tài khoản sau đó không reset deadline. Lỗi/tên đổi phải xem lại.
5. Worker độc lập chỉ dispatch khi nguồn/cấu hình được kích hoạt đúng scope. READY/PROCESSING chưa chứng minh đã nhận tiền. Owner/Admin hiện không có bước duyệt riêng từng refund trong UI; điều kiện OTP/auto-approval của provider cần evidence thực tế.

## Thay đổi kỹ thuật

- `RefundLookupProperties` và `HttpVietQrRefundDestinationVerifier`: credentials độc lập, fixed HTTPS `/v2/lookup`, không redirect; connect5s/read10s. BIN6 số, STK numeric6–19 giữ0 đầu. Chỉ HTTP200/code00/data.accountName hợp lệ mới pass; không trả/log raw provider error hoặc credential.
- `PaymentProviderConfiguration`: chỉ chọn adapter khi bật lookup rõ ràng; mặc định fail-closed. Không dùng khóa payOS thay khóa lookup, không gọi payout để tìm tên.
- Profile `refund-lookup-check`: loopback/port0, SQL-init never/DDL none, loại financial schedulers bằng profile; runner từ chối effective scheduler flags bật. Chỉ SELECT metadata nguồn PAYOS/PAYOUT rồi lookup/so tên private; không cần bật source hay tạo refund/attempt. Context tự đóng khi diagnostic xong.
- Fixture buộc lookup disabled khi không có verifier riêng, tránh chạy test với khóa thật trong local config. Không thêm dependency, schema, endpoint duyệt tiền hoặc retry cho UNKNOWN.
- Runbook có người xử lý/escalation/evidence cho UNKNOWN/PROCESSING/REVIEW_REQUIRED và source disabled; không tự mark success/resend khi chưa có guarantee NOT_SENT.

## Bằng chứng local

Mọi ca HTTP lookup thành công trong test/browser dùng mock HTTP provider; không phải lookup được cấp quyền hay chuyển tiền thật.

| Kiểm chứng | Kết quả và evidence |
| --- | --- |
| Full isolated `clean verify` | **1.207/1.207 JUnit**,79lớp,0failure/error/skip; BUILD SUCCESS,22:45phút. `clean-verify.log`, `clean-verify-reports/` giữ nguyên kết quả aggregate trước test bổ sung từ review. |
| Config sau review | **8/8**,0failure/error/skip, `review-config-test.log`. Chỉ bổ sung một test enabled bean/binding; production source không đổi sau aggregate. Không ghi1.208 thành một lần chạy full suite mới. |
| Node | **18/18**, `node-tests.log`. |
| Chrome | **182 kiểm tra,33PNG**,1440/1024/390/320px và no-JS; `browser/report.json`. Preview, confirm, HTTP429, tên đổi, READY chưa chi, deadline+1h, Admin masking, IDOR/CSRF, polling;0JS error. |
| SQL/browser |14khoảng audit trên20bảng; preview/lỗi/read không đổi DB; confirm Customer chỉ đổi `refunds` + `refund_recipient_accounts`, Owner chỉ đổi `refunds`. Kiểm tra amount/recipient/source/snapshot/READY/deadline; `read-post-audit.json`. |
| Provider fake |8lần gọi HTTP lookup adapter, **0payout call**; `browser/provider-calls.json`. |
| Cleanup | Fixture schema trong metadata đã drop, context đóng, port63682 không còn listener, file credentials tạm bị xóa; `browser/cleanup.json`, `schema-cleanup-audit.json`. |

Đã xem trực tiếp7PNG tiêu biểu desktop/mobile/no-JS: form/tên được xác minh, lỗi lookup, tên đổi, READY và Admin che STK. No-JS vẫn preview được; styling Tailwind CDN phụ thuộc JavaScript như baseline. Không coi kiểm tra tự động hoặc việc xem ảnh này là human approval.

Scratch riêng: `.scratch/p42a-p88-implementation-20261109/`. POM tách output, dùng nguyên source/test/resources của root và DB fixture UUID; không dùng root target để thay binary của app đang chạy.

JAR: `C:/Users/Admin/Documents/hoc/Car/.scratch/p42a-p88-implementation-20261109/build/carrent-0.0.1-SNAPSHOT.jar`.

SHA-256: `b5525a0f44f7c231e0d6c83492ce9e4cb8663c412bba7af1f69a9166fc08d50d`.

`final-audit.json` xác nhận JAR khớp classes, resources khớp root, không chứa `.env`/private local config/test classes; source hashes được ghi để review. Không có đường dẫn thay đổi ngoài phạm vi so với baseline; HEAD/branch/index và hashes `.env`, `application-local.properties` giữ nguyên. Runtime8080 vẫn PID32872; không restart. Read-only comparison giữ nguyên refund aggregates và channel mapping; không phải full snapshot toàn DB ứng dụng.11schema fixture nhìn thấy ngoài metadata task không được xác định sở hữu, số lượng trước/sau không đổi và không xóa chúng.

Các lần lỗi được giữ riêng: RED config thất bại đúng do chưa chặn enabled thiếu khóa; RED profile isolation thất bại đúng do chưa exclude profile. RED HTTP còn có stub unavailable/error và lỗi import JUnit đã sửa. Focused lần đầu lỗi compile import `content`; lần rerun **88 test/0 failure/1 error** do seed COLLECT thiếu bank fields, đã sửa seed trước full verify. Không gọi các lần này là một lần chạy aggregate sạch.

Review độc lập không có Critical/Important. Một góp ý Minor về enabled configuration coverage đã được xử lý bằng test binding/bean với lookup-only keys, không payOS keys/network. Agent review không thay human review nguồn/JAR/schema hoặc nghiệm thu tài chính.

## Provider và dữ liệu hiện tại

- GET public contract VietQR/payOS thành công, manifest10nguồn và hashes ở `contract-public/`; quyền `/v2/lookup` trong giai đoạn CAS vẫn cần xác nhận theo tài khoản.
- Lookup client-id/API-key và private test destination chưa được điền. Không gọi authenticated lookup.
- Signed GET payOS `/v1/payouts?limit=1&offset=0` và `/v1/payouts-account/balance` đều **HTTP403** ngày09/11, cả Owner/Admin reference dùng chung credentials. Chưa chứng minh nguyên nhân; không kết luận sai khóa hoặc chắc chắn do allowlist. Check cũ thành công không thay kết quả hiện tại.
- Read-only mapping: Owner user2/PAYOUT channel1; Admin user1/PAYOUT channel2. Hai PAYOUT source tắt, hai COLLECT source bật; không có refund ứng dụng lúc probe. Hai reference không chứng minh hai ví độc lập.
- PayOS adapter vẫn không phát sinh NOT_SENT; UNKNOWN query-only. Chưa có official minimum5.000đ; giá Small5.000đ và sample estimate-credit không phải evidence minimum payout.

## Các trường còn thiếu để đóng gate

Không cần thêm cột DB cho thay đổi này. Điền private tại [operator guide](../p42a-p88-operator-guide-2026-11-09.md); không gửi secret, STK/tên người nhận hoặc OTP trong chat.

| Đầu vào còn thiếu | Nơi cung cấp |
| --- | --- |
| Entitlement lookup áp dụng cho `/v2/lookup`; nếu chỉ có CAS cần contract thật tương ứng | `lookupEntitlementEvidencePath` |
| Lookup credentials riêng | `CARRENT_REFUND_LOOKUP_CLIENT_ID`, `CARRENT_REFUND_LOOKUP_API_KEY` trong `operator-lookup.properties` |
| Đích kiểm tra được phép: source ID, BIN, STK, tên chuẩn kỳ vọng | Bốn trường `CARRENT_REFUND_LOOKUP_CHECK_*` trong cùng file |
| Quyền truy cập payOS sau HTTP403; POST/auto-approval/OTP; phí/min/max/principal/credit; scope nguồn chung hiện tại | `payoutPermissionApprovalModeFeesLimitsAndFundingEvidencePath`, `fundingSourcePolicy` và evidence tương ứng |
| Người nhận Owner và Customer cho hai luồng | `ownerRecipientPrivateConfigPath`, `customerRecipientPrivateConfigPath` |
| SePay webhook↔API v1 đúng cùng giao dịch | `sepayWebhookV1EqualityEvidencePath` |
| Khung nghiệm thu, tổng debit/phí tối đa và người dùng chủ động chuyển tiền | `liveAcceptanceWindow`, `maximumTotalDebitVnd`, `maximumTotalFeesVnd`, `userInitiatesBankTransfers` |
| Review đúng source/JAR/schema/restore và scope kích hoạt cụ thể | `reviewer`, `sourceJarSchemaReviewEvidencePath`, `liveExecutionApproved` |

ID Owner/Admin/channel đã điền sẵn; cấu hình nguồn chung được ghi nhận là scope chưa xác minh, không yêu cầu nhập lại khóa payout hiện có. Blank/null hoặc tự đánh dấu true không thay provider/reviewer evidence. Hai file operator được Git ignore; biểu mẫu không có code tự enable payout.

Sau khi đủ input: chạy diagnostic lookup riêng → review đúng manifest/schema/restore → chốt scope/kích hoạt có kiểm soát → người dùng thực hiện hai bank transactions khác ID cùng order và ca late → một entitlement, refund đúng từ Admin → nghiệm thu Owner→Customer → đối soát provider và tiền vào đích, restart/reconcile không chi trùng → reviewer đóng từng checklist. Không replay webhook để thay giao dịch ngân hàng thật.

## Giới hạn bàn giao

Chưa kích hoạt lookup/payout, chưa restart/deploy app8080 và chưa chuyển/đối soát tiền thật. Test/browser tự động không thay human visual, screen-reader hoặc review nghiệp vụ/vận hành.
