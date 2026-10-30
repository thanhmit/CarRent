package com.carrent.payment;

import com.carrent.common.exception.BusinessException;
import com.carrent.payment.provider.RefundDestinationVerifier;
import com.carrent.payment.provider.RefundDestinationVerifier.Destination;
import com.carrent.security.CustomUserPrincipal;
import jakarta.validation.Validator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(propagation = Propagation.NEVER)
public class RefundRecipientService {
    private final RefundRecipientTransactions transactions;
    private final RefundDestinationVerifier verifier;
    private final Validator validator;
    private final RefundAccountRepository accounts;

    public RefundRecipientService(RefundRecipientTransactions transactions, RefundDestinationVerifier verifier, Validator validator,
            RefundAccountRepository accounts) {
        this.transactions = transactions; this.verifier = verifier; this.validator = validator;
        this.accounts = accounts;
    }

    public RefundView view(Long id, CustomUserPrincipal actor) { return transactions.view(id, actor); }

    public RefundRecipientForm form(Long id, CustomUserPrincipal actor) {
        var refund = transactions.view(id, actor);
        var form = new RefundRecipientForm();
        form.setBankBin(refund.bankBin()); form.setAccountNumber(refund.accountNumber());
        if (refund.accountNumber() == null && refund.editable()) {
            accounts.findByUserId(actor.getId()).ifPresent(saved -> {
                form.setBankBin(saved.bankBin()); form.setAccountNumber(saved.accountNumber());
            });
        }
        return form;
    }

    public Destination preview(Long id, RefundRecipientForm form, CustomUserPrincipal actor) {
        var check = transactions.prepare(id, actor);
        return verified(check, form);
    }

    public void submit(Long id, RefundRecipientForm form, CustomUserPrincipal actor) {
        var check = transactions.prepare(id, actor);
        var destination = verified(check, form);
        if (!destination.accountName().equals(form.getConfirmedAccountName())) {
            throw new BusinessException("Vui lòng kiểm tra tên tài khoản nhận và xác nhận lại.");
        }
        transactions.confirm(check, destination, form.isSaveForLater(), actor);
    }

    private Destination verified(RefundRecipientTransactions.Check check, RefundRecipientForm form) {
        if (form == null || !validator.validate(form).isEmpty()) throw new BusinessException("Thông tin tài khoản nhận không hợp lệ.");
        Destination result;
        try {
            result = verifier.verify(check.channelId(), form.getBankBin(), form.getAccountNumber());
        } catch (RuntimeException ex) {
            throw new BusinessException("Chưa xác minh được tài khoản nhận. Vui lòng thử lại sau.");
        }
        if (result == null || !form.getBankBin().equals(result.bankBin())
                || !form.getAccountNumber().equals(result.accountNumber()) || result.accountName() == null
                || result.accountName().isBlank() || result.accountName().length() > 150
                || result.accountName().codePoints().anyMatch(Character::isISOControl)) {
            throw new BusinessException("Không xác minh được đúng tài khoản nhận hoàn tiền.");
        }
        return result;
    }
}
