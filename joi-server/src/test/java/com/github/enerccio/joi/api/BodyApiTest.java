package com.github.enerccio.joi.api;

import com.github.enerccio.joi.domain.animations.AnimationStep;
import com.github.enerccio.joi.domain.animations.VrmaTestFiles;
import com.github.enerccio.joi.domain.bodies.ModelFiles;
import com.github.enerccio.joi.domain.bodies.UnityFixtures;
import com.github.enerccio.joi.domain.model.impl.Animation;
import com.github.enerccio.joi.domain.model.impl.AnimationPack;
import com.github.enerccio.joi.domain.model.impl.Body;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.Model;
import com.github.enerccio.joi.domain.service.AccessTokenService;
import com.github.enerccio.joi.domain.service.AnimationPackService;
import com.github.enerccio.joi.domain.service.AnimationService;
import com.github.enerccio.joi.domain.service.BodyService;
import com.github.enerccio.joi.domain.service.JoiService;
import com.github.enerccio.joi.domain.service.JoiaService;
import com.github.enerccio.joi.domain.service.JoimService;
import com.github.enerccio.joi.domain.service.ModelService;
import com.github.enerccio.joi.protocol.joia.JoiaFile;
import com.github.enerccio.joi.protocol.joia.JoiaMetadata;
import com.github.enerccio.joi.ui.dialogs.BodyDialog;
import com.github.enerccio.marginalia.test.PlatformTestBase;
import com.google.gson.JsonObject;
import com.networknt.schema.InputFormat;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SpecVersion;
import com.vaadin.flow.component.AbstractField;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.UI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A body's animations: chosen in its dialog (all of the library by default) and written into its {@code .joia} on
 * Exit, in place of the one before; the API's body call (her models, the state of their parts, her animations) and the
 * downloads of the {@code .joim} and the {@code .joia}, cached by their hash.
 */
class BodyApiTest extends PlatformTestBase {

    static final String BASE = "https://github.com/Enerccio/joi/protocol/v1/";
    static final String HOST = "http://joi.test:8080";

    @Autowired
    private JoiApi api;
    @Autowired
    private AccessTokenService accessTokenService;
    @Autowired
    private JoiService joiService;
    @Autowired
    private BodyService bodyService;
    @Autowired
    private ModelService modelService;
    @Autowired
    private JoimService joimService;
    @Autowired
    private JoiaService joiaService;
    @Autowired
    private AnimationService animationService;
    @Autowired
    private AnimationPackService packService;

