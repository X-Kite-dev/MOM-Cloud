package com.kitex.mom.plan.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.dto.MdmFactoryAccessCheck;
import com.kitex.mom.mdm.feign.RemoteMdmFactoryAccessService;
import com.kitex.mom.plan.dto.PlnObjectiveTemplateCommand;
import com.kitex.mom.plan.dto.PlnSchedulingCandidateProfileDTO;
import com.kitex.mom.plan.entity.PlnObjectiveTemplate;
import com.kitex.mom.plan.entity.PlnObjectiveTemplateOperation;
import com.kitex.mom.plan.mapper.PlnObjectiveTemplateMapper;
import com.kitex.mom.plan.mapper.PlnObjectiveTemplateOperationMapper;
import com.kitex.mom.plan.service.solver.SequenceSolverRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

@Service
@RequiredArgsConstructor
public class PlnObjectiveTemplateService {
    private final PlnObjectiveTemplateMapper templates;
    private final PlnObjectiveTemplateOperationMapper operations;
    private final RemoteMdmFactoryAccessService factories;
    private final SequenceSolverRegistry solvers;
    private final org.mybatis.spring.SqlSessionTemplate session;

    public Object page(long page, String code, boolean activeOnly) {
        String f = access("read");
        require(page > 0 && page <= 100000, "页码无效");
        return templates.selectPage(new Page<>(page, 30), scope(f).eq(StrUtil.isNotBlank(code), PlnObjectiveTemplate::getTemplateCode, code)
                .eq(activeOnly, PlnObjectiveTemplate::getStatus, "ACTIVE").orderByDesc(PlnObjectiveTemplate::getId));
    }

    public Object history(Long id) {
        String f = access("read");
        var t = templates.selectOne(scope(f).eq(PlnObjectiveTemplate::getId, id));
        require(t != null, "当前工厂模板不存在");
        return Map.of("template", t, "operations", operations.selectList(opScope(f).eq(PlnObjectiveTemplateOperation::getTemplateId, id).orderByAsc(PlnObjectiveTemplateOperation::getId)));
    }

    @Transactional(rollbackFor = Exception.class)
    public Object execute(String action, PlnObjectiveTemplateCommand c) {
        String f = access("write");
        Long actor = SecurityUtils.getUser().getId();
        require(Set.of("PROPOSE", "APPROVE", "REJECT", "WITHDRAW", "RETIRE").contains(action), "模板动作无效");
        require(c != null && c.getRequestId() != null && c.getRequestId().matches("[A-Za-z0-9_-]{16,64}") && text(c.getReason(), 500), "须填写请求号与500字以内依据");
        c.setReason(c.getReason().trim());
        if ("PROPOSE".equals(action)) {
            require(c.getId() == null && c.getTemplateCode() != null && c.getTemplateCode().matches("[A-Z0-9_]{2,50}")
                    && text(c.getTemplateName(), 100) && c.getIndustryCode() != null && c.getIndustryCode().matches("[A-Z0-9_]{2,50}"), "模板编码/行业标识/名称不合法；修订仍提交新版本");
            c.setTemplateName(c.getTemplateName().trim());
            c.setProfiles(normalize(c.getProfiles()));
        } else
            require(c.getId() != null && c.getProfiles() == null && c.getTemplateCode() == null && c.getTemplateName() == null && c.getIndustryCode() == null, "处理原版本不得夹带内容变更");
        String fingerprint = hash(SequenceSolverRegistry.json(Arrays.asList(action, c, actor)));
        templates.lockKey("APS-OBJECTIVE-REQUEST:" + TenantContextHolder.getTenantId() + ":" + f + ":" + c.getRequestId());
        session.clearCache();
        var replay = operations.selectOne(opScope(f).eq(PlnObjectiveTemplateOperation::getRequestId, c.getRequestId()));
        if (replay != null) {
            require(fingerprint.equals(replay.getFingerprint()), "请求号已用于不同动作、内容或人员");
            return Map.of("operation", replay, "template", templates.selectById(replay.getTemplateId()), "replayed", true);
        }
        PlnObjectiveTemplate t;
        if ("PROPOSE".equals(action)) {
            templates.lockKey("APS-OBJECTIVE-CODE:" + TenantContextHolder.getTenantId() + ":" + f + ":" + c.getTemplateCode());
            require(templates.selectCount(scope(f).eq(PlnObjectiveTemplate::getTemplateCode, c.getTemplateCode()).eq(PlnObjectiveTemplate::getStatus, "PENDING")) == 0, "同编码已有待审版本，请先处理");
            var last = templates.selectOne(scope(f).eq(PlnObjectiveTemplate::getTemplateCode, c.getTemplateCode()).orderByDesc(PlnObjectiveTemplate::getVersionNo).last("LIMIT 1"));
            var active = active(f, c.getTemplateCode());
            t = new PlnObjectiveTemplate();
            t.setTenantId(TenantContextHolder.getTenantId());
            t.setFactoryCode(f);
            t.setTemplateCode(c.getTemplateCode());
            t.setTemplateName(c.getTemplateName());
            t.setIndustryCode(c.getIndustryCode());
            t.setVersionNo(last == null ? 1 : Math.addExact(last.getVersionNo(), 1));
            t.setReplacesId(active == null ? null : active.getId());
            t.setProfilesJson(SequenceSolverRegistry.json(c.getProfiles()));
            t.setReason(c.getReason());
            t.setApplicantId(actor);
            t.setStatus("PENDING");
            require(templates.insert(t) == 1, "模板提案保存失败");
        } else {
            var found = templates.selectOne(scope(f).eq(PlnObjectiveTemplate::getId, c.getId()));
            require(found != null, "当前工厂模板不存在");
            templates.lockKey("APS-OBJECTIVE-CODE:" + TenantContextHolder.getTenantId() + ":" + f + ":" + found.getTemplateCode());
            session.clearCache();
            t = templates.selectOne(scope(f).eq(PlnObjectiveTemplate::getId, c.getId()).last("FOR UPDATE"));
            if ("RETIRE".equals(action)) {
                require("ACTIVE".equals(t.getStatus()), "仅启用版本可退役");
                t.setStatus("RETIRED");
            } else {
                require("PENDING".equals(t.getStatus()), "仅待审版本可复核或撤回");
                if ("WITHDRAW".equals(action)) require(Objects.equals(actor, t.getApplicantId()), "仅提案人可以撤回");
                else require(!Objects.equals(actor, t.getApplicantId()), "模板必须由另一名授权人员复核");
                if ("APPROVE".equals(action)) {
                    normalize(JSONUtil.toList(t.getProfilesJson(), PlnSchedulingCandidateProfileDTO.class));
                    var old = active(f, t.getTemplateCode());
                    require(Objects.equals(t.getReplacesId(), old == null ? null : old.getId()), "原活动版本已变化，须撤回/驳回后重提，不能覆盖他人发布");
                    if (old != null) {
                        old.setStatus("RETIRED");
                        old.setReviewerId(actor);
                        old.setReviewReason("由版本" + t.getVersionNo() + "独立批准替代：" + c.getReason());
                        // 复用500字字段，完整本次理由仍保存在新版本与批准回执。
                        old.setReviewReason(StrUtil.subPre(old.getReviewReason(), 500));
                        require(templates.updateById(old) == 1, "原活动版本退役失败");
                        record(old, "SUPERSEDE", "SUPERSEDE:" + t.getId(), fingerprint, actor, old.getReviewReason());
                    }
                    t.setStatus("ACTIVE");
                } else t.setStatus("WITHDRAW".equals(action) ? "WITHDRAWN" : "REJECTED");
            }
            t.setReviewerId(actor);
            t.setReviewReason(c.getReason());
            require(templates.updateById(t) == 1, "模板状态保存失败");
        }
        var op = record(t, action, c.getRequestId(), fingerprint, actor, c.getReason());
        return Map.of("template", t, "operation", op, "replayed", false);
    }

