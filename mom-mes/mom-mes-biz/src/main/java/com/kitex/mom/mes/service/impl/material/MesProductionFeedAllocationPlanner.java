package com.kitex.mom.mes.service.impl.material;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mdm.dto.MdmMaterialMeasureDTO;
import com.kitex.mom.mes.dto.ProcessProcedureDTO;
import com.kitex.mom.mes.entity.MesWarehouseFeedEntity;
import com.kitex.mom.mes.service.impl.MesPhysicalTransformCodec;
import com.kitex.mom.mes.util.MesMaterialSubstituteResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import static com.kitex.mom.mes.service.impl.MesPhysicalTransformCodec.require;

/**
 * 同一次生产的总投料分配：先对全部产出选料，再将真实来源分配到逐产出。
 * 此组件只计算，不写库存、不改变输入实体，也不代替调用者的权限、锁及原账证明。
 */
@Component
@RequiredArgsConstructor
public class MesProductionFeedAllocationPlanner {

    private static final int MAX_OUTPUTS = 1000;
    private static final int MAX_REQUIREMENTS = 1000;
    private static final int MAX_FEEDS = 5000;
    private static final int MAX_CONSUMPTIONS = 5000;
    private static final int MAX_ATTRIBUTIONS = 50000;

    private final MesPhysicalTransformCodec codec;

