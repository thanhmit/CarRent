package com.carrent.payment;

import com.carrent.payment.provider.RefundDestinationVerifier;
import com.carrent.payment.provider.RefundLookupProperties;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Explicit recipient lookup only; payout channels remain disabled and no refund is created. */
@Component
@Profile("refund-lookup-check")
public class LocalRefundLookupCheck implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final Environment environment;
    private final RefundLookupProperties lookup;
    private final RefundDestinationVerifier verifier;

    public LocalRefundLookupCheck(JdbcTemplate jdbc,Environment environment,RefundLookupProperties lookup,RefundDestinationVerifier verifier) {
        this.jdbc=jdbc;this.environment=environment;this.lookup=lookup;this.verifier=verifier;
    }

    @Override public void run(ApplicationArguments arguments) {
        if (environment.getProperty("carrent.payment.maintenance.enabled",Boolean.class,false)
                || environment.getProperty("carrent.rental.deadlines.enabled",Boolean.class,false)) {
            throw new IllegalStateException("Disable financial schedulers before checking recipient lookup");
        }
        if (!lookup.enabled()) throw new IllegalStateException("Recipient lookup requires confirmed entitlement and explicit configuration");
        String channel=value("CHANNEL_ID"),bank=value("BANK_BIN"),account=value("ACCOUNT_NUMBER"),name=value("EXPECTED_NAME");
        long id;
        try {
            if (!channel.matches("[1-9][0-9]{0,18}")) throw invalid();
            id=Long.parseLong(channel);
        }catch(NumberFormatException ex){throw invalid();}
        if (!bank.matches("[0-9]{6}") || !account.matches("[0-9]{6,19}") || name.isBlank()
                || name.length()>150 || name.codePoints().anyMatch(Character::isISOControl)) throw invalid();
        var sources=jdbc.queryForList("SELECT provider,purpose FROM payment_channels WHERE id=?",id);
        if (sources.size()!=1 || !"PAYOS".equals(sources.getFirst().get("provider"))
                || !"PAYOUT".equals(sources.getFirst().get("purpose"))) throw invalid();
        try {
            var destination=verifier.verify(id,bank,account);
            if (destination==null || !bank.equals(destination.bankBin()) || !account.equals(destination.accountNumber())
                    || !name.equals(destination.accountName())) throw invalid();
        }catch(RuntimeException ex){throw new IllegalStateException("Recipient lookup check failed; review private inputs and provider capability");}
        System.out.println("Recipient lookup VERIFIED. No payout was created or activated.");
    }

    private String value(String suffix){return environment.getProperty("CARRENT_REFUND_LOOKUP_CHECK_"+suffix,"");}
    private static IllegalStateException invalid(){return new IllegalStateException("Recipient lookup check inputs or payout source are unavailable");}
}
