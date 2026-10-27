# CarRent — Phase 7: Queue theo carId — Implementation Plan

> **For agentic workers:** Dùng `superpowers:executing-plans` theo từng task; checkbox ghi tiến độ thật. Chỉ dùng subagent khi được yêu cầu. Không stage/commit/push tự động.

**Goal:** Tuần tự các mutation cùng xe trong một tiến trình, request đợi kết quả và vẫn giữ khóa DB.

**Architecture:** Một executor FIFO theo khóa carId, dùng chung pool giới hạn. Entry point đưa tác vụ vào queue trước khi gọi transactional service qua Spring proxy; kết quả trả sau commit/rollback. Queue không làm kho lưu việc của webhook hoặc job tiền.

**Tech Stack:** Java 21 concurrency, Spring MVC/JPA hiện có; không Redis, RabbitMQ hoặc dependency queue mới.

**Spec:** [Hợp đồng chung](README.md) §1, §5, §6.3; kế thừa khóa DB đã triển khai D-13.

**Phân công:** `thanhmit` phụ trách toàn bộ task mới; `huy69332` review. Xem [phân công nhóm](team-assignment.md).

**Checkpoint 2026-10-27:** P7.1–P7.4 đã triển khai và kiểm chứng, **Phase 7 hoàn tất**. Singleton executor nối 14 mutation HTTP; gate transaction/DB hai kết nối đạt và contract worker tài chính đã ghi bên dưới. Build sạch đạt **103 test**; task tiếp theo: **P4.2**. Chưa triển khai thanh toán thật; chưa ghi nhận review của contributor khác.

## Global Constraints

