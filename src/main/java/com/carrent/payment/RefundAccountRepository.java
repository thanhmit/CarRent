package com.carrent.payment;

import com.carrent.payment.provider.RefundDestinationVerifier.Destination;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class RefundAccountRepository {
    private final JdbcTemplate jdbc;
    public RefundAccountRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(readOnly = true)
    public Optional<Destination> findByUserId(Long id) {
        return jdbc.query("SELECT bank_bin,account_number,account_name FROM refund_recipient_accounts WHERE user_id=?",
                (rs, row) -> new Destination(rs.getString(1), rs.getString(2), rs.getString(3)), id).stream().findFirst();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void save(Long userId, Destination destination, Instant verifiedAt) {
        var at = LocalDateTime.ofInstant(verifiedAt, ZoneOffset.UTC);
        // An atomic upsert serializes concurrent opt-ins without touching existing refund snapshots.
        jdbc.update("""
                INSERT INTO refund_recipient_accounts(user_id,bank_bin,account_number,account_name,verified_at)
                VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE bank_bin=?,account_number=?,account_name=?,verified_at=?
                """, userId, destination.bankBin(), destination.accountNumber(), destination.accountName(), at,
                destination.bankBin(), destination.accountNumber(), destination.accountName(), at);
    }
}
