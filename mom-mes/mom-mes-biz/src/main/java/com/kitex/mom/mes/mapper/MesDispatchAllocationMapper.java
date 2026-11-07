package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.kitex.mom.mes.entity.MesDispatchAllocationEntity;
import com.kitex.mom.mes.entity.MesDispatchCommandEntity;
import com.kitex.mom.mes.entity.MesProduceTaskEntity;
import com.kitex.mom.mes.entity.MesExecutionTemplateEntity;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 派工原命令、逐行出生配额及只读生产事实；每个查询显式限定实际租户工厂。
 * 锁后强制刷新会话缓存，未知回执不得复用首次不存在的旧结果。
 */
@Mapper
@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
public interface MesDispatchAllocationMapper {

	/** 核对V4配额表、永久来源和终态守卫已安装且未漂移；不是现场验收。 */
	@Select("SELECT mom_mes.mes_dispatch_allocation_v1_ready()")
	Integer ready();

	/** 在原事务内串行同组织同原号的受理、重放和永久取消。 */
	@Select("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(CONCAT('MES_DISPATCH_ALLOCATION:',"
			+ "#{tenant}, ':', #{factory}, ':', #{requestId}), 0))")
	Integer lockRequest(@Param("tenant") Long tenant, @Param("factory") String factory,
			@Param("requestId") String requestId);

	/** 返回原号永久原件；读取包含取消，不能以逻辑删除规避原号。 */
	@Select("SELECT * FROM mom_mes.mes_dispatch_command WHERE tenant_id=#{tenant} "
			+ "AND factory_code=#{factory} AND request_id=#{requestId}")
	@Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
	MesDispatchCommandEntity command(@Param("tenant") Long tenant, @Param("factory") String factory,
			@Param("requestId") String requestId);

	/** 读取实际工单；编码冲突交由调用方拒绝。 */
	@Select("SELECT * FROM mom_mes.mes_task WHERE tenant_id=#{tenant} AND factory_code=#{factory} "
			+ "AND code=#{taskCode} AND del_flag='0'")
	@Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
	List<MesTaskEntity> tasks(@Param("tenant") Long tenant, @Param("factory") String factory,
			@Param("taskCode") String taskCode);

	/** 读取工单完整冻结工序，按真实主键稳定排序。 */
	@Select("SELECT * FROM mom_mes.mes_produce_task WHERE tenant_id=#{tenant} AND factory_code=#{factory} "
			+ "AND task_code=#{taskCode} AND del_flag='0' ORDER BY id")
	@Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
	List<MesProduceTaskEntity> operations(@Param("tenant") Long tenant, @Param("factory") String factory,
			@Param("taskCode") String taskCode);

	/** 包括已退派软删除原件，不能把历史删除解释为从未派工。 */
	@Select("SELECT * FROM mom_mes.mes_task_arrange WHERE tenant_id=#{tenant} AND factory_code=#{factory} "
			+ "AND task_code=#{taskCode} ORDER BY id")
	@Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
	List<MesTaskArrangeEntity> arrangements(@Param("tenant") Long tenant, @Param("factory") String factory,
			@Param("taskCode") String taskCode);

	/** 读取完整不可删除的配额原件，包含已结算原件。 */
	@Select("SELECT * FROM mom_mes.mes_dispatch_allocation WHERE tenant_id=#{tenant} "
			+ "AND factory_code=#{factory} AND task_code=#{taskCode} ORDER BY id")
	@Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
	List<MesDispatchAllocationEntity> allocations(@Param("tenant") Long tenant, @Param("factory") String factory,
			@Param("taskCode") String taskCode);

	/** 精确读取原派工的出生配额，不按工序任取。 */
	@Select("SELECT * FROM mom_mes.mes_dispatch_allocation WHERE tenant_id=#{tenant} "
			+ "AND factory_code=#{factory} AND arrange_id=#{arrangeId}")
	@Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
	MesDispatchAllocationEntity allocation(@Param("tenant") Long tenant, @Param("factory") String factory,
			@Param("arrangeId") Long arrangeId);

	/** 只读新派工当前可用模板，匹配仍使用原模板选择算法。 */
	@Select("SELECT * FROM mom_mes.mes_execution_template WHERE tenant_id=#{tenant} "
			+ "AND factory_code=#{factory} AND del_flag='0' AND status='PUBLISHED'")
	@Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
	List<MesExecutionTemplateEntity> templates(@Param("tenant") Long tenant, @Param("factory") String factory);

