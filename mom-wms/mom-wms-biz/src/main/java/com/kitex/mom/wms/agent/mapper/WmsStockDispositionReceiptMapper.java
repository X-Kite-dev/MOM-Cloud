package com.kitex.mom.wms.agent.mapper;

import com.kitex.mom.wms.agent.dto.WmsStockDispositionReceiptDTO;
import com.kitex.mom.wms.entity.WmsQualityStatusOperationEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 按正式质量库存事务的受影响库存行主键取证；不以物料或批次模糊匹配。
 */
@Mapper
public interface WmsStockDispositionReceiptMapper {

    @Select("""
            SELECT id, request_id, hold_no, source_type, source_id, action, status,
                   requested_quantity, affected_quantity, execution_ref, executed_by, executed_time,
                   create_time, update_time, affected_stock_ids, split_recorded, split_remainder_stock_ids,
                   split_lineage_json, split_recorded_at
              FROM "mom_wms".wms_quality_status_operation
             WHERE tenant_id = #{tenantId} AND factory_code = #{factoryCode}
               AND del_flag = '0'
               AND string_to_array(COALESCE(affected_stock_ids, ''), ',') @> ARRAY[#{stockId}]::text[]
             ORDER BY COALESCE(update_time, create_time) DESC, id DESC
             LIMIT 101
            """)
    List<WmsQualityStatusOperationEntity> selectByStockId(@Param("tenantId") Long tenantId,
                                                          @Param("factoryCode") String factoryCode,
                                                          @Param("stockId") String stockId);

    /**
     * 只读原库存的软归档行；没有归档行时不能推断实物已执行。
     */
    @Select("""
            SELECT id AS stock_id, factory_code, COALESCE(mat_num, 0)::numeric AS physical_num,
                   mat_code AS material_code, mat_batch AS material_batch,
                   mat_barcode AS material_barcode, supplier_code,
                   warehouse_code, site_code, container_code, mat_unit AS unit_code,
                   status AS lock_status,
                   quality_status, quality_hold_no, quality_source_type, quality_source_id,
                   update_time AS stock_updated_at
              FROM "mom_wms".wms_live_stock
             WHERE tenant_id = #{tenantId} AND factory_code = #{factoryCode}
               AND id = #{stockId} AND del_flag = '1'
             LIMIT 1
            """)
    WmsStockDispositionReceiptDTO.ArchivedStock selectArchivedStock(@Param("tenantId") Long tenantId,
                                                                    @Param("factoryCode") String factoryCode,
                                                                    @Param("stockId") Long stockId);
}
