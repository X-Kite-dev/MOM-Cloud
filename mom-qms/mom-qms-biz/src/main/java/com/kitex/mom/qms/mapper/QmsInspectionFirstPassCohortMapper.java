package com.kitex.mom.qms.mapper;

import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 按服务端完整制造身份集合读取实际租户工厂的逐件样本候选，禁止客户端缩减统计分母。
 */
@Mapper
@com.baomidou.mybatisplus.annotation.InterceptorIgnore(tenantLine = "true", dataPermission = "true")
public interface QmsInspectionFirstPassCohortMapper {
	/**
	 * 按实际租户工厂及MES完整成员集合读取原逐件候选，最多10001条以识别超限；调用方拒绝截断统计。
	 */
	@Select({
			"<script>SELECT * FROM mom_qms.qms_inspection_piece WHERE tenant_id=#{tenant} AND"
					+ " factory_code=#{factory} AND identifier IN <foreach collection='identifiers'"
					+ " item='identifier' open='(' separator=',' close=')'>#{identifier}</foreach> ORDER BY"
					+ " identifier,task_id,id LIMIT 10001</script>"
	})
	List<QmsInspectionPieceMapper.Row> candidates(
			@Param("tenant") Long tenant,
			@Param("factory") String factory,
			@Param("identifiers") List<String> identifiers);
}
