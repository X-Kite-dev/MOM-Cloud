package com.kitex.mom.wms.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.wms.entity.WmsLiveStockEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 在库实物维护及入库时的跨库区标识核对。 */
@Mapper
public interface WmsLiveStockMapper extends MomBaseMapper<WmsLiveStockEntity> {

    /**
     * 在同一数据库快照中查在库和已拣待出实物，避免库位交接时漏掉原件。
     * 新入库单件的序列号须独占；普通条码允许重用，但不能占用已有单件的序列号。
     * 仅已清零或已删除的记录释放占用，历史结存数量不明时仍需核对原件。
     *
     * @param tenantId 当前租户
     * @param factoryCode 当前工厂
     * @param barcode 去除首尾空格后的入库条码
     * @param serialManaged 本次实物是否按单件入库
     * @return 任一库区仍有冲突实物时返回 true
     */
    @Select("""
            SELECT EXISTS (
                SELECT 1 FROM mom_wms.wms_live_stock s
                WHERE s.tenant_id = #{tenantId} AND s.factory_code = #{factoryCode}
                  AND s.del_flag = '0' AND s.mat_num IS DISTINCT FROM 0
                  AND btrim(s.mat_barcode) = #{barcode} AND (#{serialManaged} OR s.serial_managed IS TRUE)
            ) OR EXISTS (
                SELECT 1 FROM mom_wms.wms_picking_area p
                WHERE p.tenant_id = #{tenantId} AND p.factory_code = #{factoryCode}
                  AND p.del_flag = '0' AND p.mat_num IS DISTINCT FROM 0
                  AND btrim(p.mat_barcode) = #{barcode} AND (#{serialManaged} OR p.serial_managed IS TRUE)
            )
            """)
    boolean hasConflictingBarcode(@Param("tenantId") Long tenantId, @Param("factoryCode") String factoryCode,
                                  @Param("barcode") String barcode, @Param("serialManaged") boolean serialManaged);
}
