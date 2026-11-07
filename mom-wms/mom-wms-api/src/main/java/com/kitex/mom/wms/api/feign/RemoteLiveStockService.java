package com.kitex.mom.wms.api.feign;

import com.kitex.common.core.util.R;
import com.kitex.common.feign.annotation.InternalCall;
import com.kitex.mom.wms.Constants;
import com.kitex.mom.wms.dto.*;
import com.kitex.mom.wms.entity.WmsLiveStockEntity;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.cloud.openfeign.SpringQueryMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 查询实时库存
 */
@FeignClient(contextId = "remoteLiveStockService", value = Constants.Name)
public interface RemoteLiveStockService {
	/**
	 * 根据产品编码集合 查询实时库存
	 */
	@GetMapping("/material/wmsLiveStock/getLiveStockByProductCodeList")
	R<List<WmsLiveStockEntity>> getLiveStockByProductCodeList(@SpringQueryMap WmsLiveStockByProductCodeDto w);

	/**
	 * 根据产品编码集合 查询实时库存
	 */
	@GetMapping("/material/wmsLiveStock/getLiveStockList")
	R<List<WmsLiveStockEntity>> getLiveStockList(@SpringQueryMap WmsLiveStockQueryDTO wmsLiveStock);

	@InternalCall
	@PostMapping("/material/wmsLiveStock/quality/status")
	R<WmsQualityStatusResult> applyQualityStatus(@RequestBody WmsQualityStatusCommand command);

	/**
	 * 特殊退回正式质量命令专用入口；旧WMS无此路径时失败关闭，禁止退回通用FIFO接口。
	 */
	@InternalCall
	@PostMapping("/material/wmsLiveStock/quality/special-return/status")
	R<WmsQualityStatusResult> applySpecialReturnQualityStatus(@RequestBody WmsQualityStatusCommand command);

	/**
	 * 实名透传读取真实特殊退回隔离候选；仍核人员工厂及原仓读取权限。
	 */
	default R<WmsSpecialReturnQualityDTO.CandidatePage> specialReturnQualityCandidates(
			WmsSpecialReturnQualityDTO.CandidateQuery query) {
		return readSpecialReturnQualityCandidates(query.warehouseCode(), query.orderId(), query.reference(),
				query.page(), query.size());
	}

	/**
	 * Feign原生BeanQueryMapEncoder不识别record读取器，显式参数保留全部选择条件。
	 */
	@InternalCall
	@GetMapping("/material/wmsLiveStock/quality/special-return/candidates")
	R<WmsSpecialReturnQualityDTO.CandidatePage> readSpecialReturnQualityCandidates(
			@RequestParam("warehouseCode") String warehouseCode,
			@RequestParam(value = "orderId", required = false) String orderId,
			@RequestParam(value = "reference", required = false) String reference,
			@RequestParam("page") int page, @RequestParam("size") int size);

	/**
	 * 按候选选择重新证明原件，返回服务端规范冻结目标；不写库存。
	 */
	@InternalCall
	@PostMapping("/material/wmsLiveStock/quality/special-return/preflight")
	R<WmsSpecialReturnQualityDTO.Target> preflightSpecialReturnQuality(
			@RequestBody WmsSpecialReturnQualityDTO.Selection selection);

	/**
	 * 实名只读证明当前原返工影响全集；输入单位可空，输出单位必须真实唯一。
	 */
	@InternalCall
	@PostMapping("/material/wmsLiveStock/quality/ncr-rework/inspect")
	R<WmsNcrReworkDTO.Source> inspectNcrRework(@RequestBody WmsNcrReworkDTO.Key key);

	/**
	 * 按永久原号查回完整回执；目录、授权和通信故障不得返回NOT_FOUND。
	 */
	@InternalCall
	@PostMapping("/material/wmsLiveStock/quality/ncr-rework/lookup")
	R<WmsNcrReworkDTO.Lookup> lookupNcrRework(@RequestBody WmsNcrReworkDTO.Command command);

	/**
	 * 当前原复核人按原文办理整量原位终态，禁止改量或换件。
	 */
	@InternalCall
	@PostMapping("/material/wmsLiveStock/quality/ncr-rework/apply")
	R<WmsNcrReworkDTO.Receipt> applyNcrRework(@RequestBody WmsNcrReworkDTO.Command command);

	/**
	 * 未受理原号也写永久取消墓碑；已生效则只返回原生效事实。
	 */
	@InternalCall
	@PostMapping("/material/wmsLiveStock/quality/ncr-rework/cancel")
	R<WmsNcrReworkDTO.Receipt> cancelNcrRework(@RequestBody WmsNcrReworkDTO.Command command);

	/**
	 * 普通定向重隔离专用，不允许回落通用FIFO接口。
	 */
	@InternalCall
	@PostMapping("/material/wmsLiveStock/quality/physical/rehold-status")
	R<WmsQualityStatusResult> applyPhysicalReholdStatus(@RequestBody WmsQualityStatusCommand command);

	@PostMapping("/material/wmsLiveStock/quality/execute")
	R<WmsQualityStatusResult> executeQualityAction(@RequestBody WmsQualityActionExecuteCommand command);
}
