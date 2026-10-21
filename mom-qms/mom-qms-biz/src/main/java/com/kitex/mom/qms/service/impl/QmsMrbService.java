package com.kitex.mom.qms.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.dto.MdmFactoryAccessCheck;
import com.kitex.mom.mdm.feign.RemoteMdmFactoryAccessService;
import com.kitex.mom.qms.dto.req.QmsBadHandleReq;
import com.kitex.mom.qms.dto.req.QmsMrbCommand;
import com.kitex.mom.qms.entity.QmsBadInfoDetailEntity;
import com.kitex.mom.qms.entity.QmsBadInfoEntity;
import com.kitex.mom.qms.entity.QmsMrbOperation;
import com.kitex.mom.qms.entity.QmsMrbProposal;
import com.kitex.mom.qms.mapper.QmsBadInfoDetailMapper;
import com.kitex.mom.qms.mapper.QmsBadInfoMapper;
import com.kitex.mom.qms.mapper.QmsMrbOperationMapper;
import com.kitex.mom.qms.mapper.QmsMrbProposalMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 新MRB决策须多人会签后显式发布；会签不直接放行库存。
 */
@Service
@RequiredArgsConstructor
public class QmsMrbService {
    private static final String POLICY = "MRB_STANDARD_V1";
    private static final Map<String, String> PERMISSIONS = Map.of("QUALITY", "bad:mrbQuality", "PROCESS", "bad:mrbProcess", "PRODUCTION", "bad:mrbProduction", "SUPPLY_CHAIN", "bad:mrbSupplyChain");
    private final QmsMrbProposalMapper proposals;
    private final QmsMrbOperationMapper operations;
    private final QmsBadInfoMapper headers;
    private final QmsBadInfoDetailMapper details;
    private final RemoteMdmFactoryAccessService factories;
    private final org.mybatis.spring.SqlSessionTemplate sqlSession;

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new CheckedException(message);
    }

    public String access(String permission) {
        var user = SecurityUtils.getUser();
        String factory = FactoryContextHolder.getFactoryCode();
        require(user != null && user.getId() != null && TenantContextHolder.getTenantId() != null && !TenantContextHolder.getTenantSkip()
                && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip()) && StrUtil.isNotBlank(factory), "MRB须实名登录并选择实际工厂，不能跳过隔离");
        var r = factories.check(new MdmFactoryAccessCheck().setUserId(user.getId()).setFactoryCode(factory).setRequiredPermission(permission));
        require(r != null && r.isOk() && Boolean.TRUE.equals(r.getData()), "实际工厂授权不足或MDM不可核验");
        return factory;
    }

    public Map<String, Object> context(Long detailId, long page) {
        String factory = access("read");
        require(page >= 1 && page <= 100000, "页码无效");
        var source = source(detailId, factory, false);
        var rows = proposals.selectPage(new Page<>(page, 20), scope(factory).eq(QmsMrbProposal::getDetailId, detailId).orderByDesc(QmsMrbProposal::getId));
        return Map.of("detail", source.detail(), "info", source.info(), "proposals", rows, "policyVersion", POLICY,
                "requiredRoles", roles(source.info()), "notice", "新决策按固定标准矩阵会签：质量、工艺，以及采购来源的供应链/其他来源的生产。申请人与各岗位签署人互不相同；全签不等于发布。");
    }

    public Map<String, Object> detail(Long id) {
        String factory = access("read");
        var row = proposals.selectOne(scope(factory).eq(QmsMrbProposal::getId, id));
        require(row != null, "当前工厂MRB提案不存在");
        return Map.of("proposal", row, "disposition", JSONUtil.toBean(row.getCommandJson(), QmsBadHandleReq.class),
                "operations", operations.selectList(opScope(factory).eq(QmsMrbOperation::getProposalId, id).orderByAsc(QmsMrbOperation::getRevision)));
    }

    public Map<String, Object> preview(QmsBadHandleReq command) {
        var source = source(command == null ? null : command.getDetailId(), access("read"), false);
        var c = normalize(command);
        ready(source, c);
        String json = snapshot(source, c);
        return Map.of("disposition", c, "requiredRoles", roles(source.info()), "policyVersion", POLICY,
                "detail", source.detail(), "fingerprint", hash(json));
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> execute(QmsMrbCommand c) {
        String factory = access("write");
        var user = SecurityUtils.getUser();
        require(c != null && c.getDetailId() != null && validRequest(c.getRequestId()), "须提供明细和16至64位永久请求号");
        require(Set.of("PROPOSE", "SIGN", "REJECT", "WITHDRAW").contains(Objects.toString(c.getOperation(), "")), "MRB操作无效");
        require(StrUtil.isNotBlank(c.getReason()) && c.getReason().length() <= 1000, "须填写1000字以内实际依据");
        c.setReason(c.getReason().trim());
        if (Set.of("SIGN", "REJECT").contains(c.getOperation()))
            require(PERMISSIONS.containsKey(Objects.toString(c.getRoleCode(), ""))
                    && new PermissionService().hasPermission(PERMISSIONS.get(c.getRoleCode())), "当前用户没有所选会签岗位权限");
        else
            require(c.getRoleCode() == null && new PermissionService().hasPermission("bad:handle"), "申请/撤回须有MRB处置权限，不接受伪造岗位");
        String fingerprint = hash(JSONUtil.toJsonStr(Arrays.asList(c, user.getId())));
        headers.lockSource("QMS_MRB_REQUEST:" + tenant() + ":" + factory + ":" + c.getRequestId());
        var old = operations.selectOne(opScope(factory).eq(QmsMrbOperation::getRequestId, c.getRequestId()));
        if (old != null) {
            require(fingerprint.equals(old.getFingerprint()), "原请求号已用于不同人员/内容");
            return Map.of("proposal", proposals.selectById(old.getProposalId()), "operation", old, "replayed", true);
        }
        var source = source(c.getDetailId(), factory, true);
        QmsMrbProposal row;
        String before;
        if ("PROPOSE".equals(c.getOperation())) {
            require(c.getProposalId() == null && c.getDisposition() != null && Objects.equals(c.getDetailId(), c.getDisposition().getDetailId()), "提案须明确同一分类明细的完整处置内容");
            require(proposals.selectCount(scope(factory).eq(QmsMrbProposal::getDetailId, c.getDetailId()).in(QmsMrbProposal::getStatus, "PENDING", "APPROVED")) == 0, "已有待签/待发布提案，须先驳回或撤回");
            var command = normalize(c.getDisposition());
            ready(source, command);
            String json = snapshot(source, command);
            require(hash(json).equals(c.getExpectedFingerprint()), "来源或处置内容变化，请重新预览");
            row = new QmsMrbProposal();
            row.setTenantId(tenant());
            row.setFactoryCode(factory);
            row.setBadId(source.info().getId());
            row.setDetailId(c.getDetailId());
            row.setPolicyVersion(POLICY);
            row.setRequiredRoles(String.join(",", roles(source.info())));
            row.setCommandJson(JSONUtil.toJsonStr(command));
            row.setSourceSnapshot(json);
            row.setSourceFingerprint(hash(json));
            row.setReason(c.getReason());
            row.setApplicantId(user.getId());
            row.setStatus("PENDING");
            row.setRevision(1);
            LocalDateTime limit = LocalDateTime.now().plusHours(24);
            row.setExpiresTime(command.getExpectedCompletionTime().isBefore(limit) ? command.getExpectedCompletionTime() : limit);
            require(proposals.insert(row) == 1, "MRB提案保存失败");
            before = "NONE";
        } else {
            require(c.getProposalId() != null && c.getDisposition() == null && c.getExpectedFingerprint() == null, "会签/撤回只能引用原提案，不覆盖处置内容");
            row = proposals.selectOne(scope(factory).eq(QmsMrbProposal::getId, c.getProposalId()).eq(QmsMrbProposal::getDetailId, c.getDetailId()).last("FOR UPDATE"));
            require(row != null && Set.of("PENDING", "APPROVED").contains(row.getStatus()), "提案不存在或已终结/发布");
            before = row.getStatus();
            if ("WITHDRAW".equals(c.getOperation())) {
                require(Objects.equals(row.getApplicantId(), user.getId()), "仅原申请人可撤回未发布提案");
                row.setStatus("WITHDRAWN");
            } else {
                require(!Objects.equals(row.getApplicantId(), user.getId()) && Arrays.asList(row.getRequiredRoles().split(",")).contains(c.getRoleCode()), "申请人不能自签，岗位须在冻结矩阵内");
                if ("REJECT".equals(c.getOperation())) row.setStatus("REJECTED"); // 已签人员也可在发布前否决，保留原正向签名。
                else {
                    require("PENDING".equals(row.getStatus()), "已完成全签，不能追加或替换签名");
                    fresh(row, source);
                    var signed = operations.selectList(opScope(factory).eq(QmsMrbOperation::getProposalId, row.getId()).eq(QmsMrbOperation::getOperationType, "SIGN"));
                    require(signed.stream().noneMatch(op -> Objects.equals(op.getRoleCode(), c.getRoleCode()) || Objects.equals(op.getOperatorId(), user.getId())), "同一岗位只能签署一次，同一人不得兼签多个岗位");
                    if (signed.size() + 1 == row.getRequiredRoles().split(",").length) row.setStatus("APPROVED");
                }
            }
            row.setRevision(row.getRevision() + 1);
            require(proposals.updateById(row) == 1, "MRB会签保存失败");
        }
        var op = audit(row, c.getRequestId(), fingerprint, c.getOperation(), c.getRoleCode(), before, c.getReason());
        return Map.of("proposal", row, "operation", op, "replayed", false);
    }

    /**
     * 在原MRB发布事务中校验；不可在独立事务先消费会签。
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void claim(QmsBadHandleReq req, QmsBadInfoEntity info, QmsBadInfoDetailEntity detail) {
        String factory = access("write");
        require(req.getMrbProposalId() != null && validRequest(req.getRequestId()), "新MRB决策必须引用已全签提案及永久请求号");
        var row = proposals.selectOne(scope(factory).eq(QmsMrbProposal::getId, req.getMrbProposalId()).eq(QmsMrbProposal::getDetailId, detail.getId()).last("FOR UPDATE"));
        require(row != null && "APPROVED".equals(row.getStatus()), "提案未全签或已发布/撤回");
        require(Objects.equals(row.getApplicantId(), SecurityUtils.getUser().getId()) && new PermissionService().hasPermission("bad:handle"), "由原申请人明确发布已全签提案");
        require(row.getCommandJson().equals(JSONUtil.toJsonStr(normalize(req))), "实际发布命令与会签内容不一致");
        fresh(row, new Source(info, detail));
        var signed = operations.selectList(opScope(factory).eq(QmsMrbOperation::getProposalId, row.getId()).eq(QmsMrbOperation::getOperationType, "SIGN"));
        Set<String> roles = new HashSet<>();
        Set<Long> users = new HashSet<>();
        for (var op : signed) {
            require(roles.add(op.getRoleCode()) && users.add(op.getOperatorId()) && !Objects.equals(op.getOperatorId(), row.getApplicantId()), "签名岗位或人员不独立");
        }
        require(roles.equals(new HashSet<>(Arrays.asList(row.getRequiredRoles().split(",")))), "会签岗位不完整");
        String fingerprint = executionHash(req);
        row.setExecutionRequestId(req.getRequestId());
        row.setExecutionFingerprint(fingerprint);
        row.setExecutorId(SecurityUtils.getUser().getId());
        row.setExecutionTime(LocalDateTime.now());
        row.setStatus("APPLIED");
        row.setRevision(row.getRevision() + 1);
        require(proposals.updateById(row) == 1, "MRB发布回执保存失败");
        detail.setMrbProposalId(row.getId());
        var normalized = normalize(req);
        req.setHandleResult(normalized.getHandleResult());
        req.setHandleRemark(normalized.getHandleRemark());
        req.setHandleFile(normalized.getHandleFile());
        req.setMesConcessionUnrestricted(normalized.getMesConcessionUnrestricted());
        req.setExpectedCompletionTime(normalized.getExpectedCompletionTime());
        audit(row, req.getRequestId(), fingerprint, "APPLY", null, "APPROVED", "按完整会签内容发布处置决策；实物执行和独立验证继续走原业务链");
    }

    public boolean replay(QmsBadHandleReq req) {
        String factory = access("write");
        sqlSession.clearCache();
        if (req.getMrbProposalId() == null) return false;
        var row = proposals.selectOne(scope(factory).eq(QmsMrbProposal::getId, req.getMrbProposalId()).eq(QmsMrbProposal::getDetailId, req.getDetailId()));
        require(row != null, "当前工厂MRB提案不存在");
        if (!"APPLIED".equals(row.getStatus())) return false;
        require(Objects.equals(row.getExecutionRequestId(), req.getRequestId()) && Objects.equals(row.getExecutionFingerprint(), executionHash(req)), "提案已发布，不能换请求号、人员或内容重发");
        return true;
    }

    private String executionHash(QmsBadHandleReq req) {
        return hash(JSONUtil.toJsonStr(Arrays.asList(req.getRequestId(), req.getMrbProposalId(), normalize(req), SecurityUtils.getUser().getId())));
    }

    private void fresh(QmsMrbProposal row, Source source) {
        require(row.getExpiresTime().isAfter(LocalDateTime.now()), "会签提案已过期，须撤回/驳回后重新申请");
        var command = JSONUtil.toBean(row.getCommandJson(), QmsBadHandleReq.class);
        ready(source, command);
        require(row.getSourceFingerprint().equals(hash(snapshot(source, command))), "分类/隔离来源或处置基线变化，旧签名不能继续使用，请重提");
    }

    private QmsMrbOperation audit(QmsMrbProposal row, String request, String fingerprint, String type, String role, String before, String reason) {
        var user = SecurityUtils.getUser();
        var op = new QmsMrbOperation();
        op.setTenantId(tenant());
        op.setFactoryCode(row.getFactoryCode());
        op.setProposalId(row.getId());
        op.setDetailId(row.getDetailId());
        op.setRequestId(request);
        op.setFingerprint(fingerprint);
        op.setOperationType(type);
        op.setRoleCode(role);
        op.setBeforeStatus(before);
        op.setAfterStatus(row.getStatus());
        op.setRevision(row.getRevision());
        op.setOperatorId(user.getId());
        op.setOperatorCode(user.getUsername());
        op.setReason(reason);
        require(operations.insert(op) == 1, "MRB永久签名/操作回执保存失败");
        return op;
    }

    private Source source(Long id, String factory, boolean lock) {
        require(id != null, "须明确NCR分类明细");
        sqlSession.clearCache();
        var q = new LambdaQueryWrapper<QmsBadInfoDetailEntity>().eq(QmsBadInfoDetailEntity::getId, id).eq(QmsBadInfoDetailEntity::getTenantId, tenant()).eq(QmsBadInfoDetailEntity::getFactoryCode, factory);
        if (lock) q.last("FOR UPDATE");
        var d = details.selectOne(q);
        require(d != null, "当前工厂NCR分类不存在");
        var hq = new LambdaQueryWrapper<QmsBadInfoEntity>().eq(QmsBadInfoEntity::getId, d.getBadId()).eq(QmsBadInfoEntity::getTenantId, tenant()).eq(QmsBadInfoEntity::getFactoryCode, factory);
        if (lock) hq.last("FOR UPDATE");
        var h = headers.selectOne(hq);
        require(h != null, "当前工厂NCR来源不存在");
        return new Source(h, d);
    }

    private void ready(Source s, QmsBadHandleReq c) {
        require("WAIT_MRB".equals(s.detail().getStatus()) && "SUCCESS".equals(s.info().getHoldSyncStatus()) && !"CLOSED".equals(s.info().getStatus()), "须为已隔离且待MRB的有效分类");
        require(s.detail().getJudgeNum() != null && s.detail().getJudgeNum().signum() > 0 && Objects.equals(c.getDetailId(), s.detail().getId()), "分类数量或处置目标无效");
        require(c.getExpectedCompletionTime().isAfter(LocalDateTime.now()), "计划完成时间须晚于当前时间");
        require(!"RETURN".equals(c.getHandleResult()) || "PURCHASE".equals(s.info().getResourceType()), "采购来源才允许退货");
        if (com.kitex.mom.mes.dto.MesInventoryQualityOrigin.isMesResource(s.info().getResourceType())) {
            require(Set.of("RELEASE", "CONCESSION", "SMASH").contains(c.getHandleResult()), "MES在途案件不借用WMS返工或退货");
            require(!"CONCESSION".equals(c.getHandleResult()) || Boolean.TRUE.equals(c.getMesConcessionUnrestricted()), "MES让步仅支持明确无客户/订单/用途范围限制的授权");
        }
    }

    private QmsBadHandleReq normalize(QmsBadHandleReq original) {
        require(original != null && original.getDetailId() != null, "处置内容不能为空");
        var c = new QmsBadHandleReq();
        org.springframework.beans.BeanUtils.copyProperties(original, c);
        c.setRequestId(null);
        c.setMrbProposalId(null);
        c.setTaskType(null);
        require(Set.of("SMASH", "RETURN", "RELEASE", "CONCESSION", "REWORK").contains(Objects.toString(c.getHandleResult(), "")), "处置方式无效");
        require(StrUtil.isNotBlank(c.getHandleRemark()) && c.getHandleRemark().length() <= 1000, "须填写1000字以内处置说明");
        c.setHandleRemark(c.getHandleRemark().trim());
        c.setHandleFile(StrUtil.blankToDefault(StrUtil.trim(c.getHandleFile()), null));
        require(c.getHandleFile() == null || c.getHandleFile().length() <= 500, "依据引用不能超过500字");
        require(!"CONCESSION".equals(c.getHandleResult()) || c.getHandleFile() != null, "让步必须保留可追溯授权依据");
        c.setMesConcessionUnrestricted(Boolean.TRUE.equals(c.getMesConcessionUnrestricted()));
        require(c.getExpectedCompletionTime() != null && c.getExpectedCompletionTime().getNano() == 0, "计划完成时间须精确到秒");
        return c;
    }

    private List<String> roles(QmsBadInfoEntity info) {
        return List.of("QUALITY", "PROCESS", "PURCHASE".equals(info.getResourceType()) ? "SUPPLY_CHAIN" : "PRODUCTION");
    }

    private String snapshot(Source s, QmsBadHandleReq c) {
        var header = JSONUtil.parseObj(JSONUtil.toJsonStr(s.info()));
        for (String k : List.of("unJudgeNum", "judgedNum", "disposedNum", "closedNum", "status", "updateBy", "updateTime"))
            header.remove(k);
        String json = JSONUtil.toJsonStr(Arrays.asList(POLICY, header, s.detail(), c, roles(s.info())));
        require(json.length() <= 200000, "MRB来源快照超限，不截断证据");
        return json;
    }

    private LambdaQueryWrapper<QmsMrbProposal> scope(String factory) {
        return new LambdaQueryWrapper<QmsMrbProposal>().eq(QmsMrbProposal::getTenantId, tenant()).eq(QmsMrbProposal::getFactoryCode, factory);
    }

    private LambdaQueryWrapper<QmsMrbOperation> opScope(String factory) {
        return new LambdaQueryWrapper<QmsMrbOperation>().eq(QmsMrbOperation::getTenantId, tenant()).eq(QmsMrbOperation::getFactoryCode, factory);
    }

    private Long tenant() {
        return TenantContextHolder.getTenantId();
    }

    private boolean validRequest(String id) {
        return id != null && id.matches("[A-Za-z0-9_-]{16,64}");
    }

    private record Source(QmsBadInfoEntity info, QmsBadInfoDetailEntity detail) {
    }
}
