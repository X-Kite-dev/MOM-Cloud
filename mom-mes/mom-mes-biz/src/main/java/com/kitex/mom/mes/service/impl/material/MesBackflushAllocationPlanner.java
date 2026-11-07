package com.kitex.mom.mes.service.impl.material;

import com.kitex.mom.mdm.dto.MdmMaterialMeasureDTO;
import com.kitex.mom.mes.dto.ProcessProcedureDTO;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.entity.MesWarehouseLiveStockEntity;
import com.kitex.mom.mes.service.impl.MesPhysicalTransformCodec;
import com.kitex.mom.mes.util.MesMaterialSubstituteResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

import static com.kitex.mom.mes.service.impl.MesPhysicalTransformCodec.require;

import com.kitex.common.core.exception.CheckedException;

import java.util.function.Function;

/**
 * 倒冲只读分配计算；来源必须由真实库存资格/FIFO入口读取，实际出库仍逐行重新校验。
 */
@Component
@RequiredArgsConstructor
public class MesBackflushAllocationPlanner {
	private final MesPhysicalTransformCodec codec;

	/**
	 * 以本次加工量和冻结单耗生成完整倒冲分配，不修改库存。
	 * reader须返回原组织、实际需求候选的FIFO原行；materialReader提供精确计量规则。
	 * 需求缺失、单位不符、替代比例不可精确换算或任何物料不足时整笔拒绝，不返回部分成功。
	 */
	public List<Allocation> plan(MesTaskArrangeEntity arrange, BigDecimal quantity,
	                             List<ProcessProcedureDTO.ProcessProcedureMatDTO> requirements, String bomSnapshot,
	                             Function<Request, List<MesWarehouseLiveStockEntity>> reader,
	                             Function<String, MdmMaterialMeasureDTO> materialReader) {
		require(arrange != null && arrange.getTenantId() != null && arrange.getTenantId() > 0
						&& arrange.getFactoryCode() != null && arrange.getProduceTaskCode() != null,
				"倒冲分配缺少真实组织及原工序归属");
		amount(quantity, false, "本次加工量");
		require(requirements != null && requirements.size() <= 1000 && reader != null && materialReader != null,
				"倒冲原需求或真实来源读取不完整");
		var result = new ArrayList<Allocation>();
		var used = new HashMap<Long, BigDecimal>();
		var observed = new HashMap<Long, String>();
		var demandCodes = new HashSet<String>();
		for (var demand : requirements) {
			require(demand != null, "倒冲存在空需求，不能略过");
			String code = codec.code(demand.getMaterialCode(), "原倒冲需求", 100);
			String unit = codec.code(demand.getUnit(), "原倒冲需求单位", 100);
			require(demandCodes.add(code), "倒冲需求重复，不能合并猜测");
			BigDecimal needed = amount(quantity.multiply(amount(demand.getQuantity(), false, "原倒冲单耗")), false,
					"原倒冲需求总量");
			var policy = MesMaterialSubstituteResolver.resolvePolicy(bomSnapshot, code);
			var supplies = new LinkedHashMap<MesMaterialSubstituteResolver.Candidate, List<MesWarehouseLiveStockEntity>>();
			for (var candidate : policy.candidates()) {
				amount(candidate.ratio(), false, "冻结替代比例");
				var measure = materialReader.apply(candidate.actualMatCode());
				validateMeasure(arrange, candidate.actualMatCode(), unit, candidate.substitute(), measure);
				var rows = reader.apply(new Request(code, candidate));
				require(rows != null && rows.size() <= 5000, "倒冲来源未知或超过5000行，不能截断");
				var ids = new HashSet<Long>();
				for (var stock : rows) {
					validateStock(arrange, stock, candidate.actualMatCode(), measure.getBasicUnit());
					require(ids.add(stock.getId()), "倒冲候选重复，不能两次认领同一库存余额");
					var fact = new LinkedHashMap<String, Object>();
					fact.put("id", stock.getId().toString());
					fact.put("quantity", codec.amount(stock.getNum()));
					fact.put("material", stock.getMatCode());
					fact.put("unit", stock.getMatUnit());
					fact.put("reservation", stock.getReservationNo());
					fact.put("requirement", stock.getRequirementMatCode());
					fact.put("process", stock.getProduceTaskCode());
					String snapshot = codec.snapshot(fact);
					String previous = observed.putIfAbsent(stock.getId(), snapshot);
					require(previous == null || previous.equals(snapshot),
							"同一库存的余额或来源在本次观察中变化，须重试原请求");
				}
				supplies.put(candidate, List.copyOf(rows));
			}
			var candidates = policy.candidates();
			if (!policy.allowMixed()) {
				candidates = candidates.stream().filter(candidate -> available(supplies.get(candidate), used)
								.compareTo(needed.multiply(candidate.ratio())) >= 0).findFirst().map(List::of)
						.orElseThrow(() -> new CheckedException("倒冲需求禁止混用，主料或单一替代料不能满足：" + code));
			}
			BigDecimal remaining = needed;
			for (var candidate : candidates) {
				for (var stock : supplies.get(candidate)) {
					if (remaining.signum() == 0) {
						break;
					}
					BigDecimal before = stock.getNum().subtract(used.getOrDefault(stock.getId(), BigDecimal.ZERO));
					BigDecimal actual = before.min(remaining.multiply(candidate.ratio()));
					if (actual.signum() <= 0) {
						continue;
					}
					BigDecimal satisfied;
					try {
						satisfied = amount(actual.divide(candidate.ratio()), false, "倒冲原需求当量");
					} catch (ArithmeticException error) {
						throw new CheckedException("倒冲替代比例无法精确分配，禁止舍入扣库");
					}
					result.add(new Allocation(stock.getId(), code, unit, stock.getMatCode(), stock.getMatUnit(),
							amount(actual, false, "倒冲实际份额"), satisfied, candidate.ratio(), candidate.substitute(),
							before, before.subtract(actual), stock.getReservationNo(), stock.getRequirementMatCode()));
					require(result.size() <= 5000, "倒冲分配超过5000行，不能截断");
					used.merge(stock.getId(), actual, BigDecimal::add);
					remaining = remaining.subtract(satisfied);
				}
				if (remaining.signum() == 0) {
					break;
				}
			}
			require(remaining.signum() == 0, "本次原需求倒冲库存不足：" + code);
		}
		return List.copyOf(result);
	}

