package com.github.enerccio.joi.ui.workspace.parts;

import com.github.enerccio.joi.domain.service.JoiAppSettingsService;
import com.github.enerccio.joi.domain.service.TtsService;
import com.github.enerccio.joi.domain.settings.JoiAppSettings;
import com.github.enerccio.marginalia.domain.security.model.User;
import com.github.enerccio.joi.tts.ServerCheck;
import com.github.enerccio.joi.tts.model.Actor;
import com.github.enerccio.joi.tts.model.TtsModel;
import com.github.enerccio.joi.ui.dialogs.TtsModelDialog;
import com.github.enerccio.joi.ui.tts.ActorDialog;
import com.github.enerccio.joi.ui.workspace.JoiWorkspace;
import com.github.enerccio.marginalia.domain.model.impl.Gpu;
import com.github.enerccio.marginalia.domain.model.impl.GpuLock;
import com.github.enerccio.marginalia.domain.model.impl.GpuStep;
import com.github.enerccio.marginalia.domain.service.GpuService;
import com.github.enerccio.marginalia.loc.L;
import com.github.enerccio.marginalia.loc.Localization;
import com.github.enerccio.marginalia.test.PlatformTestBase;
import com.sun.net.httpserver.HttpServer;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.data.provider.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The TTS models and voice actors of the Models part (kept in the user setting, spoken through a fake Chatterbox-TTS-Server under a
 * GPU lock), and the admin part, which only administrators get.
 */
class TtsAndAdminTest extends PlatformTestBase {

    @Autowired
    private Localization loc;

    @Autowired
    private TtsService ttsService;

    @Autowired
    private GpuService gpuService;

    @Autowired
    private JoiAppSettingsService appSettingsService;

    /** The UI of the test, held strongly (the current UI is a weak reference). */
    private UI ui;
    private HttpServer server;
    /** The paths the fake server was asked, in order. */
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private volatile String lastTtsBody;

    @BeforeEach
    void setUp() throws IOException {
        ui = new UI();
        UI.setCurrent(ui);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            calls.add(path);
            byte[] body;
            String type;
            switch (path) {
                case "/api/model-info" -> {
                    body = "{\"loaded\": true, \"class_name\": \"ChatterboxTurboTTS\"}".getBytes(StandardCharsets.UTF_8);
                    type = "application/json";
                }
                case "/tts" -> {
                    lastTtsBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    body = new byte[]{(byte) 0xFF, (byte) 0xFB, 0x10, 0x00, 1, 2, 3, 4, 5, 6, 7, 8, 9};
                    type = "audio/mpeg";
                }
                default -> {
                    body = "{}".getBytes(StandardCharsets.UTF_8);
                    type = "application/json";
                }
            }
            exchange.getResponseHeaders().add("Content-Type", type);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        UI.setCurrent(null);
        ui = null;
    }

    private String serverUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private TtsModel saveModel(String name, String lock) throws Exception {
        TtsModel model = new TtsModel();
        model.setName(name);
        model.setBaseUrl(serverUrl());
        model.setGpuLock(lock);
        ttsService.saveModel(model);
        return model;
    }

    private static <T> List<T> rows(com.vaadin.flow.component.grid.Grid<T> grid) {
        return grid.getDataProvider().fetch(new Query<>()).toList();
    }

    @Test
    void ttsModelDialogSavesAModelForTheUserOnly() throws Exception {
        login();
        TtsModelDialog dialog = new TtsModelDialog();
        dialog.create();
        dialog.getNameField().setValue("Local Chatterbox");
        dialog.getAddressField().setValue(serverUrl());
        dialog.getModelSelect().setValue(TtsModel.MODEL_MULTILINGUAL);
        dialog.clickSave();

        assertThat(ttsService.models()).singleElement().satisfies(model -> {
            assertThat(model.getName()).isEqualTo("Local Chatterbox");
            assertThat(model.getModel()).isEqualTo(TtsModel.MODEL_MULTILINGUAL);
        });

        login();
        assertThat(ttsService.models()).isEmpty();
    }

    @Test
    void modelsPartListsTtsModelsAndActors() throws Exception {
        login();
        saveModel("Zeta", null);
        saveModel("alpha", null);
        Actor actor = new Actor();
        actor.setName("Joi");
        actor.setVoice("predefined:Emily.wav");
        ttsService.saveActor(actor);

        AIPart part = new AIPart();
        part.create();
        part.refresh();

        assertThat(rows(part.getTtsGrid())).extracting(TtsModel::getName).containsExactly("alpha", "Zeta");
        assertThat(rows(part.getActorGrid())).extracting(Actor::getName).containsExactly("Joi");
    }

