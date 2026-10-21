package com.kitex.mom.qms.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.api.feign.RemoteMesChangePointEffectRetestService;
import com.kitex.mom.mes.dto.MesChangePointEffectRetestProof;
import com.kitex.mom.qms.dto.QmsCapaLinkDTO;
import com.kitex.mom.qms.dto.QmsCapaMesRetestReopenCommand;
import com.kitex.mom.qms.entity.QmsCapaEntity;
import com.kitex.mom.qms.entity.QmsCapaMesRetestReopenReceiptEntity;
import com.kitex.mom.qms.entity.QmsCapaOperationEntity;
import com.kitex.mom.qms.mapper.QmsCapaMapper;
import com.kitex.mom.qms.mapper.QmsCapaMesRetestReopenReceiptMapper;
import com.kitex.mom.qms.mapper.QmsCapaOperationMapper;
import com.kitex.mom.qms.service.QmsCapaMesRetestReopenService;
import com.kitex.mom.qms.service.QmsCapaService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 未达标复测重新进入D3围堵，而不是修改旧周期的PASS结果。
 *
 * <p>先锁CAPA，再反查MES已提交的独立复核事实。MES证明接口只读本地事实，不能回调QMS，
 * 避免跨服务行锁死等。永久回执、旧关闭快照、下一周期及履历在QMS同一事务提交。</p>
 */
