package com.kitex.mom.plan.service;

import com.kitex.mom.plan.dto.PlnResourceLoadQueryDTO;
import com.kitex.mom.plan.vo.PlnResourceLoadOperationVO;
import com.kitex.mom.plan.vo.PlnResourceLoadVO;

import java.time.LocalDate;
import java.util.List;

public interface PlnResourceLoadService {

    List<PlnResourceLoadVO> queryDailyLoads(PlnResourceLoadQueryDTO query);

    List<PlnResourceLoadOperationVO> queryLoadOperations(Long taskId, String resourceType,
                                                         String resourceCode, LocalDate loadDate);
}
