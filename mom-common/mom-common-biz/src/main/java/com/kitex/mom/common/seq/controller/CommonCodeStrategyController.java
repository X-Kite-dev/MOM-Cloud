package com.kitex.mom.common.seq.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.mom.common.seq.dto.CommonCodeStrategyDTO;
import com.kitex.mom.common.seq.entity.CommonCodeStrategy;
import com.kitex.mom.common.seq.service.CommonCodeStrategyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 编码规则控制器
 *
 * @author claude
 * @date 2025-08-11
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/common/code-strategy")
@Tag(description = "编码规则管理", name = "编码规则")
public class CommonCodeStrategyController {

    private final CommonCodeStrategyService codeStrategyService;

    /**
     * 分页查询编码规则
     *
     * @param page         分页对象
     * @param codeStrategy 查询条件
     * @return 分页结果
     */
    @Operation(summary = "分页查询", description = "分页查询编码规则")
    @GetMapping("/page")
    //@PreAuthorize("@pms.hasPermission('common_code_strategy_view')")
    public R<Page<CommonCodeStrategy>> getCodeStrategyPage(SearchPage page, @ParameterObject CommonCodeStrategy codeStrategy) {
        LambdaQueryWrapper<CommonCodeStrategy> wrapper = new LambdaQueryWrapper<>();

        if (codeStrategy.getCode() != null) {
            wrapper.like(CommonCodeStrategy::getCode, codeStrategy.getCode());
        }
        if (codeStrategy.getDescription() != null) {
            wrapper.like(CommonCodeStrategy::getDescription, codeStrategy.getDescription());
        }
        if (codeStrategy.getType() != null) {
            wrapper.eq(CommonCodeStrategy::getType, codeStrategy.getType());
        }
        if (codeStrategy.getLabelType() != null) {
            wrapper.eq(CommonCodeStrategy::getLabelType, codeStrategy.getLabelType());
        }
        if (codeStrategy.getStatus() != null) {
            wrapper.eq(CommonCodeStrategy::getStatus, codeStrategy.getStatus());
        }
        if (codeStrategy.getIsDefault() != null) {
            wrapper.eq(CommonCodeStrategy::getIsDefault, codeStrategy.getIsDefault());
        }

        wrapper.orderByDesc(CommonCodeStrategy::getCreateTime);

        Page<CommonCodeStrategy> result = codeStrategyService.page(page.toMpPage(), wrapper);
        return R.ok(result);
    }

    /**
     * 获取编码规则列表
     *
     * @return 编码规则列表
     */
    @Operation(summary = "获取编码规则列表", description = "获取所有编码规则列表")
    @GetMapping("/list")
    public R<List<CommonCodeStrategy>> getCodeStrategyList() {
        LambdaQueryWrapper<CommonCodeStrategy> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByDesc(CommonCodeStrategy::getCreateTime);

        List<CommonCodeStrategy> list = codeStrategyService.list(wrapper);
        return R.ok(list);
    }

    /**
     * 根据ID获取编码规则
     *
     * @param id 编码规则ID
     * @return 编码规则详情
     */
    @Operation(summary = "根据ID获取编码规则", description = "根据ID获取编码规则详情")
    @GetMapping("/{id}")
    //@PreAuthorize("@pms.hasPermission('common_code_strategy_view')")
    public R<CommonCodeStrategy> getCodeStrategyById(@Parameter(description = "编码规则ID", required = true) @PathVariable Long id) {
        CommonCodeStrategy result = codeStrategyService.getById(id);
        return R.ok(result);
    }

