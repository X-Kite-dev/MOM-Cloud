package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mes.dto.MesChangeoverAttributionDTO;
import com.kitex.mom.mes.entity.MesChangeoverAttributionEntity;

import java.util.List;
import java.util.Map;

/**
 * 换型归属业务服务；实体遵循IService规范，控制器仅开放受控命令，不开放通用CRUD。
 */
public interface MesChangeoverAttributionService extends IService<MesChangeoverAttributionEntity> {
    /**
     * 精确源/派工完整事实预览；不自动匹配、不签发写授权。
     */
    MesChangeoverAttributionDTO.Preview preview(String executionId, String arrangeId);

    /**
     * 目标明确设备与工位范围的已完成原账候选；仍需逐项完整门禁。
     */
    List<Map<String, Object>> sources(String arrangeId);

    /**
     * 该派工完整案件摘要，原凭证正文通过detail取出。
     */
    List<Map<String, Object>> cases(String arrangeId);

    /**
     * 精确案件完整原身份、原源和目标快照。
     */
    Map<String, Object> detail(String caseId);

    /**
     * 案件逐永久操作与原结果履历，不省略失败后已确认的终态。
     */
    List<Map<String, Object>> operations(String caseId);

    /**
     * 当前实名原号查回执；null不能证明未提交。
     */
    MesChangeoverAttributionDTO.Receipt byRequest(String requestId);

    /**
     * 受控申请/审核/本人撤回/错误归属撤销，保存前拒绝与提交未知分离。
     */
    MesChangeoverAttributionDTO.Receipt execute(String action, MesChangeoverAttributionDTO.Command command);

    /**
     * 只读签出规范完整载荷及服务端指纹，供终端提交前持久登记；不是业务提交回执。
     */
    MesChangeoverAttributionDTO.Receipt prepareCommand(String action, MesChangeoverAttributionDTO.Command command);
}
