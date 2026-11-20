package com.github.enerccio.joi.ui.tools;

import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.service.WebToolSettingsService;
import com.github.enerccio.joi.domain.tools.ToolScope;
import com.github.enerccio.joi.domain.web.SearchResult;
import com.github.enerccio.joi.domain.web.SearchSource;
import com.github.enerccio.joi.domain.web.SearchSourceType;
import com.github.enerccio.joi.domain.web.WebException;
import com.github.enerccio.joi.domain.web.WebSearchConfig;
import com.github.enerccio.joi.loc.J_L;
import com.github.enerccio.joi.ui.JoiCssStyles;
import com.github.enerccio.marginalia.CssStyles;
import com.github.enerccio.marginalia.loc.L;
import com.github.enerccio.marginalia.loc.Localization;
import com.github.enerccio.marginalia.ui.widgets.DialogFocus;
import com.github.enerccio.marginalia.ui.widgets.DialogShortcuts;
import com.github.enerccio.marginalia.utils.UIUtils;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * One search source: its kind, address, API key, the sites it is limited to, a proxy of its own, and a test that searches
 * with it as it is now (not saved yet). The key is never shown: a saved one is only said to be there; typing replaces it.
 */
@Configurable
public class WebSourceDialog extends Dialog {

    @Autowired
    private Localization loc;

    @Autowired
    private WebToolSettingsService settings;

    private final ToolScope scope;
    private final Joi joi;
    private final SearchSource source;
    private final boolean keySaved;
    private final Supplier<WebSearchConfig> options;
    private final Supplier<String> proxyAuth;
    private final BiConsumer<SearchSource, String> onSave;

    private TextField name;
    private Select<SearchSourceType> type;
    private Checkbox enabled;
    private Checkbox local;
    private TextField url;
    private PasswordField key;
    private Checkbox removeKey;
    private TextField engineId;
    private TextArea sites;
    private TextField proxy;
    private TextField query;
    private Span result;

    /**
     * @param source    the source to edit, null for a new one
     * @param keySaved  whether the source has a key saved
     * @param options   the options of the page as they are now (the timeout, the proxy...), for the test
     * @param proxyAuth the proxy login typed on the page, blank for none (the saved one is used then)
     * @param onSave    the source, and the change of its key: null keeps it, blank removes it, else the new one
     */
    public WebSourceDialog(ToolScope scope, Joi joi, SearchSource source, boolean keySaved, Supplier<WebSearchConfig> options,
                           Supplier<String> proxyAuth, BiConsumer<SearchSource, String> onSave) {
        this.scope = scope;
        this.joi = joi;
        this.source = source;
        this.keySaved = keySaved;
        this.options = options;
        this.proxyAuth = proxyAuth;
        this.onSave = onSave;
    }

    public void create() {
        setHeaderTitle(loc.getValue(J_L.LABEL_SOURCE));
        setWidth("min(44em, 95vw)");
        setCloseOnOutsideClick(false);

        name = new TextField(loc.getValue(L.LABEL_NAME));
        name.setValue(source == null ? "" : StringUtils.defaultString(source.name()));
        type = new Select<>();
        type.setLabel(loc.getValue(J_L.LABEL_SOURCE_TYPE));
        type.setItems(SearchSourceType.values());
        type.setItemLabelGenerator(kind -> label(loc, kind));
        type.setWidth("18rem");
        type.setValue(source == null ? SearchSourceType.DDGS : source.type());
        HorizontalLayout head = new HorizontalLayout(name, type);
        head.setPadding(false);
        head.setWrap(true);

        enabled = new Checkbox(loc.getValue(J_L.LABEL_SOURCE_ENABLED), source == null || source.enabled());
        local = new Checkbox(loc.getValue(J_L.LABEL_SOURCE_LOCAL), source != null && source.local());
        HorizontalLayout flags = new HorizontalLayout(enabled, local);
        flags.setPadding(false);

        url = new TextField(loc.getValue(J_L.LABEL_SOURCE_URL));
        url.setValue(source == null ? "" : StringUtils.defaultString(source.url()));
        url.setHelperText(loc.getValue(J_L.HELP_SOURCE_URL));
        url.setWidthFull();
        key = new PasswordField(loc.getValue(J_L.LABEL_SOURCE_API_KEY));
        key.setHelperText(keySaved ? loc.getValue(J_L.HELP_SOURCE_KEY_SAVED) : null);
        key.setWidthFull();
        key.setAutocomplete(com.vaadin.flow.component.textfield.Autocomplete.OFF);
        removeKey = new Checkbox(loc.getValue(J_L.LABEL_SOURCE_REMOVE_KEY));
        removeKey.setVisible(keySaved);
        engineId = new TextField(loc.getValue(J_L.LABEL_SOURCE_ENGINE_ID));
        engineId.setValue(source == null ? "" : StringUtils.defaultString(source.engineId()));
        engineId.setWidthFull();
        sites = new TextArea(loc.getValue(J_L.LABEL_SOURCE_SITES));
        sites.setValue(source == null ? "" : String.join("\n", source.sites()));
        sites.setHelperText(loc.getValue(J_L.HELP_SOURCE_SITES));
        sites.setWidthFull();
        sites.setMinRows(2);
        proxy = new TextField(loc.getValue(J_L.LABEL_SOURCE_PROXY));
        proxy.setValue(source == null ? "" : StringUtils.defaultString(source.proxy()));
        proxy.setWidthFull();
        type.addValueChangeListener(e -> kindChanged());

        query = new TextField(loc.getValue(J_L.LABEL_TEST_QUERY));
        query.setValue("Joi assistant");
        Button test = new Button(loc.getValue(J_L.LABEL_TEST), e -> test());
        HorizontalLayout testRow = new HorizontalLayout(query, test);
        testRow.setPadding(false);
        testRow.setAlignItems(com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment.END);
        result = new Span();
        result.addClassName(JoiCssStyles.TEST_RESULT);

        VerticalLayout content = new VerticalLayout(head, flags, url, key, removeKey, engineId, sites, proxy, testRow, result);
        content.setPadding(false);
        add(content);
        kindChanged();

        Button cancel = new Button(loc.getValue(L.LABEL_CANCEL), e -> close());
        Button save = new Button(loc.getValue(L.LABEL_SAVE), e -> save());
        save.setThemeName("primary");
        getFooter().add(cancel, save);
        DialogFocus.onOpen(this, name);
        DialogShortcuts.submitWith(save, name, url, engineId, proxy, query);
    }

