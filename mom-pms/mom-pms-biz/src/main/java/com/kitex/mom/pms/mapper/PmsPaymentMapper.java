package com.kitex.mom.pms.mapper;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.pms.entity.PmsPaymentEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface PmsPaymentMapper extends MomBaseMapper<PmsPaymentEntity> {

    /**
     * 查询预付款和票到付款的付款金额总数和退款金额总数
     */
    List<PmsPaymentEntity> getAmount(@Param("ew") QueryWrapper<PmsPaymentEntity> wrapper);
}