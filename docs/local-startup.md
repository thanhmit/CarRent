# Chạy CarRent trên Windows bằng một click

Double-click **`start.bat`** ở thư mục gốc repo. Cần Windows 10/11 x64,
PowerShell 5.1, kết nối mạng và quyền cài phần mềm khi Windows hiện UAC.
Không cần cài Node, Git hoặc Maven riêng để chạy web Thymeleaf này.

## Launcher tự làm những gì?

1. Kiểm tra JDK có cả `java` và `javac`, phiên bản từ 21. Nếu thiếu, cài
   `EclipseAdoptium.Temurin.21.JDK` bằng winget.
2. Tìm MySQL trong PATH hoặc `C:\Program Files\MySQL`. Nếu thiếu, cài
   `Oracle.MySQL` cùng dependencies do winget quản lý.
3. Cài `Ngrok.Ngrok` nếu thiếu và chạy updater chính thức của ngrok sau
   lần cài để tránh agent quá cũ. Maven được `mvnw.cmd` tải ở lần build đầu.
4. Giữ nguyên `.env` và `application-local.properties` đã có. Nếu chưa có
   `.env`, sao chép `.env.example` để dev điền.
5. Với máy mới **không có config DB, server đang nghe hoặc service MySQL**,
   tạo MySQL riêng trong `.local/mysql/data`, sinh mật khẩu ngẫu nhiên và
   ghi vào `application-local.properties`. Mật khẩu được đặt trước khi
   server phục vụ TCP; server chỉ bind `127.0.0.1`. Không thay đổi datadir
   hay cấu hình một MySQL đã có trên máy.
6. Nếu đã có một service MySQL đang dừng, thử khởi động service đó. Nếu
   Windows từ chối quyền, báo dev mở `services.msc` để khởi động với quyền admin.
7. Kết nối DB, chỉ import `scriptDB.sql` khi schema `carrent` **không có bảng**.
   Kiểm tra tên các bảng/cột cần thiết trước khi chạy. DB có dữ liệu nhưng
   thiếu schema sẽ dừng và dẫn tới tài liệu migration, không tự sửa DB đó.
8. Kiểm tra IP Internet hiện tại bằng hai nguồn; gọi GET payOS để phân biệt
   lỗi IP, lỗi provider và lỗi mạng. Không in response chứa dữ liệu nguồn tiền.
9. Tái sử dụng tunnel ngrok trỏ đúng app, hoặc chạy ngrok khi có authtoken.
   Lấy HTTPS URL từ inspector local, không đoán domain.
10. Tái sử dụng app CarRent khỏe mạnh thuộc **đúng checkout này**, không
    kill/restart nó. Nếu chưa có app: build JAR, chạy nền và chờ `/cars` trả
    HTTP 200 có nhận diện CarRent, rồi mở trình duyệt.
11. Nếu chưa có kênh thu SePay và đã đủ dữ liệu, dùng profile `sepay-setup`
    sẵn có để tạo mapping **disabled**. Dev vẫn phải tạo webhook ở SePay
    và thực hiện quy trình kích hoạt thu sau khi đối chiếu cấu hình.

Mỗi lần chạy sẽ cập nhật **`.local/SETUP-NEEDED.md`**, in đường dẫn tuyệt đối
ra cửa sổ BAT và mở bằng Notepad nếu có mục cần xử lý. File này có tên lỗi,
IP hiện tại, URL webhook khi đã lấy được, tên các trường thiếu và đường dẫn
hướng dẫn. File không chứa giá trị secret. Sau khi sửa, chạy lại BAT để kiểm tra.

Không cần đăng nhập payOS/VietQR/ngrok qua trình duyệt cho mỗi lần chạy nếu
API credentials/token còn hợp lệ. Đăng nhập cần thiết khi dev lấy khóa,
sửa allowlist, tạo webhook hoặc xử lý enrollment/OTP. Launcher không tự
đăng nhập, đọc password manager, lấy OTP hoặc giả lập xác minh.

## File dev cần sửa

