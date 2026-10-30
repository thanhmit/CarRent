# Payment-core v1 — vận hành P4.3

## Phạm vi

`2026-10-27-01-payment-core.sql` tạo đúng 7 bảng tài chính trên schema legacy hiện hành. File không chọn database, không ghi/xóa dữ liệu legacy, không seed kênh thật và không chạy khi ứng dụng boot. `scriptDB.sql` dành cho bootstrap; không dùng `CREATE TABLE IF NOT EXISTS` của bootstrap làm công cụ sửa schema bị lệch.

Yêu cầu MySQL **8.0.16+**, InnoDB, strict SQL mode và quyền đọc metadata, CREATE TABLE/REFERENCES, CREATE ROUTINE/ALTER ROUTINE/EXECUTE. Migration là **one-shot**: từ chối cả schema đã áp dụng đầy đủ lẫn schema payment-core còn dở. Không sửa file sau khi đã apply; thay đổi tiếp theo phải có migration mới.

## Thứ tự thực hiện có kiểm soát

1. Xác nhận host/schema đích, phiên bản và quyền. Tạm ngừng các thao tác ghi và DDL khác trong cửa sổ bảo trì để schema preflight và backup cùng một baseline. Không tự chạy lên DB ứng dụng từ một phiên implement.
2. Backup schema + dữ liệu vào đường dẫn đã chọn; mật khẩu nhập ở prompt, không viết vào command/history:

   ```powershell
   & 'C:/Program Files/MySQL/MySQL Server 8.0/bin/mysqldump.exe' --host=localhost --port=3306 --user=root --password --single-transaction --routines --triggers --hex-blob --no-tablespaces --set-gtid-purged=OFF --result-file='C:/Backups/carrent-before-payment-core.sql' carrent
   if ($LASTEXITCODE -ne 0) { throw 'Backup failed' }
   Get-FileHash -Algorithm SHA256 -LiteralPath 'C:/Backups/carrent-before-payment-core.sql'
   ```

   Thư mục backup phải tồn tại. Không đưa backup chứa dữ liệu cá nhân/password hash vào Git. Kiểm tra restore trên **schema kiểm chứng riêng** và đối chiếu dữ liệu trước khi coi backup sử dụng được. Dùng tên database ở cuối lệnh dump như trên; `--databases` sẽ thêm lệnh chọn/tạo database vào bản dump và có thể khiến restore chạy nhầm schema.
3. Thử migration trên bản restore riêng trước. Dùng connection mới, không có transaction ghi dở vì CREATE PROCEDURE/DDL cũng implicit commit. Xác nhận `SELECT DATABASE(), VERSION(), @@SESSION.sql_mode;` trong đúng connection. Lệnh sau chọn schema tường minh và đặt marker sau khi người vận hành đã kiểm chứng backup:

   ```powershell
   & 'C:/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe' --host=localhost --port=3306 --user=root --password --database=carrent --execute="SET @carrent_payment_core_backup_confirmed=1; SOURCE docs/migrations/2026-10-27-01-payment-core.sql;"
   if ($LASTEXITCODE -ne 0) { throw 'Migration stopped; inspect schema before retrying' }
   ```

   Chạy từ gốc repo và đổi `--database` sang schema kiểm chứng trong lần diễn tập. **Không dùng `--force`**, không bỏ qua lỗi. Marker không tự chứng minh backup hợp lệ; đó là xác nhận của người vận hành.
4. Đối chiếu row count và fingerprint toàn bộ 7 bảng legacy (gồm hash mật khẩu, amount, paid_at và timestamp); kiểm tra 7 bảng mới rỗng, FK/CHECK/unique và mapping. Chỉ đưa thay đổi vào DB ứng dụng trong một lần vận hành đã được cho phép riêng.

## Khi có lỗi hoặc bị ngắt

MySQL DDL implicit commit: `ROLLBACK` không xóa được các bảng đã tạo. Không tự chạy lại bootstrap, xóa bảng, tắt FK hoặc ghi đè dữ liệu để "hoàn tác".

- Preflight từ chối trước khi tạo bảng tài chính. Procedure `carrent_payment_core_v1` có thể còn tồn tại sau lỗi/ngắt; kiểm tra `SHOW CREATE PROCEDURE carrent_payment_core_v1` và nguồn file trước khi xóa **đúng procedure này**. Không dùng `DROP PROCEDURE IF EXISTS` để che một đối tượng không rõ nguồn gốc.
- Nếu đã có một phần/toàn bộ bảng core: giữ trạng thái đó, ghi lại `SHOW CREATE TABLE`/row counts và điều tra. Có thể khôi phục backup vào schema khác để đối chiếu và chuẩn bị kế hoạch phục hồi. Không suy ra rằng mọi dữ liệu trong bảng core đều có thể bỏ đi.
- Sau khi thành công procedure được xóa. Migration không tự đánh dấu hoàn thành bằng một bảng version; trạng thái được xác nhận từ 7 bảng, metadata và checkpoint vận hành.

## Hợp đồng cho các task tiếp theo

