package com.carrent.payment;

import com.carrent.payment.provider.ProviderPaymentProperties;
import com.carrent.payment.provider.ProviderPaymentProperties.PayOsChannel;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PayoutChannelResolver {
    private final EntityManager em;
    private final ProviderPaymentProperties providers;
    public PayoutChannelResolver(EntityManager em, ProviderPaymentProperties providers) {
        this.em=em; this.providers=providers;
    }

    @Transactional(readOnly=true)
    public PayOsChannel resolve(Long id) {
        var channel=id==null ? null : em.find(PaymentChannel.class,id);
        if(channel==null) throw new IllegalStateException("Payout source is unavailable");
        em.refresh(channel);
        em.refresh(channel.getUser());
        var configured=providers.payos().get(channel.getCredentialReference());
        if(!channel.isEnabled() || channel.getProvider()!=PaymentProvider.PAYOS
                || channel.getPurpose()!=PaymentChannelPurpose.PAYOUT
                || channel.getUser().getRole()!=channel.getOwnerRole()
                || configured==null || !configured.enabled()) {
            throw new IllegalStateException("Payout source is unavailable");
        }
        return configured;
    }
}
