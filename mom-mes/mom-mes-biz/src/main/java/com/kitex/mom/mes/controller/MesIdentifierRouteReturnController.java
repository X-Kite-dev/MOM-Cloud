package com.kitex.mom.mes.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.util.R;
import com.kitex.common.query.utils.QueryGenerator;
import com.kitex.mom.mes.dto.MesIdentifierRouteReturnCommand;
import com.kitex.mom.mes.entity.MesIdentifierRouteReturnEntity;
import com.kitex.mom.mes.service.MesIdentifierRouteReturnService;
import com.kitex.mom.mes.service.impl.MesActualFactoryAccessService;
import com.kitex.mom.mes.service.impl.MesIdentifierRouteReturnCoordinator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 逐标识退回上序申请、双人复核、执行路线和永久审计入口。
 */
@Tag(name = "逐标识退回上序", description = "在制品无补料重过站；末工序或质量返工必须走正式返工工单")
@RestController
@RequiredArgsConstructor
@RequestMapping("/mesTask/identifier-route-return")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class MesIdentifierRouteReturnController {

    private final MesIdentifierRouteReturnCoordinator coordinator;
    private final MesIdentifierRouteReturnService routeReturnService;
    private final MesActualFactoryAccessService factoryAccess;

    @Operation(summary = "申请前预览", description = "返回最近已过工序、可退回目标及当前执行中版本")
    @GetMapping("/preview")
    @PreAuthorize("@pms.hasPermission('mesStationPass:view','mesStationPass:routeReturn:apply')")
    public R<Map<String, Object>> preview(@RequestParam String taskCode, @RequestParam String identifier) {
        return R.ok(coordinator.preview(taskCode, identifier));
    }

    @Operation(summary = "退回上序申请分页")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('mesStationPass:view','mesStationPass:routeReturn:apply','mesStationPass:routeReturn:review')")
    public R<IPage<MesIdentifierRouteReturnEntity>> page(Page<MesIdentifierRouteReturnEntity> page,
                                                         MesIdentifierRouteReturnEntity query) {
        QueryWrapper<MesIdentifierRouteReturnEntity> wrapper = QueryGenerator.initQueryWrapper(query);
        wrapper.lambda().eq(MesIdentifierRouteReturnEntity::getFactoryCode, factoryAccess.require("read"))
                .orderByDesc(MesIdentifierRouteReturnEntity::getAppliedTime)
                .orderByDesc(MesIdentifierRouteReturnEntity::getId);
        return R.ok(routeReturnService.page(page, wrapper));
    }

    @Operation(summary = "退回上序操作", description = "支持APPLY、APPROVE、REJECT、WITHDRAW；请求号永久幂等")
    @PostMapping("/{operation}")
    @PreAuthorize("@pms.hasPermission(#operation == 'APPLY' || #operation == 'WITHDRAW' "
            + "? 'mesStationPass:routeReturn:apply' : 'mesStationPass:routeReturn:review')")
    public R<Map<String, Object>> execute(@PathVariable String operation,
                                          @RequestBody MesIdentifierRouteReturnCommand command) {
        command.setOperation(operation);
        return R.ok(coordinator.execute(command));
    }

    @Operation(summary = "退回上序操作履历")
    @GetMapping("/{routeReturnId}/operations")
    @PreAuthorize("@pms.hasPermission('mesStationPass:view','mesStationPass:routeReturn:apply','mesStationPass:routeReturn:review')")
    public R<?> operations(@PathVariable Long routeReturnId) {
        return R.ok(coordinator.operations(routeReturnId));
    }
}
