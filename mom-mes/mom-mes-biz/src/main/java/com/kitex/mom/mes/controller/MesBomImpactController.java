package com.kitex.mom.mes.controller;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.security.annotation.Inner;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.dto.MdmBomImpact;
import com.kitex.mom.mdm.dto.MdmBomImpactPages;
import com.kitex.mom.mdm.dto.MdmFactoryAccessCheck;
import com.kitex.mom.mdm.feign.RemoteMdmFactoryAccessService;
import com.kitex.mom.mes.entity.MesMaterialListEntity;
import com.kitex.mom.mes.entity.MesProductionOrderEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.mapper.MesMaterialListMapper;
import com.kitex.mom.mes.mapper.MesProductionOrderMapper;
import com.kitex.mom.mes.mapper.MesTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@RestController
@RequiredArgsConstructor
@RequestMapping("/mesBomImpact/internal")
public class MesBomImpactController {
    private final MesTaskMapper taskMapper;
    private final MesProductionOrderMapper orderMapper;
    private final MesMaterialListMapper materialMapper;
    private final RemoteMdmFactoryAccessService accessService;

    @Inner
    @PostMapping("/page")
    @PreAuthorize("isAuthenticated() and @pms.hasPermission('mdm_bomEcn_view')")
    public R<MdmBomImpact.Batch> page(@RequestBody MdmBomImpact.PageQuery q) {
        MdmBomImpactPages.validate(q, Set.of("MES_TASK", "MES_ORDER", "MES_MATERIAL_LIST"));
        var access = accessService.check(new MdmFactoryAccessCheck().setFactoryCode(q.getFactoryCode())
                .setUserId(SecurityUtils.getUser().getId()).setRequiredPermission("read"));
        if (access == null || !access.isOk() || !Boolean.TRUE.equals(access.getData()))
            throw new CheckedException("无法核验BOM影响分析工厂权限");
        Long upper = q.getUpperId();
        if ("MES_TASK".equals(q.getDomain())) {
            if (upper == null) {
                var last = taskMapper.selectOne(new LambdaQueryWrapper<MesTaskEntity>().select(MesTaskEntity::getId)
                        .eq(MesTaskEntity::getFactoryCode, q.getFactoryCode()).orderByDesc(MesTaskEntity::getId).last("LIMIT 1"));
                upper = last == null ? 0L : last.getId();
            }
            var rows = taskMapper.selectList(new LambdaQueryWrapper<MesTaskEntity>().eq(MesTaskEntity::getFactoryCode, q.getFactoryCode())
                    .gt(MesTaskEntity::getId, MdmBomImpactPages.after(q)).le(MesTaskEntity::getId, upper)
                    .and(w -> {
                        w.in(MesTaskEntity::getBomId, q.getBomIds());
                        for (Long id : q.getBomIds()) w.or().like(MesTaskEntity::getBomSnapshot, id.toString());
                    })
                    .orderByAsc(MesTaskEntity::getId).last("LIMIT " + (MdmBomImpactPages.PAGE_SIZE + 1)));
            return R.ok(MdmBomImpactPages.batch(q, upper, rows, MesTaskEntity::getId, row -> MdmBomImpactPages.row(q,
                    row.getId(), row.getCode(), row.getStatus(), JSONUtil.toJsonStr(row), row.getBomSnapshot(), row.getBomId())));
        }
        if ("MES_ORDER".equals(q.getDomain())) {
            if (upper == null) {
                var last = orderMapper.selectOne(new LambdaQueryWrapper<MesProductionOrderEntity>().select(MesProductionOrderEntity::getId)
                        .eq(MesProductionOrderEntity::getFactoryCode, q.getFactoryCode()).orderByDesc(MesProductionOrderEntity::getId).last("LIMIT 1"));
                upper = last == null ? 0L : last.getId();
            }
            var rows = orderMapper.selectList(new LambdaQueryWrapper<MesProductionOrderEntity>().eq(MesProductionOrderEntity::getFactoryCode, q.getFactoryCode())
                    .gt(MesProductionOrderEntity::getId, MdmBomImpactPages.after(q)).le(MesProductionOrderEntity::getId, upper)
                    .in(MesProductionOrderEntity::getBomId, q.getBomIds().stream().map(String::valueOf).toList())
                    .orderByAsc(MesProductionOrderEntity::getId).last("LIMIT " + (MdmBomImpactPages.PAGE_SIZE + 1)));
            return R.ok(MdmBomImpactPages.batch(q, upper, rows, MesProductionOrderEntity::getId, row -> MdmBomImpactPages.row(q,
                    row.getId(), row.getOrderNo(), row.getStatus(), JSONUtil.toJsonStr(row), null, row.getBomId())));
        }
        if (upper == null) {
            var last = materialMapper.selectOne(new LambdaQueryWrapper<MesMaterialListEntity>().select(MesMaterialListEntity::getId)
                    .eq(MesMaterialListEntity::getFactoryCode, q.getFactoryCode()).orderByDesc(MesMaterialListEntity::getId).last("LIMIT 1"));
            upper = last == null ? 0L : last.getId();
        }
        var rows = materialMapper.selectList(new LambdaQueryWrapper<MesMaterialListEntity>().eq(MesMaterialListEntity::getFactoryCode, q.getFactoryCode())
                .gt(MesMaterialListEntity::getId, MdmBomImpactPages.after(q)).le(MesMaterialListEntity::getId, upper)
                .and(w -> {
                    w.in(MesMaterialListEntity::getBomId, q.getBomIds());
                    for (Long id : q.getBomIds()) w.or().like(MesMaterialListEntity::getBomSnapshot, id.toString());
                })
                .orderByAsc(MesMaterialListEntity::getId).last("LIMIT " + (MdmBomImpactPages.PAGE_SIZE + 1)));
        return R.ok(MdmBomImpactPages.batch(q, upper, rows, MesMaterialListEntity::getId, row -> MdmBomImpactPages.row(q,
                row.getId(), row.getTaskCode(), null, JSONUtil.toJsonStr(row), row.getBomSnapshot(), row.getBomId())));
    }

