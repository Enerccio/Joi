package com.github.enerccio.joi.node;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The Looking Glass as Bridge reports it (its answers as Bridge 2.6 gives them), and what the user is told without one. */
class LookingGlassBridgeTest {

    private static final String CALIBRATION = "{\\\"DPI\\\":{\\\"value\\\":491},\\\"center\\\":{\\\"value\\\":0.2},"
            + "\\\"invView\\\":{\\\"value\\\":1},\\\"pitch\\\":{\\\"value\\\":80.7},\\\"screenH\\\":{\\\"value\\\":2560},"
            + "\\\"screenW\\\":{\\\"value\\\":1440},\\\"serial\\\":\\\"LKG-E08597\\\",\\\"slope\\\":{\\\"value\\\":-6.6},"
            + "\\\"viewCone\\\":{\\\"value\\\":54.0}}";

    private static final String DEVICES = """
            {"name":"value","payload":{"name":"payload","type":"VARIANT_MAP","value":{
              "0":{"name":"0","type":"VARIANT_MAP","value":{"calibration":{"name":"calibration","type":"WSTRING","value":""},
                "defaultQuilt":{"name":"defaultQuilt","type":"WSTRING","value":"{}"},"hwid":{"name":"hwid","type":"WSTRING","value":"XZ342CK"},
                "windowCoords":{"name":"windowCoords","type":"INT2","value":{"x":0,"y":0}}}},
              "1":{"name":"1","type":"VARIANT_MAP","value":{"calibration":{"name":"calibration","type":"WSTRING","value":"%s"},
                "defaultQuilt":{"name":"defaultQuilt","type":"WSTRING","value":"{ \\"quiltAspect\\" : 0.5625, \\"quiltX\\" : 4092, \\"quiltY\\" : 4092, \\"tileX\\" : 11, \\"tileY\\" : 6}"},
                "hardwareVersion":{"name":"hardwareVersion","type":"WSTRING","value":"go_p"},
                "hwid":{"name":"hwid","type":"WSTRING","value":"LKG-E08597"},
                "windowCoords":{"name":"windowCoords","type":"INT2","value":{"x":1470,"y":360}}}}}},
             "status":{"name":"status","type":"WSTRING","value":"Completion"}}""".formatted(CALIBRATION);

    @Test
    void itFindsTheLookingGlassAndLetsBridgeGo() throws Exception {
        List<String> calls = new CopyOnWriteArrayList<>();
        HttpServer bridge = bridge(calls, DEVICES);
        try {
            LookingGlassBridge.Device device = new LookingGlassBridge(uri(bridge)).find();
            assertThat(device.serial()).isEqualTo("LKG-E08597");
            assertThat(device.x()).isEqualTo(1470);
            assertThat(device.y()).isEqualTo(360);
            assertThat(device.width()).isEqualTo(1440);
            assertThat(device.height()).isEqualTo(2560);
            JsonObject renderer = device.renderer();
            assertThat(renderer.getAsJsonObject("calibration").getAsJsonObject("pitch").get("value").getAsDouble()).isEqualTo(80.7);
            assertThat(renderer.getAsJsonObject("quilt").toString()).isEqualTo("{\"width\":4092,\"height\":4092,\"columns\":11,\"rows\":6}");
            assertThat(calls).containsExactly("enter_orchestration {\"name\":\"joi-node\"}", "available_output_devices {\"orchestration\":\"t1\"}",
                    "exit_orchestration {\"orchestration\":\"t1\"}");
        } finally {
            bridge.stop(0);
        }
    }

    @Test
    void withoutOneTheUserIsToldWhy() throws Exception {
        List<String> calls = new CopyOnWriteArrayList<>();
        HttpServer bridge = bridge(calls, DEVICES.replace("LKG-E08597\"}", "PHL\"}"));
        try {
            assertThatThrownBy(() -> new LookingGlassBridge(uri(bridge)).find()).isInstanceOf(IOException.class)
                    .hasMessageContaining("sees no Looking Glass");
            assertThat(calls.getLast()).startsWith("exit_orchestration");
        } finally {
            bridge.stop(0);
        }

        int free;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            free = socket.getLocalPort();
        }
        assertThatThrownBy(() -> new LookingGlassBridge(URI.create("http://127.0.0.1:" + free + "/")).find())
                .isInstanceOf(IOException.class).hasMessageContaining("Bridge is not running");
    }

    private static URI uri(HttpServer server) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    private static HttpServer bridge(List<String> calls, String devices) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                String call = exchange.getRequestURI().getPath().substring(1);
                calls.add(call + " " + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                String body = switch (call) {
                    case "enter_orchestration" -> "{\"name\":\"value\",\"payload\":{\"name\":\"payload\",\"type\":\"WSTRING\",\"value\":\"t1\"},"
                            + "\"status\":{\"name\":\"status\",\"type\":\"WSTRING\",\"value\":\"Completion\"}}";
                    case "available_output_devices" -> devices;
                    default -> "{\"name\":\"value\",\"status\":{\"name\":\"status\",\"type\":\"WSTRING\",\"value\":\"Completion\"}}";
                };
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        server.start();
        return server;
    }
}
