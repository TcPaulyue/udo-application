package info.nemoworks.udo.messaging.gateway;

import com.google.common.eventbus.Subscribe;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import info.nemoworks.udo.messaging.messaging.Publisher;
import info.nemoworks.udo.messaging.messaging.Subscriber;
import info.nemoworks.udo.model.Udo;
import info.nemoworks.udo.model.UriType;
import info.nemoworks.udo.model.event.EventType;
import info.nemoworks.udo.model.event.GatewayEvent;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class MQTTGateway extends UdoGateway {

    private Publisher publisher;

    private Subscriber subscriber;

    public ConcurrentHashMap<String, String> getEndpoints() {
        return endpoints;
    }

    private final ConcurrentHashMap<String, String> endpoints;

    private String topicReceive;

    private String topicRegister;

    private String topicSend;

    public MQTTGateway() throws MqttException, IOException {
        super();
        String clientid1 = UUID.randomUUID().toString();
        MqttClient client1 = new MqttClient("tcp://47.94.101.110:8081", clientid1);
        String clientid2 = UUID.randomUUID().toString();
        MqttClient client2 = new MqttClient("tcp://47.94.101.110:8081", clientid2);
        MqttConnectOptions options = new MqttConnectOptions();
        options.setAutomaticReconnect(true);
        options.setMqttVersion(MqttConnectOptions.MQTT_VERSION_DEFAULT);
        options.setCleanSession(true);
        options.setConnectionTimeout(10);
        options.setUserName("udo-user");
        char[] password = "123456".toCharArray();
        options.setPassword(password);
        client1.connect(options);
        client2.connect(options);
        this.publisher = new Publisher(client1);
        this.subscriber = new Subscriber(client2);
        this.endpoints = new ConcurrentHashMap<>();
        this.topicRegister = "topic/register";
    }

    // 向udo发送状态更新请求
    @Override
    public void downLink(String tag, byte[] payload) throws IOException, InterruptedException {
        log.info("downlink topic: " + new String(payload));
        try {
            subscriber.subscribe(new String(payload), (topic, data) -> {
                data.getPayload();
                Thread thread = Thread.currentThread();
                log.info(
                    "MQTT subscribe To Human Service=====" + new String(data.getPayload()));
                Gson gson = new Gson();
                try {
                    JsonObject update = gson
                        .fromJson(new String(data.getPayload()), JsonObject.class);
                    log.info("This thread id: " + tag);
                    log.info("Data: " + update.toString());
                    String uri = update.get("uri").getAsString();
                    log.info(
                        "Try to update, uri: " + uri);
                    update.remove("uri");
                    this.updateUdoByPolling(tag,
                        gson.toJson(update).getBytes());
                } catch (Exception e) {
                    log.info("Data is not in the Form of JSON!");
                }

            });
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    // 轮询监听资源状态
    public void start() throws InterruptedException {
//        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
//
//        executor.scheduleWithFixedDelay(
//            () -> {
        endpoints.forEach(
            (key, value) -> {
                try {
                    this.downLink(key, value.getBytes());
                } catch (IOException | InterruptedException e) {
                    e.printStackTrace();
                }
            }
        );
//            },
//            15L,
//            15L,
//            TimeUnit.SECONDS);
//        TimeUnit.SECONDS.sleep(15L);
    }

    @Subscribe
    public void subscribeMessage(GatewayEvent gatewayEvent)
        throws IOException, InterruptedException {
        Udo udo = (Udo) gatewayEvent.getSource();
        if (udo != null) {
            if (udo.getUri() == null) {
                return;
            }
            if (udo.getUri().getUriType().equals(UriType.NOTEXIST)) {
                return;
            }
        }
        EventType contextId = gatewayEvent.getContextId();
        log.info("In MQTT Subscribing Udo...");
        switch (contextId) {
            case SAVE_BY_URI:
                if (!udo.getUri().getUriType().equals(UriType.MQTT)) {
                    return;
                }
                log.info("Detect Create Request...");
                try {
                    subscriber
                        .subscribe(this.topicRegister, (topic, payload) -> {
                            Gson gson = new Gson();
                            JsonObject content = gson
                                .fromJson(new String(payload.getPayload()), JsonObject.class);
                            Thread thread = Thread.currentThread();
                            log.info(
                                "MQTT creating UDO=====" + new String(payload.getPayload()));
                            log.info("UdoId: " + udo.getId());
                            try {
                                String source = content.getAsJsonPrimitive("source").getAsString();
                                if (!source.equals("backend")) {
                                    JsonObject data = content.get("payload").getAsJsonObject();
                                    log.info("In MQTTGateWay, Creating Udo by URI");
                                    log.info("Data: " + data.toString());
                                    this.updateUdoByUri(udo.getId(), data.toString().getBytes(),
                                        gatewayEvent.getPayload(), udo.getContextInfo(),
                                        udo.getUri().getUriType());
                                    subscriber.unsubscribe(this.topicRegister);
                                    this.register(udo.getId(), udo.getUri().getUri());
                                }
                            } catch (Exception e) {
                                log.info("Data is not in the Form of JSON!");
                            }
                        });
                } catch (Exception e) {
                    e.printStackTrace();
                }
                break;
            case SAVE:
                this.register(udo.getId(), udo.getUri().getUri());
                break;
            case UPDATE:
                if (udo.getUri().getUriType().equals(UriType.HTTP)) {
                    return;
                }
                this.updateUdoByPolling(udo.getId(), udo.getData().toString().getBytes());
                break;
            case DELETE:
                this.unregister(new String(gatewayEvent.getPayload()));
                break;
            default:
                break;
        }

    }

    private synchronized void register(String udoi, String topic)
        throws IOException, InterruptedException {
        if (!endpoints.containsKey(udoi)) {
            endpoints.put(udoi, topic);
            log.info("start subscribers...");
            this.start();
        }
    }

    private synchronized void unregister(String udoi) {
        if (endpoints.containsKey(udoi)) {
            endpoints.remove(udoi);
        }
    }

    private String getUdoId(String topic) {
        return topic.split("/")[2];
    }

    // 向资源发送状态更新请求
    @Override
    public void updateLink(String topic, byte[] payload, String data)
        throws IOException, InterruptedException {
        try {
            log.info(("MQTT publish To Human Resource========" + ":" + new String(data)));
            this.publisher.publish(topic, data.getBytes());
        } catch (MqttException e) {
            e.printStackTrace();
        }
    }
}
