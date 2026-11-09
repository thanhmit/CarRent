> **CANCELLED - 2026-11-09:** Chủ dự án hủy Payment Settings S1/S2/S3 và chọn source trước S1. Nội dung bên dưới chỉ là lịch sử thiết kế/kiểm chứng, không phải kế hoạch đang thực thi. Không tiếp tục implement, migrate hoặc kích hoạt từ tài liệu này.

# Payment Settings S1 Implementation Plan — cập nhật 09/11/2026

> **Nghiệm thu S1 — 09/11/2026:** S1 COMPLETE trong worktree: 1.230/1.230 Java tests (80 suites), 81 test mới, Node 18/18, clean verify/JAR và audit đạt. S2/S3 NOT_STARTED. Xem [bàn giao S1](payment-settings-S1-handoff.md); chỉ ghi nhận local completion, chưa tích hợp root hoặc bật provider thật.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans. Thực hiện tuần tự, đánh dấu checklist theo bằng chứng; không tự dùng subagent hoặc commit. Tài liệu này bổ sung và thay thế các chỉ dẫn baseline đã cũ trong kế hoạch 08/11.

**Goal:** Hoàn tất PS-A/PS-B: credential mã hóa lưu bền trong MySQL, resolver DB/legacy và mọi caller hiện có; S2 có thể xây UI trên nền đã kiểm chứng.

**Architecture:** Giữ `payment_channels`; thêm một bảng credential theo channel. Dùng JDK AES-GCM và một resolver hai phương thức để tập trung việc đọc DB, giải mã và fallback. Tái sử dụng kiểu `ProviderPaymentProperties.PayOsChannel/SePayChannel`; không thay provider transport hoặc nghiệp vụ thanh toán.

**Tech Stack:** Java 21, Spring Boot 4.1.1, Spring Data JPA, MySQL 8.0.16+, JUnit/MockMvc và Maven wrapper hiện có; không thêm dependency.

**Spec:** [SDD](../design/payment-settings-2026-11-08.md), [S1 contracts/file list](payment-settings-S1-preimplementation-2026-11-08.md), [kế hoạch tổng](payment-settings-implementation-2026-11-08.md).

## Global Constraints

- Mục tiêu S1 **80 phút**, bao gồm chuẩn bị source, focused tests và handoff; S1/S2/S3 tổng **240 phút** là mục tiêu, không phải cam kết bỏ test nếu hết giờ.
- Chỉ dùng `.scratch/payment-settings-20261108/worktree`, branch `feat/payment-settings-ui`; bảo toàn dirty checkout gốc, index, private config và runtime 8080.
- DB-first, `ddl-auto=none`; fixture riêng dùng `validate`. Không migrate DB ứng dụng hoặc tự thêm migration khi boot.
- Không sao chép `.env`, provider/local properties có secret, uploads, backup DB hoặc target. Không gọi provider thật, bật payout/maintenance hoặc tạo giao dịch thật.
- Không có UI/controller/menu, lookup người nhận production, OAuth, token SePay mới hoặc luồng liên kết Bảo Kim trong S1.
- Credential DB hỏng phải chặn sử dụng; chỉ **không có row** mới fallback legacy. Thiếu bảng/DB lỗi không phải trường hợp fallback.
- Mọi guard actor/role/pilot/enabled và snapshot tiền/reference/idempotency hiện có được giữ nguyên.

## 1. Snapshot trước thực thi S1 — giữ làm baseline

Các phát hiện dưới đây thuộc thời điểm chuẩn bị, trước khi implement. Đã so nội dung SHA-256, không chỉ dựa vào `sessions-status.json`:

