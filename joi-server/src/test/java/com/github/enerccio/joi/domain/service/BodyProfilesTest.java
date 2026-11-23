package com.github.enerccio.joi.domain.service;

import com.github.enerccio.joi.domain.bodies.BodyState;
import com.github.enerccio.joi.domain.bodies.ModelFiles;
import com.github.enerccio.joi.domain.bodies.Outfit;
import com.github.enerccio.joi.domain.bodies.Profiles;
import com.github.enerccio.joi.domain.bodies.UnityFixtures;
import com.github.enerccio.joi.domain.collections.ChatMessageType;
import com.github.enerccio.joi.domain.events.JoiEvents;
import com.github.enerccio.joi.domain.model.impl.Body;
import com.github.enerccio.joi.domain.model.impl.ChatMessage;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.Model;
import com.github.enerccio.joi.domain.model.impl.QueuedEvent;
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
 * Profiles (home, work): each has its own body and the state of its parts; a new one starts as the default is; what
 * changes in one leaves the others as they were; what she is told follows the profile she was in last; her prompt says
 * where she is and that she moved.
 */
class BodyProfilesTest extends PlatformTestBase {

    @Autowired
    private BodyService bodyService;
    @Autowired
    private ProfileService profileService;
    @Autowired
    private ModelService modelService;
    @Autowired
    private JoiService joiService;
    @Autowired
    private ChatMessageService chatMessageService;

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
    void aNewProfileStartsAsTheDefaultIsAndEachKeepsItsOwnOutfit() throws Exception {
        Body body = bodyOf(wardrobe(), "Cherie");
        Outfit casual = bodyService.addOutfit(body, "Casual", "A pink hoodie.", null);
        Outfit sailor = bodyService.addOutfit(body, "Sailor", "A sailor suit.", shown(false, true, true));
        bodyService.wear(body, casual.id());
        String work = profileService.create(joi, "Work");

        // it starts as the default is
        assertThat(bodyService.currentOutfit(body, work).name()).isEqualTo("Casual");
        bodyService.wear(body, work, sailor.id());
        assertThat(bodyService.currentOutfit(body, work).name()).isEqualTo("Sailor");
        assertThat(bodyService.toggles(body, work)).containsAllEntriesOf(shown(false, true, true));
        // the default one did not move
        assertThat(bodyService.currentOutfit(body).name()).isEqualTo("Casual");
        assertThat(bodyService.toggles(body)).containsAllEntriesOf(shown(true, false, false));
        // and the default's change leaves work as it is
        bodyService.wear(bodyService.find(body.getId()), sailor.id());
        bodyService.wear(bodyService.find(body.getId()), casual.id());
        assertThat(bodyService.currentOutfit(body, work).name()).isEqualTo("Sailor");
        assertThat(bodyService.appearance(body, work)).isEqualTo("Pink hair, green eyes. A sailor suit.");
    }

    @Test
    void eachProfileHasItsBody() throws Exception {
        Model model = wardrobe();
        Body home = bodyOf(model, "Home");
        Body office = bodyOf(model, "Office");
        assertThat(bodyService.active(joi).getId()).isEqualTo(home.getId());
        String work = profileService.create(joi, "Work");
        assertThat(bodyService.active(joi, work).getId()).isEqualTo(home.getId());

        bodyService.activate(joi, work, office);
        assertThat(bodyService.active(joi, work).getId()).isEqualTo(office.getId());
        assertThat(bodyService.active(joi).getId()).isEqualTo(home.getId());
        assertThat(bodyService.bodyInfos(joi, work)).extracting(BodyService.BodyInfo::name, BodyService.BodyInfo::active)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("Home", false), org.assertj.core.groups.Tuple.tuple("Office", true));
        assertThat(bodyService.switchBody(joi, work, "Home")).startsWith("You moved into Home.");
        assertThat(bodyService.active(joi, work).getId()).isEqualTo(home.getId());

        // a body that is gone is the default's again where it was chosen
        bodyService.activate(joi, work, office);
        bodyService.deleteBody(office);
        assertThat(bodyService.active(joi, work).getId()).isEqualTo(home.getId());
    }

    @Test
    void sheIsToldOnlyOfWhatChangesInTheProfileSheWasInLast() throws Exception {
        Body body = bodyOf(wardrobe(), "Cherie");
        Outfit casual = bodyService.addOutfit(body, "Casual", "A pink hoodie.", null);
        Outfit sailor = bodyService.addOutfit(body, "Sailor", "A sailor suit.", shown(false, true, true));
        chatted();
        String work = profileService.create(joi, "Work");
        assertThat(bodyService.currentProfile(joi)).isEqualTo(Profiles.DEFAULT);

        // she is at home: a change at work is not told as it happens, one at home is
        assertThat(bodyService.wear(body, work, sailor.id())).isEmpty();
        assertThat(bodyService.wear(bodyService.find(body.getId()), sailor.id())).hasSize(1);

        assertThat(profileService.used(joi, work)).isTrue();
        assertThat(profileService.used(joi, work)).isFalse();
        assertThat(bodyService.currentProfile(joi)).isEqualTo(work);
        assertThat(bodyService.wear(bodyService.find(body.getId()), work, casual.id())).hasSize(1);
        assertThat(bodyService.wear(bodyService.find(body.getId()), casual.id())).isEmpty();
    }

    @Test
    void herPromptSaysWhereSheIsAndThatSheMovedOnlyWhenThereAreProfiles() throws Exception {
        Body body = bodyOf(wardrobe(), "Cherie");
        Outfit sailor = bodyService.addOutfit(body, "Sailor", "A sailor suit.", shown(false, true, true));
        assertThat(bodyService.prompt(joi, null, null)).doesNotContain("profile").doesNotContain("until now");

        String work = profileService.create(joi, "Work");
        bodyService.wear(body, work, sailor.id());
        String home = bodyService.prompt(joi, null, null);
        assertThat(home).contains("You are in \"Default\" now.").doesNotContain("until now");
        String atWork = bodyService.prompt(joi, work, Profiles.DEFAULT);
        assertThat(atWork).contains("You were in \"Default\" until now; you are in \"Work\" now").contains("A sailor suit.");
        assertThat(bodyService.prompt(joi, work, work)).contains("You are in \"Work\" now.").doesNotContain("until now");
    }

    @Test
    void profilesAreNamedOnceAndANodeIsInOneOfThem() throws Exception {
        String work = profileService.create(joi, "Work");
        assertThatThrownBy(() -> profileService.create(joi, "work")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> profileService.create(joi, "default")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> profileService.create(joi, " ")).isInstanceOf(IllegalArgumentException.class);

        assertThat(profileService.profileOf(joi, "desk-1")).isEqualTo(Profiles.DEFAULT);
        profileService.assign(joi, "desk-1", work);
        profileService.assign(joi, "phone-1", null);
        assertThat(profileService.profileOf(joi, "desk-1")).isEqualTo(work);
        assertThat(profileService.profileOf(joi, "phone-1")).isEqualTo(Profiles.DEFAULT);

        profileService.rename(joi, work, "Office");
        assertThat(profileService.profiles(joi).nameOf(work)).isEqualTo("Office");
        // the nodes of a profile that goes are in the default one again
        profileService.delete(joi, work);
        assertThat(profileService.profileOf(joi, "desk-1")).isEqualTo(Profiles.DEFAULT);
        assertThat(profileService.profiles(joi).several()).isFalse();
    }
}
