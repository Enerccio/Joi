package com.github.enerccio.joi.ui.dialogs;

import com.github.enerccio.joi.domain.bodies.BodyProposal;
import com.github.enerccio.joi.domain.bodies.ModelFiles;
import com.github.enerccio.joi.domain.bodies.UnityFixtures;
import com.github.enerccio.joi.domain.collections.ChatMessageType;
import com.github.enerccio.joi.domain.collections.ProposalStatus;
import com.github.enerccio.joi.domain.events.JoiEvents;
import com.github.enerccio.joi.domain.model.impl.Body;
import com.github.enerccio.joi.domain.model.impl.ChatMessage;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.Model;
import com.github.enerccio.joi.domain.model.impl.QueuedEvent;
import com.github.enerccio.joi.domain.service.BodySelfImageService;
import com.github.enerccio.joi.domain.service.BodyService;
import com.github.enerccio.joi.domain.service.ChatMessageService;
import com.github.enerccio.joi.domain.service.JoiService;
import com.github.enerccio.joi.domain.service.ModelService;
import com.github.enerccio.joi.domain.service.QueuedEventService;
import com.github.enerccio.marginalia.domain.model.impl.OpenAICompatible;
import com.github.enerccio.marginalia.domain.model.impl.Resource;
import com.github.enerccio.marginalia.domain.service.ResourceService;
import com.github.enerccio.marginalia.test.PlatformTestBase;
import com.github.enerccio.marginalia.test.llm.MockLLMResponse;
import com.vaadin.flow.component.UI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Her self-image: the snapshots and the user's images are stored on the body (a new set replaces the old one), a
 * multimodal model gets them with the notes and the toggles and proposes the description and the names, and the
 * reviewed proposal becomes the body's description and the model's names (she is told when she is in it).
 */
class BodySelfImageTest extends PlatformTestBase {

    @Autowired
    private BodyService bodyService;
    @Autowired
    private BodySelfImageService selfImageService;
    @Autowired
    private ModelService modelService;
    @Autowired
    private JoiService joiService;
    @Autowired
    private ChatMessageService chatMessageService;
    @Autowired
    private QueuedEventService queuedEventService;
    @Autowired
    private ResourceService resourceService;

    private UI ui;
    private Joi joi;
    private Model model;
    private Body body;

    @BeforeEach
    void setUp() throws Exception {
        ui = new UI();
        UI.setCurrent(ui);
        login();
        joi = joiService.create("Mira");
        Path dir = Files.createTempDirectory("vrm");
        File source = UnityFixtures.glb(dir.resolve("m.vrm").toFile(), """
                {"nodes": [{"name": "Headgear", "mesh": 0}],
                 "meshes": [{"extras": {"targetNames": ["Hide Halo"]}, "weights": [0], "primitives": [{}]}],
                 "extensions": {"VRMC_vrm": {"humanoid": {"humanBones": {"hips": {}}},
                   "expressions": {"preset": {"happy": {"morphTargetBinds": [{}]}}}}}}
                """);
        File file = ModelFiles.uploadFile(modelService.uploadFolder(), "Ryuu.vrm");
        Files.copy(source.toPath(), file.toPath());
        model = modelService.attach(new Model(), file, "Ryuu.vrm");
        body = bodyService.create(joi, "Street");
        body.setModel(model);
        body = bodyService.save(body);
    }

    @AfterEach
    void tearDown() {
        UI.setCurrent(null);
        ui = null;
    }

