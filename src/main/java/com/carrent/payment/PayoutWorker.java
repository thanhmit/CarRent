package com.carrent.payment;

import com.carrent.payment.provider.PayOsPayoutClient;
import com.carrent.payment.provider.PayoutResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(propagation=Propagation.NEVER)
public class PayoutWorker {
    private final PayoutTransactions transactions;
    private final PayOsPayoutClient provider;
    public PayoutWorker(PayoutTransactions transactions,PayOsPayoutClient provider) {
        this.transactions=transactions; this.provider=provider;
    }

    public void dispatch(Long refundId) {
        var claim=transactions.prepareDispatch(refundId);
        if(claim==null || !transactions.beginSend(claim)) return;
        PayoutResult result;
        try { result=provider.create(claim.channelId(),claim.command()); }
        catch(RuntimeException ex) { result=null; }
        transactions.finish(claim,result);
    }

    public void reconcile(Long attemptId) {
        var claim=transactions.prepareReconcile(attemptId);
        if(claim==null) return;
        PayoutResult result;
        try {
            result=claim.providerPayoutId()==null ? provider.findByReference(claim.channelId(),claim.command().referenceId())
                    : provider.get(claim.channelId(),claim.providerPayoutId());
        } catch(RuntimeException ex) { result=null; }
        transactions.finish(claim,result);
    }
}
