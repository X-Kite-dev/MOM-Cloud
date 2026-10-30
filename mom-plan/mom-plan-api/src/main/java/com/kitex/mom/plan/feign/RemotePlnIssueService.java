package com.kitex.mom.plan.feign;

import com.kitex.common.core.util.R;
import com.kitex.common.feign.annotation.InternalCall;
import com.kitex.mom.Constants;
import com.kitex.mom.oms.dto.OmsOrderLifeCycleDTO;
import com.kitex.mom.plan.entity.PlnIssueEntity;
import com.kitex.mom.plan.entity.PlnIssueFeedbackEntity;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@FeignClient(contextId = "remotePlnIssueService", value = Constants.Name)
public interface RemotePlnIssueService {

    /**
     * 生产模块调用
     * 生产排产回调feign
     *
     * @param omsOrderLifeCycleDtoList 订单生命周期DTO
     * @return R
     */
    @InternalCall
    @PutMapping("/plnIssue/edit")
    R<Boolean> edit(@RequestBody List<OmsOrderLifeCycleDTO> omsOrderLifeCycleDtoList);

    /**
     * 根据生产计划下达号查询生产排产结果
     *
     * @param issueNo 生产计划下达号
     * @return R
     */
    @GetMapping("/plnIssue/getIssueByNo")
    R<PlnIssueEntity> getIssueByNo(@RequestParam("issueNo") String issueNo, @RequestParam("productCode") String productCode);

    /**
     * 新增反馈信息
     *
     * @return R
     */
    @PostMapping("/plnIssue/feedback")
    R<Boolean> feedback(@RequestBody List<PlnIssueFeedbackEntity> plnFeedbackEntities);
}
