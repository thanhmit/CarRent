package com.carrent.car;

import com.carrent.booking.Booking;
import com.carrent.booking.BookingStatus;
import com.carrent.booking.BookingCreateForm;
import com.carrent.booking.BookingService;
import com.carrent.security.CustomUserPrincipal;
import com.carrent.user.Role;
import com.carrent.user.User;
import jakarta.persistence.EntityManager;
import jakarta.servlet.Filter;
import java.time.LocalDate;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Real MySQL/MVC; read fixtures roll back, queued HTTP fixtures commit and are deleted by exact IDs. */
@SpringBootTest(properties = {"debug=false", "logging.level.root=WARN", "logging.level.org.springframework=WARN",
        "logging.level.org.hibernate=WARN", "logging.level.org.hibernate.SQL=OFF", "spring.main.banner-mode=off"})
@Transactional
@Tag("integration")
class CarDateSearchIntegrationTest {
    @Autowired EntityManager em;
    @Autowired WebApplicationContext context;
    @Autowired Filter springSecurityFilterChain;
    @Autowired CarService cars;
    @Autowired BookingService bookings;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    boolean committedFixture;
    MockMvc mvc;
    User owner;
    User customer;
    CarCategory category;
    String brand;
    final LocalDate start = LocalDate.now().plusDays(60);

