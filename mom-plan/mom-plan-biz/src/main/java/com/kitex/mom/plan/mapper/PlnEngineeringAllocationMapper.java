package com.kitex.mom.plan.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * PLAN分配原件只读入口：显式租户/工厂，保留软删除事实，超限多取一条供领域层拒绝。
 */
@Mapper
public interface PlnEngineeringAllocationMapper {

	/**
	 * 来源计划原件，不用计划号跨组织猜主键。
	 */
	@Select("""
			<script>
			SELECT CASE WHEN sum(octet_length(raw)) OVER () &lt;= 8388608 THEN raw ELSE NULL END
			FROM (
				SELECT to_jsonb(t)::text AS raw FROM mom_plan.pln_issue t
				WHERE t.tenant_id=#{tenant} AND t.factory_code=#{factory}
				AND t.id IN
				<foreach collection="issues" item="id" open="(" separator="," close=")">#{id}</foreach>
				ORDER BY t.id LIMIT 5001
			) originals
			</script>
			""")
	List<String> issues(@Param("tenant") Long tenant, @Param("factory") String factory,
	                    @Param("issues") List<Long> issues);

	/**
	 * 来源计划的全部分配，包含未被本工单映射的其他批次。
	 */
	@Select("""
			<script>
			SELECT CASE WHEN sum(octet_length(raw)) OVER () &lt;= 8388608 THEN raw ELSE NULL END
			FROM (
				SELECT to_jsonb(t)::text AS raw FROM mom_plan.pln_scheduling_batch_allocation t
				WHERE t.tenant_id=#{tenant} AND t.factory_code=#{factory}
				AND t.issue_id IN
				<foreach collection="issues" item="id" open="(" separator="," close=")">#{id}</foreach>
				ORDER BY t.id LIMIT 5001
			) originals
			</script>
			""")
	List<String> allocations(@Param("tenant") Long tenant, @Param("factory") String factory,
	                         @Param("issues") List<Long> issues);

	/**
	 * 完整相关批次头，原工程与冻结分配文本均保留。
	 */
	@Select("""
			<script>
			SELECT CASE WHEN sum(octet_length(raw)) OVER () &lt;= 8388608 THEN raw ELSE NULL END
			FROM (
				SELECT to_jsonb(t)::text AS raw FROM mom_plan.pln_scheduling_batch t
				WHERE t.tenant_id=#{tenant} AND t.factory_code=#{factory}
				AND t.id IN
				<foreach collection="batches" item="id" open="(" separator="," close=")">#{id}</foreach>
				ORDER BY t.id LIMIT 5001
			) originals
			</script>
			""")
	List<String> batches(@Param("tenant") Long tenant, @Param("factory") String factory,
	                     @Param("batches") List<Long> batches);

	/**
	 * 原排程运行头与状态，不把排程锁解释为换版锁。
	 */
	@Select("""
			<script>
			SELECT CASE WHEN sum(octet_length(raw)) OVER () &lt;= 8388608 THEN raw ELSE NULL END
			FROM (
				SELECT to_jsonb(t)::text AS raw FROM mom_plan.pln_scheduling_task t
				WHERE t.tenant_id=#{tenant} AND t.factory_code=#{factory}
				AND t.id IN
				<foreach collection="tasks" item="id" open="(" separator="," close=")">#{id}</foreach>
				ORDER BY t.id LIMIT 5001
			) originals
			</script>
			""")
	List<String> tasks(@Param("tenant") Long tenant, @Param("factory") String factory,
	                   @Param("tasks") List<Long> tasks);


	/**
	 * 来源计划的其他批次，以及命中批次的其他来源计划分配，完整保留合批关系。
	 */
	@Select("""
			<script>
			SELECT CASE WHEN sum(octet_length(raw)) OVER () &lt;= 8388608 THEN raw ELSE NULL END
			FROM (
				SELECT to_jsonb(t)::text AS raw FROM mom_plan.pln_scheduling_batch_allocation t
				WHERE t.tenant_id=#{tenant} AND t.factory_code=#{factory}
				AND (t.issue_id IN
				<foreach collection="issues" item="id" open="(" separator="," close=")">#{id}</foreach>
				OR t.batch_id IN
				<foreach collection="batches" item="id" open="(" separator="," close=")">#{id}</foreach>)
				ORDER BY t.id LIMIT 5001
			) originals
			</script>
			""")
	List<String> allocationScope(@Param("tenant") Long tenant, @Param("factory") String factory,
	                             @Param("issues") List<Long> issues, @Param("batches") List<Long> batches);

	/**
	 * 同一来源计划或同一批次的全部工序资源，保留同批其他计划及历史删除行。
	 */
	@Select("""
			<script>
			SELECT CASE WHEN sum(octet_length(raw)) OVER () &lt;= 8388608 THEN raw ELSE NULL END
			FROM (
				SELECT to_jsonb(t)::text AS raw FROM mom_plan.pln_scheduling_operation t
				WHERE t.tenant_id=#{tenant} AND t.factory_code=#{factory}
				AND (t.issue_id IN
				<foreach collection="issues" item="id" open="(" separator="," close=")">#{id}</foreach>
				OR t.scheduling_batch_id IN
				<foreach collection="batches" item="id" open="(" separator="," close=")">#{id}</foreach>)
				ORDER BY t.id LIMIT 10001
			) originals
			</script>
			""")
	List<String> operations(@Param("tenant") Long tenant, @Param("factory") String factory,
	                        @Param("issues") List<Long> issues, @Param("batches") List<Long> batches);
}
