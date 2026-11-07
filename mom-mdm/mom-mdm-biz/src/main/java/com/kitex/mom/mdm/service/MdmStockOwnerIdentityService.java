package com.kitex.mom.mdm.service;

import com.kitex.mom.mdm.dto.MdmStockOwnerIdentityDTO;
import com.kitex.mom.mdm.dto.MdmStockOwnerLookupDTO;
import com.kitex.mom.mdm.dto.MdmStockOwnerSearchDTO;

import java.util.List;

/**
 * 外部货主最小身份只读编排，无单表CRUD职责；每次依当前实名人员核实际工厂读权。
 */
public interface MdmStockOwnerIdentityService {

	/**
	 * 按可信组织及精确编码最多读取两行，唯一后核预期主键；缺失、歧义或无权明确拒绝。
	 *
	 * @param query 仅外部货权类型、精确编码及可空规范主键文本
	 * @return 原文身份及真实启用、审核状态；资格不合格也不隐藏记录
	 */
	MdmStockOwnerIdentityDTO resolve(MdmStockOwnerLookupDTO query);

	/**
	 * 在当前可信工厂搜索编码或名称的字面子串，拒绝空关键词及超长输入。
	 *
	 * @param query 货权类型及修剪后1至100字符关键词
	 * @return 数值主键升序的最多20个最小候选，无隐私或深关联
	 */
	List<MdmStockOwnerIdentityDTO> search(MdmStockOwnerSearchDTO query);
}
