package com.hyoguoo.paymentplatform.payment.presentation.port;

import com.hyoguoo.paymentplatform.payment.application.dto.request.PaymentConfirmCommand;
import com.hyoguoo.paymentplatform.payment.application.dto.response.PaymentConfirmAsyncResult;

public interface PaymentConfirmService {

    PaymentConfirmAsyncResult confirm(PaymentConfirmCommand paymentConfirmCommand);
}
