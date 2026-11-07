package com.kitex.mom.mes.mapper;

import com.kitex.mom.mes.entity.*;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 只读原件及受控插入映射；每条业务查询显式限定实际租户和工厂。
 */
@Mapper
@com.baomidou.mybatisplus.annotation.InterceptorIgnore(tenantLine = "true", dataPermission = "true")
public interface MesProductionLabelMapper {
	/**
	 * 读取标签两张原件表是否存在；不把存在等同于全部保护就绪。
	 */
	@Select("SELECT CASE WHEN to_regclass('mom_mes.mes_production_label_issue') IS NULL THEN 0 ELSE 1 END + CASE WHEN to_regclass('mom_mes.mes_production_label_intent') IS NULL THEN 0 ELSE 1 END")
	int installed();

	/**
	 * 读取完整原件及审计守卫就绪状态；缺失不能继续写入。
	 */
	@Select("SELECT mom_mes.mom_production_label_ready_v1()")
	Boolean ready();

	/**
	 * 取得标签审计序列；不是制造SN，事务回滚可以跳号。
	 */
	@Select("SELECT nextval('mom_mes.mes_production_label_sequence')")
	long nextSequence();

	/**
	 * 按实际组织及精确发放主键读取不可变原件。
	 */
	@Select("SELECT * FROM mom_mes.mes_production_label_issue WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND id=#{id}")
	MesProductionLabelEntity issue(@Param("tenant") Long tenant, @Param("factory") String factory,
	                               @Param("id") Long id);

	/**
	 * 锁定实际组织内的原发放件，为同一意图链串行化提供锚点。
	 */
	@Select("SELECT * FROM mom_mes.mes_production_label_issue WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND id=#{id} FOR UPDATE")
	MesProductionLabelEntity lockIssue(@Param("tenant") Long tenant, @Param("factory") String factory,
	                                   @Param("id") Long id);

	/**
	 * 读取同实际组织、来源指纹和原模板指纹的唯一原件，防换请求号绕过重打。
	 */
	@Select("SELECT * FROM mom_mes.mes_production_label_issue WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND source_fingerprint=#{source} AND template_fingerprint=#{template}")
	MesProductionLabelEntity issueEvidence(@Param("tenant") Long tenant, @Param("factory") String factory,
	                                       @Param("source") String source, @Param("template") String template);

	/**
	 * 按实际租户/工厂及永久原请求号读取首次标签原件。
	 */
	@Select("SELECT * FROM mom_mes.mes_production_label_issue WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND request_id=#{request}")
	MesProductionLabelEntity issueRequest(@Param("tenant") Long tenant, @Param("factory") String factory,
	                                      @Param("request") String request);

	/**
	 * 按实际租户/工厂及永久原请求号读取首次打印意图。
	 */
	@Select("SELECT * FROM mom_mes.mes_production_label_intent WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND request_id=#{request}")
	MesProductionLabelEntity intentRequest(@Param("tenant") Long tenant, @Param("factory") String factory,
	                                       @Param("request") String request);

	/**
	 * 按原件读取最多1001条意图；服务须拒绝超过1000条而不截断审计。
	 */
	@Select("SELECT * FROM mom_mes.mes_production_label_intent WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND issue_id=#{issue} ORDER BY ordinal_no DESC LIMIT 1001")
	List<MesProductionLabelEntity> intents(@Param("tenant") Long tenant, @Param("factory") String factory,
	                                       @Param("issue") Long issue);

	/**
	 * 保存完整发放原文及索引；冲突后必须核首次回读，不能把零行写入当成功。
	 */
	@Insert("INSERT INTO mom_mes.mes_production_label_issue(id,tenant_id,factory_code,actor_id,request_id,arrange_id,source_fingerprint,template_fingerprint,payload,fingerprint) VALUES(#{id},#{tenantId},#{factoryCode},#{actorId},#{requestId},#{arrangeId},#{sourceFingerprint},#{templateFingerprint},#{payload},#{fingerprint}) ON CONFLICT DO NOTHING")
	int insertIssue(MesProductionLabelEntity row);

	/**
	 * 保存完整打印意图；调用方必须核首次回读与连续前件。
	 */
	@Insert("INSERT INTO mom_mes.mes_production_label_intent(id,tenant_id,factory_code,actor_id,request_id,arrange_id,issue_id,ordinal_no,payload,fingerprint) VALUES(#{id},#{tenantId},#{factoryCode},#{actorId},#{requestId},#{arrangeId},#{issueId},#{ordinalNo},#{payload},#{fingerprint}) ON CONFLICT DO NOTHING")
	int insertIntent(MesProductionLabelEntity row);

	/**
	 * 按精确主键及实际组织读取未删除原派工。
	 */
	@Select("SELECT * FROM mom_mes.mes_task_arrange WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND id=#{id} AND del_flag='0'")
	MesTaskArrangeEntity arrange(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("id") Long id);

	/**
	 * 按实际组织和业务编码读取最多两条父工单，以便拒绝非唯一来源。
	 */
	@Select("SELECT * FROM mom_mes.mes_task WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND code=#{code} AND del_flag='0' LIMIT 2")
	List<MesTaskEntity> tasks(@Param("tenant") Long tenant, @Param("factory") String factory,
	                          @Param("code") String code);
}
