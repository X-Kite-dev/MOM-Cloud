package com.kitex.mom.mes.mapper;

import com.kitex.mom.mes.entity.*;

import lombok.Data;

import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 制造出生与首轮归属原件持久化；所有业务查询显式限定实际租户和工厂，写入由服务事务与数据库守卫保护。
 */
@Mapper
@com.baomidou.mybatisplus.annotation.InterceptorIgnore(tenantLine = "true", dataPermission = "true")
public interface MesManufacturingPieceMapper {
	/**
	 * 制造出生及原首轮归属的数据库原文与索引投影，均限定实际租户工厂。
	 */
	@Data
	class Row {
		/**
		 * 经认证的实际租户ID，按十进制字符串传输。
		 */
		private Long tenantId;
		/**
		 * 实际登记操作者ID，按字符串保存，不代表设备认证。
		 */
		private Long actorId;
		/**
		 * 原派工ID字符串，仅接受实际开工的原单件派工。
		 */
		private Long arrangeId;
		/**
		 * 原工单或检验任务ID，按所属原件合同解释。
		 */
		private Long taskId;
		/**
		 * 制造编码服务端保留序号，不代表累计产量。
		 */
		private Long sequenceNo;
		/**
		 * 当前已授权实际工厂编码，与原来源一致。
		 */
		private String factoryCode;
		/**
		 * 永久幂等请求号；同号异事实拒绝。
		 */
		private String requestId;
		/**
		 * 服务端按冻结模板生成的规范单件码，不接管任意历史码。
		 */
		private String identifier;
		/**
		 * 不可变原件规范序列化原文。
		 */
		private String payload;
		/**
		 * 当前原件规范序列化的SHA-256指纹；可空性由可用状态决定。
		 */
		private String fingerprint;
		/**
		 * 原冻结稳定检验节点编码。
		 */
		private String nodeCode;
		/**
		 * 原QMS检验任务ID字符串。
		 */
		private String qmsTaskId;
		/**
		 * 原QMS样本采集请求号，永久绑定首轮归属。
		 */
		private String qmsRequestId;
		/**
		 * 原QMS采集命令SHA-256。
		 */
		private String qmsFingerprint;
	}

	/**
	 * 核对本域来源结构是否完整安装；未安装返回false，部分就绪时拒绝降级。
	 */
	/**
	 * 读取制造出生及首轮归属结构安装数量；返回0表示全未安装，部分安装由服务拒绝。
	 */
	@Select(
			"SELECT CASE WHEN to_regclass('mom_mes.mes_manufacturing_piece') IS NULL THEN 0 ELSE 1"
					+ " END + CASE WHEN to_regclass('mom_mes.mes_inspection_first_round') IS NULL THEN"
					+ " 0 ELSE 1 END")
	int installed();

	/**
	 * 核对制造出生及首轮归属结构是否完整就绪；全未安装返回false，部分安装或守卫不就绪时拒绝。
	 */
	@Select("SELECT mom_mes.mom_manufacturing_piece_ready_v1()")
	Boolean ready();

	/**
	 * 取得服务端制造编码唯一序号；序号可以跳号，不代表实际产量。
	 */
	@Select("SELECT nextval('mom_mes.mes_manufacturing_piece_sequence')")
	long nextSequence();

	/**
	 * 取得当前事务的原来源键锁，保证重放与唯一出生/首轮归属串行化。
	 */
	@Select("SELECT pg_advisory_xact_lock(hashtextextended(#{key},0))")
	void lock(@Param("key") String key);

	/**
	 * 按实际租户、工厂与永久原请求读取制造出生，不做跨工厂同名匹配。
	 */
	@Select(
			"SELECT * FROM mom_mes.mes_manufacturing_piece WHERE tenant_id=#{tenant} AND"
					+ " factory_code=#{factory} AND request_id=#{request}")
	Row request(
			@Param("tenant") Long tenant,
			@Param("factory") String factory,
			@Param("request") String request);

	/**
	 * 按实际租户、工厂与规范单件码读取不可变制造出生。
	 */
	@Select(
			"SELECT * FROM mom_mes.mes_manufacturing_piece WHERE tenant_id=#{tenant} AND"
					+ " factory_code=#{factory} AND identifier=#{identifier}")
	Row piece(
			@Param("tenant") Long tenant,
			@Param("factory") String factory,
			@Param("identifier") String identifier);

	/**
	 * 统计原工单永久制造登记身份数，已完成登记仍只占一份容量。
	 */
	@Select(
			"SELECT COUNT(*) FROM mom_mes.mes_manufacturing_piece WHERE tenant_id=#{tenant} AND"
					+ " factory_code=#{factory} AND task_id=#{task}")
	long orderCount(
			@Param("tenant") Long tenant,
			@Param("factory") String factory,
			@Param("task") Long task);

	/**
	 * 统计原派工永久制造登记身份数，不因合格、NG或重复报工释放出生额度。
	 */
	@Select(
			"SELECT COUNT(*) FROM mom_mes.mes_manufacturing_piece WHERE tenant_id=#{tenant} AND"
					+ " factory_code=#{factory} AND arrange_id=#{arrange}")
	long arrangeCount(
			@Param("tenant") Long tenant,
			@Param("factory") String factory,
			@Param("arrange") Long arrange);

