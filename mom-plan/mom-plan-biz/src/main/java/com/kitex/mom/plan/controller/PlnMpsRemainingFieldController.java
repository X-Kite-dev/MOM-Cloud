package com.kitex.mom.plan.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.plan.dto.PlnMpsRemainingFieldDTO;
import com.kitex.mom.plan.service.impl.PlnMpsRemainingFieldReader;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** MES实名现场查询专用入口；精确任务来源反查，不授予全量计划查询。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/plnMpsRemainingField")
public class PlnMpsRemainingFieldController {
    private final PlnMpsRemainingFieldReader reader;

    @Inner
    @PostMapping("/internal/summary")
    @PreAuthorize("@pms.hasPermission('mesTask:show')")
    @Operation(summary = "按真实MES工单来源读取有限正式剩余承诺摘要")
    public R<PlnMpsRemainingFieldDTO.Summary> summary(@RequestBody PlnMpsRemainingFieldDTO.Query query) {
        return R.ok(reader.read(query));
    }
}