    /**
     * 使用已锁定的实际投料、原工艺需求/BOM和真实物料计量观察规划一次总耗料。
     * 禁混策略针对全部GOOD/NG总量，而不是分别规划后拼接；数量计算全程不舍入。
     */
    public Plan plan(Scope scope, List<OutputDemand> requestedOutputs,
                     List<ProcessProcedureDTO.ProcessProcedureMatDTO> requirements, String bomSnapshot,
                     List<MesWarehouseFeedEntity> lockedFeeds,
                     Function<String, MdmMaterialMeasureDTO> materialReader) {
        validateScope(scope);
        require(requestedOutputs != null && !requestedOutputs.isEmpty()
                && requestedOutputs.size() <= MAX_OUTPUTS, "实际正量产出须为1至1000条，不能截断分配");
        require(requirements != null && requirements.size() <= MAX_REQUIREMENTS,
                "原工序需求须明确且不能超过1000条");
        require(lockedFeeds != null && lockedFeeds.size() <= MAX_FEEDS, "实际投料不能超过5000条");
        require(materialReader != null, "缺少实际物料计量读取，不能按当前编码猜单位");

        List<OutputDemand> outputs = outputs(scope, requestedOutputs);
        BigDecimal processed = amount(outputs.stream().map(OutputDemand::quantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add), false, "本次全部产出加工总量");
        List<Cursor> feeds = feeds(scope, lockedFeeds);
        var consumptions = new ArrayList<FeedConsumption>();
        var attributions = new ArrayList<OutputAttribution>();
        var measurements = new HashMap<String, MdmMaterialMeasureDTO>();
        var requirementCodes = new HashSet<String>();

        for (var requirement : requirements) {
            require(requirement != null, "原工序存在空需求，不能跳过耗料");
            String requiredCode = codec.code(requirement.getMaterialCode(), "原需求物料", 100);
            String requiredUnit = codec.code(requirement.getUnit(), "原需求单位", 100);
            require(requirementCodes.add(requiredCode), "工序存在重复需求物料，须明确原需求而非猜合并");
            BigDecimal perUnit = amount(requirement.getQuantity(), false, "原工序单位需求量");
            BigDecimal totalRequired = amount(processed.multiply(perUnit), false, "本次总需求当量");
            Map<String, BigDecimal> outputRemaining = new LinkedHashMap<>();
            for (var output : outputs) {
                outputRemaining.put(output.outputKey(), amount(output.quantity().multiply(perUnit),
                        false, "逐产出原需求当量"));
            }

            var policy = MesMaterialSubstituteResolver.resolvePolicy(bomSnapshot, requiredCode);
            List<MesMaterialSubstituteResolver.Candidate> candidates = policy.candidates();
            if (!policy.allowMixed()) {
                candidates = candidates.stream().filter(candidate -> available(feeds, requiredCode, candidate)
                                .compareTo(totalRequired.multiply(candidate.ratio())) >= 0)
                        .findFirst().map(List::of).orElseThrow(() -> new CheckedException(
                                "原需求禁止混用，任一主料/替代料无法单独满足本次全部产出：" + requiredCode));
            }

            BigDecimal remaining = totalRequired;
            for (var candidate : candidates) {
                if (remaining.signum() == 0) {
                    break;
                }
                BigDecimal ratio = amount(candidate.ratio(), false, "原替代实际/需求比例");
                for (var cursor : feeds) {
                    if (remaining.signum() == 0) {
                        break;
                    }
                    var feed = cursor.feed;
                    if (!matches(feed, requiredCode, candidate) || cursor.remaining().signum() == 0) {
                        continue;
                    }
                    require(candidate.substitute()
                                    ? Boolean.TRUE.equals(feed.getSubstituteFlag())
                                      && feed.getSubstituteRatio() != null && feed.getSubstituteRatio().compareTo(ratio) == 0
                                    : !Boolean.TRUE.equals(feed.getSubstituteFlag()) && ratio.compareTo(BigDecimal.ONE) == 0,
                            "原投料冻结替代比例与本次原BOM策略不同，不能重新解释已投物料");
                    var measure = measurements.computeIfAbsent(feed.getMatCode(), materialReader);
                    validateMeasurement(scope, feed, measure, requiredUnit, candidate.substitute());

                    BigDecimal actual = amount(cursor.remaining().min(amount(remaining.multiply(ratio),
                            false, "本次实际需求耗量")), false, "实际投料份额");
                    BigDecimal satisfied = divide(actual, ratio);
                    BigDecimal before = cursor.used;
                    BigDecimal after = amount(before.add(actual), false, "投料耗用后值");
                    var consumption = new FeedConsumption(feed.getId(), requiredCode, requiredUnit,
                            feed.getMatCode(), feed.getMatUnit(), actual, satisfied, ratio, candidate.substitute(),
                            cursor.total, before, after, codec.id(measure.getMaterialId()), measure.getUpdatedAt());
                    consumptions.add(consumption);
                    require(consumptions.size() <= MAX_CONSUMPTIONS, "实际耗用行超过5000条，不能截断登记");
                    attribute(outputs, outputRemaining, consumption, attributions);
                    cursor.used = after;
                    remaining = amount(remaining.subtract(satisfied), true, "剩余需求当量");
                }
            }
            require(remaining.signum() == 0, "本次全部产出实际投料不足：" + requiredCode);
            require(outputRemaining.values().stream().allMatch(value -> value.signum() == 0),
                    "逐产出需求分配未完整覆盖，不能只证明总耗量");
        }
        return new Plan(scope, processed, List.copyOf(outputs), List.copyOf(consumptions), List.copyOf(attributions));
    }

    /** 每条实际投料份额只分配一次；每个产出按自己的原需求取量，不按总产量比例舍入。 */
    private void attribute(List<OutputDemand> outputs, Map<String, BigDecimal> outputRemaining,
                           FeedConsumption consumption, List<OutputAttribution> result) {
        BigDecimal requirementLeft = consumption.requirementQuantity();
        BigDecimal actualAllocated = BigDecimal.ZERO;
        for (var output : outputs) {
            if (requirementLeft.signum() == 0) {
                break;
            }
            BigDecimal outputLeft = outputRemaining.get(output.outputKey());
            BigDecimal portion = outputLeft.min(requirementLeft);
            if (portion.signum() == 0) {
                continue;
            }
            portion = amount(portion, false, "逐产出需求份额");
            BigDecimal actual = amount(portion.multiply(consumption.substituteRatio()), false, "逐产出实际耗量");
            result.add(new OutputAttribution(output.outputKey(), output.kind(), consumption.feedId(),
                    consumption.requirementMatCode(), consumption.requirementUnit(),
                    consumption.matCode(), consumption.matUnit(), actual, portion));
            require(result.size() <= MAX_ATTRIBUTIONS, "逐产出分配超过50000条，不能截断登记");
            outputRemaining.put(output.outputKey(), amount(outputLeft.subtract(portion), true, "逐产出剩余需求"));
            requirementLeft = amount(requirementLeft.subtract(portion), true, "原投料剩余分配当量");
            actualAllocated = amount(actualAllocated.add(actual), false, "原投料逐产出实际份额合计");
        }
        require(requirementLeft.signum() == 0 && actualAllocated.compareTo(consumption.consumeQuantity()) == 0,
                "逐产出实际耗量与原投料份额不守恒，不能重复认领同一实际耗量");
    }

