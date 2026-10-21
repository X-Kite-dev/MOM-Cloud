package com.kitex.mom.plan.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.plan.dto.PlnMpsRemainingReplanDTO;
import com.kitex.mom.plan.entity.PlnMpsRemainingReplanLinkEntity;

import java.util.List;

/** 业务端MPS来源剩余未开工工序重排，沿用正式局部案件的选择、独立发布与回退。 */
public interface PlnMpsRemainingReplanService extends IService<PlnMpsRemainingReplanLinkEntity> {
    /** 从已核验日桶及完整正式PLAN/MES事实预览可移动范围，不写候选。 */
    PlnMpsRemainingReplanDTO.Preview preview(Long observationId, Long lineId, int toleranceMinutes);

    /** 重采指纹，精确目标评估三候选，来源绑定与候选在同一事务提交。 */
    PlnMpsRemainingReplanDTO.Detail assess(PlnMpsRemainingReplanDTO.Assess command);

    /** 原永久号查询，未找到不代表请求已取消。 */
    PlnMpsRemainingReplanDTO.Detail findRequest(String requestId);

    /** 当前组织及原观察/桶的历史来源绑定，最多200条，超限明确拒绝截断。 */
    List<PlnMpsRemainingReplanLinkEntity> history(Long observationId, Long lineId);

    /** 返回绑定及当前权威候选状态，不以已评估冒充已发布。 */
    PlnMpsRemainingReplanDTO.Detail detail(Long id);
}
