> **CANCELLED - 2026-11-09:** Chủ dự án hủy Payment Settings S1/S2/S3 và chọn source trước S1. Nội dung bên dưới chỉ là lịch sử thiết kế/kiểm chứng, không phải kế hoạch đang thực thi. Không tiếp tục implement, migrate hoặc kích hoạt từ tài liệu này.

# S1 — phân tích chi tiết và chuẩn bị implement

> **Nghiệm thu S1 — 09/11/2026:** S1 COMPLETE trong worktree: 1.230/1.230 Java tests (80 suites), 81 test mới, Node 18/18, clean verify/JAR và audit đạt. S2/S3 NOT_STARTED. Xem [bàn giao S1](payment-settings-S1-handoff.md); chỉ ghi nhận local completion, chưa tích hợp root hoặc bật provider thật.

> **Cập nhật 09/11/2026:** đọc [kế hoạch S1 hiện hành](payment-settings-S1-execution-2026-11-09.md) trước khi thực thi. Đã xác minh 31 path source/config công khai khác baseline cũ; phải làm mới baseline có manifest một lần trước S1 để giữ BEFORE/calendar. Bổ sung exception credential riêng và chính sách transaction để ACTIVE UNAVAILABLE không gây rollback-only; chưa implement hoặc chạy test mới.

Ngày **2026-11-08**. Trạng thái: **phân tích/preflight, chưa implement PS-A/PS-B**. Timebox S1 vẫn **80 phút**; các bước chuẩn bị tài liệu và baseline này không được ghi là kết quả tính năng.

## 1. Kết quả cần có sau S1

Một module lưu/đọc credential mã hóa chạy được với MySQL và Spring, đã nối vào các caller hiện có. S2 chỉ cần thêm form/controller/UI, không phải tự thiết kế mã hóa hoặc đổi cách chọn nguồn tiền.

S1 tạo **8 file mới**: một migration, bốn Java production (entity/repository/cipher/resolver), ba test (migration/cipher/resolver). S1 sửa bootstrap/config/fixtures và sáu caller; không tạo UI, không thay payment policy hoặc provider endpoint.

Worktree duy nhất: `C:/Users/Admin/Documents/hoc/Car/.scratch/payment-settings-20261108/worktree`, branch `feat/payment-settings-ui`, HEAD/base `00a6039`. Snapshot 409 file ngày 08/11 được giữ làm lịch sử. Theo checkpoint 09/11, đối chiếu và làm mới baseline bằng allowlist/hash trước S1; không copy root tùy ý hoặc ghi đè thay đổi riêng trong worktree.

## 2. Các điểm phát hiện khi đối chiếu code

| Điểm | Bằng chứng code | Quyết định trước implement |
| --- | --- | --- |
| Repository tìm chỉ reference có thể nhầm | `scriptDB.sql` có UNIQUE `(provider, credential_reference)`, không UNIQUE reference toàn hệ thống | Query credential bằng **provider + reference**, fetch channel/user. Giữ public methods `payos(reference)` và `sepay(reference)` |
| Entity mới ảnh hưởng boot trên schema incremental | `PaymentModelTest` và hai nhánh `RentalLifecycleTest` migrate schema rồi boot Hibernate validate | Thêm migration credential vào các đường boot này; không sửa migration tài chính cũ hoặc lén thêm bảng vào helper legacy |
| Properties-only test không có DB bean | `ProviderPaymentPropertiesTest.contextRunner()` chỉ load PaymentProviderConfiguration và mock payout client | Thêm mock ChannelCredentialResolver cho context này; test binding vẫn không cần DB. Kiểm chứng real wiring riêng bằng full fixture |
| Resolve eagerly có thể làm lịch sử lỗi | `PaymentIntentTransactions.view()` hiện đọc config trước nhánh kiểm tra ACTIVE, cả khi intent đã PAID/EXPIRED | Chỉ resolve cho ACTIVE QR; lỗi resolver đổi khả dụng thành UNAVAILABLE. Receipt/refund/timeline đã có vẫn đọc được |
| Shared webhook cần đọc secret của target disabled | `sharesWebhookSecret` không yêu cầu target enabled, dùng cho QR cũ và routing sibling | Resolver trả typed record disabled có secret hợp lệ; caller quyết định enabled. Không globally từ chối record disabled trước decode |
| Test shared account hiện dùng verifier riêng | `SePaySharedAccountTest` tạo @Primary verifier từ properties | Giữ test constructor legacy; thêm case routing/shared secret lấy từ DB trong ChannelCredentialResolverTest bằng default bean động, không dùng override static để chứng minh DB integration |
| Thiếu master key không được làm legacy app ngừng | Các fixture/app hiện khởi động mà chưa có master key | Cipher tạo bean được khi key thiếu/sai; `available()` false và crypto thao tác bị từ chối. Legacy absent-row vẫn resolve được |

