> **CANCELLED - 2026-11-09:** Chủ dự án hủy Payment Settings S1/S2/S3 và chọn source trước S1. Nội dung bên dưới chỉ là lịch sử thiết kế/kiểm chứng, không phải kế hoạch đang thực thi. Không tiếp tục implement, migrate hoặc kích hoạt từ tài liệu này.

# Payment settings — chia 3 phiên implement trong 4 giờ

> **Nghiệm thu S1 — 09/11/2026:** S1 COMPLETE trong worktree: 1.230/1.230 Java tests (80 suites), 81 test mới, Node 18/18, clean verify/JAR và audit đạt. S2/S3 NOT_STARTED. Xem [bàn giao S1](payment-settings-S1-handoff.md); chỉ ghi nhận local completion, chưa tích hợp root hoặc bật provider thật.

> **Checkpoint 09/11/2026:** bắt đầu S1 theo [kế hoạch thực thi cập nhật](payment-settings-S1-execution-2026-11-09.md). Worktree cũ thiếu BEFORE/calendar; làm mới baseline có kiểm soát một lần trước S1. Chưa bắt đầu implementation hoặc chạy lại preflight.

Ngày **2026-11-08**. Người dùng đã đồng ý mục tiêu 4 giờ. Trạng thái hiện tại: **đã lên kế hoạch, chưa bắt đầu phiên implement nào**.

## 1. Mục tiêu và thứ tự

Hoàn tất PS-01–08 của [SDD](../design/payment-settings-2026-11-08.md): PayOS cho Owner/Admin, SePay cho Owner, credential mã hóa DB, resolver động, UI theo quyền, test/browser và bàn giao. Phạm vi không bao gồm provider cấp quyền lookup, tự tạo webhook, rotation có lịch sử hoặc chuyển tiền thật. Không tăng scope giữa các phiên để gọi P4.2-A/P8.8 hoàn tất.

| Phiên | Task | Thời lượng | Mốc cộng dồn | Đầu ra |
| --- | --- | ---: | ---: | --- |
| **S1 — Nền DB và runtime** | PS-A + PS-B | **80 phút** | **80/240** | Migration, mã hóa, resolver và toàn bộ caller hoạt động với DB + legacy |
| **S2 — Hoàn thiện chức năng/UI** | PS-C + PS-D | **80 phút** | **160/240** | Hai trang theo role; PayOS kiểm tra/lưu; Owner SePay lưu, hướng dẫn webhook, bật thu theo pilot và tạo QR |
| **S3 — Nghiệm thu và bàn giao** | PS-E + PS-F | **80 phút** | **240/240** | Full tests/JAR, browser, sửa lỗi, audit và hướng dẫn vận hành |

Chạy **tuần tự trong cùng worktree**, không tạo ba branch hoặc ba worktree. S1/S2 cùng sửa resolver/form/service nên làm song song sẽ tốn thời gian merge và tăng rủi ro sai guard. Làm mới baseline có manifest một lần trước S1 theo checkpoint 09/11, rồi không kéo source root giữa các phiên. Kết quả 96/96 ngày 08/11 thuộc baseline lịch sử, không thay cho preflight source mới.

Mỗi task trong [implementation plan](payment-settings-implementation-2026-11-08.md) vẫn giữ checklist riêng. Phiên là đơn vị bàn giao, không thay tên task hoặc gom điều kiện nghiệm thu.

## 2. Quy tắc chung cho mọi phiên

- Worktree cố định: `C:/Users/Admin/Documents/hoc/Car/.scratch/payment-settings-20261108/worktree`, branch `feat/payment-settings-ui`, base `00a60396f2153dfb697e82f6b84a5bd743daf8dc`.
- Dùng `superpowers:executing-plans` và Ponytail; thực hiện inline, chỉ dùng subagent khi được người dùng yêu cầu.
- Đọc SDD, implementation plan, worktree handoff và bàn giao phiên trước. Giữ các interfaces đã chốt trong SDD; không phỏng đoán secret/provider API.
- Test bằng JDK 21, DB/schema fixture riêng và provider giả; không sửa DB ứng dụng, không bật provider/payout/scheduler thật hoặc restart8080.
- Không stage/commit/push/merge, không xóa nhánh, không copy .env/live keys. Mọi inherited local change nằm trong manifest baseline, không được nhận là feature diff.
- Trong S1/S2 chỉ chạy focused suite có liên quan sau mỗi thay đổi. S3 chạy full clean verify một lần trên source cuối; chỉ rerun khi có lỗi hoặc sửa source, không lặp full suite chỉ vì sang chat mới.
- Ghi log/evidence theo `S1`, `S2`, `S3` trong root `.scratch/payment-settings-20261108/implementation/`; ghi `started_at`, `ended_at` theo ISO +07:00 và `elapsed_seconds` thực tế. Không điền PASS cho việc chưa chạy. Bốn giờ là thời gian thực hiện, không tính khoảng chờ người dùng giữa các chat.
- Đến phút 60 của mỗi phiên cập nhật phần đã đạt và thời gian còn lại; lỗi chưa giải quyết phải ghi vào handoff. Không bỏ encryption/CSRF/ownership/history guard hoặc che test failure để đạt mốc giờ.

