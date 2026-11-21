package com.kitex.mom.qms.controller;

import cn.hutool.core.io.IoUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.constant.CommonConstants;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.query.enums.CommonQuery;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.qms.dto.req.QualityStandardQueryReq;
import com.kitex.mom.qms.dto.req.QualityStandardReq;
import com.kitex.mom.qms.dto.req.QualityStandardStatusReq;
import com.kitex.mom.qms.dto.resp.QmsQualityStandardDTO;
import com.kitex.mom.qms.entity.QmsQualityStandardEntity;
import com.kitex.mom.qms.entity.QmsQualityStandardItemEntity;
import com.kitex.mom.qms.enums.InspectionTaskTypeEnum;
import com.kitex.mom.qms.service.QualityStandardItemService;
import com.kitex.mom.qms.service.QualityStandardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.validation.constraints.NotNull;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 质检标准
 *
 * @author kitex
 * @date 2022/12/01
 */
@Validated
@Tag(description = "qualityStandard", name = "3.质检标准")
@RestController
@RequestMapping("/standard")
@RequiredArgsConstructor
public class QualityStandardController {

    private final QualityStandardService service;

    private final QualityStandardItemService itemService;

    @Operation(summary = "质检标准分页", description = "质检标准分页")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('standard:show')")
    public R<Page<QmsQualityStandardEntity>> page(Page<QmsQualityStandardEntity> pageParam, QualityStandardQueryReq query,
                                                  CommonQuery commonQuery) {
        return R.ok(service.page(pageParam, query, commonQuery));
    }

    @Operation(summary = "质检标准查询单条", description = "质检标准查询单条")
    @GetMapping("/findById")
    @PreAuthorize("@pms.hasPermission('standard:show')")
    public R<QmsQualityStandardDTO> findById(@NotNull(message = "主键不可为空") Long id) {
        return R.ok(service.findById(id));
    }

    @Operation(summary = "质检标准查询单条", description = "质检标准查询单条")
    @GetMapping("/list")
    public R<List<QmsQualityStandardEntity>> list(@ParameterObject QmsQualityStandardEntity query) {
        return R.ok(service.list(new LambdaQueryWrapper<QmsQualityStandardEntity>()
                .eq(StrUtil.isNotBlank(query.getInspectionType()), QmsQualityStandardEntity::getInspectionType, query.getInspectionType())
                .eq(StrUtil.isNotBlank(query.getMatCode()), QmsQualityStandardEntity::getMatCode, query.getMatCode())
                .eq(QmsQualityStandardEntity::getPublished, 1)));
    }

    @Operation(summary = "匹配物料的质检标准", description = "匹配物料的质检标准")
    @GetMapping("/getRandomStandard")
    public R<QmsQualityStandardEntity> getRandomStandard(@ParameterObject QmsQualityStandardEntity query) {
        return R.ok(service.getOne(new LambdaQueryWrapper<QmsQualityStandardEntity>()
                .eq(QmsQualityStandardEntity::getInspectionType, query.getInspectionType())
                .eq(QmsQualityStandardEntity::getMatCode, query.getMatCode())
                .eq(QmsQualityStandardEntity::getPublished, 1)
                .last(" limit 1")));
    }

