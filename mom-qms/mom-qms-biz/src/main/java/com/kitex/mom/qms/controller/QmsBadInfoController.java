package com.kitex.mom.qms.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.util.R;
import com.kitex.mom.qms.dto.req.QmsBadHandleReq;
import com.kitex.mom.qms.dto.req.QmsBadJudgeReq;
import com.kitex.mom.qms.dto.req.QmsBadVerifyReq;
import com.kitex.mom.qms.entity.QmsBadInfoDetailEntity;
import com.kitex.mom.qms.entity.QmsBadInfoEntity;
import com.kitex.mom.qms.service.QmsBadInfoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 不合格品管理
 *
 * @author kitex
 * @since 2025-01-21
 */
@Tag(description = "badInfo", name = "不合格品管理")
@RestController
@RequestMapping("/bad")
@RequiredArgsConstructor
public class QmsBadInfoController {

    private final QmsBadInfoService service;

    @com.kitex.common.security.annotation.Inner @GetMapping("/internal/mes-decision/{id}")
    public R<com.kitex.mom.qms.dto.QmsMesCaseDecision> mesDecision(@PathVariable Long id){return R.ok(service.mesDecision(id));}
    @PostMapping("/mes-execute") @PreAuthorize("@pms.hasPermission('bad:mes:execute')")
    public R<Void> executeMes(@RequestBody com.kitex.mom.qms.dto.req.QmsBadMesExecuteReq req){service.executeMes(req);return R.ok();}
    @PostMapping("/detail/{id}/sync-mes") @PreAuthorize("@pms.hasPermission('bad:sync')")
    public R<Void> syncMes(@PathVariable Long id){service.syncMes(id);return R.ok();}
    @GetMapping("/detail/{id}/mes-context") @PreAuthorize("@pms.hasPermission('bad:mes:execute','bad:verify','bad:sync','bad:handle')")
    public R<Map<String,Object>> mesContext(@PathVariable Long id){return R.ok(service.mesContext(id));}
    private final com.kitex.mom.mes.api.feign.RemoteMesInventoryQualityCaseService mesQualityCases;

    @GetMapping("/from-mes/{caseId}")
    @PreAuthorize("@pms.hasPermission('bad:add')")
    public R<com.kitex.mom.mes.entity.MesInventoryQualityCaseEntity> previewMes(@PathVariable Long caseId) {
        return mesQualityCases.source(caseId);
    }

    @PostMapping("/from-mes/{caseId}")
    @PreAuthorize("@pms.hasPermission('bad:add')")
    public R<Void> fromMes(@PathVariable Long caseId) {
        service.add(new QmsBadInfoEntity().setResourceType("MES_WIP_TRANSFER").setResourceTaskId(caseId));
        return R.ok();
    }

    @Operation(summary = "不合格品管理分页", description = "不合格品管理分页")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('bad:show')")
    public R<IPage<QmsBadInfoEntity>> page(Page<QmsBadInfoEntity> pageParam, QmsBadInfoEntity query) {
        return R.ok(service.queryPage(pageParam, query));
    }

    @Operation(summary = "不合格品管理分页", description = "不合格品管理分页")
    @GetMapping("/detail/page")
    @PreAuthorize("@pms.hasPermission('bad:detail:show')")
    public R<IPage<QmsBadInfoDetailEntity>> age(Page<QmsBadInfoDetailEntity> pageParam, QmsBadInfoDetailEntity query) {
        return R.ok(service.queryDetailPage(pageParam, query));
    }

    @Operation(summary = "不合格品管理数量", description = "不合格品管理数量")
    @GetMapping("/detail/getCount")
    @PreAuthorize("@pms.hasPermission('bad:detail:show')")
    public R<Map<String, Long>> age(QmsBadInfoDetailEntity query) {
        return R.ok(service.detailCount(query));
    }

    @Operation(summary = "获取详情", description = "获取详情")
    @GetMapping("/detail/getObj")
    @PreAuthorize("@pms.hasPermission('bad:detail:show')")
    public R<QmsBadInfoDetailEntity> queryDetailById(Long id) {
        return R.ok(service.queryDetailById(id));
    }

    @Operation(summary = "获取NCR主记录")
    @GetMapping("/getObj")
    @PreAuthorize("@pms.hasPermission('bad:show')")
    public R<QmsBadInfoEntity> queryById(Long id) {
        return R.ok(service.getById(id));
    }

    @Operation(summary = "不合格品填报", description = "不合格品填报")
    @PostMapping("/add")
    @PreAuthorize("@pms.hasPermission('bad:add')")
    public R<Void> add(@RequestBody QmsBadInfoEntity info) {
        service.add(info);
        return R.ok();
    }

    @Operation(summary = "判定", description = "判定")
    @PostMapping("/judge")
    @PreAuthorize("@pms.hasPermission('bad:judge')")
    public R<Void> judge(@RequestBody QmsBadJudgeReq req) {
        service.judge(req);
        return R.ok();
    }

    @Operation(summary = "处置", description = "处置")
    @PostMapping("/handle")
    @PreAuthorize("@pms.hasPermission('bad:handle')")
    public R<Void> handle(@RequestBody QmsBadHandleReq req) {
        service.handle(req);
        return R.ok();
    }

    @Operation(summary = "不合格处置独立验证")
    @PostMapping("/verify")
    @PreAuthorize("@pms.hasPermission('bad:verify')")
    public R<Void> verify(@RequestBody QmsBadVerifyReq req) {
        service.verify(req);
        return R.ok();
    }

    @Operation(summary = "重试NCR库存隔离")
    @PostMapping("/{id}/retry-hold")
    @PreAuthorize("@pms.hasPermission('bad:sync')")
    public R<Void> retryHold(@PathVariable Long id) {
        service.retryHold(id);
        return R.ok();
    }

    @Operation(summary = "重试MRB处置库存同步")
    @PostMapping("/detail/{id}/retry-disposition")
    @PreAuthorize("@pms.hasPermission('bad:sync')")
    public R<Void> retryDisposition(@PathVariable Long id) {
        service.retryDisposition(id);
        return R.ok();
    }

    @Operation(summary = "NCR不可变履历")
    @GetMapping("/{id}/operations")
    @PreAuthorize("@pms.hasPermission('bad:show')")
    public R<?> operations(@PathVariable Long id) {
        return R.ok(service.operations(id));
    }

}
