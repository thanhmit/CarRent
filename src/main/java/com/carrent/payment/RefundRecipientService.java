package com.carrent.payment;

import com.carrent.common.exception.BusinessException;
import com.carrent.security.CustomUserPrincipal;
import jakarta.validation.Validator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(propagation = Propagation.NEVER)
public class RefundRecipientService {
    private final RefundRecipientTransactions transactions;
    private final RefundBankDirectory banks;
    private final Validator validator;
    private final RefundAccountRepository accounts;

    public RefundRecipientService(RefundRecipientTransactions transactions, RefundBankDirectory banks, Validator validator,
            RefundAccountRepository accounts) {
        this.transactions = transactions; this.banks = banks; this.validator = validator;
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

    public void submit(Long id, RefundRecipientForm form, CustomUserPrincipal actor) {
        var check = transactions.prepare(id, actor);
        if (form == null || !validator.validate(form).isEmpty()) throw new BusinessException("Thông tin tài khoản nhận không hợp lệ.");
        if (!banks.supports(form.getBankBin())) throw new BusinessException("Ngân hàng nhận chưa được hỗ trợ.");
        transactions.confirm(check, new RefundDestination(form.getBankBin(), form.getAccountNumber()), form.isSaveForLater(), actor);
    }
}
