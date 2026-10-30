package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.mes.entity.MesPhysicalTransformExecutionEntity;
import org.apache.ibatis.annotations.*;

/**
 * 正式转换主账、精确库存批量写及永久回执查询。
 */
@Mapper
public interface MesPhysicalTransformExecutionMapper extends BaseMapper<MesPhysicalTransformExecutionEntity> {

    /**
     * 永久原号只有一个执行结果；不经逻辑删除或工厂过滤隐藏未知结果。
     */
    @InterceptorIgnore(dataPermission = "true")
    @Select("SELECT * FROM mes_physical_transform_execution WHERE tenant_id=#{tenantId} AND command_id=#{commandId}")
    MesPhysicalTransformExecutionEntity permanent(@Param("tenantId") Long tenantId,
                                                  @Param("commandId") Long commandId);

    /**
     * Java可先于SQL部署，未安装时只允许查询返回UNAVAILABLE，不能假装没有执行。
     */
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("SELECT to_regclass('\"mom_mes\".mes_physical_transform_execution') IS NOT NULL")
    Boolean available();

    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("SELECT to_regprocedure('\"mom_mes\".mom_physical_transform_execution_ready_312()') IS NOT NULL")
    Boolean readinessAvailable();

    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("SELECT \"mom_mes\".mom_physical_transform_execution_ready_312()")
    Boolean ready();

    /**
     * 与动态标识共用同一规范码锁；false时不等待、不改用另一标识。
     */
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("SELECT \"mom_mes\".mom_try_lock_binding_identifier(#{tenantId},#{factoryCode},#{identifier})")
    Boolean tryIdentifierLock(@Param("tenantId") Long tenantId, @Param("factoryCode") String factoryCode,
                              @Param("identifier") String identifier);

    /**
     * 历史任一实际身份域已有同码即拒绝；不能只查当前正量库存。
     */
    @InterceptorIgnore(dataPermission = "true")
    @Select("SELECT EXISTS("
            + "SELECT 1 FROM mes_warehouse_live_stock s WHERE s.tenant_id=#{tenantId} AND s.factory_code=#{factoryCode} AND s.mat_barcode=#{identifier} "
            + "UNION ALL SELECT 1 FROM mes_task_report r WHERE r.tenant_id=#{tenantId} AND r.factory_code=#{factoryCode} AND r.transfer_code=#{identifier} "
            + "UNION ALL SELECT 1 FROM mes_identifier_mapping m WHERE m.tenant_id=#{tenantId} AND m.factory_code=#{factoryCode} AND (m.alias_code=#{identifier} OR m.canonical_code=#{identifier}) "
            + "UNION ALL SELECT 1 FROM mes_identifier_binding b WHERE b.tenant_id=#{tenantId} AND b.factory_code=#{factoryCode} AND (b.external_code=#{identifier} OR b.canonical_identifier=#{identifier}) "
            + "UNION ALL SELECT 1 FROM mes_carrier_item c WHERE c.tenant_id=#{tenantId} AND c.factory_code=#{factoryCode} AND c.identifier=#{identifier} "
            + "UNION ALL SELECT 1 FROM mes_wrap_record w WHERE w.tenant_id=#{tenantId} AND w.factory_code=#{factoryCode} AND w.transfer_code=#{identifier} "
            + "UNION ALL SELECT 1 FROM mes_physical_transform_output o WHERE o.tenant_id=#{tenantId} AND o.factory_code=#{factoryCode} AND o.identifier=#{identifier})")
    Boolean identifierExists(@Param("tenantId") Long tenantId, @Param("factoryCode") String factoryCode,
                             @Param("identifier") String identifier);

    /**
     * 已持精确stock行锁后一次CAS全部投入；0/少行即整笔回滚。
     */
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Update("WITH x AS (SELECT * FROM jsonb_to_recordset(CAST(#{rowsJson} AS jsonb)) "
            + "AS r(stock_id text,tenant_id text,factory_code text,before_quantity text,after_quantity text,update_by text,update_time text)) "
            + "UPDATE \"mom_mes\".mes_warehouse_live_stock s SET num=x.after_quantity::numeric,"
            + "del_flag=CASE WHEN x.after_quantity::numeric=0 THEN '1' ELSE s.del_flag END,"
            + "update_by=x.update_by,update_time=x.update_time::timestamp FROM x "
            + "WHERE s.id=x.stock_id::bigint AND s.tenant_id=x.tenant_id::bigint AND s.factory_code=x.factory_code "
            + "AND s.del_flag='0' AND s.num=x.before_quantity::numeric")
    int consumeStocks(@Param("rowsJson") String rowsJson);

