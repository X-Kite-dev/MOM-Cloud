package com.kitex.mom.mes.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.Set;

/** 普通单件选料所需的原号、实耗保护及仓储单件来源读取。 */
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
			 AND to_regprocedure('mom_mes.mom_feed_is_issued_piece_423(bigint,text,bigint)') IS NOT NULL
			 AND (SELECT count(*) FROM pg_catalog.pg_trigger t WHERE t.tgenabled IN ('O','A')
			      AND ((t.tgrelid=to_regclass('mom_mes.mes_warehouse_feed') AND t.tgname='trg_mes_issued_piece_balance_423')
			        OR (t.tgrelid=to_regclass('mom_mes.mes_production_feed_share') AND t.tgname='trg_mes_issued_piece_share_423')))=2
			""")
	Boolean ready();

	/** 批量核对本次候选投料中有整件签收依据的原行，与数据库实耗保护使用同一判定。 */
	@Select("""
			<script>
			SELECT f.id
			FROM mom_mes.mes_warehouse_feed f
			WHERE f.tenant_id = #{tenant} AND f.factory_code = #{factory} AND f.del_flag = '0'
			  AND f.id IN <foreach collection="ids" item="id" open="(" close=")" separator=",">#{id}</foreach>
			  AND mom_mes.mom_feed_is_issued_piece_423(f.tenant_id, f.factory_code, f.id)
			</script>
			""")
	Set<Long> issuedPieceIds(@Param("tenant") Long tenant, @Param("factory") String factory,
	                         @Param("ids") Collection<Long> ids);
}
