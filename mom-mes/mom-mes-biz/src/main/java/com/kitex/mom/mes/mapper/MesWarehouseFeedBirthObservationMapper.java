package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import lombok.Data;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;

/**
 * 投料原生出生元数据的共享只读接口；技术列不进入可回写投料实体。
 */
@Mapper
public interface MesWarehouseFeedBirthObservationMapper {
	/**
	 * 批量读取实际租户/工厂的原投料标记和对应出生主账；缺主账保留为空，不推断历史来源。
	 *
	 * @param tenant  当前认证租户主键
	 * @param factory 已授权实际工厂编码
	 * @param feedIds 调用方已核验的1至5000个唯一投料主键
	 * @return 最多5001行观察；调用方须拒绝缺行、重复、超界或未知版本，并另核完整出生明细
	 */
	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Select({"<script>", "SELECT f.id AS feed_id,f.birth_version AS native_birth_version,",
			"b.id AS header_id,b.evidence_version AS header_version,b.status AS header_status,",
			"b.quantity AS header_quantity,b.unit AS header_unit,b.source_count,",
			"b.task_id,b.arrange_id,",
			"b.evidence_fingerprint=encode(sha256(convert_to('MES_FEED_BIRTH_V1|'||b.evidence_snapshot,'UTF8')),'hex') AS header_hash_valid",
			"FROM mom_mes.mes_warehouse_feed f LEFT JOIN mom_mes.mes_warehouse_feed_birth b",
			"ON b.feed_id=f.id AND b.tenant_id=f.tenant_id AND b.factory_code=f.factory_code",
			"WHERE f.tenant_id=#{tenant} AND f.factory_code=#{factory} AND f.id IN",
			"<foreach collection='feedIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>",
			"ORDER BY f.id LIMIT 5001", "</script>"})
	List<Observation> batchObservations(@Param("tenant") Long tenant, @Param("factory") String factory,
	                                    @Param("feedIds") Collection<Long> feedIds);

	@Data
	class Observation {
		/**
		 * 实际投料主键，按请求组织精确读取。
		 */
		private Long feedId;
		/**
		 * 投料原生出生版本；空表示历史未证明，禁止当作有效出生。
		 */
		private String nativeBirthVersion;
		/**
		 * 对应原出生主账主键；缺主账为空。
		 */
		private Long headerId;
		/**
		 * 主账原文算法版本，可信值为MES_FEED_BIRTH_V1。
		 */
		private String headerVersion;
		/**
		 * 主账状态，可信已记录状态为RECORDED。
		 */
		private String headerStatus;
		/**
		 * 原出生总投料量，单位为headerUnit；缺主账为空。
		 */
		private BigDecimal headerQuantity;
		/**
		 * 原出生数量单位，必须与原投料单位一致。
		 */
		private String headerUnit;
		/**
		 * 主账声明的完整来源行数；空不是0。
		 */
		private Integer sourceCount;
		/**
		 * 原版本域UTF8摘要是否一致；缺主账可空，只有true可继续核验。
		 */
		private Boolean headerHashValid;
		/**
		 * 原出生所属生产工单主键，限定实际组织。
		 */
		private Long taskId;
		/**
		 * 原出生所属派工主键，限定实际组织。
		 */
		private Long arrangeId;
	}
}