	/**
	 * 目标级所有执行证据计数，包括已逆向或软删除事实；逆向不能把已执行目标改成从未执行。
	 * 已接收但未使用的工装允许原退派释放，真实工装使用、投料或时间原件均阻止额度释放。
	 * 已核定的全局派工ID不以事实行的组织再次过滤；错组织/NULL组织的异常旧事实同样阻断零执行证明。
	 */
	@Select("SELECT (SELECT COUNT(*) FROM mom_mes.mes_task_report WHERE tenant_id=#{tenant} "
			+ "AND factory_code=#{factory} AND arrange_code=#{arrangeCode}) "
			+ "+ (SELECT COUNT(*) FROM mom_mes.mes_task_report_unqualified WHERE tenant_id=#{tenant} "
			+ "AND factory_code=#{factory} AND arrange_code=#{arrangeCode}) "
			+ "+ (SELECT COUNT(*) FROM mom_mes.mes_warehouse_feed WHERE tenant_id=#{tenant} "
			+ "AND factory_code=#{factory} AND arrange_code=#{arrangeCode}) "
			+ "+ (SELECT COUNT(*) FROM mom_mes.mes_task_execution_segment WHERE arrange_id=#{arrangeId} "
			+ "OR (tenant_id=#{tenant} AND factory_code=#{factory} AND arrange_code=#{arrangeCode})) "
			+ "+ (SELECT COUNT(*) FROM mom_mes.mes_workpiece_time_session WHERE arrange_id=#{arrangeId}) "
			+ "+ (SELECT COUNT(*) FROM mom_mes.mes_manufacturing_piece WHERE arrange_id=#{arrangeId}) "
			+ "+ (SELECT COUNT(*) FROM mom_mes.mes_start_material_binding WHERE arrange_id=#{arrangeId}) "
			+ "+ (SELECT COUNT(*) FROM mom_mes.mes_tooling_execution WHERE tenant_id=#{tenant} "
			+ "AND factory_code=#{factory} AND arrange_code=#{arrangeCode} "
			+ "AND (status IS NULL OR status NOT IN ('CHECKED_IN','RELEASED') OR start_time IS NOT NULL "
			+ "OR actual_life_usage IS NOT NULL OR NULLIF(life_consumption_request_id,'') IS NOT NULL))")
	long executionFacts(@Param("tenant") Long tenant, @Param("factory") String factory,
			@Param("arrangeId") Long arrangeId, @Param("arrangeCode") String arrangeCode);

	/** 写入完整ACCEPTED或CANCELLED原件；提交后禁止更改或删除。 */
	@Insert("INSERT INTO mom_mes.mes_dispatch_command(id,tenant_id,factory_code,request_id,request_fingerprint,"
			+ "actor_id,actor_code,task_code,status,request_json,result_json,cancel_reason,create_by,create_time,"
			+ "update_by,update_time,del_flag) VALUES(#{id},#{tenantId},#{factoryCode},#{requestId},#{requestFingerprint},"
			+ "#{actorId},#{actorCode},#{taskCode},#{status},#{requestJson},#{resultJson},#{cancelReason},"
			+ "#{createBy},#{createTime},#{updateBy},#{updateTime},'0')")
	int insertCommand(MesDispatchCommandEntity row);

	/** 真实派工保存前写入出生配额，提交时由数据库延迟核对真实派工与完整命令。 */
	@Insert("INSERT INTO mom_mes.mes_dispatch_allocation(id,tenant_id,factory_code,command_id,allocation_key,"
			+ "task_id,task_code,produce_task_id,produce_task_code,source_route_node_code,engineering_release_id,"
			+ "engineering_content_hash,arrange_id,arrange_code,allocated_quantity,occupied_quantity,"
			+ "settlement_status,settled_time,create_by,create_time,update_by,update_time,del_flag) "
			+ "VALUES(#{id},#{tenantId},#{factoryCode},#{commandId},#{allocationKey},#{taskId},#{taskCode},"
			+ "#{produceTaskId},#{produceTaskCode},#{sourceRouteNodeCode},#{engineeringReleaseId},"
			+ "#{engineeringContentHash},#{arrangeId},#{arrangeCode},#{allocatedQuantity},#{occupiedQuantity},"
			+ "#{settlementStatus},#{settledTime},#{createBy},#{createTime},#{updateBy},#{updateTime},'0')")
	int insertAllocation(MesDispatchAllocationEntity row);

	/** 只允许尚未结算原件迁移一次；占用不随报工冲销变化。 */
	@Update("UPDATE mom_mes.mes_dispatch_allocation SET occupied_quantity=#{occupied},settlement_status=#{status},"
			+ "settled_time=#{time},update_by=#{actor},update_time=#{time} WHERE id=#{id} "
			+ "AND tenant_id=#{tenant} AND factory_code=#{factory} AND settlement_status='RESERVED' "
			+ "AND occupied_quantity=allocated_quantity AND settled_time IS NULL")
	int settle(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("id") Long id,
			@Param("occupied") BigDecimal occupied, @Param("status") String status,
			@Param("time") LocalDateTime time, @Param("actor") String actor);
}