## 3. S1 — PS-A/PS-B, 80 phút

**Bắt đầu khi:** đọc được manifest snapshot; branch/base đúng; hoàn tất baseline refresh và focused preflight theo S1-0 của kế hoạch 09/11. Giữ baseline 08/11 làm lịch sử, không coi đó là preflight cho source mới.

Đọc thêm [S1 preimplementation](payment-settings-S1-preimplementation-2026-11-08.md) trước khi viết code. Tài liệu chốt provider-scoped query, cipher availability, migration boot-path, properties-only context và cách giữ history khi key hỏng; preflight bổ sung 49/49 test đã đạt, chưa phải nghiệm thu S1.

**Phân bổ:** PS-A 40 phút (schema/cipher/store), PS-B 40 phút (resolver/caller + focused tests). Những phút cuối mỗi task ghi log/checkpoint ngay; handoff ngắn nằm trong ngân sách 80 phút, không cộng thêm thời gian họp/review.

**Phạm vi file:** đúng danh sách Create/Modify PS-A/PS-B trong implementation plan. Không tạo controller/template/menu của S2. Bổ sung bảng vào bootstrap và cleanup fixture phải đi cùng migration.

**Điều kiện ra phiên:**

- [ ] Migration cần marker, từ chối partial/đã có, không đổi dữ liệu cũ; bootstrap parity và FK đúng.
- [ ] AES-GCM roundtrip, nonce mới mỗi lần, AAD/tag/key sai bị chặn; không có plaintext trong DB/log.
- [ ] Resolver đọc DB động, legacy fallback chỉ khi absent-row; lỗi giải mã không fallback.
- [ ] QR create/reuse/view, SePay verify/shared-secret/gateway, recipient và payout đều đã đổi sang resolver; constructor cũ của SePay verifier vẫn dùng được.
- [ ] Targeted tests PS-A/PS-B đạt; có test restart và hai role cùng bộ khóa nhưng khác mapping; source biên dịch đầy đủ.
- [ ] Có `docs/plans/payment-settings-S1-handoff.md` trong worktree: thời gian, file diff so baseline, signatures đã có, test/log và điểm chưa đạt. Không dự đoán test count.

**Đầu vào cho S2:** store/cipher/resolver được test, không cần sửa lại thiết kế mã hóa. Nếu một mục ra phiên chưa đạt, ghi S1 INCOMPLETE và giải quyết mục đó trước PS-C; không gọi S1 hoàn tất rồi chuyển lỗi sang nghiệm thu cuối.

**Prompt giao phiên:**

```text
Triển khai chỉ phiên S1 (PS-A + PS-B) theo docs/plans/payment-settings-sessions-2026-11-08.md và implementation plan tương ứng. Dùng worktree C:/Users/Admin/Documents/hoc/Car/.scratch/payment-settings-20261108/worktree, branch feat/payment-settings-ui. Đọc SDD bổ sung và manifest baseline trước khi sửa. Mục tiêu 80 phút gồm focused tests; hoàn tất migration/encrypted store/resolver và tất cả caller. Giữ backward compatibility và guard tài chính. Không làm UI, không migrate DB ứng dụng, dùng provider fixture, không commit/push. Viết payment-settings-S1-handoff.md với evidence thực tế rồi dừng trước S2.
```

## 4. S2 — PS-C/PS-D, 80 phút

**Bắt đầu khi:** S1 đủ điều kiện ra phiên và handoff ghi source/test tương ứng. Kiểm tra actual diff của worktree, không dựa duy nhất vào lời nhắn của phiên trước.

**Phân bổ:** PS-C 40 phút cho PayOS hai role; PS-D 40 phút cho Owner SePay, hướng dẫn webhook, enable có xác nhận và QR/inbox fixture. Dùng một template chung để tránh lặp form/menu/layout.

**Phạm vi file:** đúng PS-C/PS-D; chỉ sửa S1 nếu cần khắc phục lỗi được tái hiện và ghi lại test tương ứng. Không làm trang SePay Admin, OAuth, polling mới hoặc liên kết Bảo Kim trực tiếp.

**Điều kiện ra phiên:**

- [ ] Owner/Admin chỉ xem/lưu kết nối của chính mình; CUSTOMER, inactive/stale actor, thiếu CSRF hoặc mass assignment bị chặn.
- [ ] PayOS nhập ba khóa → signed GET ngoài transaction → lưu mã hóa sau revalidate → reload/restart vẫn dùng được; probe lỗi không ghi đè cấu hình cũ; không POST payout hoặc bật chi.
- [ ] SePay lưu BIN/STK/gateway/secret, giữ số 0 đầu, server tạo HTTPS webhook URL; Owner xác nhận bật thu riêng và vẫn theo pilot/global gates.
- [ ] QR của kênh DB và signed webhook fixture vào inbox/receipt được; duplicate không cấp/ghi trùng. SePay Admin legacy không bị ảnh hưởng.
- [ ] Lỗi form/success/GET không chứa secret trong HTML, BindingResult render hoặc redirect; key master thiếu báo đúng trạng thái.
- [ ] History adoption giống hệt được phép; đổi nguồn/secret có lịch sử và stale version bị chặn. Hai mapping dùng chung khóa không có quyền đọc chéo.
- [ ] Targeted tests PS-C/PS-D đạt; tạo `docs/plans/payment-settings-S2-handoff.md` có route/forms/signatures, evidence và rủi ro còn lại.