	private BigDecimal available(List<MesWarehouseLiveStockEntity> rows, Map<Long, BigDecimal> used) {
		return rows.stream().map(stock -> stock.getNum().subtract(used.getOrDefault(stock.getId(), BigDecimal.ZERO)))
				.reduce(BigDecimal.ZERO, BigDecimal::add);
	}

	private void validateMeasure(MesTaskArrangeEntity arrange, String code, String unit, boolean substitute,
	                             MdmMaterialMeasureDTO measure) {
		require(measure != null && Boolean.TRUE.equals(measure.getEnabled())
				&& Objects.equals(measure.getTenantId(), arrange.getTenantId().toString())
				&& Objects.equals(measure.getFactoryCode(), arrange.getFactoryCode())
				&& Objects.equals(measure.getMaterialCode(), code), "倒冲物料计量或组织归属不可核验");
		codec.id(measure.getMaterialId());
		codec.code(measure.getBasicUnit(), "倒冲实际物料单位", 100);
		require(substitute || Objects.equals(unit, measure.getBasicUnit()), "倒冲主料原单位不同，禁止隐式换算");
	}

	private void validateStock(MesTaskArrangeEntity arrange, MesWarehouseLiveStockEntity stock, String code,
	                           String unit) {
		require(stock != null && stock.getId() != null && stock.getId() > 0
						&& Objects.equals(stock.getTenantId(), arrange.getTenantId())
						&& Objects.equals(stock.getFactoryCode(), arrange.getFactoryCode())
						&& Objects.equals(stock.getMatCode(), code) && Objects.equals(stock.getMatUnit(), unit)
						&& (stock.getProduceTaskCode() == null || stock.getProduceTaskCode().isEmpty()
						|| Objects.equals(stock.getProduceTaskCode(), arrange.getProduceTaskCode())),
				"倒冲库存身份、原单位或工序归属不一致");
		amount(stock.getNum(), true, "原库存余额");
	}

	private BigDecimal amount(BigDecimal value, boolean zero, String label) {
		require(value != null, label + "未知");
		return codec.quantity(codec.amount(value), zero, label);
	}

	/**
	 * 原需求及其主替代候选的读取条件，不代表库存已被预留。
	 */
	public record Request(
			/** 冻结BOM中的需求物料编码。 */
			String requirementCode,
			/** 已按冻结主替代政策核定的实际物料候选。 */
			MesMaterialSubstituteResolver.Candidate candidate) {
	}

	/**
	 * 单次真实来源分配计划；实际出库仍须持锁复核原行及余额。
	 */
	public record Allocation(
			/** 本次精确选定的原库存主键。 */
			Long stockId,
			/** 本份额满足的冻结需求物料编码。 */
			String requirementCode,
			/** 需求当量的原BOM单位。 */
			String requirementUnit,
			/** 本份额实际扣减的物料编码。 */
			String materialCode,
			/** 原库存单位，也是实际量及余额单位。 */
			String materialUnit,
			/** 本份额的实际扣量，单位为materialUnit。 */
			BigDecimal actualQuantity,
			/** 本份额满足的需求当量，单位为requirementUnit。 */
			BigDecimal requirementQuantity,
			/** 需求当量换算为实际扣量的精确比例。 */
			BigDecimal ratio,
			/** 是否使用冻结政策中的替代物料。 */
			boolean substitute,
			/** 本次分配前的原库存余额。 */
			BigDecimal stockBefore,
			/** 本次分配后的原库存余额。 */
			BigDecimal stockAfter,
			/** 原库存预留号；保留原NULL与空文本。 */
			String reservationNo,
			/** 原库存需求绑定；不按当前需求覆盖原归属。 */
			String stockRequirementCode) {
	}
}
