package com.navlistener;

import android.util.Log;

import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * MqttManager — Singleton MQTT menggunakan HiveMQ client.
 * Library: com.hivemq:hivemq-mqtt-client (Maven Central, stabil)
 * Broker default: broker.emqx.io:1883
 * Topic: navmqtt/{channelID}/data
 */
public class MqttManager {

    private static final String TAG = "MqttManager";

    public static String BROKER_HOST = "broker.emqx.io";
    public static int    BROKER_PORT = 1883;
    public static String CHANNEL_ID  = "NAVKU001";

    private static MqttManager     instance;
    private        Mqtt3AsyncClient mqttClient;
    private        int              txCount = 0;

    public interface StatusListener {
        void onConnected();
        void onDisconnected();
        void onPublished(int txCount);
        void onError(String error);
    }

    private StatusListener statusListener;

    public static synchronized MqttManager getInstance() {
        if (instance == null) instance = new MqttManager();
        return instance;
    }
    private MqttManager() {}

    public void setStatusListener(StatusListener l) { this.statusListener = l; }

    /* ─── CONNECT ──────────────────────────────────────────── */

    public void connect(String host, int port, String channelId) {
        BROKER_HOST = host;
        BROKER_PORT = port;
        CHANNEL_ID  = channelId;
        connect();
    }

    public void connect() {
        if (mqttClient != null && mqttClient.getState().isConnected()) return;

        String clientId = "navandroid_" + UUID.randomUUID().toString().substring(0, 8);

        mqttClient = MqttClient.builder()
            .useMqttVersion3()
            .identifier(clientId)
            .serverHost(BROKER_HOST)
            .serverPort(BROKER_PORT)
            .automaticReconnectWithDefaultConfig()
            .buildAsync();

        mqttClient.connectWith()
            .cleanSession(true)
            .keepAlive(30)
            .send()
            .whenComplete((ack, err) -> {
                if (err != null) {
                    Log.e(TAG, "Connect gagal: " + err.getMessage());
                    if (statusListener != null) statusListener.onError(err.getMessage());
                } else {
                    Log.d(TAG, "MQTT terhubung ke " + BROKER_HOST);
                    if (statusListener != null) statusListener.onConnected();
                    subscribeAck();
                }
            });
    }

    /* ─── PUBLISH ──────────────────────────────────────────── */

    public void publish(String payload) {
        if (mqttClient == null || !mqttClient.getState().isConnected()) {
            connect(); return;
        }
        String topic = "navmqtt/" + CHANNEL_ID + "/data";
        mqttClient.publishWith()
            .topic(topic)
            .payload(payload.getBytes(StandardCharsets.UTF_8))
            .qos(MqttQos.AT_MOST_ONCE)
            .retain(false)
            .send()
            .whenComplete((pub, err) -> {
                if (err == null) {
                    txCount++;
                    if (statusListener != null) statusListener.onPublished(txCount);
                }
            });
    }

    public void publish(org.json.JSONObject json) { publish(json.toString()); }

    /* ─── SUBSCRIBE ACK ────────────────────────────────────── */

    private void subscribeAck() {
        if (mqttClient == null) return;
        mqttClient.subscribeWith()
            .topicFilter("navmqtt/" + CHANNEL_ID + "/cmd")
            .qos(MqttQos.AT_MOST_ONCE)
            .callback(msg -> Log.d(TAG, "CMD: " + new String(msg.getPayloadAsBytes())))
            .send();
    }

    /* ─── GETTERS ──────────────────────────────────────────── */

    public boolean isConnected() {
        return mqttClient != null && mqttClient.getState().isConnected();
    }
    public int getTxCount() { return txCount; }
    public void disconnect() {
        if (mqttClient != null) mqttClient.disconnect();
    }
}
