package com.kitex.mom.mes.util;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mdm.dto.MdmBomSubstituteOptionDTO;
import com.kitex.mom.mdm.enums.SubstituteStrategyEnum;
import com.kitex.mom.mes.dto.MdmBomChildDTO;
import com.kitex.mom.mes.dto.MdmBomChildListDTO;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * 只从已冻结的工单 BOM 快照解析替代策略，避免生产执行受主数据后续变更影响。
 */
public final class MesMaterialSubstituteResolver {

    private MesMaterialSubstituteResolver() {
    }

    public static Policy resolvePolicy(String bomSnapshot, String requirementMatCode) {
        MdmBomChildDTO child = flattenBom(bomSnapshot).stream()
                .filter(item -> Objects.equals(item.getMaterialCode(), requirementMatCode))
                .findFirst().orElse(null);
        if (child == null) {
            return new Policy(requirementMatCode, false, SubstituteStrategyEnum.MAIN_FIRST.name(),
                    List.of(new Candidate(requirementMatCode, BigDecimal.ONE, Integer.MIN_VALUE, false)));
        }
        List<Candidate> substitutes = parseSubstitutes(child);
        String strategy = StrUtil.blankToDefault(child.getSubstituteStrategy(), SubstituteStrategyEnum.MAIN_FIRST.name());
        int mainPriority = SubstituteStrategyEnum.SUBSTITUTE_FIRST.name().equals(strategy)
                ? Integer.MAX_VALUE : Integer.MIN_VALUE;
        List<Candidate> candidates = new ArrayList<>();
        candidates.add(new Candidate(requirementMatCode, BigDecimal.ONE, mainPriority, false));
        candidates.addAll(substitutes);
        candidates.sort(Comparator.comparing(Candidate::priority).thenComparing(Candidate::actualMatCode));
        return new Policy(requirementMatCode, Boolean.TRUE.equals(child.getSubstituteMixAllowed()), strategy, candidates);
    }

    public static Binding resolveFeedBinding(String bomSnapshot, String actualMatCode, String requestedRequirementMatCode) {
        List<Binding> result = feedBindings(bomSnapshot, actualMatCode, requestedRequirementMatCode);
        if (result.stream().map(Binding::requirementMatCode).distinct().count() > 1) {
            throw new CheckedException("替代料[" + actualMatCode + "]可替代多个BOM需求，请明确选择需求物料");
        }
        return result.get(0);
    }

    /**
     * 读取冻结BOM全部允许绑定供明确选择；执行入口仍通过resolveFeedBinding拒绝歧义。
     */
    public static List<Binding> feedBindings(String bomSnapshot, String actualMatCode, String requestedRequirementMatCode) {
        if (StrUtil.isBlank(bomSnapshot)) {
            throw new CheckedException("生产工单缺少BOM快照，不能校验投料");
        }
        Map<String, Binding> matches = new LinkedHashMap<>();
        for (MdmBomChildDTO child : flattenBom(bomSnapshot)) {
            if (StrUtil.isNotBlank(requestedRequirementMatCode)
                    && !Objects.equals(requestedRequirementMatCode, child.getMaterialCode())) {
                continue;
            }
            Policy policy = resolvePolicy(bomSnapshot, child.getMaterialCode());
            for (Candidate candidate : policy.candidates()) {
                if (Objects.equals(candidate.actualMatCode(), actualMatCode)) {
                    Binding binding = new Binding(child.getMaterialCode(), actualMatCode, candidate.ratio(),
                            candidate.substitute(), policy.allowMixed(), policy.strategy());
                    matches.putIfAbsent(binding.requirementMatCode() + "|" + binding.actualMatCode(), binding);
                }
            }
        }
        if (matches.isEmpty()) {
            throw new CheckedException("物料[" + actualMatCode + "]不是工单BOM主料或当前有效替代料");
        }
        return List.copyOf(matches.values());
    }

    public static List<MdmBomChildDTO> flattenBom(String bomSnapshot) {
        if (StrUtil.isBlank(bomSnapshot)) {
            return List.of();
        }
        List<MdmBomChildListDTO> roots;
        try {
            roots = JSONUtil.toList(bomSnapshot, MdmBomChildListDTO.class);
        } catch (Exception ex) {
            throw new CheckedException("生产工单BOM快照格式错误");
        }
        List<MdmBomChildDTO> result = new ArrayList<>();
        for (MdmBomChildListDTO root : roots) {
            flatten(root, result);
        }
        return result;
    }

    private static void flatten(MdmBomChildDTO node, List<MdmBomChildDTO> result) {
        result.add(node);
        if (node instanceof MdmBomChildListDTO listNode && listNode.getChildren() != null) {
            for (MdmBomChildDTO child : listNode.getChildren()) {
                flatten(child, result);
            }
        }
    }

    private static List<Candidate> parseSubstitutes(MdmBomChildDTO child) {
        List<MdmBomSubstituteOptionDTO> options = new ArrayList<>();
        if (StrUtil.isNotBlank(child.getSubstitutePolicy())) {
            try {
                options = JSONUtil.toList(child.getSubstitutePolicy(), MdmBomSubstituteOptionDTO.class);
            } catch (Exception ex) {
                throw new CheckedException("BOM物料[" + child.getMaterialCode() + "]替代策略快照格式错误");
            }
        } else if (StrUtil.isNotBlank(child.getReplaceCodes())) {
            int priority = 1;
            for (String code : child.getReplaceCodes().split(",")) {
                if (StrUtil.isBlank(code)) {
                    continue;
                }
                MdmBomSubstituteOptionDTO option = new MdmBomSubstituteOptionDTO();
                option.setSubstituteCode(code.trim());
                option.setSubstituteRatio(BigDecimal.ONE);
                option.setPriority(priority++);
                option.setEnabled(Boolean.TRUE);
                options.add(option);
            }
        }
        LocalDate today = LocalDate.now();
        Map<String, Candidate> unique = new LinkedHashMap<>();
        for (MdmBomSubstituteOptionDTO option : options) {
            if (Boolean.FALSE.equals(option.getEnabled()) || StrUtil.isBlank(option.getSubstituteCode())) {
                continue;
            }
            if (option.getEffectiveStart() != null && today.isBefore(option.getEffectiveStart())) {
                continue;
            }
            if (option.getEffectiveEnd() != null && today.isAfter(option.getEffectiveEnd())) {
                continue;
            }
            BigDecimal ratio = option.getSubstituteRatio();
            if (ratio == null || ratio.compareTo(BigDecimal.ZERO) <= 0) {
                throw new CheckedException("BOM物料[" + child.getMaterialCode() + "]存在无效替代比例");
            }
            Candidate candidate = new Candidate(option.getSubstituteCode(), ratio,
                    Objects.requireNonNullElse(option.getPriority(), 1), true);
            unique.putIfAbsent(candidate.actualMatCode(), candidate);
        }
        return new ArrayList<>(unique.values());
    }

    public record Candidate(String actualMatCode, BigDecimal ratio, Integer priority, boolean substitute) {
    }

    public record Policy(String requirementMatCode, boolean allowMixed, String strategy,
                         List<Candidate> candidates) {
    }

    public record Binding(String requirementMatCode, String actualMatCode, BigDecimal ratio,
                          boolean substitute, boolean allowMixed, String strategy) {
    }
}
