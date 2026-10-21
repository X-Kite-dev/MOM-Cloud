package com.kitex.mom.plan.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.plan.entity.PlnSchedulingEffectReviewEntity;
import com.kitex.mom.plan.entity.PlnSchedulingScenarioEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface PlnSchedulingEffectReviewMapper extends BaseMapper<PlnSchedulingEffectReviewEntity> {
    @Select("SELECT pg_advisory_xact_lock(hashtextextended(CONCAT('PLN_SCHEDULING_EFFECT:', #{key}), 0))")
    void lockKey(@Param("key") String key);

    @Select("SELECT * FROM pln_scheduling_effect_review WHERE id=#{id} AND del_flag='0' FOR UPDATE")
    PlnSchedulingEffectReviewEntity lockById(@Param("id") Long id);

    @Select("SELECT * FROM pln_scheduling_scenario s WHERE s.del_flag='0' " +
            "AND s.scenario_status IN('PUBLISHED','PARTIAL') " +
            "AND NOT EXISTS(SELECT 1 FROM pln_scheduling_effect_review r WHERE r.del_flag='0' " +
            "AND r.tenant_id IS NOT DISTINCT FROM s.tenant_id AND r.scenario_id=s.id) " +
            "ORDER BY COALESCE(s.published_time,s.update_time,s.create_time),s.id LIMIT #{limit}")
    List<PlnSchedulingScenarioEntity> selectUnreviewedPublishedScenarios(@Param("limit") int limit);

    @Select("SELECT * FROM pln_scheduling_effect_review WHERE del_flag='0' " +
            "AND review_status NOT IN('COMPLETED','ROLLED_BACK') AND next_evaluation_time IS NOT NULL " +
            "AND next_evaluation_time<=#{now} ORDER BY next_evaluation_time,id LIMIT #{limit}")
    List<PlnSchedulingEffectReviewEntity> selectDueReviews(@Param("now") LocalDateTime now,
                                                           @Param("limit") int limit);

    @Select("SELECT CASE " +
            "WHEN EXISTS(SELECT 1 FROM pln_shortage_replan_case c WHERE c.del_flag='0' AND c.scenario_id=#{scenarioId} AND c.status='ROLLED_BACK') THEN 'ROLLED_BACK' " +
            "WHEN EXISTS(SELECT 1 FROM pln_resource_replan_case c WHERE c.del_flag='0' AND c.scenario_id=#{scenarioId} AND c.status='ROLLED_BACK') THEN 'ROLLED_BACK' " +
            "WHEN EXISTS(SELECT 1 FROM pln_quality_replan_case c WHERE c.del_flag='0' AND c.scenario_id=#{scenarioId} AND c.status='ROLLED_BACK') THEN 'ROLLED_BACK' " +
            "WHEN EXISTS(SELECT 1 FROM pln_insertion_replan_case c WHERE c.del_flag='0' AND c.scenario_id=#{scenarioId} AND c.status='ROLLED_BACK') THEN 'ROLLED_BACK' " +
            "ELSE NULL END")
    String selectLifecycleStatus(@Param("scenarioId") Long scenarioId);
}
