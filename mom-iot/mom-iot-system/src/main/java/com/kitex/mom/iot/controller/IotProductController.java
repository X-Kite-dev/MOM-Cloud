package com.kitex.mom.iot.controller;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.iot.dto.IotProductDTO;
import com.kitex.mom.iot.entity.IotProductEntity;
import com.kitex.mom.iot.service.IotProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * IoT产品控制器
 *
 * @author system
 * @date 2025-03-12
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/product")
@Tag(name = "IoT产品管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class IotProductController {

    private final IotProductService productService;

    /**
     * 分页查询产品列表
     */
    @GetMapping("/page")
    @Operation(summary = "分页查询产品列表")
    public R<IPage<IotProductEntity>> getProductPage(SearchPage page, IotProductEntity product) {
        return R.ok(productService.getProductPage(page.toMpPage(), product));
    }


    /**
     * 分页查询产品列表
     */
    @GetMapping("/list")
    @Operation(summary = "查询产品列表")
    public R<List<IotProductEntity>> list(IotProductEntity product) {
        return R.ok(productService.list());
    }

    /**
     * 根据ID获取产品详情
     *
     * @param id 产品ID
     * @return 产品详情
     */
    @GetMapping("/{id}")
    @Operation(summary = "根据ID获取产品详情")
    @PreAuthorize("@pms.hasPermission('iot_product_view')")
    public R<IotProductEntity> getProductById(@PathVariable("id") Long id) {
        return R.ok(productService.getById(id));
    }

    /**
     * 根据字段查询产品详情
     */
    @GetMapping("/details")
    @Operation(summary = "根据字段查询产品详情")
    public R<IotProductEntity> getProductDetails(
            @Parameter(description = "产品编码") @RequestParam(required = false) String productCode,
            @Parameter(description = "产品名称") @RequestParam(required = false) String productName) {

        LambdaQueryWrapper<IotProductEntity> queryWrapper = new LambdaQueryWrapper<>();

        // 使用条件构造器的条件参数
        queryWrapper.eq(StrUtil.isNotBlank(productCode), IotProductEntity::getProductCode, productCode)
                .eq(StrUtil.isNotBlank(productName), IotProductEntity::getProductName, productName);

        return R.ok(productService.getOne(queryWrapper));
    }

    /**
     * 创建产品
     *
     * @param productDTO 产品信息
     * @return 操作结果
     */
    @PostMapping
    @Operation(summary = "创建产品")
    @SysLog("创建IoT产品")
    @PreAuthorize("@pms.hasPermission('iot_product_add')")
    public R<Boolean> createProduct(@RequestBody IotProductDTO productDTO) {
        return R.ok(productService.createProduct(productDTO));
    }

    /**
     * 更新产品
     *
     * @param productDTO 产品信息
     * @return 操作结果
     */
    @PutMapping
    @Operation(summary = "更新产品")
    @SysLog("更新IoT产品")
    @PreAuthorize("@pms.hasPermission('iot_product_edit')")
    public R<Boolean> updateProduct(@RequestBody IotProductDTO productDTO) {
        return R.ok(productService.updateProduct(productDTO));
    }

    /**
     * 删除产品
     *
     * @param id 产品ID
     * @return 操作结果
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "删除产品")
    @SysLog("删除IoT产品")
    @PreAuthorize("@pms.hasPermission('iot_product_del')")
    public R<Boolean> removeProduct(@PathVariable("id") Long id) {
        return R.ok(productService.removeProduct(id));
    }
} 