Đây là sửa rõ thiết kế trước triển khai, không phải xác nhận code hiện tại đã có các lỗi runtime của feature chưa viết.

## 3. Phạm vi file chính xác

### PS-A — schema, entity, cipher

**Tạo:**

1. `docs/migrations/2026-11-08-01-payment-channel-credentials.sql`
2. `src/main/java/com/carrent/payment/PaymentChannelCredential.java`
3. `src/main/java/com/carrent/payment/PaymentChannelCredentialRepository.java`
4. `src/main/java/com/carrent/payment/CredentialCipher.java`
5. `src/test/java/com/carrent/payment/PaymentCredentialMigrationTest.java`
6. `src/test/java/com/carrent/payment/CredentialCipherTest.java`

**Sửa:** `scriptDB.sql`, `docs/migrations/README.md`, `src/main/resources/application.properties`, `.env.example`, `src/test/java/com/carrent/payment/PaymentFlowFixture.java`, `PaymentModelTest.java`, `RentalLifecycleTest.java` (hai file sau cùng trong cùng test package).

`SubscriptionModelTest` bootstrap scriptDB nên tự nhận bảng mới, không cần sửa. `RentalModelTest` dùng `RentalLifecycleTest.migratedDatabase()` nên sửa helper chung là đủ. Không thêm bảng mới vào `PaymentMigrationTest.populatedDatabase()`, vì helper đó phải tiếp tục biểu diễn schema cũ để kiểm chứng incremental migration.

### PS-B — resolver và callers

**Tạo:** `src/main/java/com/carrent/payment/ChannelCredentialResolver.java`, `src/test/java/com/carrent/payment/ChannelCredentialResolverTest.java`.

**Sửa production:**

- `src/main/java/com/carrent/config/PaymentProviderConfiguration.java`
- `src/main/java/com/carrent/payment/provider/SePayWebhookVerifier.java`
- `src/main/java/com/carrent/payment/PaymentIntentTransactions.java`
- `src/main/java/com/carrent/payment/PayoutChannelResolver.java`
- `src/main/java/com/carrent/payment/PayoutTransactions.java`
- `src/main/java/com/carrent/payment/RefundRecipientTransactions.java`

**Sửa test wiring:** `src/test/java/com/carrent/payment/ProviderPaymentPropertiesTest.java`. Giữ `SePaySharedAccountTest` hiện có để kiểm chứng backward compatibility; case DB mới đặt ở resolver integration test.

Không sửa `HttpPayOsPayoutClient` hoặc `SePayWebhookService` nếu resolver thay thế đúng seam; test phải chứng minh hai đường này vẫn dùng signature/routing/dedup cũ qua dependency mới.

## 4. Interfaces chốt cho S2

### Store và entity

