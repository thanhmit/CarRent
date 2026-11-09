# Chạy CarRent local với ngrok và SePay cho từng thành viên

Ngày đối chiếu code và tài liệu: **2026-11-07**. Các lệnh bên dưới dùng **Windows PowerShell**, chạy từ thư mục gốc project.

## 1. Cách nhóm sử dụng

Mỗi thành viên có **tài khoản ngrok, dev domain và database local riêng**. Không cần Docker hoặc deploy lên hosting.

```text
SePay của thành viên → HTTPS ngrok của thành viên → localhost:8080 → MySQL local của thành viên
```

- Mỗi người tự cấu hình SePay trong môi trường của mình. Test mode không cần chuyển tiền thật; Live cần ngân hàng đã liên kết của người đó.
- Không đổi URL webhook trong tài khoản SePay của người khác để trỏ về máy mình. Không dùng chung một webhook rồi kỳ vọng dữ liệu tự đồng bộ giữa các database.
- Owner/Admin bên dưới là vai trò trong CarRent, không phải thành viên nhóm hay tài khoản đăng nhập ngrok.
- ngrok chỉ mở đường kết nối. Chạy được tunnel **chưa có nghĩa thanh toán đã hoạt động**.
- Tunnel ở đây công khai toàn bộ cổng ứng dụng. Dùng dữ liệu thử và đổi mật khẩu mặc định của tài khoản seed trước khi mở tunnel.

> **Cập nhật tích hợp 2026-11-07:** mã mới là `CR` + 30 ký tự hex; một webhook đã có thể định tuyến tiền về đúng collector Owner/Admin dùng chung ngân hàng. Hồi quy local không thay bằng chứng nhận tiền thật. Các intent cũ có hậu tố 32 ký tự giữ nguyên lịch sử; để hết hạn/hủy theo nghiệp vụ rồi mở intent mới trước khi thử Live.

### 1.1. Trường hợp đã có SePay và Owner/Admin dùng chung ngân hàng

**Lựa chọn của chủ dự án:** Owner và Admin cùng nhận tiền về một tài khoản ngân hàng đã liên kết SePay. Không cần tạo thêm tài khoản SePay chỉ để tách hai vai trò này.

Các thông tin cần lấy/chuẩn bị:

