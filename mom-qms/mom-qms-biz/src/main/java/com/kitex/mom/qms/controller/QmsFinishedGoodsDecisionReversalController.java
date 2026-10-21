package com.kitex.mom.qms.controller;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.qms.dto.QmsFinishedGoodsDecisionReversalDTO;
import com.kitex.mom.qms.dto.QmsFinishedGoodsDecisionReversalExecutionEvent;
import com.kitex.mom.qms.entity.QmsFinishedGoodsDecisionReversalEntity;
import com.kitex.mom.qms.service.QmsFinishedGoodsDecisionReversalService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 成品入库质量判定逆向授权工作台及WMS内部消费接口。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/finishedGoodsDecisionReversal")
public class QmsFinishedGoodsDecisionReversalController {
    private final QmsFinishedGoodsDecisionReversalService service;

    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('finishedGoodsDecisionReversal:show')")
    public R<IPage<QmsFinishedGoodsDecisionReversalEntity>> page(
            Page<QmsFinishedGoodsDecisionReversalEntity> page,
            @RequestParam(required = false) String reversalNo,
            @RequestParam(required = false) Long wmsNoticeId,
            @RequestParam(required = false) String status) {
        return R.ok(service.page(page, new LambdaQueryWrapper<QmsFinishedGoodsDecisionReversalEntity>()
                .eq(QmsFinishedGoodsDecisionReversalEntity::getFactoryCode, requireFactory())
                .like(StrUtil.isNotBlank(reversalNo),
                        QmsFinishedGoodsDecisionReversalEntity::getReversalNo, reversalNo)
                .eq(wmsNoticeId != null,
                        QmsFinishedGoodsDecisionReversalEntity::getWmsNoticeId, wmsNoticeId)
                .eq(StrUtil.isNotBlank(status),
                        QmsFinishedGoodsDecisionReversalEntity::getStatus, status)
                .orderByDesc(QmsFinishedGoodsDecisionReversalEntity::getAppliedTime)
                .orderByDesc(QmsFinishedGoodsDecisionReversalEntity::getId)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('finishedGoodsDecisionReversal:show')")
    public R<QmsFinishedGoodsDecisionReversalDTO.Detail> detail(@PathVariable Long id) {
        return R.ok(service.detail(id));
    }

    @PostMapping("/apply")
    @SysLog("发起成品质量判定逆向申请")
    @PreAuthorize("@pms.hasPermission('finishedGoodsDecisionReversal:apply')")
    public R<QmsFinishedGoodsDecisionReversalEntity> apply(
            @Valid @RequestBody QmsFinishedGoodsDecisionReversalDTO.ApplyCommand command) {
        return R.ok(service.apply(command));
    }

    @PostMapping("/review")
    @SysLog("复核成品质量判定逆向申请")
    @PreAuthorize("@pms.hasPermission('finishedGoodsDecisionReversal:review')")
    public R<QmsFinishedGoodsDecisionReversalEntity> review(
            @Valid @RequestBody QmsFinishedGoodsDecisionReversalDTO.ReviewCommand command) {
        return R.ok(service.review(command));
    }

    @PostMapping("/{id}/withdraw")
    @SysLog("撤回成品质量判定逆向申请")
    @PreAuthorize("@pms.hasPermission('finishedGoodsDecisionReversal:apply')")
    public R<QmsFinishedGoodsDecisionReversalEntity> withdraw(@PathVariable Long id,
                                                              @Valid @RequestBody QmsFinishedGoodsDecisionReversalDTO.WithdrawCommand command) {
        return R.ok(service.withdraw(id, command));
    }

    @Inner
    @PostMapping("/internal/authorization")
    public R<QmsFinishedGoodsDecisionReversalDTO.Authorization> authorization(
            @Valid @RequestBody QmsFinishedGoodsDecisionReversalDTO.AuthorizationQuery query) {
        return R.ok(service.authorization(query));
    }

    @Inner
    @PostMapping("/internal/consume")
    public R<QmsFinishedGoodsDecisionReversalDTO.Authorization> consume(
            @Valid @RequestBody QmsFinishedGoodsDecisionReversalDTO.ConsumeCommand command) {
        return R.ok(service.consume(command));
    }

    @Inner
    @PostMapping("/internal/execution")
    public R<QmsFinishedGoodsDecisionReversalExecutionEvent.Receipt> receiveExecution(
            @Valid @RequestBody QmsFinishedGoodsDecisionReversalExecutionEvent event) {
        return R.ok(service.receiveExecution(event));
    }

    @Inner
    @GetMapping("/internal/executionReceipt/{eventId}")
    public R<QmsFinishedGoodsDecisionReversalExecutionEvent.Receipt> executionReceipt(
            @PathVariable String eventId) {
        return R.ok(service.executionReceipt(eventId));
    }

    private String requireFactory() {
        String factory = FactoryContextHolder.getFactoryCode();
        if (StrUtil.isBlank(factory)) throw new CheckedException("请先在请求头factory-code中选择实际工厂");
        return factory;
    }
}