- Chưa có `CredentialCipher`, `ChannelCredentialResolver`, entity credential hoặc route settings trong root/worktree.
- Trong phạm vi source/config công khai được so sánh có **31 path khác nhau** giữa root và worktree: gồm BEFORE, calendar, fixtures và template mới. Đây không phải diff của tính năng payment settings.
- `BookingService`, `OwnerBookingController`, `application.properties`, `PaymentFlowFixture` và `FinalRentalJourneyTest` khác nhau; `OwnerBeforeEntryTest` và calendar JS chưa có trong worktree cũ.
- `PaymentIntentTransactions`, `SePayWebhookVerifier`, `PayoutTransactions` và `scriptDB.sql` vẫn khớp trong lần đối chiếu này.
- `PaymentIntentTransactions.view()` được gọi bên trong transaction của `FinancialViews`; xử lý lỗi credential cần tránh đánh dấu rollback-only cho nhánh hiển thị UNAVAILABLE.
- Chưa chạy test/preflight mới trong lượt phân tích này. 96/96 và 49/49 ngày 08/11 chỉ là bằng chứng source cũ.

**Điều chỉnh bắt buộc:** làm mới baseline có manifest **một lần trước S1**, rồi đóng băng đến hết S3. Không dùng chỉ dẫn cũ “không lấy lại root” để bỏ sót bản sửa BEFORE/calendar; cũng không copy root tùy ý giữa các phiên.

## 2. Task S1-0 — baseline hiện tại, phút 0–8

**Files/artifacts:** baseline snapshot/manifest mới và `S1-baseline-refresh-20261109.json` dưới `.scratch/payment-settings-20261108/`; giữ nguyên baseline ngày 08/11.

- [x] Kiểm tra branch/base/index và hash private files, chỉ ghi hash; kiểm tra source không đổi trong lúc snapshot.
- [x] So worktree với manifest ngày 08/11. Nếu có chỉnh sửa riêng trong worktree, giữ chúng và phân tích từng path trước khi cập nhật; không ghi đè theo root.
- [x] Lập allowlist các dependency hiện tại: Java production/test, templates/static, test resources/JS, `application.properties`, `pom.xml`, `scriptDB.sql`, `.env.example` đã rà là placeholder. Loại mọi private/local/provider properties và symlink đi ra ngoài workspace.
- [x] Đồng bộ các path khác nhau đã kiểm tra và các tài liệu payment-settings; giữ baseline cũ, ghi hash root/worktree trước/sau và baseline mới. Không xóa file chỉ vì root không có; không nhận 31 path này thành feature diff.
- [x] Giữ default workflow/advance/balance của source mới; giữ fixture boot truyền explicit flags và legacy suites tắt workflow. Chạy compile qua focused baseline dưới đây.

```powershell
$env:JAVA_HOME='C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot'
$env:Path="$env:JAVA_HOME/bin;$env:Path"
$env:MAVEN_OPTS='-Xmx768m'
./mvnw.cmd -o '-Dmaven.compiler.useIncrementalCompilation=false' '-DargLine=-Xmx768m' '-Dtest=OwnerBeforeEntryTest,BeforeInspectionTest,ProviderPaymentPropertiesTest,PaymentModelTest,RentalLifecycleTest' test
node --test src/test/js/car-availability.test.cjs
```

**Điều kiện ra task:** source mới đã đóng băng và baseline xanh trên schema fixture. Nếu có lỗi nền hoặc diff chưa rõ, ghi thời gian thực tế; chưa sửa tính năng và chưa đánh S1 COMPLETE.

## 3. Task S1-A — schema/store, phút 8–25

**Create:** `docs/migrations/2026-11-08-01-payment-channel-credentials.sql`; `src/main/java/com/carrent/payment/PaymentChannelCredential.java`, `PaymentChannelCredentialRepository.java`; `src/test/java/com/carrent/payment/PaymentCredentialMigrationTest.java`.

**Modify:** `scriptDB.sql`, `docs/migrations/README.md`, `PaymentFlowFixture.java`, `PaymentModelTest.java`, `RentalLifecycleTest.java` trong test payment package.

**Interface:** một row theo `channel_id`; repository `findByProviderAndReference(PaymentProvider provider, String reference)` trả `Optional<PaymentChannelCredential>`, join-fetch channel/user. Không lookup chỉ reference vì unique constraint là `(provider, credential_reference)`.

