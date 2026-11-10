package com.kitex.mom.pms.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.dto.MdmFactoryAccessCheck;
import com.kitex.mom.mdm.feign.RemoteMdmFactoryAccessService;
import com.kitex.mom.pms.dto.app.WmsPurchaseReceiptParamsDTO;
import com.kitex.mom.pms.entity.PmsOrderDetailEntity;
import com.kitex.mom.pms.entity.PmsOrderEntity;
import com.kitex.mom.pms.entity.WmsPurchaseReceiptEntity;
import com.kitex.mom.pms.exception.PmsReceiptRegistrationRejectedException;
import com.kitex.mom.pms.mapper.PmsExistingReceiptRegistrationMapper;
import com.kitex.mom.pms.mapper.PmsOrderDetailMapper;
import com.kitex.mom.pms.mapper.PmsOrderMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 已存在采购收料单的取证与实收登记编排：校验实名组织、串行核剩余量并保存原行实物事实。
 * 保留原价格及冻结质量配置；不创建第二张收料单，不代替质量判定或WMS库存实收。
 */
@Service
@RequiredArgsConstructor
public class PmsExistingReceiptRegistrationService {

	private final PmsExistingReceiptRegistrationMapper mapper;
	private final PmsOrderMapper orders;
	private final PmsOrderDetailMapper orderLines;
	private final RemoteMdmFactoryAccessService factories;

	/**
	 * 在同一只读快照内读取当前租户/工厂的唯一原单、明细和质量批次。
	 * 客户端传入的其他实体字段不参与组织或对象归属判断。
	 *
	 * @param billNo 完整原收料单号
	 * @return 原单及所属明细/批次；无权、不存在或单号重复时拒绝，不能任取第一单
	 */
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public WmsPurchaseReceiptEntity readByBillNo(String billNo) {
		require(StrUtil.isNotBlank(billNo), "请输入完整原收料单号");
		String factory = requireFactory("read");
		Long tenant = TenantContextHolder.getTenantId();
		WmsPurchaseReceiptEntity bill = uniqueBill(tenant, factory, billNo, false);
		bill.setDetails(mapper.details(tenant, factory, bill.getId()));
		bill.setBatchDetails(mapper.batches(tenant, factory, bill.getId()));
		return bill;
	}

	/**
	 * 对同一原单的并发登记串行处理，精确累计数量不得超过原应收量。
	 * 此旧接口没有永久请求号；结果未知必须先读取原行，不能自动重发部分收料。
	 *
	 * @param command 原单、原物料/明细、本次基本单位数量及实物批次日期
	 * @return 实收事实与单据状态已在同一事务保存
	 * @throws CheckedException 身份/组织、原单/原行、状态、数量或实物批次不符合登记条件
	 */
	@Transactional(rollbackFor = Exception.class)
	public Boolean register(WmsPurchaseReceiptParamsDTO command) {
		validate(command);
		String factory = requireFactory("write");
		Long tenant = TenantContextHolder.getTenantId();
		WmsPurchaseReceiptEntity initial = uniqueBill(tenant, factory, command.getBillNo(), false);
		// 与订单受控变更沿相同顺序先锁原订单，再锁收料单，防在登记期间撤回订单剩余量。
		PmsOrderEntity order = lockOrder(initial, tenant, factory);
		WmsPurchaseReceiptEntity bill = uniqueBill(tenant, factory, command.getBillNo(), true);
		require(Objects.equals(initial.getId(), bill.getId())
				&& Objects.equals(initial.getPmsOrderNo(), bill.getPmsOrderNo())
				&& Objects.equals(initial.getSupplierCode(), bill.getSupplierCode()), "原收料单来源已变化，请重新读取");
		require(Set.of("0", "1").contains(Objects.toString(bill.getState(), "")),
				"只有待处理或处理中收料单允许登记实收");

		List<PmsExistingReceiptRegistrationMapper.Line> matches = mapper.lines(tenant, factory, bill.getId(),
				command.getMatCode(), command.getReceiptDetailId());
		require(matches.size() == 1, "原收料明细不存在或物料对应多行，请选择精确收料明细");
		var line = matches.get(0);
		require(Boolean.FALSE.equals(line.getWmsStatus()), "原收料行已进入后续处理或状态未知，不能续收");
		require(mapper.batchCount(tenant, factory, bill.getId(), line.getMatCode(), line.getOrderDetailId()) == 0,
				"原物料已形成质量批次，不能通过总量登记覆盖实物事实");
		require(line.getShouldQuantity() != null && line.getActualQuantity() != null
						&& line.getShouldQuantity().signum() >= 0 && line.getActualQuantity().signum() >= 0,
				"原应收或实收数量缺失或无效，不能推断为零");
		BigDecimal received = line.getActualQuantity().add(command.getMatNum());
		require(received.compareTo(line.getShouldQuantity()) <= 0,
				"本次实收超过原收料行剩余应收数量，请重新读取核对");
		if (line.getActualQuantity().signum() > 0) {
			require(Objects.equals(line.getMatBatch(), command.getMatBatch())
							&& Objects.equals(line.getProduceDate(), command.getMatProduceDate()),
					"当前原行已有不同批次或生产日期，请独立分单收料，不能覆盖原实物批次");
		}

		BigDecimal price = authoritativePrice(order, line, tenant, factory);
		String actor = SecurityUtils.getUser().getUsername();
		require(mapper.receive(tenant, factory, bill.getId(), line, received, command.getMatBatch(),
				command.getMatProduceDate(), price, actor) == 1, "原收料行已变化，登记未保存，请重新核对");
		if ("0".equals(bill.getState())) {
			require(mapper.begin(tenant, factory, bill.getId(), actor) == 1, "收料单状态推进失败，实收登记必须回滚");
		}
		return Boolean.TRUE;
	}

