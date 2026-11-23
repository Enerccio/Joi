package com.github.enerccio.joi.ui.dialogs;

import com.github.enerccio.joi.domain.bodies.BodyState;
import com.github.enerccio.joi.domain.bodies.ModelFiles;
import com.github.enerccio.joi.domain.bodies.Outfit;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Outfits: presets of a body's toggles with a description. Putting one on sets the toggles and tells her (the user's
 * change) or answers her (her own); a part switched after it takes over; a new body or outfit is told once, in one
 * event; her lists and her prompt have the descriptions.
 */
class BodyOutfitsTest extends PlatformTestBase {

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

    /** A model of three pieces, each a mesh toggle; the hoodie is the only one on by default. */
    private Model wardrobe() throws Exception {
        Path dir = Files.createTempDirectory("vrm");
        File source = UnityFixtures.glb(dir.resolve("m.vrm").toFile(), """
                {"nodes": [{"name": "Hoodie", "mesh": 0}, {"name": "Skirt", "mesh": 1}, {"name": "Hat", "mesh": 2}],
                 "meshes": [{"primitives": [{}]}, {"primitives": [{}]}, {"primitives": [{}]}],
                 "extensions": {"VRMC_vrm": {"humanoid": {"humanBones": {"hips": {}}},
                   "expressions": {"preset": {"happy": {"morphTargetBinds": [{}]}}}}}}
                """);
        File file = ModelFiles.uploadFile(modelService.uploadFolder(), "Cherie.vrm");
        Files.copy(source.toPath(), file.toPath());
        Model model = modelService.find(modelService.attach(new Model(), file, "Cherie.vrm").getId());
        model.setName("Cherie");
        model.setAppearance("Pink hair, green eyes.");
        model.setDefaultStateMap(Map.of("mesh:Skirt", false, "mesh:Hat", false));
        return modelService.save(model);
    }

    private Body bodyOf(Model model, String name) throws Exception {
        Body body = bodyService.create(joi, name);
        body.setModel(model);
        return bodyService.save(body);
    }

    private void chatted() throws Exception {
        ChatMessage message = new ChatMessage();
        message.setJoi(joi);
        message.setType(ChatMessageType.USER);
        message.setSentAt(new Date());
        message.setInput("hi");
        chatMessageService.save(message);
    }

    private static Map<String, Boolean> shown(boolean hoodie, boolean skirt, boolean hat) {
        Map<String, Boolean> shown = new LinkedHashMap<>();
        shown.put("mesh:Hoodie", hoodie);
        shown.put("mesh:Skirt", skirt);
        shown.put("mesh:Hat", hat);
        return shown;
    }

