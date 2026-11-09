> **CANCELLED - 2026-11-09:** Chủ dự án hủy Payment Settings S1/S2/S3 và chọn source trước S1. Nội dung bên dưới chỉ là lịch sử thiết kế/kiểm chứng, không phải kế hoạch đang thực thi. Không tiếp tục implement, migrate hoặc kích hoạt từ tài liệu này.

# Payment Settings UI Implementation Plan

> **Nghiệm thu S1 — 09/11/2026:** S1 COMPLETE trong worktree: 1.230/1.230 Java tests (80 suites), 81 test mới, Node 18/18, clean verify/JAR và audit đạt. S2/S3 NOT_STARTED. Xem [bàn giao S1](payment-settings-S1-handoff.md); chỉ ghi nhận local completion, chưa tích hợp root hoặc bật provider thật.

> **Checkpoint 09/11/2026:** [kế hoạch S1 hiện hành](payment-settings-S1-execution-2026-11-09.md) bổ sung refresh baseline trước S1, lỗi credential có kiểu riêng và transaction noRollbackFor. Chỉ dẫn cập nhật này được ưu tiên nếu khác phần baseline lịch sử bên dưới; chưa implement.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking. Thực hiện tuần tự trên worktree đã chuẩn bị; chỉ dùng subagent nếu người dùng yêu cầu. Giao việc theo [ba phiên S1/S2/S3](payment-settings-sessions-2026-11-08.md); chỉ bắt đầu implement khi phiên được giao.

**Goal:** Owner/Admin lưu và kiểm tra cấu hình PayOS; Owner cấu hình SePay và mở QR theo quyền hiện có, với credential mã hóa trong DB.

**Architecture:** Thêm một encrypted credential store gắn với payment_channels, một resolver đọc DB trước và legacy environment sau, một trang Thymeleaf dùng chung theo role. Tái sử dụng PayOS signed GET client, HMAC/inbox/QR/worker hiện tại; không tạo cơ chế thanh toán mới.

**Tech Stack:** Java 21, Spring Boot 4.1.1, Spring MVC/Security/JPA, Thymeleaf, MySQL 8.0.16+, JDK AES/GCM, RestClient, JUnit/MockMvc và browser đang có.

