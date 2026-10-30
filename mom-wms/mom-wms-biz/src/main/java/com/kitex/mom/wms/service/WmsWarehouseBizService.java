package com.kitex.mom.wms.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.wms.entity.WmsWarehouseBizEntity;
import com.kitex.mom.wms.vo.WmsWarehouseBizCheckVO;

import java.util.List;

public interface WmsWarehouseBizService extends IService<WmsWarehouseBizEntity> {

	/**
	 * 显式配置为空，区别于未配置的历史仓库。
	 */
	String NO_BUSINESS = "__NONE__";

	boolean updateAll(List<WmsWarehouseBizCheckVO> list);
}
