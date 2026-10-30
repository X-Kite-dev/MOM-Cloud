package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.mom.mes.dto.MesProductionOrderDTO;
import com.kitex.mom.mes.entity.MesApsBatchAllocationEntity;
import com.kitex.mom.mes.entity.MesPlanWithdrawalReceiptEntity;
import com.kitex.mom.mes.entity.MesProductionOrderEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.vo.MesProductionOrderQueryVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * <p>
 * 生产订单主表 Mapper 接口
 * </p>
 *
 * @author CodeLens
 * @since 2025-08-27
 */
@Mapper
public interface MesProductionOrderMapper extends BaseMapper<MesProductionOrderEntity> {

    String CAPACITY_TASK_COLUMNS = """
            t.id,t.tenant_id,t.factory_code,t.del_flag,t.code,t.production_order,t.mat_code,t.notice_num,t.status,
            t.resource_type,t.rework_request_id,t.source_task_code,t.formal_plan_contract_no,
            t.formal_plan_contract_id,t.formal_plan_allocation_snapshot,t.formal_plan_allocation_version,
            t.source_request_id,t.source_request_fingerprint,
            t.formal_plan_contract_version,t.formal_plan_contract_fingerprint,t.source_system,t.source_plan_no,
            t.source_plan_version,t.planning_policy_id,t.planning_policy_version,t.planning_policy_fingerprint,
            t.mrp_authority,t.erp_production_order_no,t.production_order_sync_mode,t.start_time,t.finish_time,
            t.complete_num,t.in_stock_num,t.un_in_stock_num,t.un_pass_num
            """;

    /**
     * 串行化同一租户、工厂和生产订单号的接收，防止拆批并发时重复建立订单主记录。
     *
     * @param lockKey 租户、工厂和订单号组成的稳定锁键
     * @return 数据库锁占位值
     */
    @Select("SELECT 1 AS locked FROM pg_advisory_xact_lock(hashtextextended(#{lockKey}, 0))")
    Integer lockReceivedOrder(@Param("lockKey") String lockKey);

    @Select("SELECT 1 AS locked FROM pg_advisory_xact_lock(hashtextextended(#{lockKey}, 0))")
    Integer lockFormalContract(@Param("lockKey") String lockKey);

