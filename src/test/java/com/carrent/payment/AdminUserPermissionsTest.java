package com.carrent.payment;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import com.carrent.concurrency.CarCommandExecutor;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AdminUserPermissionsTest {
    static PaymentFlowFixture f;
    @BeforeAll static void start() throws Exception { f=new PaymentFlowFixture(); }
    @AfterAll static void stop() throws Exception { if(f!=null) f.close(); }
    @BeforeEach void reset() {
        f.reset();
        f.jdbc.update("UPDATE users SET role='ADMIN' WHERE id=1");
        f.jdbc.update("UPDATE users SET role='OWNER' WHERE id=2");
        f.jdbc.update("UPDATE users SET status='ACTIVE' WHERE id=3");
    }

    @Test void listAndProfileKeepTheAdminUsersNavigationCurrent() throws Exception {
        for (String path : new String[]{"/admin/users", "/admin/users/2"}) {
            assertThat(html(path, 1)).contains("aria-label=\"Điều hướng quản trị\"")
                    .containsPattern("<a(?=[^>]*href=\"/admin/users\")(?=[^>]*aria-current=\"page\")[^>]*>");
        }
    }

    @Test void adminListsProfilesAndNativeStatusFormWithoutPasswordOrUnsafeMarkup() throws Exception {
        String page=html("/admin/users",1);
        assertThat(page).contains("Quản lý tài khoản", "/admin/users/2", "name=\"q\"")
                .doesNotContain("passwordHash", "password_hash", "$2a$");
        assertOptionLabels(page);
        String detail=html("/admin/users/2",1);
        assertOptionLabels(detail);
        assertThat(detail).contains("Số liệu của tài khoản này", "/admin/users/2/status", "name=\"_csrf\"")
                .doesNotContain("passwordHash", "$2a$");
        assertThat(html("/admin/users/1",1)).contains("chính mình").doesNotContain("/admin/users/1/status");
        var name=f.jdbc.queryForObject("SELECT full_name FROM users WHERE id=3",String.class);
        try {
            f.jdbc.update("UPDATE users SET full_name='<img src=x onerror=alert(53)>' WHERE id=3");
            assertThat(html("/admin/users/3",1)).contains("&lt;img").doesNotContain("<img src=x onerror", "$2a$");
        } finally { f.jdbc.update("UPDATE users SET full_name=? WHERE id=3",name); }
    }

    private static void assertOptionLabels(String html) {
        var options=java.util.regex.Pattern.compile("<option\\b[^>]*>(.*?)</option>",java.util.regex.Pattern.DOTALL).matcher(html);
        int count=0;
        while(options.find()) { assertThat(options.group(1).trim()).isNotEmpty(); count++; }
        assertThat(count).isGreaterThanOrEqualTo(3);
    }

    @Test void filterPagerRetainsSearchRoleStatusAndInvalidFiltersAreSafe400() throws Exception {
        for(int i=0;i<21;i++) f.jdbc.update("INSERT INTO users(id,full_name,email,password_hash,role,status) SELECT ?,?, ?,password_hash,'OWNER','ACTIVE' FROM users WHERE id=2",99000+i,"Pager53 "+i,"pager53-"+i+"@example.test");
        try {
            String query="/admin/users?q=Pager53&role=OWNER&status=ACTIVE";
            assertThat(html(query,1)).contains("Trang sau", "q=Pager53", "role=OWNER", "status=ACTIVE", "pager53-20@")
                    .doesNotContain("pager53-0@example.test");
            assertThat(html(query+"&page=1",1)).contains("pager53-0@example.test").doesNotContain("Trang sau");
            assertThat(html(query.replace("role=OWNER","role=CUSTOMER"),1)).contains("Không tìm thấy tài khoản phù hợp");
            for(String bad : new String[]{"role=BAD","status=BAD","page=oops"})
                f.mvc.perform(get("/admin/users?"+bad).with(user(f.actor(1)))).andExpect(status().isBadRequest());
            f.mvc.perform(get("/admin/users/999999").with(user(f.actor(1)))).andExpect(status().isNotFound());
        } finally { f.jdbc.update("DELETE FROM users WHERE id BETWEEN 99000 AND 99020"); }
    }

    @Test void rolesAnonymousAndCsrfCannotChangeStatus() throws Exception {
        for(long actor:new long[]{2,4}) {
            for(String url:new String[]{"/admin/users","/admin/users/2"})
                f.mvc.perform(get(url).with(user(f.actor(actor)))).andExpect(status().isForbidden());
            f.mvc.perform(post("/admin/users/3/status").with(user(f.actor(actor))).with(csrf()).param("status","BANNED"))
                    .andExpect(status().isForbidden());
        }
        f.mvc.perform(get("/admin/users")).andExpect(status().is3xxRedirection());
        for(var request:new org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder[]{
                post("/admin/users/3/status").with(user(f.actor(1))).param("status","BANNED"),
                post("/admin/users/3/status").with(user(f.actor(1))).with(csrf().useInvalidToken()).param("status","BANNED")})
            f.mvc.perform(request).andExpect(status().isForbidden());
        assertThat(f.jdbc.queryForObject("SELECT status FROM users WHERE id=3",String.class)).isEqualTo("ACTIVE");
    }

    @Test void statusPostChangesOnlyTargetStatusAndDoesNotAcceptSelfRoleOrEmailTampering() throws Exception {
        var before=f.jdbc.queryForMap("SELECT email,role,password_hash FROM users WHERE id=3");
        for(String state:new String[]{"INACTIVE","BANNED","ACTIVE"})
            f.mvc.perform(post("/admin/users/3/status").with(user(f.actor(1))).with(csrf()).param("status",state))
                    .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/admin/users/3"));
        assertThat(f.jdbc.queryForMap("SELECT email,role,password_hash FROM users WHERE id=3")).isEqualTo(before);
        f.mvc.perform(post("/admin/users/1/status").with(user(f.actor(1))).with(csrf()).param("status","BANNED"))
                .andExpect(status().isForbidden());
        for(String key:new String[]{"role","email","passwordHash","id"})
            f.mvc.perform(post("/admin/users/3/status").with(user(f.actor(1))).with(csrf()).param("status","BANNED").param(key,"tampered"))
                    .andExpect(status().isBadRequest());
        f.mvc.perform(post("/admin/users/3/status").with(user(f.actor(1))).with(csrf()).param("status","DELETED"))
                .andExpect(status().isBadRequest());
        f.mvc.perform(post("/admin/users/3/status").with(user(f.actor(1))).with(csrf()))
                .andExpect(status().isBadRequest());
        f.mvc.perform(post("/admin/users/3/status").with(user(f.actor(1))).with(csrf()).param("status",""))
                .andExpect(status().isBadRequest());
        assertThat(f.jdbc.queryForObject("SELECT status FROM users WHERE id=3",String.class)).isEqualTo("ACTIVE");
    }

    @Test void staleAdminSessionIsRejectedForReadsAndStatusPost() throws Exception {
        var admin=f.actor(1);
        try {
            f.jdbc.update("UPDATE users SET status='BANNED' WHERE id=1");
            f.mvc.perform(get("/admin/users").with(user(admin))).andExpect(status().isForbidden());
            f.mvc.perform(post("/admin/users/3/status").with(user(admin)).with(csrf()).param("status","BANNED"))
                    .andExpect(status().isForbidden());
            f.jdbc.update("UPDATE users SET status='ACTIVE',role='OWNER' WHERE id=1");
            f.mvc.perform(get("/admin/users/3").with(user(admin))).andExpect(status().isForbidden());
            assertThat(f.jdbc.queryForObject("SELECT status FROM users WHERE id=3",String.class)).isEqualTo("ACTIVE");
        } finally { f.jdbc.update("UPDATE users SET role='ADMIN',status='ACTIVE' WHERE id=1"); }
    }

    @Test void statusBanRejectsNewAuthenticationWithoutChangingFinancialOrBookingData() throws Exception {
        var before=snapshot();
        f.mvc.perform(post("/admin/users/2/status").with(user(f.actor(1))).with(csrf()).param("status","BANNED"))
                .andExpect(status().is3xxRedirection());
        assertThatThrownBy(()->f.app.getBean(com.carrent.security.CustomUserDetailsService.class).loadUserByUsername("owner1"))
                .isInstanceOf(org.springframework.security.authentication.DisabledException.class);
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test void adminBanWhileOwnerRequestIsQueuedRejectsStalePrincipalAtExecution() throws Exception {
        var queue=f.app.getBean(CarCommandExecutor.class);
        var owner=f.actor(2);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var blocker=CompletableFuture.runAsync(()->queue.execute(1L,()->{entered.countDown();await(release);return null;}));
        assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
        var request=new FutureTask<org.springframework.test.web.servlet.MvcResult>(()->f.mvc.perform(post("/owner/cars/1/deactivate")
                .with(user(owner)).with(csrf())).andReturn());
        var thread=Thread.ofVirtual().start(request);
        try {
            org.awaitility.Awaitility.await().atMost(5,TimeUnit.SECONDS).until(()->
                    thread.getState()==Thread.State.TIMED_WAITING && java.util.Arrays.stream(thread.getStackTrace())
                    .anyMatch(frame->frame.getClassName().equals(CarCommandExecutor.class.getName()) && frame.getMethodName().equals("execute")));
            assertThat(request.isDone()).isFalse();
            f.mvc.perform(post("/admin/users/2/status").with(user(f.actor(1))).with(csrf()).param("status","BANNED"))
                    .andExpect(status().is3xxRedirection());
            release.countDown();blocker.get(10,TimeUnit.SECONDS);
            var response=request.get(10,TimeUnit.SECONDS);
            assertThat(response.getResponse().getStatus()).isEqualTo(403);
            assertThat(response.getFlashMap().containsKey("successMessage")).isFalse();
            assertThat(f.jdbc.queryForObject("SELECT listing_status FROM cars WHERE id=1",String.class)).isEqualTo("ACTIVE");
        } finally {release.countDown();blocker.get(10,TimeUnit.SECONDS);request.get(10,TimeUnit.SECONDS);}
    }

    private static void await(CountDownLatch latch) {
        try { if(!latch.await(10,TimeUnit.SECONDS)) throw new AssertionError("Queue blocker timeout"); }
        catch(InterruptedException e){throw new AssertionError(e);}
    }
    private java.util.Map<String,java.util.List<String>> snapshot() {
        var result=new java.util.LinkedHashMap<String,java.util.List<String>>();
        for(String table:new String[]{"bookings","cars","payment_channels","payment_obligations","payment_intents","payment_receipts","refunds","payout_attempts","notifications"})
            result.put(table,f.jdbc.query("SELECT * FROM "+table+" ORDER BY id",(rs,n)->{
                var row=new StringBuilder();for(int i=1;i<=rs.getMetaData().getColumnCount();i++)row.append(rs.getString(i)).append('|');return row.toString();}));
        return result;
    }
    private String html(String url,long actor) throws Exception {
        return f.mvc.perform(get(url).with(user(f.actor(actor)))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