    @Test
    void actorDialogSavesTheVoicesAndTheWordsOfTheClip() throws Exception {
        login();
        TtsService.Clip clip = ttsService.uploadClip("joi.wav", new byte[]{'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'A', 'V', 'E', 0}, "audio/wav");

        ActorDialog dialog = new ActorDialog();
        dialog.create();
        dialog.getNameField().setValue("Joi");
        dialog.getRoleEditor().setVoice("clip:" + clip.uuid());
        dialog.clickSave();

        Actor saved = ttsService.actors().getFirst();
        assertThat(saved.getName()).isEqualTo("Joi");
        assertThat(saved.getVoice()).isEqualTo("clip:" + clip.uuid());
        assertThat(ttsService.clips()).extracting(TtsService.Clip::uuid).contains(clip.uuid());

        ttsService.saveClipText(clip.uuid(), "Hello there.");
        assertThat(ttsService.clipText(clip.uuid())).isEqualTo("Hello there.");
    }

    @Test
    void testAndSpeakTakeTheGpuLockFirst() throws Exception {
        loginAdmin();
        Gpu gpu = new Gpu();
        gpu.setName(uniqueName("gpu"));
        gpu = gpuService.saveGpu(gpu);
        GpuStep load = new GpuStep();
        load.setName(uniqueName("load"));
        load.setUrl(serverUrl() + "/load-voice");
        load = gpuService.saveStep(load);
        GpuLock lock = new GpuLock();
        lock.setName(uniqueName("voice"));
        lock.setGpu(gpu);
        lock.setAcquireSteps(List.of(load));
        lock = gpuService.saveLock(lock);

        TtsModel model = saveModel("Chatterbox", lock.getUuid());

        ServerCheck.Result result = ttsService.test(model).get(30, TimeUnit.SECONDS);
        assertThat(result.modelLoaded()).isTrue();
        assertThat(calls).containsSubsequence("/load-voice", "/api/model-info");

        byte[] audio = ttsService.speak(model, "predefined:Emily.wav", "Hello.").get(30, TimeUnit.SECONDS);
        assertThat(audio).isNotEmpty();
        assertThat(calls).contains("/tts");
        assertThat(lastTtsBody).contains("\"text\":\"Hello.\"").contains("Emily.wav");
    }

    @Test
    void aUserCallsALocalServerOnlyWhenTheAppSettingsAllowIt() throws Exception {
        try {
            User user = login();
            // the fake server is on 127.0.0.1: marked local, but the administrator has not allowed it for users
            TtsModel model = saveModel("Mine", null);
            assertThat(ttsService.findModel(model.getId()).isLocal()).isFalse();
            assertThat(new TtsModelDialog(model)).satisfies(dialog -> {
                dialog.create();
                assertThat(dialog.getLocalBox().isVisible()).isFalse();
            });
            model.setLocal(true);
            TtsModel local = model;
            assertThat(ttsService.test(local)).failsWithin(30, TimeUnit.SECONDS).withThrowableThat().withMessageContaining("local");
            assertThat(calls).isEmpty();

            loginAdmin();
            AdminPart admin = new AdminPart();
            admin.create();
            admin.getAppSettingsPanel().getUsersMayUseLocalServers().setValue(true);
            admin.getAppSettingsPanel().save();
            assertThat(appSettingsService.get().usersMayUseLocalServers()).isTrue();

            loginAs(user);
            assertThat(ttsService.test(local).get(30, TimeUnit.SECONDS).modelLoaded()).isTrue();
            TtsModelDialog dialog = new TtsModelDialog(local);
            dialog.create();
            assertThat(dialog.getLocalBox().isVisible()).isTrue();
        } finally {
            loginAdmin();
            appSettingsService.save(JoiAppSettings.DEFAULT);
        }
    }

    @Test
    void onlyAdministratorsWriteTheAppSettings() throws Exception {
        login();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> appSettingsService.save(new JoiAppSettings(true)))
                .isInstanceOf(SecurityException.class);
        assertThat(appSettingsService.get()).isEqualTo(JoiAppSettings.DEFAULT);
    }

    @Test
    void onlyAdministratorsGetTheAdminPart() throws Exception {
        login();
        JoiWorkspace user = new JoiWorkspace();
        user.create();
        assertThat(user.getPart("admin")).isNull();
        // everybody has the trash: what they deleted can be restored
        assertThat(user.getPart("trash")).isInstanceOf(TrashPart.class);

        loginAdmin();
        // a real login does not copy the flag into the session's user: the workspace must ask the database
        currentUser.setAdmin(false);
        JoiWorkspace admin = new JoiWorkspace();
        admin.create();
        admin.refresh();
        assertThat(admin.getPart("admin")).isInstanceOf(AdminPart.class);
        assertThat(rows(((AdminPart) admin.getPart("admin")).getUsersGrid())).isNotEmpty();
        // the platform's backups, cleanup and sweep of files are in the administration, with the GPUs
        assertThat(admin.getPart("admin").getLLMChildren(false)).extracting(child -> child.getClass().getSimpleName())
                .contains("GpuPanel", "DatabaseBackupPanel", "CleanupPanel", "ResourceGarbagePanel");

        // no tab of its own: the button under the tabs shows it, and the state remembers it
        assertThat(admin.getActivePart()).isNotInstanceOf(AdminPart.class);
        admin.openAdmin();
        assertThat(admin.getActivePart()).isInstanceOf(AdminPart.class);
        assertThat(admin.storeState().get("tab").getAsString()).isEqualTo("admin");
    }

    @Test
    void thePlatformTextsSayJoi() {
        assertThat(loc.getValue(L.LABEL_USED_OUTSIDE_MARGINALIA)).isEqualTo("Also used outside Joi");
        assertThat(loc.getValue(L.HELP_GPU_USED_OUTSIDE)).contains("Joi").doesNotContain("Marginalia");
    }
}
