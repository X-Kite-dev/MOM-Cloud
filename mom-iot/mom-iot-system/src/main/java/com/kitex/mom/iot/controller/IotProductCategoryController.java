package com.kitex.mom.iot.controller;

import cn.hutool.core.lang.tree.Tree;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.iot.dto.IotProductCategoryDTO;
import com.kitex.mom.iot.entity.IotProductCategoryEntity;
import com.kitex.mom.iot.service.IotProductCategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * IoT产品分类Controller
 *
 * @author system
 * @date 2025-03-12
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/product/category")
@Tag(name = "产品分类管理")
public class IotProductCategoryController {

    private final IotProductCategoryService productCategoryService;

    /**
     * 分页查询产品分类
     *
     * @param page     分页参数
     * @param category 查询条件
     * @return 分页结果
     */
    @GetMapping("/page")
    @Operation(summary = "分页查询产品分类")
    public R<IPage<IotProductCategoryEntity>> getCategoryPage(Page<IotProductCategoryEntity> page, IotProductCategoryEntity category) {
        return R.ok(productCategoryService.getCategoryPage(page, category));
    }

    /**
     * 查询产品分类树
     *
     * @param parentId 父分类ID
     * @return 分类树
     */
    @GetMapping("/tree")
    @Operation(summary = "查询产品分类树")
    public R<List<Tree<Long>>> getCategoryTree(@RequestParam(required = false) Long parentId) {
        return R.ok(productCategoryService.getCategoryTree(parentId));
    }

    /**
     * 获取产品分类详情
     *
     * @param id 分类ID
     * @return 分类详情
     */
    @GetMapping("/{id}")
    @Operation(summary = "获取产品分类详情")
    public R<IotProductCategoryEntity> getCategoryById(@PathVariable Long id) {
        return R.ok(productCategoryService.getById(id));
    }

    /**
     * 创建产品分类
     *
     * @param categoryDTO 分类信息
     * @return 操作结果
     */
    @PostMapping
    @SysLog("创建产品分类")
    @Operation(summary = "创建产品分类")
    @PreAuthorize("@pms.hasPermission('iot_product_category_add')")
    public R<Boolean> createCategory(@RequestBody IotProductCategoryDTO categoryDTO) {
        return R.ok(productCategoryService.createCategory(categoryDTO));
    }

    /**
     * 更新产品分类
     *
     * @param categoryDTO 分类信息
     * @return 操作结果
     */
    @PutMapping
    @SysLog("更新产品分类")
    @Operation(summary = "更新产品分类")
    @PreAuthorize("@pms.hasPermission('iot_product_category_edit')")
    public R<Boolean> updateCategory(@RequestBody IotProductCategoryDTO categoryDTO) {
        return R.ok(productCategoryService.updateCategory(categoryDTO));
    }

    /**
     * 删除产品分类
     *
     * @param id 分类ID
     * @return 操作结果
     */
    @DeleteMapping("/{id}")
    @SysLog("删除产品分类")
    @Operation(summary = "删除产品分类")
    @PreAuthorize("@pms.hasPermission('iot_product_category_del')")
    public R<Boolean> removeCategory(@PathVariable Long id) {
        return R.ok(productCategoryService.removeCategory(id));
    }
} 