- Một record cho mỗi PaymentChannel, `channel_id` PK/FK; bảng thứ **21** trong bootstrap mới. DB ứng dụng vẫn 20 bảng trong đợt implement vì không chạy migration lên đó.
- `@OneToOne(fetch=LAZY) @MapsId` đến PaymentChannel; không cascade tạo/xóa channel/user. `@Id Long channelId`, `@Version Long version` nullable khi entity mới để Spring Data nhận diện persist; version lưu DB bắt đầu 0.
- Ciphertext VARBINARY(8192), nonce BINARY(12), format version 1; map binary types/length tường minh để Hibernate validate không coi nonce là VARBINARY(255). Instant dùng UTC/DATETIME(6).
- Không @Data/@ToString hoặc serialize entity. Getter binary chỉ phục vụ crypto/repository nội bộ, không đưa entity vào model/response.
- Repository method `Optional<PaymentChannelCredential> findByProviderAndReference(PaymentProvider provider, String reference)` dùng explicit query:

```java
@Query("""
    select e from PaymentChannelCredential e
    join fetch e.channel c join fetch c.user
    where c.provider = :provider and c.credentialReference = :reference
    """)
Optional<PaymentChannelCredential> findByProviderAndReference(
    @Param("provider") PaymentProvider provider, @Param("reference") String reference);
```

### Cipher

```java
CredentialCipher(String masterKeyBase64)
boolean available()
Sealed seal(byte[] plaintext, String aad)
byte[] open(Sealed value, String aad)
record Sealed(byte[] nonce, byte[] ciphertext)
```

- Key Base64 đúng 32 byte; không tạo key mặc định. Thiếu/sai key: available=false, seal/open báo IllegalStateException thông điệp cố định không có giá trị input/cause chứa secret.
- AES/GCM/NoPadding, nonce SecureRandom 12 byte mỗi seal, tag 128 bit, AAD `v1|provider|channelId|userId|credentialReference` UTF-8. Nonce/ciphertext được copy khi nhận/trả để tránh mutation ngoài ý muốn.
- Cipher không biết DB/provider HTTP/actor. Không log secret hoặc JSON decrypted. Không lưu key trong DB hoặc copy key thật vào fixture.

### Resolver

```java
ProviderPaymentProperties.PayOsChannel payos(String reference)
ProviderPaymentProperties.SePayChannel sepay(String reference)
```

- Public class/method không final nếu dùng Spring transactional proxy; readOnly/REQUIRED, không mở network hoặc thêm khóa ghi.
- Null/blank reference trả null. Với reference hợp lệ: query đúng provider → absent-row mới fallback map legacy của đúng provider. Repository error, malformed JSON, key/tag/AAD lỗi không được trở thành absent-row.
- JSON parse từ byte[] sau decrypt: strict duplicate key/trailing tokens, object shape và đúng field; PayOS ba string không blank <=512; SePay secret không blank <=512 và gateway theo schema lưu, không control characters. Không in JsonNode/exception parser vì có thể chứa plaintext.
- DB-enabled lấy từ channel, không lấy READ_VERIFIED làm quyền bật chi. Disabled channel vẫn trả record với enabled=false để caller giữ policy hiện tại và shared routing đọc secret khi cần.
- Resolver kiểm tra provider/purpose/format/AAD; **không thêm ACTIVE requirement cho mọi lần đọc secret**. Quyền actor/current role/status tiếp tục do UI/service/worker/webhook caller kiểm tra đúng ngữ cảnh, tránh bỏ tiền đến muộn khi user bị khóa.
- Lỗi credential dự kiến dùng nested `ChannelCredentialResolver.Unavailable`, message cố định và không secret/cause chứa plaintext. Resolver REQUIRED/readOnly đặt `noRollbackFor=Unavailable.class`; caller bắt riêng lỗi này theo §6. Repository/DB lỗi vẫn propagate, không fallback hoặc bị catch rộng. Kiểm thử status/finance qua Spring proxy để tránh UnexpectedRollbackException khi hiển thị UNAVAILABLE.
- Không cache plaintext hoặc map DB trong singleton. Không yêu cầu restart sau khi thêm DB record. Legacy properties vẫn immutable, dùng cho absent-row.

## 5. Migration và đường boot phải sửa

