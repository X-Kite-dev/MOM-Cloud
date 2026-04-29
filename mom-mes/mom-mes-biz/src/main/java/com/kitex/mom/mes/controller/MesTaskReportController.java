package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.mes.dto.MesTaskReportFeedVO;
import com.kitex.mom.mes.dto.MesTaskReportUnqualifiedVO;
import com.kitex.mom.mes.dto.MesTaskReportVO;
import com.kitex.mom.mes.service.MesTaskReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 生产报工
 *
 * @author kitex
 * @since 2025-05-06
 */
@Tag(description = "mesTaskReport", name = "生产报工-操作明细")
@RestController
@RequestMapping("/mesTask/report")
@RequiredArgsConstructor
public class MesTaskReportController {

    private final MesTaskReportService service;

    @Operation(summary = "生产报工", description = "生产报工")
    @PostMapping("/report")
//    @PreAuthorize("@pms.hasPermission('mesTaskReport:report')")
    public R<Void> report(@RequestBody MesTaskReportVO params) {
        service.report(params);
        return R.ok();
    }

    @Operation(summary = "生产报工-不合格品上报", description = "生产报工-不合格品上报")
    @PostMapping("/unqualified")
//    @PreAuthorize("@pms.hasPermission('mesTaskReport:unqualified')")
    public R<Void> unqualified(@RequestBody MesTaskReportUnqualifiedVO params) {
        service.unqualified(params);
        return R.ok();
    }

    @Operation(summary = "生产投料", description = "生产投料")
    @PostMapping("/feed")
//    @PreAuthorize("@pms.hasPermission('mesTaskReport:feed')")
    public R<Void> feed(@RequestBody MesTaskReportFeedVO params) {
        service.feed(params);
        return R.ok();
    }


}
