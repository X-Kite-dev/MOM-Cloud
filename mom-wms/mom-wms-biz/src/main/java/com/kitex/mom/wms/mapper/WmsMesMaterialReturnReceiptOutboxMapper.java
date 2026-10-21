package com.kitex.mom.wms.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.wms.entity.WmsMesMaterialReturnReceiptOutboxEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * WMS生产退料实收事件可靠投递箱。
 */
@Mapper
public interface WmsMesMaterialReturnReceiptOutboxMapper
        extends MomBaseMapper<WmsMesMaterialReturnReceiptOutboxEntity> {
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT id,tenant_id,factory_code FROM wms_mes_material_return_receipt_outbox WHERE del_flag='0' AND "
            + "((status IN ('PENDING','RETRY') AND next_attempt_time<=now()) "
            + "OR (status='SENDING' AND lease_until<=now())) "
            + "ORDER BY COALESCE(next_attempt_time,lease_until),id LIMIT #{limit}")
    List<WmsMesMaterialReturnReceiptOutboxEntity> dueAcrossTenants(@Param("limit") int limit);
}
