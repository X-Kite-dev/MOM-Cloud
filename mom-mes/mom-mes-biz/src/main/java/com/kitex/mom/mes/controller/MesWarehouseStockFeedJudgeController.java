package com.kitex.mom.mes.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.mes.dto.MesMaterialReturnWithdrawDTO;
import com.kitex.mom.mes.dto.MesWarehouseFeedDTO;
import com.kitex.mom.mes.dto.MesWarehouseFeedJudgeDTO;
import com.kitex.mom.mes.entity.MesWarehouseStockFeedJudgeEntity;
import com.kitex.mom.mes.service.MesWarehouseStockFeedJudgeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 线边余料申请、异岗审核、WMS通知和永久履历入口。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mesWarehouseStockFeedJudge")
@Tag(description = "mesWarehouseStockFeedJudge", name = "线边余料受控退库")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class MesWarehouseStockFeedJudgeController {

    private final MesWarehouseStockFeedJudgeService service;

    @Operation(summary = "受控退料申请分页")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('stockFeedJudge:show')")
    public R<IPage<MesWarehouseStockFeedJudgeEntity>> page(Page<MesWarehouseStockFeedJudgeEntity> page,
                                                           MesWarehouseStockFeedJudgeEntity query) {
        return R.ok(service.pageRequests(page, query));
    }

    @Operation(summary = "预览精确线边库存的退料资格")
    @PostMapping("/preview")
    @PreAuthorize("@pms.hasPermission('stockFeedJudge:submit')")
    public R<MesWarehouseFeedDTO.Evidence> preview(@RequestBody MesWarehouseFeedDTO source) {
        return R.ok(service.preview(source));
    }

    @Operation(summary = "查看退料申请、当前事实和不可变履历")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('stockFeedJudge:show')")
    public R<MesWarehouseFeedDTO.Detail> detail(@PathVariable Long id) {
        return R.ok(service.detail(id));
    }

    @Operation(summary = "提交线边余料退库申请")
    @SysLog("提交线边余料退库申请")
    @PostMapping("/submit")
    @PreAuthorize("@pms.hasPermission('stockFeedJudge:submit')")
    public R<MesWarehouseStockFeedJudgeEntity> submit(@RequestBody MesWarehouseFeedDTO command) {
        return R.ok(service.submitReturnMaterial(command));
    }

    @Operation(summary = "异岗批准或驳回退料申请")
    @SysLog("审核线边余料退库申请")
    @PostMapping("/judge")
    @PreAuthorize("@pms.hasPermission('stockFeedJudge:judge')")
    public R<MesWarehouseStockFeedJudgeEntity> judge(@RequestBody MesWarehouseFeedJudgeDTO command) {
        return R.ok(service.judge(command));
    }

    @Operation(summary = "原申请人撤回待审核退料申请")
    @SysLog("撤回线边余料退库申请")
    @PostMapping("/withdraw")
    @PreAuthorize("@pms.hasPermission('stockFeedJudge:submit')")
    public R<MesWarehouseStockFeedJudgeEntity> withdraw(@RequestBody MesMaterialReturnWithdrawDTO command) {
        return R.ok(service.withdraw(command));
    }
}
