package com.carrent.payment;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit local provisioning; never runs during normal application startup. */
@Component
@Profile({"sepay-setup", "sepay-activate"})
public class LocalSePaySetup implements ApplicationRunner {
    public record Channel(long id, String role, String url) { }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Environment environment;

    public LocalSePaySetup(JdbcTemplate jdbc, TransactionTemplate transactions, Environment environment) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        if (environment.getProperty("carrent.payment.maintenance.enabled", Boolean.class, false)
                || environment.getProperty("carrent.rental.deadlines.enabled", Boolean.class, false)) {
            throw new IllegalStateException("Disable financial schedulers before running sepay-setup");
        }
        boolean activate = environment.matchesProfiles("sepay-activate");
        if (activate && environment.getProperty("CARRENT_SEPAY_WEBHOOK_SECRET", "").isBlank()) {
            throw new IllegalStateException("Fill CARRENT_SEPAY_WEBHOOK_SECRET before activating collection");
        }
        var channels = transactions.execute(status -> {
            var configured = configure();
            if (activate) for (var channel : configured) {
                jdbc.update("UPDATE payment_channels SET enabled=b'1',updated_at=UTC_TIMESTAMP(6) WHERE id=?", channel.id());
            }
            return configured;
        });
        for (var channel : channels) {
            System.out.printf("SePay %s channelId=%d webhook=%s%n", channel.role(), channel.id(), channel.url());
        }
        System.out.println("Use ONE shared-bank webhook: " + channels.getFirst().url());
        System.out.println(activate ? "Validated collection channels are ENABLED. No payout channel was changed."
                : "New channels are DISABLED. Run sepay-activate explicitly when ready for collection.");
    }

    public List<Channel> configure() {
        String bin = required("CARRENT_SEPAY_BANK_BIN", "[0-9]{6}");
        String account = required("CARRENT_SEPAY_ACCOUNT_NUMBER", "[0-9]{1,19}");
        long owner = userId("CARRENT_SEPAY_OWNER_ID");
        long admin = userId("CARRENT_SEPAY_ADMIN_ID");
        if (owner == admin) throw invalidCollectors();
        String base = publicBaseUrl();
        return transactions.execute(status -> {
            var users = jdbc.queryForList("SELECT id,role,status FROM users WHERE id IN (?,?) ORDER BY id FOR UPDATE", owner, admin);
            if (users.size() != 2 || users.stream().anyMatch(user ->
                    !"ACTIVE".equals(user.get("status"))
                    || !(((Number) user.get("id")).longValue() == owner ? "OWNER" : "ADMIN").equals(user.get("role")))) {
                throw invalidCollectors();
            }
            return List.of(channel(owner, "OWNER", "owner-live", bin, account, base),
                    channel(admin, "ADMIN", "admin-live", bin, account, base));
        });
    }

    private Channel channel(long userId, String role, String reference, String bin, String account, String base) {
        var existing = jdbc.queryForList("""
                SELECT id,user_id,owner_role,purpose,bank_bin,account_number
                FROM payment_channels WHERE provider='SEPAY' AND credential_reference=? FOR UPDATE
                """, reference);
        if (existing.isEmpty()) {
            jdbc.update("""
                    INSERT INTO payment_channels(user_id,owner_role,provider,purpose,credential_reference,
                        bank_bin,account_number,enabled,created_at,updated_at)
                    VALUES(?,?,'SEPAY','COLLECT',?,?,?,b'0',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                    """, userId, role, reference, bin, account);
        } else {
            Map<String, Object> row = existing.getFirst();
            if (((Number) row.get("user_id")).longValue() != userId || !role.equals(row.get("owner_role"))
                    || !"COLLECT".equals(row.get("purpose")) || !bin.equals(row.get("bank_bin"))
                    || !account.equals(row.get("account_number"))) {
                throw new IllegalStateException("Existing SePay channel differs from CARRENT_SEPAY settings; no channel was repointed");
            }
        }
        Long id = jdbc.queryForObject("SELECT id FROM payment_channels WHERE provider='SEPAY' AND credential_reference=?", Long.class, reference);
        return new Channel(id, role, base + "/webhooks/sepay/" + id);
    }

    private String required(String key, String pattern) {
        String value = environment.getProperty(key, "").trim();
        if (!value.matches(pattern)) throw new IllegalStateException("Fill a valid " + key + " in your private .env");
        return value;
    }

    private long userId(String key) {
        try {
            return Long.parseLong(required(key, "[1-9][0-9]{0,18}"));
        } catch (NumberFormatException ex) {
            throw new IllegalStateException("Fill a valid " + key + " in your private .env");
        }
    }

    private String publicBaseUrl() {
        String value = environment.getProperty("CARRENT_PUBLIC_BASE_URL", "").trim();
        try {
            URI uri = URI.create(value);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || !(uri.getRawPath().isEmpty() || uri.getRawPath().equals("/"))) {
                throw new IllegalArgumentException();
            }
            return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Fill CARRENT_PUBLIC_BASE_URL with an HTTPS origin, without credentials or path");
        }
    }

    private static IllegalStateException invalidCollectors() {
        return new IllegalStateException("CARRENT_SEPAY_OWNER_ID and CARRENT_SEPAY_ADMIN_ID must select distinct ACTIVE users with matching roles");
    }
}
