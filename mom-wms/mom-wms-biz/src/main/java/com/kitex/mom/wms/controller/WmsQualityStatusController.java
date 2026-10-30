package com.kitex.mom.wms.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.wms.dto.WmsQualityActionExecuteCommand;
import com.kitex.mom.wms.dto.WmsQualityStatusCommand;
import com.kitex.mom.wms.dto.WmsQualityStatusResult;
import com.kitex.mom.wms.entity.WmsQualityStatusOperationEntity;
import com.kitex.mom.wms.service.WmsQualityStatusService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/material/wmsLiveStock/quality")
public class WmsQualityStatusController {
    private final WmsQualityStatusService service;

    /**
     * 质量状态仅接受QMS等受信业务服务命令，浏览器不能直接伪造正式放行回执。
     */
    @Inner
    @PostMapping("/status")
    public R<WmsQualityStatusResult> apply(@RequestBody WmsQualityStatusCommand command) {
        return R.ok(service.apply(command));
    }

    @Operation(summary = "质量库存事务分页")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('wmsQualityStatus:show')")
    public R<IPage<WmsQualityStatusOperationEntity>> page(Page<WmsQualityStatusOperationEntity> page,
                                                          WmsQualityStatusOperationEntity query) {
        String factoryCode = FactoryContextHolder.getFactoryCode();
        if (factoryCode == null || factoryCode.isBlank()) {
            throw new CheckedException("请先在请求头factory-code中选择实际工厂");
        }
        String stockId = query.getAffectedStockIds();
        if (stockId != null && !stockId.isBlank() && !stockId.matches("[1-9][0-9]{0,18}")) {
            throw new CheckedException("库存主键筛选必须是有效正整数");
        }
        return R.ok(service.lambdaQuery()
                .eq(WmsQualityStatusOperationEntity::getFactoryCode, factoryCode)
                .apply(stockId != null && !stockId.isBlank(),
                        "string_to_array(COALESCE(affected_stock_ids, ''), ',') @> ARRAY[{0}]::text[]",
                        stockId)
                .eq(query.getId() != null, WmsQualityStatusOperationEntity::getId, query.getId())
                .like(query.getHoldNo() != null && !query.getHoldNo().isBlank(),
                        WmsQualityStatusOperationEntity::getHoldNo, query.getHoldNo())
                .like(query.getMatCode() != null && !query.getMatCode().isBlank(),
                        WmsQualityStatusOperationEntity::getMatCode, query.getMatCode())
                .eq(query.getWarehouseCode() != null && !query.getWarehouseCode().isBlank(),
                        WmsQualityStatusOperationEntity::getWarehouseCode, query.getWarehouseCode())
                .eq(query.getSourceType() != null && !query.getSourceType().isBlank(),
                        WmsQualityStatusOperationEntity::getSourceType, query.getSourceType())
                .eq(query.getAction() != null && !query.getAction().isBlank(),
                        WmsQualityStatusOperationEntity::getAction, query.getAction())
                .eq(query.getStatus() != null && !query.getStatus().isBlank(),
                        WmsQualityStatusOperationEntity::getStatus, query.getStatus())
                .orderByDesc(WmsQualityStatusOperationEntity::getCreateTime).page(page));
    }

    @PostMapping("/{id}/retry")
    @PreAuthorize("@pms.hasPermission('wmsQualityStatus:retry')")
    public R<WmsQualityStatusResult> retry(@PathVariable Long id) {
        return R.ok(service.retry(id));
    }

    @PostMapping("/execute")
    @PreAuthorize("@pms.hasPermission('wmsQualityStatus:execute')")
    public R<WmsQualityStatusResult> execute(@RequestBody WmsQualityActionExecuteCommand command) {
        return R.ok(service.execute(command));
    }
}