    @Inner
    @GetMapping("/internal/patrol-standard")
    public R<QmsQualityStandardEntity> getPatrolStandard(
            @RequestHeader(CommonConstants.TENANT_ID) Long tenant,
            @RequestHeader(CommonConstants.FACTORY_CODE) String factory,
            @RequestParam("matCode") String matCode) {
        if (tenant == null || tenant <= 0 || !tenant.equals(TenantContextHolder.getTenantId())
                || TenantContextHolder.getTenantSkip() || StrUtil.isBlank(factory)
                || !factory.equals(FactoryContextHolder.getFactoryCode())
                || Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())
                || StrUtil.isBlank(matCode)) {
            throw new CheckedException("中检标准查询须指定实际租户、工厂和物料");
        }
        return R.ok(service.getOne(new LambdaQueryWrapper<QmsQualityStandardEntity>()
                .eq(QmsQualityStandardEntity::getTenantId, tenant)
                .eq(QmsQualityStandardEntity::getFactoryCode, factory)
                .eq(QmsQualityStandardEntity::getInspectionType, InspectionTaskTypeEnum.PROCEDURE.name())
                .eq(QmsQualityStandardEntity::getMatCode, matCode)
                .eq(QmsQualityStandardEntity::getPublished, 1)
                .orderByDesc(QmsQualityStandardEntity::getId)
                .last("LIMIT 1")));
    }


    @Operation(summary = "质检标准添加", description = "质检标准添加")
    @PostMapping("/saveOrUpdate")
    @PreAuthorize("@pms.hasPermission('standard:add')")
    public R<Void> add(@RequestBody QualityStandardReq param) {
        service.saveOrUpdate(param);
        return R.ok();
    }

    @Operation(summary = "修改状态", description = "质检标准添加")
    @PostMapping("/changeStatus")
    @PreAuthorize("@pms.hasPermission('standard:add')")
    public R<Void> changeStatus(@RequestBody QualityStandardStatusReq req) {
        service.changeStatus(req);
        return R.ok();
    }

    @Operation(summary = "修改状态", description = "质检标准添加")
    @PostMapping("/copy")
    @PreAuthorize("@pms.hasPermission('standard:copy')")
    public R<Void> copy(Long id) {
        service.copy(id);
        return R.ok();
    }

    @Operation(summary = "质检标准删除", description = "质检标准删除")
    @DeleteMapping("/remove")
    @PreAuthorize("@pms.hasPermission('standard:delete')")
    public R<Void> remove(@NotNull(message = "主键不可为空") Long id) {
        service.remove(id);
        return R.ok();
    }

    /**
     * -------质检项--------
     */

    @Operation(summary = "质检项分页", description = "质检项分页")
    @GetMapping("/item/page")
    public R<Page<QmsQualityStandardItemEntity>> itemPage(Page<QmsQualityStandardItemEntity> pageParam,
                                                          QmsQualityStandardItemEntity query) {
        return R.ok(itemService.page(pageParam, query));
    }

    @Operation(summary = "质检项查询单条", description = "质检项查询单条")
    @GetMapping("/item/findById")
    public R<QmsQualityStandardItemEntity> findItemById(@NotNull(message = "主键不可为空") Long id) {
        return R.ok(itemService.getById(id));
    }

    @Operation(summary = "质检项查询列表(全部数据)", description = "质检项查询列表(全部数据)")
    @GetMapping("/item/list")
    public R<List<QmsQualityStandardItemEntity>> itemList() {
        return R.ok(itemService.list());
    }

    @Operation(summary = "质检项添加", description = "质检项添加")
    @PostMapping("/item/add")
    public R<Void> addItem(@RequestBody QmsQualityStandardItemEntity param) {
        itemService.save(param);
        return R.ok();
    }

    @Operation(summary = "质检项更新", description = "质检项更新")
    @PostMapping("/item/update")
    public R<Void> updateItem(@RequestBody QmsQualityStandardItemEntity param) {
        itemService.updateById(param);
        return R.ok();
    }

    @Operation(summary = "质检项删除", description = "质检项删除")
    @DeleteMapping("/item/remove")
    public R<Void> removeItem(@NotNull(message = "主键不可为空") Long id) {
        itemService.removeById(id);
        return R.ok();
    }

    @SneakyThrows
    @Operation(summary = "下载模板", description = "下载模板")
    @GetMapping("/downTemplate")
    public ResponseEntity<byte[]> downTemplate() {

        // 设置header信息
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.setContentDispositionFormData("attachment",
                new String("质检配置模板.xlsx".getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1));

        ResourceLoader resourceLoader = new DefaultResourceLoader();
        InputStream inputStream = resourceLoader.getResource("classpath:templates/QualityStandardTemplate.xlsx")
                .getInputStream();

        return new ResponseEntity<>(IoUtil.readBytes(inputStream), headers, HttpStatus.OK);
    }

    @Operation(summary = "读取导入质检配置表格并返回内容", description = "读取导入质检配置表格并返回内容")
    @PostMapping("/importExcel")
    public R<List<QmsQualityStandardItemEntity>> importExcel(MultipartFile file) {
        return R.ok(service.importExcel(file));
    }

    @Operation(summary = "导出", description = "导出")
    @GetMapping("/down")
    @PreAuthorize("@pms.hasPermission('standard:export')")
    public ResponseEntity<byte[]> down(Long id) {
        return service.down(id);
    }

    @Operation(summary = "批量导出", description = "批量导出")
    @PostMapping("/downBatch")
    @PreAuthorize("@pms.hasPermission('standard:export')")
    public ResponseEntity<byte[]> downBatch(@RequestBody List<Long> ids) {
        return service.down(ids);
    }

}
