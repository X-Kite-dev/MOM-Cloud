package com.kitex.mom.wms.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.wms.dto.app.WmsInBillDetailAppDTO;
import com.kitex.mom.wms.dto.app.WmsMobileReceiptHistoryDTO.Page;
import com.kitex.mom.wms.dto.app.WmsMobileNoticeListDTO;
import com.kitex.mom.wms.dto.app.WmsMobileInboundReceiptDTO.Command;
import com.kitex.mom.wms.dto.app.WmsMobileInboundReceiptDTO.Context;
import com.kitex.mom.wms.dto.app.WmsMobileInboundReceiptDTO.View;
import com.kitex.mom.wms.entity.WmsInNoticeEntity;
import com.kitex.mom.wms.entity.WmsInNoticeDetailEntity;
import com.kitex.mom.wms.enums.menu.MenuInBizTypeEnum;
import com.kitex.mom.wms.mapper.WmsInNoticeDetailMapper;
import com.kitex.mom.wms.mapper.WmsInNoticeMapper;
import com.kitex.mom.wms.mapper.WmsMobileInboundReceiptMapper;
import com.kitex.mom.wms.mapper.WmsMobileInboundReceiptMapper.Receipt;
import com.kitex.mom.wms.service.inBill.WmsInBillFactory;
import com.kitex.mom.wms.service.inNotice.WmsInNoticeFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.List;
import java.util.HashMap;

/**
 * 永久原请求与业务数量登记同事务；网络重试不得重新累计数量。
 */
@Service
@RequiredArgsConstructor
public class WmsMobileInboundReceiptService {

	private final WmsMobileInboundReceiptMapper receipts;
	private final WmsInNoticeMapper notices;
	private final WmsInNoticeDetailMapper details;
	private final PermissionService permissions;
	private final ObjectMapper json;
	private final WmsWarehouseBusinessGuard warehouseGuard;
	private final WmsMobileWarehouseAccess mobileAccess;

	public Context context() {
		Scope scope = scope();
		return new Context(1, scope.tenant(), scope.factory(), scope.operator());
	}

	/**
	 * 当前实际仓库的待办通知候选；只读摘要不表示库存、质量和来源已满足实际办理条件。
	 *
	 * @param warehouseCode 实际手机作业仓库，须有原出入库权限
	 * @param billType      当前收货入口，只查询该仓库已启用的对应入库类型
	 * @param reference     通知/来源编号或备注文本，最多80字
	 * @param page          1至100，每页最多20条，失败不得冒充空结果
	 * @return 当前组织和查询页的待处理/处理中候选，选择后仍须重读正式来源
	 */
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public WmsMobileNoticeListDTO.Page pendingNotices(String warehouseCode, String billType,
	                                                  String reference, int page) {
		Scope scope = scope();
		String warehouse = warehouse(warehouseCode);
		String type = text(billType);
		String keyword = text(reference);
		require(page >= 1 && page <= 100 && keyword.length() <= 80, "查询页码或条件超出允许范围");
		require(MenuInBizTypeEnum.getByCode(type) != null, "请选择当前收货业务入口");
		checkPermission(type, warehouse);
		warehouseGuard.checkMobile(warehouse);
		warehouseGuard.check(warehouse, type, true);
		List<String> types = List.of(type);
		var query = new QueryWrapper<WmsInNoticeEntity>()
				.select("id", "bill_type", "state", "warehouse_code", "warehouse_name", "document_id",
						"remark", "create_time", "create_by", "tenant_id", "factory_code", "del_flag")
				.eq("tenant_id", scope.tenant()).eq("factory_code", scope.factory())
				.eq("warehouse_code", warehouse).eq("del_flag", "0")
				.in("bill_type", types).in("state", List.of("0", "1"));
		if ("return".equals(type)) {
			query.and(part -> part.isNull("return_receipt_status")
					.or().in("return_receipt_status", List.of("WAITING", "RECEIVING")));
		}
		if (!keyword.isEmpty()) {
			boolean numeric = keyword.matches("[1-9][0-9]{0,18}")
					&& (keyword.length() < 19 || keyword.compareTo("9223372036854775807") <= 0);
			query.and(part -> {
				part.apply("POSITION({0} IN COALESCE(remark,'')) > 0", keyword);
				if (numeric) {
					part.or().eq("id", Long.valueOf(keyword)).or().eq("document_id", Long.valueOf(keyword));
				}
			});
		}
		// 固定顺序和21条预读；偏移量只来自已限定范围的整数，不拼接任何用户文本。
		query.orderByDesc("id").last("LIMIT 21 OFFSET " + ((page - 1) * 20));
		var rows = notices.selectList(query);
		var selectedRows = rows.stream().limit(20).toList();
		var previews = new HashMap<Long, WmsInNoticeDetailEntity>();
		if (!selectedRows.isEmpty()) {
			// 单次批量读取各通知的首项预览，不逐张加载全部明细，也不将不同物料单位相加。
			var previewRows = details.selectList(new QueryWrapper<WmsInNoticeDetailEntity>()
					.select("DISTINCT ON (notice_id) notice_id", "mat_code", "mat_name")
					.eq("tenant_id", scope.tenant()).eq("factory_code", scope.factory())
					.eq("warehouse_code", warehouse).eq("del_flag", "0")
					.in("notice_id", selectedRows.stream().map(row -> row.getId()).toList())
					.orderByAsc("notice_id", "id"));
			previewRows.forEach(row -> previews.put(row.getNoticeId(), row));
		}
		var records = selectedRows.stream().map(row -> {
			checkNoticeScope(row, scope, row.getId(), warehouse);
			checkPermission(row.getBillType(), warehouse);
			var preview = previews.get(row.getId());
			return new WmsMobileNoticeListDTO.Item(row.getId().toString(), row.getBillType(), row.getState(),
					warehouse, row.getWarehouseName(),
					row.getDocumentId() == null ? null : row.getDocumentId().toString(),
					preview == null ? null : preview.getMatCode(),
					preview == null ? null : preview.getMatName(),
					row.getRemark(), row.getCreateTime(), row.getCreateBy());
		}).toList();
		return new WmsMobileNoticeListDTO.Page(1, scope.tenant().toString(), scope.factory(), warehouse,
				type, page, rows.size() > 20, records);
	}

