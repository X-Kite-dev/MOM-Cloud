package com.kitex.mom.plan.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.plan.dto.PlnMpsExecutionDTO;
import com.kitex.mom.plan.entity.PlnMpsExecutionObservationEntity;

/** MPS兑现取证、不可变采集及独立核验。 */
public interface PlnMpsExecutionObservationService extends IService<PlnMpsExecutionObservationEntity> {
    /** 当前已发布日桶、根计划及MES来源一致性预览，不写正式生产事实。 */
    PlnMpsExecutionDTO.Evidence preview(Long versionId, int toleranceMinutes);

    /** 永久请求号幂等冻结原始证据和服务端计算结果。 */
    PlnMpsExecutionObservationEntity capture(PlnMpsExecutionDTO.Capture command);

    /** 当前实际组织内按永久请求号查已提交观察；未找到不等于远端请求已取消。 */
    PlnMpsExecutionObservationEntity findCapture(String requestId);

    /** 按版本分页读取观察摘要，不重复传送大型快照。 */
    Page<PlnMpsExecutionObservationEntity> history(Long versionId, long current, long size);

    /** 查询不可变快照及其独立核验状态。 */
    PlnMpsExecutionObservationEntity detail(Long id);

    /** 重采并核验来源；不以确认观察的动作授权修改MPS/APS/MES。 */
    PlnMpsExecutionObservationEntity review(PlnMpsExecutionDTO.Review command);
}
