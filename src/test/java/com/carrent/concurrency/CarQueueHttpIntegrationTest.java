package com.carrent.concurrency;

import com.carrent.booking.Booking;
import com.carrent.booking.BookingStatus;
import com.carrent.car.*;
import com.carrent.common.exception.BusinessException;
import com.carrent.notification.NotificationRepository;
import com.carrent.notification.NotificationService;
import com.carrent.security.CustomUserPrincipal;
import com.carrent.user.Role;
import com.carrent.user.User;
import com.carrent.user.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.servlet.Filter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest(properties = {"debug=false", "logging.level.root=WARN", "logging.level.org.springframework=WARN",
        "logging.level.org.hibernate=WARN", "logging.level.org.hibernate.SQL=OFF", "spring.main.banner-mode=off",
        "carrent.car-queue.max-waiting-per-car=1", "carrent.car-queue.wait-timeout=2s"})
@Import(CarQueueHttpIntegrationTest.FaultConfiguration.class)
class CarQueueHttpIntegrationTest {
    @Autowired ApplicationContext context;
    @Autowired WebApplicationContext web;
    @Autowired Filter springSecurityFilterChain;
    @Autowired CarCommandExecutor queue;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager manager;
    @Autowired JdbcTemplate jdbc;
    @Autowired FaultNotifications faults;
    MockMvc mvc;
    TransactionTemplate tx;
    String brand;
    User owner;
    User customer;
    User admin;
    Car car;
    CarCategory category;
    Booking booking;
    CarImage image;
    Path imagePath;
    List<User> fixtureUsers = new ArrayList<>();
    LocalDate start = LocalDate.now().plusDays(60);

    @BeforeEach
    void fixtures() throws IOException {
        mvc = MockMvcBuilders.webAppContextSetup(web).addFilters(springSecurityFilterChain).build();
        tx = new TransactionTemplate(manager);
        brand = "queue-" + UUID.randomUUID();
        tx.executeWithoutResult(status -> {
            owner = user(Role.OWNER);
            customer = user(Role.CUSTOMER);
            admin = user(Role.ADMIN);
            category = CarCategory.builder().name(brand).build();
            em.persist(category);
            car = Car.builder().owner(owner).category(category).brand(brand).model("Queue fixture")
                    .manufactureYear(2024).seatCount(5).transmission(Transmission.AUTOMATIC)
                    .fuelType(FuelType.GASOLINE).pricePerDay(500000L).province("Hà Nội")
                    .approvalStatus(ApprovalStatus.APPROVED).listingStatus(ListingStatus.ACTIVE).build();
            em.persist(car);
            booking = Booking.builder().car(car).customer(customer).startDate(start).endDate(start.plusDays(2))
                    .pricePerDaySnapshot(500000L).totalAmount(1000000L).pickupAddress("Fixture")
                    .status(BookingStatus.PENDING).build();
            em.persist(booking);
            image = CarImage.builder().car(car).imageUrl("/uploads/queue-" + UUID.randomUUID() + ".png").build();
            em.persist(image);
        });
        imagePath = Path.of(image.getImageUrl().substring(1));
        Files.write(imagePath, new byte[]{1, 2, 3});
    }

    User user(Role role) {
        var user = User.builder().email(UUID.randomUUID() + "@queue.test").fullName("Queue fixture")
                .passwordHash("unused-test-password-hash").role(role).build();
        em.persist(user);
        fixtureUsers.add(user);
        return user;
    }

    @AfterEach
    void cleanup() throws IOException {
        faults.clear();
        if (brand == null) return;
        tx.executeWithoutResult(status -> {
            for (Long carId : jdbc.queryForList("select id from cars where brand=?", Long.class, brand)) {
                for (Long bookingId : jdbc.queryForList("select id from bookings where car_id=?", Long.class, carId)) {
                    jdbc.update("delete from notifications where related_entity_type='BOOKING' and related_entity_id=?", bookingId);
                    jdbc.update("delete from payments where booking_id=?", bookingId);
                }
                jdbc.update("delete from bookings where car_id=?", carId);
                jdbc.update("delete from notifications where related_entity_type='CAR' and related_entity_id=?", carId);
                jdbc.update("delete from car_images where car_id=?", carId);
                jdbc.update("delete from cars where id=?", carId);
            }
            for (User user : fixtureUsers) {
                if (user != null) {
                    jdbc.update("delete from notifications where user_id=?", user.getId());
                    jdbc.update("delete from users where id=?", user.getId());
                }
            }
            if (category != null) jdbc.update("delete from car_categories where id=?", category.getId());
        });
        if (imagePath != null) Files.deleteIfExists(imagePath);
    }

