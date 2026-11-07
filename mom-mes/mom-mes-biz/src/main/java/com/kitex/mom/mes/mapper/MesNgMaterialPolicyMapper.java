package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import lombok.Data;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 独立NG POLICY永久账；全部SQL显式限定实际租户工厂。
 */
@Mapper
@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
public interface MesNgMaterialPolicyMapper {
	/**
	 * 核验V4安装的全部真实保护是否就绪；缺失返回false，不代替业务授权。
	 */
	@Select("SELECT \"mom_mes\".mom_ng_material_policy_ready_v1()")
	Boolean ready();

	/**
	 * 尝试取得本事务原号锁；返回false表示仍在执行，不允许换号猜结果。
	 */
	@Select("SELECT pg_try_advisory_xact_lock(hashtextextended(#{key},0))")
	Boolean tryRequest(@Param("key") String key);

	/**
	 * 写入真实同事务NG原账头；调用方必须校验影响行数为1。
	 */
	@Insert("INSERT INTO \"mom_mes\".mes_ng_material_policy_operation (id,tenant_id,source_unqualified_report_id,"
			+ "source_birth_id,actor_id,gate_id,factory_code,request_id,task_code,produce_task_code,arrange_code,"
			+ "route_node_code,production_unit,source_fingerprint,policy_snapshot,route_snapshot,bom_snapshot,"
			+ "evidence_version,evidence_snapshot,evidence_fingerprint,processed_quantity) VALUES (#{id},#{tenantId},"
			+ "#{sourceUnqualifiedReportId},#{sourceBirthId},#{actorId},#{gateId},#{factoryCode},#{requestId},"
			+ "#{taskCode},#{produceTaskCode},#{arrangeCode},#{routeNodeCode},#{productionUnit},#{sourceFingerprint},"
			+ "#{policySnapshot},#{routeSnapshot},#{bomSnapshot},#{evidenceVersion},#{evidenceSnapshot},"
			+ "#{evidenceFingerprint},#{processedQuantity})")
	int insertHeader(Header row);

	/**
	 * 写入原账的一项完整真实份额；调用方必须校验影响行数为1。
	 */
	@Insert("INSERT INTO \"mom_mes\".mes_ng_material_policy_allocation (id,operation_id,tenant_id,source_id,"
			+ "source_flow_id,factory_code,source_kind,requirement_mat_code,requirement_unit,mat_code,mat_unit,"
			+ "source_snapshot,quantity,requirement_quantity,substitute_ratio,before_quantity,after_quantity,"
			+ "substitute_flag) VALUES (#{id},#{operationId},#{tenantId},#{sourceId},#{sourceFlowId},#{factoryCode},"
			+ "#{sourceKind},#{requirementMatCode},#{requirementUnit},#{matCode},#{matUnit},#{sourceSnapshot},"
			+ "#{quantity},#{requirementQuantity},#{substituteRatio},#{beforeQuantity},#{afterQuantity},"
			+ "#{substituteFlag})")
	int insertLine(Line row);

	/**
	 * 写入唯一原账恢复命令；重复原号或原账由约束拒绝。
	 */
	@Insert("INSERT INTO \"mom_mes\".mes_ng_material_policy_reversal (id,tenant_id,operation_id,actor_id,factory_code,"
			+ "request_id,command_version,command_snapshot,command_fingerprint,evidence_version,evidence_snapshot,"
			+ "evidence_fingerprint) VALUES (#{id},#{tenantId},#{operationId},#{actorId},#{factoryCode},#{requestId},"
			+ "#{commandVersion},#{commandSnapshot},#{commandFingerprint},#{evidenceVersion},#{evidenceSnapshot},"
			+ "#{evidenceFingerprint})")
	int insertReversal(Reversal row);

