> **CANCELLED - 2026-11-09:** Chủ dự án hủy Payment Settings S1/S2/S3 và chọn source trước S1. Nội dung bên dưới chỉ là lịch sử thiết kế/kiểm chứng, không phải kế hoạch đang thực thi. Không tiếp tục implement, migrate hoặc kích hoạt từ tài liệu này.

# Payment Settings S2 — bàn giao implementation

Ngày 09/11/2026. **S2 COMPLETE trong worktree: PS-C/PS-D đã triển khai và đạt focused verification. S3 chưa bắt đầu.** S1 được giữ lại; chưa tích hợp source vào root hoặc thay runtime 8080.

## Phạm vi đã thực hiện

| Route | Kết quả |
| --- | --- |
| `GET /owner/payment-settings` | PayOS và SePay của Owner hiện tại; metadata/account masked, secret inputs trống |
| `GET /admin/payment-settings` | PayOS của Admin hiện tại; không có form SePay |
| `POST /owner/payment-settings/payos` | Hai GET PayOS có chữ ký hợp lệ trước khi lưu mã hóa |
| `POST /admin/payment-settings/payos` | Cùng luồng, chỉ quản lý channel của Admin hiện tại |
| `POST /owner/payment-settings/sepay` | Lưu BIN/STK/gateway/secret; giữ số 0 đầu; channel mới disabled |
| `POST /owner/payment-settings/sepay/enable` | Xác nhận riêng; kiểm tra Owner/Admin pilot hiện tại, URL HTTPS và credential DB trước bật thu |

Hai trang dùng chung `payment/settings.html`, menu Owner/Admin trong workspace và active section `payment-settings`. Không thêm dependency, JavaScript, polling, schema hoặc migration mới trong S2. `.env.example` đã có `CARRENT_PUBLIC_BASE_URL`, nên không sửa lại.

## Contracts và bảo vệ

- `PaymentSettingsService` có `view`, `savePayos`, `saveSepay`, `enableSepay`; điều phối với `Propagation.NEVER`. Constructor production tạo probe bằng `HttpPayOsPayoutClient.checkConnection`; constructor package-private nhận function để test HTTP qua mock transport.
- `PaymentSettingsTransactions` có `prepare`, hai overload `save`, `view`, `enableSepay`. `Target(Long channelId, String reference, Long credentialVersion, Long ownerId, Role role)` do server tạo, không nhận từ request.
- `View`/`ChannelSummary` đúng SDD §4, chỉ chứa metadata/masked account; không trả entity, credential, reference hoặc plaintext.
- `prepare` giữ khóa ngắn rồi kết thúc transaction trước GET. `save` khóa User → channel, đọc lại role/status/candidate/reference/version; null version chuyển thành row mới trong lúc probe cũng bị từ chối.
- Kênh mới có reference `ui:{userId}:{provider}` và disabled. Nhiều candidate hoặc reference collision bị conflict. Cùng actor có hai first-save đồng thời chỉ một request lưu, request kia 409.
- **Giới hạn bản đầu:** mọi channel hiện hữu chỉ adopt cùng cấu hình đang dùng; không đổi khóa, secret, gateway, BIN/STK qua UI, kể cả khi chưa có history. Đây là nhánh bảo thủ của SDD §5 vì các writer tài chính chưa cùng khóa channel. Không tạo thêm channel để né history; placeholder legacy thiếu credential cần operator xử lý. Cấu hình trùng không tạo thêm channel/credential row; lần kiểm tra và nonce/version có thể được cập nhật.
- Adopt còn từ chối trạng thái channel DB bật nhưng provider legacy đang tắt, tránh việc chuyển credential sang DB vô tình bật khả năng chi/thu. Lưu PayOS không bật chi; lưu SePay không tự bật channel mới. Bật SePay là POST riêng, không thay global flags hoặc channel Admin.
- BIN sáu chữ số, STK String 1–19 chữ số, secret tối đa 512/gateway 50, từ chối control characters. Giữ AES-GCM/AAD, resolver và absent-row legacy fallback của S1; DB credential hỏng không fallback hoặc bị ghi đè.
- Binding allowlist theo form; lỗi trả model sạch, bỏ bound/rejected values. Không echo secret trong HTML/model/flash/Location. GET, POST lỗi và redirect thành công có no-store. Lỗi dự kiến: 400 validation, 403 current actor, 409 stale/config conflict, 502 probe, 503 key/credential unavailable.
- `carrent.payment.public-base-url=${CARRENT_PUBLIC_BASE_URL:}` lấy origin HTTPS từ server. Không dùng Host/Forwarded hoặc URL từ form. Origin thiếu/sai không phát callback sai và không cho enable; vẫn có thể lưu cấu hình hợp lệ. `lastWebhookAt` chỉ query inbox của channel hiện tại.

## Kiểm chứng thực tế

Evidence ở checkout gốc: `.scratch/payment-settings-20261108/implementation/S2/`.

