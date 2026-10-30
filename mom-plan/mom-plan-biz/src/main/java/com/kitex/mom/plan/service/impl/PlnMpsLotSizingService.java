package com.kitex.mom.plan.service.impl;

import cn.hutool.core.util.StrUtil;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mdm.dto.MdmProductionLotPolicyCatalogDTO;
import com.kitex.mom.mdm.dto.MdmProductionLotPolicyCatalogQueryDTO;
import com.kitex.mom.mdm.entity.MdmProductionLotPolicyEntity;
import com.kitex.mom.mdm.feign.RemoteMdmProductionLotPolicyService;
import com.kitex.mom.plan.entity.PlnMpsLineEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;

/**
 * MPS生产批量策略解析与数量约束。
 *
 * <p>没有配置正式策略的产品保持原有精确需求量逻辑；一旦存在ACTIVE策略，候选桶就必须
 * 同时满足最小、最大和整批倍数。MDM不可用、目录冲突或策略内容不合法时故障关闭。</p>
 */
@Service
@RequiredArgsConstructor
public class PlnMpsLotSizingService {

    private static final int SCALE = 6;
    private static final int MAX_BATCHES = 500;
    private final RemoteMdmProductionLotPolicyService policyService;

    public Catalog load(String factoryCode, Collection<String> productCodes) {
        List<String> products = productCodes == null ? List.of() : productCodes.stream()
                .filter(StrUtil::isNotBlank).map(String::trim).distinct().sorted().toList();
        if (StrUtil.isBlank(factoryCode) || products.isEmpty()) {
            throw new CheckedException("生产批量策略查询缺少工厂或产品范围");
        }
        if (products.size() > MAX_BATCHES) {
            throw new CheckedException("生产批量策略单次查询最多500个产品");
        }
        LocalDateTime effective = LocalDateTime.now();
        R<MdmProductionLotPolicyCatalogDTO> response;
        try {
            response = policyService.catalog(new MdmProductionLotPolicyCatalogQueryDTO()
                    .setFactoryCode(factoryCode).setProductCodes(products).setEffectiveTime(effective));
        } catch (Exception ex) {
            throw new CheckedException("MDM生产批量策略服务不可用，MPS已故障关闭");
        }
        if (response == null || !response.isOk() || response.getData() == null
                || response.getData().getPolicyByProduct() == null
                || StrUtil.isBlank(response.getData().getEvidenceFingerprint())) {
            throw new CheckedException("MDM生产批量策略目录不可核验，MPS已故障关闭");
        }
        Map<String, MdmProductionLotPolicyEntity> policies = new LinkedHashMap<>(
                response.getData().getPolicyByProduct());
        List<String> missing = response.getData().getProductsWithoutPolicy();
        // 缺失集合也是权威事实，不能把返回半份目录当作“其余产品未配置”后绕过正式约束。
        requireCompleteCatalog(factoryCode, products, policies, missing, effective);
        return new Catalog(Collections.unmodifiableMap(policies), List.copyOf(missing),
                response.getData().getEvidenceFingerprint());
    }

    /**
     * 正式提交和复核均调用，防止页面绕过和策略换版后的陈旧批准。
     */
    public Catalog validate(String factoryCode, List<PlnMpsLineEntity> lines) {
        Catalog catalog = load(factoryCode, lines.stream().map(PlnMpsLineEntity::getProductCode).toList());
        List<String> violations = new ArrayList<>();
        for (PlnMpsLineEntity line : lines) {
            MdmProductionLotPolicyEntity policy = catalog.policies.get(line.getProductCode());
            if (policy == null) continue;
            String violation = violation(line.getPlanQuantity(), policy);
            if (violation != null) {
                violations.add(line.getDemandNo() + "#" + line.getBucketNo() + "：" + violation);
            }
        }
        if (!violations.isEmpty()) {
            throw new CheckedException("MPS生产批量不符合正式策略：" + String.join("；", violations));
        }
        return catalog;
    }

    public String violation(BigDecimal quantity, MdmProductionLotPolicyEntity policy) {
        if (quantity == null || quantity.signum() <= 0) return "批量必须大于0";
        if (quantity.compareTo(policy.getMinimumBatchQuantity()) < 0) {
            return "数量" + text(quantity) + "小于最小批量" + text(policy.getMinimumBatchQuantity());
        }
        if (quantity.compareTo(policy.getMaximumBatchQuantity()) > 0) {
            return "数量" + text(quantity) + "大于最大批量" + text(policy.getMaximumBatchQuantity());
        }
        if (quantity.remainder(policy.getBatchMultiple()).compareTo(BigDecimal.ZERO) != 0) {
            return "数量" + text(quantity) + "不是整批倍数" + text(policy.getBatchMultiple()) + "的整数倍";
        }
        return null;
    }