@Service
@RequiredArgsConstructor
public class QmsCapaMesRetestReopenServiceImpl
        extends ServiceImpl<QmsCapaMesRetestReopenReceiptMapper, QmsCapaMesRetestReopenReceiptEntity>
        implements QmsCapaMesRetestReopenService {

    private final QmsCapaMapper capaMapper;
    private final QmsCapaOperationMapper operationMapper;
    private final QmsCapaService capaService;
    private final RemoteMesChangePointEffectRetestService mesService;

    private static void require(boolean valid, String message) {
        if (!valid) {
            throw new CheckedException(message);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public QmsCapaLinkDTO reopen(QmsCapaMesRetestReopenCommand command) {
        requireContext();
        require(command != null && command.getCapaId() != null && command.getRetestId() != null
                        && StrUtil.isNotBlank(command.getRequestId()) && command.getRequestId().length() <= 100,
                "CAPA、复测主键及永久请求号不能为空或超限");
        QmsCapaEntity capa = capaMapper.selectOne(new LambdaQueryWrapper<QmsCapaEntity>()
                .eq(QmsCapaEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(QmsCapaEntity::getFactoryCode, FactoryContextHolder.getFactoryCode())
                .eq(QmsCapaEntity::getId, command.getCapaId()).last("FOR UPDATE"));
        require(capa != null, "当前租户、工厂未找到CAPA");
        QmsCapaMesRetestReopenReceiptEntity replay = getOne(scope()
                .eq(QmsCapaMesRetestReopenReceiptEntity::getRequestId, command.getRequestId()));
        if (replay != null) {
            require(Objects.equals(replay.getCapaId(), command.getCapaId())
                    && Objects.equals(replay.getRetestId(), command.getRetestId()), "永久复开号已使用但来源不同");
            return response(replay);
        }
        var remote = mesService.proof(command.getRetestId());
        require(remote != null && remote.isOk() && remote.getData() != null, "MES已批准未达标事实不可反查");
        MesChangePointEffectRetestProof proof = remote.getData();
        QmsCapaLinkDTO current = capaService.getExternalLink(capa.getId());
        validateProof(command, capa, current, proof);
        // 同一来源若已另建活动CAPA，不把复开错误归并到另一个问题单。
        Long otherOpen = capaMapper.selectCount(new LambdaQueryWrapper<QmsCapaEntity>()
                .eq(QmsCapaEntity::getTenantId, capa.getTenantId())
                .eq(QmsCapaEntity::getFactoryCode, capa.getFactoryCode())
                .eq(QmsCapaEntity::getSourceType, capa.getSourceType())
                .eq(QmsCapaEntity::getSourceId, capa.getSourceId()).ne(QmsCapaEntity::getId, capa.getId())
                .notIn(QmsCapaEntity::getStatus, "CLOSED", "CANCELLED"));
        require(otherOpen == 0, "同一来源已有其他活动CAPA，须先核查，禁止重复复开");
        LocalDateTime now = LocalDateTime.now();
        int nextCycle = capa.getCycleNo() + 1;
        QmsCapaMesRetestReopenReceiptEntity receipt = new QmsCapaMesRetestReopenReceiptEntity()
                .setRequestId(command.getRequestId()).setCapaId(capa.getId()).setRetestId(proof.getRetestId())
                .setRetestNo(proof.getRetestNo()).setClosedCycleNo(capa.getCycleNo()).setReopenedCycleNo(nextCycle)
                .setEvidenceFingerprint(proof.getEvidenceFingerprint()).setClosedSnapshot(JSONUtil.toJsonStr(capa))
                .setSourceSnapshot(JSONUtil.toJsonStr(proof)).setReviewedBy(proof.getReviewedBy())
                .setExecutedBy(SecurityUtils.getUser().getUsername()).setExecutedTime(now)
                .setFactoryCode(capa.getFactoryCode());
        receipt.setTenantId(capa.getTenantId());
        receipt.setCreateBy(SecurityUtils.getUser().getUsername());
        receipt.setCreateTime(now);
        // 显式SET NULL：MyBatis默认忽略null，不能让下一周期继承原PASS、关闭时刻或验证人。
        int changed = capaMapper.update(null, new LambdaUpdateWrapper<QmsCapaEntity>()
                .eq(QmsCapaEntity::getId, capa.getId()).eq(QmsCapaEntity::getTenantId, capa.getTenantId())
                .eq(QmsCapaEntity::getFactoryCode, capa.getFactoryCode()).eq(QmsCapaEntity::getStatus, "CLOSED")
                .eq(QmsCapaEntity::getCycleNo, capa.getCycleNo())
                .set(QmsCapaEntity::getStatus, "CONTAINMENT").set(QmsCapaEntity::getCycleNo, nextCycle)
                .set(QmsCapaEntity::getRecurrenceCount, capa.getRecurrenceCount() == null ? 1 : capa.getRecurrenceCount() + 1)
                .set(QmsCapaEntity::getDueTime, now.plusDays(proof.getReopenDueDays()))
                .set(QmsCapaEntity::getClosedTime, null).set(QmsCapaEntity::getEffectivenessCriteria, null)
                .set(QmsCapaEntity::getEffectivenessReviewTime, null).set(QmsCapaEntity::getStandardizationScope, null)
                .set(QmsCapaEntity::getEffectivenessResult, null).set(QmsCapaEntity::getEffectivenessRemark, null)
                .set(QmsCapaEntity::getEffectivenessEvidence, null).set(QmsCapaEntity::getVerifierCode, null)
                .set(QmsCapaEntity::getVerifierName, null).set(QmsCapaEntity::getVerificationTime, null)
                .set(QmsCapaEntity::getLessonsLearned, null).set(QmsCapaEntity::getCancelReason, null)
                .set(QmsCapaEntity::getUpdateBy, SecurityUtils.getUser().getUsername())
                .set(QmsCapaEntity::getUpdateTime, now));
        require(changed == 1 && save(receipt), "CAPA复开或永久回执保存失败");
        QmsCapaOperationEntity operation = new QmsCapaOperationEntity()
                .setRequestId(command.getRequestId()).setCapaId(capa.getId()).setCapaNo(capa.getCapaNo())
                .setCycleNo(nextCycle).setObjectType("CAPA").setObjectId(capa.getId())
                .setOperationType("MES_RETEST_REOPEN").setBeforeStatus("CLOSED").setAfterStatus("CONTAINMENT")
                .setContent("MES复测" + proof.getRetestNo() + "独立核验未达标：第" + capa.getCycleNo()
                        + "周期关闭快照已保留，进入第" + nextCycle + "周期围堵；复核人=" + proof.getReviewedBy()
                        + "；证据=" + proof.getEvidenceFingerprint())
                .setOperatorCode(SecurityUtils.getUser().getUsername())
                .setOperatorName(StrUtil.blankToDefault(SecurityUtils.getUser().getName(), SecurityUtils.getUser().getUsername()))
                .setOperationTime(now).setFactoryCode(capa.getFactoryCode());
        operation.setTenantId(capa.getTenantId());
        require(operationMapper.insert(operation) == 1, "CAPA复开履历保存失败");
        return response(receipt);
    }

    @Override
    public List<QmsCapaMesRetestReopenReceiptEntity> receipts(Long capaId) {
        requireContext();
        capaService.getExternalLink(capaId);
        return list(scope().eq(QmsCapaMesRetestReopenReceiptEntity::getCapaId, capaId)
                .orderByDesc(QmsCapaMesRetestReopenReceiptEntity::getClosedCycleNo));
    }

    private void validateProof(QmsCapaMesRetestReopenCommand command, QmsCapaEntity capa,
                               QmsCapaLinkDTO current, MesChangePointEffectRetestProof proof) {
        require("FAIL".equals(proof.getStatus()) && Objects.equals(proof.getRetestId(), command.getRetestId())
                && Objects.equals(proof.getCapaId(), capa.getId())
                && Objects.equals(proof.getReopenRequestId(), command.getRequestId())
                && Objects.equals(proof.getTenantId(), capa.getTenantId())
                && Objects.equals(proof.getFactoryCode(), capa.getFactoryCode())
                && "MES_CHANGE_POINT_PARETO".equals(capa.getSourceType())
                && Objects.equals(proof.getImprovementId(), capa.getSourceId())
                && Objects.equals(proof.getImprovementNo(), capa.getSourceNo()), "MES未达标事实来源、请求号或组织不一致");
        require("CLOSED".equals(capa.getStatus()) && capa.getCycleNo() != null && capa.getCycleNo() < Integer.MAX_VALUE
                        && Objects.equals(capa.getCycleNo(), proof.getClosedCycleNo())
                        && Objects.equals(current.getLifecycleFingerprint(), proof.getCapaFingerprint()),
                "CAPA已复开、关闭依据变化或周期不一致，不使用旧复测重复开周期");
        require(StrUtil.isNotBlank(proof.getReviewedBy()) && StrUtil.isNotBlank(proof.getCollectedBy())
                && !Objects.equals(proof.getReviewedBy(), proof.getCollectedBy())
                && current.getExcludedReviewerCodes() != null
                && !current.getExcludedReviewerCodes().contains(proof.getReviewedBy()), "MES效果复核人不符合独立性要求");
        require(StrUtil.isNotBlank(proof.getEvidenceFingerprint()) && proof.getReopenDueDays() != null
                        && proof.getReopenDueDays() > 0 && proof.getReopenDueDays() <= 365,
                "MES证据指纹或新周期完成期限无效");
        require(capa.getRecurrenceCount() == null || capa.getRecurrenceCount() < Integer.MAX_VALUE, "复发次数已达上限");
    }

    private QmsCapaLinkDTO response(QmsCapaMesRetestReopenReceiptEntity receipt) {
        return capaService.getExternalLink(receipt.getCapaId()).setReopenRequestId(receipt.getRequestId())
                .setReopenRetestId(receipt.getRetestId()).setReopenedCycleNo(receipt.getReopenedCycleNo());
    }

    private LambdaQueryWrapper<QmsCapaMesRetestReopenReceiptEntity> scope() {
        return new LambdaQueryWrapper<QmsCapaMesRetestReopenReceiptEntity>()
                .eq(QmsCapaMesRetestReopenReceiptEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(QmsCapaMesRetestReopenReceiptEntity::getFactoryCode, FactoryContextHolder.getFactoryCode());
    }

    private void requireContext() {
        require(SecurityUtils.getUser() != null && SecurityUtils.getUser().getId() != null
                && TenantContextHolder.getTenantId() != null && !TenantContextHolder.getTenantSkip()
                && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())
                && StrUtil.isNotBlank(FactoryContextHolder.getFactoryCode()), "复开CAPA须实名登录并选择实际租户、工厂");
    }
}
