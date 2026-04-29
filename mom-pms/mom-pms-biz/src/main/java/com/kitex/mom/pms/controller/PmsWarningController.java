package com.kitex.mom.pms.controller;


import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.pms.entity.PmsWarningEntity;
import com.kitex.mom.pms.service.PmsWarningService;
import com.kitex.mom.pms.vo.PmsWarningExcelVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 采购异常表
 *
 * @author kitex
 * @date 2025-01-08 10:29:32
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/pmsWarning")
@Tag(description = "pmsWarning", name = "采购异常表管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class PmsWarningController {

    private final PmsWarningService pmsWarningService;

    /**
     * 分页查询采购异常
     *
     * @param page       分页对象
     * @param pmsWarning 采购异常表
     * @return
     */

    @Operation(summary = "分页查询采购异常", description = "分页查询采购异常")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('pms_pmsWarning_view')")
    public R<Page<PmsWarningEntity>> getPmsWarningPage(@ParameterObject SearchPage page, @ParameterObject PmsWarningEntity pmsWarning) {
        QueryWrapper<PmsWarningEntity> wrapper = Wrappers.query();
        wrapper.lambda()
                .like(StrUtil.isNotBlank(pmsWarning.getPmsPlanNo()), PmsWarningEntity::getPmsPlanNo, pmsWarning.getPmsPlanNo())
                .like(StrUtil.isNotBlank(pmsWarning.getMatCode()), PmsWarningEntity::getMatCode, pmsWarning.getMatCode())
                .like(StrUtil.isNotBlank(pmsWarning.getMatSpec()), PmsWarningEntity::getMatSpec, pmsWarning.getMatSpec())
                .eq(Objects.nonNull(pmsWarning.getStatus()), PmsWarningEntity::getStatus, pmsWarning.getStatus())
                .orderByAsc(PmsWarningEntity::getStatus);
        return R.ok(pmsWarningService.page(page.toMpPage(), wrapper));
    }

    /**
     * 查询各采购异常状态的数量
     *
     * @return
     */

    @Operation(summary = "查询各采购异常状态的数量", description = "查询各采购异常状态的数量")
    @GetMapping("/getCount")
    public R<Map<Boolean, Long>> getCount(@ParameterObject PmsWarningEntity pmsWarning) {
        QueryWrapper<PmsWarningEntity> wrapper = Wrappers.query();
        wrapper.lambda().select(PmsWarningEntity::getStatus)
                .like(StrUtil.isNotBlank(pmsWarning.getPmsPlanNo()), PmsWarningEntity::getPmsPlanNo, pmsWarning.getPmsPlanNo())
                .like(StrUtil.isNotBlank(pmsWarning.getMatSpec()), PmsWarningEntity::getMatSpec, pmsWarning.getMatSpec())
                .like(StrUtil.isNotBlank(pmsWarning.getMatCode()), PmsWarningEntity::getMatCode, pmsWarning.getMatCode());
        return R.ok(pmsWarningService.list(wrapper)
                .stream()
                .collect(Collectors.groupingBy(PmsWarningEntity::getStatus, Collectors.counting())));
    }

    /**
     * 通过id查询采购异常表
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "通过id查询", description = "通过id查询")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('pms_pmsWarning_view')")
    public R<PmsWarningEntity> getById(@PathVariable("id") Long id) {
        return R.ok(pmsWarningService.getById(id));
    }

    /**
     * 异常确认
     *
     * @param pmsWarning 采购异常表
     * @return R
     */
    @Operation(summary = "异常确认", description = "异常确认")
    @SysLog("异常确认")
    @PutMapping
    @PreAuthorize("@pms.hasPermission('pms_pmsWarning_edit')")
    public R<Boolean> updateById(@RequestBody PmsWarningEntity pmsWarning) {
        return R.ok(pmsWarningService.updateById(pmsWarning));
    }

    /**
     * 通过id删除采购异常表
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "通过id删除采购异常表", description = "通过id删除采购异常表")
    @SysLog("通过id删除采购异常表")
    @DeleteMapping
    @PreAuthorize("@pms.hasPermission('pms_pmsWarning_del')")
    public R<Boolean> removeById(@RequestBody Long[] ids) {
        return R.ok(pmsWarningService.removeBatchByIds(CollUtil.toList(ids)));
    }

    /**
     * 导出excel 表格
     *
     * @param pmsWarning 查询条件
     * @param ids        导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('pms_pmsWarning_export')")
    public List<PmsWarningExcelVO> export(PmsWarningEntity pmsWarning, Long[] ids) {
        return pmsWarningService.export(pmsWarning, ids);
    }
}