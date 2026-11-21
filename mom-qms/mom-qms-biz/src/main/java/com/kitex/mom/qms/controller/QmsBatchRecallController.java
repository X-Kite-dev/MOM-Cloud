package com.kitex.mom.qms.controller;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.mom.qms.agent.service.QmsAgentFactoryAccessService;
import com.kitex.mom.qms.dto.QmsBatchRecallDTO;
import com.kitex.mom.qms.entity.QmsBatchRecallEntity;
import com.kitex.mom.qms.service.QmsBatchRecallService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 批次召回候选治理工作台接口。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/qmsBatchRecall")
public class QmsBatchRecallController {
    private final QmsBatchRecallService service;
    private final QmsAgentFactoryAccessService factoryAccess;

    /**
     * 台账保留模糊查询；批次调查下钻按原物料和批次精确分页，避免相近批号混入办理范围。
     */
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('batchRecall:show')")
    public R<IPage<QmsBatchRecallEntity>> page(Page<QmsBatchRecallEntity> page,
                                               @RequestParam(required = false) String recallNo,
                                               @RequestParam(required = false) String materialCode,
                                               @RequestParam(required = false) String batchNo,
                                               @RequestParam(required = false) String status,
                                               @RequestParam(defaultValue = "false") boolean exactBatch) {
        String factory = factoryAccess.requireRead(requireFactory());
        if (exactBatch && (StrUtil.isBlank(materialCode) || StrUtil.isBlank(batchNo))) {
            throw new CheckedException("精确查询必须同时提供物料编码和批次号");
        }
        LambdaQueryWrapper<QmsBatchRecallEntity> wrapper = new LambdaQueryWrapper<QmsBatchRecallEntity>()
                .eq(QmsBatchRecallEntity::getFactoryCode, factory)
                .like(StrUtil.isNotBlank(recallNo), QmsBatchRecallEntity::getRecallNo, recallNo)
                .eq(exactBatch, QmsBatchRecallEntity::getMaterialCode, materialCode)
                .eq(exactBatch, QmsBatchRecallEntity::getBatchNo, batchNo)
                .like(!exactBatch && StrUtil.isNotBlank(materialCode), QmsBatchRecallEntity::getMaterialCode, materialCode)
                .like(!exactBatch && StrUtil.isNotBlank(batchNo), QmsBatchRecallEntity::getBatchNo, batchNo)
                .eq(StrUtil.isNotBlank(status), QmsBatchRecallEntity::getStatus, status)
                .orderByDesc(QmsBatchRecallEntity::getCreateTime)
                .orderByDesc(QmsBatchRecallEntity::getId);
        return R.ok(service.page(page, wrapper));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('batchRecall:show')")
    public R<QmsBatchRecallDTO.Detail> detail(@PathVariable Long id) {
        return R.ok(service.detail(id));
    }

    @PostMapping("/propose")
    @PreAuthorize("@pms.hasPermission('batchRecall:propose')")
    public R<QmsBatchRecallEntity> propose(@Valid @RequestBody QmsBatchRecallDTO.ProposeCommand command) {
        return R.ok(service.propose(command));
    }

    @PostMapping("/review")
    @PreAuthorize("@pms.hasPermission('batchRecall:review')")
    public R<QmsBatchRecallEntity> review(@Valid @RequestBody QmsBatchRecallDTO.ReviewCommand command) {
        return R.ok(service.review(command));
    }

    @PostMapping("/execute")
    @PreAuthorize("@pms.hasPermission('batchRecall:execute')")
    public R<QmsBatchRecallEntity> execute(@Valid @RequestBody QmsBatchRecallDTO.ExecuteCommand command) {
        return R.ok(service.execute(command));
    }

    @PostMapping("/notification")
    @PreAuthorize("@pms.hasPermission('batchRecall:notify')")
    public R<QmsBatchRecallEntity> notification(@Valid @RequestBody QmsBatchRecallDTO.NotificationCommand command) {
        return R.ok(service.recordNotification(command));
    }

    @PostMapping("/verify")
    @PreAuthorize("@pms.hasPermission('batchRecall:verify')")
    public R<QmsBatchRecallEntity> verify(@Valid @RequestBody QmsBatchRecallDTO.VerifyCommand command) {
        return R.ok(service.verify(command));
    }

    private String requireFactory() {
        String factory = FactoryContextHolder.getFactoryCode();
        if (StrUtil.isBlank(factory)) throw new CheckedException("请先在请求头factory-code中选择实际工厂");
        return factory;
    }
}