- VND là `BIGINT`; số phải trả/nhận/hoàn dương, số đã phân bổ/settled được phép bằng 0. Tiền thu không bị giảm khi tạo refund.
- `subject_type + subject_id` phân biệt BOOKING và SUBSCRIPTION_ORDER. DB kiểm tra purpose/business key tương ứng; handler Phase 6/8 kiểm tra đối tượng thực sự tồn tại và các bên có đúng quyền. Không có FK giả tới booking, cũng không có FK tới bảng subscription chưa được tạo.
- Inbox lưu payload **bytes gốc**. ID SePay là chuỗi thập phân dương canonical; không dùng UUID v2/referenceCode/namespace nguồn làm giao dịch khác. `(channel_id, provider_transaction_id)` chống trùng theo từng kênh. P4.2-A vẫn phải xác minh equality webhook/v1 trước ingest chung.
- Receipt có thể chưa tìm được intent (`intent_id NULL`) để không bỏ mất giao dịch đã nhận. Composite FK giữ receipt khớp channel/transaction của inbox và channel của intent khi có intent.
- Core dùng UTC `DATETIME(6)`, Java `Instant` với `TIMESTAMP_UTC`. Caller cung cấp các mốc bằng Clock; không dùng default thời gian session hoặc thay timezone toàn bộ entity legacy. `ready_at/due_at` cùng NULL hoặc cách đúng 1 giờ; service P4.7 giữ chúng không reset.
- Channel chỉ lưu credential reference, mặc định `enabled=false`; không lưu secret. Resolver/handler phải kiểm tra role/chủ kênh/provider/purpose, collector của receipt và nguồn chi; FK user riêng lẻ không chứng minh các quan hệ nghiệp vụ này.
- Snapshot nguồn/amount/đích, reference và idempotency key nằm trong payout attempt. Tổng số hoàn theo receipt, một intent ACTIVE, một lần chi chưa rõ và chuyển trạng thái hợp lệ cần khóa/transaction service ở P4.4–P4.9; unique/check riêng từng dòng không thay các guard đó. `UNKNOWN` không được tự gửi lại; adapter hiện chưa phát NOT_SENT.
- Lease/retry/deadline/overdue notification có cột lưu bền vững; P4.8/P4.9 đã có worker và job trên DB kiểm chứng, scheduler mặc định tắt. `OVERDUE` là thuộc tính thời gian, không phải kết quả chuyển tiền thất bại.
- Repository `findByIdForUpdate` lấy pessimistic row lock; service vẫn phải refresh/reload entity đã managed theo D-13 trước khi quyết định dựa trên trạng thái. Phương thức này không tự xóa snapshot/entity cũ khỏi persistence context.

## Kiểm chứng tự động

```powershell
.\mvnw.cmd -o '-Dmaven.compiler.useIncrementalCompilation=false' '-Dtest=PaymentMigrationTest,PaymentModelTest' clean test
```

Tests cần quyền tạo/xóa **schema tạm**, đọc cấu hình datasource hiện có mà không in credential. Mỗi schema có tên `carrent_p43_<32 hex>`, được kiểm tra trước khi xóa. Bootstrap loader bỏ đúng `CREATE DATABASE ... carrent`/`USE carrent` và từ chối các lệnh chọn database còn lại. Fixture legacy là snapshot cố định trong `src/test/resources/payment/legacy-bootstrap.sql`; test không migration hoặc reset DB ứng dụng. Không có provider/network tiền thật.

## Refund accounts v1 — bổ sung P4.7

`2026-10-30-01-refund-accounts.sql` chạy **sau** payment-core v1; chỉ thêm `refund_recipient_accounts`, không sửa 7 core/legacy hoặc seed. `scriptDB.sql` đã có cùng DDL cho bootstrap. Không dùng bootstrap để sửa schema hiện hữu; không DDL khi ứng dụng boot. DB ứng dụng chưa áp dụng hai migration.

Áp dụng cùng quy trình bảo trì, backup/restore thử và kiểm tra schema nguồn ở trên. Dùng connection mới, chọn đúng schema và xác nhận backup bằng marker riêng:

```powershell
& 'C:/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe' --host=localhost --port=3306 --user=root --password --database=carrent --execute="SET @carrent_refund_accounts_backup_confirmed=1; SOURCE docs/migrations/2026-10-30-01-refund-accounts.sql;"
if ($LASTEXITCODE -ne 0) { throw 'Refund-account migration stopped; inspect schema before retrying' }
```

Đổi database sang bản restore riêng khi diễn tập. Không `--force`. Preflight yêu cầu MySQL 8.0.16+, strict mode, đủ users + 7 core InnoDB và target chưa có. Migration one-shot; sau lỗi procedure `carrent_refund_accounts_v1` có thể còn tồn tại: kiểm tra đúng nguồn/procedure trước xử lý, không tự drop hoặc chạy lại khi chưa đánh giá trạng thái. DDL implicit commit, không dựa vào ROLLBACK để xóa bảng.

Đối chiếu fingerprint legacy/core, bảng mới rỗng, PK/FK RESTRICT/CHECK và bootstrap parity. Dữ liệu mới là opt-in theo user, bank/account ASCII giữ số 0 đầu và verified_at UTC; default chỉ prefill, không sửa snapshot refund/attempt. Test migration đã chạy trên DB fixture và dọn, chưa phải lần vận hành DB ứng dụng được cho phép.
