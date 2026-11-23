package com.github.enerccio.joi.ui.dialogs;

import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.QueuedEvent;
import com.github.enerccio.joi.domain.service.JoiService;
import com.github.enerccio.joi.loc.J_L;
import com.github.enerccio.joi.ui.dialogs.joi.JoiAboutPart;
import com.github.enerccio.joi.ui.dialogs.joi.JoiAnimationsPart;
import com.github.enerccio.joi.ui.dialogs.joi.JoiBodiesPart;
import com.github.enerccio.joi.ui.dialogs.joi.JoiChatsPart;
import com.github.enerccio.joi.ui.dialogs.joi.JoiMemoriesPart;
import com.github.enerccio.joi.ui.dialogs.joi.JoiDialogPart;
import com.github.enerccio.joi.ui.dialogs.joi.JoiFilesPart;
import com.github.enerccio.joi.ui.dialogs.joi.JoiSummariesPart;
import com.github.enerccio.joi.ui.dialogs.joi.JoiTasksPart;
import com.github.enerccio.joi.ui.dialogs.joi.JoiTemplatesPart;
import com.github.enerccio.marginalia.CssStyles;
import com.github.enerccio.marginalia.loc.L;
import com.github.enerccio.marginalia.loc.Localization;
import com.github.enerccio.marginalia.ui.widgets.HTabSheet;
import com.github.enerccio.marginalia.utils.UIUtils;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * One assistant ({@link Joi}), after Marginalia's manuscript dialog: the tabs on the left, each a {@link JoiDialogPart}
 * that stores its changes as they are made, and Exit.
 * <ul>
 *     <li>About: her name, what she calls the user, her personality, the models of the roles and her voice;</li>
 *     <li>Bodies: her bodies and the one she is in ({@link JoiBodiesPart});</li>
 *     <li>Templates: her prompts;</li>
 *     <li>Summaries: what she remembers of the past, newest first (see {@link JoiSummariesPart});</li>
 *     <li>Files: her workspace, the files she and her tasks keep (see {@link JoiFilesPart});</li>
 *     <li>Chats: her conversation, read only ({@link JoiChatsPart}).</li>
 * </ul>
 * An empty field is not set: the user's setting is used, shown greyed out.
 */
@Configurable
public class JoiDialog extends Dialog {

    @Autowired
    private Localization loc;

    @Autowired
    private JoiService joiService;

    private Joi joi;
    private Runnable onClose = () -> {
    };
    private HTabSheet tabs;
    private final Map<JoiDialogPart, Component> parts = new LinkedHashMap<>();
    private JoiAboutPart aboutPart;
    private JoiTemplatesPart templatesPart;
    private JoiSummariesPart summariesPart;
    private JoiFilesPart filesPart;
    private JoiChatsPart chatsPart;
    private JoiMemoriesPart memoriesPart;
    private JoiBodiesPart bodiesPart;
    private JoiAnimationsPart animationsPart;
    private JoiTasksPart tasksPart;

    public JoiDialog(Joi joi) {
        this.joi = joi;
    }

    /** What runs when the dialog closes (the list of the assistants refreshes). */
    public void setOnClose(Runnable onClose) {
        this.onClose = onClose == null ? () -> {
        } : onClose;
    }

    public void create() throws Exception {
        setSizeFull();
        setCloseOnOutsideClick(false);
        updateTitle();

        tabs = new HTabSheet();
        tabs.setSizeFull();
        tabs.setTabsWidth("200px");
        aboutPart = new JoiAboutPart(this);
        templatesPart = new JoiTemplatesPart(this);
        for (JoiDialogPart part : List.of(aboutPart, bodiesPart = new JoiBodiesPart(), animationsPart = new JoiAnimationsPart(this), templatesPart, memoriesPart = new JoiMemoriesPart(),
                summariesPart = new JoiSummariesPart(), chatsPart = new JoiChatsPart(), tasksPart = new JoiTasksPart(), filesPart = new JoiFilesPart())) {
            Component content = part.create();
            parts.put(part, content);
            tabs.add(loc.getValue(part.caption()), content);
        }
        for (JoiDialogPart part : parts.keySet()) {
            part.load(joi);
        }
        tabs.addSelectedChangeListener(e -> {
            for (Map.Entry<JoiDialogPart, Component> entry : parts.entrySet()) {
                if (entry.getValue().equals(tabs.getComponent(e.getSelectedTab()))) {
                    try {
                        entry.getKey().shown();
                    } catch (Exception ex) {
                        UIUtils.internalServerError(loc, ex);
                    }
                }
            }
        });

        VerticalLayout layout = new VerticalLayout(tabs);
        layout.setSizeFull();
        layout.setPadding(false);
        layout.addClassName(CssStyles.OVERFLOW_HIDDEN);
        add(layout);

        Button exitButton = new Button(loc.getValue(L.LABEL_EXIT), e -> close());
        exitButton.setThemeName("primary");
        getFooter().add(exitButton);

        addOpenedChangeListener(e -> {
            if (!e.isOpened()) {
                onClose.run();
            }
        });
        aboutPart.focus(this);
    }

    /** The assistant as stored last. */
    public Joi getJoi() {
        return joi;
    }

    /**
     * Stores a change of a part, applied to her as stored now ({@link JoiService#update}): her turns may have saved her
     * since the dialog read her (her mood of the day).
     */
    public void save(Consumer<Joi> change) {
        try {
            joi = joiService.update(joi, change);
            reload();
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    /**
     * Stores a change of her About like {@link #save}, queuing an event for each change of her persona (see
     * {@link JoiService#saveAbout}).
     */
    public List<QueuedEvent> saveAbout(JoiService.Persona before, Consumer<Joi> change) {
        try {
            joi = joiService.update(joi, change);
            List<QueuedEvent> events = joiService.saveAbout(joi, before);
            reload();
            return events;
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
            return new ArrayList<>();
        }
    }

    private void reload() throws Exception {
        joi = joiService.find(joi.getId());
        updateTitle();
    }

    private void updateTitle() throws Exception {
        setHeaderTitle(joiService.name(joi));
    }

    /** The tabs, for tests. */
    public HTabSheet getTabs() {
        return tabs;
    }

    /** The About tab, for tests. */
    public JoiAboutPart getAboutPart() {
        return aboutPart;
    }

    /** The Chats tab, for tests. */
    public JoiMemoriesPart getMemoriesPart() {
        return memoriesPart;
    }

    public JoiChatsPart getChatsPart() {
        return chatsPart;
    }

    /** The Files tab, for tests. */
    public JoiFilesPart getFilesPart() {
        return filesPart;
    }

    /** The Summaries tab, for tests. */
    public JoiSummariesPart getSummariesPart() {
        return summariesPart;
    }

    /** The Bodies tab, for tests. */
    public JoiBodiesPart getBodiesPart() {
        return bodiesPart;
    }

    public JoiAnimationsPart getAnimationsPart() {
        return animationsPart;
    }

    /** The Tasks tab, for tests. */
    public JoiTasksPart getTasksPart() {
        return tasksPart;
    }

    /** The Templates tab, for tests. */
    public JoiTemplatesPart getTemplatesPart() {
        return templatesPart;
    }
}