	/**
	 * 旧详情地址与现作业台共用实际通知范围；同一快照核原组织、仓库权限及返回明细。
	 * 查看来源不代表允许写库存，当前业务状态与配置仍在正式办理时重核。
	 *
	 * @param noticeId 实际入库通知主键
	 * @return 当前组织、原通知类型及授权仓库的完整来源
	 */
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public WmsInNoticeEntity sources(Long noticeId) {
		Scope scope = scope();
		require(noticeId != null && noticeId > 0, "请指定原入库通知");
		WmsInNoticeEntity notice = notices.selectById(noticeId);
		String warehouse = warehouse(notice == null ? null : notice.getWarehouseCode());
		checkNoticeScope(notice, scope, noticeId, warehouse);
		checkPermission(notice.getBillType(), warehouse);
		var result = WmsInNoticeFactory.getHandler(notice.getBillType()).selectByIdApp(noticeId);
		checkNoticeScope(result, scope, noticeId, warehouse);
		require(Objects.equals(result.getBillType(), notice.getBillType()) && result.getDetailList() != null,
				"通知业务类型或明细已变化，请重新读取收货来源");
		for (var detail : result.getDetailList()) {
			require(detail != null && detail.getId() != null && detail.getId() > 0
							&& Objects.equals(detail.getNoticeId(), noticeId)
							&& Objects.equals(detail.getTenantId(), scope.tenant())
							&& Objects.equals(detail.getFactoryCode(), scope.factory())
							&& Objects.equals(detail.getWarehouseCode(), warehouse),
					"收货来源明细不属于原通知或当前组织，请重新读取");
		}
		return result;
	}

	/**
	 * 兼容旧收货地址：先核实际来源及权限，再锁原通知重核类型/仓库和移动配置。
	 * 不为旧接口补造永久请求号；数量、精确来源、落位和库存仍交原专用处理器。
	 *
	 * @param input 原旧版收货正文，不改变原字段及单号返回合同
	 * @return 实际入库单号；无有效单号时整次回滚
	 */
	@Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
	public String executeLegacy(WmsInBillDetailAppDTO input) {
		Scope scope = scope();
		Long noticeId = input == null ? null : input.getNoticeId();
		require(noticeId != null && noticeId > 0, "请指定原入库通知");
		WmsInNoticeEntity original = notices.selectById(noticeId);
		String warehouse = warehouse(original == null ? null : original.getWarehouseCode());
		checkNoticeScope(original, scope, noticeId, warehouse);
		checkPermission(original.getBillType(), warehouse);
		mobileAccess.requireFactory(true);
		require(notices.lockNotice(noticeId) != null, "原入库通知不存在或不可访问");
		WmsInNoticeEntity locked = notices.selectById(noticeId);
		checkNoticeScope(locked, scope, noticeId, warehouse);
		require(Objects.equals(original.getBillType(), locked.getBillType()), "通知业务类型已变化，请重新读取");
		checkPermission(locked.getBillType(), warehouse);
		warehouseGuard.checkMobile(warehouse);
		String billNo = WmsInBillFactory.getHandler(locked.getBillType()).setInBillNum(input);
		require(!text(billNo).isEmpty() && billNo.length() <= 80, "收货未返回有效入库单号");
		return billNo;
	}

