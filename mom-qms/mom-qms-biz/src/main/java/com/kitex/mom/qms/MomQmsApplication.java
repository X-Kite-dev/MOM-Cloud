package com.kitex.mom.qms;

import com.kitex.common.feign.annotation.EnableMomFeignClients;
import com.kitex.common.job.annotation.EnableMomXxlJob;
import com.kitex.common.security.annotation.EnableMomResourceServer;
import com.kitex.common.swagger.annotation.EnableOpenApi;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * @author kitex
 * <p>
 * 项目启动类
 */
@EnableOpenApi("mom-qms")
@EnableMomFeignClients
@EnableDiscoveryClient
@EnableMomResourceServer
@SpringBootApplication
@MapperScan("com.kitex.mom.qms.mapper")
@EnableMomXxlJob
public class MomQmsApplication {

    public static void main(String[] args) {
        SpringApplication.run(MomQmsApplication.class, args);
    }

}
