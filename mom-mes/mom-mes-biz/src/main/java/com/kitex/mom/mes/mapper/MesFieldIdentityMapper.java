package com.kitex.mom.mes.mapper;

import org.apache.ibatis.annotations.*;

import java.util.Map;

/**
 * 附加认证的不可变挑战与受理账；显式限定实际租户、工厂和本人，无业务表直写。
 */
@Mapper
@com.baomidou.mybatisplus.annotation.InterceptorIgnore(tenantLine = "true", dataPermission = "true")
public interface MesFieldIdentityMapper {
	/**
	 * 读取V4完整结构与不可变保护就绪状态，缺失时拒绝新入口。
	 */
	@Select("SELECT mom_mes.mom_field_identity_ready_v1()")
	Boolean ready();

	/**
	 * 保存短期不可变挑战，成功必须恰好一行；不存原命令或凭据。
	 */
	@Insert("INSERT INTO mom_mes.mes_field_identity_challenge(challenge_id,tenant_id,factory_code,actor_id,action,request_id,command_sha256,expires_at,config_fingerprint,mode) VALUES(#{challengeId},#{tenant},#{factory},#{actor},#{action},#{request},#{sha},#{expires},#{config},#{mode})")
	int insertChallenge(Map<String, Object> row);

	/**
	 * 在当前组织及本人范围锁定原挑战，串行受理同一挑战的并发证明。
	 */
	@Select("SELECT * FROM mom_mes.mes_field_identity_challenge WHERE challenge_id=#{id} AND tenant_id=#{tenant} AND factory_code=#{factory} AND actor_id=#{actor} FOR UPDATE")
	Map<String, Object> lockChallenge(@Param("id") String id, @Param("tenant") Long tenant,
	                                  @Param("factory") String factory, @Param("actor") Long actor);

	/**
	 * 只读原挑战的受理证据；认证账不代表原交接已经完成。
	 */
	@Select("SELECT * FROM mom_mes.mes_field_identity_acceptance WHERE challenge_id=#{id} AND tenant_id=#{tenant} AND factory_code=#{factory} AND actor_id=#{actor}")
	Map<String, Object> acceptance(@Param("id") String id, @Param("tenant") Long tenant,
	                               @Param("factory") String factory, @Param("actor") Long actor);

	/**
	 * 一次性保存必要认证结果及身份，禁止保存JWT原文或卡号原文。
	 */
	@Insert("INSERT INTO mom_mes.mes_field_identity_acceptance(challenge_id,tenant_id,factory_code,actor_id,action,request_id,command_sha256,config_fingerprint,mode,subject,device_id,card_sha256) VALUES(#{challengeId},#{tenant},#{factory},#{actor},#{action},#{request},#{sha},#{config},#{mode},#{subject},#{device},#{cardSha})")
	int insertAcceptance(Map<String, Object> row);
}
