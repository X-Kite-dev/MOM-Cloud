package com.kitex.mom.wms.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.wms.entity.WmsInoutFlowEntity;
import com.kitex.mom.wms.entity.WmsOutBillDetailEntity;
import com.kitex.mom.wms.entity.WmsOutBillEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 仓储实物流水及其原业务凭证读取。 */
@Mapper
public interface WmsInoutFlowMapper extends MomBaseMapper<WmsInoutFlowEntity> {

    /**
     * 按原租户、工厂、出库单和明细核实全部实际出库份额。来源不明和已知冲突分别返回，
     * 不能根据条码外观、今日物料配置或单行数量推断历史单件身份。
     *
     * @return SERIAL 为一件有原库存依据的序列号，BULK 为可分量的普通实物，
     *         UNKNOWN 为缺少历史证据，CONFLICT 为流水与原行不一致
     */
    @Select("""
            SELECT CASE
                WHEN count(*) = 0 THEN 'UNKNOWN'
                WHEN bool_or(f.bill_no IS DISTINCT FROM #{bill.billNo} OR f.bill_type IS DISTINCT FROM #{bill.billType}
                     OR f.warehouse_code IS DISTINCT FROM #{bill.warehouseCode}
                     OR f.mat_code IS DISTINCT FROM #{detail.matCode} OR f.mat_unit IS DISTINCT FROM #{detail.matUnit}
                     OR nullif(f.mat_batch, '') IS DISTINCT FROM nullif(#{detail.matBatch}::text, '')
                     OR nullif(f.mat_barcode, '') IS DISTINCT FROM nullif(#{detail.matBarcode}::text, '')
                     OR f.mat_num IS NULL OR f.mat_num <= 0 OR f.mat_num >= 'Infinity'::double precision)
                  OR sum(f.mat_num::text::numeric) IS DISTINCT FROM #{detail.matNum}::text::numeric THEN 'CONFLICT'
                WHEN bool_or(f.serial_managed IS TRUE) AND (count(*) <> 1 OR min(f.mat_num) <> 1
                     OR nullif(btrim(#{detail.matBarcode}::text), '') IS NULL) THEN 'CONFLICT'
                WHEN bool_or(f.source_stock_id IS NULL OR f.source_stock_id <= 0
                     OR f.stock_proof_type IS DISTINCT FROM 'OUTBOUND' OR f.serial_managed IS NULL) THEN 'UNKNOWN'
                WHEN bool_or(f.serial_managed) THEN 'SERIAL'
                ELSE 'BULK'
            END
            FROM mom_wms.wms_inout_flow f
            WHERE f.tenant_id = #{bill.tenantId} AND f.factory_code = #{bill.factoryCode}
              AND f.bill_id = #{bill.id} AND f.bill_detail_id = #{detail.id} AND f.del_flag = '0' AND f.operate_type = 'out'
            """)
    String productionIssueIdentity(@Param("bill") WmsOutBillEntity bill, @Param("detail") WmsOutBillDetailEntity detail);
}