    static Stream<String> mutationRoutes() {
        return Stream.of("create-booking", "cancel", "confirm", "complete", "reject-booking", "edit",
                "delete-image", "owner-deactivate", "owner-activate", "delete-car", "approve",
                "reject-car", "admin-deactivate", "admin-activate");
    }

    @ParameterizedTest
    @MethodSource("mutationRoutes")
    void admittedMutationsCommitAndKeepTheirExistingResponses(String route) throws Exception {
        if (route.equals("approve") || route.equals("reject-car"))
            jdbc.update("update cars set approval_status='PENDING' where id=?", car.getId());
        if (route.endsWith("activate") && !route.endsWith("deactivate"))
            jdbc.update("update cars set listing_status='INACTIVE' where id=?", car.getId());
        if (route.equals("complete"))
            jdbc.update("update bookings set status='CONFIRMED', start_date=?, end_date=? where id=?",
                    LocalDate.now().minusDays(3), LocalDate.now().minusDays(1), booking.getId());
        if (route.equals("delete-car")) jdbc.update("delete from bookings where id=?", booking.getId());
        var who = session(actor(route));
        var response = mvc.perform(request(route).session(who).param("_csrf", token(who))).andReturn();
        assertThat(response.getResponse().getStatus()).as(route).isEqualTo(302);
        assertThat(response.getFlashMap().containsKey("successMessage")).as(route).isTrue();
        switch (route) {
            case "create-booking" -> assertThat(jdbc.queryForObject("select count(*) from bookings where car_id=?", Integer.class, car.getId())).isEqualTo(2);
            case "cancel", "confirm", "complete", "reject-booking" -> {
                String expected = Map.of("cancel", "CANCELLED", "confirm", "CONFIRMED", "complete", "COMPLETED", "reject-booking", "REJECTED").get(route);
                assertThat(bookingStatus()).isEqualTo(expected);
                assertThat(bookingNotifications()).isOne();
            }
            case "edit" -> assertThat(jdbc.queryForObject("select model from cars where id=?", String.class, car.getId())).isEqualTo("Updated fixture");
            case "delete-image" -> {
                assertThat(jdbc.queryForObject("select count(*) from car_images where id=?", Integer.class, image.getId())).isZero();
                assertThat(Files.exists(imagePath)).isFalse();
            }
            case "delete-car" -> {
                assertThat(jdbc.queryForObject("select count(*) from cars where id=?", Integer.class, car.getId())).isZero();
                assertThat(Files.exists(imagePath)).isFalse();
            }
            case "approve", "reject-car" -> assertThat(jdbc.queryForObject("select approval_status from cars where id=?", String.class, car.getId()))
                    .isEqualTo(route.equals("approve") ? "APPROVED" : "REJECTED");
            default -> assertThat(jdbc.queryForObject("select listing_status from cars where id=?", String.class, car.getId()))
                    .isEqualTo(route.endsWith("deactivate") ? "INACTIVE" : "ACTIVE");
        }
    }

    @Test
    void timedOutBookingRequestNeverCreatesAnOrderAfterTheWorkerIsReleased() throws Exception {
        var who = session(customer);
        String token = token(who);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var blocker = call(() -> queue.execute(car.getId(), () -> { entered.countDown(); await(release); return null; }));
        try {
            await(entered);
            var response = mvc.perform(request("create-booking").session(who).param("_csrf", token)).andReturn();
            assertThat(response.getResponse().getStatus()).isEqualTo(503);
            assertThat(response.getFlashMap().containsKey("successMessage")).isFalse();
            release.countDown();
            blocker.get();
            assertThat(jdbc.queryForObject("select count(*) from bookings where car_id=?", Integer.class, car.getId())).isOne();
            assertThat(bookingNotifications()).isZero();
        } finally {
            release.countDown();
            blocker.get();
        }
    }