**Spec:** [SDD bổ sung](../design/payment-settings-2026-11-08.md), [SDD chính §8.8](../SDD.md#88-trang-kết-nối-thanh-toán-payossepay--2026-11-08).

## Global Constraints

- Người dùng đã đồng ý mục tiêu 4 giờ; thực hiện 3 phiên liên tiếp: S1=PS-A/B 80 phút, S2=PS-C/D 80 phút, S3=PS-E/F 80 phút. Mỗi phiên có handoff, giữ nguyên worktree và interfaces.
- Mục tiêu 240 phút cho implementation và local verification; thời gian chờ provider/giao dịch thật không nằm trong mốc này. Không bỏ kiểm chứng để khớp giờ.
- Chỉ triển khai trong `.scratch/payment-settings-20261108/worktree`, branch `feat/payment-settings-ui`; bảo toàn checkout gốc và baseline snapshot khác tính năng.
- Không thêm dependency; Java 21, MySQL 8.0.16+, Hibernate `ddl-auto=none`; không tự migrate DB ứng dụng.
- Không sao chép live provider keys/.env/backup dữ liệu vào worktree hoặc Git; DB-only local fixture config đã chuẩn bị và được ignore.
- Không tự bật payout/provider/scheduler, restart8080, chuyển tiền thật, stage/commit/push/merge hoặc xóa nhánh trong đợt implement này.
- Key mã hóa chỉ từ `CARRENT_PAYMENT_CREDENTIAL_MASTER_KEY`; secret không nằm trong HTML/log/URL/session/localStorage, không serialize entity ra response.
- Chỉ không có DB credential mới fallback legacy; DB corrupt/missing key phải từ chối, không fallback sang nguồn cũ.
- Không sửa nguồn/secret kênh có lịch sử; chỉ adopt cấu hình giống hệt. Admin SePay cũ giữ nguyên; UI mới chỉ Owner SePay.
- Hoàn thành UI không đóng P4.2-A/P8.8/P5.9 hoặc xác nhận quyền chi/lookup.

## 0. Baseline lịch sử 08/11; refresh một lần trước S1 theo checkpoint 09/11

- Base main `00a60396f2153dfb697e82f6b84a5bd743daf8dc`; checkout gốc HEAD `b829b05` giữ nguyên.
- Snapshot **409 file** src/docs/pom/bootstrap/example, không có private provider config. Các khác biệt kế thừa được ghi ở `.scratch/payment-settings-20261108/baseline-source-manifest.json`; không gọi chúng là code mới của tính năng.
- Baseline mới tại worktree: **96/96 JUnit, 6 suites, 0 failure/error/skip**, Maven BUILD SUCCESS; 57.382 giây. Log/JSON ở `.scratch/payment-settings-20261108/baseline-tests.*`. Đây là focused baseline, chưa phải full verify của source tính năng.
- Đọc [worktree handoff](payment-settings-worktree-2026-11-08.md) và S1-0 ngày 09/11 trước khi chạy; làm mới source theo allowlist/hash để giữ BEFORE/calendar, không tự đổi Git base hoặc ghi đè chỉnh sửa riêng của worktree. Sau đó đóng băng baseline đến hết S3.
- S1 đọc thêm [phân tích trước implement](payment-settings-S1-preimplementation-2026-11-08.md): provider-scoped query, boot-path fixtures, properties context và chính sách lỗi cho lịch sử.

PowerShell trong worktree, mỗi phiên shell đặt JDK:

```powershell
$env:JAVA_HOME='C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot'
$env:Path="$env:JAVA_HOME/bin;$env:Path"
$env:MAVEN_OPTS='-Xmx768m'
```

## PS-A — Migration và encrypted store, 40 phút

**Files tạo:**

- `docs/migrations/2026-11-08-01-payment-channel-credentials.sql`
- `src/main/java/com/carrent/payment/PaymentChannelCredential.java`
- `src/main/java/com/carrent/payment/PaymentChannelCredentialRepository.java`
- `src/main/java/com/carrent/payment/CredentialCipher.java`
- `src/test/java/com/carrent/payment/PaymentCredentialMigrationTest.java`
- `src/test/java/com/carrent/payment/CredentialCipherTest.java`

**Files sửa:** `scriptDB.sql`, `docs/migrations/README.md`, `src/main/resources/application.properties`, `.env.example`, `src/test/java/com/carrent/payment/PaymentFlowFixture.java`, `src/test/java/com/carrent/payment/PaymentModelTest.java`, `src/test/java/com/carrent/payment/RentalLifecycleTest.java`.

**Interfaces:** Consumes PaymentChannel và DDL chính xác SDD §3. Produces entity/repository theo PK channel_id và `CredentialCipher(String masterKeyBase64)`, `available(): boolean`, nested `Sealed(byte[] nonce, byte[] ciphertext)`, `seal(byte[], String)`, `open(Sealed, String)`. Key thiếu/sai chỉ làm available=false và chặn crypto, không làm legacy bean/context lỗi.

- [x] Viết cipher RED test trước, cùng plaintext phải có nonce khác, AAD sai phải bị từ chối:

```java
@Test void bindsCiphertextToChannelAndUsesFreshNonce() {
    var cipher = new CredentialCipher(Base64.getEncoder().encodeToString(new byte[32]));
    byte[] plain = "offline-fixture".getBytes(StandardCharsets.UTF_8);
    var first = cipher.seal(plain, "v1|PAYOS|101|2|ui:2:PAYOS");
    var next = cipher.seal(plain, "v1|PAYOS|101|2|ui:2:PAYOS");
    assertThat(first.nonce()).isNotEqualTo(next.nonce());
    assertThat(cipher.open(first, "v1|PAYOS|101|2|ui:2:PAYOS")).isEqualTo(plain);
    assertThatThrownBy(() -> cipher.open(first, "v1|PAYOS|102|1|ui:1:PAYOS"))
        .isInstanceOf(RuntimeException.class);
}
```

- [x] Chạy `./mvnw.cmd -o '-Dtest=CredentialCipherTest' test`; lưu RED do thiếu implementation. Không coi lỗi JDK/DB là RED nghiệp vụ.
- [x] Implement AES/GCM bằng JDK, trả lỗi generic; key Base64 đúng 32 byte, thiếu key chỉ chặn thao tác crypto/settings, không chặn legacy startup. Phần crypto cốt lõi:

```java
byte[] nonce = new byte[12];
new SecureRandom().nextBytes(nonce);
Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
return new Sealed(nonce, cipher.doFinal(plaintext));
```

- [x] Tạo migration wrapper theo mẫu `2026-10-30-01-refund-accounts.sql`, đổi procedure thành `carrent_channel_credentials_v1`, marker thành `@carrent_channel_credentials_backup_confirmed`, dùng nguyên DDL SDD §3. Guard MySQL/strict/PK/FK/target trước CREATE; không seed, không chọn database, không `--force`.
- [x] Thêm cùng DDL vào bootstrap trước `START TRANSACTION`; entity `@MapsId` đến PaymentChannel, `@Version Long version` null khi mới, UTC Instant và binary lengths đúng SDD; không DTO trả secret. Repository `JpaRepository<PaymentChannelCredential,Long>` lookup bằng provider + reference, không nhận user/channel từ form.
- [x] Test migration dùng `PaymentMigrationTest.populatedDatabase()` để có schema trước bảng mới; lưu legacy/core hashes, chạy migration có marker, so bảng với bootstrap riêng. Marker thiếu/target đã có phải fail. Test ciphertext không chứa plaintext, tag/key/AAD sai phải fail.
- [x] Thêm helper `PaymentCredentialMigrationTest.migrate(PaymentTestDatabase, boolean)` theo S1 preimplementation §5; gọi trước boot validate ở PaymentModelTest, nhánh bootstrapMatchesMigratedRentalSchemaAndJpaMapsExpired và helper migratedDatabase của RentalLifecycleTest. Không tự thêm bảng mới vào PaymentMigrationTest.populatedDatabase, không migration lúc app boot.
- [x] Thêm `DELETE FROM payment_channel_credentials` trước xóa payment_channels trong `PaymentFlowFixture.reset()`; rà toàn bộ fixture tự xóa channel để tránh FK lỗi. Test metadata/GET không tự tạo DB credential.
- [x] Thêm property key trống theo SDD, `.env.example` chỉ placeholder. Chạy `./mvnw.cmd -o '-Dtest=CredentialCipherTest,PaymentCredentialMigrationTest,PaymentModelTest,RentalLifecycleTest,RentalModelTest,SubscriptionModelTest' test`, checkpoint log/manifest; không commit.

## PS-B — Resolver động đủ caller, 40 phút

**Create:** `src/main/java/com/carrent/payment/ChannelCredentialResolver.java`, `src/test/java/com/carrent/payment/ChannelCredentialResolverTest.java`.

**Modify:** `PaymentIntentTransactions.java`, `PayoutTransactions.java`, `RefundRecipientTransactions.java`, `PayoutChannelResolver.java` trong `src/main/java/com/carrent/payment/`; `src/main/java/com/carrent/payment/provider/SePayWebhookVerifier.java`; `src/main/java/com/carrent/config/PaymentProviderConfiguration.java`.

**Modify test wiring:** `src/test/java/com/carrent/payment/ProviderPaymentPropertiesTest.java`; context binding-only bổ sung mock resolver, không yêu cầu DB cho test cấu hình này. Real injection được kiểm chứng trong resolver integration test.

**Interfaces:** Consumes PS-A store/cipher và ProviderPaymentProperties fallback. Produces `payos(String reference): PayOsChannel`, `sepay(String reference): SePayChannel`. Resolver giữ field `ProviderPaymentProperties legacy`; repository có `Optional<PaymentChannelCredential> findByProviderAndReference(PaymentProvider provider, String reference)` với explicit fetch channel/user. Private helpers `requirePayosPayoutChannel(PaymentChannel channel)` kiểm tra PAYOS/PAYOUT; `decodePayos(PaymentChannelCredential row, CredentialCipher cipher): PayOsChannel` dựng AAD SDD, mở ciphertext, parse JSON và trả typed record với channel.enabled; nhánh SePay dùng schema field SDD. Giữ quyền current actor/role/status ở caller, không áp thêm ACTIVE cho mọi secret read. SePay verifier thêm `(Function<String,SePayChannel>, Clock)`; constructor cũ `(ProviderPaymentProperties,Clock)` giữ nguyên behavior bằng `properties.sepay()::get`.

- [x] Viết RED resolver test: legacy không DB entry vẫn trả credential; DB entry hợp lệ được ưu tiên; DB entry corrupt/thiếu key không trả legacy; cùng reference ở hai provider vẫn resolve riêng; disabled không bị bật. Cố định test key giả và encrypted fixture trong schema tạm, không đọc live keys.
- [x] Chạy `./mvnw.cmd -o '-Dtest=ChannelCredentialResolverTest' test` để ghi RED.
- [x] Resolve theo reference server-owned: join channel/user, kiểm tra provider/purpose/role và encrypted format, dựng AAD đúng SDD; decode JSON đúng bộ field. Chỉ nhánh absent-row mới fallback. Không cache plaintext lâu dài hoặc cache status lỗi. Logic quyết định phải tương đương:

```java
var stored = repository.findByProviderAndReference(PaymentProvider.PAYOS, reference);
if (stored.isEmpty()) return legacy.payos().get(reference);
var row = stored.get();
requirePayosPayoutChannel(row.getChannel());
return decodePayos(row, cipher); // malformed/decrypt failure throws; never falls back
```

- [x] Đổi ba hàm verify/sharesWebhookSecret/acceptsGateway của SePay verifier sang cùng function lookup. Cấu hình bean bằng `new SePayWebhookVerifier(resolver::sepay, clock)`; test tạo verifier trực tiếp bằng properties vẫn biên dịch.
- [x] Thay tất cả map read nghiệp vụ ở các file Modify bằng resolver đúng provider. Giữ nguyên guard, refresh/lock, signature, idempotency, source snapshot và UNKNOWN/query-only. Không sửa setup CLI để chạy khi user mở UI.
- [x] Thực hiện error policies theo S1 preimplementation §6: resolve cho QR ACTIVE, lỗi thành UNAVAILABLE; PAID/EXPIRED không resolve eager. Disabled SePay target vẫn đọc secret cho routing tiền mã cũ; verifier không ACK hoặc ingest payload không xác minh. Recipient/payout unavailable không network hoặc đổi durable attempt.
- [x] Rà lại caller bằng `rg -n 'providers\.(sepay|payos)|properties\.(sepay|payos)' src/main/java/com/carrent`; phần còn lại chỉ được là fallback resolver hoặc CLI/operator configuration. Kiểm tra QR creation và ACTIVE reuse/view đều được đổi.
- [x] Chạy aggregate focused S1 theo lệnh đầy đủ trong preimplementation §7, có cả ProviderPaymentPropertiesTest và các boot-path model tests. Thêm case DB shared routing với default verifier động trong ChannelCredentialResolverTest; giữ SePaySharedAccountTest cũ kiểm chứng legacy constructor. Có restart, hai user cùng khóa khác mapping và lịch sử đọc được khi key hỏng; checkpoint.

## PS-C — PayOS settings cho Owner/Admin, 40 phút

**Create:** `PaymentSettingsService.java`, `PaymentSettingsTransactions.java`, `PayOsSettingsForm.java`, `PaymentSettingsController.java` trong `src/main/java/com/carrent/payment/`; `src/main/resources/templates/payment/settings.html`; `src/test/java/com/carrent/payment/PaymentSettingsTest.java`.

**Modify:** `src/main/resources/templates/fragments/workspace.html` (hai menu), `src/main/resources/application.properties` (public-base-url server property, mặc định trống).

**Interfaces:** Consumes PS-A/B; produces các nested records View/ChannelSummary/Target và signatures SDD §4. Form có đúng `clientId`, `apiKey`, `checksumKey`, getter/setter không generated toString. Service `savePayos(form,actor)` chuẩn bị target trước network, signed GET list/balance ngoài transaction, commit với actor/target revalidation. Controller chỉ các routes SDD §2.

- [ ] RED MockMvc: CUSTOMER bị 403; thiếu CSRF bị 403; Owner gửi userId/channelId/enabled không làm đổi chủ hoặc bật kênh; Admin chỉ lưu chính mình; inactive/stale actor bị chặn. Dùng `PaymentFlowFixture` và `CustomUserPrincipal`, `.with(user(actor)).with(csrf())` theo test hiện có.
- [ ] RED UI test phải khẳng định response không chứa khóa đầu vào sau cả success lẫn lỗi:

```java
@Test void rendersNoSecretAfterValidationFailure() throws Exception {
    f.mvc.perform(post("/owner/payment-settings/payos")
            .with(user(f.actor(2))).with(csrf())
            .param("clientId", "offline-client-marker")
            .param("apiKey", "offline-api-secret-marker"))
        .andExpect(status().isBadRequest())
        .andExpect(content().string(not(containsString("offline-api-secret-marker"))));
}
```

`f` là PaymentFlowFixture; `f.actor(2)` đã trả CustomUserPrincipal của Owner fixture. Test sử dụng secret giả, không user/password thật.

- [ ] Chạy RED `./mvnw.cmd -o '-Dtest=PaymentSettingsTest' test`, rồi implement controller/service/transactions/template. `prepare` và `save` kiểm tra role/status từ DB; `save` lock User → channel, kiểm tra expected nullable credentialVersion, provider/purpose/reference và uniqueness. Channel mới reference do server sinh `ui:{id}:PAYOS`, disabled; existing channel giữ enabled.
- [ ] GET probe tái sử dụng `new HttpPayOsPayoutClient(id -> typedFormCredentials).checkConnection(1L)` với typed record `PayOsChannel(false,...)`; không dùng `PayoutChannelResolver` cho khóa chưa lưu. Constructor production `PaymentSettingsService(PaymentSettingsTransactions tx)` đánh dấu @Autowired, delegate sang constructor package-private `(PaymentSettingsTransactions tx, Function<PayOsChannel,HttpPayOsPayoutClient.ConnectionCheck> probe)`; default function dùng client hiện có. Test cung cấp function tạo cùng client với RestClient mock server có signed response. Không mở port, gọi payOS live hoặc thêm public endpoint truyền URL.
- [ ] Transaction save serialize JSON đúng SDD, encrypt sau khi đã có channelId, ghi status READ_VERIFIED và Clock checkedAt; network lỗi/stale target không thay ciphertext cũ. History guard theo năm FK SDD §5: chỉ adopt cùng credential/source; khác thì 409 với hướng dẫn operator. Không tạo kênh thứ hai cho cùng user/provider.
- [ ] Template password input không `th:field`/`th:value` cho secret, chỉ có name/id/label; metadata dùng escaped `th:text`. Sau lỗi tạo form trống và loại BindingResult chứa rejected secret khỏi model, chỉ trả thông báo/field code không có giá trị cũ. Form binding allowlist, response no-store, PRG success, key missing là trạng thái chưa cấu hình operator.
- [ ] Chạy focused test với mock signed GET thành công/sai signature/403/timeout; xác nhận zero POST payouts, hai user không đọc/cập nhật nhau, cùng bộ khóa được chấp nhận độc lập, encryption at rest và GET không mutate DB. Checkpoint.

## PS-D — Owner SePay, manual webhook và QR, 40 phút

**Create:** `src/main/java/com/carrent/payment/SePaySettingsForm.java`, `src/test/java/com/carrent/payment/SePaySettingsTest.java`.

**Modify:** PaymentSettingsController/Service/Transactions và payment/settings.html của PS-C; reuse `SePayWebhookService`/`PaymentIntentTransactions`, không thay HMAC hay response ACK.

**Interfaces:** Form `bankBin`, `accountNumber`, `gateway`, `webhookSecret`; service `saveSepay(form,actor)` và `enableSepay(boolean webhookConfigured,actor)`. `View.sepay` là metadata từ store/channel, lastWebhookAt query chính channel từ inbox; URL webhook do server cấu hình HTTPS.

- [ ] RED tests: Owner lưu số tài khoản có số 0 đầu; Admin/Customer không có route ghi SePay; save không bật channel mới; enable thiếu confirmation hoặc ngoài pilot bị chặn; public base URL trống/http/userinfo/query/fragment không được phát URL sai.
- [ ] Chạy `./mvnw.cmd -o '-Dtest=SePaySettingsTest' test` để lưu RED rồi implement validate BIN 6 digits/account 1–19 digits/gateway nonblank <=50/secret <=512 không control. Gateway phải khớp giá trị webhook SePay (ví dụ TPBank trong pilot hiện tại); không lấy mã MB hoặc tên hiển thị ngân hàng làm gateway bằng suy đoán.
- [ ] Save CONFIGURED, có master key và owner ACTIVE, reuse channel hiện tại dưới guard history; bank/secret/gateway khác khi có history bị từ chối, cấu hình trùng cho adopt DB được chấp nhận. Không yêu cầu API token query chưa có caller; không tạo webhook ở provider.
- [ ] UI hiển thị HTTPS webhook URL và ba bước: copy URL vào SePay → chọn tiền vào/HMAC và đúng tài khoản → xác nhận bật nhận tiền. Trạng thái “Đã lưu, chưa nhận webhook” không dùng từ đã nhận tiền. Action enable chỉ đổi kênh của Owner pilot sau confirmation; không bật các global flags hoặc đổi kênh Admin.
- [ ] Viết case xuyên flow bằng fixture: save DB → enable → tạo intent bằng business service → QR dùng đúng bank/STK/code → HMAC fixture qua MockMvc endpoint `/webhooks/sepay/{channelId}` → inbox/receipt theo processor hiện có; replay cùng ID không ghi thêm hoặc cấp gói lại. Assert QR chưa phải PAID trước webhook. Restart fixture trước webhook để chứng minh credential DB đọc được sau restart.
- [ ] Chạy `./mvnw.cmd -o '-Dtest=SePaySettingsTest,SePaySharedAccountTest,SePayInboxTest,PaymentPilotTest,SubscriptionPaymentTest,SubscriptionOrderTest' test`; kiểm tra legacy Admin vẫn nhận bằng cấu hình fixture cũ. Checkpoint.

## PS-E — Verification cuối và review, 65 phút

**Files:** các source/test ở PS-A–D; evidence mới trong `.scratch/payment-settings-20261108/implementation/` bên ngoài worktree, không chung thư mục baseline.

**Interfaces:** Consumes source cuối; produces JAR/source hashes, full JUnit/browser evidence và checklist PS-01–08. Không dùng focused baseline 96 test làm kết quả feature.

- [ ] Rà spec coverage PS-01–08; rà mọi caller map theo PS-B; kiểm tra plaintext không có trong HTML/redirect/log/output, có missing-key/bad-tag/no-fallback test và history guards. Giữ fixture key giả độc lập, không sinh key production.
- [ ] Chạy từ worktree, lưu log ra evidence mới:

```powershell
./mvnw.cmd -o '-Dmaven.compiler.useIncrementalCompilation=false' '-DargLine=-Xmx768m' clean verify
node --test src/test/js/payment-polling.test.cjs
```

Nếu máy thiếu dependency offline, ghi lỗi nguyên bản rồi chạy lại có mạng một lần; không đổi pom/dependency chỉ để né lỗi. Đếm toàn bộ surefire XML, 0 failure/error/skip và có JAR. Nếu không đạt, sửa lỗi trong scope và ghi rõ initial/rerun, không báo full pass từ lượt chọn lọc.

- [ ] Dùng Chrome/Playwright fixture riêng/provider mock, port do OS chọn hoặc 18088 đã kiểm tra rảnh; không dùng DB ứng dụng. Browser Owner/Admin/Customer và 1440/390/320: GET/menu/active, focus/labels/errors, save/reload, no secret DOM, webhook URL/status, không có form SePay Admin. Screenshot dùng secret giả; không ảnh dashboard provider/keys thật.
- [ ] Kiểm tra race: ban/đổi role khi GET probe đang chờ, hai save đồng thời, history xuất hiện trước commit, corrupt DB không fallback, shared key không tạo quyền đọc chéo. Query-only UNKNOWN và snapshots payout cũ không đổi.
- [ ] Fingerprint source cuối + JAR; snapshot DB ứng dụng/private files/root HEAD/index trước/sau; dọn schema/port fixture chính xác và xác nhận không còn listener. Không xóa backup hoặc thư mục của phiên khác.

## PS-F — Handoff, 15 phút

**Create:** `docs/plans/payment-settings-implementation-handoff.md` khi implement xong.

**Modify:** trạng thái SDD bổ sung/§8.8 và link checkpoint trong docs/plans/README.md, không thay bộ đếm 83/86 vì đây là tính năng bổ sung chưa đóng gate live.

**Interfaces:** Consumes PS-E evidence; produces bàn giao source đã test, hướng dẫn migration/key/webhook và danh sách phần live còn chờ.

- [ ] Ghi PS-01–08 đạt/chưa đạt theo evidence, test count/log/browser/JAR/source version, mọi failure và rerun; không tạo con số dự kiến như kết quả đã chạy.
- [ ] Hướng dẫn operator: backup/restore → migration mới trên schema đã duyệt → provision master key ngoài DB → HTTPS public base URL → Owner/Admin nhập khóa riêng → cấu hình webhook SePay → xác nhận bật thu theo pilot. Không kèm giá trị secrets hoặc lệnh chuyển tiền.
- [ ] So manifest feature với baseline snapshot: file khác main nhưng có từ trước không thuộc tính năng; liệt kê inherited prerequisites riêng. Không `git add -A`, không commit/push/merge/xóa nhánh khi chưa được yêu cầu.
- [ ] Báo kết quả thời gian thực tế, phần còn lại và điều kiện live P4.2-A/P8.8; dừng sau handoff.

## Self-review trước bàn giao kế hoạch

- [x] PS-01–08 đều có task/caller/test tương ứng; có cả ACTIVE reuse QR, shared secret functions, payout và saved credential restart.
- [x] Type/route/property thống nhất SDD; legacy constructor SePay verifier giữ tương thích; fixture reset xét FK mới.
- [x] Không có bước giả định lookup/autoapproval/provider account đã được cấp quyền. Không coi UI xong là hai gate live xong.
- [x] Có phạm vi 240 phút, giới hạn được nêu rõ và baseline chọn lọc thực tế; không hứa thời gian provider.

Handoff thực thi mặc định: tiếp tục tuần tự bằng `superpowers:executing-plans` trên đúng worktree; không bắt đầu implement chỉ vì tài liệu này đã được tạo.
