package com.github.enerccio.joi.ui.dialogs;

import com.github.enerccio.joi.domain.bodies.Appearance;
import com.github.enerccio.joi.domain.bodies.AppearanceProposal;
import com.github.enerccio.joi.domain.bodies.DescribedPart;
import com.github.enerccio.joi.domain.bodies.ModelCapabilities;
import com.github.enerccio.joi.domain.bodies.ToggleGroup;
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
import com.github.enerccio.joi.domain.model.impl.settings.JoiUserSetting;
import com.github.enerccio.joi.domain.service.AppearanceService;
import com.github.enerccio.joi.domain.service.BodyService;
import com.github.enerccio.joi.domain.service.ChatMessageService;
import com.github.enerccio.joi.domain.service.JoiService;
import com.github.enerccio.joi.domain.service.ModelService;
import com.github.enerccio.joi.domain.service.PersonaService;
import com.github.enerccio.joi.domain.service.QueuedEventService;
import com.github.enerccio.joi.ui.bodies.AppearancePanel;
import com.github.enerccio.marginalia.domain.model.impl.OpenAICompatible;
import com.github.enerccio.marginalia.domain.model.impl.Resource;
import com.github.enerccio.marginalia.domain.service.ResourceService;
import com.github.enerccio.marginalia.domain.service.SettingService;
import com.github.enerccio.marginalia.test.PlatformTestBase;
import com.github.enerccio.marginalia.test.llm.MockLLMRequest;
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
 * How she looks in a model: the snapshots (the whole model and the close-ups of the toggles described apart) and the
 * user's images are stored on the model, a multimodal model describes what never changes and then each toggle from its
 * close-ups, and the reviewed proposal becomes the model's; a body puts it together for its state and the assistant in
 * it is told.
 */
class ModelAppearanceTest extends PlatformTestBase {

    private static final String HALO = "key:Headgear/Hide Halo";

    @Autowired
    private BodyService bodyService;
    @Autowired
    private AppearanceService appearanceService;
    @Autowired
    private ModelService modelService;
    @Autowired
    private JoiService joiService;
    @Autowired
    private PersonaService personaService;
    @Autowired
    private SettingService settingService;
    @Autowired
    private ChatMessageService chatMessageService;
    @Autowired
    private QueuedEventService queuedEventService;
    @Autowired
    private ResourceService resourceService;

    private UI ui;
    private Joi joi;
    private Model model;

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

    private AppearancePanel panel() throws Exception {
        ModelDialog dialog = new ModelDialog(modelService.find(model.getId()));
        dialog.create();
        return dialog.getAppearancePanel();
    }

    private void visionModel() throws Exception {
        OpenAICompatible ai = createAI();
        ai.setMultimodal(true);
        ai = (OpenAICompatible) aiService.save(ai);
        JoiUserSetting setting = personaService.getUserSetting();
        setting.setReasoningModel(ai.getId());
        settingService.save(setting);
    }

    private Model awaitProposal() throws Exception {
        long end = System.currentTimeMillis() + 20_000;
        while (appearanceService.isRunning(model) && System.currentTimeMillis() < end) {
            Thread.sleep(50);
        }
        assertThat(appearanceService.isRunning(model)).isFalse();
        return modelService.find(model.getId());
    }

    @Test
    void theShotsAndTheCloseUpsOfTheDescribedTogglesAreStoredAndANewSetReplacesTheOld() throws Exception {
        AppearancePanel panel = panel();
        Map<String, byte[]> shots = new LinkedHashMap<>();
        shots.put("face-front", image("jpg", 8, 8));
        shots.put("full-front", image("jpg", 6, 8));
        // the shape key toggles are described apart until the user chooses: the halo is part 0
        shots.put("part-0-0", image("jpg", 4, 4));
        shots.put("part-0-1", image("jpg", 4, 4));
        panel.snapshotsTakenForTest(shots);

        Model stored = modelService.find(model.getId());
        assertThat(stored.getSnapshots().keySet()).containsExactly("full-front", "face-front", "part:" + HALO + ":shown", "part:" + HALO + ":hidden");
        assertThat(stored.getSnapshotsHash()).isEqualTo(modelService.bodyHash(stored)).isNotNull();
        String first = stored.getSnapshots().get("full-front");
        assertThat(modelService.imageFile(stored, first)).exists();

        panel.snapshotsTakenForTest(Map.of("full-front", image("jpg", 6, 8)));
        stored = modelService.find(model.getId());
        assertThat(stored.getSnapshots()).containsOnlyKeys("full-front");
        assertThat(resourceService.find(Long.valueOf(first)).isDeleted()).isTrue();
        assertThat(modelService.imageFile(stored, first)).isNull();
    }

