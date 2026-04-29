package com.kitex.mom.mes.controller;


import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.common.query.utils.QueryGenerator;
import com.kitex.mom.mes.dto.MesWarehouseFeedDTO;
import com.kitex.mom.mes.dto.MesWarehouseFeedJudgeDTO;
import com.kitex.mom.mes.entity.MesWarehouseStockFeedJudgeEntity;
import com.kitex.mom.mes.service.MesWarehouseStockFeedJudgeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 线边库余料退料审批
 *
 * @author kitex
 * @since 2025-04-08
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mesWarehouseStockFeedJudge")
@Tag(description = "mesWarehouseStockFeedJudge", name = "箱码规则管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class MesWarehouseStockFeedJudgeController {

    private final MesWarehouseStockFeedJudgeService service;

    /**
     * 分页查询
     *
     * @param pageParam
     * @param query
     * @return
     */

    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('stockFeedJudge:show')")
    public R<IPage<MesWarehouseStockFeedJudgeEntity>> page(Page<MesWarehouseStockFeedJudgeEntity> pageParam, MesWarehouseStockFeedJudgeEntity query) {
        QueryWrapper<MesWarehouseStockFeedJudgeEntity> autoQueryWrapper = QueryGenerator.initQueryWrapper(query);
        autoQueryWrapper.lambda().orderByDesc(MesWarehouseStockFeedJudgeEntity::getCreateTime);
        return R.ok(service.page(pageParam, autoQueryWrapper));
    }

    @Operation(summary = "列表查询", description = "列表查询")
    @GetMapping("/list")
    public R<List<MesWarehouseStockFeedJudgeEntity>> list(MesWarehouseStockFeedJudgeEntity query) {
        QueryWrapper<MesWarehouseStockFeedJudgeEntity> autoQueryWrapper = QueryGenerator.initQueryWrapper(query);
        autoQueryWrapper.lambda().eq(MesWarehouseStockFeedJudgeEntity::getStatus, Boolean.TRUE)
                .orderByDesc(MesWarehouseStockFeedJudgeEntity::getCreateTime);
        return R.ok(service.list(autoQueryWrapper));
    }


    /**
     * 通过id查询箱码规则管理
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "通过id查询", description = "通过id查询")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('mesWarehouseStockFeedJudge:show')")
    public R<MesWarehouseStockFeedJudgeEntity> getById(@PathVariable("id") Long id) {
        return R.ok(service.getById(id));
    }

    /**
     * 新增箱码规则管理
     *
     * @param mesWarehouseStockFeedJudge 箱码规则管理
     * @return R
     */
    @Operation(summary = "新增箱码规则管理", description = "新增箱码规则管理")
    @SysLog("新增箱码规则管理")
    @PostMapping
    @PreAuthorize("@pms.hasPermission('mesWarehouseStockFeedJudge:add')")
    public R<Void> save(@RequestBody MesWarehouseStockFeedJudgeEntity mesWarehouseStockFeedJudge) {
        service.save(mesWarehouseStockFeedJudge);
        return R.ok();
    }

    /**
     * 修改箱码规则管理
     *
     * @param mesWarehouseStockFeedJudge 箱码规则管理
     * @return R
     */
    @Operation(summary = "修改箱码规则管理", description = "修改箱码规则管理")
    @SysLog("修改箱码规则管理")
    @PutMapping
    @PreAuthorize("@pms.hasPermission('mesWarehouseStockFeedJudge:edit')")
    public R<Void> updateById(@RequestBody MesWarehouseStockFeedJudgeEntity mesWarehouseStockFeedJudge) {
        service.updateById(mesWarehouseStockFeedJudge);
        return R.ok();
    }

    /**
     * 通过id删除箱码规则管理
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "通过id删除箱码规则管理", description = "通过id删除箱码规则管理")
    @SysLog("通过id删除箱码规则管理")
    @DeleteMapping
    @PreAuthorize("@pms.hasPermission('mesWarehouseStockFeedJudge:delete')")
    public R<Void> removeById(@RequestBody Long[] ids) {
        service.removeBatchByIds(CollUtil.toList(ids));
        return R.ok();
    }

    /**
     * 提交退料审批
     *
     * @param submitDTO 退料审批提交参数
     * @return R
     */
    @Operation(summary = "提交退料审批", description = "提交退料审批")
    @SysLog("提交退料审批")
    @PostMapping("/submit")
    @PreAuthorize("@pms.hasPermission('stockFeedJudge:submit')")
    public R<Void> submitReturnMaterial(@RequestBody MesWarehouseFeedDTO submitDTO) {
        service.submitReturnMaterial(submitDTO);
        return R.ok();
    }

    /**
     * 审批
     *
     * @param submitDTO 审批参数
     * @return R
     */
    @Operation(summary = "审批", description = "审批")
    @SysLog("审批")
    @PostMapping("/judge")
    @PreAuthorize("@pms.hasPermission('stockFeedJudge:judge')")
    public R<Void> judge(@RequestBody MesWarehouseFeedJudgeDTO submitDTO) {
        service.judge(submitDTO);
        return R.ok();
    }

}