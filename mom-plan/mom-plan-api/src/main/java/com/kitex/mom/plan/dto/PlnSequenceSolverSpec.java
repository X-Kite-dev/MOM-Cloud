package com.kitex.mom.plan.dto;
/** 可冻结的排序求解器契约；不代表完整有限产能求解器或全局最优证明。 */
public record PlnSequenceSolverSpec(String code,String name,String version,String contractVersion,
                                    int maxPlans,int beamWidth,long maxExpansions,long searchBudgetMillis) {}