- [x] Viết migration test RED: thiếu marker từ chối trước DDL; có marker tạo đúng bảng; bootstrap/incremental parity; dữ liệu bảng cũ giữ nguyên.
- [x] Tạo đúng DDL SDD §3: ciphertext `VARBINARY(8192)`, nonce `BINARY(12)`, encryption_version=1, status CONFIGURED/READ_VERIFIED, checked_at và timestamps UTC `DATETIME(6)`, version BIGINT; PK/FK RESTRICT về channel.
- [x] Guard version MySQL/strict/schema nguồn/target partial; chỉ CREATE bảng mới, không seed secret. Thêm DDL bootstrap trước START TRANSACTION, không sửa migration lịch sử.
- [x] Entity `@MapsId`, không cascade; `@Version Long` null khi mới. Không `@Data`/toString tự sinh có thể đưa dữ liệu nhạy cảm ra ngoài; không serialize entity.
- [x] Gọi helper migration mới trước JPA validate trong `PaymentModelTest`, nhánh parity/boot của `RentalLifecycleTest` và helper `migratedDatabase()`; cleanup credential trước channels trong fixture.
- [x] Không thay `PaymentMigrationTest.populatedDatabase()` để giả rằng schema cũ đã có bảng mới. Kiểm tra mọi chỗ test tự xóa channel; chỉ sửa nơi thực sự tạo credential.

```powershell
./mvnw.cmd -o '-Dtest=PaymentCredentialMigrationTest,PaymentModelTest,RentalLifecycleTest,RentalModelTest,SubscriptionModelTest' test
```

**Đạt:** RED nghiệp vụ được giữ, migration/JPA GREEN, FK/version/binary/UTC đúng; chưa đụng DB ứng dụng.

## 4. Task S1-B — cipher, phút 25–40

**Create:** `src/main/java/com/carrent/payment/CredentialCipher.java`, `src/test/java/com/carrent/payment/CredentialCipherTest.java`.

**Modify:** `application.properties`, `.env.example`; fixture key wiring trong `PaymentFlowFixture.java` nếu dùng property để kiểm thử restart.

**Interface:**

```java
CredentialCipher(String masterKeyBase64)
boolean available()
Sealed seal(byte[] plaintext, String aad)
byte[] open(Sealed value, String aad)
record Sealed(byte[] nonce, byte[] ciphertext) { }
```

- [x] Viết RED roundtrip + sai AAD bị chặn + cùng plaintext có nonce khác nhau, theo code mẫu PS-A của implementation plan.
- [x] Dùng `AES/GCM/NoPadding`, key Base64 đúng 32 byte, nonce SecureRandom 12 byte, tag 128 bit. AAD chính xác `v1|{provider}|{channelId}|{userId}|{credentialReference}`; không chuẩn hóa reference tùy ý.
- [x] `Sealed` copy byte arrays khi nhận/trả; không log plaintext hoặc đính parser/crypto cause có thể lộ input vào thông báo lỗi.
- [x] Key trống/sai format/sai độ dài: bean vẫn tạo được, available=false, seal/open từ chối bằng thông điệp cố định. Không sinh master key tự động mỗi boot.
- [x] Property duy nhất: `carrent.payment.credentials.master-key=${CARRENT_PAYMENT_CREDENTIAL_MASTER_KEY:}`. Example chỉ có placeholder; fixture key giả ổn định qua restart, không vô tình lấy key môi trường thật.
- [x] Chạy GREEN cho roundtrip, fresh nonce, tampered tag, wrong key/AAD, defensive copies và unavailable key.

```powershell
./mvnw.cmd -o '-Dtest=CredentialCipherTest,PaymentCredentialMigrationTest' test
```

**Đạt:** cipher độc lập DB/HTTP, không dependency mới; lưu ciphertext trong fixture rồi đọc lại được.

## 5. Task S1-C — resolver và đủ caller, phút 40–63

**Create:** `src/main/java/com/carrent/payment/ChannelCredentialResolver.java`, `src/test/java/com/carrent/payment/ChannelCredentialResolverTest.java`.

**Modify production:** `PaymentIntentTransactions`, `PayoutTransactions`, `RefundRecipientTransactions`, `PayoutChannelResolver`, `provider/SePayWebhookVerifier`, `config/PaymentProviderConfiguration`.

**Modify tests:** `ProviderPaymentPropertiesTest`; thêm case integration vào test mới, tận dụng fixture hiện có. `FinancialViews` không cần đổi nếu shared `view()` xử lý đúng.