    /**
     * 求解事务内共享锁保护所引用版本；退役只阻止新的试算，不改写已生成情景。
     */
    public PlnObjectiveTemplate resolve(Long id, List<PlnSchedulingCandidateProfileDTO> candidates) {
        return resolve(id, candidates, null);
    }

    public PlnObjectiveTemplate resolve(Long id, List<PlnSchedulingCandidateProfileDTO> candidates, String expectedFingerprint) {
        String f = FactoryContextHolder.getFactoryCode();
        require(TenantContextHolder.getTenantId() != null && !TenantContextHolder.getTenantSkip()
                && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip()) && StrUtil.isNotBlank(f), "模板引用缺少实际工厂上下文");
        var t = templates.selectOne(scope(f).eq(PlnObjectiveTemplate::getId, id).last("FOR SHARE"));
        require(t != null && "ACTIVE".equals(t.getStatus()), "行业模板未启用、已退役或不属于当前工厂");
        require(t.getProfilesJson().equals(SequenceSolverRegistry.json(normalize(candidates))), "候选内容与批准模板不同，请切换手工配置或提交模板新版本");
        require(StrUtil.isBlank(expectedFingerprint) || expectedFingerprint.equals(fingerprint(t)), "行业模板版本或内容已变化，请重新读取活动模板目录");
        return t;
    }

    public String fingerprint(PlnObjectiveTemplate t) {
        require(t != null, "行业模板不能为空");
        var fields = new LinkedHashMap<String, Object>();
        fields.put("id", t.getId());
        fields.put("factory", t.getFactoryCode());
        fields.put("code", t.getTemplateCode());
        fields.put("industry", t.getIndustryCode());
        fields.put("version", t.getVersionNo());
        fields.put("profilesJson", t.getProfilesJson());
        return hash(SequenceSolverRegistry.json(fields));
    }

    public List<PlnSchedulingCandidateProfileDTO> normalize(List<PlnSchedulingCandidateProfileDTO> source) {
        require(source != null && source.size() >= 2 && source.size() <= 5, "模板必须包含2至5个完整候选");
        var result = new ArrayList<PlnSchedulingCandidateProfileDTO>();
        Set<String> codes = new HashSet<>();
        List<BigDecimal> reference = null;
        for (var p : source) {
            require(p != null && p.getCandidateCode() != null && p.getCandidateCode().matches("[A-Z0-9_]{2,30}")
                    && codes.add(p.getCandidateCode()) && text(p.getCandidateName(), 100)
                    && Set.of("DELIVERY_FIRST", "BALANCED_GLOBAL", "CHANGEOVER_FIRST").contains(Objects.toString(p.getSequenceStrategy(), ""))
                    && p.getCrossPriorityWindowDays() != null && p.getCrossPriorityWindowDays() >= 0 && p.getCrossPriorityWindowDays() <= 30, "候选编码/名称/策略/窗口无效或重复");
            var n = new PlnSchedulingCandidateProfileDTO();
            org.springframework.beans.BeanUtils.copyProperties(p, n);
            n.setCandidateName(n.getCandidateName().trim());
            n.setSequenceSolverCode(solvers.normalize(n.getSequenceSolverCode()));
            solvers.specification(n.getSequenceSolverCode());
            var weights = Arrays.asList(p.getDeliveryWeight(), p.getChangeoverWeight(), p.getCycleWeight(), p.getWaitingWeight(), p.getLoadBalanceWeight(), p.getWipWeight(), p.getOvertimeWeight(), p.getDisturbanceWeight());
            require(weights.stream().allMatch(w -> w != null && w.signum() >= 0 && w.compareTo(new BigDecimal("1000")) <= 0 && w.stripTrailingZeros().scale() <= 6)
                    && weights.stream().reduce(BigDecimal.ZERO, BigDecimal::add).signum() > 0, "八项目标权重须完整、0至1000、最多6位有效小数且合计大于0");
            weights = weights.stream().map(BigDecimal::stripTrailingZeros).toList();
            require(reference == null || reference.equals(weights), "同一模板内各候选必须使用同一组评分权重，不得各用尺度后混排");
            reference = weights;
            n.setDeliveryWeight(weights.get(0));
            n.setChangeoverWeight(weights.get(1));
            n.setCycleWeight(weights.get(2));
            n.setWaitingWeight(weights.get(3));
            n.setLoadBalanceWeight(weights.get(4));
            n.setWipWeight(weights.get(5));
            n.setOvertimeWeight(weights.get(6));
            n.setDisturbanceWeight(weights.get(7));
            result.add(n);
        }
        return result;
    }

    private PlnObjectiveTemplate active(String f, String code) {
        return templates.selectOne(scope(f).eq(PlnObjectiveTemplate::getTemplateCode, code).eq(PlnObjectiveTemplate::getStatus, "ACTIVE").last("FOR UPDATE"));
    }

    private PlnObjectiveTemplateOperation record(PlnObjectiveTemplate t, String action, String request, String fingerprint, Long actor, String reason) {
        var o = new PlnObjectiveTemplateOperation();
        o.setTenantId(t.getTenantId());
        o.setFactoryCode(t.getFactoryCode());
        o.setTemplateId(t.getId());
        o.setRequestId(request);
        o.setFingerprint(fingerprint);
        o.setAction(action);
        o.setResultStatus(t.getStatus());
        o.setOperatorId(actor);
        o.setReason(reason);
        require(operations.insert(o) == 1, "模板永久回执保存失败");
        return o;
    }

    private String access(String permission) {
        var u = SecurityUtils.getUser();
        String f = FactoryContextHolder.getFactoryCode();
        require(u != null && u.getId() != null && TenantContextHolder.getTenantId() != null && !TenantContextHolder.getTenantSkip()
                && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip()) && StrUtil.isNotBlank(f), "须实名选择实际租户/工厂");
        var r = factories.check(new MdmFactoryAccessCheck().setUserId(u.getId()).setFactoryCode(f).setRequiredPermission(permission));
        require(r != null && r.isOk() && Boolean.TRUE.equals(r.getData()), "实际工厂权限不足或MDM不可核验");
        return f;
    }

    private LambdaQueryWrapper<PlnObjectiveTemplate> scope(String f) {
        return new LambdaQueryWrapper<PlnObjectiveTemplate>().eq(PlnObjectiveTemplate::getTenantId, TenantContextHolder.getTenantId()).eq(PlnObjectiveTemplate::getFactoryCode, f);
    }

    private LambdaQueryWrapper<PlnObjectiveTemplateOperation> opScope(String f) {
        return new LambdaQueryWrapper<PlnObjectiveTemplateOperation>().eq(PlnObjectiveTemplateOperation::getTenantId, TenantContextHolder.getTenantId()).eq(PlnObjectiveTemplateOperation::getFactoryCode, f);
    }

    private String hash(String s) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private boolean text(String s, int n) {
        return StrUtil.isNotBlank(s) && s.length() <= n;
    }

    private void require(boolean ok, String message) {
        if (!ok) throw new CheckedException(message);
    }
}