    @BeforeEach
    void fixtures() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(springSecurityFilterChain).build();
        brand = "date-" + UUID.randomUUID();
        committedFixture = false;
        owner = user(Role.OWNER);
        customer = user(Role.CUSTOMER);
        category = CarCategory.builder().name(brand).build();
        em.persist(category);
    }

    private void commitFixtureForHttp() {
        // Worker transactions cannot see another thread's uncommitted fixture rows.
        committedFixture = true;
        TestTransaction.flagForCommit();
        TestTransaction.end();
    }

    @AfterEach
    void cleanupCommittedHttpFixture() {
        if (!committedFixture) return;
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            for (Long carId : jdbc.queryForList("select id from cars where brand=?", Long.class, brand)) {
                for (Long bookingId : jdbc.queryForList("select id from bookings where car_id=?", Long.class, carId)) {
                    jdbc.update("delete from notifications where related_entity_type='BOOKING' and related_entity_id=?", bookingId);
                    jdbc.update("delete from payments where booking_id=?", bookingId);
                }
                jdbc.update("delete from bookings where car_id=?", carId);
                jdbc.update("delete from car_images where car_id=?", carId);
                jdbc.update("delete from cars where id=?", carId);
            }
            jdbc.update("delete from car_categories where id=?", category.getId());
            for (User user : new User[]{owner, customer}) {
                jdbc.update("delete from notifications where user_id=?", user.getId());
                jdbc.update("delete from users where id=?", user.getId());
            }
        });
    }

    User user(Role role) {
        User user = User.builder().email(UUID.randomUUID() + "@test.local").fullName("Date search fixture")
                .passwordHash("unused-test-password-hash").role(role).build();
        em.persist(user);
        return user;
    }

    Car car(String model) {
        Car car = Car.builder().owner(owner).category(category).brand(brand).model(model)
                .manufactureYear(2024).seatCount(5).transmission(Transmission.AUTOMATIC)
                .fuelType(FuelType.GASOLINE).pricePerDay(500000L).province("Hà Nội")
                .approvalStatus(ApprovalStatus.APPROVED).listingStatus(ListingStatus.ACTIVE).build();
        em.persist(car);
        return car;
    }

    void booking(Car car, BookingStatus status, LocalDate from, LocalDate to) {
        em.persist(Booking.builder().car(car).customer(customer).startDate(from).endDate(to)
                .pricePerDaySnapshot(500000L).totalAmount(500000L * java.time.temporal.ChronoUnit.DAYS.between(from, to))
                .pickupAddress("Fixture address").status(status).build());
        em.flush();
    }

    @Test
    @SuppressWarnings("unchecked")
    void dateFilterExcludesCarsWithPendingOverlapBeforePagination() throws Exception {
        Car free = car("Free");
        Car busy = car("Busy");
        booking(busy, BookingStatus.PENDING, start.plusDays(1), start.plusDays(3));

        var result = mvc.perform(get("/cars").param("q", brand).param("startDate", start.toString())
                .param("endDate", start.plusDays(4).toString()).param("size", "1")).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        Page<Car> found = (Page<Car>) result.getModelAndView().getModel().get("cars");
        assertThat(found.getTotalElements()).isEqualTo(1);
        assertThat(found.getContent()).extracting(Car::getId).containsExactly(free.getId());
    }

    static Stream<Arguments> invalidDates() {
        String future = LocalDate.now().plusDays(60).toString();
        return Stream.of(Arguments.of(future, ""), Arguments.of("", future),
                Arguments.of("bad", future), Arguments.of(future, "bad"),
                Arguments.of("2026-02-30", future), Arguments.of(future, future),
                Arguments.of(future, LocalDate.now().plusDays(59).toString()),
                Arguments.of(LocalDate.now().minusDays(1).toString(), future),
                Arguments.of(future, "+10000-01-01"));
    }

    @ParameterizedTest
    @MethodSource("invalidDates")
    void invalidDatesStayOnSearchWithEnteredFilters(String from, String to) throws Exception {
        var result = mvc.perform(get("/cars").param("q", brand).param("startDate", from)
                .param("endDate", to).param("province", "Hà Nội")).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getModelAndView().getViewName()).isEqualTo("cars/list");
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("id=\"car-filters\"", "value=\"" + brand + "\"", "value=\"Hà Nội\"", "role=\"alert\"")
                .doesNotContain("Failed to convert", "java.time.LocalDate", "Xem chi tiết");
        assertThat(body).contains("value=\"" + from + "\"", "value=\"" + to + "\"");
    }

    MockHttpSession session(User user) {
        var principal = CustomUserPrincipal.from(user);
        var security = SecurityContextHolder.createEmptyContext();
        security.setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        var session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, security);
        return session;
    }

    String link(String body, String path) {
        var match = Pattern.compile("href=\"(" + Pattern.quote(path) + "(?:\\?[^\"]*)?)\"").matcher(body);
        assertThat(match.find()).as("link to " + path).isTrue();
        return match.group(1).replace("&amp;", "&");
    }

    @Test
    void datesSurviveSearchDetailAndBookingForm() throws Exception {
        Car car = car("Selected");
        em.flush();
        var who = session(customer);
        var list = mvc.perform(get("/cars").session(who).param("q", brand)
                .param("startDate", start.toString()).param("endDate", start.plusDays(4).toString())).andReturn();
        String detailLink = link(list.getResponse().getContentAsString(StandardCharsets.UTF_8), "/cars/" + car.getId());
        assertThat(detailLink).contains("startDate=" + start, "endDate=" + start.plusDays(4));
        var detail = mvc.perform(get(detailLink).session(who)).andReturn();
        assertThat(detail.getResponse().getStatus()).isEqualTo(200);
        String body = detail.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).doesNotContain("Kiểm tra khả dụng", "availability-start", "availability-end", "Xe còn trống trong khoảng");
        String bookingLink = link(body, "/bookings/new");
        assertThat(bookingLink).contains("startDate=" + start, "endDate=" + start.plusDays(4));
        var result = mvc.perform(get(bookingLink).session(who)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        BookingCreateForm form = (BookingCreateForm) result.getModelAndView().getModel().get("form");
        assertThat(form.getStartDate()).isEqualTo(start);
        assertThat(form.getEndDate()).isEqualTo(start.plusDays(4));
        assertThat(form.getCarId()).isEqualTo(car.getId());
    }

    String csrf(String body) {
        var match = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(body);
        assertThat(match.find()).isTrue();
        return match.group(1);
    }

    @Test
    void bookingConflictAfterSearchKeepsDatesAndNote() throws Exception {
        Car car = car("Late booking");
        em.flush();
        var who = session(customer);
        var page = mvc.perform(get("/bookings/new").session(who).param("carId", car.getId().toString())
                .param("startDate", start.toString()).param("endDate", start.plusDays(4).toString())).andReturn();
        String token = csrf(page.getResponse().getContentAsString(StandardCharsets.UTF_8));
        booking(car, BookingStatus.CONFIRMED, start.plusDays(1), start.plusDays(3));

        commitFixtureForHttp();

        var result = mvc.perform(post("/bookings").session(who).param("_csrf", token)
                .param("carId", car.getId().toString()).param("startDate", start.toString())
                .param("endDate", start.plusDays(4).toString()).param("customerNote", "Giữ ghi chú <script>test</script>")).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getModelAndView().getViewName()).isEqualTo("booking/create");
        BookingCreateForm form = (BookingCreateForm) result.getModelAndView().getModel().get("form");
        assertThat(form.getStartDate()).isEqualTo(start);
        assertThat(form.getEndDate()).isEqualTo(start.plusDays(4));
        assertThat(form.getCustomerNote()).isEqualTo("Giữ ghi chú <script>test</script>");
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("trùng thời gian thuê", "&lt;script&gt;test&lt;/script&gt;")
                .doesNotContain("<script>test</script>");
    }

    @ParameterizedTest
    @CsvSource({"PENDING,1,3,false", "CONFIRMED,1,3,false", "CANCELLED,1,3,true",
            "REJECTED,1,3,true", "COMPLETED,1,3,true", "PENDING,-4,0,true",
            "CONFIRMED,4,8,true", "CONFIRMED,-2,2,false", "PENDING,2,6,false",
            "PENDING,-1,5,false", "CONFIRMED,0,4,false"})
    void onlyActiveOverlappingBookingsHideCar(BookingStatus state, int from, int to, boolean available) {
        Car selected = car("Selected");
        Car other = car("Other");
        booking(selected, state, start.plusDays(from), start.plusDays(to));
        var criteria = new CarSearchCriteria();
        criteria.setQ(brand);
        criteria.setStartDate(start);
        criteria.setEndDate(start.plusDays(4));
        var found = cars.searchPublicCars(criteria, org.springframework.data.domain.PageRequest.of(0, 12));
        assertThat(found.getContent()).extracting(Car::getId).contains(other.getId());
        assertThat(found.getContent().stream().anyMatch(car -> car.getId().equals(selected.getId())))
                .isEqualTo(available);
    }

    @Test
    @SuppressWarnings("unchecked")
    void filtersSortAndPaginationKeepDatesAndCorrectCount() throws Exception {
        Car first = car("First");
        Car second = car("Second");
        second.setPricePerDay(800000L);
        Car busy = car("Busy");
        busy.setPricePerDay(100000L);
        Car hidden = car("Hidden");
        hidden.setListingStatus(ListingStatus.INACTIVE);
        Car unapproved = car("Unapproved");
        unapproved.setApprovalStatus(ApprovalStatus.PENDING);
        Car wrongFuel = car("Wrong fuel");
        wrongFuel.setFuelType(FuelType.DIESEL);
        booking(busy, BookingStatus.CONFIRMED, start, start.plusDays(4));
        String uri = "/cars?q=" + brand + "&startDate=" + start + "&endDate=" + start.plusDays(4)
                + "&categoryId=" + category.getId() + "&minPrice=0&maxPrice=900000&seatCount=5"
                + "&transmission=AUTOMATIC&fuelType=GASOLINE&sort=priceAsc&size=1";
        var page = mvc.perform(get(uri)).andReturn();
        assertThat(page.getResponse().getStatus()).isEqualTo(200);
        Page<Car> found = (Page<Car>) page.getModelAndView().getModel().get("cars");
        assertThat(found.getTotalElements()).isEqualTo(2);
        assertThat(found.getContent()).extracting(Car::getId).containsExactly(first.getId());
        String body = page.getResponse().getContentAsString(StandardCharsets.UTF_8);
        var next = Pattern.compile("href=\"([^\"]*page=1)\"").matcher(body);
        assertThat(next.find()).isTrue();
        String nextUrl = next.group(1).replace("&amp;", "&");
        assertThat(nextUrl).contains("startDate=" + start, "endDate=" + start.plusDays(4), "sort=priceAsc");
        var nextPage = mvc.perform(get(nextUrl)).andReturn();
        found = (Page<Car>) nextPage.getModelAndView().getModel().get("cars");
        assertThat(found.getContent()).extracting(Car::getId).containsExactly(second.getId());
    }

    @Test
    @SuppressWarnings("unchecked")
    void emptyDatesShowAllPublicCarsEvenWithBookedDates() throws Exception {
        Car busy = car("Busy");
        Car free = car("Free");
        Car hidden = car("Hidden");
        hidden.setApprovalStatus(ApprovalStatus.REJECTED);
        booking(busy, BookingStatus.PENDING, start, start.plusDays(4));
        var result = mvc.perform(get("/cars").param("q", brand).param("startDate", "").param("endDate", "")).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        Page<Car> found = (Page<Car>) result.getModelAndView().getModel().get("cars");
        assertThat(found.getContent()).extracting(Car::getId).containsExactlyInAnyOrder(busy.getId(), free.getId());
    }

    @Test
    void noAvailableCarsExplainsChosenPeriod() throws Exception {
        Car busy = car("Busy");
        booking(busy, BookingStatus.PENDING, start, start.plusDays(4));
        var result = mvc.perform(get("/cars").param("q", brand).param("startDate", start.toString())
                .param("endDate", start.plusDays(4).toString())).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("Không có xe trống trong khoảng ngày đã chọn");
    }

    @Test
    void customerCanEditPrefilledDatesAndServerBooksFinalPeriod() throws Exception {
        Car car = car("Editable dates");
        em.flush();
        commitFixtureForHttp();
        var who = session(customer);
        var form = mvc.perform(get("/bookings/new").session(who).param("carId", car.getId().toString())
                .param("startDate", start.toString()).param("endDate", start.plusDays(4).toString())).andReturn();
        var result = mvc.perform(post("/bookings").session(who)
                .param("_csrf", csrf(form.getResponse().getContentAsString(StandardCharsets.UTF_8)))
                .param("carId", car.getId().toString()).param("startDate", start.plusDays(10).toString())
                .param("endDate", start.plusDays(12).toString()).param("totalAmount", "1")).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(302);
        String location = result.getResponse().getRedirectedUrl();
        Booking saved = bookings.getBookingForView(Long.valueOf(location.substring("/bookings/".length())),
                CustomUserPrincipal.from(customer));
        assertThat(saved.getStartDate()).isEqualTo(start.plusDays(10));
        assertThat(saved.getEndDate()).isEqualTo(start.plusDays(12));
        assertThat(saved.getTotalAmount()).isEqualTo(1000000L);
        assertThat(saved.getStatus()).isEqualTo(BookingStatus.PENDING);
    }

    @Test
    void bookingStillRequiresCustomerAndCsrfWhileSearchIsPublic() throws Exception {
        Car car = car("Security");
        em.flush();
        assertThat(mvc.perform(get("/bookings/new").param("carId", car.getId().toString())).andReturn()
                .getResponse().getStatus()).isEqualTo(302);
        assertThat(mvc.perform(get("/bookings/new").session(session(owner)).param("carId", car.getId().toString()))
                .andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(mvc.perform(post("/bookings").session(session(customer)).param("carId", car.getId().toString()))
                .andReturn().getResponse().getStatus()).isEqualTo(403);
    }

    @ParameterizedTest
    @CsvSource({"bad,4", "missing,4", "equal,0", "reversed,-1", "past,4"})
    void invalidBookingSubmissionKeepsEnteredData(String scenario, int endOffset) throws Exception {
        Car car = car("Invalid booking");
        em.flush();
        var who = session(customer);
        var page = mvc.perform(get("/bookings/new").session(who).param("carId", car.getId().toString())).andReturn();
        commitFixtureForHttp();
        String from = switch (scenario) {
            case "bad" -> "bad";
            case "missing" -> "";
            case "past" -> LocalDate.now().minusDays(1).toString();
            default -> start.toString();
        };
        var result = mvc.perform(post("/bookings").session(who)
                .param("_csrf", csrf(page.getResponse().getContentAsString(StandardCharsets.UTF_8)))
                .param("carId", car.getId().toString()).param("startDate", from)
                .param("endDate", start.plusDays(endOffset).toString()).param("customerNote", "Giữ lịch trình")).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getModelAndView().getViewName()).isEqualTo("booking/create");
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(org.springframework.web.util.HtmlUtils.htmlUnescape(body))
                .contains("Giữ lịch trình", "value=\"" + start.plusDays(endOffset) + "\"")
                .doesNotContain("Failed to convert", "java.time.LocalDate");
        String back = link(body, "/cars/" + car.getId());
        assertThat(org.springframework.web.util.UriComponentsBuilder.fromUriString(back).build()
                .getQueryParams().getFirst("startDate")).isNullOrEmpty();
    }

    @Test
    void directDetailAndOwnerPreviewHaveNoAvailabilityForm() throws Exception {
        Car car = car("Direct detail");
        em.flush();
        var direct = mvc.perform(get("/cars/" + car.getId())).andReturn();
        assertThat(direct.getResponse().getStatus()).isEqualTo(200);
        assertThat(direct.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("Đặt thuê xe").doesNotContain("Kiểm tra khả dụng", "type=\"date\"");
        car.setListingStatus(ListingStatus.INACTIVE);
        em.flush();
        assertThat(mvc.perform(get("/cars/" + car.getId()).session(session(customer))).andReturn()
                .getResponse().getStatus()).isEqualTo(404);
        var preview = mvc.perform(get("/cars/" + car.getId()).session(session(owner))).andReturn();
        assertThat(preview.getResponse().getStatus()).isEqualTo(200);
        assertThat(preview.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("Bản xem trước").doesNotContain("Đặt thuê xe", "Kiểm tra khả dụng");
    }
}