**Interface:**

```java
ProviderPaymentProperties.PayOsChannel payos(String reference)
ProviderPaymentProperties.SePayChannel sepay(String reference)
```

- [x] Viết RED: DB ưu tiên legacy cùng reference; PAYOS/SEPAY có cùng reference vẫn tách; absent-row mới fallback; cipher/JSON/key lỗi không fallback.
- [x] Lookup provider+reference; kiểm tra provider/purpose/version/AAD. Decode JSON strict duplicate/trailing tokens, đúng trường/type/length và không control chars. PayOS đúng clientId/apiKey/checksumKey; SePay đúng webhookSecret/gateway theo SDD, không thêm token API.
- [x] Không cache plaintext hoặc dùng READ_VERIFIED thay enabled. DB credential disabled vẫn decode được để xác minh webhook cũ/shared routing; caller quyết định có được thu/chi hay không.
- [x] Phân loại lỗi cấu hình dự kiến bằng nested exception `ChannelCredentialResolver.Unavailable extends IllegalStateException` có message cố định, không chứa secret hoặc parser cause. DB/repository lỗi không chuyển thành absent-row hoặc thành lỗi cấu hình dự kiến.
- [x] Resolver readOnly REQUIRED dùng `noRollbackFor = ChannelCredentialResolver.Unavailable.class`. Chỉ lỗi credential dự kiến được phép để caller trả UNAVAILABLE; không dùng `noRollbackFor=Exception.class` hoặc catch rộng toàn flow. Test thực qua Spring proxy, không chỉ tự gọi object.

Ví dụ chính sách transaction cần có trong implementation:

```java
public static final class Unavailable extends IllegalStateException {
    public Unavailable() { super("Payment credentials are unavailable"); }
}
```

Đặt `@Transactional(readOnly = true, noRollbackFor = Unavailable.class)` trên cả hai public resolver methods. Thuật toán resolve: query DB → chỉ absent-row đọc map legacy → kiểm tra metadata → decrypt/AAD → strict parse → typed record. Không đưa network vào resolver.

| Caller | Thay đổi bắt buộc |
| --- | --- |
| Intent tạo mới/reuse | Đọc `resolver.sepay`; tạo mới lỗi thì chặn không persist QR sai. Reuse ACTIVE bị lỗi trả UNAVAILABLE, không cung cấp QR; không sửa trạng thái đã lưu |
| Intent view/shared finance | Chỉ resolve khi status hiển thị còn ACTIVE; catch riêng Unavailable → UNAVAILABLE. PAID/EXPIRED/CANCELLED không decrypt để dựng lịch sử |
| SePay config/verifier | Thêm constructor `(Function<String,SePayChannel>, Clock)` và giữ `(ProviderPaymentProperties, Clock)`. Config cung cấp function qua resolver; riêng Unavailable chuyển thành null để các kiểm tra trả false, không fallback |
| SePay verify/sharesWebhookSecret/acceptsGateway | Cả ba đường phải dùng function động; giữ raw-body HMAC, timestamp, constant-time và routing/dedup hiện có |
| PayoutChannelResolver | Dùng resolver.payos; không sẵn sàng thì IllegalStateException generic trước network; giữ guards nguồn/role/enabled |
| PayoutTransactions | sourceReady đọc resolver.payos; Unavailable → false, không tạo lệnh gửi sai hoặc đổi attempt/reference/idempotency; UNKNOWN giữ query-only |
| RefundRecipientTransactions | Dùng resolver.payos; Unavailable → BusinessException generic trước khi lưu đích/deadline; giữ xác minh đích production hiện có |

- [x] Bổ sung mock resolver vào properties-only ApplicationContextRunner; không kéo DB vào test binding.
- [x] Giữ legacy SePay constructor và test override hiện có, nhưng case DB integration phải chạy default bean thật.
- [x] Không đổi `HttpPayOsPayoutClient`, `SePayWebhookService`, setup runner hoặc `PaymentPilot` nếu các seam trên đủ đáp ứng.

**Đạt:** không còn đọc map tĩnh ở sáu caller phải chuyển; map legacy còn hợp lệ trong resolver, constructor tương thích, tests binding và các setup runner operator.

