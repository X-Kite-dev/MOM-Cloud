package com.kitex.mom.plan.feign;

import com.kitex.common.core.util.R;
import com.kitex.mom.Constants;
import com.kitex.mom.plan.entity.PlnIssueMonthEntity;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@FeignClient(contextId = "remotePlnIssueMonthService", value = Constants.Name)
public interface RemotePlnIssueMonthService {

    /**
     * 采购模块调用
     * 采购排产回调feign
     *
     * @param plnIssueMonthEntityList 采购计划下达列表
     * @return R
     */
    @PutMapping("/plnIssueMonth/edit")
    R<Boolean> edit(@RequestBody List<PlnIssueMonthEntity> plnIssueMonthEntityList);

    /**
     * 根据采购计划下达号查询采购排产结果
     *
     * @param issueMonthNo 采购计划下达号
     * @param productCode  物料编码
     * @return R
     */
    @GetMapping("/plnIssueMonth/getIssueMonthByNo")
    R<PlnIssueMonthEntity> getIssueMonthByNo(@RequestParam("issueMonthNo") String issueMonthNo, @RequestParam("productCode") String productCode);
}
