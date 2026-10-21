package com.kitex.mom.plan.controller;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.mom.oms.dto.OmsDeliveryPromiseDTO;
import com.kitex.mom.oms.entity.OmsDeliveryPromiseEntity;
import com.kitex.mom.plan.service.OmsDeliveryPromiseService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * OMS交期答复岗位协同工作台。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/omsDeliveryPromise")
public class OmsDeliveryPromiseController {
    private final OmsDeliveryPromiseService service;

    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('oms_deliveryPromise_view')")
    public R<IPage<OmsDeliveryPromiseEntity>> page(Page<OmsDeliveryPromiseEntity> page,
                                                   @RequestParam(required = false) String promiseNo,
                                                   @RequestParam(required = false) String orderCode,
                                                   @RequestParam(required = false) String customerName,
                                                   @RequestParam(required = false) String status) {
        LambdaQueryWrapper<OmsDeliveryPromiseEntity> wrapper = new LambdaQueryWrapper<OmsDeliveryPromiseEntity>()
                .eq(OmsDeliveryPromiseEntity::getFactoryCode, requireFactory())
                .like(StrUtil.isNotBlank(promiseNo), OmsDeliveryPromiseEntity::getPromiseNo, promiseNo)
                .like(StrUtil.isNotBlank(orderCode), OmsDeliveryPromiseEntity::getOrderCode, orderCode)
                .like(StrUtil.isNotBlank(customerName), OmsDeliveryPromiseEntity::getCustomerName, customerName)
                .eq(StrUtil.isNotBlank(status), OmsDeliveryPromiseEntity::getStatus, status)
                .orderByDesc(OmsDeliveryPromiseEntity::getCreateTime)
                .orderByDesc(OmsDeliveryPromiseEntity::getId);
        return R.ok(service.page(page, wrapper));
    }

    @GetMapping("/prepare")
    @PreAuthorize("@pms.hasPermission('oms_deliveryPromise_submit')")
    public R<OmsDeliveryPromiseDTO.Preparation> prepare(@RequestParam String orderCode) {
        return R.ok(service.prepare(orderCode));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('oms_deliveryPromise_view')")
    public R<OmsDeliveryPromiseDTO.Detail> detail(@PathVariable Long id) {
        return R.ok(service.detail(id));
    }

    @PostMapping("/submit")
    @PreAuthorize("@pms.hasPermission('oms_deliveryPromise_submit')")
    public R<OmsDeliveryPromiseDTO.Detail> submit(
            @Valid @RequestBody OmsDeliveryPromiseDTO.SubmitCommand command) {
        return R.ok(service.submit(command));
    }

    @PostMapping("/sales-confirm")
    @PreAuthorize("@pms.hasPermission('oms_deliveryPromise_sales')")
    public R<OmsDeliveryPromiseDTO.Detail> salesConfirm(
            @Valid @RequestBody OmsDeliveryPromiseDTO.ConfirmCommand command) {
        return R.ok(service.confirm(command, "SALES"));
    }

    @PostMapping("/pmc-confirm")
    @PreAuthorize("@pms.hasPermission('oms_deliveryPromise_pmc')")
    public R<OmsDeliveryPromiseDTO.Detail> pmcConfirm(
            @Valid @RequestBody OmsDeliveryPromiseDTO.ConfirmCommand command) {
        return R.ok(service.confirm(command, "PMC"));
    }

    @PostMapping("/cancel")
    @PreAuthorize("@pms.hasPermission('oms_deliveryPromise_submit')")
    public R<OmsDeliveryPromiseDTO.Detail> cancel(
            @Valid @RequestBody OmsDeliveryPromiseDTO.CancelCommand command) {
        return R.ok(service.cancel(command));
    }

    @PostMapping("/reply")
    @PreAuthorize("@pms.hasPermission('oms_deliveryPromise_reply')")
    public R<OmsDeliveryPromiseDTO.Detail> reply(
            @Valid @RequestBody OmsDeliveryPromiseDTO.ReplyCommand command) {
        return R.ok(service.reply(command));
    }

    private String requireFactory() {
        String factory = FactoryContextHolder.getFactoryCode();
        if (StrUtil.isBlank(factory)) {
            throw new CheckedException("请先在请求头factory-code中选择实际工厂");
        }
        return factory;
    }
}