## 6. Task S1-D — focused regression/handoff, phút 63–80

- [x] Lưu/đọc JPA credential fixture; DB dump không có plaintext; restart cùng schema/key đọc được, key sai/thiếu chặn đúng.
- [x] DB SePay tạo QR đúng bank/account, webhook hợp lệ ghi receipt, duplicate không nhân đôi; shared/disabled-target/gateway mismatch giữ hành vi hiện có.
- [x] ACTIVE credential hỏng → UNAVAILABLE qua cả status và finance; assertion sau transaction đảm bảo không UnexpectedRollbackException. PAID/EXPIRED vẫn đọc được mà không decrypt.
- [x] Owner/Admin payout dùng đúng channel; disabled không send; không sửa snapshot, amount, reference, idempotency hoặc signature contract.
- [x] Kiểm tra source cuối bằng aggregate sau; test mới phải tồn tại rồi mới chạy. Ghi XML/log thực tế, không dự đoán test count hoặc lấy baseline làm kết quả tính năng.

```powershell
./mvnw.cmd -o '-Dmaven.compiler.useIncrementalCompilation=false' '-DargLine=-Xmx768m' '-Dtest=CredentialCipherTest,PaymentCredentialMigrationTest,ChannelCredentialResolverTest,ProviderPaymentPropertiesTest,PaymentModelTest,RentalLifecycleTest,RentalModelTest,SubscriptionModelTest,SePayWebhookVerifierTest,SePaySharedAccountTest,PaymentIntentTest,FinancialViewsTest,RefundRecipientTest,PayoutWorkerTest,PayOsPayoutClientTest,OwnerBeforeEntryTest,BeforeInspectionTest' test
```

- [x] Nếu fixture/helper thay đổi sau baseline, chạy các legacy car/concurrency suites bị ảnh hưởng trước khi bàn giao. Theo yêu cầu nghiệm thu đợt đầu, full clean verify/JAR đã chạy ngay trong S1; S3 vẫn kiểm chứng source/UI cuối sau S2.
- [x] Ghi `docs/plans/payment-settings-S1-handoff.md`: interfaces có thật, diff theo baseline mới, log/số test thực tế, lỗi ban đầu và rerun, tình trạng private/index/runtime/schema và mục chưa đạt. Dọn schema/port fixture do S1 tạo.
- [x] Chỉ đổi S1 thành COMPLETE khi mọi điều kiện đạt; S2/S3 vẫn NOT_STARTED. Không bắt đầu UI hoặc commit trong S1.

## 7. Điều kiện chạy ứng dụng sau này

Không thể triển khai binary có entity/query mới lên schema thiếu `payment_channel_credentials` rồi kỳ vọng legacy fallback hoạt động. Fallback dành cho **schema đã migrate nhưng chưa có credential row**. Khi chuyển sang vận hành, thứ tự là backup → migration được kiểm chứng → cấu hình master key bền vững → binary mới → smoke/recovery theo phạm vi được giao. S1 chỉ chuẩn bị/test thứ tự này trên schema riêng.

Backup DB và master key phải được bảo quản riêng; chỉ restore DB mà mất key sẽ không đọc được credential. S1 test restart/decrypt; nghiệm thu restore đầy đủ P8.8 vẫn là hạng mục riêng, không tự đóng trong S1.

## 8. Quyết định bắt đầu

Không cần thêm credential/provider entitlement để thực hiện S1 bằng fixtures. Điểm bắt đầu là S1-0, sau đó schema → cipher → resolver/callers → regression/handoff. Nếu baseline hoặc kiểm chứng vượt timebox, báo số phút thực tế và phần còn lại; không cắt guard, lược test hoặc đẩy backend chưa xong sang S2.

Checkpoint phân tích ban đầu chỉ cập nhật tài liệu. Sau thực thi, S1 đã hoàn tất baseline/store/cipher/resolver/callers và kiểm chứng đầy đủ; xem handoff để biết lượt full bị dừng, lỗi teardown đã sửa và kết quả cuối. Không migrate DB ứng dụng, bật provider, restart app hoặc commit/push.
