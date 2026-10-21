package com.kitex.mom.plan.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.plan.entity.PlnMpsRemainingFulfillmentObservationEntity;
import com.kitex.mom.plan.dto.PlnMpsRemainingCommitmentDTO;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

/** 剩余承诺领域内部持久化接口；控制器不得开放任意CRUD或删除。 */
public interface PlnMpsRemainingFulfillmentObservationService extends IService<PlnMpsRemainingFulfillmentObservationEntity> {
    /** 原承诺、当前分期及全量来源事件只读预览。 */
    PlnMpsRemainingCommitmentDTO.FulfillmentEvidence preview(Long commitmentId);
    /** 当前版本不可变观察，保留原号/原实名和全部虚拟份额。 */
    PlnMpsRemainingFulfillmentObservationEntity capture(PlnMpsRemainingCommitmentDTO.FulfillmentCapture command);
    /** 另一个实名岗位重采核验，跨日/超时/替代/事实变化记失效。 */
    PlnMpsRemainingFulfillmentObservationEntity review(PlnMpsRemainingCommitmentDTO.FulfillmentReview command);
    /** 当前组织不可变原观察正文。 */
    PlnMpsRemainingFulfillmentObservationEntity detail(Long id);
    /** 原编制版本观察履历，排除大型正文。 */
    Page<PlnMpsRemainingFulfillmentObservationEntity> history(Long commitmentId, long current, long size);
    /** 采集/核验原号查回执；未找到不是未提交证明。 */
    PlnMpsRemainingFulfillmentObservationEntity byRequest(String requestId);
}
