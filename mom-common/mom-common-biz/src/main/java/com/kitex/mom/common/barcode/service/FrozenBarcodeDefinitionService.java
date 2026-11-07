package com.kitex.mom.common.barcode.service;

import com.kitex.mom.common.barcode.dto.FrozenBarcodeDefinition;
import com.kitex.mom.common.barcode.dto.req.BarcodeDefinitionFreezeReq;

/**
 * 条码定义冻结读取，不创建箱码、不分配序列，也不承担业务域封箱事务。
 */
public interface FrozenBarcodeDefinitionService {

	/**
	 * 从当前实名租户和已授权实际工厂读取一份一致的原定义。
	 * @param request 规则编码，不接受请求体指定组织或覆盖规则内容
	 * @return 按持久字段ID排序、含原文指纹的定义；规则不唯一或不兼容时拒绝
	 */
	FrozenBarcodeDefinition freezeDefinition(BarcodeDefinitionFreezeReq request);

}