	/**
	 * 写入唯一原份额的精确恢复事实；调用方必须校验影响行数为1。
	 */
	@Insert("INSERT INTO \"mom_mes\".mes_ng_material_policy_restore (id,tenant_id,reversal_id,operation_id,"
			+ "allocation_id,source_id,source_flow_id,restore_flow_id,factory_code,source_kind,mat_code,mat_unit,"
			+ "quantity,before_quantity,after_quantity) VALUES (#{id},#{tenantId},#{reversalId},#{operationId},"
			+ "#{allocationId},#{sourceId},#{sourceFlowId},#{restoreFlowId},#{factoryCode},#{sourceKind},#{matCode},"
			+ "#{matUnit},#{quantity},#{beforeQuantity},#{afterQuantity})")
	int insertRestore(Restore row);

	/**
	 * 按实际组织及真实原NG身份读取唯一原账；不存在返回null。
	 */
	@Select("SELECT * FROM \"mom_mes\".mes_ng_material_policy_operation WHERE tenant_id=#{tenant} AND "
			+ "factory_code=#{factory} AND source_unqualified_report_id=#{source}")
	Header original(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("source") Long source);

	/**
	 * 按实际组织及账主键读取原件；跨组织不返回结果。
	 */
	@Select("SELECT * FROM \"mom_mes\".mes_ng_material_policy_operation WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND id=#{id}")
	Header operation(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("id") Long id);

	/**
	 * 读取实际组织内原账完整份额；5001行为超限哨兵，不得当作已完整。
	 */
	@Select("SELECT * FROM \"mom_mes\".mes_ng_material_policy_allocation WHERE tenant_id=#{tenant} AND "
			+ "factory_code=#{factory} AND operation_id=#{id} ORDER BY id LIMIT 5001")
	List<Line> lines(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("id") Long id);

	/**
	 * 按实际租户原号回读；服务仍须核对实际工厂、本人及完整原文。
	 */
	@Select("SELECT * FROM \"mom_mes\".mes_ng_material_policy_reversal WHERE tenant_id=#{tenant} AND request_id=#{request}")
	Reversal request(@Param("tenant") Long tenant, @Param("request") String request);

	/**
	 * 读取实际组织内该原账唯一恢复原件；未恢复返回null。
	 */
	@Select("SELECT * FROM \"mom_mes\".mes_ng_material_policy_reversal WHERE tenant_id=#{tenant} AND "
			+ "factory_code=#{factory} AND operation_id=#{id}")
	Reversal attached(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("id") Long id);

	/**
	 * 读取实际组织内恢复完整份额；5001行为超限哨兵。
	 */
	@Select("SELECT * FROM \"mom_mes\".mes_ng_material_policy_restore WHERE tenant_id=#{tenant} AND "
			+ "factory_code=#{factory} AND reversal_id=#{id} ORDER BY id LIMIT 5001")
	List<Restore> restores(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("id") Long id);

	/**
	 * 不能把旧assert函数对NULL birth_version的RETURN误读为真实出生。
	 */
	@Select("SELECT h.* FROM \"mom_mes\".mes_warehouse_feed f JOIN \"mom_mes\".mes_warehouse_feed_birth h ON h.feed_id=f.id "
			+ "WHERE f.tenant_id=#{tenant} AND f.factory_code=#{factory} AND f.id=#{id} AND f.birth_version='MES_FEED_BIRTH_V1' "
			+ "AND h.created_xid=f.birth_created_xid AND h.tenant_id=f.tenant_id AND h.factory_code=f.factory_code "
			+ "AND h.quantity=f.feed_num AND h.unit=f.mat_unit AND h.evidence_version='MES_FEED_BIRTH_V1' AND h.status='RECORDED' AND h.del_flag='0'")
	com.kitex.mom.mes.entity.MesWarehouseFeedBirthEntity feedBirth(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("id") Long id);

	/**
	 * 原stockId精确读取包含原生归零归档；不把逻辑删除误认为库存不存在。
	 */
	@Select("SELECT * FROM \"mom_mes\".mes_warehouse_live_stock WHERE tenant_id=#{tenant} AND factory_code=#{factory} "
			+ "AND id=#{id} FOR UPDATE NOWAIT")
	com.kitex.mom.mes.entity.MesWarehouseLiveStockEntity rawStock(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("id") Long id);

