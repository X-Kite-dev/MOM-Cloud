package com.kitex.mom.mes.service.impl;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.dto.MesEquipmentOeeDTO.*;
import com.kitex.mom.mes.entity.MesEquipmentOeeBatchEntity;
import com.kitex.mom.mes.mapper.MesEquipmentOeeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Objects;

/**
 * 实际组织下的设备来源受理与只读复算；永久原号不因重试改变。
 */
@Service
@RequiredArgsConstructor
public class MesEquipmentOeeService {
	private final MesActualFactoryAccessService access;
	private final MesEquipmentOeeMapper rows;
	private final MesEquipmentOeeCalculator calculator = new MesEquipmentOeeCalculator();

	/**
	 * 只读核对完整来源并返回预览；来源结构、原单位或模板指纹不符时拒绝，不产生成功保存事实。
	 */
	public Saved preview(String raw) {
		String factory = access.require("read");
		tenant();
		var measured = calculator.measure(raw, Instant.now());
		return new Saved(factory, hash(raw), null, null, false, measured);
	}

	/**
	 * 按实际组织永久原批次号保存完整OEE来源；同号异事实、矛盾区间或必要写入失败时拒绝。
	 */
	@Transactional(rollbackFor = Exception.class)
	public Saved importBatch(String raw) {
		String factory = access.require("write");
		Long tenant = tenant();
		var measured = calculator.measure(raw, Instant.now());
		Batch batch = measured.source();
		var existing = rows.find(tenant, factory, batch.batchId());
		if (existing != null) {
			return replay(existing, raw, tenant, factory);
		}
		var user = SecurityUtils.getUser();
		require(user != null && user.getId() != null && user.getId() > 0
				&& Objects.equals(user.getTenantId(), tenant), "来源导入缺实际实名身份");
		var row = new MesEquipmentOeeBatchEntity();
		row.setTenantId(tenant);
		row.setFactoryCode(factory);
		row.setBatchId(batch.batchId());
		row.setDeviceCode(batch.deviceCode());
		row.setSourceKind(batch.sourceKind());
		row.setPeriodStart(batch.periodStart());
		row.setPeriodEnd(batch.periodEnd());
		row.setSourceJson(raw);
		row.setFingerprint(hash(raw));
		row.setActorId(user.getId().toString());
		row.setAcceptedAt(LocalDateTime.now(ZoneOffset.UTC));
		int inserted = rows.insert(row);
		require(inserted == 0 || inserted == 1, "来源批次必要保存结果无效");
		// A concurrent same-number insert is resolved by the scoped unique key, never by changing its body.
		var stored = rows.find(tenant, factory, batch.batchId());
		require(stored != null, "来源批次尚未确认保存，请按原批次号核对");
		return replay(stored, raw, tenant, factory);
	}

	/**
	 * 按当前实际工厂的精确原批次号读取不可变来源并复算；缺失或摘要不符不返回健康指标。
	 */
	@Transactional(readOnly = true)
	public Saved get(String batchId) {
		String factory = access.require("read");
		Long tenant = tenant();
		code(batchId);
		var row = rows.find(tenant, factory, batchId);
		require(row != null, "当前工厂未找到来源批次，不能视为导入成功");
		return saved(row, tenant, factory);
	}

	/**
	 * 按实际组织、设备条件及有界页码读取原件目录；目录不携带大原文，非法分页拒绝。
	 */
	@Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
	public Page page(String device, int page, int size) {
		String factory = access.require("read");
		Long tenant = tenant();
		require(page > 0 && page <= 100000 && size > 0 && size <= 20, "页码无效，每页1至20批来源");
		if (device != null && !device.isEmpty()) {
			code(device);
		} else {
			device = null;
		}
		var selected = rows.page(tenant, factory, device, size, ((long) page - 1) * size);
		require(selected != null, "来源批次不可读取，不能当作没有数据");
		// Listing verifies source integrity too; the UI never receives healthy-looking metadata for a bad original.
		var records = selected.stream().map(row -> {
			Saved original = saved(row, tenant, factory);
			Batch batch = original.measurement().source();
			return new Summary(batch.batchId(), batch.deviceCode(), batch.sourceKind(), batch.periodStart(),
					batch.periodEnd(), original.acceptedAt(), original.fingerprint());
		}).toList();
		return new Page(Long.toString(rows.count(tenant, factory, device)), records);
	}

	private Saved replay(MesEquipmentOeeBatchEntity row, String raw, Long tenant, String factory) {
		Saved original = saved(row, tenant, factory);
		require(calculator.canonical(original.measurement().source())
				.equals(calculator.canonical(calculator.decode(raw))), "原批次号已绑定另一份来源，请核对原件");
		return original;
	}

	private Saved saved(MesEquipmentOeeBatchEntity row, Long tenant, String factory) {
		require(Objects.equals(row.getTenantId(), tenant) && Objects.equals(row.getFactoryCode(), factory)
						&& row.getSourceJson() != null && Objects.equals(row.getFingerprint(), hash(row.getSourceJson()))
						&& row.getAcceptedAt() != null && row.getActorId() != null &&
						row.getActorId().matches("[1-9][0-9]{0,18}"),
				"留存来源完整性或实际组织无法核验");
		var measured = calculator.measure(row.getSourceJson(), row.getAcceptedAt().toInstant(ZoneOffset.UTC));
		Batch batch = measured.source();
		require(Objects.equals(batch.batchId(), row.getBatchId()) &&
						Objects.equals(batch.deviceCode(), row.getDeviceCode())
						&& Objects.equals(batch.sourceKind(), row.getSourceKind())
						&& Objects.equals(batch.periodStart(), row.getPeriodStart()) &&
						Objects.equals(batch.periodEnd(), row.getPeriodEnd()),
				"留存来源索引与原件不一致");
		return new Saved(factory, row.getFingerprint(), row.getActorId(),
				row.getAcceptedAt().toInstant(ZoneOffset.UTC).toString(), true, measured);
	}

	private Long tenant() {
		Long id = TenantContextHolder.getTenantId();
		require(id != null && id > 0 && !TenantContextHolder.getTenantSkip(), "缺少实际租户上下文");
		return id;
	}

	private void code(String raw) {
		require(raw != null && !raw.isBlank() && raw.equals(raw.trim()) && raw.length() <= 200
				&& raw.chars().noneMatch(Character::isISOControl), "来源编码格式无效");
	}

	private static String hash(String raw) {
		try {
			return HexFormat.of()
					.formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception failure) {
			throw new CheckedException("来源指纹计算失败", failure);
		}
	}

	private static void require(boolean condition, String message) {
		if (!condition) {
			throw new CheckedException(message);
		}
	}
}