    @Test
    void imagesOfTheUserAreKeptAndOtherFilesRefused() throws Exception {
        AppearancePanel panel = panel();
        File png = ModelFiles.uploadFile(modelService.uploadFolder(), "store.png");
        Files.write(png.toPath(), image("png", 4, 4));
        panel.referenceUploadedForTest(png, "store.png");
        File text = ModelFiles.uploadFile(modelService.uploadFolder(), "notes.png");
        Files.writeString(text.toPath(), "not an image");
        panel.referenceUploadedForTest(text, "notes.png");

        Model stored = modelService.find(model.getId());
        assertThat(stored.getReferences().values()).containsExactly("store.png");
        assertThat(text).doesNotExist();
        Resource resource = resourceService.find(Long.valueOf(stored.getReferences().keySet().iterator().next()));
        assertThat(resource.getMimeType()).isEqualTo("image/png");

        modelService.removeReference(stored, String.valueOf(resource.getId()));
        assertThat(modelService.find(model.getId()).getReferences()).isEmpty();
    }

    @Test
    void withoutAModelThatTakesImagesNothingIsAsked() throws Exception {
        modelService.storeSnapshots(model, Map.of("full-front", image("jpg", 4, 4)));
        assertThatThrownBy(() -> appearanceService.propose(model, null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theWholeModelThenEachPartIsDescribedReviewedAndUsed() throws Exception {
        visionModel();
        model = modelService.storeSnapshots(model, Map.of("full-front", image("jpg", 4, 4), "face-front", image("jpg", 4, 4),
                "part:" + HALO + ":shown", image("jpg", 4, 4), "part:" + HALO + ":hidden", image("jpg", 4, 4)));
        File big = ModelFiles.uploadFile(modelService.uploadFolder(), "photo.png");
        Files.write(big.toPath(), image("png", 3000, 1000));
        model = modelService.addReference(model, big, "photo.png");
        model.setNotes("The halo glows when she is happy.");
        model = modelService.save(model);
        Body street = bodyService.create(joi, "Street");
        street.setModel(model);
        bodyService.save(street);
        ChatMessage message = new ChatMessage();
        message.setJoi(joi);
        message.setType(ChatMessageType.USER);
        message.setSentAt(new Date());
        message.setInput("hi");
        chatMessageService.save(message);

        llm.enqueue(MockLLMResponse.text("""
                ```json
                {"description": "You have long silver hair and a black jacket.",
                 "toggles": {"2": "golden halo", "mesh:Nothing": "ghost", "Headgear": "headgear"}}
                ```"""), MockLLMResponse.text("""
                {"1": "A golden halo floats above your head.", "2": "Nothing floats above your head."}"""));
        appearanceService.propose(model, null);
        Model proposed = awaitProposal();
        assertThat(proposed.getProposalStatus()).isEqualTo(ProposalStatus.DONE);
        AppearanceProposal proposal = AppearanceProposal.fromJson(proposed.getProposal());
        assertThat(proposal.description()).isEqualTo("You have long silver hair and a black jacket.");
        assertThat(proposal.toggles()).containsOnlyKeys(HALO, "mesh:Headgear");
        assertThat(proposal.parts().get(HALO)).containsExactly(Map.entry("shown", "A golden halo floats above your head."),
                Map.entry("hidden", "Nothing floats above your head."));

        List<MockLLMRequest> requests = llm.getCompletionRequests();
        String whole = requests.get(requests.size() - 2).getRawBody();
        assertThat(whole).contains("The halo glows when she is happy.").contains("2 | Headgear / Hide Halo | halo | shown")
                .contains("full front", "face front").contains("Ryuu").contains("These parts can be switched").doesNotContain("part:");
        // two shots and the photo, scaled down; not the close-ups
        assertThat(whole.split("\"type\":\"image_url\"", -1)).hasSize(4);
        assertThat(whole).doesNotContain("data:image/png");
        String part = requests.getLast().getRawBody();
        assertThat(part).contains("\\\"golden halo\\\"").contains("Headgear / Hide Halo").contains("You have long silver hair")
                .contains("1 | the part shown (default)").contains("2 | the part hidden");
        assertThat(part.split("\"type\":\"image_url\"", -1)).hasSize(3);

        AppearancePanel panel = panel();
        assertThat(panel.getProposedDescription().getValue()).isEqualTo("You have long silver hair and a black jacket.");
        assertThat(panel.getToggleChoices()).containsOnlyKeys(HALO);
        panel.getProposedDescription().setValue("You have long silver hair and a black leather jacket.");
        panel.getPartAreas().get(HALO)[0].setValue("A golden halo glows above your head.");
        panel.useProposalForTest();

        Model used = modelService.find(model.getId());
        assertThat(used.getAppearance()).isEqualTo("You have long silver hair and a black leather jacket.");
        assertThat(used.getPartShown()).containsEntry(HALO, "A golden halo glows above your head.");
        assertThat(used.getPartHidden()).containsEntry(HALO, "Nothing floats above your head.");
        assertThat(used.getDescribedHash()).isEqualTo(used.getSnapshotsHash());
        assertThat(used.getProposal()).isNull();
        assertThat(used.getCapabilityNames()).containsEntry(HALO, "golden halo");
        assertThat(panel.getAppearance().getValue()).isEqualTo(used.getAppearance());

        // the description is of the model by default (the halo shown); a part off its default adds what changed
        assertThat(Appearance.of(used)).isEqualTo("You have long silver hair and a black leather jacket.");
        assertThat(Appearance.of(used, Map.of(HALO, false))).isEqualTo("You have long silver hair and a black leather jacket. Nothing floats above your head.");
        List<QueuedEvent> events = queuedEventService.findWaiting(joi);
        assertThat(events).extracting(QueuedEvent::getKind).contains(JoiEvents.BODY_DESCRIBED);
        assertThat(bodyService.prompt(joi)).contains("golden halo (shown)").doesNotContain("A golden halo glows above your head.");
    }

    @Test
    void aGroupIsShotAndDescribedAsAWholeFromTheDefaultState() throws Exception {
        visionModel();
        Model stored = modelService.find(model.getId());
        stored.setDefaultStateMap(Map.of(HALO, false));
        model = modelService.save(stored);
        ModelDialog dialog = new ModelDialog(modelService.find(model.getId()));
        dialog.create();
        ToggleGroup.State plain = new ToggleGroup.State("p", "plain", Map.of("mesh:Headgear", true, HALO, false));
        ToggleGroup.State crowned = new ToggleGroup.State("c", "crowned", Map.of("mesh:Headgear", true, HALO, true));
        dialog.getGroupsPanel().setGroupsForTest(List.of(new ToggleGroup("g1", "headgear", List.of("mesh:Headgear", HALO),
                List.of(plain, crowned), "p")));
        stored = modelService.find(model.getId());
        assertThat(stored.toggleGroups()).extracting(ToggleGroup::name).containsExactly("headgear");
        // the halo is in the group: it is described with it, not alone
        assertThat(DescribedPart.of(stored, ModelCapabilities.fromJson(stored.getCapabilities()))).extracting(DescribedPart::id)
                .containsExactly("group:g1");

        AppearancePanel panel = dialog.getAppearancePanel();
        panel.snapshotsTakenForTest(Map.of("full-front", image("jpg", 4, 4), "part-0-0", image("jpg", 4, 4),
                "part-0-1", image("jpg", 4, 4)));
        stored = modelService.find(model.getId());
        assertThat(stored.getSnapshots()).containsKeys("part:group:g1:p", "part:group:g1:c");

        llm.enqueue(MockLLMResponse.text("""
                {"description": "You have long silver hair.", "toggles": {}}"""), MockLLMResponse.text("""
                {"1": "A plain black headband sits in your hair.", "crowned": "A golden halo floats above your headband."}"""));
        appearanceService.propose(stored, null);
        Model proposed = awaitProposal();
        assertThat(proposed.getProposalStatus()).isEqualTo(ProposalStatus.DONE);
        List<MockLLMRequest> requests = llm.getCompletionRequests();
        // the default state: the halo hidden
        assertThat(requests.get(requests.size() - 2).getRawBody()).contains("2 | Headgear / Hide Halo | halo | hidden");
        String part = requests.getLast().getRawBody();
        assertThat(part).contains("1 | plain: headgear shown, halo hidden (default)").contains("2 | crowned: headgear shown, halo shown");
        assertThat(part.split("\"type\":\"image_url\"", -1)).hasSize(3);

        panel.show();
        panel.useProposalForTest();
        Model used = modelService.find(model.getId());
        assertThat(used.getGroupSentences()).containsEntry("g1:p", "A plain black headband sits in your hair.")
                .containsEntry("g1:c", "A golden halo floats above your headband.");
        // plain is as by default: the description has it; crowned is news
        assertThat(Appearance.of(used)).isEqualTo("You have long silver hair.");
        assertThat(Appearance.of(used, Map.of(HALO, true))).isEqualTo("You have long silver hair. A golden halo floats above your headband.");
        // the user makes crowned the group's default: its toggles' defaults follow, and plain becomes the news
        used.setToggleGroups(ToggleGroup.toJson(List.of(used.toggleGroups().getFirst().withDefaultState("c"))));
        used = modelService.save(used);
        assertThat(used.shownByDefault(ModelCapabilities.fromJson(used.getCapabilities()).toggles().stream()
                .filter(t -> t.id().equals(HALO)).findFirst().orElseThrow())).isTrue();
        assertThat(Appearance.of(used)).isEqualTo("You have long silver hair.");
        assertThat(Appearance.of(used, Map.of(HALO, false))).isEqualTo("You have long silver hair. A plain black headband sits in your hair.");
        Body body = bodyService.create(joi, "Street");
        body.setModel(used);
        bodyService.save(body);
        assertThat(bodyService.prompt(joi)).contains("Parts you can switch: headgear: crowned (or plain).");
    }

    @Test
    void theUserChoosesTheTogglesDescribedApart() throws Exception {
        ModelDialog dialog = new ModelDialog(modelService.find(model.getId()));
        dialog.create();
        Model stored = modelService.find(model.getId());
        assertThat(stored.describedToggles(com.github.enerccio.joi.domain.bodies.ModelCapabilities.fromJson(stored.getCapabilities())))
                .containsExactly(HALO);
        stored.setDescribeToggles(List.of("mesh:Headgear"));
        stored.setDescribeTogglesChosen(true);
        stored = modelService.save(stored);
        assertThat(stored.describedToggles(com.github.enerccio.joi.domain.bodies.ModelCapabilities.fromJson(stored.getCapabilities())))
                .containsExactly("mesh:Headgear");
    }

    @Test
    void aFailedProposalSaysWhy() throws Exception {
        visionModel();
        model = modelService.storeSnapshots(model, Map.of("full-front", image("jpg", 4, 4)));
        llm.enqueue(MockLLMResponse.text("I can't see anything."));
        appearanceService.propose(model, null);
        Model failed = awaitProposal();
        assertThat(failed.getProposalStatus()).isEqualTo(ProposalStatus.FAILED);
        assertThat(failed.getProposalError()).contains("JSON");
    }
}
