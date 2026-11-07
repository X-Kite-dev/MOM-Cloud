package com.kitex.mom.mes.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/** 选择入口须等待V3成功与未受理封存的数据库保护同时部署。 */
@Mapper
public interface MesPieceMaterialSelectionMapper {
	@Select("""
			SELECT (SELECT count(*) FROM pg_trigger g JOIN pg_class c ON c.oid=g.tgrelid
			 JOIN pg_namespace n ON n.oid=c.relnamespace JOIN pg_proc p ON p.oid=g.tgfoid
			 JOIN pg_namespace pn ON pn.oid=p.pronamespace
			 WHERE n.nspname='mom_mes' AND pn.nspname='mom_mes' AND g.tgenabled IN ('O','A')
			 AND p.proname='mom_check_piece_material_command' AND g.tgdeferrable AND g.tginitdeferred
			 AND ((c.relname='mes_report_command' AND g.tgname='trg_mes_piece_material_command_commit')
			 OR (c.relname='mes_report_request_close' AND g.tgname='trg_mes_piece_material_close_commit')))=2
			 AND (SELECT count(*) FROM pg_constraint k JOIN pg_class c ON c.oid=k.conrelid
			 JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='mom_mes' AND k.contype='c'
			 AND k.convalidated AND pg_get_constraintdef(k.oid) LIKE '%MES_REPORT_COMMAND_V3%'
			 AND ((c.relname='mes_report_command' AND k.conname='ck_mes_report_command_versions')
			 OR (c.relname='mes_report_request_close' AND k.conname='ck_mes_report_request_close_versions')))=2
			""")
	Boolean ready();
}
