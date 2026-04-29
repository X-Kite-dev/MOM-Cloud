package com.kitex.mom.qms.controller;

import cn.hutool.core.io.IoUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.util.R;
import com.kitex.common.query.enums.CommonQuery;
import com.kitex.mom.qms.dto.req.QualitySamplingStrategiesReq;
import com.kitex.mom.qms.entity.Frequency;
import com.kitex.mom.qms.entity.QmsQualitySamplingStrategiesEntity;
import com.kitex.mom.qms.service.QualitySamplingStrategiesService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.validation.constraints.NotNull;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * @author kitex
 * @date 2022/11/29
 */
@Tag(description = "qualityStandardSamplingStrategies", name = "抽样策略")
@RestController
@RequestMapping("/standard/samplingStrategies")
@RequiredArgsConstructor
public class QualityStandardSamplingStrategiesController {

    private final QualitySamplingStrategiesService service;

    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('samplingStrategy:show')")
    public R<Page<QmsQualitySamplingStrategiesEntity>> page(Page<QmsQualitySamplingStrategiesEntity> page,
                                                            QualitySamplingStrategiesReq query, CommonQuery commonQuery) {
        return R.ok(service.page(page, query, commonQuery));
    }

    @Operation(summary = "单条", description = "单条")
    @GetMapping("/findById")
    @PreAuthorize("@pms.hasPermission('samplingStrategy:show')")
    public R<QmsQualitySamplingStrategiesEntity> findById(@NotNull(message = "主键不可为空") Long id) {
        return R.ok(service.getById(id));
    }

    @Operation(summary = "列表(全部数据)", description = "列表(全部数据)")
    @GetMapping("/list")
    @PreAuthorize("@pms.hasPermission('samplingStrategy:show')")
    public R<List<QmsQualitySamplingStrategiesEntity>> list() {
        return R.ok(service.list());
    }

    @Operation(summary = "添加", description = "添加")
    @PostMapping("/add")
    @PreAuthorize("@pms.hasPermission('samplingStrategy:add')")
    public R<Void> add(@RequestBody QmsQualitySamplingStrategiesEntity param) {
        service.addData(param);
        return R.ok();
    }

    @Operation(summary = "更新", description = "更新")
    @PostMapping("/update")
    @PreAuthorize("@pms.hasPermission('samplingStrategy:edit')")
    public R<Void> update(@RequestBody QmsQualitySamplingStrategiesEntity param) {
        service.updateById(param);
        return R.ok();
    }

    @Operation(summary = "删除", description = "删除")
    @DeleteMapping("/remove")
    @PreAuthorize("@pms.hasPermission('samplingStrategy:delete')")
    public R<Void> remove(@NotNull(message = "主键不可为空") Long id) {
        service.removeById(id);
        return R.ok();
    }

    @SneakyThrows
    @Operation(summary = "下载模板", description = "下载模板")
    @GetMapping("/downTemplate")
    @PreAuthorize("@pms.hasPermission('samplingStrategy:import')")
    public ResponseEntity<byte[]> downTemplate() {

        // 设置header信息
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.setContentDispositionFormData("attachment",
                new String("AQL模板.xlsx".getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1));

        ResourceLoader resourceLoader = new DefaultResourceLoader();
        InputStream inputStream = resourceLoader.getResource("classpath:templates/AqlTemplate.xlsx").getInputStream();

        return new ResponseEntity<>(IoUtil.readBytes(inputStream), headers, HttpStatus.OK);
    }

    @Operation(summary = "读取导入抽样配置表格并返回内容", description = "读取导入抽样配置表格并返回内容")
    @PostMapping("/importExcel")
    @PreAuthorize("@pms.hasPermission('samplingStrategy:import')")
    public R<List<Frequency>> importExcel(MultipartFile file) {
        return R.ok(service.importExcel(file));
    }

    @Operation(summary = "导出", description = "导出")
    @GetMapping("/down")
    @PreAuthorize("@pms.hasPermission('samplingStrategy:export')")
    public ResponseEntity<byte[]> down(Long id) {
        return service.down(id);
    }

    @Operation(summary = "批量导出", description = "批量导出")
    @PostMapping("/downBatch")
    @PreAuthorize("@pms.hasPermission('samplingStrategy:export')")
    public ResponseEntity<byte[]> downBatch(@RequestBody List<Long> ids) {
        return service.down(ids);
    }

}
