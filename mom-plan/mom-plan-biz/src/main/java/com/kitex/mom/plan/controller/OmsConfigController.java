package com.kitex.mom.plan.controller;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.oms.entity.OmsConfigEntity;
import com.kitex.mom.plan.service.OmsConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 订单配置表
 *
 * @author kitex
 * @date 2025-03-24 10:24:55
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/omsConfig")
@Tag(description = "omsConfig", name = "订单配置表管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class OmsConfigController {

    private final OmsConfigService omsConfigService;

    /**
     * 获取订单配置
     *
     * @return R
     */
    @Operation(summary = "获取订单配置", description = "获取订单配置")
    @GetMapping()
    @PreAuthorize("@pms.hasPermission('plan_omsConfig_view')")
    public R<OmsConfigEntity> getDetail() {
        return R.ok(omsConfigService.getOne(new LambdaQueryWrapper<OmsConfigEntity>(), false));
    }

    /**
     * 新增/修改订单配置表
     *
     * @param omsConfig 订单配置表
     * @return R
     */
    @Operation(summary = "新增/修改订单配置表", description = "新增/修改订单配置表")
    @SysLog("新增/修改订单配置表")
    @PutMapping
    @PreAuthorize("@pms.hasPermission('plan_omsConfig_add')")
    public R<Boolean> save(@RequestBody OmsConfigEntity omsConfig) {
        String factoryCode = FactoryContextHolder.getFactoryCode();
        if (StrUtil.isNotEmpty(factoryCode)) {
            omsConfig.setFactoryCode(factoryCode);
        }
        return R.ok(omsConfigService.saveOrUpdate(omsConfig));
    }

}