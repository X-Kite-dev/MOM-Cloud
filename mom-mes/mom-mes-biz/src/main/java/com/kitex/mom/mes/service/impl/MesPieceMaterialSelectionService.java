package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.dto.MesPieceMaterialSelectionDTO.*;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.enums.ReportModeEnum;
import com.kitex.mom.mes.mapper.*;
import com.kitex.mom.mes.service.impl.material.MesProductionFeedAllocationPlanner;
import com.kitex.mom.mes.util.MesTaskUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

import static com.kitex.mom.mes.service.impl.MesPhysicalTransformCodec.require;

/**
 * 只读选料与实际GOOD事务共用来源核定和精确规划，不提供独立扣料入口。
 */
@Service
@RequiredArgsConstructor
public class MesPieceMaterialSelectionService {
	private final MesActualFactoryAccessService access;
	private final MesTaskArrangeMapper arranges;
	private final MesTaskMapper tasks;
	private final MesWarehouseFeedMapper feeds;
	private final MesStartMaterialBindingReader bindings;
	private final MesStartMaterialSourceVerifier sources;
	private final MesMaterialConsumptionModeResolver modes;
	private final MesProductionFeedAllocationPlanner planner;
	private final MesPhysicalTransformCodec physical;
	private final MesPieceMaterialSelectionMapper deployment;
	private final MesPieceMaterialSelectionCodec codec = new MesPieceMaterialSelectionCodec();

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public Preparation prepare(String arrangeCode, String identifier) {
		var context = context(arrangeCode, identifier);
		var requirements = MesTaskUtils.getCurrentProcess(context.task().getProcessRouteSnapshot(),
						context.arrange().getRouteCode(), context.arrange().getSourceRouteNodeCode())
				.getMdmProcessProcedureMatList();
		requirements = MesMaterialConsumptionModeResolver.partition(modes.resolve(context.arrange()), requirements,
				MesMaterialConsumptionModeResolver.Mode.FEED);
		require(requirements != null && !requirements.isEmpty() && requirements.size() <= 100,
				"当前工序没有明确用料需求，或需求超过单件选料范围");
		var required = requirements.stream().map(row -> new Requirement(row.getMaterialCode(), row.getUnit(),
				physical.amount(row.getQuantity()))).toList();
		var candidates = new ArrayList<Source>();
		var requiredCodes = new HashSet<String>();
		required.forEach(row -> requiredCodes.add(row.materialCode()));
		var pieceIds = issuedPieces(context.arrange(), context.feeds().stream().map(MesWarehouseFeedEntity::getId).toList());
		int unavailable = 0;
		for (var feed : context.feeds()) {
			if (!requiredCodes.contains(feed.getRequirementMatCode())) {
				continue;
			}
			try {
				boolean wholePiece = pieceIds.contains(feed.getId());
				if (wholePiece) {
					requireUnusedPiece(feed);
				}
				var proof = sources.source(context.arrange().getTenantId(), context.arrange().getFactoryCode(),
						context.arrange(), context.task(), feed, true);
				candidates.add(new Source(proof.feedId(), proof.birthId(), proof.birthFingerprint(),
						proof.requirementMaterialCode(),
						proof.actualMaterialCode(), proof.batchNo(), proof.barcode(), proof.unit(),
						physical.amount(feed.getFeedNum().subtract(feed.getUsedNum())), wholePiece));
			} catch (CheckedException rejected) {
				unavailable++;
			}
		}
		return new Preparation(arrangeCode, identifier, required, List.copyOf(candidates), unavailable);
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public Checked check(Input input) {
		require(input != null, "缺少本件用料核对输入");
		var context = context(input.getArrangeCode(), input.getIdentifier());
		String outputUnit = sources.measure(context.arrange().getTenantId(), context.arrange().getFactoryCode(),
				context.arrange().getMatCode()).getBasicUnit();
		plan(context.arrange(), context.task(), context.feeds(), input.getMaterialSelectionJson(), "PREVIEW",
				outputUnit);
		return new Checked(input.getArrangeCode(), input.getIdentifier(), input.getMaterialSelectionJson());
	}

	/**
	 * 调用方必须持原工单、派工及feed行锁；保持既有GOOD写账和CAS，来源核对不写库存。
	 */
	public MesProductionFeedAllocationPlanner.Plan plan(MesTaskArrangeEntity arrange, MesTaskEntity task,
	                                                    List<MesWarehouseFeedEntity> locked, String raw,
	                                                    String outputKey, String outputUnit) {
		assertScope(arrange, task);
		var selected = codec.decode(raw);
		var byId = new HashMap<Long, MesWarehouseFeedEntity>();
		locked.forEach(feed -> require(byId.put(feed.getId(), feed) == null, "实际来源ID重复"));
		var quantities = new LinkedHashMap<Long, BigDecimal>();
		var pieceIds = issuedPieces(arrange, selected.stream().map(MesPieceMaterialSelectionCodec.Selection::feedId).toList());
		for (var selection : selected) {
			var feed = byId.get(selection.feedId());
			require(feed != null, "所选来源已不可用或不属于当前派工许可集合");
			if (pieceIds.contains(feed.getId())) {
				requireUnusedPiece(feed);
				require(BigDecimal.ONE.compareTo(selection.quantity()) == 0, "原料 " + feed.getMatBarcode() + " 须整件用于本件产品");
			}
			var proof = sources.source(arrange.getTenantId(), arrange.getFactoryCode(), arrange, task, feed, true);
			require(Objects.equals(proof.birthId(), selection.birthId().toString())
							&& Objects.equals(proof.birthFingerprint(), selection.birthFingerprint()),
					"所选投料出生身份已变化，须重新核对");
			quantities.put(selection.feedId(), selection.quantity());
		}
		var requirements = MesTaskUtils.getCurrentProcess(task.getProcessRouteSnapshot(), arrange.getRouteCode(),
				arrange.getSourceRouteNodeCode()).getMdmProcessProcedureMatList();
		requirements = MesMaterialConsumptionModeResolver.partition(modes.resolve(arrange), requirements,
				MesMaterialConsumptionModeResolver.Mode.FEED);
		return planner.planSelected(
				new MesProductionFeedAllocationPlanner.Scope(arrange.getTenantId(), arrange.getFactoryCode(),
						task.getCode(), arrange.getProduceTaskCode(), arrange.getCode(), outputUnit),
				List.of(new MesProductionFeedAllocationPlanner.OutputDemand(outputKey,
						MesProductionFeedAllocationPlanner.OutputKind.GOOD, BigDecimal.ONE, outputUnit)),
				requirements, task.getBomSnapshot(), locked,
				code -> sources.measure(arrange.getTenantId(), arrange.getFactoryCode(), code), quantities);
	}

	/** 无候选时直接返回；整件身份只取本组织该投料的原签收。 */
	private Set<Long> issuedPieces(MesTaskArrangeEntity arrange, List<Long> feedIds) {
		return feedIds.isEmpty() ? Set.of() : deployment.issuedPieceIds(arrange.getTenantId(), arrange.getFactoryCode(), feedIds);
	}

	/** 整件须保有原码和完整的可用余额，半耗的历史原件不能显示为可选的整件。 */
	private void requireUnusedPiece(MesWarehouseFeedEntity feed) {
		require(feed.getMatBarcode() != null && !feed.getMatBarcode().isBlank()
						&& feed.getFeedNum() != null && BigDecimal.ONE.compareTo(feed.getFeedNum()) == 0
						&& feed.getUsedNum() != null && feed.getUsedNum().signum() == 0,
				"原料单件条码或可用量与原签收不一致，请核对投料 " + feed.getId());
	}

	private Context context(String arrangeCode, String identifier) {
		String factory = access.require("read");
		physical.code(arrangeCode, "实际派工", 100);
		physical.code(identifier, "本件产品标识", 200);
		var actual = arranges.selectList(new LambdaQueryWrapper<MesTaskArrangeEntity>()
				.eq(MesTaskArrangeEntity::getTenantId, TenantContextHolder.getTenantId())
				.eq(MesTaskArrangeEntity::getFactoryCode, factory)
				.eq(MesTaskArrangeEntity::getCode, arrangeCode).last("LIMIT 2"));
		require(actual.size() == 1, "当前工厂未找到唯一派工任务");
		var arrange = actual.get(0);
		var parents = tasks.selectList(new LambdaQueryWrapper<MesTaskEntity>()
				.eq(MesTaskEntity::getTenantId, arrange.getTenantId()).eq(MesTaskEntity::getFactoryCode, factory)
				.eq(MesTaskEntity::getCode, arrange.getTaskCode()).last("LIMIT 2"));
		require(parents.size() == 1, "实际派工父工单不唯一");
		assertScope(arrange, parents.get(0));
		var query = new LambdaQueryWrapper<MesWarehouseFeedEntity>()
				.eq(MesWarehouseFeedEntity::getTenantId, arrange.getTenantId())
				.eq(MesWarehouseFeedEntity::getFactoryCode, factory)
				.eq(MesWarehouseFeedEntity::getTaskCode, arrange.getTaskCode())
				.eq(MesWarehouseFeedEntity::getProduceTaskCode, arrange.getProduceTaskCode())
				.eq(MesWarehouseFeedEntity::getArrangeCode, arrangeCode).orderByAsc(MesWarehouseFeedEntity::getId);
		bindings.constrain(arrange, query);
		var rows = feeds.selectList(query.last("LIMIT 5001"));
		require(rows.size() <= 5000, "投料来源超过完整核对上限，不能截断选择");
		return new Context(arrange, parents.get(0), rows.stream().filter(sources::eligible).toList());
	}

	private void assertScope(MesTaskArrangeEntity arrange, MesTaskEntity task) {
		require(Boolean.TRUE.equals(deployment.ready()), "本件用料缺少原号保护或整件耗用保护，请核对选料升级和 V4 第 423 节");
		require("PRODUCT".equals(arrange.getStatus()) && !"REWORK".equals(task.getResourceType())
						&& ReportModeEnum.fromCodes(arrange.getReportMode(), arrange.getRouteType()) == ReportModeEnum.ONE_CODE,
				"请在普通单件任务开工后选择本件用料");
		require(modes.resolve(arrange) != MesMaterialConsumptionModeResolver.Mode.BACKFLUSH,
				"当前任务按倒冲方式耗料，无需选择投料来源");
	}

	private record Context(MesTaskArrangeEntity arrange, MesTaskEntity task, List<MesWarehouseFeedEntity> feeds) {
	}
}
