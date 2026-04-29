package com.kitex.mom.qms.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.util.R;
import com.kitex.mom.qms.dto.req.QmsBadHandleReq;
import com.kitex.mom.qms.dto.req.QmsBadJudgeReq;
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
    public R<QmsBadInfoDetailEntity> queryDetailById(Long id) {
        return R.ok(service.queryDetailById(id));
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

}
