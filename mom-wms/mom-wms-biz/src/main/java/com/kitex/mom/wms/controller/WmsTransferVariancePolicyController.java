package com.kitex.mom.wms.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.mom.wms.dto.WmsTransferVarianceDTO;
import com.kitex.mom.wms.entity.WmsTransferVariancePolicyEntity;
import com.kitex.mom.wms.service.WmsTransferVariancePolicyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/wmsTransferVariancePolicy")
@Tag(name = "调拨短溢收容差策略")
public class WmsTransferVariancePolicyController {
    private final WmsTransferVariancePolicyService service;

    @GetMapping("/page")
    @Operation(summary = "分页查询短溢收容差策略")
    @PreAuthorize("@pms.hasPermission('wms_transferVariancePolicy_view')")
    public R<Page<WmsTransferVariancePolicyEntity>> page(@ParameterObject SearchPage page,
                                                         @ParameterObject WmsTransferVarianceDTO.PolicyQuery query) {
        return R.ok(service.queryPage(page.toMpPage(), query));
    }

    @PostMapping
    @Operation(summary = "保存短溢收容差策略")
    @PreAuthorize("@pms.hasPermission('wms_transferVariancePolicy_edit')")
    public R<Long> save(@RequestBody WmsTransferVarianceDTO.PolicyCommand command) {
        return R.ok(service.savePolicy(command));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除已停用短溢收容差策略")
    @PreAuthorize("@pms.hasPermission('wms_transferVariancePolicy_del')")
    public R<Boolean> delete(@PathVariable Long id) {
        service.deletePolicy(id);
        return R.ok(Boolean.TRUE);
    }
}
