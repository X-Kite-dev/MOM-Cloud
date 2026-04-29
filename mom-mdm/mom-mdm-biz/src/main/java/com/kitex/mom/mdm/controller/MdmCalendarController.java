package com.kitex.mom.mdm.controller;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.mdm.entity.MdmCalendarEntity;
import com.kitex.mom.mdm.service.MdmCalendarService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * 工作日历
 *
 * @author kitex
 * @date 2025-02-17 11:58:28
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mdmCalendar")
@Tag(description = "mdmCalendar", name = "工作日历管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class MdmCalendarController {

    private final MdmCalendarService mdmCalendarService;

    /**
     * 分页查询
     *
     * @param page        分页对象
     * @param mdmCalendar 工作日历
     * @return
     */
    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('mdm_mdmCalendar_view')")
    public R getMdmCalendarPage(@ParameterObject SearchPage page, @ParameterObject MdmCalendarEntity mdmCalendar) {
        LambdaQueryWrapper<MdmCalendarEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(Objects.nonNull(mdmCalendar.getTime()), MdmCalendarEntity::getTime, mdmCalendar.getTime());
        wrapper.eq(StrUtil.isNotBlank(mdmCalendar.getStatus()), MdmCalendarEntity::getStatus, mdmCalendar.getStatus());
        return R.ok(mdmCalendarService.page(page.toMpPage(), wrapper));
    }

    /**
     * 查询工作日历列表
     *
     * @param mdmCalendar 工作日历实体
     * @return R
     */
    @Operation(summary = "查询工作日历列表", description = "查询工作日历列表")
    @GetMapping("/list")
    @PreAuthorize("@pms.hasPermission('mdm_mdmCalendar_view')")
    public R<List<MdmCalendarEntity>> getList(MdmCalendarEntity mdmCalendar) {
        return R.ok(mdmCalendarService.getList(mdmCalendar));
    }

    /**
     * 查询工作日历
     *
     * @param mdmCalendar 工作日历实体
     * @return R
     */
    @Operation(summary = "查询工作日历", description = "查询工作日历")
    @GetMapping
    @PreAuthorize("@pms.hasPermission('mdm_mdmCalendar_view')")
    public R getDetail(MdmCalendarEntity mdmCalendar) {
        return R.ok(mdmCalendarService.getOne(Wrappers.query(mdmCalendar), false));
    }

    /**
     * 新增或修改工作日历
     *
     * @param mdmCalendar 工作日历
     * @return R
     */
    @Operation(summary = "新增或修改工作日历", description = "新增或修改工作日历")
    @SysLog("新增或修改工作日历")
    @PostMapping
    @PreAuthorize("@pms.hasPermission('mdm_mdmCalendar_add')")
    public R save(@RequestBody MdmCalendarEntity mdmCalendar) {
        return R.ok(mdmCalendarService.saveOrUpdate(mdmCalendar));
    }

    /**
     * 通过id删除工作日历
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "通过id删除工作日历", description = "通过id删除工作日历")
    @SysLog("通过id删除工作日历")
    @DeleteMapping
    @PreAuthorize("@pms.hasPermission('mdm_mdmCalendar_del')")
    public R removeById(@RequestBody Long[] ids) {
        return R.ok(mdmCalendarService.removeBatchByIds(CollUtil.toList(ids)));
    }


    /**
     * 导出excel 表格
     *
     * @param mdmCalendar 查询条件
     * @param ids         导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('mdm_mdmCalendar_export')")
    public List<MdmCalendarEntity> export(MdmCalendarEntity mdmCalendar, Long[] ids) {
        return mdmCalendarService.list(Wrappers.lambdaQuery(mdmCalendar).in(ArrayUtil.isNotEmpty(ids), MdmCalendarEntity::getId, ids));
    }

    /**
     * 按日期范围查询工作日历（供排产工时计算使用）
     *
     * @param start 开始日期（含）
     * @param end   结束日期（含）
     * @return 日历条目列表，status: 0=上班 1=休息
     */
    @Operation(summary = "按日期范围查询工作日历", description = "按日期范围查询工作日历，供排产工时计算使用")
    @GetMapping("/listByRange")
    public R<List<MdmCalendarEntity>> listByRange(
            @RequestParam("start") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam("end") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end) {
        LambdaQueryWrapper<MdmCalendarEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.between(MdmCalendarEntity::getTime, start, end)
               .orderByAsc(MdmCalendarEntity::getTime);
        return R.ok(mdmCalendarService.list(wrapper));
    }
}