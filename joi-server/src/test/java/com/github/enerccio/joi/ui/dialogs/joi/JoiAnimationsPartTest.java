package com.github.enerccio.joi.ui.dialogs.joi;

import com.github.enerccio.joi.domain.animations.AnimationRoles;
import com.github.enerccio.joi.domain.model.impl.AnimationPack;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.service.AnimationPackService;
import com.github.enerccio.joi.domain.service.JoiService;
import com.github.enerccio.joi.ui.dialogs.JoiDialog;
import com.github.enerccio.marginalia.test.PlatformTestBase;
import com.vaadin.flow.component.UI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** The Animations tab: what she plays asleep, falling asleep, waking up, idle and talking, stored as chosen. */
class JoiAnimationsPartTest extends PlatformTestBase {

    @Autowired
    private JoiService joiService;
    @Autowired
    private AnimationPackService packService;
    @Autowired
    private com.github.enerccio.joi.domain.service.StanceService stanceService;

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

    private AnimationPack pack(String name) throws Exception {
        AnimationPack pack = new AnimationPack();
        pack.setName(name);
        return packService.save(pack);
    }

    @Test
    void herAnimationsAreStoredAsTheyAreChosen() throws Exception {
        Joi joi = joiService.create("Mira");
        AnimationPack sleep = pack("Sleep");
        AnimationPack wake = pack("Wake");
        AnimationPack sway = pack("Sway");
        AnimationPack stretch = pack("Stretch");
        AnimationPack talk = pack("Talk");

        JoiDialog dialog = new JoiDialog(joiService.find(joi.getId()));
        dialog.create();
        JoiAnimationsPart part = dialog.getAnimationsPart();
        assertThat(part.getSingleFields().getFirst().getListDataView().getItems().toList())
                .containsExactly(sleep.getUuid(), stretch.getUuid(), sway.getUuid(), talk.getUuid(), wake.getUuid());

        part.getSingleFields().get(0).setValue(sleep.getUuid());
        part.getSingleFields().get(2).setValue(wake.getUuid());
        part.getListFields().get(0).setValue(Set.of(sway.getUuid(), stretch.getUuid()));
        part.getListFields().get(1).setValue(Set.of(talk.getUuid()));

        AnimationRoles roles = joiService.find(joi.getId()).animationRoleSet();
        assertThat(roles.sleeping()).isEqualTo(sleep.getUuid());
        assertThat(roles.fallingAsleep()).isNull();
        assertThat(roles.wakingUp()).isEqualTo(wake.getUuid());
        // in the order of the library, by name
        assertThat(roles.idle()).containsExactly(stretch.getUuid(), sway.getUuid());
        assertThat(roles.talking()).containsExactly(talk.getUuid());

        // a deleted animation is no longer chosen
        packService.delete(packService.find(sway.getId()), false);
        JoiDialog again = new JoiDialog(joiService.find(joi.getId()));
        again.create();
        assertThat(again.getAnimationsPart().getListFields().get(0).getValue()).containsExactly(stretch.getUuid());
        assertThat(again.getAnimationsPart().getSingleFields().get(0).getValue()).isEqualTo(sleep.getUuid());

        // cleared: none
        again.getAnimationsPart().getSingleFields().get(0).clear();
        assertThat(joiService.find(joi.getId()).animationRoleSet().sleeping()).isNull();
    }

    @Test
    void rolesReadTheirJson() {
        AnimationRoles roles = new AnimationRoles("a", " ", null, List.of("b", "b", ""), null);
        assertThat(roles.fallingAsleep()).isNull();
        assertThat(roles.idle()).containsExactly("b");
        assertThat(roles.talking()).isEmpty();
        assertThat(AnimationRoles.fromJson(AnimationRoles.toJson(roles))).isEqualTo(roles);
        assertThat(AnimationRoles.toJson(AnimationRoles.NONE)).isNull();
        assertThat(AnimationRoles.fromJson("{nope")).isEqualTo(AnimationRoles.NONE);
        assertThat(roles.all()).containsExactly("a", "b");
    }

    @Test
    void herStanceIsShownAndTheUserEndsIt() throws Exception {
        Joi joi = joiService.create("Mira");
        AnimationPack dance = pack("Dance");
        JoiDialog dialog = new JoiDialog(joiService.find(joi.getId()));
        dialog.create();
        JoiAnimationsPart part = dialog.getAnimationsPart();
        assertThat(part.getStanceText().getText()).contains("none");
        assertThat(part.getEndStanceButton().isVisible()).isFalse();

        stanceService.take(joi, dance, "test");
        part.shown();
        assertThat(part.getStanceText().getText()).contains("Dance");
        assertThat(part.getEndStanceButton().isVisible()).isTrue();

        com.vaadin.flow.component.ComponentUtil.fireEvent(part.getEndStanceButton(),
                new com.vaadin.flow.component.ClickEvent<>(part.getEndStanceButton()));
        assertThat(joiService.find(joi.getId()).getStance()).isNull();
        assertThat(part.getStanceText().getText()).contains("none");
        assertThat(part.getEndStanceButton().isVisible()).isFalse();
    }
}
