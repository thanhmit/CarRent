> **CANCELLED - 2026-11-09:** Chủ dự án hủy Payment Settings S1/S2/S3 và chọn source trước S1. Nội dung bên dưới chỉ là lịch sử thiết kế/kiểm chứng, không phải kế hoạch đang thực thi. Không tiếp tục implement, migrate hoặc kích hoạt từ tài liệu này.

# Payment Settings S1 — bàn giao implementation

Ngày 09/11/2026. **Trạng thái: S1 COMPLETE — implementation và toàn bộ tiêu chí local đã kiểm chứng.** S2/S3 chưa bắt đầu. Thời gian từ checkpoint bắt đầu S1 đến verification: **3.993 giây, khoảng 66,6 phút**.

## Phạm vi đã triển khai

- Schema/bootstrap/migration một bảng `payment_channel_credentials`, PK/FK theo channel, ciphertext/nonce, status, encryption version và optimistic version.
- `PaymentChannelCredential`, repository provider+reference, `CredentialCipher` và `ChannelCredentialResolver`.
- AES-256-GCM, nonce mới mỗi seal, AAD gắn provider/channel/user/reference; key bên ngoài DB, lỗi không chứa plaintext/cause nhạy cảm.
- Resolver đọc DB trước, chỉ absent-row fallback legacy; malformed JSON, sai key/AAD/tag và lỗi DB không chuyển sang credential cũ.
- QR tạo/reuse/view, webhook verify/shared-secret/gateway, payout resolver/worker preparation và recipient preparation đều đọc resolver.
- ACTIVE credential hỏng trả UNAVAILABLE mà không sửa intent hoặc làm rollback finance; lịch sử RECEIVED/EXPIRED/CANCELLED không query credential.
- Giữ constructor SePay legacy, properties-only tests, pilot/role/enabled guards và các snapshots/reference/idempotency hiện có.
- Cập nhật fixture cleanup/migration boot paths, key wiring riêng cho fixture; không đổi provider transport, lookup production hoặc nghiệp vụ BEFORE.
- Cách ly ba suite `CarDateSearchIntegrationTest`, `CarCommandTransactionTest`, `CarQueueHttpIntegrationTest` bằng `IsolatedIntegrationDatabase` + DynamicPropertySource; kiểm tra schema UUID trước khi tạo fixture, `@DirtiesContext` để Spring quản lý context. Thêm test xác minh bootstrap 21 bảng và cleanup idempotent.

## Interfaces cho S2

```java
CredentialCipher(String masterKeyBase64)
boolean available()
CredentialCipher.Sealed seal(byte[] plaintext, String aad)
byte[] open(CredentialCipher.Sealed value, String aad)
record CredentialCipher.Sealed(byte[] nonce, byte[] ciphertext)

Optional<PaymentChannelCredential> findByProviderAndReference(PaymentProvider provider, String reference)
ProviderPaymentProperties.PayOsChannel payos(String reference)
ProviderPaymentProperties.SePayChannel sepay(String reference)
```

`ChannelCredentialResolver.Unavailable` là lỗi cấu hình dự kiến, message cố định, không cause. Hai public resolver methods REQUIRED/readOnly với `noRollbackFor=Unavailable.class`; caller chỉ bắt lỗi này để trả trạng thái không khả dụng. Lỗi repository/SQL vẫn propagate.

Property: `carrent.payment.credentials.master-key=${CARRENT_PAYMENT_CREDENTIAL_MASTER_KEY:}`. Key Base64 đúng 32 byte; thiếu/sai key không ngăn bean khởi động nhưng chặn crypto. Record DB disabled vẫn đọc được cho shared/late webhook; không dùng READ_VERIFIED để tự bật kênh.

## Bằng chứng hiện có

Artifact nằm dưới `.scratch/payment-settings-20261108/` ở checkout gốc:

| Lượt | Kết quả | Log |
| --- | --- | --- |
| Baseline source mới | 97/97 Java, 0 fail/error/skip | `S1-baseline-tests.log` |
| Store compile RED | Thiếu class mới, ghi riêng; không coi là behavioral RED | `S1-store-red.log` |
| Store behavioral RED | Migration chưa tồn tại và cipher stub chưa mã hóa; 16 failures, 11 errors | `S1-store-behavior-red.log` |
| Store GREEN | 81/81 | `S1-store-green.log` |
| Resolver RED | 35 ca: 30 failures, 5 errors khi vẫn đọc map legacy | `S1-resolver-red.log` |
| Resolver GREEN | 56/56 gồm resolver, binding và verifier | `S1-resolver-green.log` |
| Focused aggregate | 332/332, 0 fail/error/skip, source trung gian trước các bổ sung test cuối | `S1-focused-aggregate.log` |
| Node | 18/18, 0 fail/skip | `S1-node-tests.log` |
| Full lượt đầu | Chủ động dừng sau 513 test/26 suites; không dùng để nghiệm thu vì ba suite cũ dùng datasource ứng dụng | `S1-full-verify-interrupted.log` |
| Cách ly lần đầu | 3 lỗi callback teardown do đóng context thủ công; đã sửa để Spring quản lý context | `S1-isolation-green.log` |
| Cách ly rerun | 91/91, 0 fail/error/skip, ghi fixture vào schema riêng | `S1-isolation-rerun.log` |
| Full source cuối | **1.230/1.230, 80 suites, 0 failures/errors/skips; clean verify + executable JAR** | `S1-full-verify.log` |

