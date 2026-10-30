package com.kitex.mom.mes.service.impl;

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
import com.kitex.mom.mes.dto.AndonProductionReleaseCommand;
import com.kitex.mom.mes.entity.MesAndonProductionRelease;
import com.kitex.mom.mes.entity.MesAndonRecordEntity;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.mapper.MesAndonProductionReleaseMapper;
import com.kitex.mom.mes.mapper.MesAndonRecordMapper;
import com.kitex.mom.mes.mapper.MesTaskArrangeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 安灯闭环和生产放行是两件事；批准只解除本事件的逻辑阻断，不代替现场开工。
 */
@Service
@RequiredArgsConstructor
public class MesAndonProductionReleaseService {
    private final MesAndonRecordMapper events;
    private final MesTaskArrangeMapper arranges;
    private final MesAndonProductionReleaseMapper releases;
    private final RemoteMdmFactoryAccessService factories;
    private final MesExecutionTaskLock executionTaskLock;

    private static void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }

    public String authorizeFactory(String permission) {
        var user = SecurityUtils.getUser();
        String factory = FactoryContextHolder.getFactoryCode();
        require(user != null && user.getId() != null && TenantContextHolder.getTenantId() != null
                && !TenantContextHolder.getTenantSkip() && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())
                && StrUtil.isNotBlank(factory), "安灯操作须登录并选择实际工厂，不能跳过隔离");
        var result = factories.check(new MdmFactoryAccessCheck().setUserId(user.getId())
                .setFactoryCode(factory).setRequiredPermission(permission));
        require(result != null && result.isOk() && Boolean.TRUE.equals(result.getData()), "实际工厂权限不足或MDM不可核验");
        return factory;
    }

    public MesAndonRecordEntity readScopedEvent(Long id) {
        return event(id, authorizeFactory("read"), false);
    }

    /**
     * 先取真实工单业务锁，再取创建/开工/报工共用派工锁，避免出口暂停与绑定穿透。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public MesTaskArrangeEntity lockLinkedArrange(String code, String factory) {
        require(StrUtil.isNotBlank(code), "影响生产的安灯必须关联明确派工单；设备/整线停机应走对应设备治理");
        var before = executionTaskLock.arrange(code);
        require(Objects.equals(before.getFactoryCode(), factory), "安灯关联工厂与实际派工不一致");
        var rows = arranges.selectList(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                .eq(MesTaskArrangeEntity::getId, before.getId())
                .eq(MesTaskArrangeEntity::getTenantId, before.getTenantId())
                .eq(MesTaskArrangeEntity::getFactoryCode, factory).last("LIMIT 2 FOR UPDATE"));
        require(rows.size() == 1, "关联派工不存在或编码不唯一");
        executionTaskLock.assertArrange(before, rows.get(0));
        return rows.get(0);
    }

    @PreAuthorize("@pms.hasPermission('andonRecord:show')")
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<String, Object> view(Long id, long current) {
        String factory = authorizeFactory("read");
        var event = event(id, factory, false);
        require(current >= 1 && current <= 100000, "页码不合法");
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("event", event);
        view.put("required", Boolean.TRUE.equals(event.getProductionReleaseRequired()));
        view.put("latest", latest(id));
        view.put("history", releases.selectPage(new Page<>(current, 20), new LambdaQueryWrapper<MesAndonProductionRelease>()
                .eq(MesAndonProductionRelease::getRecordId, id).eq(MesAndonProductionRelease::getFactoryCode, factory)
                .orderByDesc(MesAndonProductionRelease::getId)));
        if (Boolean.TRUE.equals(event.getProductionReleaseRequired())) {
            var rows = arranges.selectList(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                    .eq(MesTaskArrangeEntity::getCode, event.getArrangeCode()).eq(MesTaskArrangeEntity::getFactoryCode, factory).last("LIMIT 2"));
            require(rows.size() == 1, "安灯派工关联缺失或歧义，禁止推测复产对象");
            var evidence = evidence(event, rows.get(0));
            view.put("evidence", evidence);
            view.put("fingerprint", fingerprint(evidence));
            view.put("arrangeStatus", rows.get(0).getStatus());
            view.put("allowed", approved(event, rows.get(0), latest(id)));
        } else {
            view.put("allowed", false); // 未纳管不是批准，不伪造历史放行。
        }
        return view;
    }

    @Transactional(rollbackFor = Exception.class)
    @PreAuthorize("@pms.hasPermission('andonRecord:resumeRequest')")
    public MesAndonProductionRelease request(Long id, AndonProductionReleaseCommand command) {
        require(command != null && "REQUEST".equals(command.getAction()), "此入口仅接受复产申请");
        return execute(id, command);
    }

    @Transactional(rollbackFor = Exception.class)
    @PreAuthorize("@pms.hasPermission('andonRecord:resumeApprove')")
    public MesAndonProductionRelease decide(Long id, AndonProductionReleaseCommand command) {
        require(command != null && command.getAction() != null && Set.of("APPROVE", "REJECT", "REVOKE").contains(command.getAction()), "不支持的复产决策");
        return execute(id, command);
    }

    private MesAndonProductionRelease execute(Long id, AndonProductionReleaseCommand command) {
        String factory = authorizeFactory("write");
        require(text(command.getRequestId(), 64) && text(command.getReason(), 1000), "须提供永久请求号及1至1000字说明");
        var first = event(id, factory, false);
        require(Boolean.TRUE.equals(first.getProductionReleaseRequired()), "此安灯未纳入派工复产审批，不能补造历史放行");
        var arrange = lockLinkedArrange(first.getArrangeCode(), factory);
        var event = event(id, factory, true); // 锁序：派工 → 安灯；发起和开工同序。
        var user = SecurityUtils.getUser();
        String commandHash = fingerprint(Arrays.asList(id, user.getId(), command));
        var duplicate = releases.selectOne(new LambdaQueryWrapper<MesAndonProductionRelease>()
                .eq(MesAndonProductionRelease::getRequestId, command.getRequestId()));
        if (duplicate != null) {
            require(Objects.equals(duplicate.getCommandFingerprint(), commandHash) && Objects.equals(duplicate.getRecordId(), id), "请求号已用于其他复产命令");
            return duplicate;
        }
        var previous = latest(id);
        require(Objects.equals(command.getExpectedLastId(), previous == null ? null : previous.getId()), "复产审批状态已变化，请重新读取");
        var evidence = evidence(event, arrange);
        String evidenceHash = fingerprint(evidence);
        require(Objects.equals(command.getExpectedFingerprint(), evidenceHash), "异常或派工事实已变化，须重新核对现场");
        String action = command.getAction();
        if ("REQUEST".equals(action)) {
            require(terminal(event), "安灯须验证闭环或有原因撤销后才能申请复产，不能以审批跳过异常处理");
            require(Set.of("RECEIVE", "PAUSE", "PRODUCT").contains(arrange.getStatus()), "请先接收并确定实际工位；终止或完工派工不再申请复产");
            require(previous == null || !Set.of("REQUEST", "APPROVE").contains(previous.getAction())
                    || !Objects.equals(previous.getEvidenceFingerprint(), evidenceHash), "已有待审批申请或有效批准，不能重复申请");
            require(checked(command), "须确认现场环境、设备、物料质量和人员四项准备情况");
        } else if ("APPROVE".equals(action) || "REJECT".equals(action)) {
            require(previous != null && "REQUEST".equals(previous.getAction()), "仅待审批申请可以批准或驳回");
            require(!Objects.equals(previous.getOperatorId(), user.getId()), "申请人与审批人必须分离");
            if ("APPROVE".equals(action)) {
                require(Set.of("RECEIVE", "PAUSE", "PRODUCT").contains(arrange.getStatus()), "派工状态不再允许复产批准");
                require(terminal(event) && Objects.equals(previous.getEvidenceFingerprint(), evidenceHash), "申请事实已变化，不能批准旧申请");
                require(!Objects.equals(event.getReceiverCode(), String.valueOf(user.getId())), "安灯实际处理人不能批准自己的复产结果");
                require(checked(command), "审批人须独立确认全部四项现场准备条件");
            }
        } else {
            require(previous != null && "APPROVE".equals(previous.getAction()), "仅已批准的复产可以撤回放行");
        }
        var result = new MesAndonProductionRelease();
        result.setTenantId(event.getTenantId());
        result.setFactoryCode(factory);
        result.setRecordId(id);
        result.setArrangeId(arrange.getId());
        result.setPreviousId(previous == null ? null : previous.getId());
        result.setAction(action);
        result.setRequestId(command.getRequestId());
        result.setCommandFingerprint(commandHash);
        result.setEvidenceFingerprint(evidenceHash);
        result.setEvidenceJson(JSONUtil.toJsonStr(evidence));
        result.setChecksJson(JSONUtil.toJsonStr(Arrays.asList(command.getSiteChecked(), command.getEquipmentChecked(), command.getMaterialQualityChecked(), command.getPersonnelChecked())));
        result.setOperatorId(user.getId());
        result.setOperatorName(user.getName());
        result.setReason(command.getReason());
        result.setOperationTime(LocalDateTime.now());
        require(releases.insert(result) == 1, "复产决策保存失败");
        return result;
    }

    /**
     * 人工与IoT共用，不要求IoT伪造人类账号；调用方必须已锁定派工并建立真实租户/工厂上下文。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void assertAllowed(MesTaskArrangeEntity arrange, String stage) {
        previewAllowed(arrange, stage);
    }

    /**
     * 只读检查与正式门共用规则，不写审批或通过事务代理标记外层快照回滚。
     */
    public void previewAllowed(MesTaskArrangeEntity arrange, String stage) {
        require(TenantContextHolder.getTenantId() != null && !TenantContextHolder.getTenantSkip()
                && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())
                && Objects.equals(TenantContextHolder.getTenantId(), arrange.getTenantId())
                && StrUtil.isNotBlank(arrange.getFactoryCode()) && Objects.equals(FactoryContextHolder.getFactoryCode(), arrange.getFactoryCode()), "安灯生产门缺少实际租户/工厂上下文");
        var blockers = events.selectList(new LambdaQueryWrapper<MesAndonRecordEntity>()
                .eq(MesAndonRecordEntity::getFactoryCode, arrange.getFactoryCode()).eq(MesAndonRecordEntity::getArrangeCode, arrange.getCode())
                .eq(MesAndonRecordEntity::getProductionReleaseRequired, true).orderByAsc(MesAndonRecordEntity::getId).last("LIMIT 201"));
        require(blockers.size() <= 200, "关联安灯超过200条，请治理历史执行范围，不能截断生产门核验");
        for (var event : blockers) {
            require(approved(event, arrange, latest(event.getId())), "安灯[" + event.getCode() + "]未取得当前派工事实的有效复产批准，阻断" + stage + "；须先完成异常验证并由独立人员批准复产");
        }
    }

    /**
     * 返回本次纯读观察消费的精确安灯/批准摘要，不能用“已查过”替代源证据。
     */
    public java.util.List<Map<String, Object>> previewStartEvidence(MesTaskArrangeEntity arrange) {
        require(Objects.equals(TenantContextHolder.getTenantId(), arrange.getTenantId())
                && Objects.equals(FactoryContextHolder.getFactoryCode(), arrange.getFactoryCode())
                && !TenantContextHolder.getTenantSkip() && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip()), "安灯观察缺少实际范围");
        var rows = events.selectList(new LambdaQueryWrapper<MesAndonRecordEntity>()
                .eq(MesAndonRecordEntity::getTenantId, arrange.getTenantId())
                .eq(MesAndonRecordEntity::getFactoryCode, arrange.getFactoryCode())
                .eq(MesAndonRecordEntity::getArrangeCode, arrange.getCode())
                .eq(MesAndonRecordEntity::getProductionReleaseRequired, true)
                .orderByAsc(MesAndonRecordEntity::getId).last("LIMIT 201"));
        require(rows.size() <= 200, "安灯来源超过200条，不能截断");
        var result = new java.util.ArrayList<Map<String, Object>>();
        for (var event : rows) {
            var release = latest(event.getId());
            boolean valid = approved(event, arrange, release);
            result.add(Map.of("eventId", event.getId().toString(), "eventCode", event.getCode(),
                    "approved", valid, "releaseId", release == null ? "" : release.getId().toString(),
                    "evidenceFingerprint", release == null ? "" : Objects.toString(release.getEvidenceFingerprint(), ""),
                    "source", evidence(event, arrange)));
        }
        return result;
    }

    private boolean approved(MesAndonRecordEntity event, MesTaskArrangeEntity arrange, MesAndonProductionRelease latest) {
        return terminal(event) && latest != null && "APPROVE".equals(latest.getAction())
                && Objects.equals(latest.getArrangeId(), arrange.getId())
                && Objects.equals(latest.getEvidenceFingerprint(), fingerprint(evidence(event, arrange)));
    }

    private Map<String, Object> evidence(MesAndonRecordEntity event, MesTaskArrangeEntity arrange) {
        require(Objects.equals(event.getTenantId(), arrange.getTenantId()) && Objects.equals(event.getFactoryCode(), arrange.getFactoryCode())
                && Objects.equals(event.getArrangeCode(), arrange.getCode()) && Objects.equals(event.getTaskCode(), arrange.getTaskCode())
                && Objects.equals(event.getProduceTaskCode(), arrange.getProduceTaskCode()), "安灯与派工工单/工序归属不一致，禁止放行");
        var result = new LinkedHashMap<String, Object>();
        result.put("version", "ANDON_PRODUCTION_RELEASE_V1");
        result.put("recordId", event.getId());
        result.put("code", event.getCode());
        result.put("tenantId", event.getTenantId());
        result.put("factoryCode", event.getFactoryCode());
        result.put("state", event.getState());
        result.put("impactProduction", event.getImpactProduction());
        result.put("required", event.getProductionReleaseRequired());
        result.put("typeCode", event.getTypeCode());
        result.put("level", event.getLevel());
        result.put("exDesc", event.getExDesc());
        result.put("eventDeviceCode", event.getDeviceCode());
        result.put("eventWorksiteCode", event.getWorksiteCode());
        result.put("rootCause", event.getRootCause());
        result.put("correctiveAction", event.getCorrectiveAction());
        result.put("result", event.getResult());
        result.put("receiverCode", event.getReceiverCode());
        result.put("verificationResult", event.getVerificationResult());
        result.put("verificationRemark", event.getVerificationRemark());
        result.put("verificationTime", event.getVerificationTime());
        result.put("verifierCode", event.getVerifierCode());
        result.put("cancelReason", event.getCancelReason());
        result.put("arrangeId", arrange.getId());
        result.put("arrangeCode", arrange.getCode());
        result.put("taskCode", arrange.getTaskCode());
        result.put("produceTaskCode", arrange.getProduceTaskCode());
        result.put("workerCode", arrange.getWorkerCode());
        result.put("plannedDeviceCode", arrange.getPlannedDeviceCode());
        result.put("actualWorksiteCode", arrange.getActualWorksiteCode());
        result.put("worksiteCodes", arrange.getWorksiteCodes());
        return result;
    }

    private MesAndonRecordEntity event(Long id, String factory, boolean lock) {
        require(id != null, "请选择安灯事件");
        var result = events.selectOne(new LambdaQueryWrapper<MesAndonRecordEntity>().eq(MesAndonRecordEntity::getId, id)
                .eq(MesAndonRecordEntity::getFactoryCode, factory).last(lock ? "FOR UPDATE" : ""));
        require(result != null, "实际工厂内未找到安灯事件");
        return result;
    }

    private MesAndonProductionRelease latest(Long id) {
        return releases.selectOne(new LambdaQueryWrapper<MesAndonProductionRelease>().eq(MesAndonProductionRelease::getRecordId, id)
                .orderByDesc(MesAndonProductionRelease::getId).last("LIMIT 1"));
    }

    private boolean terminal(MesAndonRecordEntity event) {
        return ("closed".equals(event.getState()) && "PASS".equals(event.getVerificationResult()) && event.getVerificationTime() != null)
                || ("cancel".equals(event.getState()) && StrUtil.isNotBlank(event.getCancelReason()));
    }

    public String creationFingerprint(MesAndonRecordEntity command) {
        return fingerprint(Arrays.asList(SecurityUtils.getUser().getId(), command.getTypeCode(), command.getLevel(), command.getExDesc(),
                command.getWorkshopCode(), command.getLineCode(), command.getFaultCode(), command.getFaultName(), command.getTaskCode(),
                command.getProduceTaskCode(), command.getArrangeCode(), command.getDeviceCode(), command.getWorksiteCode(), command.getImpactProduction()));
    }

    private boolean checked(AndonProductionReleaseCommand command) {
        return Boolean.TRUE.equals(command.getSiteChecked()) && Boolean.TRUE.equals(command.getEquipmentChecked())
                && Boolean.TRUE.equals(command.getMaterialQualityChecked()) && Boolean.TRUE.equals(command.getPersonnelChecked());
    }

    private boolean text(String value, int max) {
        return StrUtil.isNotBlank(value) && value.length() <= max;
    }

    private String fingerprint(Object value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSONUtil.toJsonStr(value).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
