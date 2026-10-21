package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mes.dto.MesTaskReportVO;
import com.kitex.mom.mes.entity.MesIdentifierMappingEntity;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.entity.MesTaskReportEntity;
import com.kitex.mom.mes.enums.ReportModeEnum;

import java.util.List;

/**
 * 生产标识映射生命周期和报工时解析服务。
 */
public interface MesIdentifierMappingService extends IService<MesIdentifierMappingEntity> {
    IPage<MesIdentifierMappingEntity> queryPage(Page<MesIdentifierMappingEntity> page,
                                                MesIdentifierMappingEntity query);

    MesIdentifierMappingEntity saveDraft(MesIdentifierMappingEntity draft);

    MesIdentifierMappingEntity copyVersion(Long sourceId);

    MesIdentifierMappingEntity publish(Long id);

    MesIdentifierMappingEntity retire(Long id);

    /**
     * 返回扫码预览阶段可能对应的规范码。结果始终包含原始输入值；同一外码在不同
     * 工单、物料或工序作用域下允许映射到不同规范码，最终是否可用仍由派工冻结
     * 的执行契约和 {@link #resolve} 二次判定。
     */
    List<String> findCanonicalCandidates(String submittedIdentifier);

    void resolve(MesTaskArrangeEntity arrange, MesTaskEntity task, ReportModeEnum mode,
                 MesTaskReportVO command);

    void recordSuccessfulUse(MesTaskReportEntity report, MesTaskReportVO command);
}
