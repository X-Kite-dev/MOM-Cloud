package com.kitex.mom.mdm.controller;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.tree.Tree;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.RequestExcel;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.mdm.entity.MdmMaterialGroupEntity;
import com.kitex.mom.mdm.service.MdmMaterialGroupService;
import com.kitex.mom.mdm.vo.excel.MdmMaterialGroupExcelVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 物料分组表
 *
 * @author kitex
 * @date 2024-09-18 10:00:05
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mdmMaterialGroup")
@Tag(description = "MdmMaterialGroup", name = "物料分组表管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class MdmMaterialGroupController {

    private final MdmMaterialGroupService mdmMaterialGroupService;

    /**
     * 分页查询
     *
     * @param page             分页对象
     * @param mdmMaterialGroup 物料分组表
     * @return
     */
    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('mdm_mdmMaterialGroup_view')")
    public R<Page<MdmMaterialGroupEntity>> getMdmMaterialGroupPage(SearchPage page, @ParameterObject MdmMaterialGroupEntity mdmMaterialGroup) {
        QueryWrapper<MdmMaterialGroupEntity> wrapper = new QueryWrapper<>();
        wrapper.lambda().like(StrUtil.isNotBlank(mdmMaterialGroup.getGroupName()), MdmMaterialGroupEntity::getGroupName,
                mdmMaterialGroup.getGroupName());
        wrapper.lambda().like(StrUtil.isNotBlank(mdmMaterialGroup.getGroupCode()), MdmMaterialGroupEntity::getGroupCode,
                mdmMaterialGroup.getGroupCode());
        wrapper.lambda().like(StrUtil.isNotBlank(mdmMaterialGroup.getDescription()), MdmMaterialGroupEntity::getDescription,
                mdmMaterialGroup.getDescription());
        wrapper.lambda().eq(Objects.nonNull(mdmMaterialGroup.getEnabled()), MdmMaterialGroupEntity::getEnabled,
                mdmMaterialGroup.getEnabled());
        return R.ok(mdmMaterialGroupService.page(page.toMpPage(), wrapper));
    }

    /**
     * 树形数据
     *
     * @param parentId  父节点ID
     * @param groupCode 分组编码
     * @param groupName 分组名称
     * @return 树形物料分组
     */
    @Operation(summary = "树形数据", description = "树形数据")
    @GetMapping("/tree")
    @PreAuthorize("@pms.hasPermission('mdm_mdmMaterialGroup_view')")
    public R<List<Tree<Long>>> getMdmMaterialGroupTree(Long parentId, String groupCode, String groupName, Boolean enabled, String factoryCode) {
        return R.ok(mdmMaterialGroupService.getMdmMaterialGroupTree(parentId, groupCode, groupName, enabled));
    }

    /**
     * 查询各物料分组状态的数量
     *
     * @return
     */
    @Operation(summary = "查询各物料分组状态的数量", description = "查询各物料分组状态的数量")
    @GetMapping("/getCount")
    public R<Map<Boolean, Long>> getCount(@ParameterObject MdmMaterialGroupEntity mdmMaterialGroup) {
        QueryWrapper<MdmMaterialGroupEntity> wrapper = new QueryWrapper<>();
        wrapper.lambda().select(MdmMaterialGroupEntity::getEnabled)
                .like(StrUtil.isNotBlank(mdmMaterialGroup.getGroupName()), MdmMaterialGroupEntity::getGroupName, mdmMaterialGroup.getGroupName())
                .like(StrUtil.isNotBlank(mdmMaterialGroup.getGroupCode()), MdmMaterialGroupEntity::getGroupCode, mdmMaterialGroup.getGroupCode());
        return R.ok(mdmMaterialGroupService.list(wrapper)
                .stream()
                .collect(Collectors.groupingBy(MdmMaterialGroupEntity::getEnabled, Collectors.counting())));
    }

    /**
     * 物料分组列表
     *
     * @return 物料分组列表
     */
    @Operation(summary = "物料分组列表", description = "物料分组列表")
    @GetMapping("/list")
    public R<List<MdmMaterialGroupEntity>> getList() {
        return R.ok(mdmMaterialGroupService.list());
    }

    /**
     * 通过id查询物料分组表
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "通过id查询", description = "通过id查询")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('mdm_mdmMaterialGroup_view')")
    public R<MdmMaterialGroupEntity> getById(@PathVariable("id") Long id) {
        return R.ok(mdmMaterialGroupService.getById(id));
    }

    /**
     * 根据物料分组编码获取详情
     *
     * @param groupCode 物料分组编码
     * @return R
     */
    @Operation(summary = "根据物料分组编码获取详情", description = "根据物料分组编码获取详情")
    @GetMapping("/details")
    public R<MdmMaterialGroupEntity> details(String groupCode) {
        return R.ok(mdmMaterialGroupService.getOne(
                Wrappers.<MdmMaterialGroupEntity>lambdaQuery().eq(MdmMaterialGroupEntity::getGroupCode, groupCode)));
    }

    /**
     * 新增物料分组表
     *
     * @param mdmMaterialGroup 物料分组表
     * @return R
     */
    @Operation(summary = "新增物料分组表", description = "新增物料分组表")
    @SysLog("新增物料分组表")
    @PostMapping
    @PreAuthorize("@pms.hasPermission('mdm_mdmMaterialGroup_add')")
    public R<Boolean> save(@RequestBody MdmMaterialGroupEntity mdmMaterialGroup) {
        return R.ok(mdmMaterialGroupService.save(mdmMaterialGroup));
    }

    /**
     * 修改物料分组表
     *
     * @param mdmMaterialGroup 物料分组表
     * @return R
     */
    @Operation(summary = "修改物料分组表", description = "修改物料分组表")
    @SysLog("修改物料分组表")
    @PutMapping
    @PreAuthorize("@pms.hasPermission('mdm_mdmMaterialGroup_edit')")
    public R<Boolean> updateById(@RequestBody MdmMaterialGroupEntity mdmMaterialGroup) {
        return R.ok(mdmMaterialGroupService.updateById(mdmMaterialGroup));
    }

    /**
     * 通过id删除物料分组表
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "通过id删除物料分组表", description = "通过id删除物料分组表")
    @SysLog("通过id删除物料分组表")
    @DeleteMapping
    @PreAuthorize("@pms.hasPermission('mdm_mdmMaterialGroup_del')")
    public R<Boolean> removeById(@RequestBody Long[] ids) {
        return R.ok(mdmMaterialGroupService.removeMaterialGroup(CollUtil.toList(ids)));
    }

    /**
     * 导入excel 表格
     *
     * @param excelVOList   物料分组列表
     * @param bindingResult 错误信息列表
     * @return R
     */
    @PostMapping("/import")
    @PreAuthorize("@pms.hasPermission('mdm_mdmMaterialGroup_export')")
    public R importMaterialGroup(@RequestExcel(headRowNumber = 2) List<MdmMaterialGroupExcelVO> excelVOList, BindingResult bindingResult) {
        return mdmMaterialGroupService.importMaterialGroup(excelVOList, bindingResult);
    }

    /**
     * 导出excel 表格
     *
     * @param mdmMaterialGroup 查询条件
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('mdm_mdmMaterialGroup_export')")
    public List<MdmMaterialGroupExcelVO> export(MdmMaterialGroupEntity mdmMaterialGroup) {
        return mdmMaterialGroupService.export(mdmMaterialGroup);
    }

}