    /**
     * 在日容量上限内选取一个合法批量，并保证余量存在完整的合法批次数量解。
     *
     * <p>只检查“余量不少于最小批量”不够。例如最小60、最大70，余量90仍然不可拆分。
     * 本方法按后续批次数求出本次批量的可行区间，优先靠近经济批量，再以较大批量打破平局。</p>
     */
    public BigDecimal allocatable(BigDecimal quantityLeft, BigDecimal capacityQuantity,
                                  MdmProductionLotPolicyEntity policy) {
        if (quantityLeft == null || capacityQuantity == null
                || quantityLeft.signum() <= 0 || capacityQuantity.signum() <= 0) {
            return BigDecimal.ZERO.setScale(SCALE);
        }
        if (policy == null) {
            return quantityLeft.min(capacityQuantity).setScale(SCALE, RoundingMode.DOWN);
        }
        requireValidPolicy(policy);
        if (quantityLeft.compareTo(policy.getMinimumBatchQuantity()) < 0
                || quantityLeft.remainder(policy.getBatchMultiple()).compareTo(BigDecimal.ZERO) != 0) {
            return BigDecimal.ZERO.setScale(SCALE);
        }
        BigDecimal multiple = policy.getBatchMultiple();
        BigInteger total = units(quantityLeft, multiple);
        BigInteger minimum = units(policy.getMinimumBatchQuantity(), multiple);
        BigInteger maximum = units(policy.getMaximumBatchQuantity(), multiple);
        BigInteger economic = units(policy.getEconomicBatchQuantity(), multiple);
        BigInteger ceiling = capacityQuantity.divide(multiple, 0, RoundingMode.DOWN)
                .toBigIntegerExact().min(maximum).min(total);
        if (ceiling.compareTo(minimum) < 0) {
            return BigDecimal.ZERO.setScale(SCALE);
        }
        if (total.compareTo(ceiling) <= 0) {
            return quantityLeft.setScale(SCALE, RoundingMode.UNNECESSARY);
        }
        BigInteger firstCount = ceilDivide(total.subtract(ceiling), maximum);
        BigInteger lastCount = total.subtract(minimum).divide(minimum).min(BigInteger.valueOf(MAX_BATCHES - 1));
        if (firstCount.compareTo(BigInteger.valueOf(MAX_BATCHES - 1)) > 0) {
            throw new CheckedException("需求至少需要超过500个合法批次，请缩小本次MPS编制范围");
        }
        BigInteger best = null;
        BigInteger bestDistance = null;
        for (int count = firstCount.intValueExact(); count <= lastCount.intValueExact(); count++) {
            BigInteger remainingBatches = BigInteger.valueOf(count);
            BigInteger lower = minimum.max(total.subtract(maximum.multiply(remainingBatches)));
            BigInteger upper = ceiling.min(total.subtract(minimum.multiply(remainingBatches)));
            if (lower.compareTo(upper) > 0) {
                continue;
            }
            BigInteger candidate = economic.max(lower).min(upper);
            BigInteger distance = candidate.subtract(economic).abs();
            if (best == null || distance.compareTo(bestDistance) < 0
                    || (distance.equals(bestDistance) && candidate.compareTo(best) > 0)) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best == null ? BigDecimal.ZERO.setScale(SCALE)
                : multiple.multiply(new BigDecimal(best)).setScale(SCALE, RoundingMode.UNNECESSARY);
    }

    /**
     * 将需求总量确定性拆成合法生产批次，不改变总量。
     *
     * <p>该方法不依赖RCCP，用于尚未启用粗能力控制的工厂先完成生产批量整形；
     * 总量小于最小批量或不满足整批倍数时没有守恒解，返回空集合交由上层明确阻断。</p>
     */
    public List<BigDecimal> split(BigDecimal totalQuantity, MdmProductionLotPolicyEntity policy) {
        requireValidPolicy(policy);
        if (totalQuantity == null || totalQuantity.compareTo(policy.getMinimumBatchQuantity()) < 0
                || totalQuantity.remainder(policy.getBatchMultiple()).signum() != 0) {
            return List.of();
        }
        BigDecimal multiple = policy.getBatchMultiple();
        BigInteger total = units(totalQuantity, multiple);
        BigInteger minimum = units(policy.getMinimumBatchQuantity(), multiple);
        BigInteger maximum = units(policy.getMaximumBatchQuantity(), multiple);
        BigInteger economic = units(policy.getEconomicBatchQuantity(), multiple);
        BigInteger least = ceilDivide(total, maximum);
        BigInteger most = total.divide(minimum);
        if (least.compareTo(most) > 0) {
            return List.of();
        }
        if (least.compareTo(BigInteger.valueOf(MAX_BATCHES)) > 0) {
            throw new CheckedException("需求至少需要" + least + "个合法批次，超过单个MPS版本500桶限制");
        }
        // 先选择经济批量附近的可行批次数，避免贪心拆分在最后一批才发现不可守恒。
        BigInteger preferred = new BigDecimal(total).divide(new BigDecimal(economic), 0, RoundingMode.HALF_UP)
                .toBigIntegerExact();
        int count = preferred.max(least).min(most.min(BigInteger.valueOf(MAX_BATCHES))).intValueExact();
        List<BigDecimal> result = new ArrayList<>(count);
        BigInteger remaining = total;
        for (int left = count; left > 0; left--) {
            BigInteger following = BigInteger.valueOf(left - 1L);
            BigInteger lower = minimum.max(remaining.subtract(maximum.multiply(following)));
            BigInteger upper = maximum.min(remaining.subtract(minimum.multiply(following)));
            BigInteger quantity = economic.max(lower).min(upper);
            result.add(multiple.multiply(new BigDecimal(quantity)).setScale(SCALE, RoundingMode.UNNECESSARY));
            remaining = remaining.subtract(quantity);
        }
        return List.copyOf(result);
    }

    /**
     * 换算为整批单位，所有可行区间运算保持整数精确性。
     */
    private BigInteger units(BigDecimal quantity, BigDecimal multiple) {
        return quantity.divide(multiple, 0, RoundingMode.UNNECESSARY).toBigIntegerExact();
    }

    private BigInteger ceilDivide(BigInteger quantity, BigInteger divisor) {
        return quantity.add(divisor).subtract(BigInteger.ONE).divide(divisor);
    }

    private void requireCompleteCatalog(String factory, List<String> products,
                                        Map<String, MdmProductionLotPolicyEntity> policies,
                                        List<String> missing, LocalDateTime effective) {
        Long tenant = TenantContextHolder.getTenantId();
        if (tenant == null || tenant <= 0 || TenantContextHolder.getTenantSkip() || missing == null
                || new HashSet<>(missing).size() != missing.size()
                || missing.stream().anyMatch(StrUtil::isBlank)) {
            throw new CheckedException("MDM生产批量目录缺失产品或租户依据不可核验");
        }
        Set<String> covered = new HashSet<>(policies.keySet());
        if (!Collections.disjoint(covered, missing)) {
            throw new CheckedException("MDM生产批量目录同时将同一产品标为已配置和未配置");
        }
        covered.addAll(missing);
        if (!covered.equals(new HashSet<>(products))) {
            throw new CheckedException("MDM生产批量目录未精确覆盖请求产品范围，MPS已故障关闭");
        }
        for (Map.Entry<String, MdmProductionLotPolicyEntity> entry : policies.entrySet()) {
            MdmProductionLotPolicyEntity policy = entry.getValue();
            requireValidPolicy(policy);
            if (!Objects.equals(entry.getKey(), policy.getProductCode())
                    || !Objects.equals(factory, policy.getFactoryCode())
                    || !Objects.equals(tenant, policy.getTenantId())
                    || StrUtil.isBlank(policy.getPolicyNo()) || policy.getValidStartTime() == null
                    || policy.getValidStartTime().isAfter(effective)
                    || (policy.getValidEndTime() != null && !policy.getValidEndTime().isAfter(effective))) {
                throw new CheckedException("MDM生产批量策略产品、组织或有效期不一致，MPS已故障关闭");
            }
        }
    }

    private void requireValidPolicy(MdmProductionLotPolicyEntity policy) {
        boolean valid = policy != null && policy.getId() != null && policy.getVersionNo() != null
                && policy.getVersionNo() > 0 && "ACTIVE".equals(policy.getStatus())
                && positive(policy.getMinimumBatchQuantity()) && positive(policy.getEconomicBatchQuantity())
                && positive(policy.getMaximumBatchQuantity()) && positive(policy.getBatchMultiple())
                && policy.getMinimumBatchQuantity().compareTo(policy.getEconomicBatchQuantity()) <= 0
                && policy.getEconomicBatchQuantity().compareTo(policy.getMaximumBatchQuantity()) <= 0
                && policy.getMinimumBatchQuantity().remainder(policy.getBatchMultiple()).signum() == 0
                && policy.getEconomicBatchQuantity().remainder(policy.getBatchMultiple()).signum() == 0
                && policy.getMaximumBatchQuantity().remainder(policy.getBatchMultiple()).signum() == 0;
        if (!valid) throw new CheckedException("MDM返回了不合法的正式生产批量策略，MPS已故障关闭");
    }

    private boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0 && value.stripTrailingZeros().scale() <= SCALE
                && value.stripTrailingZeros().precision() - value.stripTrailingZeros().scale() <= 14;
    }

    private String text(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    public record Catalog(Map<String, MdmProductionLotPolicyEntity> policies,
                          List<String> productsWithoutPolicy,
                          String evidenceFingerprint) {
    }
}
