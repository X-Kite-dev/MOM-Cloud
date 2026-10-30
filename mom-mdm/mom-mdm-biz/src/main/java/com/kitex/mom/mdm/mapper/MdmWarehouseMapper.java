package com.kitex.mom.mdm.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.mdm.entity.MdmWarehouseEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Collection;
import java.util.List;

@Mapper
public interface MdmWarehouseMapper extends MomBaseMapper<MdmWarehouseEntity> {

    // 显式限定实际租户/工厂，避免控制器传入的工厂或拦截器跳过标志扩大删除范围。
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("""
            <script>
            SELECT * FROM mdm_warehouse
            WHERE tenant_id = #{tenant} AND factory_code = #{factory} AND del_flag = '0'
            AND id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            ORDER BY id FOR UPDATE
            </script>
            """)
    List<MdmWarehouseEntity> findWarehousesForDeletion(@Param("ids") Collection<Long> ids,
                                                       @Param("tenant") Long tenant,
                                                       @Param("factory") String factory);

    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("""
            SELECT * FROM mdm_warehouse
            WHERE tenant_id = #{tenant} AND factory_code = #{factory} AND warehouse_code = #{code}
              AND del_flag = '0' ORDER BY id FOR UPDATE
            """)
    List<MdmWarehouseEntity> findWarehousesForConfig(@Param("tenant") Long tenant,
                                                     @Param("factory") String factory, @Param("code") String code);

    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("""
            SELECT * FROM mdm_warehouse
            WHERE tenant_id = #{tenant} AND factory_code = #{factory} AND del_flag = '0'
            ORDER BY id
            """)
    List<MdmWarehouseEntity> findMenuConfigurationWarehouses(@Param("tenant") Long tenant,
                                                             @Param("factory") String factory);

    // 历史库位和BOM也构成引用，不能因软删而丢失关联。
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("""
            SELECT 'MDM库位及其历史' WHERE EXISTS (
                SELECT 1 FROM mdm_warehouse_location
                WHERE tenant_id = #{tenant} AND factory_code = #{factory} AND warehouse_code = #{code})
            UNION ALL
            SELECT 'MDM物料BOM默认发料仓库' WHERE EXISTS (
                SELECT 1 FROM mdm_bom_child
                WHERE tenant_id = #{tenant} AND factory_code = #{factory}
                AND (default_issuance_warehouse = #{code} OR default_issuance_warehouse = #{warehouseId}))
            """)
    List<String> findLocalReferences(@Param("tenant") Long tenant, @Param("factory") String factory,
                                     @Param("code") String code, @Param("warehouseId") String warehouseId);

    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Update("""
            UPDATE mdm_warehouse SET del_flag = '1'
            WHERE id = #{id} AND tenant_id = #{tenant} AND factory_code = #{factory}
              AND warehouse_code = #{code} AND status = false AND del_flag = '0'
            """)
    int deleteUnusedWarehouse(@Param("id") Long id, @Param("tenant") Long tenant,
                              @Param("factory") String factory, @Param("code") String code);

}
