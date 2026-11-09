> **CANCELLED - 2026-11-09:** Chủ dự án hủy Payment Settings S1/S2/S3 và chọn source trước S1. Nội dung bên dưới chỉ là lịch sử thiết kế/kiểm chứng, không phải kế hoạch đang thực thi. Không tiếp tục implement, migrate hoặc kích hoạt từ tài liệu này.

# Payment Settings S2 — phân tích trước triển khai

Ngày 09/11/2026. **S1 giữ nguyên; S2 PREPARED, chưa implement hoặc chạy test S2.**

> Khi được giao implement S2, dùng `superpowers:executing-plans` inline và Ponytail; thực hiện PS-C rồi PS-D theo tài liệu này và kế hoạch gốc. Dừng sau focused verification và handoff S2, trước S3. Không dùng subagent trừ khi người dùng yêu cầu.

**Goal:** Owner/Admin kiểm tra và lưu PayOS của chính mình; Owner lưu SePay, nhận hướng dẫn webhook và xác nhận bật thu trong phạm vi pilot, bằng encrypted store/resolver đã hoàn tất ở S1.

**Architecture:** controller xử lý binding/response; service điều phối probe HTTP ngoài transaction; transactions kiểm tra actor/channel/history/version và ghi qua entity/cipher S1. Hai route GET dùng một template Thymeleaf và workspace hiện có. Giữ Spring MVC, RestClient, JPA, JDK crypto; không thêm dependency.

## 1. Baseline và thứ tự nguồn

- Checkout thực thi: `C:/Users/Admin/Documents/hoc/Car/.scratch/payment-settings-20261108/worktree`.
- Branch `feat/payment-settings-ui`, HEAD `00a60396f2153dfb697e82f6b84a5bd743daf8dc`; S1 là working files chưa commit, không phải một commit riêng.
- Đã đối chiếu trực tiếp **340/340 file** với `.scratch/payment-settings-20261108/S1-final-source-manifest.json` tại checkout gốc: không drift. Kết quả này chỉ xác minh file, không phải một lượt chạy test mới.
- Evidence S1 hiện có: 1.230 Java tests/80 suites, Node 18/18, clean verify/JAR; xem [handoff S1](payment-settings-S1-handoff.md). Chưa có class/form/template/test Settings nào trong source.
- Status lúc khảo sát: root 85 tracked dirty/98 file untracked; worktree S1 130 tracked dirty/71 file untracked; cả hai không có staged change. Số này bao gồm inherited changes, không phải diff của S2.
- Root có lookup hoàn tiền và thay đổi cấu hình/test riêng sau snapshot; worktree S1 chưa có adapter lookup đó. Giữ nguyên baseline S1 trong S2; tích hợp root là công việc riêng, theo từng path/manifest. Không copy toàn bộ worktree sang root.
- Ảnh `uploads/seed-car-5.png` do người dùng chủ động xóa; giữ nguyên quyết định đó.

