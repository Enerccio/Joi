package com.github.enerccio.joi.ui.dialogs.joi;

import com.github.enerccio.joi.domain.bodies.Profiles;
import com.github.enerccio.joi.domain.model.impl.Body;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.service.BodyService;
import com.github.enerccio.joi.domain.service.JoiService;
import com.github.enerccio.joi.domain.service.ProfileService;
import com.github.enerccio.joi.ui.dialogs.JoiDialog;
import com.github.enerccio.marginalia.test.PlatformTestBase;
import com.vaadin.flow.component.UI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Profiles tab and the choice of the profile in the Bodies tab: the profiles and the nodes in them, a node that
 * connected remembered by its name, and the body she is in shown for the profile that is chosen.
 */
class JoiProfilesPartTest extends PlatformTestBase {

    @Autowired
    private JoiService joiService;
    @Autowired
    private BodyService bodyService;
    @Autowired
    private ProfileService profileService;

    private UI ui;

    @BeforeEach
    void setUp() throws Exception {
        ui = new UI();
        UI.setCurrent(ui);
        login();
    }

    @AfterEach
    void tearDown() {
        UI.setCurrent(null);
        ui = null;
    }

    @Test
    void theTabListsTheProfilesAndTheNodesInThem() throws Exception {
        Joi joi = joiService.create("Mira");
        String work = profileService.create(joi, "Work");
        profileService.seen(joi, "desk-1", "desk");
        profileService.seen(joi, "phone-1", "phone");
        profileService.assign(joi, "desk-1", work);

        JoiDialog dialog = new JoiDialog(joiService.find(joi.getId()));
        dialog.create();
        JoiProfilesPart part = dialog.getProfilesPart();
        assertThat(part.getProfiles().getListDataView().getItems().toList()).containsExactly(Profiles.DEFAULT, work);
        assertThat(part.getNodes().getListDataView().getItems().toList()).containsExactlyInAnyOrder("desk-1", "phone-1");
        // the profile of the node the user picks is kept
        profileService.assign(joi, "phone-1", work);
        assertThat(profileService.profileOf(joi, "phone-1")).isEqualTo(work);
    }

    @Test
    void theBodiesTabShowsTheBodyOfTheChosenProfile() throws Exception {
        Joi joi = joiService.create("Mira");
        Body home = bodyService.create(joi, "Home");
        Body office = bodyService.create(joi, "Office");
        JoiDialog dialog = new JoiDialog(joiService.find(joi.getId()));
        dialog.create();
        JoiBodiesPart bodies = dialog.getBodiesPart();
        // no profiles: no choice
        assertThat(bodies.getProfileSelect().isVisible()).isFalse();

        String work = profileService.create(joi, "Work");
        dialog.getProfilesPart().shown();
        assertThat(bodies.getProfileSelect().isVisible()).isTrue();
        bodies.getProfileSelect().setValue(work);
        bodyService.activate(joi, work, bodyService.find(office.getId()));
        bodies.shown();
        // the work profile is in the office body, the default one still in the home body
        assertThat(bodyService.active(joi, work).getId()).isEqualTo(office.getId());
        assertThat(bodyService.active(joi).getId()).isEqualTo(home.getId());

        // a profile that is removed is no longer chosen
        profileService.delete(joi, work);
        dialog.getProfilesPart().shown();
        assertThat(bodies.getProfileSelect().isVisible()).isFalse();
        assertThat(bodies.getProfileSelect().getValue()).isEqualTo(Profiles.DEFAULT);
    }
}
