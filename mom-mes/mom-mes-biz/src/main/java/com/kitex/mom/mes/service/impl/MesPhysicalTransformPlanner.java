package com.kitex.mom.mes.service.impl;

import com.kitex.mom.mes.dto.MesPhysicalTransformDTO;
import com.kitex.mom.mes.enums.ReportModeEnum;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.kitex.mom.mes.service.impl.MesPhysicalTransformCodec.require;

/**
 * 拆分、汇合、转批与位置拆件共用的精确当量分配校验。
 * 只计算既有来源到明确输出的守恒，不记产、不生成条码，也不授予工程/质量资格。
 */
@Component
@RequiredArgsConstructor
public class MesPhysicalTransformPlanner {

    private final MesPhysicalTransformCodec codec;

    /**
     * 核对每源观察指纹、源余量、每输出分配和每位置；调用方须提供完整同范围真实源集合。
     * 计算结果不是执行许可，写事务必须在取得规范锁后重新调用并验证真实冻结工艺。
     */
    public MesPhysicalTransformDTO.Calculation calculate(MesPhysicalTransformDTO.Plan plan,
                                                         MesPhysicalTransformDTO.Policy policy,
                                                         List<MesPhysicalTransformDTO.SourceFact> sources) {
        require(plan != null && policy != null && sources != null && plan.getInputs() != null
                && plan.getOutputs() != null && plan.getAllocations() != null, "投入产出完整正文和来源不能为空");
        var inputs = plan.getInputs();
        var outputs = plan.getOutputs();
        require(!inputs.isEmpty() && inputs.size() <= MesPhysicalTransformCodec.MAX_SOURCES
                && !outputs.isEmpty() && outputs.size() <= MesPhysicalTransformCodec.MAX_OUTPUTS
                && !plan.getAllocations().isEmpty()
                && plan.getAllocations().size() <= MesPhysicalTransformCodec.MAX_ALLOCATIONS, "投入产出或分配全集超界");
        require(sources.size() == inputs.size() && sources.stream().map(MesPhysicalTransformDTO.SourceFact::getStockId)
                .distinct().count() == sources.size(), "真实来源全集不完整或重复");
        Map<String, MesPhysicalTransformDTO.SourceFact> bySource = sources.stream()
                .collect(Collectors.toMap(MesPhysicalTransformDTO.SourceFact::getStockId, Function.identity()));
        BigDecimal factor = codec.quantity(policy.getEquivalenceFactor(), false, "当量系数");
        Map<String, BigDecimal> equivalents = new TreeMap<>();
        Map<String, String> remainders = new TreeMap<>();
        Set<String> sourceIdentifiers = new HashSet<>();
        String material = null;
        BigDecimal inputTotal = BigDecimal.ZERO;
        boolean depanel = "DEPANEL".equals(policy.getOperationType());
        for (var input : inputs) {
            require(input != null, "投入行不能为空");
            codec.id(input.getStockId());
            require(!equivalents.containsKey(input.getStockId()), "精确投入库存不能重复");
            var source = bySource.get(input.getStockId());
            require(source != null && Boolean.TRUE.equals(source.getSourceChecksPassed()), "来源不存在或已知来源检查未通过");
            require(input.getExpectedFingerprint() != null && input.getExpectedFingerprint().matches("[0-9a-f]{64}")
                    && input.getExpectedFingerprint().equals(source.getFingerprint()), "来源完整事实已变化，请重新读取，不改用旧观察量");
            require(policy.getInputUnit().equals(source.getUnit()), "政策投入单位与真实原库存单位不一致");
            require(source.getMaterialCode() != null && !source.getMaterialCode().isBlank(), "原来源物料未明确");
            if (material == null) material = source.getMaterialCode();
            require(material.equals(source.getMaterialCode()), "不同来源物料不能按同一物料当量自动混合，须冻结真实工程转换依据");
            assertInputObject(policy.getInputObjectType(), source);
            BigDecimal available = codec.quantity(source.getQuantity(), false, "真实来源量");
            BigDecimal used = codec.quantity(input.getQuantity(), false, "投入量");
            require(used.compareTo(available) <= 0, "投入量超过该精确来源实际库存");
            require(policy.getPartialInputAllowed() || used.compareTo(available) == 0, "政策不允许部分投入，须使用完整源量");
            if ("SN".equals(policy.getInputObjectType()))
                require(available.compareTo(BigDecimal.ONE) == 0 && used.compareTo(BigDecimal.ONE) == 0, "单件SN须完整1件投入");
            BigDecimal equivalent = used.multiply(factor);
            codec.amount(equivalent);
            if (depanel) {
                requireWholeSnEquivalent(available.multiply(factor), "拆板完整来源当量");
                requireWholeSnEquivalent(equivalent, "拆板投入当量");
                requireWholeSnEquivalent(available.subtract(used).multiply(factor), "拆板剩余当量");
            }
            equivalents.put(input.getStockId(), equivalent);
            remainders.put(input.getStockId(), codec.amount(available.subtract(used)));
            inputTotal = inputTotal.add(used);
            if (source.getIdentifier() != null && !source.getIdentifier().isBlank()) sourceIdentifiers.add(source.getIdentifier());
        }
        assertShape(policy.getOperationType(), inputs.size(), outputs.size());

        Map<String, BigDecimal> outputQuantities = new TreeMap<>();
        Set<String> outputIdentifiers = new HashSet<>();
        Set<String> positions = new HashSet<>();
        Set<String> missingKeys = new HashSet<>();
        BigDecimal good = BigDecimal.ZERO;
        BigDecimal scrap = BigDecimal.ZERO;
        for (var output : outputs) {
            require(output != null, "输出行不能为空");
            codec.code(output.getOutputKey(), "输出键", 100);
            require(!outputQuantities.containsKey(output.getOutputKey()), "输出键不能重复");
            require(Objects.equals(policy.getOutputObjectType(), output.getObjectType()), "输出对象与政策不一致");
            require(Set.of("GOOD", "SCRAP", "MISSING").contains(Objects.toString(output.getDisposition(), "")), "输出处置未明确");
            boolean missing = "MISSING".equals(output.getDisposition());
            BigDecimal quantity = codec.quantity(output.getQuantity(), missing, "输出量");
            if (missing) {
                require(depanel && quantity.signum() == 0 && "".equals(output.getIdentifier()), "缺位只能是拆板中的零量空标识，不产生虚假实物");
                missingKeys.add(output.getOutputKey());
            } else {
                codec.code(output.getIdentifier(), "产出实物标识", 100);
                require(outputIdentifiers.add(output.getIdentifier()) && !sourceIdentifiers.contains(output.getIdentifier()),
                        "输出实物标识重复或仍是投入母件标识");
                require(!"SN".equals(output.getObjectType()) || quantity.compareTo(BigDecimal.ONE) == 0, "单件SN输出数量须为1");
                if ("GOOD".equals(output.getDisposition())) good = good.add(quantity);
                else scrap = scrap.add(quantity);
            }
            if (depanel) {
                codec.code(output.getPositionCode(), "板位", 100);
                require(policy.getPositionCodes().contains(output.getPositionCode()) && positions.add(output.getPositionCode()),
                        "板位重复或不属于政策完整位置集合");
            } else require("".equals(output.getPositionCode()), "非拆板动作不能伪造板位事实");
            outputQuantities.put(output.getOutputKey(), quantity);
        }
        require(!depanel || policy.getPartialInputAllowed() || positions.size() == policy.getPositionCodes().size(),
                "整拆必须明确全部位置的合格、报废或缺位，不能截断");
        if (depanel) {
            BigDecimal remaining = remainders.values().stream().map(BigDecimal::new).reduce(BigDecimal.ZERO, BigDecimal::add);
            int remainingPositionCount = policy.getPositionCodes().size() - positions.size();
            require(remainingPositionCount == 0 ? remaining.signum() == 0 : remaining.signum() > 0,
                    "板位已全处理时不能仍留母件余量，未选板位也不能在母件已耗尽后悬空");
            require(remainingPositionCount == 0 || remaining.multiply(factor).compareTo(BigDecimal.valueOf(remainingPositionCount)) <= 0,
                    "母件余量当量超过未选逐SN位置数量，不能遗漏实物");
        }

        Set<String> edges = new HashSet<>();
        Map<String, BigDecimal> allocatedInputs = new HashMap<>();
        Map<String, BigDecimal> allocatedOutputs = new HashMap<>();
        for (var allocation : plan.getAllocations()) {
            require(allocation != null && equivalents.containsKey(allocation.getStockId())
                    && outputQuantities.containsKey(allocation.getOutputKey()) && !missingKeys.contains(allocation.getOutputKey()),
                    "分配引用范围外投入/输出或零量缺位");
            require(edges.add(codec.snapshot(List.of(allocation.getStockId(), allocation.getOutputKey()))), "投入到输出的当量边重复");
            BigDecimal quantity = codec.quantity(allocation.getQuantity(), false, "分配当量");
            allocatedInputs.merge(allocation.getStockId(), quantity, BigDecimal::add);
            allocatedOutputs.merge(allocation.getOutputKey(), quantity, BigDecimal::add);
        }
        equivalents.forEach((id, quantity) -> require(quantity.compareTo(allocatedInputs.getOrDefault(id, BigDecimal.ZERO)) == 0,
                "源库存" + id + "投入当量与完整分配不守恒"));
        outputQuantities.forEach((key, quantity) -> require(quantity.compareTo(allocatedOutputs.getOrDefault(key, BigDecimal.ZERO)) == 0,
                "输出" + key + "数量与全部真实来源分配不守恒"));
        BigDecimal equivalentTotal = equivalents.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        require(equivalentTotal.compareTo(good.add(scrap)) == 0, "投入与合格/报废总当量不守恒");

        var result = new MesPhysicalTransformDTO.Calculation();
        result.setOperationType(policy.getOperationType());
        result.setInputUnit(policy.getInputUnit());
        result.setOutputUnit(policy.getOutputUnit());
        result.setInputQuantity(codec.amount(inputTotal));
        result.setInputEquivalent(codec.amount(equivalentTotal));
        result.setGoodQuantity(codec.amount(good));
        result.setScrapQuantity(codec.amount(scrap));
        result.setMissingPositionCount(Integer.toString(missingKeys.size()));
        result.setSourceRemainders(remainders);
        Map<String, String> equivalentTexts = new TreeMap<>();
        equivalents.forEach((id, quantity) -> equivalentTexts.put(id, codec.amount(quantity)));
        result.setSourceEquivalents(equivalentTexts);
        result.setRemainingPositions(policy.getPositionCodes().stream().filter(position -> !positions.contains(position)).toList());
        return result;
    }