    @Inner
    @PostMapping("/query")
    @PreAuthorize("isAuthenticated() and @pms.hasPermission('mdm_bomEcn_view')")
    public R<List<MdmBomImpact.Row>> query(@RequestBody MdmBomImpact.Query q) {
        if (q == null || q.getBomIds() == null || q.getBomIds().isEmpty() || q.getBomIds().size() > 200
                || q.getBomIds().stream().anyMatch(Objects::isNull))
            throw new CheckedException("BOM影响范围须为1至200个精确版本");
        var access = accessService.check(new MdmFactoryAccessCheck().setFactoryCode(q.getFactoryCode())
                .setUserId(SecurityUtils.getUser().getId()).setRequiredPermission("read"));
        if (access == null || !access.isOk() || !Boolean.TRUE.equals(access.getData()))
            throw new CheckedException("无法核验BOM影响分析工厂权限");
        List<MdmBomImpact.Row> result = new ArrayList<>();
        var tasks = taskMapper.selectList(new LambdaQueryWrapper<MesTaskEntity>().eq(MesTaskEntity::getFactoryCode, q.getFactoryCode())
                .and(w -> {
                    w.in(MesTaskEntity::getBomId, q.getBomIds());
                    for (Long id : q.getBomIds()) w.or().like(MesTaskEntity::getBomSnapshot, id.toString());
                })
                .orderByAsc(MesTaskEntity::getId).last("LIMIT 1001"));
        tasks.forEach(row -> result.add(new MdmBomImpact.Row().setDomain(q.getBomIds().contains(row.getBomId()) ? "MES_TASK" : "MES_TASK_SNAPSHOT_CANDIDATE")
                .setId(row.getId()).setNumber(row.getCode()).setStatus(row.getStatus()).setFactoryCode(row.getFactoryCode())
                .setEvidenceJson(JSONUtil.toJsonStr(row))));
        var orders = orderMapper.selectList(new LambdaQueryWrapper<MesProductionOrderEntity>()
                .eq(MesProductionOrderEntity::getFactoryCode, q.getFactoryCode())
                .in(MesProductionOrderEntity::getBomId, q.getBomIds().stream().map(String::valueOf).toList())
                .orderByAsc(MesProductionOrderEntity::getId).last("LIMIT 1001"));
        orders.forEach(row -> result.add(new MdmBomImpact.Row().setDomain("MES_ORDER").setId(row.getId()).setNumber(row.getOrderNo())
                .setStatus(row.getStatus()).setFactoryCode(row.getFactoryCode()).setEvidenceJson(JSONUtil.toJsonStr(row))));
        var lists = materialMapper.selectList(new LambdaQueryWrapper<MesMaterialListEntity>().eq(MesMaterialListEntity::getFactoryCode, q.getFactoryCode())
                .and(w -> {
                    w.in(MesMaterialListEntity::getBomId, q.getBomIds());
                    for (Long id : q.getBomIds()) w.or().like(MesMaterialListEntity::getBomSnapshot, id.toString());
                })
                .orderByAsc(MesMaterialListEntity::getId).last("LIMIT 1001"));
        lists.forEach(row -> result.add(new MdmBomImpact.Row().setDomain(q.getBomIds().contains(row.getBomId()) ? "MES_MATERIAL_LIST" : "MES_MATERIAL_SNAPSHOT_CANDIDATE")
                .setId(row.getId()).setNumber(row.getTaskCode()).setFactoryCode(row.getFactoryCode())
                .setEvidenceJson(JSONUtil.toJsonStr(row))));
        if (result.size() > 1000) throw new CheckedException("MES影响候选超过1000条，须先核实工程范围，不截断后批准");
        return R.ok(result);
    }
}