    private void kindChanged() {
        SearchSourceType kind = type.getValue();
        key.setVisible(kind.needsKey());
        removeKey.setVisible(kind.needsKey() && keySaved);
        engineId.setVisible(kind == SearchSourceType.GOOGLE);
        url.setRequired(kind == SearchSourceType.SEARXNG);
    }

    /** The source as typed; its id is the one it had, else a new one. */
    private SearchSource draft() {
        List<String> domains = Arrays.stream(sites.getValue().split("[\\s,]+")).map(String::strip).filter(s -> !s.isEmpty()).toList();
        SearchSourceType kind = type.getValue();
        return new SearchSource(source == null ? "s" + UUID.randomUUID().toString().substring(0, 8) : source.id(),
                StringUtils.defaultIfBlank(name.getValue().strip(), label(loc, kind)), kind, enabled.getValue(), local.getValue(),
                StringUtils.trimToNull(url.getValue()), kind == SearchSourceType.GOOGLE ? StringUtils.trimToNull(engineId.getValue()) : null,
                domains, StringUtils.trimToNull(proxy.getValue()));
    }

    private void test() {
        SearchSource draft = draft();
        long started = System.nanoTime();
        result.removeClassName(CssStyles.TEXT_ERROR);
        try {
            String typed = kindTyped(draft);
            SearchResult found = settings.testSearch(scope, joi, options.get(), draft, typed, proxyAuth.get(),
                    StringUtils.defaultIfBlank(query.getValue().strip(), "Joi"));
            long ms = (System.nanoTime() - started) / 1_000_000;
            String first = found.hits().isEmpty() ? "-" : found.hits().getFirst().title();
            result.setText(String.format(loc.getValue(J_L.MSG_TEST_RESULTS), found.hits().size(), ms, first));
            result.addClassName(CssStyles.TEXT_SUCCESS);
            result.removeClassName(CssStyles.TEXT_ERROR);
        } catch (WebException e) {
            result.setText(String.format(loc.getValue(J_L.MSG_TEST_FAILED), e.getMessage()));
            result.addClassName(CssStyles.TEXT_ERROR);
            result.removeClassName(CssStyles.TEXT_SUCCESS);
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    /** The key typed, null when none (the saved one is used by the test). */
    private String kindTyped(SearchSource draft) {
        return draft.type().needsKey() && !key.isEmpty() ? key.getValue() : null;
    }

    private void save() {
        SearchSource draft = draft();
        boolean invalid = false;
        if (draft.type() == SearchSourceType.SEARXNG && draft.url() == null) {
            url.setErrorMessage(loc.getValue(J_L.MSG_SOURCE_NEEDS_URL));
            url.setInvalid(true);
            invalid = true;
        }
        boolean hasKey = !key.isEmpty() || (keySaved && !removeKey.getValue());
        if (draft.type().needsKey() && !hasKey) {
            key.setErrorMessage(loc.getValue(J_L.MSG_SOURCE_NEEDS_KEY));
            key.setInvalid(true);
            invalid = true;
        }
        if (draft.type() == SearchSourceType.GOOGLE && draft.engineId() == null) {
            engineId.setErrorMessage(loc.getValue(J_L.MSG_SOURCE_NEEDS_ENGINE));
            engineId.setInvalid(true);
            invalid = true;
        }
        if (invalid) {
            return;
        }
        String change = !draft.type().needsKey() ? (keySaved ? "" : null) : !key.isEmpty() ? key.getValue() : removeKey.getValue() ? "" : null;
        onSave.accept(draft, change);
        close();
    }

    /** The label of a kind, for the sources grid. */
    public static String label(Localization loc, SearchSourceType kind) {
        return loc.getValue(switch (kind) {
            case DDGS -> J_L.LABEL_SOURCE_TYPE_DDGS;
            case SEARXNG -> J_L.LABEL_SOURCE_TYPE_SEARXNG;
            case GOOGLE -> J_L.LABEL_SOURCE_TYPE_GOOGLE;
            case BRAVE -> J_L.LABEL_SOURCE_TYPE_BRAVE;
            case TAVILY -> J_L.LABEL_SOURCE_TYPE_TAVILY;
            case PERPLEXITY -> J_L.LABEL_SOURCE_TYPE_PERPLEXITY;
        });
    }
}