    /**
     * 物理事实不受软删除或当前人员可见行范围影响，租户工厂由接收事务上下文显式传入。
     */
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("SELECT " + CAPACITY_TASK_COLUMNS + """
            FROM mom_mes.mes_task t
            WHERE t.tenant_id=#{tenantId} AND t.factory_code=#{factoryCode} AND t.production_order=#{orderNo}
            ORDER BY t.id
            """)
    List<MesTaskEntity> selectOrderCapacityTasks(@Param("tenantId") Long tenantId,
                                                 @Param("factoryCode") String factoryCode, @Param("orderNo") String orderNo);

    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("SELECT " + CAPACITY_TASK_COLUMNS + """
            FROM mom_mes.mes_task t WHERE t.tenant_id=#{tenantId} AND t.factory_code=#{factoryCode}
            AND t.formal_plan_contract_no=#{contractNo}
            AND (t.formal_plan_contract_version=#{version} OR t.formal_plan_contract_version IS NULL)
            ORDER BY t.id
            """)
    List<MesTaskEntity> selectContractCapacityTasks(@Param("tenantId") Long tenantId,
                                                    @Param("factoryCode") String factoryCode, @Param("contractNo") String contractNo, @Param("version") Integer version);

    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("""
            SELECT id,tenant_id,factory_code,del_flag,order_no,source_type,source_no,product_code,order_quantity,
                formal_plan_contract_id,formal_plan_contract_no,formal_plan_contract_version,formal_plan_contract_fingerprint,
                source_system,source_plan_no,source_plan_version,planning_policy_id,planning_policy_version,
                planning_policy_fingerprint,mrp_authority,erp_production_order_no
            FROM mom_mes.mes_production_order WHERE tenant_id=#{tenantId} AND factory_code=#{factoryCode}
            AND formal_plan_contract_no=#{contractNo}
            AND (formal_plan_contract_version=#{version} OR formal_plan_contract_version IS NULL)
            ORDER BY id
            """)
    List<MesProductionOrderEntity> selectContractCapacityOrders(@Param("tenantId") Long tenantId,
                                                                @Param("factoryCode") String factoryCode, @Param("contractNo") String contractNo, @Param("version") Integer version);

    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Update("""
            UPDATE mom_mes.mes_task SET formal_plan_allocation_snapshot=#{snapshot},formal_plan_allocation_version=1
            WHERE id=#{taskId} AND tenant_id=#{tenantId} AND factory_code=#{factoryCode}
            AND formal_plan_allocation_snapshot IS NULL AND formal_plan_allocation_version IS NULL
            """)
    int freezeContractAllocation(@Param("tenantId") Long tenantId, @Param("factoryCode") String factoryCode,
                                 @Param("taskId") Long taskId, @Param("snapshot") String snapshot);

    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("""
            SELECT r.id,r.tenant_id,r.factory_code,r.del_flag,r.request_id,r.request_fingerprint,r.issue_id,r.issue_no,
                r.expected_quantity,r.expected_evidence_fingerprint,r.actual_evidence_fingerprint,r.closed_task_count,
                r.closed_task_codes,r.closed_produce_task_count,r.closed_arrange_count,r.cancelled_qms_task_count,
                r.qms_evidence_fingerprint,r.status,r.reason,r.message,r.handled_time
            FROM mom_mes.mes_plan_withdrawal_receipt r
            WHERE r.tenant_id=#{tenantId} AND r.factory_code=#{factoryCode}
            AND EXISTS (SELECT 1 FROM mom_mes.mes_task t
                WHERE t.tenant_id=#{tenantId} AND t.factory_code=#{factoryCode}
                AND t.formal_plan_contract_no=#{contractNo} AND t.formal_plan_contract_version=#{version}
                AND position(',' || t.code || ',' IN ',' || r.closed_task_codes || ',')>0)
            ORDER BY r.id
            """)
    List<MesPlanWithdrawalReceiptEntity> selectContractWithdrawals(@Param("tenantId") Long tenantId,
                                                                   @Param("factoryCode") String factoryCode, @Param("contractNo") String contractNo, @Param("version") Integer version);

    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("""
            SELECT r.id,r.tenant_id,r.factory_code,r.del_flag,r.request_id,r.request_fingerprint,r.issue_id,r.issue_no,
                r.expected_quantity,r.expected_evidence_fingerprint,r.actual_evidence_fingerprint,r.closed_task_count,
                r.closed_task_codes,r.closed_produce_task_count,r.closed_arrange_count,r.cancelled_qms_task_count,
                r.qms_evidence_fingerprint,r.status,r.reason,r.message,r.handled_time
            FROM mom_mes.mes_plan_withdrawal_receipt r
            WHERE r.tenant_id=#{tenantId} AND r.factory_code=#{factoryCode}
            AND EXISTS (SELECT 1 FROM mom_mes.mes_task t
                WHERE t.tenant_id=#{tenantId} AND t.factory_code=#{factoryCode} AND t.production_order=#{orderNo}
                AND position(',' || t.code || ',' IN ',' || r.closed_task_codes || ',')>0)
            ORDER BY r.id
            """)
    List<MesPlanWithdrawalReceiptEntity> selectOrderWithdrawals(@Param("tenantId") Long tenantId,
                                                                @Param("factoryCode") String factoryCode, @Param("orderNo") String orderNo);

    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("""
            SELECT id,tenant_id,factory_code,del_flag,request_id,request_fingerprint,issue_id,issue_no,
                expected_quantity,expected_evidence_fingerprint,actual_evidence_fingerprint,closed_task_count,
                closed_task_codes,closed_produce_task_count,closed_arrange_count,cancelled_qms_task_count,
                qms_evidence_fingerprint,status,reason,message,handled_time
            FROM mom_mes.mes_plan_withdrawal_receipt WHERE tenant_id=#{tenantId} AND factory_code=#{factoryCode}
                AND issue_id=#{issueId} AND issue_no=#{issueNo} ORDER BY id
            """)
    List<MesPlanWithdrawalReceiptEntity> selectPlanWithdrawals(@Param("tenantId") Long tenantId,
                                                               @Param("factoryCode") String factoryCode, @Param("issueId") Long issueId, @Param("issueNo") String issueNo);

    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("<script>SELECT " + CAPACITY_TASK_COLUMNS + """
            FROM mom_mes.mes_task t WHERE t.tenant_id=#{tenantId} AND t.factory_code=#{factoryCode}
            AND t.code IN <foreach collection="codes" item="code" open="(" separator="," close=")">#{code}</foreach>
            ORDER BY t.id</script>
            """)
    List<MesTaskEntity> selectWithdrawalTasks(@Param("tenantId") Long tenantId,
                                              @Param("factoryCode") String factoryCode, @Param("codes") List<String> codes);

    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("""
            <script>SELECT id,tenant_id,factory_code,mes_task_id,mes_task_code,issue_id,issue_no,
                issue_quantity_snapshot,allocated_quantity,completed_quantity,in_stock_quantity,unqualified_quantity,
                quality_isolated_quantity,quality_released_quantity,quality_rejected_quantity,scrap_quantity,
                rework_quantity,rework_completed_quantity,rework_qualified_quantity,rework_rejected_quantity
            FROM mom_mes.mes_aps_batch_allocation WHERE tenant_id=#{tenantId} AND factory_code=#{factoryCode}
            AND mes_task_id IN <foreach collection="taskIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            ORDER BY id</script>
            """)
    List<MesApsBatchAllocationEntity> selectWithdrawalAllocations(@Param("tenantId") Long tenantId,
                                                                  @Param("factoryCode") String factoryCode, @Param("taskIds") List<Long> taskIds);

