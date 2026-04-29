package com.kitex.mom.pms.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.pms.entity.PmsRefundEntity;
import com.kitex.mom.pms.vo.PmsRefundVO;

public interface PmsRefundService extends IService<PmsRefundEntity> {

    /**
     * 通过id查询退款表
     *
     * @param id id
     * @return R
     */
    PmsRefundVO getRefundById(Long id);

    /**
     * 新增退款表
     *
     * @param pmsRefund 退款表
     * @return R
     */
    Boolean saveRefund(PmsRefundEntity pmsRefund);
}