    /**
     * 创建编码规则
     *
     * @param dto 编码规则信息
     * @return 创建结果
     */
    @Operation(summary = "创建编码规则", description = "创建新的编码规则")
    @PostMapping
    //@PreAuthorize("@pms.hasPermission('common_code_strategy_add')")
    public R<Boolean> createCodeStrategy(@RequestBody CommonCodeStrategyDTO dto) {
        CommonCodeStrategy codeStrategy = new CommonCodeStrategy();
        codeStrategy.setParentCode(dto.getParentCode());
        codeStrategy.setCode(dto.getCode());
        codeStrategy.setDescription(dto.getDescription());
        codeStrategy.setType(dto.getType());
        codeStrategy.setLabelType(dto.getLabelType());
        codeStrategy.setLength(dto.getLength());
        codeStrategy.setCheckLength(dto.getCheckLength());
        codeStrategy.setIsDefault(dto.getIsDefault());
        codeStrategy.setSegmentNum(dto.getSegmentNum());
        codeStrategy.setParseEntity(dto.getParseEntity());
        codeStrategy.setParseEntityDescription(dto.getParseEntityDescription());
        codeStrategy.setStatus(dto.getStatus());

        boolean result = codeStrategyService.save(codeStrategy);
        return R.ok(result);
    }

    /**
     * 更新编码规则
     *
     * @param dto 编码规则信息
     * @return 更新结果
     */
    @Operation(summary = "更新编码规则", description = "更新编码规则信息")
    @PutMapping
    //@PreAuthorize("@pms.hasPermission('common_code_strategy_edit')")
    public R<Boolean> updateCodeStrategy(@RequestBody CommonCodeStrategyDTO dto) {
        CommonCodeStrategy codeStrategy = new CommonCodeStrategy();
        codeStrategy.setId(dto.getId());
        codeStrategy.setParentCode(dto.getParentCode());
        codeStrategy.setCode(dto.getCode());
        codeStrategy.setDescription(dto.getDescription());
        codeStrategy.setType(dto.getType());
        codeStrategy.setLabelType(dto.getLabelType());
        codeStrategy.setLength(dto.getLength());
        codeStrategy.setCheckLength(dto.getCheckLength());
        codeStrategy.setIsDefault(dto.getIsDefault());
        codeStrategy.setSegmentNum(dto.getSegmentNum());
        codeStrategy.setParseEntity(dto.getParseEntity());
        codeStrategy.setParseEntityDescription(dto.getParseEntityDescription());
        codeStrategy.setStatus(dto.getStatus());

        boolean result = codeStrategyService.updateById(codeStrategy);
        return R.ok(result);
    }

    /**
     * 删除编码规则
     *
     * @param id 编码规则ID
     * @return 删除结果
     */
    @Operation(summary = "删除编码规则", description = "删除编码规则")
    @DeleteMapping("/{id}")
    //@PreAuthorize("@pms.hasPermission('common_code_strategy_del')")
    public R<Boolean> deleteCodeStrategy(@Parameter(description = "编码规则ID", required = true) @PathVariable Long id) {
        boolean result = codeStrategyService.removeById(id);
        return R.ok(result);
    }

    /**
     * 批量删除编码规则
     *
     * @param ids 编码规则ID数组
     * @return 删除结果
     */
    @Operation(summary = "批量删除编码规则", description = "批量删除编码规则")
    @DeleteMapping("/batch")
    //@PreAuthorize("@pms.hasPermission('common_code_strategy_del')")
    public R<Boolean> deleteCodeStrategyBatch(@Parameter(description = "编码规则ID数组", required = true) @RequestBody Long[] ids) {
        boolean result = codeStrategyService.removeByIds(java.util.Arrays.asList(ids));
        return R.ok(result);
    }

    /**
     * 根据规则编码获取编码规则
     */
    @Operation(summary = "根据规则编码获取编码规则", description = "根据规则编码获取编码规则")
    @GetMapping("/getByCode")
    public R<CommonCodeStrategy> getByCode(@Parameter(description = "规则编码", required = true) @RequestParam String code) {
        LambdaQueryWrapper<CommonCodeStrategy> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CommonCodeStrategy::getCode, code);
        CommonCodeStrategy result = codeStrategyService.getOne(wrapper);
        return R.ok(result);
    }

    /**
     * 获取默认编码规则
     */
    @Operation(summary = "获取默认编码规则", description = "获取默认编码规则")
    @GetMapping("/getDefault")
    public R<CommonCodeStrategy> getDefault() {
        LambdaQueryWrapper<CommonCodeStrategy> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CommonCodeStrategy::getIsDefault, true);
        wrapper.eq(CommonCodeStrategy::getStatus, true);
        CommonCodeStrategy result = codeStrategyService.getOne(wrapper);
        return R.ok(result);
    }

}