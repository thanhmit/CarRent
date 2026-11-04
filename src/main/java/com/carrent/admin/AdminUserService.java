package com.carrent.admin;

import com.carrent.common.exception.ForbiddenException;
import com.carrent.common.exception.NotFoundException;
import com.carrent.security.CustomUserPrincipal;
import com.carrent.user.Role;
import com.carrent.user.User;
import com.carrent.user.UserStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.HashMap;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
public class AdminUserService {
    public record UserRow(long id, String fullName, String email, String phone, Role role,
            UserStatus status, LocalDateTime createdAt) { }
    public record UserDetail(UserRow user, long ownedCars, long customerBookings, long ownerBookings,
            long subscriptionOrders, boolean financialAvailable) { }
    private static final String COLUMNS = "id,full_name,email,phone,role,status,created_at";
    private static final RowMapper<UserRow> ROW = (rs, n) -> new UserRow(rs.getLong("id"),
            rs.getString("full_name"), rs.getString("email"), rs.getString("phone"),
            Role.valueOf(rs.getString("role")), UserStatus.valueOf(rs.getString("status")),
            rs.getObject("created_at", LocalDateTime.class));
    private final NamedParameterJdbcTemplate jdbc;
    private final EntityManager em;
    private final AdminService admins;

    public AdminUserService(NamedParameterJdbcTemplate jdbc, EntityManager em, AdminService admins) {
        this.jdbc = jdbc; this.em = em; this.admins = admins;
    }

    public Page<UserRow> list(String q, Role role, UserStatus status, int page, CustomUserPrincipal actor) {
        admins.requireAdmin(actor);
        String query = q == null ? "" : q.trim();
        if (query.length() > 150) throw invalid();
        var params = new HashMap<String,Object>();
        String where = " FROM users WHERE 1=1";
        if (!query.isEmpty()) {
            where += " AND (full_name LIKE :q ESCAPE '!' OR email LIKE :q ESCAPE '!' OR phone LIKE :q ESCAPE '!')";
            params.put("q", "%" + query.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
        }
        if (role != null) { where += " AND role=:role"; params.put("role", role.name()); }
        if (status != null) { where += " AND status=:status"; params.put("status", status.name()); }
        var request = PageRequest.of(Math.clamp(page, 0, 100000), 20);
        params.put("offset", request.getOffset());
        long total = jdbc.queryForObject("SELECT COUNT(*)" + where, params, Long.class);
        var rows = jdbc.query("SELECT " + COLUMNS + where + " ORDER BY id DESC LIMIT 20 OFFSET :offset", params, ROW);
        return new PageImpl<>(rows, request, total);
    }

    public UserDetail detail(long id, CustomUserPrincipal actor) {
        admins.requireAdmin(actor);
        var params = java.util.Map.of("id", id);
        var users = jdbc.query("SELECT " + COLUMNS + " FROM users WHERE id=:id", params, ROW);
        if (users.isEmpty()) throw missing();
        long cars = jdbc.queryForObject("SELECT COUNT(*) FROM cars WHERE owner_id=:id", params, Long.class);
        long customer = jdbc.queryForObject("SELECT COUNT(*) FROM bookings WHERE customer_id=:id", params, Long.class);
        long owner = jdbc.queryForObject("""
                SELECT COUNT(*) FROM bookings b JOIN cars c ON c.id=b.car_id WHERE c.owner_id=:id
                """, params, Long.class);
        boolean available = admins.available(actor);
        long orders = available ? jdbc.queryForObject("SELECT COUNT(*) FROM subscription_orders WHERE owner_id=:id", params, Long.class) : 0;
        return new UserDetail(users.getFirst(), cars, customer, owner, orders, available);
    }

    @Transactional
    public void changeStatus(long id, UserStatus status, CustomUserPrincipal actor) {
        if (actor == null || actor.getRole() != Role.ADMIN) throw forbidden();
        // Use one lock order so two Admins changing each other cannot deadlock.
        User first = lock(Math.min(actor.getId(), id), actor.getId());
        User second = actor.getId() == id ? first : lock(Math.max(actor.getId(), id), actor.getId());
        User admin = actor.getId() <= id ? first : second;
        User target = actor.getId() <= id ? second : first;
        if (admin == null || admin.getRole() != Role.ADMIN || admin.getStatus() != UserStatus.ACTIVE) throw forbidden();
        if (actor.getId() == id) throw new ForbiddenException("Bạn không được đổi trạng thái của chính mình.");
        if (status == null) throw invalid();
        if (target == null) throw missing();
        target.setStatus(status);
    }

    private User lock(long id, long actorId) {
        // A cached entity must not supply the state checked after waiting for a row lock.
        em.detach(em.getReference(User.class, id));
        // Actor authorization shares notification FK locks; only the status target needs WRITE.
        return em.find(User.class, id, id == actorId ? LockModeType.PESSIMISTIC_READ : LockModeType.PESSIMISTIC_WRITE);
    }
    private static ForbiddenException forbidden() { return new ForbiddenException("Bạn không có quyền quản lý người dùng."); }
    private static NotFoundException missing() { return new NotFoundException("Không tìm thấy người dùng."); }
    private static ResponseStatusException invalid() { return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Dữ liệu không hợp lệ."); }
}
