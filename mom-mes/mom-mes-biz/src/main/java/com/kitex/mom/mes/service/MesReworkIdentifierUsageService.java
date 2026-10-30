package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mes.dto.MesReworkIdentifierCandidateDTO;
import com.kitex.mom.mes.dto.MesReworkMaterialCommand;
import com.kitex.mom.mes.dto.MesTaskReportVO;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.enums.ReportModeEnum;

import java.util.List;

/**
 * 正式返工对原动态实例的受控引用服务。
 *
 * <p>服务遵循标准IService结构，但不开放通用CRUD；领域入口只允许冻结、核验和在真实报工
 * 事务末尾追加使用账。</p>
 */
public interface MesReworkIdentifierUsageService extends IService<MesReworkIdentifierUsageEntity> {

    ScopeContext context(MesTaskEntity reworkTask);

    FrozenScope freeze(MesTaskEntity reworkTask, MesReworkMaterialCommand.IdentifierScope requested);

    void validateApproved(MesTaskEntity reworkTask);

    /**
     * 复核尚未写入工单的待批准范围，防止用当前工单旧值代替提案原文。
     */
    void validateFrozen(MesTaskEntity reworkTask, String snapshot, String fingerprint);

    Prepared prepare(MesTaskReportVO command, MesTaskArrangeEntity arrange, MesTaskEntity task,
                     ReportModeEnum mode, String outcome);

    void complete(Prepared prepared, MesTaskReportEntity report);

    void complete(Prepared prepared, MesTaskReportUnqualifiedEntity report);

    record ScopeContext(boolean required, List<MesReworkIdentifierCandidateDTO> candidates,
                        String approvedScopeSnapshot, String approvedScopeFingerprint) {
    }

    record FrozenScope(String snapshot, String fingerprint) {
    }

    record Prepared(String outcome, Long planId, Long sourceBindingId, String submittedIdentifier,
                    String canonicalIdentifier, Integer sourceBindingVersion, String scopeFingerprint,
                    String evidenceSnapshot, String evidenceFingerprint, Long actorId, String actorCode) {
    }
}