    private final JsonSchemaFactory schemas = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012,
            builder -> builder.schemaMappers(m -> m.mapPrefix(BASE, "classpath:joi-protocol/schemas/")));

    /** Held here: the current UI is only weakly referenced. */
    private UI ui;
    private Joi joi;
    private String token;
    private Model ryuu;
    private Animation wave;
    private Animation bow;
    private AnimationPack greeting;
    private AnimationPack bowing;

    @BeforeEach
    void setUp() throws Exception {
        ui = new UI();
        UI.setCurrent(ui);
        login();
        joi = joiService.create("Mira");
        token = accessTokenService.create("test", null).text();
        ryuu = vrm("Ryuu");
        ryuu = modelService.find(ryuu.getId());
        ryuu.getCapabilityNames().put("key:Headgear/Hide Halo", "golden halo");
        ryuu.setAppearance("Silver hair.");
        ryuu.getPartShown().put("key:Headgear/Hide Halo", "A golden halo floats above you.");
        ryuu = modelService.save(ryuu);

        wave = clip("wave.vrma", "Wave");
        bow = clip("bow.vrma", "Bow");
        greeting = new AnimationPack();
        greeting.setName("Greeting");
        greeting.setDescription("You wave.");
        greeting.setStepList(List.of(new AnimationStep(wave.getUuid(), false, 1, true, false)));
        greeting = packService.save(greeting);
        bowing = new AnimationPack();
        bowing.setName("Bowing");
        bowing = packService.addClips(packService.save(bowing), List.of(bow));
    }

    @AfterEach
    void tearDown() {
        UI.setCurrent(null);
        ui = null;
    }

    private Model vrm(String name) throws Exception {
        Path dir = Files.createTempDirectory("vrm");
        File source = UnityFixtures.glb(dir.resolve("m.vrm").toFile(), """
                {"nodes": [{"name": "Headgear", "mesh": 0}],
                 "meshes": [{"extras": {"targetNames": ["Hide Halo"]}, "weights": [0], "primitives": [{}]}],
                 "extensions": {"VRMC_vrm": {"humanoid": {"humanBones": {"hips": {}}},
                   "expressions": {"preset": {"happy": {"morphTargetBinds": [{}]}}}}}}
                """);
        File file = ModelFiles.uploadFile(modelService.uploadFolder(), name + ".vrm");
        Files.copy(source.toPath(), file.toPath());
        return modelService.attach(new Model(), file, name + ".vrm");
    }

    private Animation clip(String fileName, String name) throws Exception {
        Animation clip = animationService.add(VrmaTestFiles.wave(ModelFiles.uploadFile(modelService.uploadFolder(), fileName)), fileName);
        clip.setName(name);
        return animationService.save(clip);
    }

    private JoiApiTest.Answer get(String path, String... headers) throws Exception {
        Map<String, String> map = new HashMap<>();
        map.put("authorization", "Bearer " + token);
        for (int i = 0; i + 1 < headers.length; i += 2) {
            map.put(headers[i].toLowerCase(), headers[i + 1]);
        }
        JoiApiTest.Answer answer = new JoiApiTest.Answer();
        api.handle(new ApiCall("GET", path.startsWith(HOST) ? path.substring(HOST.length()) : path, Map.of(), map, new byte[0], HOST), answer);
        if (answer.body != null && answer.contentType.startsWith("application/json")) {
            String schema = answer.status == 200 ? "BodyInfo" : "ApiError";
            assertThat(schemas.getSchema(SchemaLocation.of(BASE + schema + ".schema.json")).validate(answer.body, InputFormat.JSON))
                    .as(answer.body).isEmpty();
        }
        return answer;
    }

    /** The user checks or unchecks a box, or picks animations, as in the browser. */
    private static <V> void pick(AbstractField<?, V> field, V value) {
        V before = field.getValue();
        field.setValue(value);
        ComponentUtil.fireEvent(field, new AbstractField.ComponentValueChangeEvent<>(field, field, before, true));
    }

    @Test
    void theBodysAnimationsAreWrittenOnExitAndServedWithItsModel() throws Exception {
        assertThat(get("/api/v1/jois/" + joi.getUuid() + "/body").json().get("code").getAsString()).isEqualTo("no_body");

        BodyDialog dialog = new BodyDialog(joi, null);
        dialog.create();
        // all of the library by default
        assertThat(dialog.getAllAnimations().getValue()).isTrue();
        assertThat(dialog.getAnimationSelect().getValue()).containsExactlyInAnyOrder(greeting.getUuid(), bowing.getUuid());
        dialog.getNameField().setValue("Street");
        dialog.getModelSelect().setValue(ryuu.getId());
        Body body = bodyService.find(dialog.getBody().getId());
        assertThat(body.animationUuids()).isNull();
        assertThat(bodyService.animations(body)).extracting(AnimationPack::getName).containsExactly("Bowing", "Greeting");

        // only the greeting fits this body
        pick(dialog.getAllAnimations(), false);
        pick(dialog.getAnimationSelect(), Set.of(greeting.getUuid()));
        body = bodyService.find(body.getId());
        assertThat(body.animationUuids()).containsExactly(greeting.getUuid());
        assertThat(joiaService.needsBodyExport(body)).isTrue();
        dialog.exitForTest();
        body = bodyService.find(body.getId());
        File first = bodyService.animationsFile(body);
        assertThat(first).exists();
        assertThat(joiaService.needsBodyExport(body)).isFalse();
        JoiaMetadata meta = JoiaFile.read(first, (index, clip) -> null).meta();
        assertThat(meta.animations()).extracting(JoiaMetadata.Animation::name).containsExactly("Greeting");
        assertThat(meta.clips()).extracting(JoiaMetadata.Clip::uuid).containsExactly(wave.getUuid());

        // the body call: the model with its .joim, the parts, the animations with their .joia
        ryuu = joimService.export(modelService.find(ryuu.getId()), bytes -> {
        });
        JsonObject info = get("/api/v1/jois/" + joi.getUuid() + "/body").json();
        assertThat(info.get("id").getAsString()).isEqualTo(body.getUuid());
        assertThat(info.get("name").getAsString()).isEqualTo("Street");
        assertThat(info.get("appearance").getAsString()).isEqualTo("Silver hair.");
        JsonObject model = info.getAsJsonObject("model");
        assertThat(model.get("id").getAsString()).isEqualTo(ryuu.getUuid());
        JsonObject joim = model.getAsJsonObject("joim");
        assertThat(joim.get("current").getAsBoolean()).isTrue();
        assertThat(joim.get("size").getAsLong()).isEqualTo(modelService.exportFile(ryuu).length());
        assertThat(info.get("lookingGlassModel").isJsonNull()).isTrue();
        JsonObject part = info.getAsJsonArray("parts").get(0).getAsJsonObject();
        assertThat(part.get("name").getAsString()).isEqualTo("golden halo");
        assertThat(part.get("state").getAsString()).isEqualTo("shown");
        assertThat(part.get("default").getAsBoolean()).isTrue();
        JsonObject animations = info.getAsJsonObject("animations");
        assertThat(animations.get("all").getAsBoolean()).isFalse();
        assertThat(animations.getAsJsonArray("animations")).hasSize(1);
        assertThat(animations.getAsJsonArray("animations").get(0).getAsJsonObject().get("name").getAsString()).isEqualTo("Greeting");
        JsonObject joia = animations.getAsJsonObject("joia");
        assertThat(joia.get("current").getAsBoolean()).isTrue();

        // the downloads, cached by their hash
        JoiApiTest.Answer download = get(joia.get("url").getAsString());
        assertThat(download.status).isEqualTo(200);
        assertThat(download.contentType).isEqualTo(JoiaFile.CONTENT_TYPE);
        assertThat(download.file).isEqualTo(first);
        assertThat(download.headers.get("ETag")).isEqualTo("\"" + joia.get("sha256").getAsString() + "\"");
        assertThat(download.headers.get("Content-Disposition")).contains("Street.joia");
        JoiApiTest.Answer cached = get(joia.get("url").getAsString(), "If-None-Match", download.headers.get("ETag"));
        assertThat(cached.status).isEqualTo(304);
        assertThat(cached.file).isNull();
        JoiApiTest.Answer model1 = get(joim.get("url").getAsString());
        assertThat(model1.status).isEqualTo(200);
        assertThat(model1.file).isEqualTo(modelService.exportFile(ryuu));

        // the greeting changed: the .joia is not current until the next Exit, which writes it in place of the first
        packService.addClips(packService.find(greeting.getId()), List.of(bow));
        assertThat(get("/api/v1/jois/" + joi.getUuid() + "/body").json().getAsJsonObject("animations").getAsJsonObject("joia")
                .get("current").getAsBoolean()).isFalse();
        BodyDialog again = new BodyDialog(joi, body);
        again.create();
        assertThat(again.getAllAnimations().getValue()).isFalse();
        assertThat(again.getAnimationSelect().getValue()).containsExactly(greeting.getUuid());
        Long before = body.getAnimationsResourceId();
        again.exitForTest();
        body = bodyService.find(body.getId());
        assertThat(body.getAnimationsResourceId()).isNotEqualTo(before);
        assertThat(JoiaFile.read(bodyService.animationsFile(body), (index, clip) -> null).meta().clips())
                .extracting(JoiaMetadata.Clip::uuid).containsExactly(wave.getUuid(), bow.getUuid());
        JsonObject now = get("/api/v1/jois/" + joi.getUuid() + "/body").json().getAsJsonObject("animations").getAsJsonObject("joia");
        assertThat(now.get("current").getAsBoolean()).isTrue();
        assertThat(now.get("sha256").getAsString()).isNotEqualTo(joia.get("sha256").getAsString());
        assertThat(get(joia.get("url").getAsString(), "If-None-Match", download.headers.get("ETag")).status).isEqualTo(200);

        // none chosen: no .joia
        BodyDialog none = new BodyDialog(joi, body);
        none.create();
        pick(none.getAnimationSelect(), Set.of());
        none.exitForTest();
        body = bodyService.find(body.getId());
        assertThat(bodyService.animationsFile(body)).isNull();
        assertThat(get("/api/v1/jois/" + joi.getUuid() + "/body").json().getAsJsonObject("animations").get("joia").isJsonNull()).isTrue();
        assertThat(get(joia.get("url").getAsString()).status).isEqualTo(404);
    }

    @Test
    void anotherUserGetsNeitherTheBodyNorItsFiles() throws Exception {
        Body body = bodyService.create(joi, "Street");
        body.setModel(ryuu);
        body = joiaService.exportBody(bodyService.save(body));
        ryuu = joimService.export(modelService.find(ryuu.getId()), bytes -> {
        });
        assertThat(bodyService.animationsFile(body)).exists();

        loginAs(createUser());
        token = accessTokenService.create("theirs", null).text();
        assertThat(get("/api/v1/jois/" + joi.getUuid() + "/body").status).isEqualTo(404);
        assertThat(get("/api/v1/bodies/" + body.getUuid() + "/joia").status).isEqualTo(404);
        assertThat(get("/api/v1/models/" + ryuu.getUuid() + "/joim").status).isEqualTo(404);
        assertThat(new String(get("/api/v1/models/" + ryuu.getUuid() + "/joim").body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8))
                .contains("not_found");
    }
}