	@Transactional(rollbackFor = Exception.class)
	public View receive(Command command) {
		Scope scope = scope();
		Prepared prepared = prepare(command, scope);
		Receipt existing = receipts.find(scope.tenant(), command.requestId());
		if (existing != null) {
			return replay(existing, prepared, scope);
		}
		// 既有终态只读回原事实；首次实际登记另外核当前工厂写授权。
		mobileAccess.requireFactory(true);
		checkNotice(prepared, scope);
		warehouseGuard.checkMobile(prepared.row().warehouseCode());
		int reserved = receipts.reserve(prepared.row());
		Receipt row = receipts.findForUpdate(scope.tenant(), command.requestId());
		checkOriginal(row, prepared, scope);
		if (reserved == 0) {
			return terminal(row);
		}

		// 与原通知的修改、其他收货共用锁，锁内核对精确来源及标准单位。
		require(notices.lockNotice(row.noticeId()) != null, "原入库通知不存在或不可访问");
		checkNotice(prepared, scope);
		var source = details.selectById(row.noticeDetailId());
		require(source != null && Objects.equals(source.getNoticeId(), row.noticeId())
						&& Objects.equals(source.getTenantId(), scope.tenant())
						&& Objects.equals(source.getFactoryCode(), scope.factory())
						&& Objects.equals(source.getWarehouseCode(), row.warehouseCode())
						&& Objects.equals(source.getMatCode(), prepared.input().getMatCode())
						&& Objects.equals(source.getMatUnit(), prepared.input().getOperateUnit()),
				"收货明细或标准单位已变化，请核对原通知");
		String billNo = WmsInBillFactory.getHandler(row.billType()).receiveByOriginalRequest(prepared.input());
		require(!text(billNo).isEmpty() && billNo.length() <= 80, "收货未返回有效入库单号");
		complete(row, scope, "CONFIRMED", billNo, null);
		return terminal(receipts.find(scope.tenant(), row.requestId()));
	}

	@Transactional(rollbackFor = Exception.class)
	public View cancel(Command command, String reason) {
		Scope scope = scope();
		Prepared prepared = prepare(command, scope);
		String explanation = text(reason);
		require(!explanation.isEmpty() && explanation.length() <= 500, "请填写不超过500字的撤销原因");
		Receipt existing = receipts.find(scope.tenant(), command.requestId());
		if (existing != null) {
			return replay(existing, prepared, scope);
		}
		mobileAccess.requireFactory(true);
		checkPermission(prepared.row().billType(), prepared.row().warehouseCode());
		// 撤销只封存原号，允许原通知已变更时安全结束待核请求，不回退实物业务。
		int reserved = receipts.reserve(prepared.row());
		Receipt row = receipts.findForUpdate(scope.tenant(), command.requestId());
		checkOriginal(row, prepared, scope);
		if (reserved == 0) {
			return terminal(row);
		}
		complete(row, scope, "CANCELLED", null, explanation);
		return terminal(receipts.find(scope.tenant(), row.requestId()));
	}

	public View lookup(String requestId, Long noticeId, String warehouseCode) {
		Scope scope = scope();
		checkRequestId(requestId);
		String warehouse = warehouse(warehouseCode);
		require(noticeId != null && noticeId > 0, "请指定原入库通知");
		Receipt row = receipts.find(scope.tenant(), requestId);
		if (row != null) {
			checkIdentity(row, scope, noticeId, warehouse);
			checkPermission(row.billType(), warehouse);
			return terminal(row);
		}
		WmsInNoticeEntity notice = notices.selectById(noticeId);
		checkNoticeScope(notice, scope, noticeId, warehouse);
		checkPermission(notice.getBillType(), warehouse);
		// 未读到已提交回执不能证明原请求未执行，客户端仍只能使用原号重试或撤销。
		return new View(1, requestId, "NOT_CONFIRMED", noticeId, warehouse,
				null, BigDecimal.ZERO, null, null, null);
	}

