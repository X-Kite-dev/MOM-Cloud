package com.kitex.mom.mdm.controller;

import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.RequestExcel;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.mom.common.barcode.dto.req.PrintReq;
import com.kitex.mom.mdm.dto.MdmFactoryModelDTO;
import com.kitex.mom.mdm.entity.MdmFactoryModelEntity;
import com.kitex.mom.mdm.service.MdmFactoryModelService;
import com.kitex.mom.mdm.vo.MdmFactoryModelAncestorVO;
import com.kitex.mom.mdm.vo.MdmFactoryModelTreeVO;
import com.kitex.mom.mdm.vo.excel.MdmFactoryModelExcelVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.BindingResult;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 工厂建模控制器
 *
 * @author claude
 * @date 2025-07-30
 */
@Tag(name = "工厂建模管理")
@RestController
@RequestMapping("/factory-model")
@RequiredArgsConstructor
@Validated
public class MdmFactoryModelController {

    private final MdmFactoryModelService factoryModelService;

    @Operation(summary = "获取工厂建模树形结构")
    @GetMapping("/tree")
    @PreAuthorize("@pms.hasPermission('mdm_factoryModel_view')")
    public R<List<MdmFactoryModelTreeVO>> getFactoryModelTree() {
        List<MdmFactoryModelTreeVO> tree = factoryModelService.getFactoryModelTree();
        return R.ok(tree);
    }

    /**
     * 分页查询工厂建模
     *
     * @param page                  分页对象，包含分页参数
     * @param mdmFactoryModelEntity 查询条件
     * @return 分页查询结果
     */
    @Operation(summary = "获取工厂建模树形结构")
    @GetMapping("/page")
    public R<Page<MdmFactoryModelEntity>> getPage(SearchPage page, @ParameterObject MdmFactoryModelEntity mdmFactoryModelEntity) {
        QueryWrapper<MdmFactoryModelEntity> wrapper = new QueryWrapper<>();
        wrapper.lambda()
                .like(StrUtil.isNotBlank(mdmFactoryModelEntity.getName()), MdmFactoryModelEntity::getName, mdmFactoryModelEntity.getName())
                .like(StrUtil.isNotBlank(mdmFactoryModelEntity.getCode()), MdmFactoryModelEntity::getCode, mdmFactoryModelEntity.getCode())
                .like(StrUtil.isNotBlank(mdmFactoryModelEntity.getType()), MdmFactoryModelEntity::getType, mdmFactoryModelEntity.getType())
                .eq(ObjUtil.isNotEmpty(mdmFactoryModelEntity.getStatus()), MdmFactoryModelEntity::getStatus, mdmFactoryModelEntity.getStatus())
                .eq(ObjUtil.isNotEmpty(mdmFactoryModelEntity.getLevel()), MdmFactoryModelEntity::getLevel, mdmFactoryModelEntity.getLevel())
                .eq(ObjUtil.isNotEmpty(mdmFactoryModelEntity.getParentId()), MdmFactoryModelEntity::getParentId, mdmFactoryModelEntity.getParentId());
        return R.ok(factoryModelService.page(page.toMpPage(), wrapper));
    }

    @Operation(summary = "根据父节点ID获取子节点列表")
    @GetMapping("/children")
    @PreAuthorize("@pms.hasPermission('mdm_factoryModel_view')")
    public R<List<MdmFactoryModelTreeVO>> getChildrenByParentId(@Parameter(description = "父节点ID") @RequestParam(required = false) Long parentId) {
        List<MdmFactoryModelTreeVO> children = factoryModelService.getChildrenByParentId(parentId);
        return R.ok(children);
    }

    /**
     * 根据父节点ID获取所有子节点-分页
     *
     * @param page                  分页对象，包含分页参数
     * @param mdmFactoryModelEntity 查询条件
     * @return 所有子节点
     */
    @Operation(summary = "根据父节点ID获取所有子节点")
    @GetMapping("/getAllChildrenPage")
    public R<Page<MdmFactoryModelEntity>> getAllChildrenPage(SearchPage page, @ParameterObject MdmFactoryModelEntity mdmFactoryModelEntity) {
        return R.ok(factoryModelService.getAllChildrenPage(page.toMpPage(), mdmFactoryModelEntity));
    }

