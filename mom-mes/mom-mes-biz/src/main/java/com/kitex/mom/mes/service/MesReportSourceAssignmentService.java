package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mes.dto.MesReportSourceAssignmentDTO;
import com.kitex.mom.mes.entity.MesReportSourceAssignmentEntity;

import java.util.List;
import java.util.Map;

/** 末序报工来源核清；只追加凭证，不改原报工、原分配、入库或PLAN回传。 */
public interface MesReportSourceAssignmentService extends IService<MesReportSourceAssignmentEntity> {
    /** 预览精确报工及整个物理工单来源占用，未知不分摊。 */
    MesReportSourceAssignmentDTO.Preview preview(Long reportId);
    /** 同一MES快照采集全工单当前有效末序产出及逐来源归属。 */
    MesReportSourceAssignmentDTO.Preview taskEvidence(Long taskId);
    /** 同快照批量读取，避免MPS整版逐工单发起多次取证查询。 */
    Map<Long, MesReportSourceAssignmentDTO.Preview> taskEvidenceBatch(List<Long> taskIds);
    /** 冻结原预览并追加核定版本，同号异参拒绝。 */
    MesReportSourceAssignmentEntity assign(MesReportSourceAssignmentDTO.Assign command);
    /** 精确报工的全部历史核定版本，不覆盖旧事实。 */
    List<MesReportSourceAssignmentEntity> history(Long reportId);
    /** 查询永久号；无记录仅表示尚未观察到提交。 */
    MesReportSourceAssignmentEntity byRequest(String requestId);
}
