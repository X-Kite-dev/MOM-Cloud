package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.kitex.mom.mes.entity.MesWarehousePickingBillEntity;
import com.kitex.mom.mes.entity.MesWarehousePickingBillDetailEntity;

import java.time.LocalDateTime;
import java.util.List;

import lombok.Data;
import org.apache.ibatis.annotations.*;

/**
 * 领料V1原件专用持久层；每条SQL显式限定实际组织，不提供通用CRUD。
 */
@Mapper
@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
public interface MesPickingWmsV1Mapper {
	/**
	 * 当前完整结构、不可变守卫和在途质量互斥是否就绪。
	 */
	@Select("""
			SELECT mom_mes.mes_picking_wms_v1_ready()
			""")
	@Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
	Integer ready();

	/**
	 * 独立无锁准确原单观察；不能据此授予写许可。
	 */
	@Select("""
			SELECT * FROM mom_mes.mes_warehouse_picking_bill
			WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND id=#{id}
			""")
	@Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
	MesWarehousePickingBillEntity bill(@Param("tenant") Long tenant, @Param("factory") String factory,
	                                   @Param("id") Long id);

	/**
	 * 精确组织原领料全部明细；501条用于拒绝超限，不能截断提交。
	 */
	@Select("""
			SELECT * FROM mom_mes.mes_warehouse_picking_bill_detail
			WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND bill_id=#{id} AND del_flag='0'
			ORDER BY id
			LIMIT 501
			""")
	@Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
	List<MesWarehousePickingBillDetailEntity> details(@Param("tenant") Long tenant, @Param("factory") String factory,
	                                                  @Param("id") Long id);

	/**
	 * 来源读取不复用锁前缓存；锁由上层实际根及原单守卫提供。
	 */
	@Select("""
			SELECT * FROM mom_mes.mes_picking_wms_source
			WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND bill_id=#{id}
			""")
	@Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
	Source source(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("id") Long id);

	/**
	 * 原工单ID/编码必须仍为同组织真实原件，不能以同码替代工单接续来源。
	 */
	@Select("""
			SELECT count(*) FROM mom_mes.mes_task
			WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND id=#{taskId} AND code=#{code} AND del_flag='0'
			""")
	@Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
	long taskIdentity(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("taskId") Long taskId,
	                  @Param("code") String code);

	/**
	 * 新真实领料同事务登记V1来源，旧单不收编。
	 */
	@Insert("""
			INSERT INTO mom_mes.mes_picking_wms_source(tenant_id, factory_code, bill_id, bill_no, task_id, task_code,
			current_revision, phase, current_request_id, create_by, create_time, update_time)
			VALUES(#{tenantId}, #{factoryCode}, #{billId}, #{billNo}, #{taskId}, #{taskCode}, 0, 'DRAFT', NULL,
			#{createBy}, #{createTime}, #{updateTime})
			""")
	int insertSource(Source source);

	/**
	 * 同根锁内以原阶段、原修订精确推进投影，不覆盖别的原件。
	 */
	@Update("""
			UPDATE mom_mes.mes_picking_wms_source
			SET current_revision=#{revision}, phase=#{phase}, current_request_id=#{request}, update_time=localtimestamp
			WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND bill_id=#{id} AND
			current_revision=#{previousRevision} AND phase=#{previousPhase}
			""")
	int phase(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("id") Long id,
	          @Param("previousRevision") int previousRevision, @Param("previousPhase") String previousPhase,
	          @Param("revision") int revision, @Param("phase") String phase, @Param("request") String request);

	/**
	 * 选定单一修订至多两份命令，不读取无界历史。
	 */
	@Select("""
			SELECT * FROM mom_mes.mes_picking_wms_command
			WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND bill_id=#{id} AND source_revision=#{revision}
			ORDER BY action
			LIMIT 3
			""")
	@Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
	List<Command> commands(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("id") Long id,
	                       @Param("revision") int revision);

	/**
	 * 本组织原单的原操作；不能仅按全局请求号定位。
	 */
	@Select("""
			SELECT * FROM mom_mes.mes_picking_wms_command
			WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND bill_id=#{id} AND request_id=#{request}
			""")
	@Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
	Command command(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("id") Long id,
	                @Param("request") String request);