| File | Dùng cho | Có đưa lên Git không? |
| --- | --- | --- |
| `.env` | Ngrok token, ngân hàng thu, HMAC SePay, ID người dùng, khóa payOS/VietQR | Không |
| `application-local.properties` | Kết nối MySQL và các override provider đã có | Không |
| `.local/SETUP-NEEDED.md` | Báo cáo tự tạo; đọc rồi sửa file input, không điền secret vào báo cáo | Không |
| `.local/*.log`, `.local/mysql/` | Log/process/datadir riêng; có thể chứa thông tin nhạy cảm | Không |
| `.env.example` | Tên các trường dùng chung; luôn để trống secret | Có |

Input theo cú pháp **Java properties**: `KEY=value`, không có `export`,
không bọc giá trị trong dấu nháy. Dấu `\` trong một giá trị cần viết `\\`;
không dùng file này như script shell. Xem comment trong `.env.example`.
Environment của Windows có thể ghi đè giá trị file. `.env` được import sau
`application-local.properties`; các override trực tiếp cùng tên cần được
kiểm tra để tránh điền một nơi nhưng app đọc nơi khác.

<a id="environment"></a>
## Môi trường và lần cài đầu

Launcher dùng winget source chính thức; winget kiểm tra hash installer và cài
dependencies. Windows có thể yêu cầu UAC hoặc khởi động lại máy. Sau reboot,
double-click BAT lần nữa; phần đã cài không được cài lại khi đã tìm thấy binary.

Nếu không có winget, launcher thử tải Microsoft App Installer từ
<https://aka.ms/getwinget> và cài bằng `Add-AppxPackage` với kiểm tra chữ ký
của Windows. Trường hợp Windows thiếu dependencies App Installer, bị chặn Store,
Appx hoặc chính sách công ty, dev phải hoàn tất cài winget theo
<https://learn.microsoft.com/windows/package-manager/winget/#install-winget>.
Không tắt kiểm tra chữ ký hoặc Windows security.

Các lệnh cài có thể chạy lại thủ công:

```powershell
winget install --id EclipseAdoptium.Temurin.21.JDK --exact --source winget
winget install --id Oracle.MySQL --exact --source winget
winget install --id Ngrok.Ngrok --exact --source winget
```

Nếu PATH chưa được cập nhật, mở lại terminal/BAT. Chi tiết installer nằm
trong `.local/install-<package>.log`. Maven cần truy cập Maven Central ở
lần build đầu; lỗi proxy/chứng chỉ cần sửa cấu hình mạng/Maven của máy.

<a id="database"></a>
## MySQL và dữ liệu

Máy đã có MySQL: sửa `application-local.properties` với host/port/user/password
đúng. Không copy mật khẩu demo hoặc tự đổi mật khẩu root của một server đã có.

```properties
spring.datasource.url=jdbc:mysql://localhost:3306/carrent?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Ho_Chi_Minh
spring.datasource.username=root
spring.datasource.password=YOUR_OWN_LOCAL_PASSWORD
```

Launcher hỗ trợ MySQL 8 local, không tự provision DB remote. Khi nhiều service
MySQL tồn tại, tự chọn/start đúng service trong `services.msc`. Không chạy
song song MySQL riêng của launcher và service khác trên cùng cổng.

`DB_CONNECT`: kiểm tra service, URL/user/password và quyền đăng nhập TCP.
`DB_MIGRATION`: backup và đọc [hướng dẫn migration](migrations/README.md).
Launcher chỉ kiểm tra tên bảng/cột, không thay thế kiểm tra kiểu dữ liệu,
index, constraint hoặc diễn tập migration.

Nếu khởi tạo MySQL riêng bị ngắt giữa chừng, giữ nguyên `.local/mysql` để
kiểm tra `server.err.log`/`server.out.log`; không xóa hoặc reset dữ liệu
để thử lại. File `.local/mysql/set-root-password.sql` là private; launcher
chỉ xóa nó sau khi kết nối bằng mật khẩu mới thành công.

DB mới nhận dữ liệu demo từ `scriptDB.sql`. Dùng local để phát triển; không
dùng tài khoản demo hoặc bộ bootstrap này làm môi trường production.

<a id="ngrok"></a>
## Ngrok: token và domain lấy ở đâu?

1. Đăng nhập tài khoản của dev tại <https://dashboard.ngrok.com>.
2. Mở **Your Authtoken**:
   <https://dashboard.ngrok.com/get-started/your-authtoken>.
3. Điền token trực tiếp vào `.env`:

   ```properties
   CARRENT_NGROK_AUTHTOKEN=
   CARRENT_PUBLIC_BASE_URL=
   ```

4. Có thể để token trống nếu ngrok đã được cấu hình ở Windows account này.
   Launcher dùng `NGROK_AUTHTOKEN` riêng trong environment tiến trình con,
   không truyền token qua command line hoặc ghi token vào báo cáo.
5. Nếu có domain được cấp, điền `CARRENT_PUBLIC_BASE_URL=https://DOMAIN_CUA_BAN`.
   Không thêm `/webhooks/...`, dấu nháy, query hoặc user/password vào URL.