`S1-acceptance-matrix.json` liệt kê các nhóm thành công/lỗi/biên. Có **81 test mới** trong 4 suites: cipher 22, migration 19, resolver/integration 39 và isolated-database 1. `S1-final-source-manifest.json` ghi 340 file và đã khớp hoàn toàn sau full run. HTTP PayOS bị chặn tại MockRestServiceServer, không có chuyển tiền thật.

Audit tự động sau full (`S1-verification-summary.json`): hash dữ liệu **20/20 bảng ứng dụng không đổi trong lượt full cuối**; không có schema fixture bổ sung, giữ nguyên 9 schema đã có; root index/.env và DB-only configs giữ nguyên checkpoint, worktree index giữ nguyên, không có `.env` trong worktree. JAR chứa đủ 4 lớp production mới và không đóng gói private `.env`/`application-local.properties`. Self-review theo caller/security/transaction được ghi riêng trong `S1-code-review.json`.

**Sự cố verification được giữ nguyên bằng chứng:** lượt full đầu đã chạy ba suite cũ trên datasource mặc định `carrent`; các suite tạo/xóa fixture riêng, không migrate schema ứng dụng. Audit sau dừng không còn car/category/user mang dấu fixture của ba suite (`S1-app-fixtures-after-interruption.json`). Không có fingerprint DB từ trước lượt đầu, nên không tuyên bố đã chứng minh toàn bộ DB không đổi trong lượt đó; không reset auto-increment. Schema UUID mới của PaymentFoundation bị bỏ lại do dừng giữa chừng đã được xác định qua mốc tạo, transaction giả 22001/22002 và 0 kết nối trước cleanup; chín schema có sẵn được giữ nguyên. Lượt full cuối chạy sau khi sửa cách ly; `S1-app-before-final.json` ghi hash dữ liệu 20 bảng để so sau run.

## Baseline và thay đổi song song

- Worktree: `C:/Users/Admin/Documents/hoc/Car/.scratch/payment-settings-20261108/worktree`, branch `feat/payment-settings-ui`, Git base `00a6039`.
- Refresh có kiểm tra hash 31 path trước implementation để giữ BEFORE/calendar; 318 file đóng băng trong `S1-baseline-source-20261109/`. Không xóa snapshot 08/11.
- `S1-feature-files.json` tách các file source/config/migration/readme của S1 khỏi inherited dirty snapshot, gồm cả sửa cách ly test cần thiết cho nghiệm thu. Không stage hoặc commit toàn bộ status.
- Checkout gốc có 9 path thay đổi song song sau snapshot; đã ghi `S1-concurrent-root-drift.json`. Không kéo vào worktree giữa phiên, không ghi đè hoặc nhận là diff của S1. Cần đối chiếu riêng trước tích hợp sau này.
- Root index/.env và cấu hình DB-only khớp checkpoint đã ghi. Runtime 8080 PID 32872 được giữ nguyên sau verification; không restart hoặc cài JAR mới lên ứng dụng đang chạy.
- Audit metadata trước/sau full cuối cùng thấy đúng 9 schema fixture đã tồn tại, danh sách không đổi (`S1-schemas-before-final.json`, `S1-schemas-after-final.json`). Không xóa schema có sẵn hoặc của tiến trình khác.

## Điều kiện vận hành và việc còn thuộc S2

Binary mới cần migration trước khi chạy trên DB ứng dụng; fallback legacy không che lỗi thiếu bảng. Backup DB và master key bảo quản riêng. S1 không chạy migration thật, không tạo master key vận hành, không bật provider/maintenance, không restart app, commit/push/merge hoặc xóa nhánh.

S2 mới triển khai forms/service/transactions/controller/template/menu, PayOS GET check trước lưu và SePay enable có xác nhận. Hãy đọc [SDD](../design/payment-settings-2026-11-08.md) về khóa actor/channel, history adoption, dữ liệu masked và không echo secret. Probe PayOS trước lưu dùng key từ form qua client GET; không nới guard của `PayoutChannelResolver` để probe kênh disabled/chưa tồn tại.

Trong baseline worktree S1, `RefundDestinationVerifier` production vẫn fail-closed; quyền chi và lookup/đối soát thực, schema restore P8.8 và live refund là gate riêng. Không coi hoàn thành S1 là hoàn thành P4.2-A/P8.8 hoặc toàn bộ Payment Settings UI. Checkout gốc có thay đổi song song nên cần đối chiếu riêng khi tích hợp, không ghi đè bằng toàn bộ snapshot của worktree.
