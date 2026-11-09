package com.carrent.payment;

import com.carrent.payment.provider.HttpPayOsPayoutClient;
import com.carrent.payment.provider.ProviderPaymentProperties;
import com.carrent.payment.provider.ProviderPaymentProperties.PayOsChannel;
import java.util.List;
import java.util.Map;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit GET-only diagnostics and disabled local payout-channel provisioning. */
@Component
@Profile({"payos-check", "payos-setup"})
public class LocalPayOsSetup implements ApplicationRunner {
    public record Channel(long id, String role) { }
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Environment environment;
    private final ProviderPaymentProperties providers;

    public LocalPayOsSetup(JdbcTemplate jdbc, TransactionTemplate transactions, Environment environment,
            ProviderPaymentProperties providers) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.environment = environment;
        this.providers = providers;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        requireSchedulersOff();
        var owner = credentials("owner-payout");
        var admin = credentials("admin-payout");
        var client = new HttpPayOsPayoutClient(id -> id == 1L ? owner : admin);
        var ownerCheck = client.checkConnection(1L);
        var adminCheck = sameCredentials(owner, admin) ? ownerCheck : client.checkConnection(2L);
        System.out.printf("payOS OWNER read=VERIFIED source=%s%n", ownerCheck.funded() ? "FUNDED" : "ZERO");
        System.out.printf("payOS ADMIN read=VERIFIED source=%s%n", adminCheck.funded() ? "FUNDED" : "ZERO");
        if (environment.matchesProfiles("payos-setup")) {
            for (var channel : configure()) {
                System.out.printf("payOS %s channelId=%d%n", channel.role(), channel.id());
            }
            System.out.println("New payout mappings are DISABLED; existing activation states were preserved.");
        }
        System.out.println("GET checks completed. No payout was created; recipient lookup and payout capability remain separate gates.");
    }

    public List<Channel> configure() {
        requireSchedulersOff();
        credentials("owner-payout");
        credentials("admin-payout");
        long owner = collector("OWNER");
        long admin = collector("ADMIN");
        if (owner == admin) throw invalidCollectors();
        return transactions.execute(status -> {
            var users = jdbc.queryForList("SELECT id,role,status FROM users WHERE id IN (?,?) ORDER BY id FOR UPDATE", owner, admin);
            if (users.size() != 2 || users.stream().anyMatch(user -> !"ACTIVE".equals(user.get("status"))
                    || !(((Number) user.get("id")).longValue() == owner ? "OWNER" : "ADMIN").equals(user.get("role")))) {
                throw invalidCollectors();
            }
            return List.of(channel(owner, "OWNER", "owner-payout"), channel(admin, "ADMIN", "admin-payout"));
        });
    }

    private Channel channel(long userId, String role, String reference) {
        var existing = jdbc.queryForList("""
                SELECT id,user_id,owner_role,purpose FROM payment_channels
                WHERE provider='PAYOS' AND credential_reference=? FOR UPDATE
                """, reference);
        if (existing.isEmpty()) {
            jdbc.update("""
                    INSERT INTO payment_channels(user_id,owner_role,provider,purpose,credential_reference,enabled,created_at,updated_at)
                    VALUES(?,?,'PAYOS','PAYOUT',?,b'0',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                    """, userId, role, reference);
        } else {
            Map<String, Object> row = existing.getFirst();
            if (((Number) row.get("user_id")).longValue() != userId || !role.equals(row.get("owner_role"))
                    || !"PAYOUT".equals(row.get("purpose"))) {
                throw new IllegalStateException("Existing payOS mapping differs; no payout channel was repointed");
            }
        }
        Long id = jdbc.queryForObject("SELECT id FROM payment_channels WHERE provider='PAYOS' AND credential_reference=?", Long.class, reference);
        return new Channel(id, role);
    }

    private PayOsChannel credentials(String reference) {
        var configured = providers.payos().get(reference);
        if (configured == null || configured.clientId() == null || configured.clientId().isBlank()
                || configured.apiKey() == null || configured.apiKey().isBlank()
                || configured.checksumKey() == null || configured.checksumKey().isBlank()) {
            throw new IllegalStateException("Configure all three payOS credentials for " + reference);
        }
        return configured;
    }

    private long collector(String role) {
        String value = environment.getProperty("CARRENT_PAYOS_" + role + "_ID", "");
        if (value.isBlank()) value = environment.getProperty("CARRENT_SEPAY_" + role + "_ID", "");
        try {
            if (!value.matches("[1-9][0-9]{0,18}")) throw invalidCollectors();
            return Long.parseLong(value);
        } catch (NumberFormatException ex) { throw invalidCollectors(); }
    }

    private void requireSchedulersOff() {
        if (environment.getProperty("carrent.payment.maintenance.enabled", Boolean.class, false)
                || environment.getProperty("carrent.rental.deadlines.enabled", Boolean.class, false)) {
            throw new IllegalStateException("Disable financial schedulers before running payOS diagnostics/setup");
        }
    }

    private static boolean sameCredentials(PayOsChannel first, PayOsChannel second) {
        return first.clientId().equals(second.clientId()) && first.apiKey().equals(second.apiKey())
                && first.checksumKey().equals(second.checksumKey());
    }

    private static IllegalStateException invalidCollectors() {
        return new IllegalStateException("Configure distinct ACTIVE OWNER/ADMIN IDs using CARRENT_PAYOS_OWNER_ID and CARRENT_PAYOS_ADMIN_ID");
    }
}
