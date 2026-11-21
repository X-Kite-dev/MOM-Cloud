package com.kitex.mom.pms.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.pms.entity.PmsOrderDetailEntity;
import com.kitex.mom.pms.entity.WmsPurchaseReceiptDetailEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface PmsOrderDetailMapper extends MomBaseMapper<PmsOrderDetailEntity> {

    /**
     * 同一采购单尚未完成的收料行。编辑时排除当前收料单，原行主键用于区分同物料多行。
     */
    @Select("""
            <script>
            SELECT d.id, d.order_detail_id, d.mat_code, d.mat_unit, d.should_num, coalesce(d.in_num, 0) AS in_num
            FROM mom_pms.wms_purchase_receipt r
            JOIN mom_pms.wms_purchase_receipt_detail d ON d.bill_id = r.id AND d.tenant_id = r.tenant_id
            WHERE r.tenant_id = #{tenant} AND r.factory_code = #{factory} AND r.pms_order_no = #{orderNo}
              AND r.del_flag = '0' AND d.del_flag = '0' AND (r.state IS NULL OR r.state != '4')
            <if test="exceptBill != null">AND r.id != #{exceptBill}</if>
            ORDER BY d.id
            </script>
            """)
    List<WmsPurchaseReceiptDetailEntity> openReceiptLines(@Param("tenant") Long tenant, @Param("factory") String factory,
            @Param("orderNo") String orderNo, @Param("exceptBill") Long exceptBill);
}
