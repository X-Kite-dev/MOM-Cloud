package com.kitex.mom.plan.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.mom.oms.dto.OmsOrderSupplyReplanDTO;
import com.kitex.mom.oms.entity.OmsOrderSupplyReplanEntity;

/**
 * OMS订单变更对已形成但未执行供应的受控撤回、重算和重下达服务。
 */
public interface OmsOrderSupplyReplanService extends IService<OmsOrderSupplyReplanEntity> {
    /**
     * 按当前租户和工厂查询级联重排工作台。
     */
    Page<OmsOrderSupplyReplanEntity> page(SearchPage page, OmsOrderSupplyReplanDTO.Query query);

    /**
     * 查询主账、逐MRP执行回执和不可变操作履历。
     */
    OmsOrderSupplyReplanDTO.Detail detail(Long id);

    /**
     * 查询某次OMS订单变更评审最近一份级联申请。
     */
    OmsOrderSupplyReplanDTO.Detail latestByReview(Long reviewId);

    /**
     * 由订单变更申请人冻结完整证据并提交级联申请。
     */
    OmsOrderSupplyReplanDTO.Detail submit(OmsOrderSupplyReplanDTO.SubmitCommand command);

    /**
     * 由另一账号复核，并在批准后启动分阶段编排。
     */
    OmsOrderSupplyReplanDTO.Detail review(OmsOrderSupplyReplanDTO.ReviewCommand command);

    /**
     * 在业务阻断消除后，按已有永久回执从未完成阶段续跑。
     */
    OmsOrderSupplyReplanDTO.Detail retry(OmsOrderSupplyReplanDTO.RetryCommand command);
}