- Thực hiện **trước Phase 4**; không đổi policy thanh toán/bàn giao trong phase này.
- 4 worker; 50 tác vụ chờ/car, 500 chờ toàn app; chờ trước bắt đầu 10 giây. Cấu hình tại server, không nhận từ request.
- Cùng xe FIFO; xe khác có thể song song; đọc danh sách/chi tiết/polling không enqueue.
- Không enqueue từ transaction đang giữ khóa; không lồng queue. Worker không lấy secret hoặc quyền từ dữ liệu client.
- Queue mất khi dừng app; DB locking, rollback, timeout và kiểm tra trạng thái vẫn là lớp bảo vệ cuối.
- Toàn bộ quy tắc bảo toàn seed, kiểm chứng và không auto-commit ở [README §8](README.md#8-verification-và-bàn-giao) áp dụng.

## Map file

- Create `src/main/java/com/carrent/concurrency/CarCommandExecutor.java`: admission, lane FIFO, worker, cleanup.
- Create `src/main/java/com/carrent/concurrency/CarQueueProperties.java`: giới hạn cấu hình.
- Create `src/main/java/com/carrent/concurrency/CarQueueRejectedException.java`: hàng đợi đầy/hết thời gian chờ trước chạy.
- Modify `config/` để đăng ký properties/bean, `application.properties`, `common/exception/CommonExceptionHandler.java`.
- Modify entry point hiện có: `booking/BookingController.java`, `booking/OwnerBookingController.java`, `car/OwnerCarController.java`, `car/AdminCarController.java`.
- Test `src/test/java/com/carrent/concurrency/CarCommandExecutorTest.java`, `CarCommandTransactionTest.java`.
- Test tích hợp HTTP `src/test/java/com/carrent/concurrency/CarQueueHttpIntegrationTest.java`; điều chỉnh fixture HTTP trong `car/CarDateSearchIntegrationTest.java` để worker đọc được dữ liệu đã commit.

### Task P7.1 — Admission, FIFO và chạy song song giữa xe

**Phụ trách:** `@thanhmit` · **Review:** `@huy69332`.

**Interfaces:** `public <T> T execute(Long carId, Supplier<T> action)`; carId phải dương, action khác null. Exception nghiệp vụ giữ nguyên nguyên nhân cho MVC. Tác vụ có state QUEUED/RUNNING/DONE/CANCELLED_BEFORE_START.

- [x] Viết test FIFO có latch, không dùng sleep để suy đoán thứ tự:

```java
var entered = new CountDownLatch(1);
var release = new CountDownLatch(1);
var first = clients.submit(() -> queue.execute(1L, () -> {
    entered.countDown();
    await(release);
    return "first";
}));
assertTrue(entered.await(2, TimeUnit.SECONDS));
var other = clients.submit(() -> queue.execute(2L, () -> "other"));
assertEquals("other", other.get(2, TimeUnit.SECONDS));
release.countDown();
assertEquals("first", first.get(2, TimeUnit.SECONDS));
```

Test helper `await(CountDownLatch)` chờ tối đa 2 giây rồi fail; tạo executor/client trong fixture và đóng sau test. Bổ sung tác vụ thứ hai carId=1 để assert chỉ bắt đầu sau first hoàn tất.

- [x] Run `.\mvnw.cmd '-Dtest=CarCommandExecutorTest' test`; xác nhận RED do chưa có executor.
- [x] Implement lane map bằng `ConcurrentHashMap.compute`; enqueue và xóa lane rỗng cùng thao tác atomic theo key. Một lane chỉ có một drainer. Pool chung 4 worker; không tạo thread hoặc executor riêng cho từng xe.
- [x] Giới hạn cả từng lane và toàn bộ tác vụ chờ; trả quota khi dequeue/cancel/reject đúng một lần. Từ chối admission không được tạo tác vụ chạy ngầm.
- [x] Chạy lại test: FIFO, xe khác song song, exception tác vụ đầu không chặn tác vụ sau, 50/500 bị chặn đúng biên.

### Task P7.2 — Timeout trước chạy, shutdown và cleanup an toàn

**Phụ trách:** `@thanhmit` · **Review:** `@huy69332`.

**Files:** CarCommandExecutor, CarQueueRejectedException, properties; test cùng file P7.1.

- [x] Viết test khóa worker đầu, cho tác vụ sau hết 10 giây bằng clock/deadline fixture; assert tác vụ bị từ chối **không bao giờ thực thi**, kể cả sau khi worker được mở.
- [x] Chuyển QUEUED → RUNNING và QUEUED → CANCELLED_BEFORE_START dưới cùng lane synchronization. Nếu đã RUNNING, không dùng `Future.cancel(true)` hoặc trả lỗi chắc chắn khi transaction có thể commit; đợi kết quả action. Executor không mở transaction; caller phải gọi service proxy có timeout hữu hạn (đặt/kiểm chứng cấu hình DB khi tích hợp P7.3–P7.4).
- [x] Fail fast khi đang có transaction hoặc re-entry từ worker cùng executor. Không gọi queue từ bên trong BookingService/CarService transactional methods.
- [x] Shutdown: dừng admission, chờ tác vụ đang chạy trong thời gian đóng app, fail các tác vụ chưa chạy; không hứa khôi phục request RAM sau crash. Hết thời gian chờ shutdown chỉ log cảnh báo; không ngắt hoặc giả báo thất bại tác vụ RUNNING.
- [x] Test đua enqueue với lane cleanup bằng barrier nhiều vòng, bảo đảm không tạo hai drainer cùng carId; pool rejection không để future treo hoặc rò quota. Đóng executor cuối test.

### Bằng chứng P7.1–P7.2 — 2026-10-27

- Tạo `CarCommandExecutor`, `CarQueueProperties`, `CarQueueRejectedException` và `CarCommandExecutorTest`; không thêm dependency, đổi schema/seed hoặc thay đổi controller/service hiện có.
- RED lần đầu: lệnh trong P7.1 thất bại vì chưa có executor/properties. RED tiếp theo xác nhận thiếu admission limits/validation; thiếu timeout constructor; thiếu transaction/re-entry guard và shutdown; cấu hình record chưa bind; `CompletionException` bị unwrap mất identity. Sau từng nhóm đã chạy GREEN.
- `CarCommandExecutorTest`: **19 test đạt**, gồm FIFO, xe khác song song, 50/500 đúng biên, exception/error gốc, timeout trước chạy, deadline 10 giây bằng monotonic ticker fixture, interruption trước/sau khi chạy, shutdown/grace/interruption, pool rejection và trả quota, validation/binding cấu hình, 200 vòng barrier enqueue-vs-cleanup (400 action, tối đa 1 action cùng xe).
- Build cuối: `.\mvnw.cmd -o '-Dmaven.compiler.useIncrementalCompilation=false' clean verify` → **BUILD SUCCESS**, **53 test, 0 failure/error/skip** (19 queue + 34 Spring/MySQL/MVC hiện có). Raw log local: `.scratch/phase7/core-clean-verify.log`. Hai WARN shutdown là nhánh lỗi được chủ động kiểm thử; cảnh báo Mockito/Java agent là từ bộ test hiện có.
- Mặc định properties: 4 worker, 50 chờ/xe, 500 chờ toàn app, chờ trước chạy 10 giây; thêm `shutdownTimeout=30s` làm thời gian chờ đóng hữu hạn. Đồng hồ production dùng `System.nanoTime`, không phụ thuộc giờ hệ thống. Pool có queue lịch chạy hữu hạn và không tạo thread riêng cho mỗi xe.
- Chưa đăng ký bean, bind `application.properties`, map HTTP 503, chuyển principal/context hoặc nối POST. Các bước đó thuộc **P7.3**; transaction/DB thật hai kết nối và boundary commit/rollback thuộc **P7.4**. Chưa benchmark, kiểm thử browser hoặc tích hợp thanh toán. Queue chỉ RAM, không thay DB locking hoặc lưu việc tài chính bền vững.

### Task P7.3 — Nối vào mutation HTTP hiện có

**Phụ trách:** `@thanhmit` · **Review:** `@huy69332`.

**Files:** bốn controller đã liệt kê, config/CarQueueConfiguration, CommonExceptionHandler, application.properties. Giữ implementation transactional của BookingService/CarService qua proxy.

- [x] Đăng ký `CarQueueProperties` bằng `@EnableConfigurationProperties`, tạo singleton executor bean có destroy method `close`, khai báo giới hạn tại server. Đặt timeout transaction hữu hạn cho các service action (không dùng thời gian chờ queue để giả làm timeout transaction); xác minh commit/rollback qua proxy tại P7.4.

- [x] Lập bảng toàn bộ POST thay đổi xe/booking/image hiện tại; với route có bookingId đọc carId để route, sau đó service kiểm tra lại ownership/current state khi thực thi. Create car chưa có carId không vào queue; quota Owner sẽ khóa DB tại Phase 8.
- [x] Bao entry point theo mẫu:

```java
commands.execute(carId, () -> {
    bookingService.confirmBooking(bookingId, principal);
    return null;
});
```

Không di chuyển `@Transactional` lên controller/executor và không gọi transactional method bằng self-invocation. Bấm xác nhận chỉ nhận success sau khi service proxy đã commit.

- [x] Queue đầy/hết chờ khi chưa chạy: HTTP 503 với thông báo tiếng Việt thử lại, không lộ stack/SQL; rollback hoặc admission fail không gửi notification thành công. Giữ handler 409 cho lỗi khóa DB hiện có.
- [x] Test HTTP cùng xe confirm/cancel cạnh tranh; mỗi tác vụ kiểm tra trạng thái đã commit của tác vụ trước và chỉ áp dụng transition hợp lệ theo policy hiện hành. Thêm test SQL failure/notification failure cho thấy caller nhận lỗi sau rollback và task sau tiếp tục chạy.

### Map mutation HTTP P7.3

Tất cả route dưới đây dùng POST. Preflight kiểm tra ID/quyền để định tuyến; worker gọi service proxy với ID/form/principal tường minh, đọc lại quyền và trạng thái trong transaction riêng. Không chuyển EntityManager, managed entity, Model, session hoặc SecurityContext sang worker.

| Route | Khóa queue | Service |
|---|---|---|
| `/bookings` | `form.carId` | `createBooking` |
| `/bookings/{id}/cancel` | carId từ booking | `cancelBooking` |
| `/owner/bookings/{id}/confirm` | carId từ booking | `confirmBooking` |
| `/owner/bookings/{id}/complete` | carId từ booking | `completeBooking` |
| `/owner/bookings/{id}/reject` | carId từ booking | `rejectBooking` |
| `/owner/cars/{id}/edit` | id xe | `updateCar`, gồm upload ảnh |
| `/owner/cars/{id}/images/{imageId}/delete` | id xe | `deleteImage` |
| `/owner/cars/{id}/deactivate` | id xe | `deactivateCar` |
| `/owner/cars/{id}/activate` | id xe | `activateCar` |
| `/owner/cars/{id}/delete` | id xe | `deleteCar` |
| `/admin/cars/{id}/approve` | id xe | `approveCar` |
| `/admin/cars/{id}/reject` | id xe | `rejectCar` |
| `/admin/cars/{id}/deactivate` | id xe | `deactivateCarForAdmin` |
| `/admin/cars/{id}/activate` | id xe | `activateCarForAdmin` |
| `/owner/cars/new` | bypass, chưa có carId | `createCar` |

GET danh sách/chi tiết và các thao tác ngoài aggregate xe/booking không enqueue. Validation có thể trả lỗi trước admission. Không thay đổi `@Transactional` hoặc khóa/current read trong service.

### Bằng chứng P7.3 — 2026-10-27

- `CarQueueConfiguration` đăng ký properties và singleton executor có `destroyMethod=close`. Cấu hình production: 4 worker, 50 chờ/xe, 500 chờ toàn app, chờ trước chạy 10 giây, shutdown grace 30 giây; `spring.transaction.default-timeout=30s`, test xác nhận JpaTransactionManager nhận giá trị 30 giây.
- RED: thiếu executor bean; sau khi đăng ký bean, 14 ca queue đầy vẫn trả 302/200 thay vì 503. Sau khi nối controller và handler, GREEN. Fixture test cũ phải commit trước POST qua queue vì worker dùng connection/transaction khác; giữ kiểm tra giá phía server và trùng lịch. Helper đồng bộ test được sửa để phân biệt chờ DB với chờ trong executor, không dùng sleep đoán thứ tự.
- **41 test Spring/MySQL/MVC HTTP đạt:** 14 route thành công và 14 route queue đầy; timeout không tạo đơn muộn; reads/create-car bypass; CSRF/role/ownership/ID sai; hai thứ tự confirm/cancel; lỗi notification rollback cả booking/payment/thông báo; SQL CHECK failure thật và lỗi khóa được inject trả 500/409, tác vụ sau vẫn commit; ba role bị BANNED khi chờ và đổi ownership đều bị worker từ chối. Xóa ảnh/xóa xe kiểm tra file đã được dọn sau commit.
- **Làm rõ policy legacy:** cancel trước → confirm bị từ chối (1 transition/notification); confirm trước → Customer vẫn được cancel CONFIRMED (2 transition/notification hợp lệ, cuối cùng CANCELLED). Không đổi policy chỉ để ép cả hai thứ tự có đúng một transition. Chặn hủy sau bàn giao thuộc Phase 6.
- SQL failure dùng UPDATE vi phạm CHECK trên booking fixture sau khi flush thay đổi và notification thật; không sửa constraint/schema. Lỗi notification chỉ được kích hoạt trong test, không thêm hook production. Ca 409 ở đợt này là lỗi inject, không được coi là bằng chứng 1205/1213 thật mới; gate hai kết nối còn tại P7.4.
- Build cuối `.\mvnw.cmd -o '-Dmaven.compiler.useIncrementalCompilation=false' clean verify` → **BUILD SUCCESS; 94 test, 0 failure/error/skip** (19 lõi queue + 34 tìm xe theo ngày + 41 HTTP queue). Raw log local: `.scratch/phase7/http-clean-verify.log`. Stack SQL CHECK và hai WARN shutdown trong log là nhánh lỗi có chủ đích; cảnh báo Mockito/Java agent có từ bộ test cũ.
- Snapshot trước/sau lần build đầy đủ xác nhận fingerprints toàn bộ rows/timestamps, columns/constraints của 7 bảng và ảnh seed giữ nguyên; không còn xe fixture queue/date, user queue hoặc file ảnh queue. Script local `.scratch/phase7/check-http-db.py`; baseline `.scratch/phase7/http-db-before.json`. Không reset/seed/DDL DB, không thêm dependency. AUTO_INCREMENT có thể tăng do fixture.
- `git diff --check` đạt; giữ nguyên thay đổi có sẵn ở `phase-0-init.md`. Tại thời điểm kiểm chứng: branch `feat/car-queue-http`, chưa stage/commit/push; chưa ghi nhận review của contributor khác. Chưa benchmark/browser hoặc tích hợp tiền thật. Queue vẫn chỉ RAM trong một tiến trình; DB locking/rollback được giữ nguyên. **P7.4 chưa hoàn thành.**

### Task P7.4 — Gate tích hợp và điểm mở rộng cho worker tài chính

**Phụ trách:** `@thanhmit` · **Review:** `@huy69332`.

**Test:** CarCommandTransactionTest; hồi quy `car/CarDateSearchIntegrationTest.java` hiện có.

- [x] Hai transaction/kết nối thật cùng xe vẫn không tạo trùng kể cả gọi trực tiếp service bỏ qua queue; chứng minh không thay khóa DB bằng queue.
- [x] Assert transaction chưa mở khi task đang chờ; task trả về sau commit/rollback. Chạy query current read để loại stale managed state như protocol hiện có.
- [x] Run `.\mvnw.cmd '-Dmaven.compiler.useIncrementalCompilation=false' clean verify`; đối chiếu baseline seed và status cuối fixture.
- [x] Ghi contract cho Phase 4/6: worker đọc ID việc từ DB rồi gọi `execute(carId, ...)`; nếu admission fail thì để việc trong DB retry, không đánh dấu processed trước commit.
- [x] Cập nhật checkpoint chỉ sau khi các test trên chạy thật; không cần benchmark hoặc triển khai queue phân tán.

**Hoàn thành khi:** FIFO/parallel/giới hạn/cleanup/exception/transaction boundary đều có bằng chứng; luồng cũ giữ kết quả; reads bypass queue; còn lỗi DB có thông báo kiểm soát. Chưa có thay đổi số tiền, webhook hoặc workflow rental mới.

### Bằng chứng P7.4 — 2026-10-27

- Thêm `CarCommandTransactionTest`: **9 test tích hợp Spring/JPA/MySQL đạt**, dùng service proxy, transaction manager và connection thật. Không mock service/DB, không thêm dependency hoặc thay đổi implementation production. Transaction listener của Spring chỉ được gắn trong từng test rồi khôi phục; latch có deadline và mọi tác vụ được đợi kết thúc trước cleanup fixture theo ID.
- **Chờ queue chưa mở transaction:** listener `beforeBegin` quan sát không có transaction nào khi action bị chặn ở queue; khi tới lượt mới có một transaction trên `car-command-*`, không ở thread caller. Gọi queue từ transaction thật đang khóa Car bị từ chối trước khi action chạy; sau khi nhả transaction vẫn tạo booking được.
- **Commit/rollback boundary:** giữ proxy ở cả trước và sau commit/rollback bằng listener/latch; caller và lệnh kế tiếp chưa được hoàn tất/chạy. Connection độc lập thấy booking/notification bằng 0 trước commit; sau commit bằng 1, sau rollback bằng 0. Lỗi rollback gốc trở về đúng caller; tác vụ kế tiếp vẫn chạy. Nhánh rollback dùng TransactionTemplate bao service proxy thật, flush booking/notification rồi ném lỗi fixture.
- **Bỏ qua queue vẫn an toàn DB:** hai connection ID khác nhau, REPEATABLE READ; A tạo booking chưa commit, B giữ snapshot cũ không thấy đơn và managed Car cũ rồi gọi trực tiếp BookingService. Test quan sát `performance_schema.data_lock_waits` đúng Car và hai connection, không suy đoán bằng sleep. A commit → B từ chối trùng lịch; A rollback → B đặt thành công. Cả hai kết thúc chỉ 1 booking và 1 notification bền vững. Tài khoản DB chạy test cần đọc được các bảng lock/thread trong `performance_schema`.
- **Current read:** snapshot/managed Car cũ vẫn có giá 500.000 và ACTIVE sau khi connection khác đổi giá/ẩn xe; createBooking dùng giá 700.000, tổng 1.400.000 cho 2 ngày hoặc từ chối xe đã ẩn. Managed Booking PENDING cũ không cho Owner confirm sau khi connection khác đã cancel; không phát sinh notification confirm.
- Đây là gate kiểm chứng implementation đã có: các lát test lần lượt đạt 1 → 3 → 5 → 9 ca. Kiểm tra độ nhạy chủ động tạm bỏ `detach` trong `BookingService.lockedCar`: **RED 2/2** (dùng giá 500.000 cũ, nhận xe đã ẩn), log `.scratch/phase7/transaction-sensitivity-red.log`. Sau đó khôi phục nguyên bytes production; `git diff -- src/main/java src/main/resources` rỗng. Không coi lỗi được chủ động tạo là bug mới đã sửa của production.
- Build cuối `.\mvnw.cmd -o '-Dmaven.compiler.useIncrementalCompilation=false' clean verify` → **BUILD SUCCESS; 103 test, 0 failure/error/skip** (19 lõi + 34 date-search + 41 HTTP queue + 9 transaction). Log `.scratch/phase7/transaction-clean-verify.log`. SQL CHECK stack và WARN shutdown từ các test lỗi có chủ đích đã có tại P7.1–P7.3.
- Script `.scratch/phase7/check-transaction-db.py` đối chiếu `.scratch/phase7/transaction-db-before.json`: rows/timestamps fingerprints, columns/constraints cả 7 bảng và ảnh seed giữ nguyên; không còn user/car fixture P7.4. Checker P7.3 cũng đạt lại. Không reset/seed/DDL DB; AUTO_INCREMENT có thể tăng do fixture.
- Contract worker Phase 4/6 bên dưới đã ghi; chưa tạo inbox/job/payout hoặc thử API tiền thật. Không benchmark/browser, không thêm cơ chế queue phân tán. Nhánh triển khai `feat/car-queue-transaction`; chủ dự án yêu cầu commit/push/merge sau kiểm chứng và giữ nhánh. Thay đổi riêng sẵn có ở `phase-0-init.md` được giữ ngoài commit. Phụ trách `thanhmit`, reviewer `huy69332` chưa ghi nhận review.

### Contract worker tài chính cho Phase 4/6

Đây là hợp đồng tích hợp cho các worker sẽ được tạo tại Phase 4/6, không phải code thanh toán đã triển khai.

1. **Nguồn việc là DB.** Webhook phải xác thực và commit inbox trước ACK. Scheduler tìm lại việc chưa hoàn tất từ DB sau restart; không dùng queue RAM hoặc một `Supplier` trong bộ nhớ làm bằng chứng đã nhận tiền.
2. **Claim và route trước queue.** `ReceiptProcessor.process(inboxId)` đọc ID đối tượng/carId từ dữ liệu server; claim bằng lease trong transaction ngắn rồi commit. Kết thúc transaction và nhả khóa trước `execute`. Route chỉ là gợi ý định tuyến; apply phải đọc lại đối tượng/trạng thái hiện hành. Không chuyển managed entity, EntityManager hoặc transaction sang worker.
3. **Rental theo carId.** Worker gọi `commands.execute(carId, () -> { applicationService.apply(inboxId); return null; })`. `applicationService` là Spring proxy `ReceiptApplicationService`, mở transaction hữu hạn khi đến lượt; không gọi qua `this`, không lồng queue. Job expiry dùng cùng quy tắc với `expire(intentId)`.
4. **Giữ thứ tự khóa và current read.** Apply rental khóa Car/Booking trước Obligation/Intent/Receipt rồi mới handler domain; trạng thái managed/snapshot cũ phải được đọc lại dưới khóa như protocol hiện có. Với subscription, khóa Owner trước entitlement/order và Car theo ID tăng; không vào Car queue rồi quay lại lấy Owner lock, không tạo carId giả cho gói.
5. **Chỉ hoàn tất sau commit.** Receipt duy nhất, thay đổi domain, notification và đánh dấu inbox processed nằm cùng transaction. Nếu lỗi thì rollback cả nhóm; không có trạng thái processed bền vững trước commit. Caller chỉ nhận kết quả sau proxy hoàn tất commit/rollback.
6. **Từ chối admission không làm mất việc.** Queue đầy, hết chờ trước chạy hoặc app đang dừng: giữ việc trong DB để quét lại; trả lease hoặc chờ lease hết hạn theo cơ chế claim, không đánh dấu processed/failed vĩnh viễn. Tác vụ bị từ chối trước chạy không được âm thầm apply muộn. Tác vụ RUNNING đợi kết quả transaction, không coi hết chờ queue là bằng chứng đã rollback.
7. **Retry phải idempotent.** Rollback/crash trước commit cho phép retry; crash sau commit nhưng trước nhận kết quả phải được nhận diện từ DB để không cộng tiền/cấp quyền/tạo refund lần hai. Unique provider transaction và business key theo Phase 4 là lớp bảo vệ cuối, không dựa vào FIFO để chống trùng. Domain chưa bật vẫn giữ việc bền vững.
8. **Tách quyền người dùng và ghi nhận tiền.** HTTP giữ CSRF và kiểm tra lại actor ACTIVE/role/ownership khi thực thi. Worker dùng entry point hệ thống, kiểm chứng channel/chủ thể và sự kiện đã xác thực; không giả session Customer hoặc bỏ giao dịch ngân hàng chỉ vì tài khoản bị khóa.
9. **API chi ngoài transaction/queue.** Claim/lưu attempt và idempotency key → commit → gọi provider ngoài DB transaction và Car queue → ghi kết quả trong transaction ngắn; đối soát kết quả chưa rõ theo cùng key. Queue không giải quyết side effect mạng hoặc thay cơ chế phục hồi payout.

Kiểm chứng inbox/retry/restart/payout thật thuộc P4.5–P4.10; P7.4 không tạo schema/job tiền, không chứng minh provider đã sẵn sàng live. Xem [hợp đồng chung §6.3](README.md#63-khóa-queue-và-giao-dịch-ngoài-hệ-thống) và [P4.6](phase-4-payment.md#task-p46--apply-receipt-một-lần-và-adapter-domain).