	/**
	 * 仅对原stockId、实际组织、原余额及归档态作CAS恢复；返回0须整笔回滚。
	 */
	@Update("UPDATE \"mom_mes\".mes_warehouse_live_stock SET num=#{after},del_flag='0',update_by=#{actor},update_time=localtimestamp "
			+ "WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND id=#{id} AND num=#{before} AND del_flag=#{delFlag}")
	int restoreStock(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("id") Long id,
	                 @Param("before") java.math.BigDecimal before, @Param("after") java.math.BigDecimal after,
	                 @Param("delFlag") String delFlag, @Param("actor") String actor);

	/**
	 * 原始operation typed投影；无默认值，不接受客户端实体。
	 */
	@Data
	class Header {
		/**
		 * 本行真实主键。
		 */
		private Long id;
		/**
		 * 实际认证租户主键。
		 */
		private Long tenantId;
		/**
		 * 原真实NG报告主键。
		 */
		private Long sourceUnqualifiedReportId;
		/**
		 * 与原NG同事务的真实出生事实主键。
		 */
		private Long sourceBirthId;
		/**
		 * 实际操作人员主键。
		 */
		private Long actorId;
		/**
		 * 原START_CHECK开工政策记录主键。
		 */
		private Long gateId;
		/**
		 * 实际授权工厂编码。
		 */
		private String factoryCode;
		/**
		 * 租户内永久请求号。
		 */
		private String requestId;
		/**
		 * 原生产工单编码。
		 */
		private String taskCode;
		/**
		 * 原工序任务编码。
		 */
		private String produceTaskCode;
		/**
		 * 原派工编码。
		 */
		private String arrangeCode;
		/**
		 * 原工艺稳定节点编码。
		 */
		private String routeNodeCode;
		/**
		 * 原NG加工量单位。
		 */
		private String productionUnit;
		/**
		 * 原NG来源版本与原文绑定的SHA-256。
		 */
		private String sourceFingerprint;
		/**
		 * 原开工逐料政策及完整来源池绑定原文。
		 */
		private String policySnapshot;
		/**
		 * 原工单冻结工艺快照。
		 */
		private String routeSnapshot;
		/**
		 * 原工单冻结BOM快照。
		 */
		private String bomSnapshot;
		/**
		 * 不可变证据协议版本。
		 */
		private String evidenceVersion;
		/**
		 * 完整typed事实集合的证据JSON原文。
		 */
		private String evidenceSnapshot;
		/**
		 * 证据版本与原文绑定的SHA-256。
		 */
		private String evidenceFingerprint;
		/**
		 * 真实原NG加工数量，单位为productionUnit。
		 */
		private java.math.BigDecimal processedQuantity;
	}