	/**
	 * 原回执独立留存；查询不依赖仍有余额的库存或当前通知状态。
	 */
	@Transactional(readOnly = true)
	public Page<View> history(String warehouseCode, String billType, String reference, String status,
	                          int page) {
		Scope scope = scope();
		String warehouse = warehouse(warehouseCode);
		String type = text(billType);
		require(MenuInBizTypeEnum.getByCode(type) != null, "请选择当前收货业务入口");
		require(page >= 1 && page <= 100 && text(reference).length() <= 80,
				"查询页码或单据号超出允许范围");
		String state = text(status);
		require(state.isEmpty() || "CONFIRMED".equals(state) || "CANCELLED".equals(state),
				"请选择有效的作业结果");
		checkPermission(type, warehouse);
		LocalDateTime through = LocalDateTime.now();
		var rows = receipts.history(scope.tenant(), scope.factory(), scope.operator(), warehouse, type,
				text(reference), state, through.minusDays(30), through, (page - 1) * 20);
		var views = rows.stream().limit(20).map(row -> {
			checkIdentity(row, scope, row.noticeId(), warehouse);
			checkPermission(row.billType(), warehouse);
			return terminal(row);
		}).toList();
		return new Page<>(1, page, rows.size() > 20, views);
	}

	private View replay(Receipt row, Prepared prepared, Scope scope) {
		checkOriginal(row, prepared, scope);
		checkPermission(row.billType(), row.warehouseCode());
		return terminal(row);
	}

	private void complete(Receipt row, Scope scope, String status, String billNo, String reason) {
		require(receipts.complete(scope.tenant(), scope.factory(), scope.operator(), row.requestId(),
						row.requestFingerprint(), status, billNo, LocalDateTime.now(), reason) == 1,
				"收货回执确认失败，请按原请求核对结果");
	}

	private View terminal(Receipt row) {
		require(row != null && ("CONFIRMED".equals(row.status()) || "CANCELLED".equals(row.status())),
				"收货结果待核对，请保留原请求");
		return new View(1, row.requestId(), row.status(), row.noticeId(), row.warehouseCode(), row.billNo(),
				"CONFIRMED".equals(row.status()) ? row.requestedQuantity() : BigDecimal.ZERO,
				row.requestPayload(), row.completedTime(), row.cancellationReason());
	}

	private void checkOriginal(Receipt row, Prepared prepared, Scope scope) {
		require(row != null, "收货原请求不存在，请保留原号核对");
		checkIdentity(row, scope, prepared.row().noticeId(), prepared.row().warehouseCode());
		require(Objects.equals(row.requestFingerprint(), prepared.row().requestFingerprint())
						&& Objects.equals(row.requestPayload(), prepared.row().requestPayload()),
				"原收货请求内容不可变更，请先核对或安全撤销原请求");
	}

	private void checkIdentity(Receipt row, Scope scope, Long noticeId, String warehouse) {
		require(Objects.equals(row.tenantId(), scope.tenant())
						&& Objects.equals(row.factoryCode(), scope.factory())
						&& Objects.equals(row.operatorCode(), scope.operator())
						&& Objects.equals(row.noticeId(), noticeId) && Objects.equals(row.warehouseCode(), warehouse),
				"原收货请求不属于当前账号、工厂、仓库或通知");
	}

	private void checkNotice(Prepared prepared, Scope scope) {
		Receipt row = prepared.row();
		WmsInNoticeEntity notice = notices.selectById(row.noticeId());
		checkNoticeScope(notice, scope, row.noticeId(), row.warehouseCode());
		require(Objects.equals(notice.getBillType(), row.billType()), "收货业务类型与原通知不一致");
		checkPermission(row.billType(), row.warehouseCode());
	}

	private void checkNoticeScope(WmsInNoticeEntity notice, Scope scope, Long noticeId, String warehouse) {
		require(notice != null && Objects.equals(notice.getId(), noticeId)
				&& "0".equals(notice.getDelFlag())
				&& Objects.equals(notice.getTenantId(), scope.tenant())
				&& Objects.equals(notice.getFactoryCode(), scope.factory())
				&& Objects.equals(notice.getWarehouseCode(), warehouse), "原入库通知不存在或不可访问");
	}

	private void checkPermission(String type, String warehouse) {
		require(MenuInBizTypeEnum.getByCode(type) != null, "不支持当前收货业务");
		String permission = "wms_notice_inout_" + type;
		require(permissions.hasPermission(permission) ||
						permissions.hasPermission(permission + "_" + warehouse),
				"当前账号无权办理该仓库收货");
	}

