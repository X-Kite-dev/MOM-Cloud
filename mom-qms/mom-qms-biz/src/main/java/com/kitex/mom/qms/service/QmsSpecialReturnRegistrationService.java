package com.kitex.mom.qms.service;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.dto.MdmFactoryAccessCheck;
import com.kitex.mom.mdm.feign.RemoteMdmFactoryAccessService;
import com.kitex.mom.qms.entity.QmsBadInfoEntity;
import com.kitex.mom.qms.entity.QmsBadOperationEntity;
import com.kitex.mom.qms.mapper.QmsBadInfoMapper;
import com.kitex.mom.wms.api.feign.RemoteLiveStockService;
import com.kitex.mom.wms.dto.WmsSpecialReturnQualityDTO.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * 特殊退回原件登记：服务端证明、源唯一和永久来源冻结；本地提交前绝不调用远程HOLD。
 * 控制器在本代理事务返回后调用独立NCR服务的retryHold，进程中断由原案的bad:sync恢复。
 */
@Service
@RequiredArgsConstructor
public class QmsSpecialReturnRegistrationService {
	public static final String SOURCE_TYPE = "WMS_SPECIAL_RETURN";
	private final QmsBadInfoService infos;
	private final QmsBadInfoMapper mapper;
	private final QmsBadOperationService operations;
	private final RemoteLiveStockService stocks;
	private final RemoteMdmFactoryAccessService factories;
	private final JdbcTemplate jdbc;
	private final ObjectMapper json;

	/**
	 * 按真实仓库与业务参照分页查询原件，WMS保留原人员令牌并核仓库权限。
	 */
	public CandidatePage candidates(CandidateQuery query) {
		requireScope();
		requireReady();
		return data(stocks.specialReturnQualityCandidates(query), "特殊退回候选读取失败");
	}

	/**
	 * 预检只接受选择合同；规范来源、原隔离和数量全部由WMS重新证明。
	 */
	public Target preflight(Selection selection) {
		requireScope();
		requireReady();
		var target = data(stocks.preflightSpecialReturnQuality(selection), "特殊退回原件核验失败");
		assertTargetScope(target);
		return target;
	}

	/**
	 * 永久原案来源查看；精确ID也核实际人员、租户和工厂，不要求当前原件仍为候选。
	 */
	public QmsBadInfoEntity view(Long id) {
		String factory = requireScope();
		requireReady();
		var info = infos.getOne(new LambdaQueryWrapper<QmsBadInfoEntity>()
				.eq(QmsBadInfoEntity::getId, id).eq(QmsBadInfoEntity::getFactoryCode, factory)
				.eq(QmsBadInfoEntity::getTenantId, TenantContextHolder.getTenantId())
				.eq(QmsBadInfoEntity::getResourceType, SOURCE_TYPE), false);
		require(info != null, "当前工厂不存在该特殊退回NCR");
		assertTargetScope(readTarget(info.getWmsSpecialReturnTargetJson()));
		return info;
	}

