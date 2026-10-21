package com.kitex.mom.wms.controller;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.wms.dto.WmsTransferRoutePolicyDTO;
import com.kitex.mom.wms.entity.WmsTransferRoutePolicyEntity;
import com.kitex.mom.wms.service.WmsTransferRoutePolicyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;

@RestController
@RequiredArgsConstructor
@RequestMapping("/wmsTransferRoutePolicy")
@Tag(description = "wmsTransferRoutePolicy", name = "调拨路线时效策略")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class WmsTransferRoutePolicyController {

    private final WmsTransferRoutePolicyService service;

    @Operation(summary = "路线策略分页")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('wms_transferRoutePolicy_view')")
    public R<Page<WmsTransferRoutePolicyEntity>> page(@ParameterObject SearchPage page,
                                                      @ParameterObject WmsTransferRoutePolicyEntity query) {
        LambdaQueryWrapper<WmsTransferRoutePolicyEntity> wrapper = Wrappers.lambdaQuery();
        String currentFactory = FactoryContextHolder.getFactoryCode();
        wrapper.eq(StrUtil.isNotBlank(currentFactory), WmsTransferRoutePolicyEntity::getFactoryCode, currentFactory);
        wrapper.like(StrUtil.isNotBlank(query.getPolicyCode()), WmsTransferRoutePolicyEntity::getPolicyCode,
                        query.getPolicyCode())
                .like(StrUtil.isNotBlank(query.getPolicyName()), WmsTransferRoutePolicyEntity::getPolicyName,
                        query.getPolicyName())
                .eq(StrUtil.isNotBlank(query.getSourceWarehouseCode()),
                        WmsTransferRoutePolicyEntity::getSourceWarehouseCode, query.getSourceWarehouseCode())
                .eq(StrUtil.isNotBlank(query.getTargetWarehouseCode()),
                        WmsTransferRoutePolicyEntity::getTargetWarehouseCode, query.getTargetWarehouseCode())
                .eq(StrUtil.isNotBlank(query.getTransportMode()), WmsTransferRoutePolicyEntity::getTransportMode,
                        query.getTransportMode())
                .eq(StrUtil.isNotBlank(query.getUrgencyLevel()), WmsTransferRoutePolicyEntity::getUrgencyLevel,
                        query.getUrgencyLevel())
                .eq(query.getStatus() != null, WmsTransferRoutePolicyEntity::getStatus, query.getStatus())
                .orderByAsc(WmsTransferRoutePolicyEntity::getSourceWarehouseCode)
                .orderByAsc(WmsTransferRoutePolicyEntity::getTargetWarehouseCode)
                .orderByAsc(WmsTransferRoutePolicyEntity::getRoutePriority);
        return R.ok(service.page(page.toMpPage(), wrapper));
    }

    @Operation(summary = "路线策略详情")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('wms_transferRoutePolicy_view')")
    public R<WmsTransferRoutePolicyEntity> detail(@PathVariable Long id) {
        return R.ok(service.getPolicy(id));
    }

    @Operation(summary = "新增或修改路线策略")
    @PostMapping
    @SysLog("保存调拨路线时效策略")
    @PreAuthorize("@pms.hasPermission('wms_transferRoutePolicy_edit')")
    public R<WmsTransferRoutePolicyEntity> save(@RequestBody WmsTransferRoutePolicyEntity policy) {
        return R.ok(service.savePolicy(policy));
    }

    @Operation(summary = "删除已停用路线策略")
    @DeleteMapping
    @SysLog("删除已停用调拨路线时效策略")
    @PreAuthorize("@pms.hasPermission('wms_transferRoutePolicy_del')")
    public R<Boolean> remove(@RequestBody Long[] ids) {
        return R.ok(service.removePolicies(Arrays.asList(ids)));
    }

    @Operation(summary = "路线时效试算")
    @PostMapping("/preview")
    @PreAuthorize("@pms.hasPermission('wms_transferRoutePolicy_view')")
    public R<WmsTransferRoutePolicyDTO.RoutePlan> preview(
            @RequestBody WmsTransferRoutePolicyDTO.PreviewQuery query) {
        return R.ok(service.preview(query));
    }
}