    private List<OutputDemand> outputs(Scope scope, List<OutputDemand> requested) {
        var result = new ArrayList<OutputDemand>();
        Set<String> keys = new HashSet<>();
        for (var output : requested) {
            require(output != null && output.kind() != null, "产出必须明确GOOD或NG，不由数量猜类型");
            String key = codec.code(output.outputKey(), "实际产出key", 100);
            require(keys.add(key), "产出key重复，不能合并两份不同的原生产事实");
            require(Objects.equals(output.productionUnit(), scope.productionUnit()),
                    "本次产出原单位不一致，不能隐式换算后合计加工量");
            result.add(new OutputDemand(key, output.kind(), amount(output.quantity(), false, "实际产出数量"),
                    output.productionUnit()));
        }
        result.sort(Comparator.comparing(OutputDemand::outputKey));
        return result;
    }

    /** 只更新独立游标，不能提前修改实体usedNum，让后续必要CAS拿不到真实before。 */
    private List<Cursor> feeds(Scope scope, List<MesWarehouseFeedEntity> locked) {
        var result = new ArrayList<Cursor>();
        var ids = new HashSet<Long>();
        LocalDate today = LocalDate.now();
        for (var feed : locked) {
            require(feed != null && feed.getId() != null && feed.getId() > 0 && ids.add(feed.getId()),
                    "投料主键缺失或重复，不能两次使用同一来源余额");
            require(Objects.equals(feed.getTenantId(), scope.tenantId())
                    && Objects.equals(feed.getFactoryCode(), scope.factoryCode())
                    && Objects.equals(feed.getTaskCode(), scope.taskCode())
                    && Objects.equals(feed.getProduceTaskCode(), scope.produceTaskCode())
                    && Objects.equals(feed.getArrangeCode(), scope.arrangeCode())
                    && Boolean.FALSE.equals(feed.getIsLock())
                    && (feed.getMatValidEndDate() == null || !feed.getMatValidEndDate().isBefore(today)),
                    "投料不是本次实际组织/原工单工序派工的可用来源");
            BigDecimal total = amount(feed.getFeedNum(), true, "原实际投料总量");
            BigDecimal used = amount(Objects.requireNonNullElse(feed.getUsedNum(), BigDecimal.ZERO), true, "原投料已用量");
            require(used.compareTo(total) <= 0, "原投料已用量超过总量，不能继续分配");
            result.add(new Cursor(feed, total, used));
        }
        result.sort(Comparator.comparing((Cursor cursor) -> cursor.feed.getFeedTime(),
                Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(cursor -> cursor.feed.getId()));
        return result;
    }

    private void validateScope(Scope scope) {
        require(scope != null && scope.tenantId() != null && scope.tenantId() > 0, "缺少真实投料分配组织");
        codec.code(scope.factoryCode(), "实际工厂", 100);
        codec.code(scope.taskCode(), "原工单", 100);
        codec.code(scope.produceTaskCode(), "原工序任务", 100);
        codec.code(scope.arrangeCode(), "原派工", 100);
        codec.code(scope.productionUnit(), "原生产基本单位", 100);
    }

    private void validateMeasurement(Scope scope, MesWarehouseFeedEntity feed, MdmMaterialMeasureDTO material,
                                     String requirementUnit, boolean substitute) {
        require(material != null && Boolean.TRUE.equals(material.getEnabled())
                && Objects.equals(material.getTenantId(), scope.tenantId().toString())
                && Objects.equals(material.getFactoryCode(), scope.factoryCode())
                && Objects.equals(material.getMaterialCode(), feed.getMatCode())
                && Objects.equals(material.getBasicUnit(), feed.getMatUnit()),
                "投料原单位与实际启用物料不一致，禁止隐式换算");
        codec.code(feed.getMatUnit(), "实际投料原单位", 100);
        codec.id(material.getMaterialId());
        require(substitute || Objects.equals(feed.getMatUnit(), requirementUnit),
                "主料原投料单位与工序需求单位不同，须明确工程换算");
    }

    private boolean matches(MesWarehouseFeedEntity feed, String required,
                            MesMaterialSubstituteResolver.Candidate candidate) {
        return Objects.equals(feed.getMatCode(), candidate.actualMatCode())
                && (feed.getRequirementMatCode() != null && !feed.getRequirementMatCode().isBlank()
                    ? Objects.equals(feed.getRequirementMatCode(), required)
                    : !candidate.substitute() && Objects.equals(feed.getMatCode(), required));
    }

    private BigDecimal available(List<Cursor> feeds, String required,
                                 MesMaterialSubstituteResolver.Candidate candidate) {
        return feeds.stream().filter(cursor -> matches(cursor.feed, required, candidate))
                .map(Cursor::remaining).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal divide(BigDecimal actual, BigDecimal ratio) {
        try {
            return amount(actual.divide(ratio), false, "本次投料满足需求当量");
        } catch (ArithmeticException exception) {
            throw new CheckedException("投料替代分配无法精确表示，禁止舍入耗用");
        }
    }

    private BigDecimal amount(BigDecimal value, boolean allowZero, String name) {
        require(value != null, name + "未知");
        return codec.quantity(codec.amount(value), allowZero, name);
    }

    /** 本次产出种类，不与报工对象模式或触发来源混为同一枚举。 */
    public enum OutputKind {
        GOOD,
        NG
    }

    /** 实际事务调用者的原组织/工单/工序/派工/生产单位，不从客户端权限声明构造。 */
    public record Scope(Long tenantId, String factoryCode, String taskCode, String produceTaskCode,
                        String arrangeCode, String productionUnit) {
    }

    /** 明确的一份正量产出；key由上层真实生产协议与原来源建立，零量产出不得伪建。 */
    public record OutputDemand(String outputKey, OutputKind kind, BigDecimal quantity, String productionUnit) {
    }

    /** 实际feed耗量/需求当量、冻结比例及本次真实before/after，尚未持久化。 */
    public record FeedConsumption(Long feedId, String requirementMatCode, String requirementUnit,
                                  String matCode, String matUnit, BigDecimal consumeQuantity,
                                  BigDecimal requirementQuantity, BigDecimal substituteRatio, boolean substitute,
                                  BigDecimal feedQuantity, BigDecimal usedBefore, BigDecimal usedAfter,
                                  Long materialId, String materialUpdatedAt) {
    }

    /** 每份产出认领的真实feed份额；实际单位和原需求单位分别保存，不能用生产单位代替。 */
    public record OutputAttribution(String outputKey, OutputKind kind, Long feedId, String requirementMatCode,
                                    String requirementUnit, String matCode, String matUnit,
                                    BigDecimal consumeQuantity, BigDecimal requirementQuantity) {
    }

    /** 不可变计算结果；没有APPLIED、库存或质量批准含义，调用者仍须保存并证明完整真实事务。 */
    public record Plan(Scope scope, BigDecimal processedQuantity, List<OutputDemand> outputs,
                       List<FeedConsumption> consumptions, List<OutputAttribution> attributions) {
    }

    /** 与输入实体分离的本次分配余额，后续需求共用而不能重复分配。 */
    private static final class Cursor {
        private final MesWarehouseFeedEntity feed;
        private final BigDecimal total;
        private BigDecimal used;

        private Cursor(MesWarehouseFeedEntity feed, BigDecimal total, BigDecimal used) {
            this.feed = feed;
            this.total = total;
            this.used = used;
        }

        private BigDecimal remaining() {
            return total.subtract(used);
        }
    }
}
