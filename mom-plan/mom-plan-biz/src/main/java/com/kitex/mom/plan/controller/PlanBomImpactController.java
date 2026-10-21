package com.kitex.mom.plan.controller;

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
import com.kitex.mom.plan.entity.PlanMatMrpEntity;
import com.kitex.mom.plan.mapper.PlanMatMrpMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;
import java.util.Set;

@RestController
@RequiredArgsConstructor
@RequestMapping("/planBomImpact/internal")
public class PlanBomImpactController {
    private final PlanMatMrpMapper mapper;
    private final RemoteMdmFactoryAccessService accessService;

    @Inner
    @PostMapping("/page")
    @PreAuthorize("isAuthenticated() and @pms.hasPermission('mdm_bomEcn_view')")
    public R<MdmBomImpact.Batch> page(@RequestBody MdmBomImpact.PageQuery q) {
        MdmBomImpactPages.validate(q, Set.of("PLAN_MRP"));
        var access = accessService.check(new MdmFactoryAccessCheck().setFactoryCode(q.getFactoryCode())
                .setUserId(SecurityUtils.getUser().getId()).setRequiredPermission("read"));
        if (access == null || !access.isOk() || !Boolean.TRUE.equals(access.getData()))
            throw new CheckedException("无法核验BOM影响分析工厂权限");
        Long upper = q.getUpperId();
        if (upper == null) {
            var latest = mapper.selectOne(new LambdaQueryWrapper<PlanMatMrpEntity>().select(PlanMatMrpEntity::getId)
                    .eq(PlanMatMrpEntity::getFactoryCode, q.getFactoryCode()).orderByDesc(PlanMatMrpEntity::getId).last("LIMIT 1"));
            upper = latest == null ? 0L : latest.getId();
        }
        var rows = mapper.selectList(new LambdaQueryWrapper<PlanMatMrpEntity>().eq(PlanMatMrpEntity::getFactoryCode, q.getFactoryCode())
                .gt(PlanMatMrpEntity::getId, MdmBomImpactPages.after(q)).le(PlanMatMrpEntity::getId, upper)
                .and(w -> {
                    w.in(PlanMatMrpEntity::getBomId, q.getBomIds()).or().in(PlanMatMrpEntity::getSubitemBomId, q.getBomIds());
                    for (Long id : q.getBomIds()) w.or().like(PlanMatMrpEntity::getBomPathSnapshot, id.toString());
                })
                .orderByAsc(PlanMatMrpEntity::getId).last("LIMIT " + (MdmBomImpactPages.PAGE_SIZE + 1)));
        return R.ok(MdmBomImpactPages.batch(q, upper, rows, PlanMatMrpEntity::getId, row -> MdmBomImpactPages.row(q,
                row.getId(), row.getPlanDemandNo(), String.valueOf(row.getStatus()), JSONUtil.toJsonStr(row), row.getBomPathSnapshot(),
                row.getBomId(), row.getSubitemBomId())));
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
        // 精确头引用与路径候选均保留；路径文本候选显式展示，不冒充完整PLM依赖扫描。
        var wrapper = new LambdaQueryWrapper<PlanMatMrpEntity>().eq(PlanMatMrpEntity::getFactoryCode, q.getFactoryCode())
                .and(w -> {
                    w.in(PlanMatMrpEntity::getBomId, q.getBomIds()).or().in(PlanMatMrpEntity::getSubitemBomId, q.getBomIds());
                    for (Long id : q.getBomIds()) w.or().like(PlanMatMrpEntity::getBomPathSnapshot, id.toString());
                });
        List<PlanMatMrpEntity> rows = mapper.selectList(wrapper.orderByAsc(PlanMatMrpEntity::getId).last("LIMIT 1001"));
        if (rows.size() > 1000) throw new CheckedException("MRP影响候选超过1000条，须先按工程范围分批核实，不截断后批准");
        return R.ok(rows.stream().map(row -> new MdmBomImpact.Row().setDomain(
                        q.getBomIds().contains(row.getBomId()) || q.getBomIds().contains(row.getSubitemBomId()) ? "PLAN_MRP" : "PLAN_MRP_PATH_CANDIDATE")
                .setId(row.getId()).setNumber(row.getPlanDemandNo()).setStatus(String.valueOf(row.getStatus()))
                .setFactoryCode(row.getFactoryCode()).setEvidenceJson(JSONUtil.toJsonStr(row))).toList());
    }
}
