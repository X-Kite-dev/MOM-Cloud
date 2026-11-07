package com.kitex.mom.mes.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;

/**
 * 新逐料执行须有对应开工版本和原依据数据库守卫；不将缺升级当作可执行。
 */
@Mapper
public interface MesMaterialConsumptionPolicyMapper {
	/**
	 * 检查当前库V2开工守卫和约束是否真实启用；缺失返回false，不表示业务已验收。
	 */
	@Select("""
			SELECT EXISTS (SELECT 1 FROM pg_trigger g
			 JOIN pg_class c ON c.oid=g.tgrelid JOIN pg_namespace n ON n.oid=c.relnamespace
			 JOIN pg_proc p ON p.oid=g.tgfoid JOIN pg_namespace pn ON pn.oid=p.pronamespace
			 WHERE n.nspname='mom_mes' AND pn.nspname='mom_mes' AND c.relname='mes_material_start_check'
			 AND g.tgname='trg_mom_material_start_v2' AND p.proname='mom_guard_material_start_v2'
			 AND g.tgenabled='A' AND g.tgtype=7 AND NOT g.tgisinternal)
			 AND EXISTS (SELECT 1 FROM pg_constraint k JOIN pg_class c ON c.oid=k.conrelid
			 JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='mom_mes'
			 AND c.relname='mes_material_start_check' AND k.conname='ck_mes_material_start_check'
			 AND k.contype='c' AND k.convalidated AND pg_get_constraintdef(k.oid) LIKE '%MATERIAL_START_V2%')
			""")
	@Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
	Boolean ready();
}