    /**
     * 分页查询生产订单（包含统计信息）
     *
     * @param page    分页参数
     * @param queryVO 查询条件
     * @return 生产订单分页数据
     */
    Page<MesProductionOrderDTO> selectProductionOrderPage(Page<MesProductionOrderDTO> page, @Param("query") MesProductionOrderQueryVO queryVO);

    /**
     * 根据订单ID查询生产订单详情
     *
     * @param orderId 订单ID
     * @return 生产订单详情
     */
    MesProductionOrderDTO selectProductionOrderById(@Param("orderId") Long orderId);

    /**
     * 根据订单编号查询生产订单详情
     *
     * @param orderNo 订单编号
     * @return 生产订单详情
     */
    MesProductionOrderDTO selectProductionOrderByOrderNo(@Param("orderNo") String orderNo);

    /**
     * 统计生产订单数量按状态分组
     *
     * @param factoryCode 工厂编码
     * @return 统计结果
     */
    List<Map<String, Object>> selectProductionOrderCountByStatus(@Param("factoryCode") String factoryCode);

    /**
     * 查询待排程的生产订单
     *
     * @param factoryCode 工厂编码
     * @return 待排程订单列表
     */
    List<MesProductionOrderDTO> selectPendingScheduleOrders(@Param("factoryCode") String factoryCode);

    /**
     * 查询超期未完成的生产订单
     *
     * @param factoryCode 工厂编码
     * @return 超期订单列表
     */
    List<MesProductionOrderDTO> selectOverdueOrders(@Param("factoryCode") String factoryCode);

    /**
     * 更新订单完成数量和剩余数量
     *
     * @param orderId          订单ID
     * @param finishedQuantity 完成数量
     * @return 更新结果
     */
    int updateOrderQuantities(@Param("orderId") Long orderId, @Param("finishedQuantity") java.math.BigDecimal finishedQuantity);
}