Nguồn contract: [SDD §2–6](../design/payment-settings-2026-11-08.md), [PS-C/PS-D](payment-settings-implementation-2026-11-08.md#ps-c--payos-settings-cho-owneradmin-40-phút), [điều kiện phiên S2](payment-settings-sessions-2026-11-08.md#4-s2--ps-cps-d-80-phút). Tài liệu này làm rõ thực thi, không thay các interfaces đã chốt.

## 2. Deliverable S2

| Route | Hành vi |
| --- | --- |
| `GET /owner/payment-settings` | Metadata PayOS + SePay của Owner hiện tại; account masked, inputs secret trống |
| `GET /admin/payment-settings` | Metadata PayOS của Admin hiện tại; không có form SePay |
| `POST /owner/payment-settings/payos` | Kiểm tra hai GET PayOS có response signature hợp lệ, rồi lưu mã hóa |
| `POST /admin/payment-settings/payos` | Cùng luồng, chỉ dùng channel thuộc Admin hiện tại |
| `POST /owner/payment-settings/sepay` | Lưu BIN/STK/gateway/secret; channel mới disabled, channel có sẵn giữ enabled |
| `POST /owner/payment-settings/sepay/enable` | Checkbox `webhookConfigured`, revalidate cấu hình và pilot trước bật đúng channel Owner |

Client ID/API Key/Checksum Key đều nhập lại mỗi lần kiểm tra/lưu; không dùng giá trị masked như credential thật. Không có ID user/channel/provider/reference/enabled được chấp nhận từ form. Admin SePay vẫn do cấu hình legacy quản lý.

PayOS hiển thị “GET đã xác thực”, không gọi là đã được phép chi. `ConnectionCheck.funded` chỉ phản ánh kết quả số dư từ probe; không tự bật payout hoặc tạo quyền provider. SePay phân biệt đã lưu, đang bật thu và đã nhận webhook hợp lệ; inbox chưa chứng minh tiền đã được đối soát hoặc receipt đã áp dụng.

## 3. File và interfaces

Tạo trong `src/main/java/com/carrent/payment/`:

- `PaymentSettingsService.java`: public `view`, `savePayos`, `saveSepay`, `enableSepay`; nested `View` và `ChannelSummary` đúng SDD §4.
- `PaymentSettingsTransactions.java`: `prepare`, hai overload `save`, `view`, `enableSepay`; nested `Target(Long channelId, String reference, Long credentialVersion, Long ownerId, Role role)`.
- `PaymentSettingsController.java`: sáu route ở §2, binder allowlist theo từng form, sanitized errors, no-store và PRG success.
- `PayOsSettingsForm.java`: `clientId`, `apiKey`, `checksumKey`; getter/setter, không generated `toString`.
- `SePaySettingsForm.java`: `bankBin`, `accountNumber`, `gateway`, `webhookSecret`; giữ STK dạng String.

Tạo `src/main/resources/templates/payment/settings.html` và hai suite mới `src/test/java/com/carrent/payment/PaymentSettingsTest.java`, `SePaySettingsTest.java`.

Sửa `src/main/resources/templates/fragments/workspace.html`: menu Owner/Admin cùng active section `payment-settings`, giữ chữ ký fragment. Sửa `src/main/resources/application.properties`: `carrent.payment.public-base-url=${CARRENT_PUBLIC_BASE_URL:}`. Chỉ thêm dòng `.env.example` nếu placeholder tương ứng thực sự còn thiếu.

Có thể bổ sung seam fixture tối thiểu trong `PaymentFlowFixture.java` nếu cần truyền public-base-url giả; giữ default cách ly/master-key hiện có. Ưu tiên query EntityManager trong transactions cho candidate/history/lastWebhookAt; không thêm repository chỉ để bọc một query.

S1 production chỉ sửa khi tái hiện được lỗi ảnh hưởng S2 và có regression tương ứng. Không đổi DDL/migration, bootstrap hoặc contract HMAC/dedup/payout để làm settings.

## 4. Những điểm phải xử lý trước khi gọi S2 hoàn tất

### 4.1. Probe PayOS ngoài transaction

`HttpPayOsPayoutClient.checkConnection(Long)` đã có ở dòng 44: GET list rồi balance, xác minh response signature, cho phép credential disabled và trả lỗi generic. Dùng lại qua typed `PayOsChannel(false, ...)` từ form; ID truyền vào chỉ là handle local cố định cho function, không phải channel do request chọn.

Service constructor production `PaymentSettingsService(PaymentSettingsTransactions tx)` được đánh dấu `@Autowired`, delegate sang constructor package-private nhận `Function<PayOsChannel,HttpPayOsPayoutClient.ConnectionCheck>` như kế hoạch gốc. Probe mặc định dùng client hiện có; test cung cấp cùng HTTP client với MockRestServiceServer.

Trình tự: validate input → `prepare` trong transaction ngắn → kết thúc transaction → GET probe → `save` trong transaction mới. Service điều phối không có transaction bao ngoài; không giữ User/channel lock khi chờ HTTP. Trước probe phải kiểm tra quyền và master key; lỗi probe không ghi READ_VERIFIED/ciphertext/metadata mới.

Test seam phải assert trực tiếp:

```java
assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
```

Sau probe phải revalidate current actor, role/status, candidate, reference và credential version. Actor bị ban/đổi role, target đã đổi hoặc credential đã được request khác lưu trong lúc GET chờ thì từ chối persist. Null version nghĩa chưa có credential row; nếu row xuất hiện trước commit cũng là stale.

### 4.2. Khóa, history và giới hạn sửa kênh

`PaymentChannel` không có `@Version`; version nằm ở `PaymentChannelCredential`. Không được chỉ dựa vào optimistic version của credential để bảo vệ toàn bộ channel. Lock User trước, sau đó channel; reload current state sau khi chờ lock, theo pattern `UserService.current` và `PaymentLocks.current`.

Candidate phải đúng user/provider/purpose/ownerRole, kể cả channel disabled. Nhiều candidate hoặc reference collision thì trả conflict cho operator, không chọn dòng đầu hoặc tạo channel thứ hai. Tạo mới dùng reference server-owned `ui:{userId}:{provider}`; lock User để serialize hai first-save của cùng actor, rồi kiểm tra candidate lại trước insert.

History xét đủ năm FK: `payment_intents.channel_id`, `webhook_inbox.channel_id`, `payment_receipts.channel_id`, `refunds.payout_channel_id`, `payout_attempts.payout_channel_id`. Tính cả dữ liệu terminal, không chỉ pending.

**Chọn nhánh bảo thủ được SDD §5 cho phép:** trên channel hiện hữu, S2 cho adopt cùng cấu hình; từ chối thay đổi credential/nguồn nếu chưa có bằng chứng serialize được tất cả writers liên quan. Riêng User/channel lock của settings không phải bằng chứng đó: intent creation và payout preparation chưa cùng khóa channel; subscription payer/collector không luôn giữ User read-lock. Không mở rộng S2 sang sửa toàn bộ writers để cho phép rotation.

Adopt so sánh đúng ba khóa PayOS hoặc BIN/STK/secret/gateway SePay với cấu hình resolver đang dùng; giữ ID/reference/enabled và snapshots lịch sử. Same-config save không tạo thêm channel/credential row; checkedAt/version có thể đổi sau probe, không gọi đó là đổi nguồn. Credential DB hỏng/sai master key trả unavailable, không được coi là absent để dùng legacy hoặc ghi đè.

Nếu implementer muốn cho sửa cấu hình khác trên channel chưa có history, phải chứng minh nhánh đó an toàn bằng test cạnh tranh với writer thật trước khi mở; nếu chưa chứng minh, giữ conflict và hướng dẫn operator. Đây là giới hạn cần ghi trong handoff/UI, không phải một tính năng đã đạt.

### 4.3. Secret và HTTP errors

Validate secret nonblank, tối đa 512 ký tự, không control character; gateway nonblank tối đa 50; BIN đúng sáu chữ số, STK 1–19 chữ số giữ số 0 đầu. Không trim/biến đổi secret làm sai probe hoặc history comparison.

`ProfileController` dòng 88–98 đã có pattern thay bound form và BindingResult bằng bản sạch. Tái sử dụng cách đó: secret fields không `th:field`/`th:value`; errors không giữ rejected values, arguments hoặc message chứa input/provider body. Chỉ View/ChannelSummary metadata đi vào template; không đưa Target/entity/typed credential vào model, flash hoặc redirect URL.

Các lỗi dự kiến cần handler cục bộ trong controller: validation 400, stale/history/multiple candidate 409, actor không hợp lệ 403, master key/credential unavailable 503. Probe lỗi HTTP/signature/timeout trả 502 với thông điệp cố định và form trống; không propagate raw provider/crypto exception hoặc dùng redirect “thành công”.

Không để lỗi settings dự kiến rơi vào `CommonExceptionHandler.business` (redirect Referer) hoặc catch-all log exception. Không cần sửa global advice để đạt điều này. GET, POST lỗi và redirect thành công phải có `Cache-Control: no-store`; không gọi provider hoặc ghi DB trong GET view.

### 4.4. SePay và public URL

Public-base-url do server quản lý, không dùng Host/Forwarded/request URL hoặc field người dùng. URI phải là HTTPS origin có host, không userinfo/query/fragment/path ngoài `/`; chuẩn hóa trailing slash rồi nối `/webhooks/sepay/{channelId}`. Có thể tham khảo validation trong `LocalSePaySetup.publicBaseUrl`, không gọi runner CLI từ UI.

Public URL thiếu/sai: vẫn cho xem/lưu cấu hình nếu các điều kiện khác đạt, không phát URL không hợp lệ và chặn enable với lý do rõ ràng. GET chỉ đọc metadata; `lastWebhookAt` lấy max receivedAt từ inbox của đúng channel, không đọc raw payload hoặc dùng dữ liệu kênh khác.

Enable phải kiểm tra Owner ACTIVE hiện tại, cấu hình DB giải mã được, BIN/STK/gateway/secret hợp lệ, checkbox, public URL và scope PaymentPilot; revalidate Admin pilot hiện tại nếu dùng điều kiện cặp Owner/Admin. Không sửa cờ global hoặc channel Admin. Save channel mới luôn disabled; save/adopt channel cũ không tự tắt/bật kênh đó. Enabled của DB không được suy ra từ READ_VERIFIED.

## 5. Thực thi PS-C rồi PS-D

### PS-C — PayOS và nền HTTP/UI

- [ ] Viết `PaymentSettingsTest` RED cho Owner/Admin GET, Customer/anonymous, CSRF, binding allowlist, stale actor và no-secret response/model; ghi riêng RED compile và RED hành vi nếu có.
- [ ] Implement forms/controller/view/template/menu và transaction prepare/save theo §3–4; reuse cipher/AAD/JSON schema S1.
- [ ] Wire mocked signed GET success/zero balance/failure, assert không có transaction trong probe và không POST payout. Sai signature/403/timeout phải giữ nguyên cấu hình cũ.
- [ ] Test race bằng latch: probe chờ rồi actor đổi role/status hoặc request khác lưu; commit phải từ chối. Test hai first-save không sinh trùng, history đủ năm loại, same-config adopt, DB bad tag không fallback.
- [ ] Chạy focused PS-C và ghi source/evidence checkpoint trước chuyển PS-D.

### PS-D — Owner SePay và xuyên flow

- [ ] Viết `SePaySettingsTest` RED cho BIN/STK/gateway/secret, thiếu checkbox, ngoài pilot, URL origin xấu, stale actor, route Admin/Customer và no-secret errors.
- [ ] Implement save CONFIGURED và enable riêng theo §4.4; query metadata/masking riêng channel của actor, giữ Admin legacy.
- [ ] Fixture save DB → enable → business create intent → assert QR đúng account/BIN/code và vẫn chưa PAID → restart fixture → HMAC webhook MockMvc → inbox → gọi processor hiện có → receipt. Assert DB credential thực sự được dùng, không thay business state bằng SQL.
- [ ] Replay cùng provider transaction ID không có receipt/apply lần hai; sai secret/account/gateway bị chặn; shared Owner/Admin và lịch sử disabled target giữ behavior S1.
- [ ] Chạy aggregate focused S2, Node nếu sửa JS/polling contract, audit baseline và viết `payment-settings-S2-handoff.md`. S2 không tạo JavaScript/polling mới.

Mục tiêu cũ là 40 + 40 phút, không phải thời gian đã đo hoặc bảo đảm hoàn thành. Phần có rủi ro nhất là race/history và sanitized error handling. Ghi mốc bắt đầu/kết thúc thực tế; không cắt kiểm tra để khớp thời gian.

## 6. Test matrix và commands khi implement

Hai suite mới phải có coverage thực tế cho:

| Nhóm | Kết quả cần chứng minh |
| --- | --- |
| Quyền | own-scope; ACTIVE/role DB hiện tại; CSRF; không mass assignment; không đọc chéo Owner/Admin dù dùng cùng khóa |
| PayOS | hai GET có signature; lỗi HTTP/signature/timeout; zero-balance không thành payout-ready; không POST payouts; probe ngoài transaction |
| Persistence | ciphertext không chứa marker secret; reload/restart dùng được; master key thiếu/sai; bad DB record không fallback |
| Race/history | actor đổi khi chờ GET; nullable version stale; hai first-save; đủ năm history FK; same-config adoption và từ chối đổi nguồn |
| SePay | số 0 đầu; trạng thái save/enable; HTTPS URL; pilot; QR/HMAC/inbox/receipt/replay; Admin legacy/shared routing |
| Response/UI | secret không trong HTML/model/BindingResult/Location/flash; masked account; no-store; menu/active và form theo role |

Chạy từ **worktree S1**, với datasource fixture đã kiểm tra. Các suite mới chỉ chạy sau khi được tạo:

```powershell
.\mvnw.cmd '-Dtest=PaymentSettingsTest,PayOsConnectionCheckTest,CredentialCipherTest,ChannelCredentialResolverTest' '-DargLine=-Xmx768m' test
.\mvnw.cmd '-Dtest=SePaySettingsTest,SePaySharedAccountTest,SePayInboxTest,SePayCollectionModeTest,PaymentPilotTest,SubscriptionPaymentTest,SubscriptionOrderTest' '-DargLine=-Xmx768m' test
.\mvnw.cmd '-Dtest=PaymentSettingsTest,SePaySettingsTest,WorkspaceUiTest,PayOsConnectionCheckTest,ChannelCredentialResolverTest' '-DargLine=-Xmx768m' test
```

Tên suite cũ trong ba lệnh đã được xác nhận có file; không có `PaymentSettingsTest`/`SePaySettingsTest` tại thời điểm chuẩn bị. Đây là lệnh tương lai, chưa có kết quả S2. Full clean verify/JAR/browser ba viewport và nghiệm thu tổng thuộc S3; focused pass S2 không thay các mục đó.

Fixture luôn dùng schema riêng, key/provider giả, maintenance/deadline tắt. Cấu hình test phải chặn default probe trước bất kỳ HTTP settings POST nào; MockRestServiceServer chỉ cho GET đúng endpoint cố định. Không dùng credential của root hoặc provider thật để kiểm chứng form.

## 7. Đầu ra S2 và bước tích hợp sau này

S2 chỉ đạt khi cả PS-C/PS-D có implementation, focused GREEN và audit trên source cuối; handoff ghi routes/interfaces, initial failures/reruns, test counts thực tế, feature-file manifest và giới hạn sửa kênh hiện hữu. Evidence mới đặt tại `.scratch/payment-settings-20261108/implementation/S2/` ở checkout gốc, tách log S1.

Giữ nguyên runtime root, private config, DB ứng dụng, HEAD/index của hai checkout, các worktree khác và quyết định xóa ảnh seed. Chưa migrate/enable provider thật, restart/deploy 8080, commit/push/merge hoặc chuyển tiền. Các điều kiện live P4.2-A/P8.8/P5.9 và bộ đếm 83/86 giữ riêng.

Khi tích hợp root sau S3: merge từng thay đổi riêng từ root với source Settings đã kiểm chứng; đặc biệt `PaymentProviderConfiguration`, application properties và fixture có drift song song. Không dùng test S1/S2 để suy bản tích hợp cuối đã pass. Lookup production ở root vẫn là scope khác; không đưa nó vào S2 giữa phiên.

## 8. Kiểm chứng của phiên chuẩn bị

Đã đọc source/interfaces/callers/fixtures nêu trên, xác minh manifest S1 340 file, status/HEAD và sự vắng mặt file Settings. Chỉ thêm tài liệu này và checkpoint trong README của worktree S1; chưa chạy Maven/Node/browser/DB/provider hoặc tạo source S2.

Đánh giá: **đủ cơ sở để triển khai S2 trên baseline S1**, với nhánh sửa kênh bảo thủ và các test transaction/history/no-secret bắt buộc. Chưa có bằng chứng implementation hoặc acceptance S2.
