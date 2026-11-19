package com.github.enerccio.joi.ui.dialogs;

import com.github.enerccio.joi.domain.model.impl.Animation;
import com.github.enerccio.joi.domain.model.impl.AnimationPack;
import com.github.enerccio.joi.domain.service.AnimationPackService;
import com.github.enerccio.joi.domain.service.AnimationService;
import com.github.enerccio.joi.loc.J_L;
import com.github.enerccio.joi.ui.JoiCssStyles;
import com.github.enerccio.joi.ui.bodies.ClipPreview;
import com.github.enerccio.joi.ui.widgets.EditorSplit;
import com.github.enerccio.marginalia.CssStyles;
import com.github.enerccio.marginalia.loc.L;
import com.github.enerccio.marginalia.loc.Localization;
import com.github.enerccio.marginalia.utils.UIUtils;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * One clip of the user's library ({@link Animation}), full screen: on the left what the user decides on it (its name,
 * description, tags) and what was read from it (its length, the bones it moves, where it came from, the animations it
 * is in); on the right it plays in a loop in a {@link ClipPreview} on a body of their assistants. How it plays (loop,
 * speed, in place) is decided by each animation it is in. Changes are stored at once.
 */
@Configurable
public class AnimationDialog extends Dialog {

    @Autowired
    private Localization loc;

    @Autowired
    private AnimationService animationService;

    @Autowired
    private AnimationPackService packService;

    private Animation clip;
    private Runnable onClose = () -> {
    };
    private TextField name;
    private TextArea description;
    private TextField tags;
    private ClipPreview preview;
    private boolean filling;

    public AnimationDialog(Animation clip) {
        this.clip = clip;
    }

    /** What runs when the dialog closes (the table of clips refreshes). */
    public void setOnClose(Runnable onClose) {
        this.onClose = onClose == null ? () -> {
        } : onClose;
    }

    public void create() throws Exception {
        setSizeFull();
        setCloseOnOutsideClick(false);
        setHeaderTitle(StringUtils.defaultString(clip.getName()));

        name = new TextField(loc.getValue(L.LABEL_NAME));
        name.setWidthFull();
        description = new TextArea(loc.getValue(L.LABEL_DESCRIPTION));
        description.setWidthFull();
        description.setHelperText(loc.getValue(J_L.MSG_ANIMATION_DESCRIPTION_HINT));
        tags = new TextField(loc.getValue(J_L.LABEL_ANIMATION_TAGS));
        tags.setWidthFull();
        tags.setHelperText(loc.getValue(J_L.MSG_ANIMATION_TAGS_HINT));

        Span info = new Span(info(loc, clip));
        info.addClassName(CssStyles.HINT_TEXT);
        List<String> packs = new ArrayList<>();
        for (AnimationPack pack : packService.packsWith(clip)) {
            packs.add(StringUtils.defaultIfBlank(pack.getName(), loc.getValue(J_L.LABEL_NEW_ANIMATION_PACK)));
        }
        Span inPacks = new Span(loc.getValue(J_L.LABEL_IN_PACKS) + ": " + (packs.isEmpty() ? "—" : String.join(", ", packs)));
        inPacks.addClassName(CssStyles.HINT_TEXT);

        filling = true;
        try {
            name.setValue(StringUtils.defaultString(clip.getName()));
            description.setValue(StringUtils.defaultString(clip.getDescription()));
            tags.setValue(String.join(", ", clip.getTags()));
        } finally {
            filling = false;
        }
        name.addValueChangeListener(e -> store());
        description.addValueChangeListener(e -> store());
        tags.addValueChangeListener(e -> store());

        VerticalLayout sidebar = new VerticalLayout(name, description, tags, info, inPacks);
        sidebar.setPadding(false);
        sidebar.addClassName(JoiCssStyles.MODEL_SIDEBAR);

        preview = new ClipPreview(null);
        add(EditorSplit.create(sidebar, preview, "joi.animation-dialog.split"));

        Button exitButton = new Button(loc.getValue(L.LABEL_EXIT), e -> close());
        exitButton.setThemeName("primary");
        getFooter().add(exitButton);
        addOpenedChangeListener(e -> {
            if (!e.isOpened()) {
                onClose.run();
            }
        });
        preview.play(clip);
        name.focus();
    }

    /** What was read from the clip and where it came from, in a line. */
    public static String info(Localization loc, Animation clip) {
        String source = clip.getSource() == null ? ""
                : String.format(loc.getValue(J_L.MSG_ANIMATION_SOURCE), clip.getSource()
                + (clip.getTake() == null ? "" : " (" + clip.getTake() + ")"));
        return String.format(loc.getValue(J_L.MSG_ANIMATION_INFO), length(clip.getDuration()), clip.getBones().size(),
                Boolean.TRUE.equals(clip.getExpressions()) ? loc.getValue(J_L.MSG_ANIMATION_EXPRESSIONS) : "") + source;
    }

    /** A length in seconds, as shown. */
    public static String length(Double seconds) {
        return seconds == null ? "" : String.format(Locale.ROOT, "%.1f s", seconds);
    }

    /** The tags of a comma-separated text: trimmed, lower case, each once. */
    public static List<String> tags(String text) {
        List<String> tags = new ArrayList<>();
        if (text != null) {
            Arrays.stream(text.split(","))
                    .map(tag -> tag.strip().toLowerCase(Locale.ROOT))
                    .filter(tag -> !tag.isEmpty() && !tags.contains(tag))
                    .forEach(tags::add);
        }
        return tags;
    }

    private void store() {
        if (filling) {
            return;
        }
        try {
            Animation stored = animationService.find(clip.getId());
            stored.setName(StringUtils.defaultIfBlank(StringUtils.strip(name.getValue()), stored.getFileName()));
            stored.setDescription(StringUtils.defaultIfBlank(description.getValue(), null));
            stored.setTags(tags(tags.getValue()));
            clip = animationService.save(stored);
            setHeaderTitle(StringUtils.defaultString(clip.getName()));
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    /** The clip as stored last, for tests. */
    public Animation getClip() {
        return clip;
    }

    public TextField getNameField() {
        return name;
    }

    public TextField getTagsField() {
        return tags;
    }
}