Migration chỉ tạo `payment_channel_credentials` theo DDL SDD, không DML bảng cũ; guard MySQL/strict/schema nguồn/PK BIGINT/target/marker trước CREATE. Marker `@carrent_channel_credentials_backup_confirmed=1`, procedure `carrent_channel_credentials_v1`. Không USE/CREATE DATABASE, không bootstrap sửa app DB.

`PaymentCredentialMigrationTest` cung cấp helper package-private:

```java
static void migrate(PaymentTestDatabase db, boolean confirmed) throws Exception {
    db.execute("SET @carrent_channel_credentials_backup_confirmed=" + (confirmed ? "1" : "NULL"));
    db.run(Files.readString(Path.of("docs/migrations/2026-11-08-01-payment-channel-credentials.sql"))
        .replaceAll("(?m)^DELIMITER[^\\r\\n]*", ""), "$$");
}
```

Gọi helper trước boot Hibernate validate ở:

1. `PaymentModelTest.repositoriesRoundTripFinancialSnapshotsAndUtcInstantsOnTheMigratedSchema()` sau subscription migration.
2. `RentalLifecycleTest.bootstrapMatchesMigratedRentalSchemaAndJpaMapsExpired()` trên `migrated` trước boot.
3. `RentalLifecycleTest.migratedDatabase()` trước return để RentalModelTest dùng schema đủ entity.

Fixture dùng bootstrap nhận bảng mới tự động. `PaymentFlowFixture.reset()` xóa credential trước payment_channels. Không viết helper tự migrate lúc application boot; deployment sau này phải áp migration trước JAR mới có entity.

## 6. Chính sách lỗi theo caller

| Caller | Hành vi khi DB credential hỏng/thiếu key |
| --- | --- |
| Tạo/reuse intent | BusinessException generic, không tạo hoặc tái sử dụng QR sai cấu hình |
| `PaymentIntentTransactions.view` intent ACTIVE | View trả UNAVAILABLE, không render QR; vẫn dựng receipt/refund metadata, không sửa persisted intent/status |
| View intent không ACTIVE | Không cần resolve để hiển thị lịch sử; không lỗi 500 chỉ vì secret không giải mã được |
| SePay verify/shared secret/gateway | Từ chối xác thực/routing, không ACK thành công hoặc ghi tiền từ payload chưa được xác minh; không đổi HMAC/timestamp semantics |
| `PayoutChannelResolver.resolve` | IllegalStateException generic, không network |
| Payout prepare/sourceReady | Không gửi khi source chưa sẵn sàng; attempt/UNKNOWN cũ giữ nguyên, không tạo key/reference mới |
| Recipient prepare/confirm | BusinessException generic, không đặt đích hoặc deadline khi kênh không sẵn sàng |

Resolver không nuốt exception để tự fallback. Caller có thể chuyển lỗi credential thành unavailable/false/BusinessException theo bảng; không log message/cause chứa key/plaintext. Các truy vấn receipt/refund khác vẫn có error handling hiện tại, không catch rộng toàn flow để giấu lỗi DB.

## 7. Ma trận test tối thiểu S1

| Nhóm | Case bắt buộc |
| --- | --- |
| Migration | Marker thiếu; target đã có/partial; PK nguồn không tương thích; strict mode; bootstrap parity; toàn bộ dữ liệu legacy/core không đổi; FK RESTRICT; entity validate và UTC/version/binary roundtrip |
| Cipher | Roundtrip; nonce khác mỗi seal; sai AAD/user/channel/reference; tag sai; wrong key; key trống/sai Base64/sai độ dài; không lộ plaintext trong toString/error/DB |
| Resolver | Legacy absent-row; DB ưu tiên; cùng reference ở PAYOS và SEPAY vẫn tách đúng; missing key/corrupt JSON/tag không fallback; disabled không thành enabled; reads sau insert/restart không dùng map stale |
| Wiring | Properties-only context không cần DB; legacy constructor verifier còn dùng được; full fixture thực sự inject resolver động |
| QR/lịch sử | DB credential tạo và xem ACTIVE QR; cấu hình hỏng làm ACTIVE UNAVAILABLE; PAID/EXPIRED vẫn xem history; giữ source snapshot và pilot |
| Shared webhook | DB/legacy cùng account và secret được route đúng; target disabled nhận tiền mã cũ qua sibling hợp lệ; khác secret/gateway không route; replay numeric ID không trùng |
| Refund/payout | Đọc DB credential đúng nguồn Owner/Admin; không đổi account/amount/reference/idempotency/signature; disabled không send; UNKNOWN chỉ query |