6. Chạy BAT. Xem `Public HTTPS origin` trong `.local/SETUP-NEEDED.md`.
   Nếu domain thực tế khác config, sửa `.env` và URL webhook trên SePay.

Không dùng domain của thành viên khác. Ngrok có thể từ chối token hết hạn,
domain sai, nhiều session hoặc hết quota. Xem `.local/ngrok.err.log` và
`.local/ngrok.out.log`. Nếu inspector 4040 đã thuộc một tunnel khác, launcher
không chiếm/kill nó; dev tự sửa upstream hoặc dừng đúng tunnel của mình.

Ngrok cần tiếp tục chạy để SePay gửi webhook vào máy. Máy ngủ/mất mạng hoặc
đóng tiến trình ngrok sẽ làm endpoint ngừng hoạt động. IP public đi ra cho
payOS là thông tin khác với domain HTTPS ngrok đi vào.

<a id="sepay"></a>
## SePay: ngân hàng, ID, HMAC và tạo webhook

Điền trực tiếp `.env`:

```properties
CARRENT_SEPAY_BANK_BIN=
CARRENT_SEPAY_GATEWAY=TPBank
CARRENT_SEPAY_ACCOUNT_NUMBER=
CARRENT_SEPAY_WEBHOOK_SECRET=
CARRENT_SEPAY_OWNER_ID=
CARRENT_SEPAY_ADMIN_ID=
CARRENT_PUBLIC_BASE_URL=
```

| Trường | Lấy ở đâu / yêu cầu |
| --- | --- |
| `BANK_BIN` | BIN 6 số của ngân hàng đang nhận tiền; TPBank là `970423` |
| `GATEWAY` | Tên gateway tương ứng ngân hàng trong payload SePay |
| `ACCOUNT_NUMBER` | Tài khoản nhận đã kết nối trong SePay; giữ số 0 đầu |
| `WEBHOOK_SECRET` | Secret HMAC ở bước bảo mật webhook; không phải API token truy vấn |
| `OWNER_ID`, `ADMIN_ID` | `users.id` trong **DB CarRent của dev**, ACTIVE và đúng OWNER/ADMIN, khác nhau |
| `PUBLIC_BASE_URL` | HTTPS origin thật lấy từ tunnel ngrok của dev |

Khi đủ dữ liệu và chưa có kênh thu, launcher chạy `sepay-setup` sẵn có để tạo
mapping disabled. Nếu mapping chỉ có một phần hoặc khác dữ liệu, không tự
repoint: dùng quy trình đối chiếu ở [hướng dẫn SePay](local-ngrok-sepay-setup.md).
Để chủ động tạo/đối chiếu mapping với workers tắt:

```powershell
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=sepay-setup' '-Dspring-boot.run.arguments=--carrent.payment.maintenance.enabled=false --carrent.rental.deadlines.enabled=false --carrent.payment.payout.enabled=false'
```

