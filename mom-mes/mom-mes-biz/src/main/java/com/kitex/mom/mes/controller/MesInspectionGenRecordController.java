package com.kitex.mom.mes.controller;


import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.util.R;
import com.kitex.common.query.utils.QueryGenerator;
import com.kitex.mom.mes.entity.MesInspectionGenRecordEntity;
import com.kitex.mom.mes.service.MesInspectionGenRecordService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 质检任务生成记录 管理
 *
 * @author kitex
 * @since 2024-02-06
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mesInspectionGenRecord")
@Tag(name = "质检任务生成记录管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class MesInspectionGenRecordController {

    private final MesInspectionGenRecordService mesInspectionGenRecordService;

    /**
     * 分页查询
     *
     * @param page  分页对象
     * @param query 质检任务生成记录
     * @return
     */

    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('mesInspectionGenRecord:show')")
    public R<IPage<MesInspectionGenRecordEntity>> getMesInspectionGenRecordPage(Page<MesInspectionGenRecordEntity> page, MesInspectionGenRecordEntity query) {
        QueryWrapper<MesInspectionGenRecordEntity> autoQueryWrapper = QueryGenerator.initQueryWrapper(query);
        return R.ok(mesInspectionGenRecordService.page(page, autoQueryWrapper));
    }

    /**
     * 通过id查询质检任务生成记录
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "通过id查询", description = "通过id查询")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('mesInspectionGenRecord:show')")
    public R getById(@PathVariable("id") Long id) {
        return R.ok(mesInspectionGenRecordService.getById(id));
    }

}