    /**
     * 根据工位ids以列表的形式展示工位及其所有上级数据
     *
     * @param stationIds 工位ids
     * @return 工位及其所有上级数据
     */
    @Operation(summary = "以列表的形式展示工位及其所有上级数据")
    @GetMapping("/getStationByAllParent")
    public R<List<MdmFactoryModelAncestorVO>> getStationByAllParent(@RequestParam Long[] stationIds) {
        return R.ok(factoryModelService.getStationByAllParent(stationIds));
    }

    /**
     * 根据父节点ID获取子节点列表及其子节点所有上级(工序页面绑定工位，根据线体查询工位时使用)
     *
     * @param parentId 父节点ID
     * @return 子节点列表及其子节点所有上级
     */
    @Operation(summary = "根据父节点ID获取子节点列表及其子节点所有上级")
    @GetMapping("/childrenByParent")
    public R<List<MdmFactoryModelAncestorVO>> childrenByParent(@Parameter(description = "父节点ID") @RequestParam Long parentId) {
        List<MdmFactoryModelAncestorVO> children = factoryModelService.childrenByParent(parentId);
        return R.ok(children);
    }

    @Operation(summary = "根据级别获取节点列表")
    @GetMapping("/by-level")
    public R<List<MdmFactoryModelTreeVO>> getNodesByLevel(@Parameter(description = "节点级别", required = true) @RequestParam @NotNull Integer level) {
        List<MdmFactoryModelTreeVO> nodes = factoryModelService.getNodesByLevel(level);
        return R.ok(nodes);
    }

    @Operation(summary = "根据类型获取节点列表")
    @GetMapping("/by-type")
    public R<List<MdmFactoryModelTreeVO>> getNodesByType(@Parameter(description = "节点类型", required = true) @RequestParam @NotNull String type) {
        List<MdmFactoryModelTreeVO> nodes = factoryModelService.getNodesByType(type);
        return R.ok(nodes);
    }

    /** 查询当前实名用户有权访问的工厂下的启用节点，用于跨域校验工位。 */
    @Operation(summary = "查询当前授权工厂的启用节点")
    @GetMapping("/by-type/scoped")
    public R<List<MdmFactoryModelTreeVO>> getScopedNodesByType(@RequestParam("type") String type,
                                                              @RequestParam("factoryCode") String factoryCode) {
        return R.ok(factoryModelService.getScopedNodesByType(type, factoryCode));
    }

    @Operation(summary = "根据类型获取节点列表（带缓存）")
    @GetMapping("/by-type-cache")
    public R<List<MdmFactoryModelTreeVO>> getNodesByTypeWithCache(@Parameter(description = "节点类型", required = true) @RequestParam @NotNull String type) {
        List<MdmFactoryModelTreeVO> nodes = factoryModelService.getNodesByTypeWithCache(type);
        return R.ok(nodes);
    }

    @Operation(summary = "根据类型和父节点ID获取子节点列表")
    @GetMapping("/by-type-parent")
    public R<List<MdmFactoryModelTreeVO>> getNodesByTypeAndParent(@Parameter(description = "节点类型", required = true) @RequestParam @NotNull String type, @Parameter(description = "父节点ID") @RequestParam(required = false) Long parentId) {
        List<MdmFactoryModelTreeVO> nodes = factoryModelService.getNodesByTypeAndParent(type, parentId);
        return R.ok(nodes);
    }

    @Operation(summary = "根据类型获取节点数量")
    @GetMapping("/count-by-type")
    public R<Integer> countNodesByType(@Parameter(description = "节点类型", required = true) @RequestParam @NotNull String type) {
        Integer count = factoryModelService.countNodesByType(type);
        return R.ok(count);
    }

    @Operation(summary = "根据级别获取所有节点类型")
    @GetMapping("/types-by-level")
    public R<List<String>> getTypesByLevel(@Parameter(description = "节点级别", required = true) @RequestParam @NotNull Integer level) {
        List<String> types = factoryModelService.getTypesByLevel(level);
        return R.ok(types);
    }