Chạy lại `start.bat -CheckOnly -NoOpen` sau khi có tunnel/mapping. Báo cáo sẽ
hiện URL thực, ví dụ `https://DOMAIN/webhooks/sepay/CHANNEL_ID`; không lấy số
channel từ tài liệu/máy người khác. Dùng URL Owner cho **một webhook chung**
khi Owner/Admin dùng cùng tài khoản ngân hàng và cùng secret.

1. Đăng nhập <https://my.sepay.vn>, chọn đúng môi trường Test/Live.
2. Kết nối ngân hàng nhận tiền của dev, rồi mở **Webhook → Thêm webhook**.
3. Chọn tài khoản nhận tương ứng; URL là URL Owner thực trong báo cáo;
   phương thức `POST`, JSON; sự kiện tiền vào phù hợp cấu hình SePay.
4. Bảo mật phải tương thích HMAC-SHA256. CarRent kiểm tra
   `X-SePay-Timestamp`, `X-SePay-Signature`, ký `{timestamp}.{raw_body}`,
   freshness ±300 giây. Secret phải trùng `.env`.
5. Nếu dashboard chỉ cho API Key/OAuth và không có HMAC tương thích,
   ghi nhận provider mismatch và kiểm tra lại với SePay; không tắt verifier.
6. Lưu webhook; xem **Lịch sử gửi** để kiểm tra HTTP status và kết quả xử lý.
   Không chuyển tiền thật chỉ để kiểm tra kết nối.

Khi ngân hàng/IDs/secret/mapping/webhook đã được đối chiếu, dùng quy trình
`sepay-activate` trong [hướng dẫn đầy đủ](local-ngrok-sepay-setup.md), sau đó
đặt các flag thu trong config local theo hướng dẫn đó. Launcher giữ các
flag thu đã có, không coi việc vừa điền secret là yêu cầu bật thu live.

<a id="payos"></a>
## PayOS: kênh chi, credentials và IP sai

Đăng nhập <https://my.payos.vn>. Mở **Kênh chuyển tiền / Kênh chi** đúng
nguồn đã liên kết Bảo Kim, lấy **Client ID, API Key, Checksum Key**.
Đây phải là bộ khóa kênh chi; khóa tạo link thanh toán không thay thế được.

Điền `.env` cho từng nguồn Owner/Admin, hoặc sửa override tương ứng đã có
trong `application-local.properties`:

```properties
CARRENT_PAYOS_OWNER_CLIENT_ID=
CARRENT_PAYOS_OWNER_API_KEY=
CARRENT_PAYOS_OWNER_CHECKSUM_KEY=
CARRENT_PAYOS_ADMIN_CLIENT_ID=
CARRENT_PAYOS_ADMIN_API_KEY=
CARRENT_PAYOS_ADMIN_CHECKSUM_KEY=
```

Lỗi **IP_DENIED / 403**:

1. Xem **Egress IPv4** vừa kiểm tra trong `.local/SETUP-NEEDED.md`.
2. Trong chi tiết kênh chi payOS, mở **Quản lý IP → Thêm IP**, thêm IP đó.
   Thực hiện trên các nguồn thực tế đang dùng; không xóa IP server khác.
3. Tự hoàn thành đăng nhập/OTP nếu provider yêu cầu. Không gửi OTP/keys vào chat.
4. Chạy lại BAT để GET kiểm tra. IP mạng/VPN có thể đổi ở lần chạy sau;
   launcher kiểm tra lại mỗi lần, không hardcode IP của máy tác giả.

Không điền domain ngrok vào allowlist IP đi ra. Nếu hai nguồn tra IP không
đồng nhất hoặc qua NAT/proxy khác nhau, xác nhận đúng egress phía backend
trước khi thay đổi allowlist.

`ACCESSIBLE_UNVERIFIED` chỉ là GET đã có response code `00`, chưa xác minh
chữ ký. Diagnostic sẵn có kiểm tra chữ ký list/balance, không chi tiền:

```powershell
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=payos-check' '-Dspring-boot.run.arguments=--carrent.payment.maintenance.enabled=false --carrent.rental.deadlines.enabled=false --carrent.payment.payout.enabled=false'
```

