package com.github.enerccio.joi.ui.dialogs;

import com.github.enerccio.joi.domain.bodies.ModelFiles;
import com.github.enerccio.joi.domain.bodies.UnityFixtures;
import com.github.enerccio.joi.domain.collections.ChatMessageType;
import com.github.enerccio.joi.domain.events.JoiEvents;
import com.github.enerccio.joi.domain.model.impl.Body;
import com.github.enerccio.joi.domain.model.impl.ChatMessage;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.Model;
import com.github.enerccio.joi.domain.model.impl.QueuedEvent;
import com.github.enerccio.joi.domain.service.BodyService;
import com.github.enerccio.joi.domain.service.ChatMessageService;
import com.github.enerccio.joi.domain.service.JoiService;
import com.github.enerccio.joi.domain.service.ModelService;
import com.github.enerccio.joi.domain.service.QueuedEventService;
import com.github.enerccio.marginalia.test.PlatformTestBase;
import com.vaadin.flow.component.UI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bodies: the dialog stores a new body once it has a name or a model and offers only models with a VRM; her first body
 * is the one she is in; switching and deleting tell her once she has chatted; her prompt describes the body she is in.
 */
class BodyDialogTest extends PlatformTestBase {

    @Autowired
    private BodyService bodyService;
    @Autowired
    private ModelService modelService;
    @Autowired
    private JoiService joiService;
    @Autowired
    private ChatMessageService chatMessageService;
    @Autowired
    private QueuedEventService queuedEventService;

    private UI ui;
    private Joi joi;

    @BeforeEach
    void setUp() throws Exception {
        ui = new UI();
        UI.setCurrent(ui);
        login();
        joi = joiService.create("Mira");
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
                   "expressions": {"preset": {"happy": {"morphTargetBinds": [{}]}, "blink": {"morphTargetBinds": [{}]}}}}}}
                """);
        File file = ModelFiles.uploadFile(modelService.uploadFolder(), name + ".vrm");
        Files.copy(source.toPath(), file.toPath());
        return modelService.attach(new Model(), file, name + ".vrm");
    }

    private void chatted() throws Exception {
        ChatMessage message = new ChatMessage();
        message.setJoi(joi);
        message.setType(ChatMessageType.USER);
        message.setSentAt(new Date());
        message.setInput("hi");
        chatMessageService.save(message);
    }

    @Test
    void aNewBodyIsStoredOnceItHasANameAndOffersOnlyModelsWithAVrm() throws Exception {
        Model ryuu = vrm("Ryuu");
        ryuu = modelService.find(ryuu.getId());
        ryuu.setAppearance("Silver hair, a black jacket.");
        ryuu = modelService.save(ryuu);
        Model fbx = new Model();
        fbx.setName("Unconverted");
        modelService.save(fbx);

        BodyDialog dialog = new BodyDialog(joi, null);
        dialog.create();
        assertThat(dialog.getModelSelect().getListDataView().getItems().toList()).containsExactly(ryuu.getId());
        dialog.getNameField().setValue(" ");
        assertThat(bodyService.findForJoi(joi)).isEmpty();

        dialog.getNameField().setValue("Street");
        dialog.getModelSelect().setValue(ryuu.getId());
        // how she looks is the model's, shown read only
        assertThat(dialog.getDescription().getValue()).isEqualTo("Silver hair, a black jacket.");
        Body body = bodyService.find(dialog.getBody().getId());
        assertThat(body.getName()).isEqualTo("Street");
        assertThat(body.getModel().getId()).isEqualTo(ryuu.getId());
        assertThat(body.getLookingGlassModel()).isNull();
        assertThat(body.isActive()).isTrue();
        assertThat(bodyService.findByModel(modelService.find(ryuu.getId()))).extracting(Body::getId).containsExactly(body.getId());
    }

    @Test
    void herFirstBodyIsActiveAndSwitchingTellsHerOnceSheHasChatted() throws Exception {
        Body street = bodyService.create(joi, "Street");
        Body armor = bodyService.create(joi, "Armor");
        assertThat(bodyService.active(joi).getId()).isEqualTo(street.getId());

        assertThat(bodyService.activate(joi, armor)).isEmpty();
        assertThat(bodyService.active(joi).getId()).isEqualTo(armor.getId());
        assertThat(bodyService.find(street.getId()).isActive()).isFalse();

        chatted();
        assertThat(bodyService.activate(joi, armor)).isEmpty();
        List<QueuedEvent> events = bodyService.activate(joi, bodyService.find(street.getId()));
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().getKind()).isEqualTo(JoiEvents.BODY_SWITCHED);
        assertThat(events.getFirst().getText()).isEqualTo("You were switched from your body Armor to Street.");

        events = bodyService.deleteBody(bodyService.find(street.getId()));
        assertThat(events).extracting(QueuedEvent::getText).containsExactly("You were taken out of your body: nobody can see you for now.");
        assertThat(bodyService.active(joi)).isNull();
        assertThat(bodyService.findForJoi(joi)).extracting(Body::getName).containsExactly("Armor");
        assertThat(queuedEventService.findWaiting(joi)).hasSize(2);
    }

    @Test
    void herFirstBodyTellsHerOnceSheHasChatted() throws Exception {
        chatted();
        Body street = bodyService.create(joi, "Street");
        assertThat(street.isActive()).isTrue();
        assertThat(queuedEventService.findWaiting(joi)).extracting(QueuedEvent::getText)
                .containsExactly("You have a body now: Street.");
        bodyService.create(joi, "Armor");
        assertThat(queuedEventService.findWaiting(joi)).hasSize(1);
    }

    @Test
    void herPromptDescribesTheBodySheIsIn() throws Exception {
        assertThat(bodyService.prompt(joi)).isNull();
        Body bare = bodyService.create(joi, "Bare");
        assertThat(bodyService.prompt(joi)).isEqualTo("You are in your body \"Bare\". Nobody has described how you look in it yet.");
        bodyService.deleteBody(bare);
        Model ryuu = vrm("Ryuu");
        ryuu = modelService.find(ryuu.getId());
        ryuu.getCapabilityNames().put("key:Headgear/Hide Halo", "golden halo");
        ryuu.setAppearance("Silver hair, a black jacket & a \"halo\" that's gold.");
        ryuu.getPartShown().put("key:Headgear/Hide Halo", "A golden halo floats above you.");
        ryuu.getPartHidden().put("key:Headgear/Hide Halo", "Nothing floats above you.");
        modelService.save(ryuu);

        Body street = bodyService.create(joi, "Street");
        street.setModel(ryuu);
        bodyService.save(street);
        bodyService.create(joi, "Armor");

        assertThat(bodyService.prompt(joi)).isEqualTo("""
                You are in your body "Street".
                Silver hair, a black jacket & a "halo" that's gold.
                Expressions your face can show: happy.
                Parts you can switch: golden halo (shown).
                Your other bodies: Armor.""");
    }
}