| Thông tin | Nơi lấy | Nơi sử dụng |
| --- | --- | --- |
| Tên ngân hàng | SePay → Tài khoản ngân hàng → tài khoản đã liên kết | Xác định đúng ngân hàng nhận tiền |
| BIN ngân hàng, 6 chữ số | Thông tin ngân hàng nếu có hiển thị; nếu không, đối chiếu tên ngân hàng với [danh sách VietQR](https://api.vietqr.io/v2/banks), lấy trường `bin` | `payment_channels.bank_bin` của cả Owner và Admin |
| Số tài khoản nhận tiền | Chi tiết ngân hàng đã liên kết trên SePay; giữ nguyên số 0 đầu | `payment_channels.account_number` của cả hai kênh |
| Tên chủ tài khoản | Chi tiết ngân hàng | Đối chiếu đúng người nhận; code hiện tại không có property SePay bắt buộc cho tên này |
| Môi trường Test hay Live | Chế độ đang chọn trên Dashboard | Ngân hàng đã liên kết thật thuộc Live; Test cần tài khoản/mapping riêng |
| Secret Key HMAC | Bước Bảo mật khi tạo webhook, chọn HMAC-SHA256; lấy/tạo theo form và lưu riêng | Secret xác thực webhook; không phải mật khẩu ngân hàng, ngrok authtoken hay API token truy vấn |
| HTTPS domain ngrok | Dòng Forwarding sau khi chạy `ngrok http 8080` | Phần domain trong URL webhook |
| ID Owner/Admin CarRent và ID kênh thu | DB local, xem mục 4.2 | Mapping chủ kênh; không lấy các ID này từ SePay |

**Dùng chung tài khoản nhận tiền không có nghĩa dùng một ảnh QR tĩnh cho mọi đơn.** CarRent sinh QR theo từng intent, giữ chung ngân hàng/số tài khoản nhưng thay số tiền và nội dung chuyển khoản để nhận diện đơn. Không cần tải ảnh QR từ SePay rồi đưa vào project.

**Chỉ tạo một webhook cho tài khoản chung**, dùng URL Owner được lệnh setup in sau dòng `Use ONE shared-bank webhook`. Endpoint vẫn là `/webhooks/sepay/{channelId}`. Sau khi xác thực HMAC, hệ thống tìm mã intent và định tuyến về collector có cùng BIN, số tài khoản và secret; kiểm tra thêm gateway theo cấu hình. Thuê xe thuộc Owner, phí gói thuộc Admin. Không bỏ `channelId` khỏi URL.

Một transaction ngân hàng chỉ được ghi nhận một lần trong nhóm cùng tài khoản nhận, kể cả gửi lại qua URL kênh còn lại hoặc gửi đồng thời. Mã không xác định/khác kênh tin cậy được giữ để đối soát, không tự đánh dấu đơn đã thanh toán. Cấu hình này dành cho tài khoản chính không dùng VA/TKP; `subAccount` khác rỗng hiện bị từ chối.

### 1.2. Điền `.env` và lấy URL có ID thật — cách dùng hiện tại

Database local `carrent` trên máy chủ dự án đã được nâng cấp ngày 2026-11-07 bằng 5 migration hiện có, sau backup/restore thử và đối chiếu dữ liệu cũ. Máy thành viên mới vẫn phải chuẩn bị schema theo mục 2.

File `.env` ở gốc project được Git ignore; mẫu chia sẻ là [`.env.example`](../.env.example). Spring Boot đọc `.env` dưới dạng **Java properties**: dùng `KEY=value`, không thêm `export`, không bọc giá trị trong dấu nháy. Ký tự escape tuân theo Java properties; HMAC secret dạng chuỗi hex tránh được việc escape.

Điền các trường sau trực tiếp trong `.env`, không gửi giá trị bí mật vào chat:

```properties
CARRENT_SEPAY_BANK_BIN=
CARRENT_SEPAY_GATEWAY=TPBank
CARRENT_SEPAY_ACCOUNT_NUMBER=
CARRENT_SEPAY_WEBHOOK_SECRET=
CARRENT_SEPAY_OWNER_ID=2
CARRENT_SEPAY_ADMIN_ID=1
CARRENT_PUBLIC_BASE_URL=https://cloak-siberian-cartoon.ngrok-free.dev
```

- `BANK_BIN`: BIN 6 chữ số của ngân hàng chung; `ACCOUNT_NUMBER`: số tài khoản chung, giữ số 0 đầu.
- TPBank dùng BIN **970423**, gateway **TPBank**. `250605` không phải BIN TPBank. Ngân hàng khác cần đổi cả BIN và gateway đúng payload SePay; tên chủ tài khoản dùng để bạn đối chiếu khi quét QR, không phải secret hay BIN.
- `WEBHOOK_SECRET`: Secret Key HMAC đã chọn trên SePay. Hai reference local `owner-live`/`admin-live` tham chiếu cùng biến này; điền secret **không tự bật** kênh.
- ID Owner `2`, Admin `1` đã xác minh trên máy chủ dự án; máy khác phải SELECT user của database đó. Có thể chọn Owner ACTIVE khác nếu đúng tài khoản dùng để kiểm thử.
- Domain phải là origin HTTPS của ngrok trên máy bạn, không gồm `/webhooks/...`.
- Cấu hình MySQL hiện có vẫn nằm ở `application-local.properties`. Không ghi đè cấu hình DB đang chạy.

Sau khi điền **BIN và số tài khoản**, chạy từ gốc project để tạo kênh thu và in URL thật:

```powershell
.\mvnw.cmd -o spring-boot:run '-Dspring-boot.run.profiles=sepay-setup' '-Dspring-boot.run.arguments=--server.port=0 --carrent.payment.maintenance.enabled=false --carrent.rental.deadlines.enabled=false'
```

Lệnh dùng cùng cơ chế nạp `.env` của ứng dụng, ghi cả hai kênh trong một transaction và thoát khi xong. Kết quả có dạng `SePay OWNER channelId=... webhook=https://.../webhooks/sepay/...` và dòng tương ứng Admin. ID chỉ xuất hiện khi tạo được row thật; để trống ngân hàng/số tài khoản thì lệnh báo tên trường thiếu và không tạo row.

Các kênh mới luôn **tắt**. Chạy lại cùng cấu hình giữ nguyên ID và không sửa trạng thái enabled hiện có. Nếu ngân hàng/số tài khoản/chủ kênh khác row đã tạo, lệnh từ chối toàn bộ thay vì ghi đè lịch sử. Lệnh không chạy migration và không gọi ngân hàng/SePay/payOS. Lưu **một** webhook tài khoản chung theo mục 6, dùng URL Owner vừa in.

### 1.3. Bật thu tiền sau khi đã điền HMAC secret

Sau khi lưu webhook trên Dashboard và điền cùng secret vào `.env`, chạy:

```powershell
.\mvnw.cmd -o spring-boot:run '-Dspring-boot.run.profiles=sepay-activate'
```

Lệnh kiểm tra cấu hình/chủ kênh, bật đúng hai kênh SePay trong một transaction rồi thoát. Thiếu secret hoặc mapping sai thì từ chối, không tạo một nửa. Không bật/sửa kênh payOS và không chạy scheduler. Đừng chạy kết hợp với profile runtime.

Dừng tiến trình Spring Boot cũ bằng Ctrl+C trong terminal đang chạy, rồi mở runtime chỉ thu tiền:

```powershell
.\mvnw.cmd -o spring-boot:run '-Dspring-boot.run.profiles=sepay-local'
```

Profile này bật pilot theo hai ID trong `.env`, mua gói/thuê xe 30%/70%, xử lý inbox và deadline. **Chi hoàn tiền và query chi đều tắt** (`carrent.payment.payout.enabled=false`, provider payOS tắt). Không thêm profile/cấu hình khác ghi đè các cờ này. Hoàn tiền vẫn có thể hiện chờ xử lý; xem mục 7.4. Thay `.env` cần khởi động lại app để nạp giá trị mới.

## 2. Chuẩn bị máy và database

1. Cài Git, **JDK 21**, **MySQL 8.0.16+**. Project có Maven Wrapper, không bắt buộc cài Maven riêng.
2. Clone repository bằng URL Git nhóm đang dùng, rồi mở PowerShell tại thư mục có `pom.xml` và `mvnw.cmd`.
3. Kiểm tra:

   ```powershell
   java -version
   git --version
   mysql --version
   ```

4. Nếu chưa có cấu hình local, sao chép mẫu; giữ file hiện có nếu đã cấu hình:

   ```powershell
   if (-not (Test-Path -LiteralPath .\application-local.properties)) {
       Copy-Item .\application-local.properties.example .\application-local.properties
   }
   git check-ignore application-local.properties
   ```

   Lệnh cuối phải in `application-local.properties`. File này đã được `.gitignore` loại trừ.

5. **Chỉ với máy chưa có database `carrent`**, tạo schema và seed theo README:

   ```powershell
   mysql -h localhost -P 3306 -u root -p --execute="source scriptDB.sql"
   ```

   Nhập mật khẩu khi MySQL hỏi. Nếu `mysql` chưa có trong PATH, dùng đường dẫn `mysql.exe` trên máy.

   **Database đã có dữ liệu:** backup và làm theo [hướng dẫn migration](migrations/README.md). Không chạy lại bootstrap để sửa schema cũ; không bật Hibernate tự sửa bảng. Giữ sáu ảnh `uploads/seed-car-1.png` đến `seed-car-6.png`.

## 3. Tạo tài khoản và cài ngrok

1. Mở [đăng ký ngrok](https://dashboard.ngrok.com/signup), đăng ký bằng tài khoản của chính bạn và hoàn tất bước xác minh/onboarding được yêu cầu.
2. Mở [trang tải ngrok cho Windows](https://ngrok.com/downloads/windows). Cài từ Microsoft Store hoặc dùng lệnh chính thức:

   ```powershell
   winget install ngrok -s msstore
   ```

3. Mở cửa sổ PowerShell mới, kiểm tra:

   ```powershell
   ngrok version
   ```

4. Trong Dashboard ngrok, tìm **Your Authtoken**. Token này thuộc ngrok, không phải secret/API token của SePay.
5. Trong PowerShell, dùng lời nhắc ẩn để nhập token; không viết token thật vào lệnh, Git hoặc tin nhắn:

   ```powershell
   $ngrokToken = Read-Host 'Nhap ngrok authtoken cua ban' -AsSecureString
   ngrok config add-authtoken ([System.Net.NetworkCredential]::new('', $ngrokToken).Password)
   Remove-Variable ngrokToken
   ```

   Lệnh này lưu token vào cấu hình ngrok trên máy, ngoài project. Không chia sẻ file cấu hình đó.

6. Trong Dashboard, tìm **Domains** và ghi nhận dev domain được cấp. Theo tài liệu hiện tại, gói miễn phí có một dev domain gắn với tài khoản, giữ nguyên sau khi restart agent; `ngrok http` tự sử dụng domain đó. Bạn không được tự chọn tên trên gói miễn phí.

## 4. Các trường cần cấu hình trong project

### 4.1. File cấu hình và secret

Phần này dành cho cấu hình Test mode thủ công; nếu dùng tài khoản ngân hàng chung đã liên kết Live thì làm theo mục 1.2. Sửa **`application-local.properties` tại thư mục gốc**, không sửa file mẫu bằng dữ liệu thật. Spring Boot tự import file này và `.env`; không cần thêm profile `local`.

```properties
server.port=8080
spring.datasource.url=jdbc:mysql://localhost:3306/carrent?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Ho_Chi_Minh
spring.datasource.username=root
spring.datasource.password=${CARRENT_DB_PASSWORD:}
spring.jpa.hibernate.ddl-auto=none

carrent.payment.providers.sepay.owner-test.enabled=false
carrent.payment.providers.sepay.owner-test.webhook-secret=${CARRENT_SEPAY_OWNER_TEST_SECRET:}
carrent.payment.providers.sepay.admin-test.enabled=false
carrent.payment.providers.sepay.admin-test.webhook-secret=${CARRENT_SEPAY_ADMIN_TEST_SECRET:}

carrent.payment.maintenance.enabled=false
```

| Trường | Lấy ở đâu / điền gì |
| --- | --- |
| `spring.datasource.url` | Địa chỉ MySQL và database trên máy bạn; sửa port/schema nếu khác mặc định |
| `spring.datasource.username` | User MySQL local của bạn |
| `CARRENT_DB_PASSWORD` | Mật khẩu MySQL của user trên |
| `owner-test` / `admin-test` | Tên tham chiếu nội bộ, phải khớp `payment_channels.credential_reference`; không phải ID SePay |
| `CARRENT_SEPAY_OWNER_TEST_SECRET` | Secret Key HMAC của webhook dành cho kênh thu Owner, cùng môi trường Test |
| `CARRENT_SEPAY_ADMIN_TEST_SECRET` | Secret Key HMAC riêng của webhook dành cho kênh thu Admin |
| `enabled` của mỗi reference | Giữ `false` khi chuẩn bị; chỉ đổi `true` sau khi secret và mapping đúng |

Sau khi có Secret Key từ bước tạo webhook ở mục 6, quay lại điền các biến môi trường bằng lời nhắc ẩn **trong cửa sổ sẽ chạy Spring Boot**:

```powershell
$dbPassword = Read-Host 'Nhap mat khau MySQL local' -AsSecureString
$env:CARRENT_DB_PASSWORD = [System.Net.NetworkCredential]::new('', $dbPassword).Password
Remove-Variable dbPassword

$ownerSecret = Read-Host 'Nhap HMAC Secret Key webhook Owner Test' -AsSecureString
$env:CARRENT_SEPAY_OWNER_TEST_SECRET = [System.Net.NetworkCredential]::new('', $ownerSecret).Password
Remove-Variable ownerSecret

$adminSecret = Read-Host 'Nhap HMAC Secret Key webhook Admin Test' -AsSecureString
$env:CARRENT_SEPAY_ADMIN_TEST_SECRET = [System.Net.NetworkCredential]::new('', $adminSecret).Password
Remove-Variable adminSecret
```

Nếu chỉ kiểm tra một kênh, chỉ nhập secret và bật đúng kênh đó. Biến trên chỉ tồn tại trong phiên PowerShell và các tiến trình con; có thể lưu cùng các tên biến trong `.env` để Spring tự nạp khi khởi động. Biến môi trường của hệ điều hành/IDE có độ ưu tiên cao hơn giá trị cùng tên trong file.

**Không có trường ngrok URL bắt buộc trong cấu hình Java hiện tại.** Domain ngrok được dùng ở URL webhook trên Dashboard SePay. Authtoken ngrok nằm trong cấu hình ngrok, không đặt vào `application-local.properties`.

### 4.2. Mapping kênh thu trong MySQL

Chỉ cấu hình properties chưa đủ: endpoint tra `payment_channels` để tìm đúng chủ kênh và credential reference. Chạy SQL bằng MySQL Workbench hoặc client **trên database local của bạn**.

Kiểm tra user và các kênh đã có trước:

```sql
SELECT DATABASE();
SELECT id, full_name, role, status FROM users WHERE role IN ('OWNER', 'ADMIN');
SELECT id, user_id, owner_role, provider, purpose, credential_reference,
       bank_bin, account_number, enabled
FROM payment_channels;
```

Chọn đúng schema `carrent` trước khi tiếp tục. Không giả định ID giống máy thành viên khác.

| Cột | Giá trị cho kênh thu SePay |
| --- | --- |
| `user_id` | ID user CarRent nhận tiền, có role OWNER hoặc ADMIN phù hợp |
| `owner_role` | `OWNER` hoặc `ADMIN`, khớp role hiện tại của user |
| `provider` | `SEPAY` |
| `purpose` | `COLLECT` |
| `credential_reference` | `owner-test` hoặc `admin-test`, khớp properties |
| `bank_bin` | Mã BIN ngân hàng gồm **6 chữ số**, không dùng tên viết tắt ngân hàng |
| `account_number` | Đúng số tài khoản ngân hàng trong môi trường SePay đã chọn; giữ số 0 đầu |
| `enabled` | Ban đầu `0`; đổi `1` khi hoàn tất cấu hình kênh |

Nếu chưa có kênh, dùng mẫu sau để tạo **một kênh mới đang tắt**. Thay toàn bộ `<...>` bằng dữ liệu local; không chạy nguyên placeholder:

```sql
INSERT INTO payment_channels
    (user_id, owner_role, provider, purpose, credential_reference,
     bank_bin, account_number, enabled, created_at, updated_at)
SELECT u.id, 'OWNER', 'SEPAY', 'COLLECT', 'owner-test',
       '<BIN_6_CHU_SO>', '<SO_TAI_KHOAN_TEST>', b'0', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
FROM users u
WHERE u.id = <OWNER_USER_ID> AND u.role = 'OWNER' AND u.status = 'ACTIVE'
  AND NOT EXISTS (
      SELECT 1 FROM payment_channels c
      WHERE c.provider = 'SEPAY' AND c.credential_reference = 'owner-test'
  );

SELECT id, user_id, owner_role, credential_reference, enabled
FROM payment_channels
WHERE provider = 'SEPAY' AND credential_reference = 'owner-test';
```

Để tạo kênh Admin, đổi `OWNER` thành `ADMIN`, `owner-test` thành `admin-test`, dùng `<ADMIN_USER_ID>` và thông tin ngân hàng tương ứng. Nếu INSERT thêm 0 dòng, kiểm tra user/role/status hoặc reference đã tồn tại; không tự đổi tài khoản của kênh có lịch sử giao dịch.

Ghi lại `id` vừa SELECT: đây là **CarRent channelId** dùng trong URL. Ví dụ ID `12` thì đường dẫn là `/webhooks/sepay/12`. Đây không phải ID ngân hàng hoặc ID webhook trên SePay.

## 5. Chạy ứng dụng và mở tunnel

1. Trong cửa sổ PowerShell đã khai báo biến môi trường, chạy:

   ```powershell
   .\mvnw.cmd spring-boot:run
   ```

2. Mở `http://localhost:8080` và xác nhận ứng dụng chạy. Nếu chưa chạy local được, sửa lỗi Java/MySQL/schema trước.
3. Mở **cửa sổ PowerShell thứ hai**:

   ```powershell
   ngrok http 8080
   ```

4. Ghi lại HTTPS URL trong dòng **Forwarding**, ví dụ `https://your-assigned-name.ngrok-free.app`. Dùng URL thực tế của bạn, không dùng domain ví dụ.
5. Mở URL đó để kiểm tra trang chủ. Gói miễn phí có thể hiện trang cảnh báo trước khi truy cập bằng trình duyệt; bấm **Visit**. Theo ngrok, trang này không ảnh hưởng request API được gửi bằng chương trình.
6. Có thể xem request/response trong inspector local, mặc định `http://127.0.0.1:4040` nếu chưa đổi cấu hình. Payload/header tại đây có dữ liệu nhạy cảm; không gửi ảnh nguyên bản hoặc log nguyên văn cho nhóm.

Nếu đổi `server.port`, sửa cả lệnh ngrok sang cùng port. Đóng app/ngrok, máy ngủ hoặc mất mạng sẽ làm webhook không đến được.

## 6. Tạo và cấu hình webhook SePay

### 6.1. Chọn đúng môi trường

1. Đăng nhập [SePay](https://my.sepay.vn) bằng tài khoản của bạn; thành viên chưa có tài khoản thì tự đăng ký theo màn hình của SePay.
2. Chọn **Test mode** ở sidebar để kiểm thử trước. Tạo ngân hàng Test tại **Test mode → Tài khoản ngân hàng → Thêm mới**; dữ liệu tách biệt Live.
3. Lấy số tài khoản và BIN tương ứng để mapping kênh local ở mục 4.2. Không trộn ngân hàng Test với webhook Live.
4. Nếu dùng tài khoản chung Live theo mục 1, chọn đúng ngân hàng thật đã liên kết và tạo **một** webhook dùng URL setup đã in. Phần cấu hình Test thủ công ở mục 4 dùng reference/secret riêng, không trộn vào kênh Live.

### 6.2. Các trường trên form webhook

Vào **Test mode → Webhook → Thêm webhook**. Với Live, tài liệu hiện tại dùng **Dashboard → Tích hợp → Webhooks**; chưa chuyển Live chỉ để kiểm tra tunnel.

| Trường trên SePay | Giá trị cần chọn |
| --- | --- |
| Tên webhook | Tên dễ nhận biết, ví dụ `CarRent - tenban - Tai khoan chung` |
| URL webhook | `https://<DOMAIN_NGROK_CUA_BAN>/webhooks/sepay/<CARRENT_CHANNEL_ID>` |
| Loại sự kiện | **Tiền vào** |
| Tài khoản ngân hàng | Đúng một tài khoản đã mapping cho kênh đó |
| Định dạng dữ liệu | **JSON / application/json** |
| Phương thức xác thực / Bảo mật | **HMAC-SHA256** |
| Secret Key | Cùng giá trị với biến môi trường secret của reference tương ứng; giữ riêng trên máy và Dashboard |
| Mẫu mã thanh toán | Tiền tố `CR`, hậu tố 30 ký tự chữ/số; giữ trọn mã hệ thống sinh trong nội dung chuyển khoản |
| Bộ lọc mã thanh toán | Cho gửi cả giao dịch không nhận diện mã để vào đối soát, nếu form có tùy chọn này |
| Tài khoản định danh / VA / TKP | Không dùng; chọn tài khoản ngân hàng chính, `subAccount` rỗng/null |
| Trạng thái | Kích hoạt sau khi URL và HMAC khớp; chỉ một webhook cho ngân hàng chung |

CarRent hiện yêu cầu `X-SePay-Timestamp` và `X-SePay-Signature`, ký HMAC-SHA256 trên `{timestamp}.{raw_body}`, timestamp lệch tối đa ±300 giây. **API Key, OAuth hoặc Không xác thực không thay thế được HMAC của code hiện tại**, kể cả Test mode. Nếu Dashboard không có lựa chọn HMAC, ghi lại mô tả đã che thông tin để kiểm tra tương thích; không tắt kiểm tra chữ ký trong code.

Không cần API token truy vấn giao dịch để nhận webhook. Code hiện tại chưa có client đối soát SePay hoàn chỉnh; không thêm một property API token tự đặt tên rồi kỳ vọng được sử dụng. Cấu hình payOS hoàn tiền là luồng riêng.

**Với tài khoản chung Live:** làm mục 1.3. Các bước thủ công dưới đây chỉ dành cho reference Test ở mục 4, tránh bật thêm kênh COLLECT thứ hai cho cùng collector:

1. Đổi `carrent.payment.providers.sepay.owner-test.enabled=true` cho kênh Owner đã cấu hình; chỉ bật Admin tương tự nếu đã chuẩn bị đầy đủ.
2. Bật **đúng kênh mới của bạn** trong DB, sau khi thay ID và kiểm tra lại role/tài khoản:

   ```sql
   UPDATE payment_channels
   SET enabled = b'1', updated_at = UTC_TIMESTAMP(6)
   WHERE id = <CARRENT_OWNER_CHANNEL_ID>
     AND provider = 'SEPAY' AND purpose = 'COLLECT'
     AND owner_role = 'OWNER' AND credential_reference = 'owner-test';
   ```

3. Dừng/chạy lại Spring Boot từ cửa sổ có biến môi trường để nạp properties mới. ngrok có thể giữ nguyên đang chạy.

Nếu sau này kiểm thử Live, tạo reference/channel/webhook/secret riêng, ví dụ `owner-live`; không repoint kênh Test có lịch sử sang ngân hàng thật.

## 7. Kiểm tra kết quả và các việc còn thiếu

### 7.1. Chứng minh kết nối và nhận webhook

1. Trên SePay Test mode, mở **Giao dịch → Mô phỏng**.
2. Chọn đúng tài khoản Test, loại **Tiền vào**, số tiền nguyên VND dương và nội dung nhận biết đây là test kết nối. Bước này chưa cần ghép với một đơn thanh toán.
3. Xem **Webhook → Lịch sử gửi** trên Test mode và inspector ngrok. Request phải là POST đến đúng `/webhooks/sepay/<channelId>`.
4. Thành công ở tầng tiếp nhận: HTTP **200**, JSON `{"success":true}`. Code chỉ trả thành công sau khi ghi inbox bền vững.
5. Kiểm tra database local bằng truy vấn chỉ đọc:

   ```sql
   SELECT id, channel_id, provider_transaction_id, status, received_at
   FROM webhook_inbox
   WHERE channel_id = <CARRENT_CHANNEL_ID>
   ORDER BY id DESC LIMIT 10;
   ```

Một webhook thiếu chữ ký hoặc kênh bị tắt phải bị từ chối; không dùng POST không ký để kết luận tích hợp SePay bị hỏng. Mở URL webhook bằng trình duyệt là GET, không phải phép thử POST hợp lệ.

### 7.2. Vì sao inbox có dữ liệu nhưng đơn chưa thanh toán?

HTTP 200 chỉ chứng minh đã nhận vào inbox, không chứng minh đã ghi nhận thanh toán cho đơn.

- `carrent.payment.maintenance.enabled=false` mặc định: scheduler xử lý inbox chưa tự chạy. Dùng profile `sepay-local` ở mục 1.3 khi muốn chạy nghiệp vụ thu tiền; profile này chặn cả dispatch và reconcile chi, giữ payOS tắt.
- Muốn thanh toán một đơn phải có obligation/intent hợp lệ, đúng kênh, mã và số tiền. Webhook mô phỏng ngẫu nhiên không tạo ra đơn hay receipt hợp lệ.
- Intent mới sinh `CR` + 30 ký tự. Dashboard phải trích xuất đúng toàn bộ mã; không tự cắt mã hoặc tái sử dụng QR cũ hậu tố 32 ký tự.
- Parser hiện từ chối `subAccount` có giá trị khác rỗng. Nếu ngân hàng/VA được chọn luôn gửi trường này, cần xử lý tương thích trước; không xoá trường trên request đã ký.
- Pilot còn dùng `carrent.payment.pilot.owner-id` và `carrent.payment.pilot.admin-id` (mặc định `0`), phải là hai user ID khác nhau trong DB của bạn khi chạy pilot đầy đủ.
- Các cờ `carrent.rental.workflow.enabled`, `carrent.rental.advance.enabled`, `carrent.rental.balance.enabled`, `carrent.rental.deadlines.enabled`, `carrent.subscription.enabled` là cấu hình cho hành trình nghiệp vụ, không phải điều kiện mở tunnel. Không bật hàng loạt để vượt lỗi checkout; xem [hợp đồng provider](plans/payment-provider-contract.md) và runbook của task tương ứng.

### 7.3. Lỗi thường gặp

| Hiện tượng | Cách kiểm tra |
| --- | --- |
| `ngrok` không được nhận diện | Mở lại terminal sau cài đặt; kiểm tra PATH hoặc đường dẫn executable |
| Ngrok báo token/quota/domain lỗi | Kiểm tra đúng tài khoản, authtoken, domain và Usage trong Dashboard |
| Ngrok báo upstream không kết nối được / 502 | Spring Boot đã chạy chưa, port có trùng lệnh ngrok không |
| HTTP 401 từ webhook | channelId có tồn tại, DB/config đều bật, role đúng, reference/secret/HMAC khớp, đồng hồ máy chính xác |
| HTTP 400 từ webhook | JSON, tiền vào, accountNumber khớp, số tiền nguyên dương, ngày giờ đúng và `subAccount` rỗng/null |
| HTTP 503 từ webhook | Kiểm tra kết nối DB, schema và lỗi server; chưa được coi là ACK thành công |
| Redirect login / HTML thay vì JSON | Kiểm tra đúng URL POST và đường dẫn; không đặt thêm đăng nhập ngrok trước endpoint webhook |
| Không có request trong inspector | Kiểm tra URL, môi trường Test/Live, ngân hàng, bộ lọc webhook và trạng thái tunnel |
| Có 200 nhưng chưa có PAID | Đối chiếu mục 7.2; không sửa trạng thái PAID trực tiếp trong DB |

### 7.4. Nghiệm thu thu tiền thật

1. Đăng nhập Owner pilot → Gói dịch vụ → mua Small (hiện 5.000 VND). Mở checkout của order mới, xác nhận ngân hàng/tên người nhận/số tài khoản/amount/nội dung từ QR. Người dùng tự quét và chuyển đúng một lần.
2. SePay phải có giao dịch thật, lịch sử webhook HTTP 200 + `success=true`. Sau worker: inbox `PROCESSED`, receipt `APPLIED`, order/entitlement cập nhật đúng một lần; checkout hiển thị kết quả từ backend. Ghi order/intent/receipt/transaction ID đã che và thời điểm; không lưu raw payload/secret vào Git.
3. Thuê xe: dùng xe của Owner pilot, đặt thuê và hoàn tất xác nhận BEFORE để lấy QR 30%. Chỉ thanh toán QR đang còn hiệu lực. Kiểm tra booking CONFIRMED và số tiền trả trước. Sau hợp đồng/giao/nhận lại theo nghiệp vụ, mở QR 70%, thanh toán đúng phần còn nợ và kiểm tra COMPLETED.
4. Khi cần khôi phục webhook đã mất do local/ngrok dừng: khởi động lại, vào lịch sử gửi SePay và gửi lại **giao dịch cũ**, không chuyển tiền lần hai. SePay phải ký timestamp mới. Dedup giữ một inbox/receipt cho cùng transaction. Chưa có client query SePay tự động; đối chiếu Dashboard và DB thủ công.
5. Chuyển trễ/trùng hoặc thừa có thể tạo `REFUND_PENDING`; lệch 30%/không có mã có thể cần `REVIEW_REQUIRED` theo policy. Profile này không trả tiền thật. Không chủ động chuyển sai để thử khi luồng hoàn chưa đủ điều kiện.

Chỉ đóng phần thu thật khi cả giao dịch ngân hàng, webhook, receipt và kết quả nghiệp vụ khớp. P4.2-A/P8.8/P5.9 toàn bộ vẫn cần hoàn tiền thật, đối soát và review; một giao dịch thu thành công chưa đóng các gate đó.

## 8. Chạy lại hằng ngày và chia sẻ trong nhóm

1. Bật MySQL.
2. Khai báo lại biến môi trường trong terminal chạy app, hoặc cấu hình riêng trong IDE.
3. Khi đã kích hoạt thu tiền theo mục 1.3, chạy `.\mvnw.cmd -o spring-boot:run '-Dspring-boot.run.profiles=sepay-local'`. Chạy mặc định chỉ để kiểm tra website, không xử lý thanh toán tự động.
4. Terminal khác chạy `ngrok http 8080`; đối chiếu Forwarding với URL đã lưu trên SePay.
5. Nếu domain và channelId không đổi thì không cần sửa URL webhook. Khi khôi phục/tạo lại DB, kiểm tra lại ID kênh.
6. Khi xong, dừng ngrok bằng `Ctrl+C` rồi dừng ứng dụng. Nếu tạm tắt webhook, thao tác trên môi trường SePay của chính bạn.

Có thể chia sẻ hướng dẫn này và cấu hình mẫu không có dữ liệu thật. Không commit/chia sẻ authtoken, HMAC secret, mật khẩu, file cấu hình ngrok, cấu hình local, bản dump DB hoặc inspector chứa dữ liệu ngân hàng. Kiểm tra trước khi stage:

```powershell
git status --short
git check-ignore application-local.properties
```

Gói ngrok miễn phí có quota; URL cố định không bảo đảm hoạt động 24/7. Máy ngủ, app/ngrok dừng, mất mạng hoặc hết quota đều có thể làm webhook thất bại. Không replay giao dịch thật hoặc chuyển tiền thật chỉ để kiểm tra đường kết nối.

## 9. Checklist hoàn tất trên từng máy

- [ ] Java 21, MySQL và schema phù hợp; app chạy ở `localhost:8080`.
- [ ] Tài khoản ngrok riêng, authtoken lưu riêng, dev domain đã ghi nhận.
- [ ] Cấu hình MySQL/secret chỉ ở môi trường local; file local được Git ignore.
- [ ] DB có kênh COLLECT đúng user/role/ngân hàng/reference, biết CarRent channelId.
- [ ] Webhook Test dùng URL HTTPS riêng, JSON, Tiền vào và HMAC-SHA256 đúng secret.
- [ ] Mô phỏng Test đến đúng máy, nhận 200 + success và có inbox trong DB local.
- [ ] Mã mới `CR` + 30 ký tự được SePay trích xuất nguyên vẹn; runtime `sepay-local` xử lý inbox, payOS/chi tắt.
- [ ] Ghi riêng bằng chứng kết nối, thu thật và hoàn thật; chưa đủ bằng chứng thì chưa đánh dấu nghiệm thu tương ứng.

## 10. Nguồn đối chiếu

- [Cài ngrok trên Windows](https://ngrok.com/downloads/windows)
- [Dev domain cố định và giới hạn gói ngrok miễn phí](https://ngrok.com/docs/pricing-limits/free-plan-limits/)
- [SePay Test mode](https://developer.sepay.vn/vi/sepay-webhooks/test-mode/bat-dau-nhanh)
- [Tạo webhook SePay](https://developer.sepay.vn/vi/sepay-webhooks/tao-webhook)
- [Xác thực HMAC của SePay](https://developer.sepay.vn/vi/sepay-webhooks/xac-thuc)
- [Payload và ACK SePay](https://developer.sepay.vn/vi/sepay-webhooks/tich-hop-webhook)
- [Giới hạn mẫu mã thanh toán SePay](https://developer.sepay.vn/vi/sepay-webhooks/cau-hinh-ma-thanh-toan)
- Code: `ProviderPaymentProperties`, `SePayWebhookVerifier`, `SePayWebhookController`, `SePayWebhookService`, `SePayIncomingPayment`, `PaymentIntentTransactions`, `PaymentPilot`, `PaymentMaintenanceConfiguration`; schema `scriptDB.sql`; mẫu `application-local.properties.example`.

Tài liệu này được đối chiếu tĩnh với code và tài liệu công khai; chưa phải bằng chứng đăng nhập Dashboard, chạy tunnel hay nhận webhook SePay thật trên máy thành viên.