	private Prepared prepare(Command command, Scope scope) {
		require(command != null && command.receipt() != null, "收货原请求不能为空");
		checkRequestId(command.requestId());
		String warehouse = warehouse(command.warehouseCode());
		String type = text(command.billType());
		require(MenuInBizTypeEnum.getByCode(type) != null, "不支持当前收货业务");
		WmsInBillDetailAppDTO source = command.receipt();
		require(source.getNoticeId() != null && source.getNoticeId() > 0
						&& source.getNoticeDetailId() != null && source.getNoticeDetailId() > 0,
				"收货必须指定原通知及精确来源明细");
		require(source.getMatNum() != null && Double.isFinite(source.getMatNum()) && source.getMatNum() > 0
						&& source.getOperateNum() != null && Double.isFinite(source.getOperateNum())
						&& Double.compare(source.getMatNum(), source.getOperateNum()) == 0,
				"请按标准单位填写一致的收货数量");
		require(!text(source.getMatCode()).isEmpty() && !text(source.getSiteCode()).isEmpty()
				&& !text(source.getOperateUnit()).isEmpty(), "物料、库位和标准单位不能为空");
		// 仅复制实际作业字段，显示名称、货权等从受锁保护的原通知继承。
		var input = new WmsInBillDetailAppDTO().setNoticeId(source.getNoticeId())
				.setNoticeDetailId(source.getNoticeDetailId()).setBillNo(optional(source.getBillNo()))
				.setMatCode(text(source.getMatCode())).setMatBatch(optional(source.getMatBatch()))
				.setMatBarcode(optional(source.getMatBarcode())).setMatNum(source.getMatNum())
				.setOperateNum(source.getMatNum()).setOperateUnit(text(source.getOperateUnit()))
				.setSupplierCode(optional(source.getSupplierCode())).setSiteCode(text(source.getSiteCode()))
				.setContainerCode(optional(source.getContainerCode()));
		BigDecimal quantity = BigDecimal.valueOf(input.getMatNum()).stripTrailingZeros();
		var payload = new LinkedHashMap<String, Object>();
		payload.put("warehouseCode", warehouse);
		payload.put("billType", type);
		payload.put("noticeId", input.getNoticeId().toString());
		payload.put("noticeDetailId", input.getNoticeDetailId().toString());
		payload.put("billNo", text(input.getBillNo()));
		payload.put("matCode", input.getMatCode());
		payload.put("matBatch", text(input.getMatBatch()));
		payload.put("matBarcode", text(input.getMatBarcode()));
		payload.put("matNum", quantity);
		payload.put("operateNum", quantity);
		payload.put("operateUnit", input.getOperateUnit());
		payload.put("supplierCode", text(input.getSupplierCode()));
		payload.put("siteCode", input.getSiteCode());
		payload.put("containerCode", text(input.getContainerCode()));
		try {
			String serialized = json.writeValueAsString(payload);
			String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(serialized.getBytes(StandardCharsets.UTF_8)));
			var row = new Receipt(scope.tenant(), scope.factory(), scope.operator(), command.requestId(),
					input.getNoticeId(), input.getNoticeDetailId(), warehouse, type, serialized, fingerprint,
					"PENDING", null, quantity, null, null);
			return new Prepared(row, input);
		} catch (JsonProcessingException | NoSuchAlgorithmException exception) {
			throw new CheckedException("收货原请求无法编码，请保留原记录");
		}
	}

	/**
	 * 读取及既有终态先核工厂read；首次登记和未登记原号结束另在写入前核write。
	 */
	private Scope scope() {
		mobileAccess.requireFactory(false);
		Long tenant = TenantContextHolder.getTenantId();
		String factory = FactoryContextHolder.getFactoryCode();
		var actor = SecurityUtils.getUser();
		require(tenant != null && tenant > 0 && !Boolean.TRUE.equals(TenantContextHolder.getTenantSkip())
				&& !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip()) && !text(factory).isEmpty()
				&& actor != null && !text(actor.getUsername()).isEmpty(), "收货需要实际租户、工厂和登录账号");
		return new Scope(tenant, factory, actor.getUsername());
	}

	private static String warehouse(String value) {
		String code = text(value);
		require(!code.isEmpty() && code.length() <= 64 && !code.contains("*") && !code.contains("?"),
				"请指定实际收货仓库");
		return code;
	}

	private static void checkRequestId(String value) {
		require(value != null && value.matches("[A-Za-z0-9][A-Za-z0-9_-]{15,63}"), "原收货请求号无效");
	}

	private static String text(String value) {
		return value == null ? "" : value.trim();
	}

	private static String optional(String value) {
		return text(value).isEmpty() ? null : text(value);
	}

	private static void require(boolean condition, String message) {
		if (!condition) {
			throw new CheckedException(message);
		}
	}

	private record Scope(Long tenant, String factory, String operator) {
	}

	private record Prepared(Receipt row, WmsInBillDetailAppDTO input) {
	}
}