    @Test
    void readsAndCreatingANewCarBypassAnExistingCarsFullQueue() throws Exception {
        var who = session(owner);
        String token = token(who);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var blocker = call(() -> queue.execute(car.getId(), () -> { entered.countDown(); await(release); return null; }));
        try {
            await(entered);
            var waiting = call(() -> queue.execute(car.getId(), () -> null));
            waiting.awaitWaiting();
            assertThat(mvc.perform(get("/cars/" + car.getId())).andReturn().getResponse().getStatus()).isEqualTo(200);
            assertThat(mvc.perform(get("/bookings/" + booking.getId()).session(who)).andReturn().getResponse().getStatus()).isEqualTo(200);
            var response = mvc.perform(carForm(post("/owner/cars/new")).session(who).param("_csrf", token)).andReturn();
            assertThat(response.getResponse().getStatus()).isEqualTo(302);
            assertThat(response.getFlashMap().containsKey("successMessage")).isTrue();
            assertThat(jdbc.queryForObject("select count(*) from cars where brand=?", Integer.class, brand)).isEqualTo(2);
            release.countDown();
            blocker.get();
            try {
                waiting.get();
            } catch (ExecutionException ex) {
                // The queue filler may time out while unrelated HTTP views render; only their bypass is under test.
                assertThat(ex.getCause()).isInstanceOf(CarQueueRejectedException.class)
                        .hasMessageContaining("hết thời gian chờ");
            }
        } finally {
            release.countDown();
        }
    }