    /**
     * GOOD输出一次建成typed转换库存；所有ID由服务端预分配并在同事务回读。
     */
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Insert("INSERT INTO \"mom_mes\".mes_warehouse_live_stock "
            + "(id,tenant_id,factory_code,physical_transform_output_id,bill_no,produce_task_code,route_code,route_name,"
            + "mat_code,mat_name,mat_spec,mat_batch,mat_unit,mat_barcode,warehouse_code,warehouse_name,site_code,site_name,"
            + "in_time,num,status,inspection_status,mat_detail_type,create_by,create_time,del_flag) "
            + "SELECT x.id::bigint,x.tenant_id::bigint,x.factory_code,x.output_id::bigint,x.bill_no,x.produce_task_code,"
            + "x.route_code,x.route_name,x.mat_code,x.mat_name,x.mat_spec,NULLIF(x.mat_batch,''),x.mat_unit,x.mat_barcode,"
            + "NULLIF(x.warehouse_code,''),NULLIF(x.warehouse_name,''),NULLIF(x.site_code,''),NULLIF(x.site_name,''),"
            + "x.in_time::timestamp,x.quantity::numeric,'0','RELEASED','WIP',x.create_by,x.create_time::timestamp,'0' "
            + "FROM jsonb_to_recordset(CAST(#{rowsJson} AS jsonb)) AS x(id text,tenant_id text,factory_code text,output_id text,"
            + "bill_no text,produce_task_code text,route_code text,route_name text,mat_code text,mat_name text,mat_spec text,"
            + "mat_batch text,mat_unit text,mat_barcode text,warehouse_code text,warehouse_name text,site_code text,site_name text,"
            + "in_time text,quantity text,create_by text,create_time text)")
    int insertOutputStocks(@Param("rowsJson") String rowsJson);

    /**
     * 输入OUT与GOOD输出IN流水一次写入，SCRAP/MISSING只在输出事实中保存。
     */
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Insert("INSERT INTO \"mom_mes\".mes_warehouse_inout "
            + "(id,tenant_id,factory_code,physical_transform_execution_id,physical_transform_output_id,source_stock_id,source_report_id,"
            + "bill_id,bill_no,resource_no,produce_task_code,mat_code,mat_name,mat_spec,mat_batch,mat_unit,mat_barcode,"
            + "warehouse_code,warehouse_name,site_code,site_name,operate_num,operate_type,biz_type,inspection_status,mat_detail_type,"
            + "stock_before_num,stock_after_num,stock_proof_version,allocation_policy,create_by,create_time,del_flag) "
            + "SELECT x.id::bigint,x.tenant_id::bigint,x.factory_code,x.execution_id::bigint,NULLIF(x.output_id,'')::bigint,"
            + "NULLIF(x.source_stock_id,'')::bigint,NULLIF(x.source_report_id,'')::bigint,x.execution_id::bigint,x.bill_no,x.resource_no,"
            + "x.produce_task_code,x.mat_code,x.mat_name,x.mat_spec,NULLIF(x.mat_batch,''),x.mat_unit,NULLIF(x.mat_barcode,''),"
            + "NULLIF(x.warehouse_code,''),NULLIF(x.warehouse_name,''),NULLIF(x.site_code,''),NULLIF(x.site_name,''),"
            + "x.quantity::numeric,x.operate_type,x.biz_type,x.inspection_status,'WIP',x.before_quantity::numeric,x.after_quantity::numeric,"
            + "'PHYSICAL_TRANSFORM_V1',x.allocation_policy,x.create_by,x.create_time::timestamp,'0' "
            + "FROM jsonb_to_recordset(CAST(#{rowsJson} AS jsonb)) AS x(id text,tenant_id text,factory_code text,execution_id text,"
            + "output_id text,source_stock_id text,source_report_id text,bill_no text,resource_no text,produce_task_code text,"
            + "mat_code text,mat_name text,mat_spec text,mat_batch text,mat_unit text,mat_barcode text,warehouse_code text,"
            + "warehouse_name text,site_code text,site_name text,quantity text,operate_type text,biz_type text,inspection_status text,"
            + "before_quantity text,after_quantity text,allocation_policy text,create_by text,create_time text)")
    int insertFlows(@Param("rowsJson") String rowsJson);
}