GET thành công chưa chứng minh quyền tạo lệnh, hạn mức, phí, tiền nguồn hoặc
đích nhận. Xem [quy trình payOS](local-payos-setup.md) trước khi kích hoạt.

<a id="vietqr"></a>
## VietQR: khóa tra cứu người nhận và quyền dịch vụ

1. Đăng nhập <https://my.vietqr.io> bằng tài khoản của dev.
2. Lấy Client ID/API Key cho dịch vụ tra cứu tên chủ tài khoản; xác nhận
   tài khoản có entitlement cho `POST https://api.vietqr.io/v2/lookup`.
3. Điền `.env`, giữ disabled khi chưa được xác nhận quyền dịch vụ:

   ```properties
   CARRENT_REFUND_LOOKUP_ENABLED=false
   CARRENT_REFUND_LOOKUP_CLIENT_ID=
   CARRENT_REFUND_LOOKUP_API_KEY=
   ```

Khóa này độc lập với payOS, SePay và token ngrok. Không tự tái sử dụng khóa
payout. Nếu VietQR đã chuyển sang CAS và không cấp `/v2/lookup`, cần provider
cung cấp contract thay thế; launcher không suy đoán endpoint hoặc tạo lệnh
chi để tìm tên người nhận.

Sau khi entitlement được xác nhận, dùng các trường
`CARRENT_REFUND_LOOKUP_CHECK_*` trong `.env.example` với một đích do dev
kiểm soát, và diagnostic `refund-lookup-check` theo
[hướng dẫn operator](p42a-p88-operator-guide-2026-11-09.md). Đây là bước
gửi thông tin tài khoản nhận thật tới dịch vụ lookup; launcher không tự làm
bước đó mỗi lần mở dự án.

<a id="startup"></a>
## Kiểm tra, lỗi và tiến trình đang chạy

```powershell
# Chỉ kiểm tra: không cài, build, start service/tunnel/app hoặc ghi DB.
# Vẫn ghi báo cáo private trong .local và gọi GET kiểm tra provider/IP.
.\start.bat -CheckOnly -NoOpen

# Chạy bình thường nhưng không mở browser/Notepad tự động.
.\start.bat -NoOpen

# Kiểm thử launcher, không cần cài Pester.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/test-start-local.ps1

# Kiểm thử thêm MySQL mới trong datadir/cổng tạm; không dùng DB hiện tại.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/test-start-local.ps1 -MySqlSmoke

# Nếu đã có JAR: kiểm thử thêm app mới ở DB/cổng tạm, không đụng app thật.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/test-start-local.ps1 -MySqlSmoke -AppSmokeJar .\target\carrent-0.0.1-SNAPSHOT.jar
```

`PORT_BUSY`: launcher không kill tiến trình lạ. Đổi `server.port` hoặc tự
dừng đúng tiến trình chiếm cổng. `BUILD`: xem `.local/build.log`. `APP_START`:
xem `.local/app.out.log` và `.local/app.err.log`. Không đăng công khai log
nguyên bản vì Spring/provider/DB có thể ghi dữ liệu riêng trong lỗi.

Đóng cửa sổ BAT không dừng app/MySQL/ngrok đang chạy nền. `.local/*.pid` chỉ
là thông tin theo dõi; PID có thể được Windows tái sử dụng, nên không dùng
lệnh kill mù theo file PID. Kiểm tra Task Manager → Details → Command line,
đối chiếu đường dẫn repo/datadir rồi mới dừng tiến trình của mình. App đã
chạy không được restart tự động khi config/source thay đổi: dừng đúng app,
sửa file và chạy BAT để build/nạp lại.

Launcher mới luôn ép `ddl-auto=none`, SQL init `never`, payout worker OFF
và rental deadline job OFF. Subscription UI được bật. Nó không tự chi tiền,
không tạo refund, không bật Payment Settings S1/S2/S3 và không sửa trạng thái
`UNKNOWN`. Việc bật live payout là quy trình riêng sau xác minh nguồn/đích,
provider và đối soát; xem tài liệu payOS. Nếu tái sử dụng một app đã chạy,
launcher giữ nguyên cấu hình runtime của app đó.
