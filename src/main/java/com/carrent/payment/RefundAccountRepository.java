package com.carrent.payment;

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
    public Optional<RefundDestination> findByUserId(Long id) {
        return jdbc.query("SELECT bank_bin,account_number FROM refund_recipient_accounts WHERE user_id=?",
                (rs, row) -> new RefundDestination(rs.getString(1), rs.getString(2)), id).stream().findFirst();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void save(Long userId, RefundDestination destination, Instant confirmedAt) {
        var at = LocalDateTime.ofInstant(confirmedAt, ZoneOffset.UTC);
        // Legacy verified_at now records recipient confirmation, without lookup or an invented name.
        jdbc.update("""
                INSERT INTO refund_recipient_accounts(user_id,bank_bin,account_number,account_name,verified_at)
                VALUES(?,?,?,NULL,?) ON DUPLICATE KEY UPDATE bank_bin=?,account_number=?,account_name=NULL,verified_at=?
                """, userId, destination.bankBin(), destination.accountNumber(), at,
                destination.bankBin(), destination.accountNumber(), at);
    }
}
