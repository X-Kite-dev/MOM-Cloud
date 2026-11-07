package com.kitex.mom.mes.service.impl;

import com.kitex.mom.mdm.dto.MdmMaterialMeasureDTO;
import com.kitex.mom.mes.dto.ProcessProcedureDTO;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.entity.MesWarehouseFeedEntity;
import com.kitex.mom.mes.entity.MesWarehouseLiveStockEntity;
import com.kitex.mom.mes.service.impl.material.MesProductionFeedAllocationPlanner;
import com.kitex.mom.mes.service.impl.material.MesBackflushAllocationPlanner;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Objects;

import static com.kitex.mom.mes.service.impl.MesPhysicalTransformCodec.require;

import java.util.function.Function;

/**
 * 一个真实NG按原需求方式计算独立份额；不记产、不扣料、不兼作GOOD路径。
 */
@Component
@RequiredArgsConstructor
public class MesNgMaterialPolicyPlanner {
	private final MesProductionFeedAllocationPlanner feedPlanner;
	private final MesBackflushAllocationPlanner stockPlanner;

	/**
	 * 按一个真实NG加工量和原冻结需求分区计算FEED及STOCK精确份额；不写库存、不记产。重复需求、政策全集不符、单位或来源不足均拒绝。
	 */
	public Plan plan(MesTaskArrangeEntity arrange, String sourceId, BigDecimal quantity, String unit,
	                 MesMaterialConsumptionModeResolver.Selection selection,
	                 List<ProcessProcedureDTO.ProcessProcedureMatDTO> requirements, String bom,
	                 List<MesWarehouseFeedEntity> feeds,
	                 Function<MesBackflushAllocationPlanner.Request, List<MesWarehouseLiveStockEntity>> stockReader,
	                 Function<String, MdmMaterialMeasureDTO> materials) {
		require(arrange != null && sourceId != null && sourceId.matches("[1-9][0-9]{0,18}")
						&& selection != null && requirements != null && requirements.size() <= 1000
						&& Objects.equals(selection.routeNodeCode(), arrange.getSourceRouteNodeCode()),
				"NG原政策、稳定节点或需求全集不完整");
		var codes = new HashSet<String>();
		for (var row : requirements) {
			require(row != null && row.getMaterialCode() != null && !row.getMaterialCode().isBlank()
					&& codes.add(row.getMaterialCode()), "NG原需求缺失或重复，不能合并猜测");
			if (selection.perMaterial()) {
				require(selection.forRequirement(row.getMaterialCode()).name().equals(row.getConsumptionMode()),
						"NG原需求方式偏离原开工逐物料政策");
			}
		}
		require(!selection.perMaterial() || codes.equals(selection.requirementModes().keySet()),
				"NG原开工政策与原需求全集不同");
		var feedRequirements = MesMaterialConsumptionModeResolver.partition(selection.mode(), requirements,
				MesMaterialConsumptionModeResolver.Mode.FEED);
		var stockRequirements = MesMaterialConsumptionModeResolver.partition(selection.mode(), requirements,
				MesMaterialConsumptionModeResolver.Mode.BACKFLUSH);
		var measured = new HashMap<String, MdmMaterialMeasureDTO>();
		Function<String, MdmMaterialMeasureDTO> originalMeasures = code -> measured.computeIfAbsent(code, materials);
		var feedPlan = feedPlanner.plan(new MesProductionFeedAllocationPlanner.Scope(arrange.getTenantId(),
						arrange.getFactoryCode(), arrange.getTaskCode(), arrange.getProduceTaskCode(), arrange.getCode(), unit),
				List.of(new MesProductionFeedAllocationPlanner.OutputDemand(sourceId,
						MesProductionFeedAllocationPlanner.OutputKind.NG, quantity, unit)),
				feedRequirements, bom, feeds, originalMeasures);
		require(feedPlan.attributions().size() == feedPlan.consumptions().size()
						&& feedPlan.attributions().stream().allMatch(row -> sourceId.equals(row.outputKey())
						&& row.kind() == MesProductionFeedAllocationPlanner.OutputKind.NG),
				"单NG只能保存本人原产出的全部真实份额，不能接GOOD或组合总量");
		var stockPlan = stockPlanner.plan(arrange, quantity, stockRequirements, bom, stockReader, originalMeasures);
		return new Plan(feedPlan.consumptions(), stockPlan);
	}

	/**
	 * 一个真实NG的两个独立来源分区；每份额沿各自原单位。
	 */
	public record Plan(
			/** 原投料消费份额完整集合。 */
			List<MesProductionFeedAllocationPlanner.FeedConsumption> feeds,
			/** 原库存倒冲份额完整集合。 */
			List<MesBackflushAllocationPlanner.Allocation> stocks) {
		public Plan {
			feeds = List.copyOf(feeds);
			stocks = List.copyOf(stocks);
		}
	}
}
