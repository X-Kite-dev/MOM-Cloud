package com.kitex.mom.mes.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * 逐料GOOD回执的即时及延迟数据库保护目录；只读检查，不安装或修复对象。
 */
@Mapper
public interface MesGoodMaterialPolicyReceiptMapper {
	/**
	 * 核对原件核验函数及两类ALWAYS触发器的准确形状。
	 *
	 * @return 仅完整、启用的即时及提交守卫返回true；缺失或停用返回false
	 */
	@Select("""
			SELECT to_regprocedure('mom_mes.mom_assert_good_material_receipt_v1(mom_mes.mes_report_command)') IS NOT NULL
			AND (SELECT count(*) FROM pg_trigger t
			 WHERE t.tgrelid='mom_mes.mes_report_command'::regclass AND t.tgenabled='A' AND NOT t.tgisinternal
			  AND t.tgfoid=to_regprocedure('mom_mes.mom_check_good_material_receipt_v1()')
			  AND t.tgnargs=0 AND octet_length(t.tgargs)=0 AND t.tgqual IS NULL
			  AND t.tgoldtable IS NULL AND t.tgnewtable IS NULL
			  AND ((t.tgname='trg_mes_good_material_receipt_before' AND t.tgtype=7 AND NOT t.tgdeferrable
			        AND NOT t.tginitdeferred AND t.tgconstraint=0)
			   OR (t.tgname='trg_mes_good_material_receipt_commit' AND t.tgtype=5 AND t.tgdeferrable
			        AND t.tginitdeferred AND t.tgconstraint<>0)))=2
			""")
	Boolean ready();

}