    @Test
    void csrfRoleOwnershipAndInvalidIdsStillRejectRequests() throws Exception {
        var own = session(owner);
        var cus = session(customer);
        var adm = session(admin);
        String ownerToken = token(own), customerToken = token(cus), adminToken = token(adm);
        var foreign = tx.execute(status -> user(Role.OWNER));
        var foreignSession = session(foreign);
        assertThat(mvc.perform(request("confirm").session(own)).andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(mvc.perform(request("confirm").session(cus).param("_csrf", customerToken)).andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(mvc.perform(request("confirm").session(foreignSession).param("_csrf", token(foreignSession))).andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mvc.perform(post("/owner/cars/0/deactivate").session(own).param("_csrf", ownerToken)).andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mvc.perform(post("/admin/cars/0/approve").session(adm).param("_csrf", adminToken)).andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mvc.perform(post("/bookings").session(cus).param("_csrf", customerToken).param("carId", "0")
                .param("startDate", start.toString()).param("endDate", start.plusDays(1).toString())).andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(bookingStatus()).isEqualTo("PENDING");
        assertThat(bookingNotifications()).isZero();
    }

    User actor(String route) {
        return route.equals("create-booking") || route.equals("cancel") ? customer
                : List.of("approve", "reject-car", "admin-deactivate", "admin-activate").contains(route) ? admin : owner;
    }

    String bookingStatus() {
        return jdbc.queryForObject("select status from bookings where id=?", String.class, booking.getId());
    }

    int bookingNotifications() {
        return jdbc.queryForObject("select count(*) from notifications where related_entity_type='BOOKING' and related_entity_id=?",
                Integer.class, booking.getId());
    }

    static Stream<String> firstTransitions() { return Stream.of("cancel", "confirm"); }

    @ParameterizedTest
    @MethodSource("firstTransitions")
    void competingConfirmAndCancelRecheckTheCommittedStatusInQueueOrder(String first) throws Exception {
        String second = first.equals("cancel") ? "confirm" : "cancel";
        var firstSession = session(actor(first));
        var secondSession = session(actor(second));
        String firstCsrf = token(firstSession), secondCsrf = token(secondSession);
        var fault = faults.arm(booking.getId(), FailureMode.PAUSE);
        var running = call(() -> perform(first, firstSession, firstCsrf));
        Call<MvcResult> waiting = null;
        try {
            await(fault.entered());
            waiting = call(() -> perform(second, secondSession, secondCsrf));
            waiting.awaitWaiting();
            fault.release().countDown();
            var firstResult = running.get();
            var secondResult = waiting.get();
            assertThat(firstResult.getResponse().getStatus()).isEqualTo(302);
            assertThat(firstResult.getFlashMap().containsKey("successMessage")).isTrue();
            assertThat(secondResult.getResponse().getStatus()).isEqualTo(302);
            // Legacy policy permits cancelling CONFIRMED, but never confirming CANCELLED.
            assertThat(secondResult.getFlashMap().containsKey("successMessage")).isEqualTo(first.equals("confirm"));
            assertThat(secondResult.getFlashMap().containsKey("errorMessage")).isEqualTo(first.equals("cancel"));
            assertThat(bookingStatus()).isEqualTo("CANCELLED");
            assertThat(bookingNotifications()).isEqualTo(first.equals("cancel") ? 1 : 2);
        } finally {
            fault.release().countDown();
            running.get();
            if (waiting != null) waiting.get();
        }
    }

    @Test
    void notificationFailureRollsBackCancellationAndLegacyRefundBeforeReturningAnError() throws Exception {
        jdbc.update("update bookings set status='CONFIRMED' where id=?", booking.getId());
        jdbc.update("insert into payments (booking_id, amount, method, status, transaction_reference, paid_at, created_at, updated_at) "
                + "values (?, 1000000, 'MOCK_BANK_TRANSFER', 'PAID', ?, now(), now(), now())", booking.getId(), UUID.randomUUID().toString());
        faults.arm(booking.getId(), FailureMode.BUSINESS);
        var who = session(customer);
        String csrf = token(who);
        var response = perform("cancel", who, csrf);
        assertThat(response.getResponse().getStatus()).isEqualTo(302);
        assertThat(response.getFlashMap().containsKey("successMessage")).isFalse();
        assertThat(response.getFlashMap().get("errorMessage")).isEqualTo("Notification fixture failure");
        assertThat(bookingStatus()).isEqualTo("CONFIRMED");
        assertThat(paymentStatus()).isEqualTo("PAID");
        assertThat(bookingNotifications()).isZero();
        assertThat(perform("cancel", who, csrf).getFlashMap().containsKey("successMessage")).isTrue();
        assertThat(bookingStatus()).isEqualTo("CANCELLED");
        assertThat(paymentStatus()).isEqualTo("REFUNDED");
        assertThat(bookingNotifications()).isEqualTo(2);
    }

    String paymentStatus() {
        return jdbc.queryForObject("select status from payments where booking_id=?", String.class, booking.getId());
    }

    static Stream<FailureMode> databaseFailures() { return Stream.of(FailureMode.SQL, FailureMode.LOCK); }

    @ParameterizedTest
    @MethodSource("databaseFailures")
    void databaseFailureRollsBackBeforeHttpErrorAndTheNextCommandCanCommit(FailureMode mode) throws Exception {
        faults.arm(booking.getId(), mode);
        var who = session(owner);
        String csrf = token(who);
        var response = perform("confirm", who, csrf);
        assertThat(response.getResponse().getStatus()).isEqualTo(mode == FailureMode.SQL ? 500 : 409);
        assertThat(response.getFlashMap().containsKey("successMessage")).isFalse();
        assertThat(response.getResponse().getContentAsString(StandardCharsets.UTF_8)).doesNotContain("SQLState", "chk_bookings", "java.lang");
        assertThat(bookingStatus()).isEqualTo("PENDING");
        assertThat(bookingNotifications()).isZero();
        assertThat(perform("confirm", who, csrf).getFlashMap().containsKey("successMessage")).isTrue();
        assertThat(bookingStatus()).isEqualTo("CONFIRMED");
        assertThat(bookingNotifications()).isOne();
    }

    static Stream<String> protectedMutations() { return Stream.of("confirm", "cancel", "approve"); }

    @ParameterizedTest
    @MethodSource("protectedMutations")
    void workerRejectsAnActorBannedWhileTheirRequestWasWaiting(String route) throws Exception {
        if (route.equals("approve")) jdbc.update("update cars set approval_status='PENDING' where id=?", car.getId());
        assertChangedAuthorityWhileWaiting(route, () -> jdbc.update("update users set status='BANNED' where id=?", actor(route).getId()));
    }

    @Test
    void workerRechecksCarOwnershipAfterWaiting() throws Exception {
        var other = tx.execute(status -> user(Role.OWNER));
        assertChangedAuthorityWhileWaiting("owner-deactivate", () -> jdbc.update("update cars set owner_id=? where id=?", other.getId(), car.getId()));
    }

    void assertChangedAuthorityWhileWaiting(String route, Runnable change) throws Exception {
        var who = session(actor(route));
        String csrf = token(who);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var blocker = call(() -> queue.execute(car.getId(), () -> { entered.countDown(); await(release); return null; }));
        Call<MvcResult> waiting = null;
        try {
            await(entered);
            waiting = call(() -> perform(route, who, csrf));
            waiting.awaitWaiting();
            change.run();
            release.countDown();
            blocker.get();
            var response = waiting.get();
            assertThat(response.getResponse().getStatus()).isEqualTo(403);
            assertThat(response.getFlashMap().containsKey("successMessage")).isFalse();
            assertThat(bookingStatus()).isEqualTo("PENDING");
            assertThat(jdbc.queryForObject("select listing_status from cars where id=?", String.class, car.getId())).isEqualTo("ACTIVE");
            assertThat(bookingNotifications()).isZero();
        } finally {
            release.countDown();
            blocker.get();
            if (waiting != null) waiting.get();
        }
    }

    MvcResult perform(String route, MockHttpSession who, String csrf) {
        try {
            return mvc.perform(request(route).session(who).param("_csrf", csrf)).andReturn();
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    enum FailureMode { BUSINESS, SQL, LOCK, PAUSE }

    record Fault(Long bookingId, FailureMode mode, CountDownLatch entered, CountDownLatch release) {}

    @TestConfiguration(proxyBeanMethods = false)
    static class FaultConfiguration {
        @Bean
        @Primary
        FaultNotifications faultNotifications(NotificationRepository notifications, UserRepository users, EntityManager em) {
            return new FaultNotifications(notifications, users, em);
        }
    }

    /** Real notification writes with one-shot faults; no production hooks or schema changes. */
    static class FaultNotifications extends NotificationService {
        private final EntityManager em;
        private final AtomicReference<Fault> armed = new AtomicReference<>();

        FaultNotifications(NotificationRepository notifications, UserRepository users, EntityManager em) {
            super(notifications, users);
            this.em = em;
        }

        Fault arm(Long bookingId, FailureMode mode) {
            var fault = new Fault(bookingId, mode, new CountDownLatch(1), new CountDownLatch(1));
            armed.set(fault);
            return fault;
        }

        void clear() {
            var fault = armed.getAndSet(null);
            if (fault != null) fault.release().countDown();
        }

        @Override
        @Transactional
        public void notify(Long userId, String type, String title, String message, String entityType, Long entityId) {
            super.notify(userId, type, title, message, entityType, entityId);
            var fault = armed.get();
            if (fault == null || !"BOOKING".equals(entityType) || !fault.bookingId().equals(entityId)
                    || !armed.compareAndSet(fault, null)) return;
            em.flush();
            switch (fault.mode()) {
                case BUSINESS -> throw new BusinessException("Notification fixture failure");
                case SQL -> em.createNativeQuery("update bookings set total_amount=0 where id=:id")
                        .setParameter("id", entityId).executeUpdate();
                case LOCK -> throw new PessimisticLockingFailureException("Lock fixture failure");
                case PAUSE -> { fault.entered().countDown(); await(fault.release()); }
            }
        }
    }

    @ParameterizedTest
    @MethodSource("mutationRoutes")
    void fullCarQueueRejectsEveryMutationWithoutChangingData(String route) throws Exception {
        var session = session(actor(route));
        String csrf = token(session);
        var release = new CountDownLatch(1);
        var entered = new CountDownLatch(1);
        var blocker = call(() -> queue.execute(car.getId(), () -> {
            entered.countDown();
            await(release);
            return null;
        }));
        try {
            await(entered);
            var waiting = call(() -> queue.execute(car.getId(), () -> null));
            waiting.awaitWaiting();
            var response = mvc.perform(request(route).session(session).param("_csrf", csrf)).andReturn();
            assertThat(response.getResponse().getStatus()).as(route).isEqualTo(503);
            assertThat(response.getFlashMap().containsKey("successMessage")).isFalse();
            assertThat(response.getResponse().getContentAsString(StandardCharsets.UTF_8))
                    .contains("Vui lòng thử lại").doesNotContain("java.lang", "SQLState");
            assertThat(jdbc.queryForObject("select status from bookings where id=?", String.class, booking.getId()))
                    .isEqualTo("PENDING");
            assertThat(jdbc.queryForObject("select count(*) from notifications where related_entity_type='BOOKING' and related_entity_id=?",
                    Integer.class, booking.getId())).isZero();
            assertThat(jdbc.queryForObject("select count(*) from car_images where id=?", Integer.class, image.getId())).isOne();
            release.countDown();
            blocker.get();
            waiting.get();
        } finally {
            release.countDown();
            blocker.get();
        }
    }

    MockHttpServletRequestBuilder request(String route) {
        return switch (route) {
            case "create-booking" -> post("/bookings").param("carId", car.getId().toString())
                    .param("startDate", start.plusDays(10).toString()).param("endDate", start.plusDays(12).toString());
            case "cancel" -> post("/bookings/" + booking.getId() + "/cancel");
            case "confirm", "complete" -> post("/owner/bookings/" + booking.getId() + "/" + route);
            case "reject-booking" -> post("/owner/bookings/" + booking.getId() + "/reject").param("reason", "Fixture reason");
            case "edit" -> carForm(post("/owner/cars/" + car.getId() + "/edit"));
            case "delete-image" -> post("/owner/cars/" + car.getId() + "/images/" + image.getId() + "/delete");
            case "owner-deactivate" -> post("/owner/cars/" + car.getId() + "/deactivate");
            case "owner-activate" -> post("/owner/cars/" + car.getId() + "/activate");
            case "delete-car" -> post("/owner/cars/" + car.getId() + "/delete");
            case "approve" -> post("/admin/cars/" + car.getId() + "/approve");
            case "reject-car" -> post("/admin/cars/" + car.getId() + "/reject").param("reason", "Fixture reason");
            case "admin-deactivate" -> post("/admin/cars/" + car.getId() + "/deactivate");
            case "admin-activate" -> post("/admin/cars/" + car.getId() + "/activate");
            default -> throw new IllegalArgumentException(route);
        };
    }

    MockHttpServletRequestBuilder carForm(MockHttpServletRequestBuilder request) {
        return request.param("brand", brand).param("model", "Updated fixture").param("manufactureYear", "2024")
                .param("seatCount", "5").param("transmission", "AUTOMATIC").param("fuelType", "GASOLINE")
                .param("pricePerDay", "500000").param("categoryId", category.getId().toString()).param("province", "Hà Nội");
    }

    MockHttpSession session(User user) {
        var principal = CustomUserPrincipal.from(user);
        var security = SecurityContextHolder.createEmptyContext();
        security.setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        var session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, security);
        return session;
    }

    String token(MockHttpSession session) throws Exception {
        String body = mvc.perform(get("/").session(session)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        var match = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(body);
        assertThat(match.find()).isTrue();
        return match.group(1);
    }

    static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
        }
    }

    static <T> Call<T> call(Supplier<T> action) {
        var result = new CompletableFuture<T>();
        var thread = Thread.ofVirtual().start(() -> {
            try { result.complete(action.get()); }
            catch (Throwable ex) { result.completeExceptionally(ex); }
        });
        return new Call<>(thread, result);
    }

    record Call<T>(Thread thread, CompletableFuture<T> result) {
        T get() throws Exception { return result.get(10, TimeUnit.SECONDS); }
        void awaitWaiting() {
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!result.isDone() && System.nanoTime() < until) {
                if (thread.getState() == Thread.State.TIMED_WAITING) {
                    for (var frame : thread.getStackTrace()) {
                        if (frame.getClassName().equals(CarCommandExecutor.class.getName())
                                && frame.getMethodName().equals("execute")) return;
                    }
                }
                Thread.yield();
            }
            throw new AssertionError("Caller did not reach the queue wait before completion/deadline");
        }
    }

    @Test
    void singletonQueueUsesServerPropertiesAndTransactionsHaveAFiniteTimeout() {
        assertThat(context.getBeansOfType(CarCommandExecutor.class)).hasSize(1);
        assertThat(context.getBean(CarQueueProperties.class).maxWaitingPerCar()).isEqualTo(1);
        assertThat(context.getBean(JpaTransactionManager.class).getDefaultTimeout()).isEqualTo(30);
    }
}
