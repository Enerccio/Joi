package com.github.enerccio.joi.ui.dialogs.joi;

import com.flowingcode.vaadin.addons.fontawesome.FontAwesome.Solid;
import com.github.enerccio.joi.domain.collections.MemoryImportance;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.Memory;
import com.github.enerccio.joi.domain.service.MemoryService;
import com.github.enerccio.joi.loc.J_L;
import com.github.enerccio.joi.ui.JoiCssStyles;
import com.github.enerccio.joi.ui.widgets.PromptAreas;
import com.github.enerccio.marginalia.CssStyles;
import com.github.enerccio.marginalia.loc.L;
import com.github.enerccio.marginalia.loc.Localization;
import com.github.enerccio.marginalia.UIConstants;
import com.github.enerccio.marginalia.ui.dialogs.ConfirmDialog;
import com.github.enerccio.marginalia.ui.widgets.ResizableTextArea;
import com.github.enerccio.marginalia.utils.UIUtils;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridMultiSelectionModel;
import com.vaadin.flow.component.grid.dataview.GridListDataView;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Memories tab: what she remembers, one row each (its name, type, who wrote it, when), the row opening into the
 * memory itself to edit (name, type, keywords and the text, in a text area that grows). The user adds memories, and
 * deletes them one by one or checked (shift-click checks a range). Every change is stored as it is made.
 */
@Configurable
public class JoiMemoriesPart implements JoiDialogPart {

    @Autowired
    private Localization loc;

    @Autowired
    private MemoryService memoryService;

    private final DateFormat format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
    private Joi joi;
    private Grid<Memory> grid;
    private GridListDataView<Memory> dataView;
    private GridMultiSelectionModel<Memory> selection;
    private Button deleteSelectedButton;
    private final List<Memory> memories = new ArrayList<>();
    /** The opened editor of each memory, kept so a refresh of its row keeps what is typed and where. */
    private final Map<Long, Editor> editors = new HashMap<>();
    /** The memory last checked or unchecked, where a shift-click's range starts. */
    private Memory anchor;

    @Override
    public J_L caption() {
        return J_L.LABEL_MEMORIES;
    }

    @Override
    public Component create() {
        Span hint = new Span(loc.getValue(J_L.MSG_MEMORIES_HINT));
        hint.addClassName(CssStyles.HINT_TEXT);
        deleteSelectedButton = new Button(loc.getValue(L.LABEL_DELETE_SELECTED), Solid.TRASH.create(), e -> deleteSelected());
        deleteSelectedButton.setThemeName("error");
        deleteSelectedButton.setEnabled(false);
        Button addButton = new Button(loc.getValue(J_L.LABEL_ADD_MEMORY), Solid.PLUS.create(), e -> add());
        addButton.setThemeName("primary");
        HorizontalLayout buttons = new HorizontalLayout(deleteSelectedButton, addButton);
        HorizontalLayout header = new HorizontalLayout(hint, buttons);
        header.setWidthFull();
        header.setAlignItems(FlexComponent.Alignment.CENTER);
        header.setJustifyContentMode(FlexComponent.JustifyContentMode.BETWEEN);

        grid = new Grid<>();
        grid.addClassName(JoiCssStyles.MEMORY_GRID);
        selection = (GridMultiSelectionModel<Memory>) grid.setSelectionMode(Grid.SelectionMode.MULTI);
        selection.addClientItemToggleListener(e -> toggled(e.getItem(), e.isSelected(), e.isShiftKey()));
        grid.addSelectionListener(e -> deleteSelectedButton.setEnabled(!e.getAllSelectedItems().isEmpty()));
        grid.addColumn(this::title)
                .setHeader(loc.getValue(J_L.LABEL_MEMORY_NAME))
                .setFlexGrow(1);
        grid.addColumn(memory -> importance(memory.getImportance()))
                .setHeader(loc.getValue(J_L.LABEL_MEMORY_TYPE))
                .setFlexGrow(0)
                .setWidth("120px");
        grid.addColumn(memory -> loc.getValue(memory.getSourceMessage() == null ? J_L.LABEL_MEMORY_FROM_USER : J_L.LABEL_MEMORY_FROM_HER))
                .setHeader(loc.getValue(J_L.LABEL_MEMORY_FROM))
                .setFlexGrow(0)
                .setWidth("90px");
        grid.addColumn(memory -> memory.getCreation() == null ? "" : format.format(memory.getCreation()))
                .setHeader(loc.getValue(L.LABEL_CREATED))
                .setFlexGrow(0)
                .setWidth(UIConstants.DATE_COLUMN_SIZE);
        grid.addComponentColumn(this::actions)
                .setHeader("")
                .setFlexGrow(0)
                .setWidth(UIConstants.TOOL_COLUMN_SIZE_LARGE);
        grid.setItemDetailsRenderer(new ComponentRenderer<>(memory -> editor(memory).layout));
        grid.setEmptyStateText(loc.getValue(J_L.MSG_NO_MEMORIES));
        grid.setSizeFull();
        dataView = grid.setItems(memories);

        VerticalLayout layout = new VerticalLayout(header, grid);
        layout.setSizeFull();
        layout.setPadding(false);
        layout.setFlexGrow(1, grid);
        return layout;
    }

