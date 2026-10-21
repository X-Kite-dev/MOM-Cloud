package com.kitex.mom.plan.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.oms.dto.OmsDeliveryPromiseDTO;
import com.kitex.mom.oms.entity.OmsDeliveryPromiseEntity;

/**
 * 客户交期答复从证据冻结、销售/PMC确认、订单变更到对客留痕的完整业务服务。
 */
public interface OmsDeliveryPromiseService extends IService<OmsDeliveryPromiseEntity> {
    OmsDeliveryPromiseDTO.Preparation prepare(String orderCode);

    OmsDeliveryPromiseDTO.Detail submit(OmsDeliveryPromiseDTO.SubmitCommand command);

    OmsDeliveryPromiseDTO.Detail confirm(OmsDeliveryPromiseDTO.ConfirmCommand command, String role);

    OmsDeliveryPromiseDTO.Detail cancel(OmsDeliveryPromiseDTO.CancelCommand command);

    OmsDeliveryPromiseDTO.Detail reply(OmsDeliveryPromiseDTO.ReplyCommand command);

    OmsDeliveryPromiseDTO.Detail detail(Long id);
}