	/**
	 * 永久保存不可变原号原文，发送前必须提交。
	 */
	@Insert("""
			INSERT INTO mom_mes.mes_picking_wms_command(tenant_id, factory_code, request_id, bill_id, source_revision,
			action, operator_id, operator_name, payload, payload_sha256, state, receipt, receipt_sha256, create_time,
			update_time)
			VALUES(#{tenantId}, #{factoryCode}, #{requestId}, #{billId}, #{sourceRevision}, #{action}, #{operatorId},
			#{operatorName}, #{payload}, #{payloadSha256}, 'PREPARED', NULL, NULL, #{createTime}, #{updateTime})
			""")
	int insertCommand(Command command);

	/**
	 * 只有首份已核验完整证明可接受；旧永久回执不能覆盖。
	 */
	@Update("""
			UPDATE mom_mes.mes_picking_wms_command
			SET state='ACCEPTED', receipt=#{receipt}, receipt_sha256=#{sha}, update_time=localtimestamp
			WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND bill_id=#{id} AND request_id=#{request} AND
			state='PREPARED' AND receipt IS NULL AND receipt_sha256 IS NULL
			""")
	int accept(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("id") Long id,
	           @Param("request") String request, @Param("receipt") String receipt, @Param("sha") String sha);

	/**
	 * 提交回执应用仅推进尚为CREATED的单，不回退先到的正式发料/签收。
	 */
	@Update("""
			UPDATE mom_mes.mes_warehouse_picking_bill
			SET status=#{next}
			WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND id=#{id} AND del_flag='0' AND status=#{previous}
			""")
	int billStatus(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("id") Long id,
	               @Param("previous") String previous, @Param("next") String next);

	/**
	 * 任何历史发料通知包括软删除均阻止来源取消。
	 */
	@Select("""
			SELECT count(*) FROM mom_mes.mes_warehouse_material_in_bill
			WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND bill_id=#{id}
			""")
	long issued(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("id") Long id);

	/**
	 * 本域来源阶段投影，业务原件身份不可修改。
	 */
	@Data
	class Source {
		/**
		 * 实际租户。
		 */
		private Long tenantId;
		/**
		 * 实际工厂。
		 */
		private String factoryCode;
		/**
		 * 原领料ID。
		 */
		private Long billId;
		/**
		 * 原领料单号。
		 */
		private String billNo;
		/**
		 * 原生产工单ID。
		 */
		private Long taskId;
		/**
		 * 原生产工单编码。
		 */
		private String taskCode;
		/**
		 * 当前来源修订，初始0。
		 */
		private Integer currentRevision;
		/**
		 * DRAFT/PREPARED/ACCEPTED/CANCELLING/CANCELLED。
		 */
		private String phase;
		/**
		 * 当前原操作号，初始可空。
		 */
		private String currentRequestId;
		/**
		 * 创建账号快照。
		 */
		private String createBy;
		/**
		 * 源登记时间。
		 */
		private LocalDateTime createTime;
		/**
		 * 最后投影时间。
		 */
		private LocalDateTime updateTime;
	}

	/**
	 * 本人不可变命令及首份已核验永久回执。
	 */
	@Data
	class Command {
		/**
		 * 实际租户。
		 */
		private Long tenantId;
		/**
		 * 实际工厂。
		 */
		private String factoryCode;
		/**
		 * 永久原操作号。
		 */
		private String requestId;
		/**
		 * 原领料ID。
		 */
		private Long billId;
		/**
		 * 原来源修订。
		 */
		private Integer sourceRevision;
		/**
		 * SUBMIT或CANCEL。
		 */
		private String action;
		/**
		 * 原实名用户ID。
		 */
		private Long operatorId;
		/**
		 * 原账号快照。
		 */
		private String operatorName;
		/**
		 * 发送前冻结完整命令。
		 */
		private String payload;
		/**
		 * 命令原文SHA256。
		 */
		private String payloadSha256;
		/**
		 * PREPARED或ACCEPTED。
		 */
		private String state;
		/**
		 * 已核验完整WMS证明，可空。
		 */
		private String receipt;
		/**
		 * 回执原文SHA256，可空。
		 */
		private String receiptSha256;
		/**
		 * 原命令登记时间。
		 */
		private LocalDateTime createTime;
		/**
		 * 最后回执保存时间。
		 */
		private LocalDateTime updateTime;
	}
}