    @Override
    public void load(Joi joi) throws Exception {
        this.joi = joi;
        memories.clear();
        editors.clear();
        anchor = null;
        if (joi.getId() != null) {
            memories.addAll(memoryService.findAll(joi));
        }
        grid.deselectAll();
        dataView.refreshAll();
    }

    /** The row's text: the name, else the start of the memory, else that it is new. */
    private String title(Memory memory) {
        if (StringUtils.isNotBlank(memory.getName())) {
            return memory.getName();
        }
        if (StringUtils.isNotBlank(memory.getText())) {
            return StringUtils.abbreviate(StringUtils.normalizeSpace(memory.getText()), 120);
        }
        return loc.getValue(J_L.LABEL_NEW_MEMORY);
    }

    private String importance(MemoryImportance importance) {
        return loc.getValue(switch (importance == null ? MemoryImportance.MEDIUM : importance) {
            case IMPORTANT -> J_L.LABEL_MEMORY_IMPORTANT;
            case MEDIUM -> J_L.LABEL_MEMORY_MEDIUM;
            case TRIVIAL -> J_L.LABEL_MEMORY_TRIVIAL;
        });
    }

    private Component actions(Memory memory) {
        Button deleteButton = new Button(Solid.TRASH.create(), e ->
                ConfirmDialog.show(loc.getValue(J_L.MSG_CONFIRM_DELETE_MEMORY), () -> delete(List.of(memory))));
        deleteButton.setThemeName("error tertiary");
        deleteButton.setTooltipText(loc.getValue(L.LABEL_DELETE));
        deleteButton.setAriaLabel(loc.getValue(L.LABEL_DELETE));
        return deleteButton;
    }

    /** The fields of a memory, under its row. */
    private final class Editor {
        final VerticalLayout layout;
        final TextField name;

        Editor(Memory memory) {
            name = new TextField(loc.getValue(J_L.LABEL_MEMORY_NAME));
            name.setValue(StringUtils.defaultString(memory.getName()));
            name.setPlaceholder(title(memory));
            name.setWidthFull();
            name.addValueChangeListener(e -> {
                if (e.isFromClient()) {
                    memory.setName(StringUtils.trimToNull(e.getValue()));
                    store(memory);
                }
            });

            Select<MemoryImportance> type = new Select<>();
            type.setLabel(loc.getValue(J_L.LABEL_MEMORY_TYPE));
            type.setItems(MemoryImportance.values());
            type.setItemLabelGenerator(JoiMemoriesPart.this::importance);
            type.setValue(memory.getImportance() == null ? MemoryImportance.MEDIUM : memory.getImportance());
            type.setHelperText(loc.getValue(J_L.MSG_MEMORY_TYPE_HINT));
            type.setWidth("24rem");
            type.addValueChangeListener(e -> {
                if (e.isFromClient()) {
                    memory.setImportance(e.getValue());
                    store(memory);
                }
            });

            TextField keywords = new TextField(loc.getValue(J_L.LABEL_MEMORY_KEYWORDS));
            keywords.setValue(StringUtils.defaultString(memory.getKeywords()));
            keywords.setHelperText(loc.getValue(J_L.MSG_MEMORY_KEYWORDS_HINT));
            keywords.setWidthFull();
            keywords.addValueChangeListener(e -> {
                if (e.isFromClient()) {
                    memory.setKeywords(StringUtils.trimToNull(e.getValue()));
                    store(memory);
                }
            });

            TextArea text = new TextArea(loc.getValue(J_L.LABEL_MEMORY_TEXT));
            text.setValue(StringUtils.defaultString(memory.getText()));
            text.setWidthFull();
            ResizableTextArea.install(loc, text, PromptAreas.TEXT_HEIGHT);
            text.addValueChangeListener(e -> {
                if (e.isFromClient()) {
                    memory.setText(e.getValue());
                    store(memory);
                }
            });

            HorizontalLayout top = new HorizontalLayout(name, type);
            top.setWidthFull();
            top.setFlexGrow(1, name);
            layout = new VerticalLayout(top, keywords, text);
            layout.setPadding(false);
            layout.setSpacing(false);
            layout.addClassName(JoiCssStyles.MEMORY_EDITOR);
        }
    }