    /** 每个板位对应完整单件；来源、投入和余量均不能留下分数SN或依赖舍入。 */
    private void requireWholeSnEquivalent(BigDecimal quantity, String name) {
        codec.amount(quantity);
        require(quantity.stripTrailingZeros().scale() <= 0, name + "必须是整数个SN，不能用分数实物当量拆板");
    }

    private void assertShape(String action, int inputCount, int outputCount) {
        boolean valid = switch (action) {
            case "SPLIT" -> inputCount == 1 && outputCount >= 2;
            case "MERGE" -> inputCount >= 2 && outputCount == 1;
            case "REBATCH" -> inputCount == 1 && outputCount == 1;
            case "DEPANEL" -> inputCount == 1;
            default -> false;
        };
        require(valid, "动作的投入/产出数量结构不正确，不能混用拆分汇合语义");
    }

    private void assertInputObject(String objectType, MesPhysicalTransformDTO.SourceFact source) {
        ReportModeEnum mode;
        try {
            mode = ReportModeEnum.fromCode(source.getReportMode());
        } catch (RuntimeException exception) {
            throw new com.kitex.common.core.exception.CheckedException("原报工对象模式未确认，不能用当前模板反推实物");
        }
        boolean matches = switch (mode) {
            case QUANTITY -> "BATCH".equals(objectType);
            case ONE_CODE -> "SN".equals(objectType);
            case TRANSFER_CARD -> "TRAVELER".equals(objectType) || "PANEL".equals(objectType);
            case PACKAGE_ONE_CODE, PACKAGE_QUANTITY -> false;
        };
        require(matches, "投入对象与原报工不符；包装须沿原拆箱/重组链，不能伪装成普通拆件");
        require(mode == ReportModeEnum.QUANTITY || source.getIdentifier() != null && !source.getIdentifier().isBlank(),
                "原单件/卡标识缺失，不能补造投入身份");
    }
}