| Lượt | Kết quả | Evidence |
| --- | --- | --- |
| GET RED | 1 failure: route settings 404 thay vì 200 | `PS-C-http-red.log` |
| GET GREEN | 1/1 | `PS-C-http-green.log` |
| Compile fixture lỗi | Helper PayOsSignature package-private; đã thay bằng HMAC cho payload mẫu độc lập. Không coi là RED nghiệp vụ | `PS-C-save-compile-red.log` |
| POST PayOS RED | 16 test, 14 failure do POST chưa có; không error | `PS-C-save-behavior-red.log` |
| PayOS GREEN attempt đầu | 15/16; ca còn lại do test builder thêm apiKey thay vì thay thế, đã sửa input fixture | `PS-C-save-green-attempt1.log` |
| PayOS GREEN / SePay RED | 16/16 PayOS; 18 SePay failure do route chưa có | `PS-C-green-PS-D-red.log` |
| Settings GREEN trung gian | 37/37: PayOS 19, SePay 18, trước regression switch cuối | `S2-focused-green-attempt1.log`, `*-initial-green.xml` |
| Regression switch RED | Cả hai adopt trả 302 dù provider legacy tắt; đã thêm guard cho PayOS/SePay | `S2-disabled-legacy-red.log` |
| **Aggregate source cuối** | **195/195, 12 suites, 0 failure/error/skip; BUILD SUCCESS, 8 phút 55 giây** | `S2-focused-final.log`, `focused-tests.json`, `reports-final/` |

Aggregate cuối gồm hai suite mới **38 tests**: `PaymentSettingsTest` 20, `SePaySettingsTest` 18; cùng `PayOsConnectionCheckTest`, `CredentialCipherTest`, `ChannelCredentialResolverTest`, `SePaySharedAccountTest`, `SePayInboxTest`, `SePayCollectionModeTest`, `PaymentPilotTest`, `SubscriptionPaymentTest`, `SubscriptionOrderTest`, `WorkspaceUiTest`.

Các ca xác minh gồm: own-scope/role/ACTIVE/CSRF/mass assignment; hai GET PayOS thật của adapter trên MockRestServiceServer, signature/403/timeout/zero balance; probe không nằm trong transaction; stale actor/version và hai first-save; missing key/corrupt cipher; encrypted persistence; masked/no-secret response; QR đúng BIN/STK/code trước restart, restart rồi webhook HMAC của DB credential, sai secret/account/gateway bị chặn, replay chỉ một inbox/receipt/apply; Admin legacy/shared routing và shared workspace callers. Không POST payout. Warning SQL `subscription notification failure` trong aggregate thuộc ca rollback cố ý của suite cũ, không phải JUnit failure.

`final-test-source-manifest.json` ghi **340 path source/test/resources/bootstrap/example** cho aggregate, đã kiểm tra không drift. Chỉ đếm/copy XML của đúng 12 suite được chọn; không cộng các report S1 cũ còn trong target. Node không chạy lại vì S2 không sửa JS/polling; full clean verify/JAR/browser thuộc S3.

## Bảo toàn và Git

- Worktree `C:/Users/Admin/Documents/hoc/Car/.scratch/payment-settings-20261108/worktree`, branch `feat/payment-settings-ui`, HEAD `00a60396f2153dfb697e82f6b84a5bd743daf8dc`.
- Baseline đầy đủ ở `baseline.json`; `S2-feature-files.json` và `S2-verification-summary.json` tách feature khỏi inherited dirty snapshot.
- Hash dữ liệu **20/20 bảng ứng dụng không đổi** từ trước các lượt S2 đến sau aggregate. Danh sách **9 schema fixture có sẵn** trước/sau giống nhau; không còn schema bổ sung do lượt này để lại, không xóa schema có sẵn.
- Các file root có trong baseline, private files và HEAD/index của hai checkout được giữ nguyên; mọi source S2 chỉ nằm trong allowlist của worktree. Audit phát hiện root có thêm tài liệu `docs/analysis/huy-functional-data-flows-2026-11-09.md` trong lúc thực hiện, ngoài phạm vi S2; giữ nguyên và ghi drift riêng, không nhận là feature diff hoặc tuyên bố toàn bộ root status không đổi. Root vẫn ở nhánh/commit riêng của nó; S2 chưa được copy sang root. Runtime 8080 vẫn PID **32872**, bind `127.0.0.1`; không restart/deploy.
- Ảnh `uploads/seed-car-5.png` do người dùng chủ động xóa, được giữ nguyên. Không stage/commit/push/merge hoặc xóa branch/worktree.
- `started_at`, `completed_at`, elapsed wall time nằm trong verification summary; wall time gồm các khoảng ngắt/tiếp tục của phiên, không phải số đo active-only. Không tuyên bố đã đo được mốc 80 phút thực thi.

Self-review inline đã đối chiếu caller, dữ liệu vào/ra, transaction, lock/version, fallback, secret và phạm vi provider. Finding switch legacy đã có RED/GREEN; review này không thay human review hoặc bằng chứng vận hành.

## Đầu vào S3 và giới hạn

S3 tiếp tục nguyên worktree này: full clean verify/JAR, Node, browser Owner/Admin/Customer ở 1440/390/320, keyboard/focus và kiểm tra secret trong DOM, audit và bàn giao toàn tính năng. JAR còn trong target từ S1 không phải artifact phát hành S2; S2 chỉ chạy lifecycle `test`.

Trước khi chạy binary mới trên DB ứng dụng vẫn cần review/backup và migration bảng credential của S1, master key được operator quản lý và public origin phù hợp. S2 không chạy migration, không tạo key vận hành, không bật provider/maintenance thật và không chuyển/đối soát tiền thật.

Root có lookup hoàn tiền và các thay đổi cấu hình/test riêng sau baseline S1; không kéo chúng vào S2. Việc tích hợp root sau này phải merge từng path rồi kiểm chứng đúng source tích hợp. Lookup production ở baseline worktree vẫn fail-closed như bàn giao S1.

**P4.2-A/P8.8/P5.9, số task 83/86, human review và live acceptance giữ riêng.** S2 complete không có nghĩa toàn bộ Payment Settings hoặc hệ thống đã nghiệm thu 100%.
