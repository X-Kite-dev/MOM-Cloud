package com.kitex.mom.pms.service;

import com.kitex.mom.pms.dto.PmsOmsSupplyWithdrawalDTO;
import com.kitex.mom.pms.dto.PmsOrderChangeDTO;
import com.kitex.mom.pms.entity.PmsOrderChangeRequestEntity;

import java.util.List;

public interface PmsOrderChangeService {
    PmsOrderChangeDTO.Preview previewCancelRemaining(Long orderId);

    PmsOrderChangeDTO.Preview previewCancelLines(PmsOrderChangeDTO.LineCancelPreviewCommand command);

    PmsOrderChangeDTO.Preview previewAmendment(PmsOrderChangeDTO.AmendPreviewCommand command);

    PmsOrderChangeDTO.Detail submitCancelRemaining(PmsOrderChangeDTO.SubmitCommand command);

    PmsOrderChangeDTO.Detail submitCancelLines(PmsOrderChangeDTO.LineCancelSubmitCommand command);

    PmsOrderChangeDTO.Detail submitAmendment(PmsOrderChangeDTO.AmendSubmitCommand command);

    PmsOrderChangeDTO.Detail review(PmsOrderChangeDTO.ReviewCommand command);

    PmsOrderChangeDTO.Detail detail(Long requestId);

    List<PmsOrderChangeRequestEntity> listByOrder(Long orderId);

    PmsOrderChangeDTO.Preview previewOmsLineWithdrawal(PmsOmsSupplyWithdrawalDTO.PreviewCommand command);

    PmsOmsSupplyWithdrawalDTO.Receipt submitOmsLineWithdrawal(PmsOmsSupplyWithdrawalDTO.SubmitCommand command);

    PmsOmsSupplyWithdrawalDTO.Receipt getOmsLineWithdrawalReceipt(String requestId);
}
