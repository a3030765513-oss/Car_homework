package com.substation.common.infra;

import java.util.List;

/**
 * 分布式部署本地配置（{@code deploy/infra.local.json}）。
 */
public record DeployConfig(
        String redisHost,
        int redisPort,
        String mqHost,
        int mqPort,
        String role,
        String displayHost,
        int displayHttpPort,
        int displayWsPort,
        List<String> cars) {

    public static final String ROLE_INFRA = "infra";
    public static final String ROLE_PLANNER = "planner";
    public static final String ROLE_CAR = "car";
    public static final String ROLE_DISPLAY = "display";

    public static final List<String> DEFAULT_CARS = List.of("Car001", "Car002", "Car003");

    public static DeployConfig localhostDefaults() {
        return new DeployConfig(
                InfraConnectionConfig.DEFAULT_REDIS_HOST,
                InfraConnectionConfig.DEFAULT_REDIS_PORT,
                InfraConnectionConfig.DEFAULT_MQ_HOST,
                InfraConnectionConfig.DEFAULT_MQ_PORT,
                ROLE_INFRA,
                "localhost",
                8887,
                8888,
                DEFAULT_CARS);
    }

    public InfraConnectionConfig toInfraConnectionConfig() {
        return new InfraConnectionConfig(redisHost, redisPort, mqHost, mqPort);
    }
}