	/**
	 * 以租户/工厂/RETURN请求/选件库存永久唯一登记；先查原案再预检现存隔离，允许成功后的同源重放。
	 * 同源异选择或异量拒绝；外部不能写入NCR物料、组织、冻结JSON和任何进度。
	 *
	 * @param selection 已观察的候选及接管数量，均为文本
	 * @return 已提交后可回读的原NCR，尚不表示WMS已成功HOLD
	 */
	@Transactional(rollbackFor = Exception.class)
	public QmsBadInfoEntity register(Selection selection) {
		String factory = requireScope();
		requireReady();
		require(selection != null && StrUtil.isNotBlank(selection.returnRequestId())
				&& StrUtil.isNotBlank(selection.stockId()), "请选择真实退回原件");
		String source;
		try {
			// JSON元组避免业务参照含冒号时源字段边界歧义；数据库唯一索引仍按四个原字段。
			source = json.writeValueAsString(List.of(TenantContextHolder.getTenantId().toString(), factory,
					selection.returnRequestId(), selection.stockId()));
		} catch (JsonProcessingException ex) {
			throw new CheckedException("特殊退回源键生成失败");
		}
		// 事务级源锁配合数据库唯一索引；数量不进入源键，不能通过改量另建NCR。
		mapper.lockSource("QMS-SPECIAL-RETURN:" + sourceHash(source));
		var existing = infos.getOne(new LambdaQueryWrapper<QmsBadInfoEntity>()
				.eq(QmsBadInfoEntity::getTenantId, TenantContextHolder.getTenantId())
				.eq(QmsBadInfoEntity::getFactoryCode, factory)
				.eq(QmsBadInfoEntity::getResourceType, SOURCE_TYPE)
				.apply("wms_special_return_target_json::jsonb ->> 'returnRequestId' = {0}", selection.returnRequestId())
				.apply("wms_special_return_target_json::jsonb ->> 'stockId' = {0}", selection.stockId()), false);
		if (existing != null) {
			Target target = readTarget(existing.getWmsSpecialReturnTargetJson());
			assertTargetScope(target);
			require(sameSelection(selection, target), "该退回原件已登记NCR，但选择、原指纹或数量不同；请查看原案");
			return existing;
		}
		Target target = preflight(selection);
		require(sameSelection(selection, target), "预检返回来源与所选原件不一致");
		var identity = target.identity();
		var user = SecurityUtils.getUser();
		var quantity = new BigDecimal(target.quantity());
		var info = new QmsBadInfoEntity().setResourceType(SOURCE_TYPE)
				.setWmsSpecialReturnTargetJson(writeTarget(target))
				.setRequestId("WMS-SPECIAL-RETURN:" + sourceHash(source))
				.setNcrNo("NCR-" + IdUtil.getSnowflakeNextIdStr())
				.setFactoryCode(factory).setMatCode(identity.get("matCode")).setMatName(identity.get("matName"))
				.setMatModel(identity.get("matSpec")).setSupplierCode(identity.get("supplierCode"))
				.setSupplierName(identity.get("supplierName")).setBatchNo(identity.get("matBatch"))
				.setQuantityUnit(target.unit()).setLocationCode(target.siteCode()).setLocationName(target.siteCode())
				.setContainerCode(target.containerCode()).setNum(quantity).setUnJudgeNum(quantity)
				.setJudgedNum(BigDecimal.ZERO).setDisposedNum(BigDecimal.ZERO).setClosedNum(BigDecimal.ZERO)
				.setStatus("WAIT_WMS_HOLD").setHoldSyncStatus("PENDING")
				.setHoldSyncMessage("NCR已登记，待按原号同步WMS精确隔离")
				.setDetectedBy(user.getUsername()).setDetectedByName(user.getName())
				.setDetectedTime(LocalDateTime.now());
		info.setTenantId(TenantContextHolder.getTenantId());
		require(infos.save(info), "特殊退回NCR登记失败");
		var stored = infos.getById(info.getId());
		require(stored != null && stored.getNum() != null && stored.getNum().compareTo(quantity) == 0
						&& Objects.equals(stored.getWmsSpecialReturnTargetJson(), info.getWmsSpecialReturnTargetJson()),
				"NCR冻结来源或数量回读不一致，已回滚且未调用WMS");
		var operation = new QmsBadOperationEntity().setBadId(info.getId()).setNcrNo(info.getNcrNo())
				.setRequestId(info.getRequestId()).setOperationType("CREATE").setAfterStatus("WAIT_WMS_HOLD")
				.setContent("特殊退回原件登记，数量=" + target.quantity() + " " + target.unit() + "；待原号精确隔离")
				.setOperatorCode(user.getUsername()).setOperatorName(user.getName())
				.setOperationTime(LocalDateTime.now()).setFactoryCode(factory);
		operation.setTenantId(info.getTenantId());
		require(operations.save(operation), "NCR登记履历保存失败");
		return stored;
	}