	/**
	 * 限定实际租户工厂和精确工单编码读取原工单，额外一行用于拒绝重复。
	 */
	@Select(
			"SELECT * FROM mom_mes.mes_task WHERE tenant_id=#{tenant} AND factory_code=#{factory}"
					+ " AND code=#{code} AND del_flag='0' LIMIT 2")
	List<MesTaskEntity> tasks(
			@Param("tenant") Long tenant,
			@Param("factory") String factory,
			@Param("code") String code);

	/**
	 * 核对同组织规范码是否已存在历史计时会话，防止接管旧身份。
	 */
	@Select(
			"SELECT COUNT(*) FROM mom_mes.mes_workpiece_time_session WHERE tenant_id=#{tenant} AND"
					+ " factory_code=#{factory} AND identifier_type='SN' AND identifier=#{identifier}")
	long existingSessions(
			@Param("tenant") Long tenant,
			@Param("factory") String factory,
			@Param("identifier") String identifier);

	/**
	 * 核对同组织规范码是否已有报工历史，防止通过新出生追认历史。
	 */
	@Select(
			"SELECT COUNT(*) FROM mom_mes.mes_task_report WHERE tenant_id=#{tenant} AND"
					+ " factory_code=#{factory} AND transfer_code=#{identifier}")
	long existingReports(
			@Param("tenant") Long tenant,
			@Param("factory") String factory,
			@Param("identifier") String identifier);

	/**
	 * 统计原工单不能与服务器出生身份精确对应的计时或报工来源，存在时保守拒绝额外出生，不猜测数量重叠。
	 */
	@Select(
			"SELECT (SELECT COUNT(*) FROM mom_mes.mes_workpiece_time_session s WHERE"
					+ " s.tenant_id=#{tenant} AND s.factory_code=#{factory} AND s.task_code=#{task} AND"
					+ " (s.identifier_type<>'SN' OR NOT EXISTS(SELECT 1 FROM"
					+ " mom_mes.mes_manufacturing_piece b WHERE b.tenant_id=s.tenant_id AND"
					+ " b.factory_code=s.factory_code AND b.identifier=s.identifier))) + (SELECT"
					+ " COUNT(*) FROM mom_mes.mes_task_report r WHERE r.tenant_id=#{tenant} AND"
					+ " r.factory_code=#{factory} AND r.task_code=#{task} AND (r.del_flag<>'0' OR"
					+ " r.status IS NULL OR r.status NOT IN('EFFECTIVE','REVERSED') OR r.report_num IS"
					+ " NULL OR r.report_num<>1 OR r.effective_report_num IS NULL OR"
					+ " r.effective_report_num NOT IN(0,1) OR NOT EXISTS(SELECT 1 FROM"
					+ " mom_mes.mes_manufacturing_piece b WHERE b.tenant_id=r.tenant_id AND"
					+ " b.factory_code=r.factory_code AND b.identifier=r.transfer_code)))")
	long unregisteredHistory(
			@Param("tenant") Long tenant,
			@Param("factory") String factory,
			@Param("task") String task);

	/**
	 * 按实际租户工厂、规范单件码与冻结稳定节点读取唯一原首轮归属。
	 */
	@Select(
			"SELECT * FROM mom_mes.mes_inspection_first_round WHERE tenant_id=#{tenant} AND"
					+ " factory_code=#{factory} AND identifier=#{identifier} AND node_code=#{node}")
	Row assignment(
			@Param("tenant") Long tenant,
			@Param("factory") String factory,
			@Param("identifier") String identifier,
			@Param("node") String node);

	/**
	 * 按实际租户工厂、规范码、原QMS任务和原采集号读取永久重放归属；额外一行用于拒绝重复原件。
	 */
	@Select(
			"SELECT * FROM mom_mes.mes_inspection_first_round WHERE tenant_id=#{tenant} AND"
					+ " factory_code=#{factory} AND identifier=#{identifier} AND qms_task_id=#{task}"
					+ " AND qms_request_id=#{request} LIMIT 2")
	List<Row> replay(
			@Param("tenant") Long tenant,
			@Param("factory") String factory,
			@Param("identifier") String identifier,
			@Param("task") String task,
			@Param("request") String request);

	/**
	 * 插入不可变制造出生原文及索引，返回实际影响行数；调用方要求恰为1并与原START同事务提交。
	 */
	@Insert(
			"INSERT INTO"
					+
					" mom_mes.mes_manufacturing_piece(tenant_id,factory_code,actor_id,arrange_id,task_id,request_id,identifier,payload,fingerprint,sequence_no)"
					+
					" VALUES(#{tenantId},#{factoryCode},#{actorId},#{arrangeId},#{taskId},#{requestId},#{identifier},#{payload},#{fingerprint},#{sequenceNo})")
	int insertBirth(Row row);

	/**
	 * 插入不可变首轮归属原文及索引，返回实际影响行数；同件同稳定节点由数据库唯一约束保护。
	 */
	@Insert(
			"INSERT INTO"
					+
					" mom_mes.mes_inspection_first_round(tenant_id,factory_code,actor_id,identifier,node_code,qms_task_id,qms_request_id,qms_fingerprint,payload,fingerprint)"
					+
					" VALUES(#{tenantId},#{factoryCode},#{actorId},#{identifier},#{nodeCode},#{qmsTaskId},#{qmsRequestId},#{qmsFingerprint},#{payload},#{fingerprint})")
	int insertAssignment(Row row);
}