    private Editor editor(Memory memory) {
        return editors.computeIfAbsent(memory.getId(), id -> new Editor(memory));
    }

    private void store(Memory memory) {
        try {
            Memory stored = memoryService.update(joi, memory);
            if (stored == null) {
                return;
            }
            dataView.refreshItem(memory);
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    /** A new memory, at the top and open, its name focused. */
    private void add() {
        try {
            Memory memory = memoryService.create(joi);
            memories.addFirst(memory);
            dataView.refreshAll();
            grid.setDetailsVisible(memory, true);
            grid.scrollToStart();
            editor(memory).name.focus();
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    /** A row was checked or unchecked; with shift the rows from the one before take its state. */
    private void toggled(Memory memory, boolean selected, boolean shift) {
        if (shift && anchor != null) {
            int from = dataView.getItemIndex(anchor).orElse(-1);
            int to = dataView.getItemIndex(memory).orElse(-1);
            if (from >= 0 && to >= 0) {
                Memory[] range = dataView.getItems()
                        .skip(Math.min(from, to))
                        .limit(Math.abs(from - to) + 1L)
                        .toArray(Memory[]::new);
                if (selected) {
                    selection.selectItems(range);
                } else {
                    selection.deselectItems(range);
                }
            }
        }
        anchor = memory;
    }

    private void deleteSelected() {
        List<Memory> selected = new ArrayList<>(grid.getSelectedItems());
        if (selected.isEmpty()) {
            return;
        }
        ConfirmDialog.show(String.format(loc.getValue(J_L.MSG_CONFIRM_DELETE_MEMORIES), selected.size()), () -> delete(selected));
    }

    private void delete(List<Memory> deleted) {
        try {
            memoryService.deleteMemories(joi, deleted.stream().map(Memory::getId).toList());
            for (Memory memory : deleted) {
                selection.deselect(memory);
                editors.remove(memory.getId());
                if (memory.equals(anchor)) {
                    anchor = null;
                }
            }
            memories.removeAll(deleted);
            dataView.refreshAll();
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    /** The memories shown, newest first, for tests. */
    List<Memory> getMemories() {
        return memories;
    }

    Grid<Memory> getGrid() {
        return grid;
    }

    /** A click on a row's checkbox, as the browser reports it, for tests. */
    void toggleForTest(Memory memory, boolean shift) {
        boolean selected = !grid.getSelectedItems().contains(memory);
        if (selected) {
            selection.select(memory);
        } else {
            selection.deselect(memory);
        }
        toggled(memory, selected, shift);
    }

    /** Adds a memory as the button does, for tests. */
    Memory addForTest() {
        add();
        return memories.getFirst();
    }

    /** Edits a memory as its fields do, for tests. */
    void editForTest(Memory memory, String name, MemoryImportance importance, String keywords, String text) {
        memory.setName(name);
        memory.setImportance(importance);
        memory.setKeywords(keywords);
        memory.setText(text);
        store(memory);
    }

    /** Deletes the checked memories as the confirmed button does, for tests. */
    void deleteSelectedForTest() {
        delete(new ArrayList<>(grid.getSelectedItems()));
    }
}