**Đầu vào cho S3:** chức năng và UI đã có, focused xanh; S3 tập trung full regression/browser/khắc phục, không phải nơi viết phần backend còn bỏ trống.

**Prompt giao phiên:**

```text
Tiếp tục chỉ phiên S2 (PS-C + PS-D) theo docs/plans/payment-settings-sessions-2026-11-08.md, SDD và implementation plan. Dùng nguyên worktree C:/Users/Admin/Documents/hoc/Car/.scratch/payment-settings-20261108/worktree, branch feat/payment-settings-ui. Đọc và đối chiếu payment-settings-S1-handoff.md với source trước khi làm. Mục tiêu 80 phút: UI PayOS Owner/Admin, UI SePay Owner, lưu an toàn, resolver động và QR/webhook fixture xuyên flow. Không bật chi hoặc dùng provider thật; không đổi nguồn lịch sử, không mở scope. Chạy focused tests, viết payment-settings-S2-handoff.md rồi dừng trước S3. Không commit/push/migrate DB ứng dụng.
```

## 5. S3 — PS-E/PS-F, 80 phút

**Bắt đầu khi:** S1/S2 có đầy đủ tính năng và focused evidence; kiểm tra actual source/manifest thay vì mặc định hai handoff là đúng.

**Phân bổ trong 65 phút PS-E:** khoảng 20 phút full clean verify/JAR và Node; 25 phút browser/kiểm tra bảo mật; 20 phút sửa lỗi, rerun đúng phần bị ảnh hưởng và audit. 15 phút PS-F cho handoff/hướng dẫn. Nếu source thay đổi sau full suite, chạy lại kiểm chứng phù hợp và full suite trước xác nhận kết quả cuối; không dùng log của tree cũ.

**Điều kiện ra phiên:**

- [ ] Full clean verify có JAR, XML 0 failures/errors/skips; Node polling đạt. Ghi số suite/test thực tế và hash source/JAR.
- [ ] Browser 1440/390/320, Owner/Admin/Customer: menu, role, validate, focus, reload và no-secret DOM. Screenshot chỉ chứa fixture keys giả.
- [ ] Race/stale actor, optimistic version, bad cipher/no fallback, history guards, payout disabled và SePay legacy được kiểm chứng.
- [ ] DB ứng dụng/private config/root HEAD/index giữ nguyên; schema/port fixture dọn đúng và xác minh. Feature diff tách khỏi inherited baseline.
- [ ] PS-01–08 được đánh dấu bằng evidence; `docs/plans/payment-settings-implementation-handoff.md` có thời gian thực tế, test logs/browser/JAR, hướng dẫn migration/master key/webhook và giới hạn live.
- [ ] Cập nhật trạng thái SDD/plan theo kết quả đã đạt, không đổi bộ đếm/gate P4.2-A/P8.8/P5.9 bằng bằng chứng fixture.

**Prompt giao phiên:**

```text
Triển khai phiên S3 (PS-E + PS-F) theo docs/plans/payment-settings-sessions-2026-11-08.md. Dùng nguyên worktree C:/Users/Admin/Documents/hoc/Car/.scratch/payment-settings-20261108/worktree, branch feat/payment-settings-ui. Đọc và xác minh hai handoff S1/S2 với actual source. Mục tiêu 80 phút: full regression/JAR, Node, browser ba viewport/ba role, sửa lỗi trong scope, audit bảo toàn và cleanup, rồi viết payment-settings-implementation-handoff.md. Chỉ gọi hoàn thành khi PS-01–08 có evidence trên source cuối. Không thêm tính năng, không chuyển tiền thật, không migrate DB ứng dụng, không commit/push/merge hoặc xóa nhánh. Báo rõ tổng thời gian và các gate provider/live còn chờ.
```

## 6. Nguyên tắc chốt 100%

**100% đợt tính năng** là PS-01–08 đạt theo source/test/browser/handoff. Việc tài khoản PayOS được cấp quyền chi/lookup và giao dịch thật được đối soát vẫn là các điều kiện riêng của P4.2-A/P8.8. S1/S2 không được tự nhận 100% chỉ vì biên dịch được; S3 không được giảm checklist để khớp mục tiêu 4 giờ.

Ghi tổng thời gian thực tế của ba phiên. Nếu vượt 240 phút, báo mức vượt và nguyên nhân; tiếp tục sửa trong phạm vi được giao cho đến khi đạt, không tuyên bố hoàn tất giả hoặc dừng xử lý một lỗi còn làm luồng sai chỉ vì đã chạm mốc thời gian.
