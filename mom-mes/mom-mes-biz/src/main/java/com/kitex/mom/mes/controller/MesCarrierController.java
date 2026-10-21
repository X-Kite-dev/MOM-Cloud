package com.kitex.mom.mes.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.util.R;
import com.kitex.common.query.utils.QueryGenerator;
import com.kitex.mom.mes.dto.MesCarrierCommand;
import com.kitex.mom.mes.dto.MesCarrierReportCommand;
import com.kitex.mom.mes.entity.MesCarrierEntity;
import com.kitex.mom.mes.entity.MesCarrierItemEntity;
import com.kitex.mom.mes.entity.MesCarrierOperationEntity;
import com.kitex.mom.mes.service.MesCarrierService;
import com.kitex.mom.mes.service.impl.MesActualFactoryAccessService;
import com.kitex.mom.mes.service.impl.MesCarrierCoordinator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 可复用生产载具、逐件成员和整载具过站入口。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mesTask/carrier")
@Tag(name = "生产载具逐件谱系", description = "载具装卸、锁定、生命周期和逐件原子展开报工")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class MesCarrierController {

    private final MesCarrierService carrierService;
    private final MesCarrierCoordinator coordinator;
    private final MesActualFactoryAccessService factoryAccess;

    @GetMapping("/page")
    @Operation(summary = "载具分页查询")
    @PreAuthorize("@pms.hasPermission('mesCarrier:view')")
    public R<IPage<MesCarrierEntity>> page(Page<MesCarrierEntity> page, MesCarrierEntity query) {
        QueryWrapper<MesCarrierEntity> wrapper = QueryGenerator.initQueryWrapper(query);
        wrapper.lambda().eq(MesCarrierEntity::getFactoryCode, factoryAccess.require("read"))
                .orderByDesc(MesCarrierEntity::getUpdateTime).orderByDesc(MesCarrierEntity::getId);
        return R.ok(carrierService.page(page, wrapper));
    }

    @GetMapping("/preview")
    @Operation(summary = "读取载具实时成员及共同下一工序")
    @PreAuthorize("@pms.hasPermission('mesCarrier:view','mesCarrier:report')")
    public R<Map<String, Object>> preview(@RequestParam String carrierCode) {
        return R.ok(coordinator.preview(carrierCode));
    }

    @PostMapping("/operate")
    @Operation(summary = "载具装卸、锁定及生命周期操作", description = "所有操作使用永久请求号和状态版本防重")
    @PreAuthorize("@pms.hasPermission(#command.operation != null && {'CREATE','MAINTAIN','RESTORE','RETIRE'}.contains(#command.operation.toUpperCase()) ? 'mesCarrier:maintain' : 'mesCarrier:operate')")
    public R<Map<String, Object>> operate(@RequestBody MesCarrierCommand command) {
        return R.ok(coordinator.execute(command));
    }

    @PostMapping("/report")
    @Operation(summary = "整载具逐件原子报工", description = "每件进入标准报工主链；任一件失败整批回滚")
    @PreAuthorize("@pms.hasPermission('mesCarrier:report') and @pms.hasPermission('mesStationPass:report')")
    public R<Map<String, Object>> report(@RequestBody MesCarrierReportCommand command) {
        return R.ok(coordinator.report(command));
    }

    @GetMapping("/{carrierId}/items")
    @Operation(summary = "载具逐件装卸谱系")
    @PreAuthorize("@pms.hasPermission('mesCarrier:view')")
    public R<List<MesCarrierItemEntity>> items(@PathVariable Long carrierId) {
        return R.ok(coordinator.items(carrierId));
    }

    @GetMapping("/{carrierId}/operations")
    @Operation(summary = "载具永久操作及批量报工回执")
    @PreAuthorize("@pms.hasPermission('mesCarrier:view')")
    public R<List<MesCarrierOperationEntity>> operations(@PathVariable Long carrierId) {
        return R.ok(coordinator.operations(carrierId));
    }
}
