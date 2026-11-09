package com.carrent.payment;

import com.carrent.payment.provider.SePayWebhookVerifier;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Clock;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SePayWebhookService {
    private final EntityManager em;
    private final SePayWebhookVerifier verifier;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public SePayWebhookService(EntityManager em, SePayWebhookVerifier verifier, JdbcTemplate jdbc, Clock clock) {
        this.em = em; this.verifier = verifier; this.jdbc = jdbc; this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public void receive(Long channelId, HttpHeaders headers, byte[] rawBody) {
        var channel = em.find(PaymentChannel.class, channelId);
        if (channel != null) em.refresh(channel);
        if (channel == null || !channel.isEnabled() || channel.getProvider() != PaymentProvider.SEPAY
                || channel.getPurpose() != PaymentChannelPurpose.COLLECT
                || channel.getOwnerRole() != channel.getUser().getRole()
                || !verifier.verify(channel.getCredentialReference(), headers, rawBody)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Webhook authentication failed");
        }
        var payment = SePayIncomingPayment.parse(rawBody);
        if (!payment.accountNumber().equals(channel.getAccountNumber())
                || !verifier.acceptsGateway(channel.getCredentialReference(), payment.gateway())
                || payment.creditedAt().isAfter(clock.instant().plusSeconds(300))) {
            throw new IllegalArgumentException("Invalid SePay channel payload");
        }
        // Lock the physical receiving account before cross-channel deduplication or routing.
        var receiving = em.createQuery("""
                select c from PaymentChannel c where c.provider = :provider and c.purpose = :purpose
                    and c.bankBin = :bank and c.accountNumber = :account order by c.id
                """, PaymentChannel.class)
                .setParameter("provider", PaymentProvider.SEPAY).setParameter("purpose", PaymentChannelPurpose.COLLECT)
                .setParameter("bank", channel.getBankBin()).setParameter("account", channel.getAccountNumber())
                .setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
        for (var current : receiving) em.refresh(current);
        if (!channel.isEnabled()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Webhook authentication failed");
        var ids = receiving.stream().map(PaymentChannel::getId).toList();
        long duplicates = em.createQuery("""
                select count(i) from WebhookInbox i where i.channel.id in :channels
                    and i.providerTransactionId = :transaction
                """, Long.class).setParameter("channels", ids).setParameter("transaction", payment.transactionId()).getSingleResult();
        if (duplicates != 0) return;
        if (payment.code() != null) {
            var matches = em.createQuery("select i from PaymentIntent i where i.code = :code", PaymentIntent.class)
                    .setParameter("code", payment.code()).getResultList();
            if (!matches.isEmpty()) {
                var target = matches.getFirst().getChannel();
                if (ids.contains(target.getId())
                        && verifier.sharesWebhookSecret(channel.getCredentialReference(), target.getCredentialReference())
                        && verifier.acceptsGateway(target.getCredentialReference(), payment.gateway())) {
                    channelId = target.getId();
                }
            }
        }
        // Atomic MySQL deduplication preserves the first payload even across concurrent deliveries.
        jdbc.update("""
                INSERT INTO webhook_inbox(channel_id,provider_transaction_id,raw_payload,received_at,status,retry_count)
                VALUES(?,?,?,?,'RECEIVED',0) ON DUPLICATE KEY UPDATE id=id
                """, channelId, payment.transactionId(), rawBody,
                java.time.LocalDateTime.ofInstant(clock.instant(), java.time.ZoneOffset.UTC));
    }
}
