package com.substation.common.infra;

/**
 * Redis / RabbitMQ 连接参数，供各模块 {@code main} 解析命令行。
 *
 * <p>单机默认 {@code localhost}；分布式时 Person C/D 传入 Person A 的 Tailscale IP：
 * {@code --redis-host 100.x.x.x --mq-host 100.x.x.x}
 */
public record InfraConnectionConfig(
        String redisHost,
        int redisPort,
        String mqHost,
        int mqPort) {

    public static final String DEFAULT_REDIS_HOST = "localhost";
    public static final int DEFAULT_REDIS_PORT = 6379;
    public static final String DEFAULT_MQ_HOST = "localhost";
    public static final int DEFAULT_MQ_PORT = 5672;

    public static InfraConnectionConfig localhost() {
        return new InfraConnectionConfig(
                DEFAULT_REDIS_HOST, DEFAULT_REDIS_PORT, DEFAULT_MQ_HOST, DEFAULT_MQ_PORT);
    }

    /**
     * 从 {@code args} 解析 {@code --redis-host} 等；未识别的参数（如 {@code Car001}）忽略。
     */
    public static InfraConnectionConfig fromArgs(String[] args) {
        String redisHost = DEFAULT_REDIS_HOST;
        int redisPort = DEFAULT_REDIS_PORT;
        String mqHost = DEFAULT_MQ_HOST;
        int mqPort = DEFAULT_MQ_PORT;

        for (int i = 0; i < args.length; i++) {
            String key = args[i];
            switch (key) {
                case "--redis-host" -> redisHost = requireValue(args, ++i, key);
                case "--redis-port" -> redisPort = requireInt(args, ++i, key);
                case "--mq-host" -> mqHost = requireValue(args, ++i, key);
                case "--mq-port" -> mqPort = requireInt(args, ++i, key);
                default -> { /* 忽略 Car001、--dynamic 等 */ }
            }
        }
        return new InfraConnectionConfig(redisHost, redisPort, mqHost, mqPort);
    }

    private static String requireValue(String[] args, int index, String key) {
        if (index >= args.length) {
            throw new IllegalArgumentException("参数 " + key + " 缺少值");
        }
        return args[index];
    }

    private static int requireInt(String[] args, int index, String key) {
        try {
            return Integer.parseInt(requireValue(args, index, key));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("参数 " + key + " 的值不是有效整数", e);
        }
    }
}