Không áp một con số test dự kiến làm điều kiện đạt; parameterized cases có thể cho số lượng khác nhau. Test mới dùng fixture keys giả, schema UUID riêng, không provider live.

Chạy focused theo từng nhánh rồi aggregate một lần trước bàn giao S1:

```powershell
./mvnw.cmd -o '-Dmaven.compiler.useIncrementalCompilation=false' '-DargLine=-Xmx768m' '-Dtest=CredentialCipherTest,PaymentCredentialMigrationTest,ChannelCredentialResolverTest,ProviderPaymentPropertiesTest,PaymentModelTest,RentalLifecycleTest,RentalModelTest,SubscriptionModelTest,SePayWebhookVerifierTest,SePaySharedAccountTest,PaymentIntentTest,RefundRecipientTest,PayoutWorkerTest,PayOsPayoutClientTest' test
```

Đây là lệnh cho **sau implement**; các test mới chưa có. Không chạy lệnh này bây giờ rồi coi lỗi thiếu class là feature failure. Full clean verify/browser để S3.

## 8. Thứ tự 80 phút và đầu ra bàn giao

| Phút | Việc |
| --- | --- |
| 0–5 | Kiểm tra branch/manifest, đọc contracts S1; ghi thời điểm bắt đầu |
| 5–18 | Migration/bootstrap/entity/repository và boot-path fixtures |
| 18–32 | Cipher + RED/GREEN + property/example |
| 32–40 | Migration/cipher/JPA focused, sửa binary/version/UTC nếu lỗi |
| 40–58 | Resolver provider-scoped, wiring và đủ caller/error policies |
| 58–73 | Integration/restart/legacy/shared/history/payout focused aggregate |
| 73–80 | Source/evidence manifest, thời gian thực tế, S1 handoff cho S2 |

S1 handoff phải có interfaces đã implement, file diff so baseline, test logs/số thực tế, missing-key behavior, state source/DB/private/index và phần chưa đạt. Chỉ đánh S1 COMPLETE khi schema/entity/cipher/resolver/callers đều có bằng chứng; không chuyển một backend còn thiếu sang S2 để giữ mốc giờ.

## 9. Kết luận chuẩn bị

S1 không phụ thuộc quyền lookup hoặc payout thật, nên có thể bắt đầu bằng source và fixture hiện có. Những thay đổi thiết kế ở §2 đã được đưa lại vào SDD và implementation plan để S2/S3 không dùng interface cũ. Rủi ro thời gian chính là JPA/schema fixture và error handling của lịch sử; không phải chờ tài khoản provider.

Baseline ngày 08/11: focused 96/96 đã ghi ở worktree handoff. Lượt chuẩn bị ngày 08/11 chạy thêm **49/49 test, 5 suites, 0 failure/error/skip** gồm ProviderPaymentPropertiesTest, PaymentModelTest, RentalLifecycleTest, RentalModelTest, SubscriptionModelTest. Kết quả riêng nằm ở `.scratch/payment-settings-20261108/S1-preflight-tests.json` và `.log`, không cộng thành full suite hoặc gọi là nghiệm thu S1. Chưa chạy lại trên baseline cần refresh ngày 09/11.

Tham chiếu: [SDD](../design/payment-settings-2026-11-08.md), [plan](payment-settings-implementation-2026-11-08.md), [phân phiên](payment-settings-sessions-2026-11-08.md), [worktree](payment-settings-worktree-2026-11-08.md).