	/**
	 * 服务调用同样防守必要字段与真实numeric(24,6)精度，不依赖客户端或Controller校验。
	 */
	private void validate(WmsPurchaseReceiptParamsDTO command) {
		require(command != null, "实收登记不能为空");
		require(!StrUtil.hasBlank(command.getBillNo(), command.getMatCode(), command.getMatBatch()),
				"请填写原收料单、物料和实物批次");
		require(command.getReceiptDetailId() == null || command.getReceiptDetailId() > 0, "原收料明细ID无效");
		require(command.getMatBatch().length() <= 255, "实物批次最多255个字符");
		require(command.getMatProduceDate() != null && !command.getMatProduceDate().isAfter(LocalDate.now()),
				"生产日期不能为空或晚于今天");
		BigDecimal amount = command.getMatNum();
		require(amount != null && amount.signum() > 0, "本次实收数量必须为正数");
		BigDecimal normalized = amount.stripTrailingZeros();
		require(Math.max(0, normalized.scale()) <= 6 && normalized.precision() - normalized.scale() <= 18,
				"实收数量最多18位整数和6位小数");
	}

	/**
	 * 工厂编码来自实际请求上下文，并由MDM核验当前人员读/写权限，禁止跳过组织隔离。
	 */
	private String requireFactory(String permission) {
		var authentication = SecurityUtils.getAuthentication();
		var user = SecurityUtils.getUser();
		Long tenant = TenantContextHolder.getTenantId();
		String factory = FactoryContextHolder.getFactoryCode();
		require(authentication != null && authentication.isAuthenticated() && user != null
						&& user.getId() != null && user.getId() > 0 && StrUtil.isNotBlank(user.getUsername())
						&& tenant != null && tenant > 0
						&& Objects.equals(user.getTenantId(), tenant) && !TenantContextHolder.getTenantSkip()
						&& !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip()) && StrUtil.isNotBlank(factory),
				"请实名登录并选择实际工厂，禁止跳过组织隔离");
		R<Boolean> access;
		try {
			access = factories.check(new MdmFactoryAccessCheck().setUserId(user.getId())
					.setFactoryCode(factory).setRequiredPermission(permission));
		} catch (RuntimeException failure) {
			throw new CheckedException("工厂访问权限暂时无法核验，请稍后重试", failure);
		}
		require(access != null && access.isOk() && Boolean.TRUE.equals(access.getData()), "当前工厂访问权限无法确认");
		return factory;
	}

	/**
	 * 同组织同原号重复时拒绝取第一单，防旧数据歧义落错实物。
	 */
	private WmsPurchaseReceiptEntity uniqueBill(Long tenant, String factory, String billNo, boolean lock) {
		List<WmsPurchaseReceiptEntity> bills = mapper.bills(tenant, factory, billNo, lock);
		require(bills.size() == 1, "当前工厂下原收料单不存在或原号不唯一");
		return bills.get(0);
	}

	/**
	 * 订单来源单据只允许在已审批、收货中或部分到货时继续登记。手工无订单单据保留原来源。
	 */
	private PmsOrderEntity lockOrder(WmsPurchaseReceiptEntity bill, Long tenant, String factory) {
		if (StrUtil.isBlank(bill.getPmsOrderNo())) {
			return null;
		}
		List<PmsOrderEntity> matches = orders.selectList(new LambdaQueryWrapper<PmsOrderEntity>()
				.eq(PmsOrderEntity::getTenantId, tenant).eq(PmsOrderEntity::getFactoryCode, factory)
				.eq(PmsOrderEntity::getPmsOrderNo, bill.getPmsOrderNo())
				.eq(PmsOrderEntity::getSupplierCode, bill.getSupplierCode()).last("LIMIT 2 FOR UPDATE"));
		require(matches.size() == 1, "原收料单所属采购订单不存在或不唯一");
		PmsOrderEntity order = matches.get(0);
		require(Set.of("2", "3", "4").contains(Objects.toString(order.getStatus(), "")),
				"采购订单当前状态不允许继续收料");
		return order;
	}

	/**
	 * 只沿已保存收料价或精确所属订单行取价；无订单且未知的价格保持未知，不接收客户端改价。
	 */
	private BigDecimal authoritativePrice(PmsOrderEntity order, PmsExistingReceiptRegistrationMapper.Line line,
	                                      Long tenant, String factory) {
		if (order == null) {
			return line.getPrice();
		}
		require(line.getOrderDetailId() != null, "订单来源收料行缺少精确原订单行");
		PmsOrderDetailEntity original = orderLines.selectOne(new LambdaQueryWrapper<PmsOrderDetailEntity>()
				.eq(PmsOrderDetailEntity::getId, line.getOrderDetailId())
				.eq(PmsOrderDetailEntity::getTenantId, tenant).eq(PmsOrderDetailEntity::getFactoryCode, factory)
				.eq(PmsOrderDetailEntity::getPmsOrderId, order.getId())
				.eq(PmsOrderDetailEntity::getMatCode, line.getMatCode()));
		require(original != null, "原收料行不属于当前采购订单物料行");
		return line.getPrice() != null ? line.getPrice() : original.getPrice();
	}

	/**
	 * 登记自身的校验和条件写失败使用窄领域异常；由事务代理回滚后，Controller才可返回明确拒绝。
	 * 不在此处捕获或包装数据库、提交等未知异常。
	 */
	private void require(boolean accepted, String message) {
		if (!accepted) {
			throw new PmsReceiptRegistrationRejectedException(message);
		}
	}
}
