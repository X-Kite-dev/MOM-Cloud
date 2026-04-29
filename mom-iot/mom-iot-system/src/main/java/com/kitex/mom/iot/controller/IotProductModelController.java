package com.kitex.mom.iot.controller;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.iot.entity.IotProductModelEntity;
import com.kitex.mom.iot.service.IotProductModelService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * IoT产品数据模型控制器
 *
 * @author system
 * @date 2025-03-12
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/product/model")
@Tag(name = "IoT产品数据模型管理")
public class IotProductModelController {

    private final IotProductModelService productModelService;

    /**
     * 分页查询产品数据模型列表
     */
    @GetMapping("/page")
    @Operation(summary = "分页查询产品数据模型列表")
    public R<IPage<IotProductModelEntity>> getProductModelPage(SearchPage page, IotProductModelEntity model) {
        return R.ok(productModelService.getModelPage(page.toMpPage(), model));
    }

    /**
     * 根据产品ID查询数据模型列表
     */
    @GetMapping("/list/{productId}")
    @Operation(summary = "根据产品ID查询数据模型列表")
    public R<List<IotProductModelEntity>> getProductModelList(@PathVariable("productId") Long productId) {
        LambdaQueryWrapper<IotProductModelEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(IotProductModelEntity::getProductId, productId);
        return R.ok(productModelService.list(queryWrapper));
    }

    /**
     * 根据ID获取数据模型详情
     */
    @GetMapping("/{id}")
    @Operation(summary = "根据ID获取数据模型详情")
    public R<IotProductModelEntity> getProductModelById(@PathVariable("id") Long id) {
        return R.ok(productModelService.getById(id));
    }

    /**
     * 根据字段查询数据模型详情
     */
    @GetMapping("/details")
    @Operation(summary = "根据字段查询数据模型详情")
    public R<IotProductModelEntity> getProductModelDetails(
            @Parameter(description = "产品ID") @RequestParam(required = false) Long productId,
            @Parameter(description = "功能类型") @RequestParam(required = false) String funcType,
            @Parameter(description = "标识符") @RequestParam(required = false) String identifier) {

        LambdaQueryWrapper<IotProductModelEntity> queryWrapper = new LambdaQueryWrapper<>();

        // 使用条件构造器的条件参数
        queryWrapper.eq(productId != null, IotProductModelEntity::getProductId, productId)
                .eq(StrUtil.isNotBlank(funcType), IotProductModelEntity::getFuncType, funcType)
                .eq(StrUtil.isNotBlank(identifier), IotProductModelEntity::getIdentifier, identifier);

        return R.ok(productModelService.getOne(queryWrapper));
    }

    /**
     * 创建数据模型
     */
    @PostMapping
    @SysLog("创建数据模型")
    @Operation(summary = "创建数据模型")
    public R<Boolean> createProductModel(@RequestBody IotProductModelEntity model) {
        return R.ok(productModelService.save(model));
    }

    /**
     * 批量创建数据模型
     */
    @PostMapping("/batch")
    @SysLog("批量创建数据模型")
    @Operation(summary = "批量创建数据模型")
    public R<Boolean> batchCreateProductModel(@RequestBody List<IotProductModelEntity> models) {
        return R.ok(productModelService.saveBatch(models));
    }

    /**
     * 更新数据模型
     */
    @PutMapping
    @SysLog("更新数据模型")
    @Operation(summary = "更新数据模型")
    public R<Boolean> updateProductModel(@RequestBody IotProductModelEntity model) {
        return R.ok(productModelService.updateById(model));
    }

    /**
     * 删除数据模型
     */
    @DeleteMapping("/{id}")
    @SysLog("删除数据模型")
    @Operation(summary = "删除数据模型")
    public R<Boolean> removeProductModel(@PathVariable("id") Long id) {
        return R.ok(productModelService.removeById(id));
    }

    /**
     * 根据产品ID删除所有数据模型
     */
    @DeleteMapping("/product/{productId}")
    @SysLog("根据产品ID删除所有数据模型")
    @Operation(summary = "根据产品ID删除所有数据模型")
    public R<Boolean> removeProductModelByProductId(@PathVariable("productId") Long productId) {
        LambdaQueryWrapper<IotProductModelEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(IotProductModelEntity::getProductId, productId);
        return R.ok(productModelService.remove(queryWrapper));
    }
} 