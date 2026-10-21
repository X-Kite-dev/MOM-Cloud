package com.kitex.mom.plan.service;

import com.kitex.mom.plan.dto.PlanQualityLocalReplanDTO;
import com.kitex.mom.plan.entity.PlnQualitySupplementWithdrawalEntity;

public interface PlnQualitySupplementWithdrawalService {
    PlnQualitySupplementWithdrawalEntity submit(PlanQualityLocalReplanDTO.SupplementWithdrawalSubmitCommand command);

    PlnQualitySupplementWithdrawalEntity review(PlanQualityLocalReplanDTO.SupplementWithdrawalReviewCommand command);

    PlnQualitySupplementWithdrawalEntity retry(PlanQualityLocalReplanDTO.SupplementWithdrawalRetryCommand command);
}
