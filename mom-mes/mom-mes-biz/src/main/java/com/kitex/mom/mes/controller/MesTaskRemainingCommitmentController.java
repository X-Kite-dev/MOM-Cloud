package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.mes.dto.MesTaskCommitmentSourceDTO;
import com.kitex.mom.mes.service.impl.MesTaskCommitmentSourceReader;
import com.kitex.mom.plan.dto.PlnMpsRemainingFieldDTO;
import com.kitex.mom.plan.feign.RemotePlnMpsRemainingFieldService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 现场工单剩余承诺只读入口；先验证可读真实工单，不扩大到全量PLAN权限。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mesTask/remaining-commitment")
public class MesTaskRemainingCommitmentController {
    private final MesTaskCommitmentSourceReader sourceReader;
    private final RemotePlnMpsRemainingFieldService planService;

    @GetMapping("/summary")
    @PreAuthorize("@pms.hasPermission('mesTask:show')")
    @Operation(summary = "真实工单逐来源的正式剩余承诺摘要")
    public R<PlnMpsRemainingFieldDTO.Summary> summary(@RequestParam String taskCode) {
        sourceReader.read(taskCode); // PLAN回调也独立核验，不信任调用端传入来源ID。
        return planService.summary(new PlnMpsRemainingFieldDTO.Query().setTaskCode(taskCode));
    }

    @Inner
    @GetMapping("/internal/sources")
    @PreAuthorize("@pms.hasPermission('mesTask:show')")
    @Operation(summary = "实名反查当前组织真实物理工单的完整计划来源")
    public R<MesTaskCommitmentSourceDTO.Evidence> sources(@RequestParam String taskCode) {
        return R.ok(sourceReader.read(taskCode));
    }
}
