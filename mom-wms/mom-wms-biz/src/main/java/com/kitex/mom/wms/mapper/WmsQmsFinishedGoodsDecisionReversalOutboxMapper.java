package com.kitex.mom.wms.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.wms.entity.WmsQmsFinishedGoodsDecisionReversalOutboxEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 访问WMS到QMS的质量逆向执行结果可靠投递事件。
 */
@Mapper
public interface WmsQmsFinishedGoodsDecisionReversalOutboxMapper
        extends MomBaseMapper<WmsQmsFinishedGoodsDecisionReversalOutboxEntity> {
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT id,tenant_id,factory_code FROM wms_qms_finished_goods_decision_reversal_outbox "
            + "WHERE del_flag='0' AND (((status IN ('PENDING','RETRY')) AND next_attempt_time<=now()) "
            + "OR (status='SENDING' AND lease_until<=now())) "
            + "ORDER BY COALESCE(next_attempt_time,lease_until),id LIMIT #{limit}")
    List<WmsQmsFinishedGoodsDecisionReversalOutboxEntity> dueAcrossTenants(@Param("limit") int limit);
}
