package com.kitex.mom.wms;

import com.kitex.common.feign.annotation.EnableMomFeignClients;
import com.kitex.common.job.annotation.EnableMomXxlJob;
import com.kitex.common.security.annotation.EnableMomResourceServer;
import com.kitex.common.swagger.annotation.EnableOpenApi;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * @author kitex
 * <p>
 * 项目启动类
 */
@EnableOpenApi("mom-wms")
@EnableMomFeignClients
@EnableDiscoveryClient
@EnableMomResourceServer
@SpringBootApplication
@EnableMomXxlJob
//@EnableDynamicRefreshMdm
public class MomWmsApplication {

    public static void main(String[] args) {
        SpringApplication.run(MomWmsApplication.class, args);
    }

}