    @Operation(summary = "获取节点详情")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('mdm_factoryModel_view')")
    public R<MdmFactoryModelEntity> getFactoryModelTree(@PathVariable Long id) {
        return R.ok(factoryModelService.getById(id));
    }

    /**
     * 工位打印
     *
     * @param printReq 打印Req
     * @return R 对象
     */
    @Operation(summary = "工位打印", description = "工位打印")
    @GetMapping("/print")
    public R<String> print(@ParameterObject PrintReq printReq) {
        return R.ok(factoryModelService.print(printReq));
    }

    @Operation(summary = "创建工厂建模节点")
    @PostMapping
    @PreAuthorize("@pms.hasPermission('mdm_factoryModel_add')")
    public R<Boolean> createFactoryModelNode(@Valid @RequestBody MdmFactoryModelDTO dto) {
        Boolean result = factoryModelService.createFactoryModelNode(dto);
        return R.ok(result);
    }

    @Operation(summary = "更新工厂建模节点")
    @PutMapping
    @PreAuthorize("@pms.hasPermission('mdm_factoryModel_edit')")
    public R<Boolean> updateFactoryModelNode(@Valid @RequestBody MdmFactoryModelDTO dto) {
        Boolean result = factoryModelService.updateFactoryModelNode(dto);
        return R.ok(result);
    }

    @Operation(summary = "删除工厂建模节点")
    @DeleteMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('mdm_factoryModel_del')")
    public R<Boolean> deleteFactoryModelNode(@Parameter(description = "节点ID", required = true) @PathVariable @NotNull Long id) {
        Boolean result = factoryModelService.deleteFactoryModelNode(id);
        return R.ok(result);
    }

    @Operation(summary = "移动节点位置")
    @PutMapping("/move")
    public R<Boolean> moveNode(@Parameter(description = "节点ID", required = true) @RequestParam @NotNull Long nodeId, @Parameter(description = "新父节点ID") @RequestParam(required = false) Long newParentId, @Parameter(description = "新排序") @RequestParam(required = false) Integer newSort) {
        Boolean result = factoryModelService.moveNode(nodeId, newParentId, newSort);
        return R.ok(result);
    }

    @Operation(summary = "验证节点编码是否重复")
    @GetMapping("/validate-code")
    public R<Boolean> validateNodeCode(@Parameter(description = "节点编码", required = true) @RequestParam @NotNull String code, @Parameter(description = "排除的节点ID") @RequestParam(required = false) Long excludeId) {
        Boolean result = factoryModelService.validateNodeCode(code, excludeId);
        return R.ok(result);
    }

    @Operation(summary = "获取节点的完整路径")
    @GetMapping("/{id}/path")
    public R<String> getNodePath(@Parameter(description = "节点ID", required = true) @PathVariable @NotNull Long id) {
        String path = factoryModelService.getNodePath(id);
        return R.ok(path);
    }

    @Operation(summary = "根据路径查找节点")
    @GetMapping("/find-by-path")
    public R<Long> getNodeByPath(@Parameter(description = "节点路径", required = true) @RequestParam @NotNull String path) {
        var node = factoryModelService.getNodeByPath(path);
        return R.ok(node != null ? node.getId() : null);
    }

    /**
     * 导出excel 表格
     *
     * @return excel 文件流
     */
    @ResponseExcel
    @Operation(summary = "导出工厂建模数据")
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('mdm_factoryModel_export')")
    public List<MdmFactoryModelExcelVO> exportFactoryModel() {
        return factoryModelService.exportFactoryModel();
    }

    /**
     * 导入工厂建模数据
     *
     * @param excelVOList
     * @param bindingResult
     * @return
     */
    @Operation(summary = "导入工厂建模数据")
    @PostMapping("/import")
    @PreAuthorize("@pms.hasPermission('mdm_factoryModel_export')")
    public R importFactoryModel(@RequestExcel(headRowNumber = 2) List<MdmFactoryModelExcelVO> excelVOList, BindingResult bindingResult) {
        return factoryModelService.importFactoryModel(excelVOList, bindingResult);
    }

}