    private static byte[] image(String format, int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, format, out);
        return out.toByteArray();
    }

    private BodyDialog dialog() throws Exception {
        BodyDialog dialog = new BodyDialog(joi, bodyService.find(body.getId()));
        dialog.create();
        return dialog;
    }

    private void visionModel() throws Exception {
        OpenAICompatible ai = createAI();
        ai.setMultimodal(true);
        ai = (OpenAICompatible) aiService.save(ai);
        joi = joiService.find(joi.getId());
        joi.setReasoningModel(ai.getId());
        joi = joiService.save(joi);
    }

    private Body awaitProposal() throws Exception {
        long end = System.currentTimeMillis() + 20_000;
        while (selfImageService.isRunning(body) && System.currentTimeMillis() < end) {
            Thread.sleep(50);
        }
        assertThat(selfImageService.isRunning(body)).isFalse();
        return bodyService.find(body.getId());
    }

    @Test
    void snapshotsAreStoredForTheModelAndANewSetReplacesTheOld() throws Exception {
        BodyDialog dialog = dialog();
        Map<String, byte[]> shots = new LinkedHashMap<>();
        shots.put("face-front", image("jpg", 8, 8));
        shots.put("full-front", image("jpg", 6, 8));
        dialog.snapshotsTakenForTest(shots);

        Body stored = bodyService.find(body.getId());
        assertThat(stored.getSnapshots()).containsOnlyKeys("full-front", "face-front");
        assertThat(stored.getSnapshots().keySet()).containsExactly("full-front", "face-front");
        assertThat(stored.getSnapshotsHash()).isEqualTo(bodyService.modelHash(stored)).isNotNull();
        String first = stored.getSnapshots().get("full-front");
        assertThat(bodyService.imageFile(stored, first)).exists();

        dialog.snapshotsTakenForTest(Map.of("full-front", image("jpg", 6, 8)));
        stored = bodyService.find(body.getId());
        assertThat(stored.getSnapshots()).containsOnlyKeys("full-front");
        assertThat(resourceService.find(Long.valueOf(first)).isDeleted()).isTrue();
        assertThat(bodyService.imageFile(stored, first)).isNull();
    }

    @Test
    void imagesOfTheUserAreKeptAndOtherFilesRefused() throws Exception {
        BodyDialog dialog = dialog();
        File png = ModelFiles.uploadFile(modelService.uploadFolder(), "store.png");
        Files.write(png.toPath(), image("png", 4, 4));
        dialog.referenceUploadedForTest(png, "store.png");
        File text = ModelFiles.uploadFile(modelService.uploadFolder(), "notes.png");
        Files.writeString(text.toPath(), "not an image");
        dialog.referenceUploadedForTest(text, "notes.png");

        Body stored = bodyService.find(body.getId());
        assertThat(stored.getReferences().values()).containsExactly("store.png");
        assertThat(text).doesNotExist();
        Resource resource = resourceService.find(Long.valueOf(stored.getReferences().keySet().iterator().next()));
        assertThat(resource.getMimeType()).isEqualTo("image/png");

        bodyService.removeReference(stored, String.valueOf(resource.getId()));
        assertThat(bodyService.find(body.getId()).getReferences()).isEmpty();
    }

    @Test
    void withoutAModelThatTakesImagesNothingIsAsked() throws Exception {
        bodyService.storeSnapshots(body, Map.of("full-front", image("jpg", 4, 4)));
        assertThatThrownBy(() -> selfImageService.propose(body, null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theProposalIsReviewedAndUsed() throws Exception {
        visionModel();
        body = bodyService.storeSnapshots(body, Map.of("full-front", image("jpg", 4, 4), "face-front", image("jpg", 4, 4)));
        File big = ModelFiles.uploadFile(modelService.uploadFolder(), "photo.png");
        Files.write(big.toPath(), image("png", 3000, 1000));
        body = bodyService.addReference(body, big, "photo.png");
        body.setNotes("The halo glows when she is happy.");
        body = bodyService.save(body);
        ChatMessage message = new ChatMessage();
        message.setJoi(joi);
        message.setType(ChatMessageType.USER);
        message.setSentAt(new Date());
        message.setInput("hi");
        chatMessageService.save(message);

        llm.enqueue(MockLLMResponse.text("""
                Here you go:
                ```json
                {"description": "You have long silver hair and a golden halo.",
                 "toggles": {"2": "golden halo", "mesh:Nothing": "ghost", "Headgear": "headgear"}}
                ```"""));
        selfImageService.propose(body, null);
        Body proposed = awaitProposal();
        assertThat(proposed.getProposalStatus()).isEqualTo(ProposalStatus.DONE);
        BodyProposal proposal = BodyProposal.fromJson(proposed.getProposal());
        assertThat(proposal.description()).isEqualTo("You have long silver hair and a golden halo.");
        assertThat(proposal.toggles()).containsOnlyKeys("key:Headgear/Hide Halo", "mesh:Headgear");

        String request = llm.lastCompletionRequest().getRawBody();
        assertThat(request).contains("image_url").contains("The halo glows when she is happy.")
                .contains("2 | Headgear / Hide Halo | halo | shown")
                .contains("full front", "face front").contains("Street").contains("Mira");
        // two snapshots and the photo, scaled down to the longest side the model gets
        assertThat(request.split("\"type\":\"image_url\"", -1)).hasSize(4);
        assertThat(request).doesNotContain("data:image/png");

        BodyDialog dialog = dialog();
        assertThat(dialog.getProposedDescription().getValue()).isEqualTo("You have long silver hair and a golden halo.");
        // "headgear" is its name already: only the halo is offered
        assertThat(dialog.getToggleChoices()).containsOnlyKeys("key:Headgear/Hide Halo");
        dialog.getProposedDescription().setValue("You have long silver hair and a golden halo that glows.");
        dialog.useProposalForTest();

        Body used = bodyService.find(body.getId());
        assertThat(used.getDescription()).isEqualTo("You have long silver hair and a golden halo that glows.");
        assertThat(used.getDescribedHash()).isEqualTo(used.getSnapshotsHash());
        assertThat(used.getProposal()).isNull();
        assertThat(modelService.find(model.getId()).getCapabilityNames()).containsEntry("key:Headgear/Hide Halo", "golden halo");
        assertThat(dialog.getDescription().getValue()).isEqualTo(used.getDescription());
        List<QueuedEvent> events = queuedEventService.findWaiting(joi);
        assertThat(events).extracting(QueuedEvent::getKind).contains(JoiEvents.BODY_DESCRIBED);
        assertThat(bodyService.prompt(joi)).contains("golden halo (shown)").contains("a golden halo that glows");
    }

    @Test
    void aFailedProposalSaysWhy() throws Exception {
        visionModel();
        body = bodyService.storeSnapshots(body, Map.of("full-front", image("jpg", 4, 4)));
        llm.enqueue(MockLLMResponse.text("I can't see anything."));
        selfImageService.propose(body, null);
        Body failed = awaitProposal();
        assertThat(failed.getProposalStatus()).isEqualTo(ProposalStatus.FAILED);
        assertThat(failed.getProposalError()).contains("JSON");
    }
}