    @Test
    void anOutfitIsAPresetOfTheTogglesWithADescriptionAndNamesAreUnique() throws Exception {
        Body body = bodyOf(wardrobe(), "Cherie");
        // what the body shows now is where a new outfit starts
        Outfit casual = bodyService.addOutfit(body, " Casual ", "A pink hoodie.", null);
        assertThat(casual.name()).isEqualTo("Casual");
        assertThat(casual.toggles()).containsAllEntriesOf(shown(true, false, false));
        Outfit sailor = bodyService.addOutfit(body, "Sailor", null, shown(false, true, true));
        assertThat(bodyService.outfits(body)).extracting(Outfit::name).containsExactly("Casual", "Sailor");
        assertThat(bodyService.outfits(body).get(1).description()).isNull();

        assertThatThrownBy(() -> bodyService.addOutfit(body, "casual", null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bodyService.addOutfit(body, "  ", null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bodyService.saveOutfit(body, sailor.named("CASUAL"))).isInstanceOf(IllegalArgumentException.class);

        bodyService.saveOutfit(body, sailor.named("Sailor suit").described(" Blue and white. "));
        assertThat(bodyService.outfits(body).get(1).name()).isEqualTo("Sailor suit");
        assertThat(bodyService.outfits(body).get(1).description()).isEqualTo("Blue and white.");
        bodyService.deleteOutfit(body, casual.id());
        assertThat(bodyService.outfits(body)).extracting(Outfit::name).containsExactly("Sailor suit");
    }

    @Test
    void puttingAnOutfitOnSetsTheTogglesAndHerLookIsTheOutfitsDescription() throws Exception {
        Model model = wardrobe();
        Body first = bodyOf(model, "Cherie");
        Outfit casual = bodyService.addOutfit(first, "Casual", "A pink hoodie.", null);
        Outfit sailor = bodyService.addOutfit(first, "Sailor", "A sailor top, a pleated skirt and a white hat.", shown(false, true, true));
        Body body = first;
        assertThat(bodyService.currentOutfit(body).name()).isEqualTo("Casual");
        assertThat(bodyService.appearance(body)).isEqualTo("Pink hair, green eyes. A pink hoodie.");

        // the user's change: kept, and told once she has chatted
        assertThat(bodyService.wear(body, sailor.id())).isEmpty();
        body = bodyService.find(body.getId());
        assertThat(bodyService.toggles(body)).containsAllEntriesOf(shown(false, true, true));
        assertThat(bodyService.currentOutfit(body).name()).isEqualTo("Sailor");
        assertThat(bodyService.appearance(body)).isEqualTo("Pink hair, green eyes. A sailor top, a pleated skirt and a white hat.");

        chatted();
        List<QueuedEvent> events = bodyService.wear(body, casual.id());
        assertThat(events).extracting(QueuedEvent::getKind, QueuedEvent::getText).containsExactly(org.assertj.core.groups.Tuple.tuple(
                JoiEvents.OUTFIT_CHANGED, "You were put into the outfit \"Casual\". How you look now: Pink hair, green eyes. A pink hoodie."));
        // the same outfit again is nothing new
        assertThat(bodyService.wear(bodyService.find(body.getId()), casual.id())).isEmpty();
        assertThatThrownBy(() -> bodyService.wear(bodyService.find(first.getId()), "nope")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anOutfitWithoutADescriptionLeavesHerLookToTheModelAndTheParts() throws Exception {
        Model model = wardrobe();
        model.setDescribeToggles(List.of("mesh:Hat"));
        model.setDescribeTogglesChosen(true);
        model.getPartShown().put("mesh:Hat", "A white hat sits on your head.");
        modelService.save(model);
        Body body = bodyOf(modelService.find(model.getId()), "Cherie");
        Outfit hat = bodyService.addOutfit(body, "Hat only", null, shown(true, false, true));
        bodyService.wear(body, hat.id());
        assertThat(bodyService.appearance(bodyService.find(body.getId()))).isEqualTo("Pink hair, green eyes. A white hat sits on your head.");
    }

    @Test
    void aPartSwitchedAfterAnOutfitTakesOverFromIt() throws Exception {
        Model model = wardrobe();
        model.setDescribeToggles(List.of("mesh:Hat"));
        model.setDescribeTogglesChosen(true);
        model = modelService.save(model);
        Body body = bodyOf(model, "Cherie");
        Outfit sailor = bodyService.addOutfit(body, "Sailor", "A sailor suit.", shown(false, true, true));
        bodyService.wear(body, sailor.id());
        body = bodyService.find(body.getId());
        // the hat part shows the state the outfit gave it
        assertThat(BodyState.stateOf(model, com.github.enerccio.joi.domain.bodies.DescribedPart.of(model,
                com.github.enerccio.joi.domain.bodies.ModelCapabilities.fromJson(model.getCapabilities())).getFirst(), body.partStateMap()).id())
                .isEqualTo("shown");

        // she takes the hat off: the part's own switch, the outfit no longer fits
        assertThat(bodyService.changePart(body, "hat", "hidden")).contains("hat");
        body = bodyService.find(body.getId());
        assertThat(bodyService.toggles(body)).containsAllEntriesOf(shown(false, true, false));
        assertThat(bodyService.currentOutfit(body)).isNull();
        // hidden is the default of the hat: switching to it removes the part's entry, not the outfit's hold on it
        assertThat(body.partStateMap()).doesNotContainKey("mesh:Hat").doesNotContainKey("@mesh:Hat");
        assertThat(bodyService.prompt(joi)).contains("Your outfits: Sailor.").doesNotContain("You wear the outfit");
    }

    @Test
    void aPartIsToldInTheWordsTheUserGaveItsStatesNotShownAndHidden() throws Exception {
        Model model = wardrobe();
        model.setDescribeToggles(List.of("mesh:Hat"));
        model.setDescribeTogglesChosen(true);
        model.getShownWords().put("mesh:Hat", "worn");
        model.getHiddenWords().put("mesh:Hat", "taken off");
        model = modelService.save(model);
        Body body = bodyOf(model, "Cherie");
        // the words are in her prompt, in the options of her function and in what she is told of a switch
        assertThat(bodyService.prompt(joi)).contains("hat (taken off)").doesNotContain("hidden").doesNotContain("shown");
        assertThat(bodyService.partOptions(body)).singleElement().satisfies(option -> assertThat(option.states()).containsExactly("worn", "taken off"));
        assertThat(bodyService.changePart(body, "hat", "worn")).contains("now worn");
        assertThat(bodyService.changePart(bodyService.find(body.getId()), "hat", "worn")).contains("already worn");
        // the plain ids still work
        assertThat(bodyService.changePart(bodyService.find(body.getId()), "hat", "hidden")).contains("now taken off");
        // the same words for both states are no words: they could not be told apart
        model = modelService.find(model.getId());
        model.getHiddenWords().put("mesh:Hat", "Worn");
        model = modelService.save(model);
        assertThat(bodyService.partOptions(bodyService.find(body.getId()))).singleElement()
                .satisfies(option -> assertThat(option.states()).containsExactly("shown", "hidden"));
    }

    @Test
    void sheChoosesOutfitsAndBodiesByNameAndIsToldInTheAnswer() throws Exception {
        Model model = wardrobe();
        Body cherie = bodyOf(model, "Cherie");
        Outfit casual = bodyService.addOutfit(cherie, "Casual", "A pink hoodie.", null);
        bodyService.addOutfit(cherie, "Sailor", "A sailor suit.", shown(false, true, true));
        Body spare = bodyService.create(joi, "Spare");
        spare.setDescription("A plain spare body.");
        bodyService.save(spare);
        chatted();

        assertThat(bodyService.wearOutfit(cherie, "sailor")).isEqualTo("You put on Sailor. How you look now: Pink hair, green eyes. A sailor suit.");
        assertThat(bodyService.wearOutfit(bodyService.find(cherie.getId()), "SAILOR")).isEqualTo("You wear Sailor already.");
        assertThatThrownBy(() -> bodyService.wearOutfit(cherie, "Armor")).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("You have no outfit called \"Armor\". Your outfits: Casual, Sailor");
        // her own change is no event
        assertThat(queuedEventService.findWaiting(joi)).isEmpty();

        List<BodyService.BodyInfo> infos = bodyService.bodyInfos(joi);
        assertThat(infos).extracting(BodyService.BodyInfo::name, BodyService.BodyInfo::active).containsExactly(
                org.assertj.core.groups.Tuple.tuple("Cherie", true), org.assertj.core.groups.Tuple.tuple("Spare", false));
        assertThat(infos.get(0).description()).isEqualTo("Pink hair, green eyes.");
        assertThat(infos.get(0).outfits()).extracting(BodyService.OutfitInfo::name, BodyService.OutfitInfo::worn)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("Casual", false), org.assertj.core.groups.Tuple.tuple("Sailor", true));
        assertThat(infos.get(1).description()).isEqualTo("A plain spare body.");

        assertThat(bodyService.switchBody(joi, "spare")).isEqualTo("You moved into Spare.");
        assertThat(bodyService.active(joi).getName()).isEqualTo("Spare");
        assertThat(bodyService.switchBody(joi, "Spare")).isEqualTo("You are in Spare already.");
        assertThatThrownBy(() -> bodyService.switchBody(joi, "Nobody")).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("You have no body called \"Nobody\". Your bodies: Cherie, Spare");
        assertThat(queuedEventService.findWaiting(joi)).isEmpty();
        assertThat(casual.name()).isEqualTo("Casual");
    }

    @Test
    void aNewBodyAndNewOutfitsAreToldOnceInOneEventThatGrows() throws Exception {
        Model model = wardrobe();
        Body street = bodyOf(model, "Street");
        // before she has chatted nothing is queued, but it counts as told
        bodyService.addOutfit(street, "Early", null, null);
        assertThat(bodyService.announce(street)).isEmpty();
        chatted();

        // a second body, not the one she is in: told with its description, and its outfit
        Body cherie = bodyOf(model, "Cherie");
        cherie.setDescription("A tall girl with pink hair.");
        bodyService.save(cherie);
        bodyService.addOutfit(cherie, "Casual", "A pink hoodie.", null);
        bodyService.addOutfit(cherie, "Sailor", null, shown(false, true, true));
        List<QueuedEvent> events = bodyService.announce(cherie);
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().getKind()).isEqualTo(JoiEvents.BODY_ADDED);
        assertThat(events.getFirst().getText()).isEqualTo("A new body is yours now: Cherie. A tall girl with pink hair. "
                + "New outfits for your body Cherie: \"Casual\" (A pink hoodie.); \"Sailor\".");
        // nothing new, nothing queued
        assertThat(bodyService.announce(bodyService.find(cherie.getId()))).isEmpty();

        // a later outfit joins the waiting event instead of making another
        Body again = bodyService.find(cherie.getId());
        bodyService.addOutfit(again, "Swim", "A striped bikini.", null);
        events = bodyService.announce(again);
        assertThat(events).hasSize(1);
        List<QueuedEvent> waiting = queuedEventService.findWaiting(joi);
        assertThat(waiting).hasSize(1);
        assertThat(waiting.getFirst().getText()).endsWith("\nA new outfit for your body Cherie: \"Swim\" (A striped bikini.).")
                .startsWith("A new body is yours now: Cherie.");
        assertThat(waiting.getFirst().getPayload()).contains(cherie.getUuid());

        // the body she is in is told with her switch, not as new; its outfits are
        Body active = bodyService.active(joi);
        bodyService.addOutfit(active, "Evening", "A black dress.", null);
        events = bodyService.announce(active);
        assertThat(events).hasSize(1);
        assertThat(queuedEventService.findWaiting(joi).getFirst().getText()).endsWith("A new outfit for your body Street: \"Evening\" (A black dress.). You can put it on with set_outfit.");
    }

    @Test
    void herPromptListsTheOutfitsAndTheOneSheWears() throws Exception {
        Body body = bodyOf(wardrobe(), "Cherie");
        bodyService.addOutfit(body, "Casual", "A pink hoodie.", null);
        Outfit sailor = bodyService.addOutfit(body, "Sailor", "A sailor suit.", shown(false, true, true));
        assertThat(bodyService.prompt(joi)).isEqualTo("""
                You are in your body "Cherie". You wear the outfit "Casual".
                Pink hair, green eyes. A pink hoodie.
                Expressions your face can show: happy.
                Your other outfits: Sailor.""");
        bodyService.wear(body, sailor.id());
        assertThat(bodyService.prompt(joi)).contains("You wear the outfit \"Sailor\".").contains("Your other outfits: Casual.");
    }

    @Test
    void theOutfitDialogSavesTheBoxesAsTheOutfit() throws Exception {
        Model model = wardrobe();
        Body body = bodyOf(model, "Cherie");
        OutfitDialog dialog = new OutfitDialog(body, model, null, () -> {
        });
        dialog.create();
        // a new outfit starts from what the body shows
        assertThat(dialog.getToggle("mesh:Hoodie").getValue()).isTrue();
        assertThat(dialog.getToggle("mesh:Skirt").getValue()).isFalse();
        dialog.getToggle("mesh:Hoodie").setValue(false);
        dialog.getToggle("mesh:Hat").setValue(true);
        dialog.getDescription().setValue("Just a hat.");
        dialog.saveForTest();
        assertThat(bodyService.outfits(body)).isEmpty();

        dialog.getNameField().setValue("Hat");
        dialog.saveForTest();
        assertThat(bodyService.outfits(body)).singleElement().satisfies(outfit -> {
            assertThat(outfit.name()).isEqualTo("Hat");
            assertThat(outfit.description()).isEqualTo("Just a hat.");
            assertThat(outfit.toggles()).containsAllEntriesOf(shown(false, false, true));
        });

        OutfitDialog edit = new OutfitDialog(bodyService.find(body.getId()), model, bodyService.outfits(body).getFirst(), () -> {
        });
        edit.create();
        assertThat(edit.getToggle("mesh:Hat").getValue()).isTrue();
        edit.getNameField().setValue("Hat and hoodie");
        edit.getToggle("mesh:Hoodie").setValue(true);
        edit.saveForTest();
        assertThat(bodyService.outfits(body)).singleElement().satisfies(outfit -> {
            assertThat(outfit.name()).isEqualTo("Hat and hoodie");
            assertThat(outfit.toggles()).containsAllEntriesOf(shown(true, false, true));
        });
    }
}
