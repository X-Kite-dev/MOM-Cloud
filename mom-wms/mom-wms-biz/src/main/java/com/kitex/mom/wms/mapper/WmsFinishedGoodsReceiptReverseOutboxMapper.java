package com.kitex.mom.wms.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.wms.entity.WmsFinishedGoodsReceiptReverseOutboxEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 访问WMS到MES的成品红冲可靠投递事件。
 */
@Mapper
public interface WmsFinishedGoodsReceiptReverseOutboxMapper
        extends MomBaseMapper<WmsFinishedGoodsReceiptReverseOutboxEntity> {
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT id,tenant_id,factory_code FROM wms_finished_goods_receipt_reverse_outbox "
            + "WHERE del_flag='0' AND (((status IN ('PENDING','RETRY')) AND next_attempt_time<=now()) "
            + "OR (status='SENDING' AND lease_until<=now())) "
            + "ORDER BY COALESCE(next_attempt_time,lease_until),id LIMIT #{limit}")
    List<WmsFinishedGoodsReceiptReverseOutboxEntity> dueAcrossTenants(@Param("limit") int limit);
}
