package com.kitex.mom.mdm.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.mdm.entity.MdmManufacturingModelEntity;
import com.kitex.mom.mdm.service.MdmManufacturingModelService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 制造模型控制器
 *
 * @author claude
 * @date 2025-08-11
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/manufacturing-model")
@Tag(description = "制造模型管理", name = "制造模型")
public class MdmManufacturingModelController {

    private final MdmManufacturingModelService manufacturingModelService;

    /**
     * 获取制造模型列表
     *
     * @return 制造模型列表
     */
    @Operation(summary = "获取制造模型列表", description = "获取所有制造模型列表")
    @GetMapping("/list")
    @PreAuthorize("@pms.hasPermission('mdm_mdmManufacturingModel_view')")
    public R<List<MdmManufacturingModelEntity>> getManufacturingModelList(@ParameterObject MdmManufacturingModelEntity mdmManufacturingModelEntity) {
        return R.ok(manufacturingModelService.getManufacturingModelList(mdmManufacturingModelEntity));
    }

    /**
     * 根据ID获取制造模型
     *
     * @param id 制造模型ID
     * @return 制造模型详情
     */
    @Operation(summary = "根据ID获取制造模型", description = "根据ID获取制造模型详情")
    @GetMapping("/{id}")
    public R<MdmManufacturingModelEntity> getManufacturingModelById(@Parameter(description = "制造模型ID", required = true) @PathVariable Long id) {
        MdmManufacturingModelEntity result = manufacturingModelService.getById(id);
        return R.ok(result);
    }

    /**
     * 更新制造模型
     *
     * @param manufacturingModelEntityList 制造模型信息
     * @return 更新结果
     */
    @Operation(summary = "更新制造模型", description = "更新制造模型信息")
    @PutMapping
    @PreAuthorize("@pms.hasPermission('mdm_mdmManufacturingModel_edit')")
    public R<Boolean> updateBatchManufacturingModel(@Valid @RequestBody List<MdmManufacturingModelEntity> manufacturingModelEntityList) {
        return R.ok(manufacturingModelService.updateBatchManufacturingModel(manufacturingModelEntityList));
    }

    /**
     * 根据车间编码获取制造模型
     *
     * @param workshopCode
     * @return
     */
    @Operation(summary = "根据车间获取制造模型", description = "根据车间获取制造模型")
    @GetMapping("/getByWorkshop")
    public R<MdmManufacturingModelEntity> getByWorkshop(@Parameter(description = "车间", required = true) @RequestParam String workshopCode) {
        MdmManufacturingModelEntity result = manufacturingModelService.getByWorkshop(workshopCode);
        return R.ok(result);
    }

}