	/**
	 * 原始allocation typed投影；无默认值，不接受客户端实体。
	 */
	@Data
	class Line {
		/**
		 * 本行真实主键。
		 */
		private Long id;
		/**
		 * 所属原NG逐料耗用账主键。
		 */
		private Long operationId;
		/**
		 * 实际认证租户主键。
		 */
		private Long tenantId;
		/**
		 * 原FEED投料或STOCK库存主键。
		 */
		private Long sourceId;
		/**
		 * 原STOCK真实OUT主键；FEED为空。
		 */
		private Long sourceFlowId;
		/**
		 * 实际授权工厂编码。
		 */
		private String factoryCode;
		/**
		 * 来源类型：FEED原投料、STOCK原库存。
		 */
		private String sourceKind;
		/**
		 * 原冻结需求物料编码。
		 */
		private String requirementMatCode;
		/**
		 * 原需求当量单位。
		 */
		private String requirementUnit;
		/**
		 * 实际来源物料编码。
		 */
		private String matCode;
		/**
		 * 实际来源计量单位。
		 */
		private String matUnit;
		/**
		 * 原来源身份和出生或原生OUT回执原文。
		 */
		private String sourceSnapshot;
		/**
		 * 原正量份额，单位为matUnit；恢复使用同量。
		 */
		private java.math.BigDecimal quantity;
		/**
		 * 原需求当量，单位为requirementUnit。
		 */
		private java.math.BigDecimal requirementQuantity;
		/**
		 * 实际份额与需求当量之比，沿原BOM冻结值。
		 */
		private java.math.BigDecimal substituteRatio;
		/**
		 * 本次动作前余额；FEED为已用量，STOCK为库存量，单位为matUnit。
		 */
		private java.math.BigDecimal beforeQuantity;
		/**
		 * 本次动作后余额，与beforeQuantity同单位。
		 */
		private java.math.BigDecimal afterQuantity;
		/**
		 * 是否使用原冻结BOM允许的替代物料；不允许未知。
		 */
		private Boolean substituteFlag;
	}

	/**
	 * 原始reversal typed投影；无默认值，不接受客户端实体。
	 */
	@Data
	class Reversal {
		/**
		 * 本行真实主键。
		 */
		private Long id;
		/**
		 * 实际认证租户主键。
		 */
		private Long tenantId;
		/**
		 * 所属原NG逐料耗用账主键。
		 */
		private Long operationId;
		/**
		 * 实际操作人员主键。
		 */
		private Long actorId;
		/**
		 * 实际授权工厂编码。
		 */
		private String factoryCode;
		/**
		 * 租户内永久请求号。
		 */
		private String requestId;
		/**
		 * 恢复命令协议版本。
		 */
		private String commandVersion;
		/**
		 * 原号绑定的完整恢复命令原文。
		 */
		private String commandSnapshot;
		/**
		 * 命令版本与原文绑定的SHA-256。
		 */
		private String commandFingerprint;
		/**
		 * 不可变证据协议版本。
		 */
		private String evidenceVersion;
		/**
		 * 完整typed事实集合的证据JSON原文。
		 */
		private String evidenceSnapshot;
		/**
		 * 证据版本与原文绑定的SHA-256。
		 */
		private String evidenceFingerprint;
	}

	/**
	 * 原始restore typed投影；无默认值，不接受客户端实体。
	 */
	@Data
	class Restore {
		/**
		 * 本行真实主键。
		 */
		private Long id;
		/**
		 * 实际认证租户主键。
		 */
		private Long tenantId;
		/**
		 * 所属独立全份额恢复账主键。
		 */
		private Long reversalId;
		/**
		 * 所属原NG逐料耗用账主键。
		 */
		private Long operationId;
		/**
		 * 唯一对应的原耗用分配行主键。
		 */
		private Long allocationId;
		/**
		 * 原FEED投料或STOCK库存主键。
		 */
		private Long sourceId;
		/**
		 * 原STOCK真实OUT主键；FEED为空。
		 */
		private Long sourceFlowId;
		/**
		 * STOCK精确原库存恢复的真实IN主键；FEED为空。
		 */
		private Long restoreFlowId;
		/**
		 * 实际授权工厂编码。
		 */
		private String factoryCode;
		/**
		 * 来源类型：FEED原投料、STOCK原库存。
		 */
		private String sourceKind;
		/**
		 * 实际来源物料编码。
		 */
		private String matCode;
		/**
		 * 实际来源计量单位。
		 */
		private String matUnit;
		/**
		 * 原正量份额，单位为matUnit；恢复使用同量。
		 */
		private java.math.BigDecimal quantity;
		/**
		 * 本次动作前余额；FEED为已用量，STOCK为库存量，单位为matUnit。
		 */
		private java.math.BigDecimal beforeQuantity;
		/**
		 * 本次动作后余额，与beforeQuantity同单位。
		 */
		private java.math.BigDecimal afterQuantity;
	}

}
