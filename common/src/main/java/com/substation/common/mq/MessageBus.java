package com.substation.common.mq;

import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DeliverCallback;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

public class MessageBus implements AutoCloseable {

    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private Connection connection;
    private Channel channel;

    public MessageBus(String host, int port, String username, String password) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
    }

    public void connect() throws IOException, TimeoutException {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(host);
        factory.setPort(port);
        factory.setUsername(username);
        factory.setPassword(password);
        factory.setAutomaticRecoveryEnabled(true);
        connection = factory.newConnection();
        channel = connection.createChannel();
    }

    // ==================== 队列声明 ====================

    public void declareCarQueue(String carId) throws IOException {
        channel.queueDeclare(QueueNames.carQueue(carId), true, false, false, null);
    }

    public void declareNavigatorQueue() throws IOException {
        channel.queueDeclare(QueueNames.NAVIGATOR_CMD, true, false, false, null);
    }

    public void declareTargetPlannerQueue() throws IOException {
        channel.queueDeclare(QueueNames.TARGET_PLANNER_CMD, true, false, false, null);
    }

    public void declareTaskConfigQueue() throws IOException {
        channel.queueDeclare(QueueNames.TASK_CONFIG_CMD, true, false, false, null);
    }

    public void declareControllerQueue() throws IOException {
        channel.queueDeclare(QueueNames.CONTROLLER_CMD, true, false, false, null);
    }

    public void declareFanoutExchange() throws IOException {
        channel.exchangeDeclare(QueueNames.UPDATE_VIEW_EXCHANGE, BuiltinExchangeType.FANOUT, true);
    }

    public String bindFanoutQueue() throws IOException {
        String queueName = channel.queueDeclare().getQueue();
        channel.queueBind(queueName, QueueNames.UPDATE_VIEW_EXCHANGE, "");
        return queueName;
    }

    // ==================== 发布 ====================

    public void publish(String queueName, String message) throws IOException {
        channel.basicPublish("", queueName, null, message.getBytes(StandardCharsets.UTF_8));
    }

    public void publishFanout(String exchangeName, String message) throws IOException {
        channel.basicPublish(exchangeName, "", null, message.getBytes(StandardCharsets.UTF_8));
    }

    // ==================== 订阅 ====================

    public void subscribe(String queueName, Consumer<String> handler) throws IOException {
        DeliverCallback callback = (consumerTag, delivery) -> {
            String msg = new String(delivery.getBody(), StandardCharsets.UTF_8);
            handler.accept(msg);
        };
        channel.basicConsume(queueName, true, callback, consumerTag -> {});
    }

    // ==================== 连接管理 ====================

    public boolean isConnected() {
        return connection != null && connection.isOpen();
    }

    @Override
    public void close() {
        try {
            if (channel != null && channel.isOpen()) {
                channel.close();
            }
            if (connection != null && connection.isOpen()) {
                connection.close();
            }
        } catch (IOException | TimeoutException e) {
            throw new UncheckedIOException(new IOException("关闭连接失败", e));
        }
    }
}
