package com.kitex.mom.mes.service.impl;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.dto.MesFieldIdentityDTO;
import com.kitex.mom.mes.mapper.MesFieldIdentityMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 附加认证编排：短期挑战绑定实际本人及原HANDOVER命令，独立受理后复用原交接门禁。
 * 认证账仅证明认证受理，不与交接跨事务原子；未知结果必须按原交接请求号回读。
 */
@Service
@RequiredArgsConstructor
public class MesFieldIdentityService {
	private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
			.enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY).enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
			.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
	private final MesActualFactoryAccessService access;
	private final MesFieldIdentityProperties properties;
	private final MesFieldIdentityProofVerifier verifier;
	private final MesFieldIdentityMapper mapper;
	private final MesShiftHandoverService handover;
	private final PlatformTransactionManager transactionManager;

	/**
	 * 为当前实名、实际工厂和原命令创建30至180秒一次性挑战。
	 *
	 * @param command 原交接动作及命令，不能由客户端提供组织或认证主体
	 * @return 交给厂商SDK的完整绑定依据
	 */
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public MesFieldIdentityDTO.Challenge challenge(MesFieldIdentityDTO.ChallengeCommand command) {
		String factory = access.require("write");
		Long tenant = TenantContextHolder.getTenantId(), actor = SecurityUtils.getUser().getId();
		var trust = trust(tenant, factory);
		validate(command);
		require(properties.getChallengeSeconds() >= 30 && properties.getChallengeSeconds() <= 180,
				"挑战有效期配置须为30至180秒");
		String sha = commandSha(command), id = UUID.randomUUID().toString();
		long expires = Instant.now().getEpochSecond() + properties.getChallengeSeconds();
		var row = row(id, tenant, factory, actor, command, sha, configSha(trust), trust.getMode());
		row.put("expires", expires);
		new TransactionTemplate(transactionManager).execute(status -> {
			ready();
			require(mapper.insertChallenge(row) == 1, "挑战保存失败");
			return null;
		});
		return new MesFieldIdentityDTO.Challenge(id, tenant.toString(), factory, actor.toString(), command.getAction(),
				command.getCommand().getRequestId(), sha, expires, trust.getMode());
	}

	/**
	 * 独立提交一次性认证受理账，再执行原交接命令；原服务保留自身NOT_SUPPORTED事务。
	 * 同挑战重试只可恢复同本人同原命令，已受理不重验过期JWT，也不推断交接成功。
	 *
	 * @param command 挑战号、原命令及只在内存验签的厂商证明
	 * @return 分离的认证受理状态与原交接实际回执；业务异常仍沿原号查询
	 */
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public MesFieldIdentityDTO.Result authenticate(MesFieldIdentityDTO.AuthenticateCommand command) {
		String factory = access.require("write");
		Long tenant = TenantContextHolder.getTenantId(), actor = SecurityUtils.getUser().getId();
		var trust = trust(tenant, factory);
		validate(command);
		require(command.getChallengeId() != null && command.getChallengeId().matches("[a-f0-9-]{36}"), "挑战号无效");
		String sha = commandSha(command);
		String mode = new TransactionTemplate(transactionManager).execute(status -> {
			ready();
			var challenge = mapper.lockChallenge(command.getChallengeId(), tenant, factory, actor);
			require(challenge != null && Objects.equals(challenge.get("command_sha256"), sha)
							&& Objects.equals(challenge.get("action"), command.getAction())
							&& Objects.equals(challenge.get("request_id"), command.getCommand().getRequestId()),
					"挑战与实际组织、本人或原命令不匹配");
			var accepted = mapper.acceptance(command.getChallengeId(), tenant, factory, actor);
			if (accepted == null) {
				require(((Number) challenge.get("expires_at")).longValue() > Instant.now().getEpochSecond(),
						"挑战已失效，请沿原交接号重新认证");
				require(configSha(trust).equals(challenge.get("config_fingerprint")), "现场信任配置已变化，请重新认证");
				var evidence = verifier.verify(command.getProof(), trust, challenge);
				var row = row(command.getChallengeId(), tenant, factory, actor, command, sha, configSha(trust),
						trust.getMode());
				row.put("subject", evidence.subject());
				row.put("device", evidence.deviceId());
				row.put("cardSha", evidence.cardSha256());
				require(mapper.insertAcceptance(row) == 1, "认证受理账保存失败");
			}
			return String.valueOf(challenge.get("mode"));
		});
		// 到此只保证认证账提交；原交接可能拒绝、超时或已经成功，必须复用原永久请求号。
		Object result = handover.execute(command.getAction(), command.getCommand());
		return new MesFieldIdentityDTO.Result("AUTHENTICATION_ACCEPTED", command.getChallengeId(), mode, result);
	}

	/**
	 * 按经过授权的实际租户/工厂取唯一信任；禁用、缺失、重复或不完整配置均拒绝，不使用通配回退。
	 */
	private MesFieldIdentityProperties.Trust trust(Long tenant, String factory) {
		require(properties.isEnabled(), "现场附加认证尚未配置启用");
		var matches = properties.getTrusts().stream().filter(t -> Objects.equals(t.getTenantId(), tenant)
				&& Objects.equals(t.getFactoryCode(), factory)).toList();
		require(matches.size() == 1, "当前租户工厂须有唯一的现场公钥信任配置");
		var trust = matches.get(0);
		require(trust.getMode() != null && Set.of("REAL", "SIMULATION").contains(trust.getMode()) &&
				trust.getIssuer() != null && !trust.getIssuer().isBlank()
				&& trust.getAudience() != null && !trust.getAudience().isBlank() && trust.getKeyId() != null &&
				!trust.getKeyId().isBlank()
				&& trust.getPublicKeyPem() != null && !trust.getPublicKeyPem().isBlank()
				&& trust.getDeviceIds() != null && !trust.getDeviceIds().isEmpty(), "现场公钥信任配置不完整");
		return trust;
	}

	/**
	 * 核完整安装的目录及不可变/scope保护，数据库不可用或结构缺失均不能受理认证。
	 */
	private void ready() {
		try {
			require(Boolean.TRUE.equals(mapper.ready()), "现场认证V4结构或不可变保护未就绪");
		} catch (CheckedException failure) {
			throw failure;
		} catch (RuntimeException failure) {
			throw new CheckedException("现场认证V4结构尚未安装或无法核验");
		}
	}

	/**
	 * 只允许原HANDOVER四种动作及稳定原请求号；具体交接门禁仍由原领域服务执行。
	 */
	private static void validate(MesFieldIdentityDTO.ChallengeCommand command) {
		require(command != null && command.getAction() != null &&
						Set.of("SUBMIT", "ACCEPT", "REJECT", "WITHDRAW").contains(command.getAction())
						&& command.getCommand() != null && command.getCommand().getRequestId() != null
						&& command.getCommand().getRequestId().matches("[A-Za-z0-9_-]{16,64}"),
				"须绑定已有交接动作和永久请求号");
	}

	/**
	 * 只摘要实际交接DTO及动作；客户端不得自报命令摘要或改变已绑定字段。
	 */
	private static String commandSha(MesFieldIdentityDTO.ChallengeCommand command) {
		return hash(Map.of("contract", "MES_FIELD_HANDOVER_V1", "action", command.getAction(), "command",
				command.getCommand()));
	}

	/**
	 * 公钥、设备列表或模式变化后旧未受理挑战失效；指纹不包含私钥或秘密。
	 */
	private static String configSha(MesFieldIdentityProperties.Trust trust) {
		return hash(trust);
	}

	private static String hash(Object value) {
		try {
			return MesFieldIdentityProofVerifier.sha256(JSON.writeValueAsString(value));
		} catch (Exception failure) {
			throw new CheckedException("认证绑定依据无法生成摘要");
		}
	}

	/**
	 * 生成两张不可变账共用的最小绑定列；不复制原命令、JWT或卡号正文。
	 */
	private static Map<String, Object> row(String id, Long tenant, String factory, Long actor,
	                                       MesFieldIdentityDTO.ChallengeCommand command, String sha, String config,
	                                       String mode) {
		var row = new LinkedHashMap<String, Object>();
		row.put("challengeId", id);
		row.put("tenant", tenant);
		row.put("factory", factory);
		row.put("actor", actor);
		row.put("action", command.getAction());
		row.put("request", command.getCommand().getRequestId());
		row.put("sha", sha);
		row.put("config", config);
		row.put("mode", mode);
		return row;
	}

	private static void require(boolean condition, String message) {
		if (!condition) {
			throw new CheckedException(message);
		}
	}
}
