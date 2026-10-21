package com.kitex.mom.qms.api.feign;

import com.kitex.common.core.util.R;
import com.kitex.common.feign.annotation.InternalCall;
import com.kitex.mom.qms.Constants;
import com.kitex.mom.qms.dto.QmsFinishedGoodsDecisionReversalDTO;
import com.kitex.mom.qms.dto.QmsFinishedGoodsDecisionReversalExecutionEvent;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * WMS对成品质量判定逆向授权的受控查询和一次性消费接口。
 */
@FeignClient(contextId = "remoteQmsFinishedGoodsDecisionReversalService", value = Constants.Name)
public interface RemoteQmsFinishedGoodsDecisionReversalService {

    @InternalCall(userTokenRequired = true)
    @PostMapping("/finishedGoodsDecisionReversal/internal/authorization")
    R<QmsFinishedGoodsDecisionReversalDTO.Authorization> authorization(
            @RequestBody QmsFinishedGoodsDecisionReversalDTO.AuthorizationQuery query);

    @InternalCall(userTokenRequired = true)
    @PostMapping("/finishedGoodsDecisionReversal/internal/consume")
    R<QmsFinishedGoodsDecisionReversalDTO.Authorization> consume(
            @RequestBody QmsFinishedGoodsDecisionReversalDTO.ConsumeCommand command);

    @InternalCall
    @PostMapping("/finishedGoodsDecisionReversal/internal/execution")
    R<QmsFinishedGoodsDecisionReversalExecutionEvent.Receipt> receiveExecution(
            @RequestBody QmsFinishedGoodsDecisionReversalExecutionEvent event);

    @InternalCall
    @GetMapping("/finishedGoodsDecisionReversal/internal/executionReceipt/{eventId}")
    R<QmsFinishedGoodsDecisionReversalExecutionEvent.Receipt> executionReceipt(
            @PathVariable("eventId") String eventId);
}