	/**
	 * 冻结来源只读解析；不从今日库存或主数据重算历史Target。
	 */
	public Target readTarget(String value) {
		try {
			require(StrUtil.isNotBlank(value), "特殊退回NCR缺永久来源，请核对升级及原案");
			return json.readValue(value, Target.class);
		} catch (JsonProcessingException ex) {
			throw new CheckedException("特殊退回冻结来源无法解析，禁止重新选件");
		}
	}

	private String writeTarget(Target target) {
		try {
			return json.writeValueAsString(target);
		} catch (JsonProcessingException ex) {
			throw new CheckedException("特殊退回来源冻结失败");
		}
	}

	private boolean sameSelection(Selection selection, Target target) {
		return Objects.equals(selection.warehouseCode(), target.warehouseCode())
				&& Objects.equals(selection.orderId(), target.orderId())
				&& Objects.equals(selection.returnRequestId(), target.returnRequestId())
				&& Objects.equals(selection.returnFlowId(), target.returnFlowId())
				&& Objects.equals(selection.stockId(), target.stockId())
				&& Objects.equals(selection.fingerprint(), target.fingerprint())
				&& sameQuantity(selection.quantity(), target.quantity());
	}

	/**
	 * 合法十进制尾零不改变业务量；负数、指数文本、超精度和真正异量均拒绝。
	 */
	private boolean sameQuantity(String selected, String frozen) {
		if (selected == null || !selected.matches("(?:0|[1-9][0-9]*)(?:\\.[0-9]{1,8})?")) {
			return false;
		}
		var quantity = new BigDecimal(selected);
		return quantity.signum() > 0 && quantity.precision() <= 24
				&& frozen != null && quantity.compareTo(new BigDecimal(frozen)) == 0;
	}

	private void assertTargetScope(Target target) {
		require(target != null && target.contractVersion() == 1 && target.identity() != null
						&& Objects.equals(target.tenantId(), String.valueOf(TenantContextHolder.getTenantId()))
						&& Objects.equals(target.factoryCode(), FactoryContextHolder.getFactoryCode()),
				"特殊退回来源不属于当前实际租户工厂");
	}

	private String requireScope() {
		var user = SecurityUtils.getUser();
		String factory = FactoryContextHolder.getFactoryCode();
		require(user != null && user.getId() != null && TenantContextHolder.getTenantId() != null
				&& !TenantContextHolder.getTenantSkip() && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())
				&& StrUtil.isNotBlank(factory), "特殊退回接管须实名登录并选择单一实际工厂");
		var access = factories.check(new MdmFactoryAccessCheck().setUserId(user.getId())
				.setFactoryCode(factory).setRequiredPermission("read"));
		require(access != null && access.isOk() && Boolean.TRUE.equals(access.getData()),
				"当前人员没有实际工厂访问权限");
		return factory;
	}

	private void requireReady() {
		try {
			require(Boolean.TRUE.equals(
							jdbc.queryForObject("SELECT mom_qms.qms_special_return_quality_ready_v1()", Boolean.class)),
					"特殊退回质量接管数据库保护未就绪，请安装本次V4章节");
		} catch (org.springframework.dao.DataAccessException ex) {
			throw new CheckedException("特殊退回质量接管数据库保护未就绪，请核对本次V4章节");
		}
	}

	private <T> T data(R<T> response, String message) {
		require(response != null && response.isOk() && response.getData() != null,
				response == null ? message : StrUtil.blankToDefault(response.getMsg(), message));
		return response.getData();
	}

	private String sourceHash(String source) {
		try {
			return HexFormat.of()
					.formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("缺少SHA-256", ex);
		}
	}

	private void require(boolean condition, String message) {
		if (!condition) {
			throw new CheckedException(message);
		}
	}
}
